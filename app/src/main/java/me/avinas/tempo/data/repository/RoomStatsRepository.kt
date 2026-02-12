package me.avinas.tempo.data.repository

import android.util.Log
import android.util.LruCache
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.stats.*
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import me.avinas.tempo.domain.insights.InsightGenerator
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.AudioFeaturesStats
import me.avinas.tempo.data.stats.MoodTrend
import me.avinas.tempo.data.stats.TempoBucket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Implementation of StatsRepository with in-memory caching and background processing.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This repository serves data ONLY from the local database.
 * It NEVER makes external API calls. All API data is fetched by
 * EnrichmentWorker in background and stored in database first.
 * 
 * Flow:
 * 1. ViewModels call this repository to get stats
 * 2. Repository queries the local Room database
 * 3. Results are cached in memory for fast repeated access
 * 4. Background EnrichmentWorker keeps the database fresh
 * 
 * =====================================================
 * 
 * Features:
 * - LRU cache with TTL (Time To Live) for efficient memory management
 * - Smart invalidation when new listening events are added
 * - Background computation for heavy calculations
 * - Thread-safe cache access
 */
@Singleton
class RoomStatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val insightGenerator: InsightGenerator,
    private val albumDao: AlbumDao,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val spotifyEnrichmentService: me.avinas.tempo.data.enrichment.SpotifyEnrichmentService,
    private val iTunesEnrichmentService: me.avinas.tempo.data.enrichment.ITunesEnrichmentService,
    private val userPreferencesDao: UserPreferencesDao
) : StatsRepository {

    companion object {
        private const val TAG = "StatsRepository"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 64 // LRU cache max entries
        private const val CACHE_SIZE_BYTES = 4 * 1024 * 1024 // 4MB max cache memory
    }

    // Thread-safe LRU cache with expiration
    private val cache = object : LruCache<String, CachedValue<*>>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedValue<*>): Int = 1
    }
    private val cacheMutex = Mutex()
    
    // Session cache to prevent redundant API calls for artist images within the same session
    // This is cleared only on app restart
    private val artistImageSearchCache = mutableSetOf<String>()
    
    // Flow to notify UI of metadata updates (album art, etc.)
    private val _metadataUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    
    override fun observeMetadataUpdates(): kotlinx.coroutines.flow.Flow<Unit> = _metadataUpdateFlow.asSharedFlow()
    
    /**
     * Call this when track metadata (album art, etc.) is updated by enrichment.
     * This will trigger UI refresh in components observing metadata updates.
     */
    fun notifyMetadataUpdate() {
        _metadataUpdateFlow.tryEmit(Unit)
        Log.d(TAG, "Metadata update emitted")
    }

    // Track last event timestamp for smart invalidation
    @Volatile
    private var lastKnownEventTimestamp: Long = 0
    
    // Cache hit/miss stats for debugging
    @Volatile
    private var cacheHits: Long = 0
    @Volatile
    private var cacheMisses: Long = 0

    // =====================
    // Cache Management
    // =====================

    private data class CachedValue<T>(
        val value: T,
        val timestamp: Long = System.currentTimeMillis(),
        val eventTimestamp: Long // Last event timestamp when cached
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
        fun isStale(currentEventTimestamp: Long): Boolean = eventTimestamp < currentEventTimestamp
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> getCached(
        key: String,
        compute: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val cached = cache.get(key) as? CachedValue<T>
        
        // Return cached value if valid and not stale due to new events
        if (cached != null && !cached.isExpired() && !cached.isStale(lastKnownEventTimestamp)) {
            cacheHits++
            return@withContext cached.value
        }

        cacheMisses++
        
        // Compute new value
        val value = compute()
        
        // Store in cache
        cacheMutex.withLock {
            cache.put(key, CachedValue(value, eventTimestamp = lastKnownEventTimestamp))
        }
        
        // Log cache stats periodically
        if ((cacheHits + cacheMisses) % 100 == 0L) {
            val hitRate = if (cacheHits + cacheMisses > 0) {
                (cacheHits * 100) / (cacheHits + cacheMisses)
            } else 0
            Log.d(TAG, "Cache stats: hits=$cacheHits, misses=$cacheMisses, hitRate=$hitRate%, size=${cache.size()}")
        }
        
        value
    }
    
    /**
     * Get cached value without computing if missing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> peekCached(key: String): T? {
        val cached = cache.get(key) as? CachedValue<T>
        return if (cached != null && !cached.isExpired() && !cached.isStale(lastKnownEventTimestamp)) {
            cached.value
        } else null
    }

    override fun invalidateCache() {
        cache.evictAll()
        lastKnownEventTimestamp = System.currentTimeMillis()
        cacheHits = 0
        cacheMisses = 0
        Log.d(TAG, "Cache invalidated")
        // Also notify UI of potential metadata updates
        notifyMetadataUpdate()
    }
    
    /**
     * Invalidate specific cache keys matching a pattern.
     */
    private fun invalidateCachePattern(pattern: String) {
        val snapshot = cache.snapshot()
        val keysToRemove = snapshot.keys.filter { key -> key.contains(pattern) }
        keysToRemove.forEach { key -> cache.remove(key) }
    }

    override fun invalidateCache(timeRange: TimeRange) {
        // Remove all cache entries for this time range
        val snapshot = cache.snapshot()
        val keysToRemove = snapshot.keys.filter { key -> key.contains(timeRange.name) }
        keysToRemove.forEach { key -> cache.remove(key) }
        Log.d(TAG, "Cache invalidated for $timeRange: ${keysToRemove.size} entries removed")
    }

    override fun clearArtistImageSearchCache() {
        artistImageSearchCache.clear()
        Log.d(TAG, "Artist image search cache cleared")
    }

    /**
     * Call this when a new listening event is added to trigger smart invalidation.
     */
    fun onNewListeningEvent(timestamp: Long) {
        lastKnownEventTimestamp = timestamp
        // Invalidate TODAY and THIS_WEEK caches immediately as they're most affected
        invalidateCache(TimeRange.TODAY)
        invalidateCache(TimeRange.THIS_WEEK)
        // Also invalidate THIS_MONTH for near real-time updates
        invalidateCache(TimeRange.THIS_MONTH)
        // Invalidate THIS_YEAR and ALL_TIME to ensure Spotlight (which defaults to THIS_YEAR) 
        // and overall stats are always fresh when top songs change
        invalidateCache(TimeRange.THIS_YEAR)
        invalidateCache(TimeRange.ALL_TIME)
        // Clear artist details cache entries for real-time updates
        val snapshot = cache.snapshot()
        val artistCacheKeys = snapshot.keys.filter { key -> key.startsWith("artist_details") || key.startsWith("top_artists") }
        artistCacheKeys.forEach { key -> cache.remove(key) }
        Log.d(TAG, "Cache invalidated for new event at $timestamp: cleared ${artistCacheKeys.size} artist cache entries, cache size=${cache.size()}")
    }

    // =====================
    // Overview Stats
    // =====================

    override suspend fun getListeningOverview(timeRange: TimeRange): ListeningOverview {
        // Get user content filtering preferences for cache key
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "overview_${timeRange.name}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}"
        return getCached(key) {
            computeListeningOverview(timeRange)
        }
    }

    override suspend fun getInsights(timeRange: TimeRange): List<InsightCardData> = withContext(Dispatchers.IO) {
        val (startTime, endTime) = getTimeRangeBounds(timeRange)
        
        try {
            // Fetch raw JSONs and aggregate in memory
            val moodRawList = statsDao.getMoodRawData(startTime, endTime)
            val moodStats = calculateMoodAggregates(moodRawList)
            
            val bingeSessions = statsDao.getBingeListeningSessions(startTime, endTime)
            val discoveryTrends = statsDao.getNewArtistDiscoveryTrend(startTime, endTime)
            val hourlyDistribution = statsDao.getHourlyDistribution(startTime, endTime)
            val dayOfWeekDistribution = statsDao.getDayOfWeekDistribution(startTime, endTime)
            
            // New Data Points for Dynamic Feed
            val listeningStreak = getListeningStreak()
            val topGenres = getTopGenres(timeRange, limit = 1) // Just need top one
            val engagementStats = getEngagementStats(timeRange)

            insightGenerator.generateInsights(
                moodStats = moodStats,
                bingeSessions = bingeSessions,
                discoveryTrends = discoveryTrends,
                hourlyDistribution = hourlyDistribution,
                dayOfWeekDistribution = dayOfWeekDistribution,
                listeningStreak = listeningStreak,
                topGenres = topGenres,
                engagementStats = engagementStats,
                timeRange = timeRange
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    private fun calculateMoodAggregates(rawList: List<MoodRawData>): me.avinas.tempo.data.stats.MoodAggregates? {
        if (rawList.isEmpty()) return null
        
        var totalValence = 0.0
        var totalEnergy = 0.0
        var totalDanceability = 0.0
        var totalTempo = 0.0
        var totalAcousticness = 0.0
        var realCount = 0
        var estimatedCount = 0
        var tempoRealCount = 0 // Only count tempo from verified sources
        var failureCount = 0
        
        rawList.forEach { item ->
            var hasData = false
            var isRealData = false
            
            // Try reading from JSON (Spotify/ReccoBeats features - REAL DATA)
            if (!item.json.isNullOrBlank()) {
                try {
                    val obj = org.json.JSONObject(item.json)
                    // Check for minimal required fields to ensure data integrity
                    if (obj.has("energy") && obj.has("valence")) {
                        totalValence += obj.optDouble("valence", 0.0)
                        totalEnergy += obj.optDouble("energy", 0.0)
                        totalDanceability += obj.optDouble("danceability", 0.0)
                        totalTempo += obj.optDouble("tempo", 0.0)
                        totalAcousticness += obj.optDouble("acousticness", 0.0)
                        hasData = true
                        isRealData = true
                        if (obj.has("tempo") && obj.optDouble("tempo", 0.0) > 0) {
                            tempoRealCount++
                        }
                    }
                } catch (e: Exception) {
                    // Log warning but allow fallback to proceed
                    Log.w(TAG, "Failed to parse audio features JSON, attempting fallback", e)
                }
            }
            
            // Fallback estimation if no real data found (ESTIMATED DATA)
            if (!hasData) {
                try {
                    val tags = item.tags?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
                    val genres = item.genres?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
                    
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        totalValence += TagBasedMoodAnalyzer.analyzeValence(tags, genres).toDouble()
                        totalEnergy += TagBasedMoodAnalyzer.analyzeEnergy(tags, genres).value.toDouble()
                        totalDanceability += TagBasedMoodAnalyzer.analyzeDanceability(tags, genres).toDouble()
                        totalAcousticness += TagBasedMoodAnalyzer.analyzeAcousticness(tags, genres).toDouble()
                        // NOTE: DO NOT estimate Tempo - it cannot be reliably inferred from genre
                        hasData = true
                        // isRealData remains false - this is estimated
                        
                        // Log sporadic debug info for fallback validation
                        if (estimatedCount < 3) {
                             Log.d(TAG, "Estimated mood for track: energy=${TagBasedMoodAnalyzer.analyzeEnergy(tags, genres).value} (tags: ${tags.take(3)})")
                        }
                    }
                } catch (e: Exception) {
                    // Catch unexpected errors during tag analysis to prevent crashing the whole batch
                    Log.e(TAG, "Error during mood estimation fallback", e)
                    failureCount++
                }
            }
            
            if (hasData) {
                if (isRealData) realCount++ else estimatedCount++
            }
        }
        
        if (failureCount > 0) {
            Log.w(TAG, "Mood aggregation completed with $failureCount failures out of ${rawList.size} items")
        }
        
        val totalCount = realCount + estimatedCount
        if (totalCount == 0) return null
        
        Log.d(TAG, "Mood stats: $realCount real + $estimatedCount estimated = $totalCount total")
        
        return me.avinas.tempo.data.stats.MoodAggregates(
            avg_valence = (totalValence / totalCount).toFloat(),
            avg_energy = (totalEnergy / totalCount).toFloat(),
            avg_danceability = (totalDanceability / totalCount).toFloat(),
            // Only average Tempo if we have real data; null otherwise
            avg_tempo = if (tempoRealCount > 0) (totalTempo / tempoRealCount).toFloat() else null,
            avg_acousticness = (totalAcousticness / totalCount).toFloat(),
            sample_size = totalCount,
            real_sample_size = realCount,
            estimated_sample_size = estimatedCount
        )
    }

    override fun observeListeningOverview(timeRange: TimeRange): Flow<ListeningOverview> = 
        listeningEventDao.all()
            .map { _ -> computeListeningOverview(timeRange) }
            .flowOn(Dispatchers.IO)

    private suspend fun computeListeningOverview(timeRange: TimeRange): ListeningOverview {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()
        
        // Get user content filtering preferences
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val filterPodcasts = prefs.filterPodcasts
        val filterAudiobooks = prefs.filterAudiobooks

        // Use combined query with content filtering to reduce database round trips
        val combinedStats = statsDao.getCombinedBasicStatsFiltered(startTime, endTime, filterPodcasts, filterAudiobooks)

        // Calculate average session duration (approximate: total time / number of days)
        val activeDays = statsDao.getActiveDaysCount(startTime, endTime).coerceAtLeast(1)
        val avgSessionDuration = combinedStats.totalTimeMs / activeDays

        return ListeningOverview(
            totalListeningTimeMs = combinedStats.totalTimeMs,
            totalPlayCount = combinedStats.playCount,
            uniqueTracksCount = combinedStats.uniqueTracks,
            uniqueArtistsCount = combinedStats.uniqueArtists,
            uniqueAlbumsCount = combinedStats.uniqueAlbums,
            averageSessionDurationMs = avgSessionDuration,
            longestSessionMs = calculateLongestSession(startTime, endTime), // Use actual session calculation
            timeRange = timeRange
        )

    }

    /**
     * Calculate the longest continuous listening session in a time range.
     * A session is defined as a sequence of songs with less than 20 minutes gap between them.
     */
    private suspend fun calculateLongestSession(startTime: Long, endTime: Long): Long {
        val points = listeningEventDao.getSessionPointsInRange(startTime, endTime)
        if (points.isEmpty()) return 0L

        var maxDuration = 0L
        var currentSessionDuration = 0L
        var sessionStartTime = points.first().timestamp
        var lastEndTime = points.first().timestamp + points.first().playDuration

        currentSessionDuration = points.first().playDuration
        maxDuration = currentSessionDuration

        // Threshold for session break: 20 minutes (1200000 ms)
        val sessionThreshold = 20 * 60 * 1000L

        for (i in 1 until points.size) {
            val point = points[i]
            val gap = point.timestamp - lastEndTime
            
            if (gap <= sessionThreshold) {
                // Continue session
                // Add gap time to duration? Ideally yes for "wall clock" session time, 
                // but let's stick to "active listening time" sum or "total elapsed"?
                // Let's use total elapsed time (wall clock) for the session length
                val pointEndTime = point.timestamp + point.playDuration
                currentSessionDuration = pointEndTime - sessionStartTime
                lastEndTime = pointEndTime
            } else {
                // End of session
                if (currentSessionDuration > maxDuration) {
                    maxDuration = currentSessionDuration
                }
                // Start new session
                sessionStartTime = point.timestamp
                currentSessionDuration = point.playDuration
                lastEndTime = sessionStartTime + point.playDuration
            }
        }
        
        // Check last session
        if (currentSessionDuration > maxDuration) {
            maxDuration = currentSessionDuration
        }

        return maxDuration
    }

    // =====================
    // Top Charts
    // =====================

    override suspend fun getTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int
    ): PaginatedResult<TopTrack> {
        // Get user content filtering preferences for cache key
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "top_tracks_${timeRange.name}_${sortBy.name}_${page}_${pageSize}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}"
        return getCached(key) {
            computeTopTracks(timeRange, sortBy, page, pageSize)
        }
    }

    private suspend fun computeTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int
    ): PaginatedResult<TopTrack> {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()
        val offset = page * pageSize
        
        // Get user content filtering preferences
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val filterPodcasts = prefs.filterPodcasts
        val filterAudiobooks = prefs.filterAudiobooks

        val items = when (sortBy) {
            SortBy.PLAY_COUNT -> statsDao.getTopTracksByPlayCountFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
            SortBy.TOTAL_TIME -> statsDao.getTopTracksByTimeFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
            SortBy.COMBINED_SCORE -> statsDao.getTopTracksByCombinedScoreFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
        }

        val totalCount = statsDao.getUniqueTracksPlayedCountFiltered(startTime, endTime, filterPodcasts, filterAudiobooks)

        return PaginatedResult(
            items = items,
            totalCount = totalCount,
            page = page,
            pageSize = pageSize,
            hasMore = (page + 1) * pageSize < totalCount
        )
    }

    override suspend fun getTopArtists(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int
    ): PaginatedResult<TopArtist> {
        // Get user content filtering preferences for cache key
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "top_artists_${timeRange.name}_${sortBy.name}_${page}_${pageSize}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}"
        return getCached(key) {
            computeTopArtists(timeRange, sortBy, page, pageSize)
        }
    }

    private suspend fun computeTopArtists(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int
    ): PaginatedResult<TopArtist> {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()
        val offset = page * pageSize
        
        // Get user content filtering preferences
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val filterPodcasts = prefs.filterPodcasts
        val filterAudiobooks = prefs.filterAudiobooks

        // Get raw artist stats from database with content filtering
        // Limit to top 100 artists by raw play count - users rarely scroll past this
        // This significantly reduces memory and CPU usage during aggregation
        val maxArtistsToProcess = 100 + (page * pageSize) // Fetch enough for requested page + buffer
        val rawStats = statsDao.getAllArtistStatsRawFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, maxArtistsToProcess)
        
        // Split multi-artist entries and aggregate by individual artist
        val artistStatsMap = mutableMapOf<String, ArtistAggregator>()
        
        for (raw in rawStats) {
            // Parse the artist string to get individual artists
            val individualArtists = ArtistParser.getAllArtists(raw.artist)
            
            for (artistName in individualArtists) {
                val normalizedName = artistName.trim()
                if (normalizedName.isBlank()) continue
                
                val aggregator = artistStatsMap.getOrPut(normalizedName) { 
                    ArtistAggregator(normalizedName) 
                }
                aggregator.addStats(raw)
            }
        }
        
        // Convert to TopArtist list and sort
        val sortedArtists = artistStatsMap.values
            .map { agg -> agg.toTopArtist() }
            .let { list ->
                when (sortBy) {
                    SortBy.PLAY_COUNT -> list.sortedByDescending { it.playCount }
                    SortBy.TOTAL_TIME -> list.sortedByDescending { it.totalTimeMs }
                    SortBy.COMBINED_SCORE -> {
                        // Calculate combined score: normalize and weight (50/50)
                        val maxPlays = list.maxOfOrNull { it.playCount } ?: 1
                        val maxTime = list.maxOfOrNull { it.totalTimeMs } ?: 1L
                        list.sortedByDescending { artist ->
                            (0.5 * artist.playCount.toDouble() / maxPlays) + 
                            (0.5 * artist.totalTimeMs.toDouble() / maxTime)
                        }
                    }
                }
            }
        
        val totalCount = sortedArtists.size
        
        // Apply pagination
        val paginatedItems = sortedArtists
            .drop(offset)
            .take(pageSize)
        
        // Fetch image URLs, artist IDs, and country for each artist IN PARALLEL
        // This significantly speeds up loading by running all fetches concurrently
        val itemsWithImages = coroutineScope {
            paginatedItems.map { artist ->
                async {
                    val imageUrl = getArtistImageUrlWithFallback(artist.artist)
                    val country = getArtistCountry(artist.artist)
                    // Try to find the artist ID from the database
                    val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artist.artist)
                    val artistEntity = artistDao.getArtistByNormalizedName(normalizedName)
                        ?: artistDao.getArtistByName(artist.artist)
                    artist.copy(
                        artistId = artistEntity?.id,
                        imageUrl = imageUrl, 
                        country = country
                    )
                }
            }.awaitAll()
        }

        return PaginatedResult(
            items = itemsWithImages,
            totalCount = totalCount,
            page = page,
            pageSize = pageSize,
            hasMore = (page + 1) * pageSize < totalCount
        )
    }
    
    /**
     * Helper class for aggregating stats for individual artists
     */
    private class ArtistAggregator(val name: String) {
        var playCount: Int = 0
        var totalTimeMs: Long = 0
        val trackIds = mutableSetOf<Int>()
        var firstPlayed: Long = Long.MAX_VALUE
        var lastPlayed: Long = 0
        
        fun addStats(raw: RawArtistStats) {
            playCount += raw.playCount
            totalTimeMs += raw.totalTimeMs
            // Since uniqueTracks is per combined artist, we track it as approximation
            trackIds.add(raw.artist.hashCode()) // Use hash as proxy for unique source
            if (raw.firstPlayed < firstPlayed) firstPlayed = raw.firstPlayed
            if (raw.lastPlayed > lastPlayed) lastPlayed = raw.lastPlayed
        }
        
        fun toTopArtist(): TopArtist = TopArtist(
            artist = name,
            playCount = playCount,
            totalTimeMs = totalTimeMs,
            uniqueTracks = trackIds.size,
            firstPlayed = firstPlayed,
            lastPlayed = lastPlayed,
            imageUrl = null,  // Will be filled in later
            country = null    // Will be filled in later
        )
    }
    
    /**
     * Get artist image URL with smart fallback strategy.
     * 
     * For multi-artist tracks, we need to find the correct image for each individual artist.
     * Priority order:
     * 0. track_artists junction table lookup (most accurate - uses relational data)
     * 1. Artists table exact match (if artist has been enriched separately)
     * 2. Artists table partial match (for featured artists in multi-artist entries)
     * 3. Tracks where this artist is the ONLY/PRIMARY artist (exact match)
     * 4. Tracks where this artist is listed FIRST (e.g., "Artist, feat. Other")
     * 5. Any track containing this artist (now filters out featured contexts)
     * 6. iTunes API search (high quality, no auth required)
     * 7. Spotify API search (saves result to database for future use)
     */
    private suspend fun getArtistImageUrlWithFallback(artistName: String): String? {
        // 0. Try track_artists junction table (most accurate for properly linked artists)
        // First, try to find the artist in the artists table to get their ID
        val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artistName)
        val artistEntity = artistDao.getArtistByNormalizedName(normalizedName)
            ?: artistDao.getArtistByName(artistName)
        
        if (artistEntity != null) {
            // Try to get image from tracks linked via track_artists junction table
            val fromTrackArtists = statsDao.getArtistImageByArtistId(artistEntity.id)
            if (!fromTrackArtists.isNullOrBlank()) {
                Log.d(TAG, "Found artist image via track_artists junction table: $artistName")
                // Persist to artists table for future fast lookups
                persistImageToArtistTable(artistName, fromTrackArtists)
                return fromTrackArtists
            }
        }
        
        // 1. First try the artists table exact match (most reliable)
        val fromArtistTable = artistDao.getArtistByName(artistName)?.imageUrl
        if (!fromArtistTable.isNullOrBlank()) {
            Log.d(TAG, "Found artist image from artists table (exact): $artistName")
            return fromArtistTable
        }
        
        // 1b. Try artists table with partial match
        val fromArtistTablePartial = artistDao.getArtistByNamePartial(artistName)?.imageUrl
        if (!fromArtistTablePartial.isNullOrBlank()) {
            Log.d(TAG, "Found artist image from artists table (partial): $artistName")
            return fromArtistTablePartial
        }
        
        // 2. Try tracks where this artist is the primary/solo artist
        val asPrimaryArtist = statsDao.getArtistImageAsPrimaryArtist(artistName)
        if (!asPrimaryArtist.isNullOrBlank()) {
            Log.d(TAG, "Found artist image as primary artist: $artistName")
            // Persist to artists table for future fast lookups
            persistImageToArtistTable(artistName, asPrimaryArtist)
            return asPrimaryArtist
        }
        
        // 3. Try tracks where this artist is listed first in multi-artist string
        val asFirstArtist = statsDao.getArtistImageAsFirstArtist(artistName)
        if (!asFirstArtist.isNullOrBlank()) {
            Log.d(TAG, "Found artist image as first artist: $artistName")
            // Persist to artists table for future fast lookups
            persistImageToArtistTable(artistName, asFirstArtist)
            return asFirstArtist
        }
        
        // 4. Fallback: any track containing this artist (now excludes featured contexts)
        val fromEnriched = statsDao.getArtistImageFromEnrichedMetadata(artistName)
        if (!fromEnriched.isNullOrBlank()) {
            Log.d(TAG, "Found artist image from enriched metadata: $artistName")
            // Persist to artists table for future fast lookups
            persistImageToArtistTable(artistName, fromEnriched)
            return fromEnriched
        }
        
        
        // Check session cache - if we already searched for this artist in this session, skip API calls
        val cacheKey = artistName.lowercase().trim()
        if (cacheKey in artistImageSearchCache) {
            Log.d(TAG, "Already searched for '$artistName' this session, skipping API calls")
            return null
        }
        // Mark as searched to prevent redundant API calls
        artistImageSearchCache.add(cacheKey)
        
        // 5. Fallback: Search iTunes (High quality, no auth required)
        if (iTunesEnrichmentService.isAvailable()) {
            Log.d(TAG, "Fetching artist image from iTunes for: $artistName")
            val fromITunes = iTunesEnrichmentService.searchAndFetchArtistImage(artistName)
            if (!fromITunes.isNullOrBlank()) {
                Log.d(TAG, "Found and cached artist image from iTunes: $artistName")
                // Save to DB so we don't query again
                persistImageToArtistTable(artistName, fromITunes)
                return fromITunes
            }
        }

        // 6. Final fallback: Fetch from Spotify API and SAVE to database for future use
        // This ensures we only make the API call once per artist - subsequent requests use cached data
        if (spotifyEnrichmentService.isAvailable()) {
            Log.d(TAG, "Fetching artist image from Spotify API for: $artistName (will save to DB)")
            val fromSpotify = spotifyEnrichmentService.searchAndFetchArtistImage(artistName)
            if (!fromSpotify.isNullOrBlank()) {
                Log.d(TAG, "Found and cached artist image from Spotify: $artistName")
                // Note: searchAndFetchArtistImage already saves to DB via cacheArtistImageResult
                // But let's also update the artists table directly if the artist exists
                persistImageToArtistTable(artistName, fromSpotify)
                return fromSpotify
            }
        }
        
        Log.d(TAG, "No artist image found for: $artistName")
        return null
    }
    
    /**
     * Helper to persist artist image URL to the artists table for future fast lookups.
     */
    private suspend fun persistImageToArtistTable(artistName: String, imageUrl: String) {
        try {
            val existingArtist = artistDao.getArtistByName(artistName)
                ?: artistDao.getArtistByNormalizedName(Artist.normalizeName(artistName))
            if (existingArtist != null && existingArtist.imageUrl.isNullOrBlank()) {
                artistDao.updateImageUrl(existingArtist.id, imageUrl)
                Log.d(TAG, "Persisted image to artists table for: $artistName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist artist image to DB for: $artistName", e)
        }
    }
    
    /**
     * Get artist country from enriched metadata
     */
    private suspend fun getArtistCountry(artistName: String): String? {
        return statsDao.getArtistCountry(artistName)
    }

    override suspend fun getTopAlbums(
        timeRange: TimeRange,
        page: Int,
        pageSize: Int
    ): PaginatedResult<TopAlbum> {
        // Get user content filtering preferences
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val filterPodcasts = prefs.filterPodcasts
        val filterAudiobooks = prefs.filterAudiobooks
        val key = "top_albums_${timeRange.name}_${page}_${pageSize}_pod${if (filterPodcasts) 1 else 0}_audio${if (filterAudiobooks) 1 else 0}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp()
            val endTime = timeRange.getEndTimestamp()
            val offset = page * pageSize

            val items = statsDao.getTopAlbumsFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
            val totalCount = statsDao.getUniqueAlbumsCountFiltered(startTime, endTime, filterPodcasts, filterAudiobooks)

            PaginatedResult(
                items = items,
                totalCount = totalCount,
                page = page,
                pageSize = pageSize,
                hasMore = (page + 1) * pageSize < totalCount
            )
        }
    }

    override suspend fun getTopGenres(timeRange: TimeRange, limit: Int): List<TopGenre> {
        val key = "top_genres_${timeRange.name}_$limit"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp()
            val endTime = timeRange.getEndTimestamp()
            
            // Get raw genre data (from both genres and tags fields in enriched_metadata)
            val rawGenres = statsDao.getTopGenresRaw(startTime, endTime, limit * 3)
            
            Log.d(TAG, "Raw genres query returned ${rawGenres.size} entries for $timeRange")
            
            // Parse and aggregate genres (both genres and tags are stored as |||-delimited strings)
            val genreMap = mutableMapOf<String, GenreAccumulator>()
            
            for (raw in rawGenres) {
                // Parse the genre string - format is "pop|||rock|||indie" (|||-delimited)
                val genres = raw.genre.split("|||").filter { it.isNotBlank() }
                if (genres.isEmpty()) {
                    Log.d(TAG, "Skipping empty genre entry: '${raw.genre}'")
                    continue
                }
                
                for (genre in genres) {
                    val normalized = genre.trim().lowercase().replaceFirstChar { it.uppercase() }
                    val acc = genreMap.getOrPut(normalized) { GenreAccumulator() }
                    acc.playCount += raw.playCount
                    acc.totalTimeMs += raw.totalTimeMs
                    acc.uniqueArtists.add(raw.genre) // Using original as proxy for artist
                }
            }
            
            val result = genreMap.entries
                .map { (genre, acc) ->
                    TopGenre(genre, acc.playCount, acc.totalTimeMs, acc.uniqueArtists.size)
                }
                .sortedByDescending { it.playCount }
                .take(limit)
            
            Log.d(TAG, "Processed genres: ${result.size} unique genres, top: ${result.take(3).map { it.genre }}")
            
            result
        }
    }

    private class GenreAccumulator {
        var playCount: Int = 0
        var totalTimeMs: Long = 0
        val uniqueArtists: MutableSet<String> = mutableSetOf()
    }

    // =====================
    // Temporal Analysis
    // =====================

    override suspend fun getHourlyDistribution(timeRange: TimeRange): List<HourlyDistribution> {
        val key = "hourly_dist_${timeRange.name}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp()
            val endTime = timeRange.getEndTimestamp()
            
            val result = statsDao.getHourlyDistribution(startTime, endTime)
            
            // Fill in missing hours with zero values
            val hourMap = result.associateBy { it.hour }
            (0..23).map { hour ->
                hourMap[hour] ?: HourlyDistribution(hour, 0, 0)
            }
        }
    }

    override suspend fun getDayOfWeekDistribution(timeRange: TimeRange): List<DayOfWeekDistribution> {
        val key = "dow_dist_${timeRange.name}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp()
            val endTime = timeRange.getEndTimestamp()
            
            val result = statsDao.getDayOfWeekDistribution(startTime, endTime)
            
            // Fill in missing days with zero values
            val dayMap = result.associateBy { it.dayOfWeek }
            (1..7).map { day ->
                dayMap[day] ?: DayOfWeekDistribution(day, 0, 0)
            }
        }
    }

    override suspend fun getDailyListening(timeRange: TimeRange, limit: Int): List<DailyListening> {
        val key = "daily_${timeRange.name}_$limit"
        return getCached(key) {
            statsDao.getDailyListening(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp(),
                limit
            )
        }
    }

    override suspend fun getMonthlyListening(timeRange: TimeRange): List<MonthlyListening> {
        val key = "monthly_${timeRange.name}"
        return getCached(key) {
            statsDao.getMonthlyListening(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
        }
    }

    override suspend fun getListeningStreak(): ListeningStreak {
        val key = "streak"
        return getCached(key) {
            computeListeningStreak()
        }
    }

    private suspend fun computeListeningStreak(): ListeningStreak {
        val dates = statsDao.getAllListeningDates()
        
        if (dates.isEmpty()) {
            return ListeningStreak(0, 0, null, null, null, 0)
        }

        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val parsedDates = dates.mapNotNull { 
            try { LocalDate.parse(it, formatter) } catch (e: Exception) { null }
        }.sorted()

        if (parsedDates.isEmpty()) {
            return ListeningStreak(0, 0, null, null, null, 0)
        }

        // Calculate current streak
        val today = LocalDate.now()
        var currentStreak = 0
        var currentStreakStart: LocalDate? = null
        var checkDate = today

        for (i in parsedDates.indices.reversed()) {
            val date = parsedDates[i]
            val daysDiff = ChronoUnit.DAYS.between(date, checkDate)
            
            if (daysDiff == 0L || daysDiff == 1L) {
                currentStreak++
                currentStreakStart = date
                checkDate = date
            } else if (daysDiff > 1) {
                break
            }
        }

        // Calculate longest streak
        var longestStreak = 0
        var longestStreakStart: LocalDate? = null
        var longestStreakEnd: LocalDate? = null
        var tempStreak = 1
        var tempStart = parsedDates.first()

        for (i in 1 until parsedDates.size) {
            val daysDiff = ChronoUnit.DAYS.between(parsedDates[i - 1], parsedDates[i])
            
            if (daysDiff == 1L) {
                tempStreak++
            } else {
                if (tempStreak > longestStreak) {
                    longestStreak = tempStreak
                    longestStreakStart = tempStart
                    longestStreakEnd = parsedDates[i - 1]
                }
                tempStreak = 1
                tempStart = parsedDates[i]
            }
        }

        // Check final streak
        if (tempStreak > longestStreak) {
            longestStreak = tempStreak
            longestStreakStart = tempStart
            longestStreakEnd = parsedDates.last()
        }

        return ListeningStreak(
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            currentStreakStartDate = currentStreakStart?.toString(),
            longestStreakStartDate = longestStreakStart?.toString(),
            longestStreakEndDate = longestStreakEnd?.toString(),
            totalActiveDays = parsedDates.size
        )
    }

    override suspend fun getMostActiveHour(timeRange: TimeRange): HourlyDistribution? {
        val key = "most_active_hour_${timeRange.name}"
        return getCached(key) {
            statsDao.getMostActiveHour(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
        }
    }

    override suspend fun getMostActiveDay(timeRange: TimeRange): DayOfWeekDistribution? {
        val key = "most_active_day_${timeRange.name}"
        return getCached(key) {
            statsDao.getMostActiveDay(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
        }
    }

    // =====================
    // Discovery Metrics
    // =====================

    override suspend fun getDiscoveryStats(timeRange: TimeRange): DiscoveryStats {
        val key = "discovery_${timeRange.name}"
        return getCached(key) {
            computeDiscoveryStats(timeRange)
        }
    }

    private suspend fun computeDiscoveryStats(timeRange: TimeRange): DiscoveryStats {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()

        val newArtists = statsDao.getNewArtistsCount(startTime, endTime)
        val newTracks = statsDao.getNewTracksCount(startTime, endTime)
        val repeatTracks = statsDao.getRepeatTracksCount(startTime, endTime)
        val totalPlays = statsDao.getTotalPlayCount(startTime, endTime)

        val newVsRepeatRatio = if (totalPlays > 0) {
            newTracks.toDouble() / totalPlays
        } else 0.0

        val varietyScore = getVarietyScore(timeRange)

        // Get top new artist
        val topArtists = statsDao.getTopArtistsByPlayCount(startTime, endTime, 1, 0)
        
        return DiscoveryStats(
            newArtistsCount = newArtists,
            newTracksCount = newTracks,
            repeatListensCount = repeatTracks,
            newVsRepeatRatio = newVsRepeatRatio,
            varietyScore = varietyScore,
            topNewArtist = topArtists.firstOrNull()?.artist,
            topNewTrack = null // Would need additional query
        )
    }

    override suspend fun getArtistLoyalty(
        timeRange: TimeRange,
        minPlays: Int,
        limit: Int
    ): List<ArtistLoyalty> {
        val key = "loyalty_${timeRange.name}_${minPlays}_$limit"
        return getCached(key) {
            statsDao.getArtistLoyalty(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp(),
                minPlays,
                limit
            )
        }
    }

    override suspend fun getArtistFirstListens(): List<FirstListen> {
        val key = "first_listens"
        return getCached(key) {
            statsDao.getArtistFirstListens()
        }
    }

    override suspend fun getVarietyScore(timeRange: TimeRange): Double {
        val key = "variety_${timeRange.name}"
        return getCached(key) {
            computeVarietyScore(timeRange)
        }
    }

    /**
     * Calculate Shannon entropy as a measure of genre diversity.
     * Higher values indicate more diverse listening habits.
     */
    private suspend fun computeVarietyScore(timeRange: TimeRange): Double {
        val topArtists = statsDao.getTopArtistsByPlayCount(
            timeRange.getStartTimestamp(),
            timeRange.getEndTimestamp(),
            100,
            0
        )

        if (topArtists.isEmpty()) return 0.0

        val totalPlays = topArtists.sumOf { it.playCount }
        if (totalPlays == 0) return 0.0

        // Calculate Shannon entropy
        var entropy = 0.0
        for (artist in topArtists) {
            val probability = artist.playCount.toDouble() / totalPlays
            if (probability > 0) {
                entropy -= probability * ln(probability)
            }
        }

        // Normalize to 0-100 scale (max entropy = ln(n))
        val maxEntropy = ln(topArtists.size.toDouble())
        return if (maxEntropy > 0) (entropy / maxEntropy) * 100 else 0.0
    }

    // =====================
    // Engagement Metrics
    // =====================

    override suspend fun getEngagementStats(timeRange: TimeRange): EngagementStats {
        val key = "engagement_${timeRange.name}"
        return getCached(key) {
            computeEngagementStats(timeRange)
        }
    }

    private suspend fun computeEngagementStats(timeRange: TimeRange): EngagementStats {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()

        val avgCompletion = statsDao.getAverageCompletionRate(startTime, endTime) ?: 0.0
        val fullListens = statsDao.getFullListensCount(startTime, endTime)
        val skips = statsDao.getSkipsCount(startTime, endTime)
        val totalPlays = statsDao.getTotalPlayCount(startTime, endTime)

        val partialListens = totalPlays - fullListens - skips
        val skipRate = if (totalPlays > 0) skips.toDouble() / totalPlays else 0.0

        // Detect binge sessions
        val events = statsDao.getEventsForBingeDetection(startTime, endTime)
        val bingeResult = detectBingeSessions(events)

        return EngagementStats(
            averageCompletionRate = avgCompletion,
            fullListensCount = fullListens,
            partialListensCount = partialListens,
            skipsCount = skips,
            skipRate = skipRate,
            bingeSessions = bingeResult.bingeCount,
            longestBingeArtist = bingeResult.longestBingeArtist,
            longestBingeCount = bingeResult.longestBingeLength
        )
    }

    private data class BingeResult(
        val bingeCount: Int,
        val longestBingeArtist: String?,
        val longestBingeLength: Int
    )

    /**
     * Detect binge sessions (same artist 3+ times consecutively).
     */
    private fun detectBingeSessions(events: List<BingeDetectionEvent>): BingeResult {
        if (events.size < 3) return BingeResult(0, null, 0)

        var bingeCount = 0
        var currentArtist = ""
        var currentStreak = 0
        var longestBingeArtist: String? = null
        var longestBingeLength = 0

        for (event in events) {
            if (event.artist == currentArtist) {
                currentStreak++
            } else {
                if (currentStreak >= 3) {
                    bingeCount++
                    if (currentStreak > longestBingeLength) {
                        longestBingeLength = currentStreak
                        longestBingeArtist = currentArtist
                    }
                }
                currentArtist = event.artist
                currentStreak = 1
            }
        }

        // Check final streak
        if (currentStreak >= 3) {
            bingeCount++
            if (currentStreak > longestBingeLength) {
                longestBingeLength = currentStreak
                longestBingeArtist = currentArtist
            }
        }

        return BingeResult(bingeCount, longestBingeArtist, longestBingeLength)
    }

    override suspend fun getTrackCompletionStats(
        timeRange: TimeRange,
        minPlays: Int,
        limit: Int
    ): List<TrackCompletion> {
        val key = "completion_${timeRange.name}_${minPlays}_$limit"
        return getCached(key) {
            statsDao.getTrackCompletionStats(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp(),
                minPlays,
                limit
            )
        }
    }

    override suspend fun getMostSkippedTracks(timeRange: TimeRange, limit: Int): List<TrackCompletion> {
        val key = "skipped_${timeRange.name}_$limit"
        return getCached(key) {
            statsDao.getMostSkippedTracks(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp(),
                limit
            )
        }
    }
    
    /**
     * Get track engagement using optimized single query.
     * Uses indexed columns (was_skipped, is_replay, pause_count) for efficiency.
     * This avoids loading all events into memory and computing metrics manually.
     */
    override suspend fun getTrackEngagement(trackId: Long): TrackEngagement? {
        return try {
            // Use optimized single query instead of loading all events
            val stats = statsDao.getTrackEngagementStats(trackId) ?: return null
            if (stats.playCount == 0) return null
            
            val track = trackDao.getTrackById(trackId) ?: return null
            
            TrackEngagement(
                trackId = trackId,
                title = track.title,
                artist = track.artist,
                playCount = stats.playCount,
                totalListeningTimeMs = stats.totalListeningTimeMs,
                averageCompletionPercent = stats.averageCompletionPercent?.toFloat() ?: 0f,
                fullPlaysCount = stats.fullPlaysCount,
                partialPlaysCount = stats.partialPlaysCount,
                skipsCount = stats.skipsCount,
                replayCount = stats.replayCount,
                lastPlayedTimestamp = stats.lastPlayedTimestamp ?: 0L,
                // Use new fields from optimized query
                firstPlayedTimestamp = stats.firstPlayedTimestamp ?: 0L,
                averagePauseCount = stats.averagePauseCount ?: 0f,
                totalPauseCount = stats.totalPauseCount,
                uniqueSessionsCount = stats.uniqueSessionsCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error computing track engagement for $trackId", e)
            null
        }
    }
    
    override suspend fun getEngagementOverview(timeRange: TimeRange): EngagementOverview {
        val key = "engagement_overview_${timeRange.name}"
        return getCached(key) {
            computeEngagementOverview(timeRange)
        }
    }
    
    /**
     * Compute engagement overview using optimized database queries.
     * Uses indexed columns (was_skipped, is_replay, pause_count) for efficiency.
     */
    private suspend fun computeEngagementOverview(timeRange: TimeRange): EngagementOverview {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()
        
        val totalPlays = statsDao.getTotalPlayCount(startTime, endTime)
        val totalTime = statsDao.getTotalListeningTime(startTime, endTime)
        val avgCompletion = (statsDao.getAverageCompletionRate(startTime, endTime) ?: 0.0).toFloat()
        val fullPlays = statsDao.getFullListensCount(startTime, endTime)
        val skips = statsDao.getSkipsCount(startTime, endTime)
        
        // Use optimized query with is_replay column instead of loading all events
        val totalReplays = statsDao.getReplayCount(startTime, endTime)
        
        // Get partial plays count using optimized query
        val partialPlays = statsDao.getPartialPlaysCount(startTime, endTime)
        
        // Get average pause count and unique sessions
        val avgPauseCount = statsDao.getAveragePauseCount(startTime, endTime) ?: 0f
        val uniqueSessions = statsDao.getUniqueSessionsCount(startTime, endTime)
        
        val skipRate = if (totalPlays > 0) skips.toFloat() / totalPlays else 0f
        val completionRate = if (totalPlays > 0) fullPlays.toFloat() / totalPlays else 0f
        
        // Find most engaged hour and most skipped hour
        val hourlyEngagement = computeHourlyEngagement(timeRange)
        val mostEngagedHour = hourlyEngagement.maxByOrNull { it.engagementScore }?.hour
        val mostSkippedHour = hourlyEngagement.maxByOrNull { it.skipCount }?.hour
        
        // Count binge sessions - still needs event data for sequential analysis
        val allEvents = statsDao.getEventsForBingeDetection(startTime, endTime)
        val bingeResult = detectBingeSessions(allEvents)
        
        return EngagementOverview(
            timeRange = timeRange,
            totalPlays = totalPlays,
            totalListeningTimeMs = totalTime,
            averageCompletionPercent = avgCompletion,
            totalFullPlays = fullPlays,
            totalSkips = skips,
            totalReplays = totalReplays,
            skipRate = skipRate,
            completionRate = completionRate,
            mostEngagedHour = mostEngagedHour,
            mostSkippedHour = mostSkippedHour,
            bingeSessionsCount = bingeResult.bingeCount,
            // Add new fields
            totalPartialPlays = partialPlays,
            averagePauseCount = avgPauseCount,
            uniqueSessionsCount = uniqueSessions
        )
    }
    
    override suspend fun getHourlyEngagement(timeRange: TimeRange): List<HourlyEngagement> {
        val key = "hourly_engagement_${timeRange.name}"
        return getCached(key) {
            computeHourlyEngagement(timeRange)
        }
    }
    
    private suspend fun computeHourlyEngagement(timeRange: TimeRange): List<HourlyEngagement> {
        val startTime = timeRange.getStartTimestamp()
        val endTime = timeRange.getEndTimestamp()
        
        // Get hourly distribution and completion data
        val hourlyDist = statsDao.getHourlyDistribution(startTime, endTime)
        val hourlyCompletion = statsDao.getHourlyCompletionStats(startTime, endTime)
        
        val completionMap = hourlyCompletion.associateBy { it.hour }
        
        return (0..23).map { hour ->
            val dist = hourlyDist.find { it.hour == hour }
            val completion = completionMap[hour]
            
            HourlyEngagement(
                hour = hour,
                playCount = dist?.playCount ?: 0,
                avgCompletion = completion?.avgCompletion ?: 0f,
                skipCount = completion?.skipCount ?: 0
            )
        }
    }

    // =====================
    // Spotify Audio Features (Deprecated - Nov 2024)
    // =====================

    /**
     * @deprecated Spotify's audio-features API was deprecated in November 2024.
     * Use TagBasedMoodAnalyzer with MusicBrainz tags and getTrackEngagement() 
     * for mood/energy insights based on user behavior patterns.
     */
    override suspend fun getAudioFeaturesStats(timeRange: TimeRange): AudioFeaturesStats? {
        // Note: Spotify audio-features API was deprecated in November 2024.
        // This method now returns null. Use getTrackEngagement() and 
        // TagBasedMoodAnalyzer for mood/energy insights based on MusicBrainz tags
        // and user behavior patterns.
        return null
    }

    // Kept for reference but no longer called - API deprecated
    @Suppress("UNUSED")
    private suspend fun legacyComputeAudioFeaturesStats(timeRange: TimeRange): AudioFeaturesStats? {
        val raw = statsDao.getAverageAudioFeatures(
            timeRange.getStartTimestamp(),
            timeRange.getEndTimestamp()
        ) ?: return null

        if (raw.tracks_count == 0 || raw.avg_energy == null) {
            return null
        }

        // Determine dominant mood
        val dominantMood = when {
            (raw.avg_valence ?: 0f) >= 0.6f && (raw.avg_energy ?: 0f) >= 0.6f -> "Upbeat & Happy"
            (raw.avg_valence ?: 0f) >= 0.6f -> "Happy & Relaxed"
            (raw.avg_energy ?: 0f) >= 0.6f -> "Energetic"
            (raw.avg_valence ?: 0f) <= 0.4f && (raw.avg_energy ?: 0f) <= 0.4f -> "Melancholic"
            else -> "Balanced"
        }

        // Get mood trends to determine energy trend
        val moodTrends = statsDao.getMoodTrends(
            timeRange.getStartTimestamp(),
            timeRange.getEndTimestamp()
        )
        val energyTrend = calculateTrend(moodTrends.map { it.avgEnergy })

        return AudioFeaturesStats(
            averageEnergy = raw.avg_energy ?: 0f,
            averageDanceability = raw.avg_danceability ?: 0f,
            averageValence = raw.avg_valence ?: 0f,
            averageTempo = raw.avg_tempo ?: 0f,
            averageAcousticness = raw.avg_acousticness ?: 0f,
            averageInstrumentalness = raw.avg_instrumentalness ?: 0f,
            averageSpeechiness = raw.avg_speechiness ?: 0f,
            averageLoudness = raw.avg_loudness ?: 0f,
            tracksWithFeatures = raw.tracks_count,
            dominantMood = dominantMood,
            energyTrend = energyTrend
        )
    }

    private fun calculateTrend(values: List<Float>): String {
        if (values.size < 2) return "stable"
        
        val firstHalf = values.take(values.size / 2).average()
        val secondHalf = values.drop(values.size / 2).average()
        
        val change = secondHalf - firstHalf
        return when {
            change > 0.05 -> "increasing"
            change < -0.05 -> "decreasing"
            else -> "stable"
        }
    }

    override suspend fun getMoodTrends(timeRange: TimeRange): List<MoodTrend> {
        val key = "mood_trends_${timeRange.name}"
        return getCached(key) {
            statsDao.getMoodTrends(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
        }
    }

    override suspend fun getTempoDistribution(timeRange: TimeRange): List<TempoBucket> {
        val key = "tempo_dist_${timeRange.name}"
        return getCached(key) {
            val raw = statsDao.getTempoDistributionRaw(
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
            
            raw.map { r ->
                val (min, max) = parseTempoRange(r.bucket_label)
                TempoBucket(
                    bucketLabel = r.bucket_label,
                    minTempo = min,
                    maxTempo = max,
                    trackCount = r.track_count,
                    totalPlays = r.total_plays
                )
            }
        }
    }

    private fun parseTempoRange(label: String): Pair<Int, Int> {
        return when {
            label.contains("<80") -> 0 to 80
            label.contains("80-100") -> 80 to 100
            label.contains("100-120") -> 100 to 120
            label.contains("120-140") -> 120 to 140
            label.contains("140+") -> 140 to 999
            else -> 0 to 999
        }
    }

    // =====================
    // Comparisons
    // =====================

    override suspend fun getYearOverYearComparison(currentYear: Int): YearOverYearComparison {
        val key = "yoy_$currentYear"
        return getCached(key) {
            val current = statsDao.getYearStats(currentYear.toString())
            val previous = statsDao.getYearStats((currentYear - 1).toString())

            YearOverYearComparison(
                currentYear = currentYear,
                previousYear = currentYear - 1,
                currentYearPlayCount = current.play_count,
                previousYearPlayCount = previous.play_count,
                currentYearTimeMs = current.total_time_ms,
                previousYearTimeMs = previous.total_time_ms,
                currentYearUniqueArtists = current.unique_artists,
                previousYearUniqueArtists = previous.unique_artists
            )
        }
    }

    override suspend fun getPeriodComparison(timeRange: TimeRange): PeriodComparison {
        val key = "period_comp_${timeRange.name}"
        return getCached(key) {
            val currentStart = timeRange.getStartTimestamp()
            val currentEnd = timeRange.getEndTimestamp()
            val duration = currentEnd - currentStart
            
            val previousEnd = currentStart - 1
            val previousStart = previousEnd - duration

            val currentPlayCount = statsDao.getTotalPlayCount(currentStart, currentEnd)
            val previousPlayCount = statsDao.getTotalPlayCount(previousStart, previousEnd)
            
            val currentTime = statsDao.getTotalListeningTime(currentStart, currentEnd)
            val previousTime = statsDao.getTotalListeningTime(previousStart, previousEnd)

            val playCountChange = if (previousPlayCount > 0) {
                ((currentPlayCount - previousPlayCount).toDouble() / previousPlayCount) * 100
            } else 0.0

            val timeChange = if (previousTime > 0) {
                ((currentTime - previousTime).toDouble() / previousTime) * 100
            } else 0.0

            val trending = when {
                playCountChange > 5 -> "up"
                playCountChange < -5 -> "down"
                else -> "stable"
            }

            PeriodComparison(
                currentPeriodPlayCount = currentPlayCount,
                previousPeriodPlayCount = previousPlayCount,
                currentPeriodTimeMs = currentTime,
                previousPeriodTimeMs = previousTime,
                playCountChangePercent = playCountChange,
                timeChangePercent = timeChange,
                trending = trending
            )
        }
    }

    // =====================
    // Detail Screens
    // =====================

    override suspend fun getTrackDetails(trackId: Long): TrackDetails {
        val key = "track_details_$trackId"
        return getCached(key) {
            var track = trackDao.getTrackById(trackId) ?: throw NoSuchElementException("Track not found")
            val enrichedMetadata = enrichedMetadataDao.forTrackSync(trackId)
            
            // Smart fallback strategy:
            // - EnrichedMetadata has hotlink URL (from API)
            // - Track table has local backup URL (from MediaSession bitmap)
            // - UI tries hotlink first, falls back to local if it fails
            // - If hotlink works, local file gets deleted to save storage
            
            // Get enriched hotlink URL (remote)
            val enrichedArtUrl = enrichedMetadata?.albumArtUrl 
                ?: enrichedMetadata?.albumArtUrlSmall 
                ?: enrichedMetadata?.albumArtUrlLarge
            
            // Fix HTTP to HTTPS for enriched URL
            val fixedEnrichedUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService.fixHttpUrl(enrichedArtUrl)
            
            // Fix HTTP to HTTPS for track URL (may be local backup)
            val fixedTrackUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService.fixHttpUrl(track.albumArtUrl)
            
            // Determine which is hotlink and which is local backup
            val isEnrichedRemote = fixedEnrichedUrl?.startsWith("http") == true
            val isTrackLocal = fixedTrackUrl?.startsWith("file://") == true
            
            // Strategy:
            // - If we have enriched hotlink: use it in track.albumArtUrl, keep local in localBackupArtUrl
            // - If no enriched: use local in track.albumArtUrl, no backup needed
            val (primaryUrl, backupUrl) = when {
                isEnrichedRemote && isTrackLocal -> fixedEnrichedUrl to fixedTrackUrl
                isEnrichedRemote && !isTrackLocal -> fixedEnrichedUrl to null
                !isEnrichedRemote && isTrackLocal -> fixedTrackUrl to null
                else -> (fixedEnrichedUrl ?: fixedTrackUrl) to null
            }
            
            // Update track with primary URL
            if (primaryUrl != null && primaryUrl != track.albumArtUrl) {
                track = track.copy(albumArtUrl = primaryUrl)
            }
            
            val playCount = statsDao.getTrackPlayCount(trackId)
            val totalTime = statsDao.getTrackTotalTime(trackId)
            val firstPlayed = statsDao.getTrackFirstPlayed(trackId)
            val lastPlayed = statsDao.getTrackLastPlayed(trackId)
            
            // Peak Position (All Time Rank) based on play count
            val peakRank = if (playCount > 0) {
                 statsDao.getTrackRankAllTime(playCount)
            } else null
            
            // Favorite logic: played more than 20 times
            val isFavorite = playCount > 20

            TrackDetails(
                track = track,
                playCount = playCount,
                totalTimeMs = totalTime,
                peakRank = peakRank,
                firstPlayed = firstPlayed,
                lastPlayed = lastPlayed,
                isFavorite = isFavorite,
                appleMusicUrl = enrichedMetadata?.appleMusicUrl?.takeIf { it.isNotBlank() },
                spotifyUrl = enrichedMetadata?.spotifyTrackUrl?.takeIf { it.isNotBlank() },
                localBackupArtUrl = backupUrl  // Local backup for fallback
            )
        }
    }

    override suspend fun getArtistDetails(artistId: Long): ArtistDetails {
        val key = "artist_details_$artistId"
        return getCached(key) {
            val artist = artistDao.getArtistById(artistId) ?: throw NoSuchElementException("Artist not found")
            
            Log.d(TAG, "getArtistDetails: Loading artist ID=$artistId, name='${artist.name}', " +
                "existing imageUrl=${artist.imageUrl?.take(80) ?: "null"}")
            
            // Get artist image URL with fallback strategy
            // 1. Use artist.imageUrl if available
            // 2. Try to get from enriched_metadata via track_artists join
            // 3. Try name-based fallback
            val fromArtistTable = artist.imageUrl?.takeIf { it.isNotBlank() }
            val fromEnrichedById = if (fromArtistTable == null) {
                statsDao.getArtistImageByArtistId(artistId)?.also {
                    Log.d(TAG, "getArtistDetails: Found image via track_artists join: ${it.take(80)}")
                }
            } else null
            val fromNameFallback = if (fromArtistTable == null && fromEnrichedById == null) {
                getArtistImageUrlWithFallback(artist.name)?.also {
                    Log.d(TAG, "getArtistDetails: Found image via name fallback: ${it.take(80)}")
                }
            } else null
            
            val imageUrl = fromArtistTable ?: fromEnrichedById ?: fromNameFallback
            
            if (imageUrl == null) {
                Log.w(TAG, "getArtistDetails: No image found for artist '${artist.name}' (ID=$artistId)")
            } else {
                Log.d(TAG, "getArtistDetails: Resolved image for '${artist.name}': ${imageUrl.take(80)}")
                // Update artist table with found image for future lookups
                if (artist.imageUrl.isNullOrBlank()) {
                    try {
                        artistDao.updateImageUrl(artistId, imageUrl)
                        Log.d(TAG, "getArtistDetails: Persisted image URL to artists table for ID=$artistId")
                    } catch (e: Exception) {
                        Log.w(TAG, "getArtistDetails: Failed to persist image URL", e)
                    }
                }
            }
            
            // Create artist with resolved image URL
            val artistWithImage = if (imageUrl != artist.imageUrl) {
                artist.copy(imageUrl = imageUrl)
            } else {
                artist
            }
            
            // Use ID-based queries for proper relational lookups
            val playCount = statsDao.getArtistPlayCountById(artistId)
            val totalTime = statsDao.getArtistTotalTimeById(artistId)
            val topSongs = statsDao.getTopTracksForArtistById(artistId, 10)
            val firstDiscovery = statsDao.getArtistFirstListenById(artistId)
            
            // Extended data using ID-based queries
            val uniqueAlbums = statsDao.getArtistUniqueAlbumsPlayedById(artistId)
            val uniqueTracks = statsDao.getArtistUniqueTracksPlayedById(artistId)
            val listeningDates = statsDao.getArtistListeningDatesById(artistId)
            val peakHour = statsDao.getArtistPeakListeningHourById(artistId)
            val topAlbums = statsDao.getTopAlbumsForArtistById(artistId, 5)
            
            // Country from artist table or enriched metadata
            val country = artistWithImage.country ?: statsDao.getArtistCountry(artistWithImage.name)
            
            // Use genres from artist entity or extract from enriched metadata
            val topGenres = if (artistWithImage.genres.isNotEmpty()) {
                artistWithImage.genres.take(5)
            } else {
                emptyList()
            }
            
            // Calculate mood summary from tags/genres
            val moodSummary = TagBasedMoodAnalyzer.getMoodSummary(
                tags = artistWithImage.genres, // Using genres as tags for now
                genres = topGenres
            )
            
            // Global listeners count would come from MusicBrainz/Spotify, placeholder for now
            val listenersCount: Long? = null

            ArtistDetails(
                artist = artistWithImage,
                listenersCount = listenersCount,
                personalPlayCount = playCount,
                personalTotalTimeMs = totalTime,
                firstDiscovery = firstDiscovery,
                topSongs = topSongs,
                country = country,
                uniqueAlbumsPlayed = uniqueAlbums,
                uniqueTracksPlayed = uniqueTracks,
                moodSummary = moodSummary,
                topGenres = topGenres,
                firstListenedDate = listeningDates?.first_listened,
                lastListenedDate = listeningDates?.last_listened,
                peakListeningHour = peakHour,
                topAlbums = topAlbums
            )
        }
    }

    override suspend fun getArtistDetailsByName(artistName: String): ArtistDetails? {
        val key = "artist_details_name_$artistName"
        return getCached(key) {
            // Try to find the artist in the database first by normalized name
            val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artistName)
            val existingArtist = artistDao.getArtistByNormalizedName(normalizedName)
                ?: artistDao.getArtistByName(artistName)
            
            if (existingArtist != null) {
                // Artist found in database - use ID-based queries for proper relational lookups
                Log.d(TAG, "getArtistDetailsByName: Found artist '${existingArtist.name}' (ID=${existingArtist.id}), " +
                    "existing imageUrl=${existingArtist.imageUrl?.take(80) ?: "null"}")
                
                val playCount = statsDao.getArtistPlayCountById(existingArtist.id)
                val totalTime = statsDao.getArtistTotalTimeById(existingArtist.id)
                val topSongs = statsDao.getTopTracksForArtistById(existingArtist.id, 10)
                val firstDiscovery = statsDao.getArtistFirstListenById(existingArtist.id)
                
                // Extended data using ID-based queries
                val uniqueAlbums = statsDao.getArtistUniqueAlbumsPlayedById(existingArtist.id)
                val uniqueTracks = statsDao.getArtistUniqueTracksPlayedById(existingArtist.id)
                val listeningDates = statsDao.getArtistListeningDatesById(existingArtist.id)
                val peakHour = statsDao.getArtistPeakListeningHourById(existingArtist.id)
                val topAlbums = statsDao.getTopAlbumsForArtistById(existingArtist.id, 5)
                
                // Get artist image URL with fallback strategy
                val imageUrl = existingArtist.imageUrl?.takeIf { it.isNotBlank() }
                    ?: statsDao.getArtistImageByArtistId(existingArtist.id)?.also {
                        Log.d(TAG, "getArtistDetailsByName: Found image via track_artists join")
                    }
                    ?: getArtistImageUrlWithFallback(existingArtist.name)?.also {
                        Log.d(TAG, "getArtistDetailsByName: Found image via name fallback")
                    }
                
                if (imageUrl != null) {
                    Log.d(TAG, "getArtistDetailsByName: Resolved image: ${imageUrl.take(80)}")
                    // Update artist table with found image for future lookups
                    if (existingArtist.imageUrl.isNullOrBlank()) {
                        try {
                            artistDao.updateImageUrl(existingArtist.id, imageUrl)
                            Log.d(TAG, "getArtistDetailsByName: Persisted image URL to artists table")
                        } catch (e: Exception) {
                            Log.w(TAG, "getArtistDetailsByName: Failed to persist image URL", e)
                        }
                    }
                } else {
                    Log.w(TAG, "getArtistDetailsByName: No image found for artist '${existingArtist.name}'")
                }
                
                // Create artist with resolved image URL
                val artistWithImage = if (imageUrl != existingArtist.imageUrl) {
                    existingArtist.copy(imageUrl = imageUrl)
                } else {
                    existingArtist
                }
                
                // Country from artist table or enriched metadata
                val country = artistWithImage.country ?: statsDao.getArtistCountry(artistWithImage.name)
                
                // Use genres from artist entity or extract from enriched metadata
                val topGenres = if (artistWithImage.genres.isNotEmpty()) {
                    artistWithImage.genres.take(5)
                } else {
                    emptyList()
                }
                
                // Calculate mood summary from tags/genres
                val moodSummary = TagBasedMoodAnalyzer.getMoodSummary(
                    tags = artistWithImage.genres,
                    genres = topGenres
                )
                
                ArtistDetails(
                    artist = artistWithImage,
                    listenersCount = null,
                    personalPlayCount = playCount,
                    personalTotalTimeMs = totalTime,
                    firstDiscovery = firstDiscovery,
                    topSongs = topSongs,
                    country = country,
                    uniqueAlbumsPlayed = uniqueAlbums,
                    uniqueTracksPlayed = uniqueTracks,
                    moodSummary = moodSummary,
                    topGenres = topGenres,
                    firstListenedDate = listeningDates?.first_listened,
                    lastListenedDate = listeningDates?.last_listened,
                    peakListeningHour = peakHour,
                    topAlbums = topAlbums
                )
            } else {
                // Artist not in database - use partial match queries to find tracks
                // This handles parsed individual artists from multi-artist entries
                val playCount = statsDao.getArtistPlayCountByPartialMatch(artistName)
                
                // If no plays found, this artist doesn't exist in listening history
                if (playCount == 0) return@getCached null
                
                val totalTime = statsDao.getArtistTotalTimeByPartialMatch(artistName)
                val topSongs = statsDao.getTopTracksForArtistPartialMatch(artistName, 10)
                val firstDiscovery = statsDao.getArtistFirstListenPartialMatch(artistName)
                
                // Extended data using partial match
                val uniqueAlbums = statsDao.getArtistUniqueAlbumsPlayedPartialMatch(artistName)
                val uniqueTracks = statsDao.getArtistUniqueTracksPlayedPartialMatch(artistName)
                val listeningDates = statsDao.getArtistListeningDatesPartialMatch(artistName)
                val peakHour = statsDao.getArtistPeakListeningHourPartialMatch(artistName)
                val country = statsDao.getArtistCountry(artistName) // Already uses partial match
                val topAlbums = statsDao.getTopAlbumsForArtistPartialMatch(artistName, 5)
                
                // Get image URL with fallback strategy
                val imageUrl = getArtistImageUrlWithFallback(artistName)
                
                // Create a synthetic Artist entity for artists not in the database
                val syntheticArtist = Artist(
                    id = 0, // Synthetic ID (not persisted)
                    name = artistName,
                    imageUrl = imageUrl,
                    genres = emptyList(),
                    musicbrainzId = null,
                    spotifyId = null
                )
                
                ArtistDetails(
                    artist = syntheticArtist,
                    listenersCount = null,
                    personalPlayCount = playCount,
                    personalTotalTimeMs = totalTime,
                    firstDiscovery = firstDiscovery,
                    topSongs = topSongs,
                    country = country,
                    uniqueAlbumsPlayed = uniqueAlbums,
                    uniqueTracksPlayed = uniqueTracks,
                    moodSummary = null,
                    topGenres = emptyList(),
                    firstListenedDate = listeningDates?.first_listened,
                    lastListenedDate = listeningDates?.last_listened,
                    peakListeningHour = peakHour,
                    topAlbums = topAlbums
                )
            }
        }
    }
    


    override suspend fun getAlbumDetails(albumId: Long): AlbumDetails {
        val key = "album_details_$albumId"
        return getCached(key) {
            val album = albumDao.getAlbumById(albumId) ?: throw NoSuchElementException("Album not found")
            val artist = artistDao.getArtistById(album.artistId) ?: throw NoSuchElementException("Artist not found")
            
            val playCount = statsDao.getAlbumPlayCount(album.title, artist.name)
            val totalTime = statsDao.getAlbumTotalTime(album.title, artist.name)
            
            val rawTracks = statsDao.getTracksForAlbumWithStats(album.title, artist.name)
            val tracks = rawTracks.map { 
                TrackWithStats(it.track, it.play_count, it.total_time_ms) 
            }
            
            val completionRate = if (tracks.isNotEmpty()) {
                val playedTracks = tracks.count { it.playCount > 0 }
                (playedTracks.toDouble() / tracks.size) * 100
            } else 0.0

            AlbumDetails(
                album = album,
                artistName = artist.name,
                totalPlayCount = playCount,
                totalTimeMs = totalTime,
                completionRate = completionRate,
                tracks = tracks
            )
        }
    }

    override suspend fun getTrackListeningHistory(trackId: Long, timeRange: TimeRange): List<DailyListening> {
        val key = "track_history_${trackId}_${timeRange.name}"
        return getCached(key) {
            statsDao.getTrackListeningHistory(
                trackId,
                timeRange.getStartTimestamp(),
                timeRange.getEndTimestamp()
            )
        }
    }

    private fun calculatePercentageChange(current: Number, previous: Number): Double {
        val curr = current.toDouble()
        val prev = previous.toDouble()
        
        if (prev == 0.0) return if (curr > 0) 100.0 else 0.0
        
        return ((curr - prev) / prev) * 100.0
    }

    // =====================
    // History
    // =====================

    override suspend fun getHistory(
        timeRange: TimeRange?,
        searchQuery: String?,
        startTime: Long?,
        endTime: Long?,
        includeSkips: Boolean,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        page: Int,
        pageSize: Int
    ): PaginatedResult<HistoryItem> {
        // Construct separate cache key for each filter combination
        // Note: Caching search results might be aggressive if search queries are very diverse,
        // but LRU will handle eviction. Short TTL might be better for search.
        val rangeKey = timeRange?.name ?: "custom_${startTime}_${endTime}"
        val queryKey = searchQuery?.lowercase() ?: "all"
        val skipKey = if (includeSkips) "with_skips" else "no_skips"
        val filterKey = "pod${if (filterPodcasts) 1 else 0}_audio${if (filterAudiobooks) 1 else 0}"
        val key = "history_${rangeKey}_${queryKey}_${skipKey}_${filterKey}_${page}_$pageSize"

        return getCached(key) {
            val finalStartTime = startTime ?: timeRange?.getStartTimestamp()
            val finalEndTime = endTime ?: timeRange?.getEndTimestamp()
            val offset = page * pageSize

            val items = statsDao.getHistory(
                searchQuery = searchQuery,
                startTime = finalStartTime,
                endTime = finalEndTime,
                includeSkips = includeSkips,
                filterPodcasts = filterPodcasts,
                filterAudiobooks = filterAudiobooks,
                limit = pageSize,
                offset = offset
            )

            // Total count for pagination is harder with dynamic filters.
            // For now, we assume if we got a full page, there might be more.
            // Ideally we'd have a count query in DAO with same filters.
            // Falling back to simple "hasMore" check based on result size.
            val hasMore = items.size == pageSize

            PaginatedResult(
                items = items,
                totalCount = -1, // Unknown total count
                page = page,
                pageSize = pageSize,
                hasMore = hasMore
            )
        }
    }
    
    override suspend fun getHistoryExcludingLastFm(
        searchQuery: String?,
        startTime: Long?,
        endTime: Long?,
        includeSkips: Boolean,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        page: Int,
        pageSize: Int
    ): PaginatedResult<HistoryItem> {
        val queryKey = searchQuery?.lowercase() ?: "all"
        val skipKey = if (includeSkips) "with_skips" else "no_skips"
        val filterKey = "pod${if (filterPodcasts) 1 else 0}_audio${if (filterAudiobooks) 1 else 0}"
        val key = "history_live_${queryKey}_${startTime}_${endTime}_${skipKey}_${filterKey}_${page}_$pageSize"

        return getCached(key) {
            val offset = page * pageSize

            val items = statsDao.getHistoryExcludingLastFm(
                searchQuery = searchQuery,
                startTime = startTime,
                endTime = endTime,
                includeSkips = includeSkips,
                filterPodcasts = filterPodcasts,
                filterAudiobooks = filterAudiobooks,
                limit = pageSize,
                offset = offset
            )

            val hasMore = items.size == pageSize

            PaginatedResult(
                items = items,
                totalCount = -1,
                page = page,
                pageSize = pageSize,
                hasMore = hasMore
            )
        }
    }
    
    override suspend fun getHistoryLastFmOnly(
        searchQuery: String?,
        startTime: Long?,
        endTime: Long?,
        includeSkips: Boolean,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        page: Int,
        pageSize: Int
    ): PaginatedResult<HistoryItem> {
        val queryKey = searchQuery?.lowercase() ?: "all"
        val skipKey = if (includeSkips) "with_skips" else "no_skips"
        val filterKey = "pod${if (filterPodcasts) 1 else 0}_audio${if (filterAudiobooks) 1 else 0}"
        val key = "history_lastfm_${queryKey}_${startTime}_${endTime}_${skipKey}_${filterKey}_${page}_$pageSize"

        return getCached(key) {
            val offset = page * pageSize

            val items = statsDao.getHistoryLastFmOnly(
                searchQuery = searchQuery,
                startTime = startTime,
                endTime = endTime,
                includeSkips = includeSkips,
                filterPodcasts = filterPodcasts,
                filterAudiobooks = filterAudiobooks,
                limit = pageSize,
                offset = offset
            )

            val hasMore = items.size == pageSize

            PaginatedResult(
                items = items,
                totalCount = -1,
                page = page,
                pageSize = pageSize,
                hasMore = hasMore
            )
        }
    }

    // =====================
    // Lookup Methods
    // =====================

    override suspend fun getArtistIdByName(artistName: String): Long? {
        return artistDao.getArtistByName(artistName)?.id
    }

    override suspend fun getAlbumIdByTitleAndArtist(albumTitle: String, artistName: String): Long? {
        return albumDao.getAlbumByTitleAndArtist(albumTitle, artistName)?.id
    }

    override suspend fun getArtistImageUrl(artistName: String): String? {
        return artistDao.getArtistByName(artistName)?.imageUrl
    }

    // =====================
    // Track Audio Features
    // =====================

    override suspend fun getTrackAudioFeatures(trackId: Long): TrackAudioFeatures? {
        val key = "track_audio_features_$trackId"
        return getCached(key) {
            computeTrackAudioFeatures(trackId)
        }
    }

    private suspend fun computeTrackAudioFeatures(trackId: Long): TrackAudioFeatures? {
        val metadata = enrichedMetadataDao.forTrackSync(trackId) ?: return null
        val json = metadata.audioFeaturesJson ?: return null

        return try {
            val raw = statsDao.getTrackAudioFeaturesRaw(trackId) ?: return null
            
            TrackAudioFeatures(
                energy = raw.energy ?: return null,
                danceability = raw.danceability ?: return null,
                valence = raw.valence ?: return null,
                tempo = raw.tempo ?: return null,
                acousticness = raw.acousticness ?: return null,
                instrumentalness = raw.instrumentalness ?: return null,
                speechiness = raw.speechiness ?: return null,
                liveness = raw.liveness ?: return null,
                loudness = raw.loudness ?: return null,
                key = raw.key ?: -1,
                mode = raw.mode ?: 0,
                timeSignature = raw.timeSignature ?: 4
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse audio features for track $trackId", e)
            null
        }
    }

    private fun getTimeRangeBounds(timeRange: TimeRange): Pair<Long, Long> {
        return Pair(timeRange.getStartTimestamp(), timeRange.getEndTimestamp())
    }

    override suspend fun getArtistRankPercentile(playCount: Int, timeRange: TimeRange): Double {
        val key = "artist_rank_percentile_${playCount}_${timeRange.name}"
        return getCached(key) {
            // Count how many artists have > playCount plays
            val startTime = timeRange.getStartTimestamp()
            val endTime = timeRange.getEndTimestamp()
            
            // Get total unique artists played in this period
            val totalArtists = statsDao.getUniqueArtistsPlayedCount(startTime, endTime)
            
            if (totalArtists == 0) return@getCached 0.0 // No data
            
            // Get count of artists with MORE plays than this one
            val artistsWithMorePlays = statsDao.countArtistsWithPlayCountMoreThan(playCount, startTime, endTime)
            
            // Calculate percentile: (artistsWithMorePlays / totalArtists) * 100
            // Example: 50 artists total. This artist has 100 plays. 
            // 2 artists have > 100 plays.
            // Percentile = (2 / 50) * 100 = 4.0 (Top 4%)
            
            // We add 1 to denominator to avoid division by zero (though check above handles it)
            // and to be conservative (can't be Top 0%)
            val percentile = (artistsWithMorePlays.toDouble() / totalArtists.toDouble()) * 100.0
            
            // formatting: 0.1% precision
            Math.round(percentile * 10.0) / 10.0
        }
    }

    override suspend fun getArtistDiscoveryDate(artistId: Long): Long? {
        val key = "artist_discovery_$artistId"
        return getCached(key) {
            statsDao.getArtistDiscoveryDate(artistId)
        }
    }
    
    // =====================
    // Batch Operations (Spotlight Performance)
    // =====================
    
    override suspend fun getArtistPlayCountsBatch(artistNames: List<String>): Map<String, Int> {
        if (artistNames.isEmpty()) return emptyMap()
        
        val key = "artist_playcounts_batch_${artistNames.hashCode()}"
        return getCached(key) {
            // Build result map by querying each artist
            // Note: For optimal performance, a dedicated batch DAO query would be better,
            // but this still avoids the N+1 problem in the generator loop by pre-fetching
            val result = mutableMapOf<String, Int>()
            artistNames.forEach { name ->
                // Try ID-based lookup first (faster with proper indices)
                val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(name)
                val artist = artistDao.getArtistByNormalizedName(normalizedName)
                    ?: artistDao.getArtistByName(name)
                
                val playCount = if (artist != null) {
                    statsDao.getArtistPlayCountById(artist.id)
                } else {
                    // Fallback to partial match for parsed artists
                    statsDao.getArtistPlayCountByPartialMatch(name)
                }
                result[name] = playCount
            }
            result
        }
    }
    
    override suspend fun getArtistImageUrlsBatch(artistNames: List<String>): Map<String, String?> {
        // Implementation for batch fetching (could be optimized further)
        // For now, simple parallel fetch to reuse existing logic
        return coroutineScope {
            artistNames.associateWith { name ->
                async { getArtistImageUrlWithFallback(name) }
            }.mapValues { it.value.await() }
        }
    }

    override suspend fun getEarliestDataTimestamp(): Long? {
        // Cache this as it rarely changes (only on import or deletion)
        val key = "earliest_data_timestamp"
        return getCached(key) {
           listeningEventDao.getEarliestEventTimestamp()
        }
    }
}
