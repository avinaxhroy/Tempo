package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.isLocalBackupArtwork

import android.util.Log
import android.util.LruCache
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.UserPreferences
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
 * Room-backed implementation of [StatsRepository] with in-memory LRU caching.
 * Serves listening statistics computed from local database tables.
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
    private val userPreferencesDao: UserPreferencesDao,
    private val artistAliasDao: ArtistAliasDao
) : StatsRepository {

    companion object {
        private const val TAG = "StatsRepository"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 64 // LRU cache max entries
        private const val CACHE_SIZE_BYTES = 4 * 1024 * 1024 // 4MB max cache memory
        private const val MAX_ARTIST_IMAGE_SEARCH_CACHE = 2_048
        private const val USER_PREFS_TTL_MS = 5_000L // memoize content-filter preference reads
    }

    // Thread-safe LRU cache with expiration
    private val cache = object : LruCache<String, CachedValue<*>>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedValue<*>): Int = 1
    }
    private val cacheMutex = Mutex()

    // Memoized content-filter preferences read. userPreferencesDao.getSync() is a
    // single-row query but runs several times per reload (once per getListeningOverview
    // call plus insights keying); the value changes rarely, so cache it with a short TTL.
    @Volatile
    private var cachedUserPrefs: UserPreferences? = null
    @Volatile
    private var cachedUserPrefsTimestamp: Long = 0L

    private suspend fun getCachedUserPrefs(): UserPreferences {
        val now = System.currentTimeMillis()
        val cached = cachedUserPrefs
        if (cached != null && now - cachedUserPrefsTimestamp < USER_PREFS_TTL_MS) return cached
        val fresh = userPreferencesDao.getSync() ?: UserPreferences()
        cachedUserPrefs = fresh
        cachedUserPrefsTimestamp = now
        return fresh
    }
    
    // Session cache to prevent redundant API calls for artist images within the same session.
    // Keep this bounded so long sessions with many unique artists do not grow unbounded.
    private val artistImageSearchCache = linkedSetOf<String>()
    
    // Flow to notify UI of metadata updates (album art, etc.)
    private val _metadataUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    
    override fun observeMetadataUpdates(): kotlinx.coroutines.flow.Flow<Unit> = _metadataUpdateFlow.asSharedFlow()
    
    /**
     * Call this when track metadata (album art, etc.) is updated by enrichment.
     * This will trigger UI refresh in components observing metadata updates.
     */
    override fun notifyMetadataUpdate() {
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

    // Cache Management

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
        // Don't notify UI here - let callers decide when to notify
        // This prevents global UI refreshes on background enrichment
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
        synchronized(artistImageSearchCache) {
            artistImageSearchCache.clear()
        }
        Log.d(TAG, "Artist image search cache cleared")
    }

    override suspend fun clearArtistImagesForArtist(artistId: Long): Unit = withContext(Dispatchers.IO) {
        enrichedMetadataDao.clearArtistImagesForArtist(artistId)
        Log.d(TAG, "Cleared artist images from enriched metadata for artist ID: $artistId")
    }

    /**
     * Refresh artist image by directly searching for the specific artist via API.
     * This avoids the track-based enrichment problem where a featured artist would get
     * the track's primary artist's image instead of their own.
     */
    override suspend fun refreshArtistImageDirectly(artistId: Long): String? = withContext(Dispatchers.IO) {
        val artist = artistDao.getArtistById(artistId)
        if (artist == null) {
            Log.w(TAG, "refreshArtistImageDirectly: Artist not found for ID=$artistId")
            return@withContext null
        }
        val artistName = artist.name
        Log.d(TAG, "refreshArtistImageDirectly: Searching for image of '$artistName' (ID=$artistId)")

        // Clear existing image and caches
        artistDao.updateImageUrl(artistId, null)
        synchronized(artistImageSearchCache) {
            artistImageSearchCache.remove(artistName.lowercase().trim())
        }

        // Try iTunes first (high quality, no auth required)
        // Use searchAndFetchSingleArtistImage: strict matching, no multi-artist splitting,
        // no track/album artwork fallback — only actual artist photos
        if (iTunesEnrichmentService.isAvailable()) {
            val fromITunes = iTunesEnrichmentService.searchAndFetchSingleArtistImage(artistName)
            if (!fromITunes.isNullOrBlank()) {
                Log.d(TAG, "refreshArtistImageDirectly: Found image from iTunes for '$artistName'")
                artistDao.updateImageUrl(artistId, fromITunes)
                invalidateCache()
                return@withContext fromITunes
            }
        }

        // Try Spotify
        if (spotifyEnrichmentService.isAvailable()) {
            val fromSpotify = spotifyEnrichmentService.searchAndFetchArtistImage(artistName)
            if (!fromSpotify.isNullOrBlank()) {
                Log.d(TAG, "refreshArtistImageDirectly: Found image from Spotify for '$artistName'")
                artistDao.updateImageUrl(artistId, fromSpotify)
                invalidateCache()
                return@withContext fromSpotify
            }
        }

        // Fallback: try enriched_metadata via track_artists junction (PRIMARY role only)
        val fromTrackArtists = statsDao.getArtistImageByArtistId(artistId)
        if (!fromTrackArtists.isNullOrBlank()) {
            Log.d(TAG, "refreshArtistImageDirectly: Found image from track_artists for '$artistName'")
            artistDao.updateImageUrl(artistId, fromTrackArtists)
            invalidateCache()
            return@withContext fromTrackArtists
        }

        Log.w(TAG, "refreshArtistImageDirectly: No image found for '$artistName' (ID=$artistId)")
        invalidateCache()
        null
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
        // Invalidate THIS_YEAR and ALL_TIME so Spotlight reflects updated top tracks
        invalidateCache(TimeRange.THIS_YEAR)
        invalidateCache(TimeRange.ALL_TIME)
        // Clear artist details cache entries for real-time updates
        val snapshot = cache.snapshot()
        val artistCacheKeys = snapshot.keys.filter { key -> key.startsWith("artist_details") || key.startsWith("top_artists") }
        artistCacheKeys.forEach { key -> cache.remove(key) }
        Log.d(TAG, "Cache invalidated for new event at $timestamp: cleared ${artistCacheKeys.size} artist cache entries, cache size=${cache.size()}")
    }

    // Overview Stats

    override suspend fun getListeningOverview(timeRange: TimeRange, withLeeway: Boolean): ListeningOverview {
        // Get user content filtering preferences for cache key
        val prefs = getCachedUserPrefs()
        val key = "overview_${timeRange.name}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            computeListeningOverview(timeRange, withLeeway)
        }
    }

    override suspend fun getInsights(timeRange: TimeRange, withLeeway: Boolean): List<InsightCardData> {
        val prefs = getCachedUserPrefs()
        val key = "insights_${timeRange.name}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val (startTime, endTime) = getTimeRangeBounds(timeRange, withLeeway)
            try {
                // Fetch raw JSONs and aggregate in memory
                val moodRawList = statsDao.getMoodRawData(startTime, endTime)
                val moodStats = calculateMoodAggregates(moodRawList)

                val bingeSessions = statsDao.getBingeListeningSessions(startTime, endTime)
                val discoveryTrends = statsDao.getNewArtistDiscoveryTrend(startTime, endTime)
                val hourlyDistribution = statsDao.getHourlyDistribution(startTime, endTime)
                val dayOfWeekDistribution = statsDao.getDayOfWeekDistribution(startTime, endTime)

                // Feed metrics
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
                    // Require energy and valence metrics for mood scoring
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

    override fun observeListeningOverview(timeRange: TimeRange, withLeeway: Boolean): Flow<ListeningOverview> =
        // Use a bounded query as a change trigger; all() on large datasets (e.g. after Last.fm import)
        // overflows the 2MB CursorWindow and throws IllegalStateException. The actual data is
        // fetched inside computeListeningOverview() via targeted statsDao queries.
        listeningEventDao.recentEvents(1)
            .map { _ -> computeListeningOverview(timeRange, withLeeway) }
            .flowOn(Dispatchers.IO)

    private suspend fun computeListeningOverview(timeRange: TimeRange, withLeeway: Boolean = true): ListeningOverview {
        val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
        val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
        
        // Get user content filtering preferences
        val prefs = getCachedUserPrefs()
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

    // Top Charts

    override suspend fun getTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int,
        withLeeway: Boolean
    ): PaginatedResult<TopTrack> {
        // Get user content filtering preferences for cache key
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "top_tracks_${timeRange.name}_${sortBy.name}_${page}_${pageSize}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            computeTopTracks(timeRange, sortBy, page, pageSize, withLeeway)
        }
    }

    private suspend fun computeTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int,
        withLeeway: Boolean = true
    ): PaginatedResult<TopTrack> {
        val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
        val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
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
        pageSize: Int,
        withLeeway: Boolean
    ): PaginatedResult<TopArtist> {
        // Get user content filtering preferences for cache key
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "top_artists_${timeRange.name}_${sortBy.name}_${page}_${pageSize}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            computeTopArtists(timeRange, sortBy, page, pageSize, withLeeway)
        }
    }

    private suspend fun computeTopArtists(
        timeRange: TimeRange,
        sortBy: SortBy,
        page: Int,
        pageSize: Int,
        withLeeway: Boolean = true
    ): PaginatedResult<TopArtist> {
        val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
        val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
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
        
        // Aggregate by individual canonical artist (merging aliases and normalized case variations)
        val aggregatedArtists = aggregateArtistStats(rawStats)
        val sortedArtists = sortTopArtists(aggregatedArtists, sortBy)
        
        val totalCount = sortedArtists.size
        
        // Apply pagination
        val paginatedItems = sortedArtists
            .drop(offset)
            .take(pageSize)
        
        val itemsWithImages = enrichTopArtistsWithMetadata(paginatedItems)

        return PaginatedResult(
            items = itemsWithImages,
            totalCount = totalCount,
            page = page,
            pageSize = pageSize,
            hasMore = (page + 1) * pageSize < totalCount
        )
    }

    /**
     * Canonical artist resolution result.
     */
    private data class ResolvedArtistInfo(
        val canonicalKey: String,
        val canonicalName: String,
        val artistId: Long?,
        val imageUrl: String?,
        val country: String?
    )

    /**
     * Aggregates raw artist stats by individual artist, resolving aliases and canonical artist identities
     * from the database so that name variations (e.g. case differences like "System of A Down" vs "System of a Down")
     * and aliases are merged into a single canonical entry with aggregated play count and duration.
     */
    private suspend fun aggregateArtistStats(rawStats: List<RawArtistStats>): List<TopArtist> {
        if (rawStats.isEmpty()) return emptyList()

        // 1. Parse all individual artists from raw strings
        val parsedRows = rawStats.map { raw ->
            val artists = ArtistParser.getAllArtists(raw.artist)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            raw to artists
        }

        // 2. Collect all distinct normalized names
        val allNormalizedNames = parsedRows.flatMap { (_, artists) ->
            artists.map { me.avinas.tempo.data.local.entities.Artist.normalizeName(it) }
        }.filter { it.isNotBlank() }.distinct()

        // 3. Load aliases and pre-fetch matching artist entities in batch
        val aliases = artistAliasDao.getAllSync()
        val aliasMap = aliases.associateBy({ it.originalNameNormalized }, { it.targetArtistId })

        val aliasTargetIds = allNormalizedNames.mapNotNull { aliasMap[it] }.distinct()
        val unaliasedNames = allNormalizedNames.filter { it !in aliasMap }

        // Batch query artists from database
        val dbArtistsByNormalized = if (unaliasedNames.isNotEmpty()) {
            artistDao.getArtistsByNormalizedNames(unaliasedNames)?.associateBy { it.normalizedName } ?: emptyMap()
        } else {
            emptyMap()
        }

        // Also fetch any target artists for aliases
        val dbArtistsById = if (aliasTargetIds.isNotEmpty()) {
            artistDao.getArtistsByIds(aliasTargetIds)?.associateBy { it.id } ?: emptyMap()
        } else {
            emptyMap()
        }

        // Helper to resolve an artist name to canonical info with fallback cache
        val resolutionCache = mutableMapOf<String, ResolvedArtistInfo>()
        suspend fun resolveArtist(trimmedName: String): ResolvedArtistInfo {
            val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(trimmedName)
            resolutionCache[normalizedName]?.let { return it }

            val targetArtistId = aliasMap[normalizedName]
            val artistEntity = if (targetArtistId != null) {
                dbArtistsById[targetArtistId]
                    ?: dbArtistsByNormalized.values.find { it.id == targetArtistId }
                    ?: artistDao.getArtistById(targetArtistId)
            } else {
                dbArtistsByNormalized[normalizedName]
                    ?: artistDao.getArtistByNormalizedName(normalizedName)
                    ?: artistDao.getArtistByName(trimmedName)
            }

            val resolved = if (artistEntity != null) {
                ResolvedArtistInfo(
                    canonicalKey = "id:${artistEntity.id}",
                    canonicalName = artistEntity.name,
                    artistId = artistEntity.id,
                    imageUrl = artistEntity.imageUrl,
                    country = artistEntity.country
                )
            } else {
                ResolvedArtistInfo(
                    canonicalKey = "norm:$normalizedName",
                    canonicalName = trimmedName,
                    artistId = null,
                    imageUrl = null,
                    country = null
                )
            }
            resolutionCache[normalizedName] = resolved
            return resolved
        }

        // 4. Aggregate stats by canonical key
        val artistStatsMap = mutableMapOf<String, ArtistAggregator>()

        for ((raw, individualArtists) in parsedRows) {
            val seenKeysInTrack = mutableSetOf<String>()

            for (artistName in individualArtists) {
                val resolved = resolveArtist(artistName)
                if (seenKeysInTrack.add(resolved.canonicalKey)) {
                    val aggregator = artistStatsMap.getOrPut(resolved.canonicalKey) {
                        ArtistAggregator(
                            name = resolved.canonicalName,
                            artistId = resolved.artistId,
                            imageUrl = resolved.imageUrl,
                            country = resolved.country
                        )
                    }
                    aggregator.addStats(raw, artistName)
                }
            }
        }

        return artistStatsMap.values.map { it.toTopArtist() }
    }

    private fun sortTopArtists(artists: List<TopArtist>, sortBy: SortBy): List<TopArtist> {
        return when (sortBy) {
            SortBy.PLAY_COUNT -> artists.sortedByDescending { it.playCount }
            SortBy.TOTAL_TIME -> artists.sortedByDescending { it.totalTimeMs }
            SortBy.COMBINED_SCORE -> {
                val maxPlays = artists.maxOfOrNull { it.playCount } ?: 1
                val maxTime = artists.maxOfOrNull { it.totalTimeMs } ?: 1L
                artists.sortedByDescending { artist ->
                    (0.5 * artist.playCount.toDouble() / maxPlays) +
                    (0.5 * artist.totalTimeMs.toDouble() / maxTime)
                }
            }
        }
    }

    private suspend fun enrichTopArtistsWithMetadata(
        artists: List<TopArtist>
    ): List<TopArtist> = coroutineScope {
        artists.map { artist ->
            async {
                val imageUrl = artist.imageUrl?.takeIf { it.isNotBlank() }
                    ?: getArtistImageUrlWithFallback(artist.artist, dbOnly = true)
                val country = artist.country?.takeIf { it.isNotBlank() }
                    ?: getArtistCountry(artist.artist)
                val artistId = artist.artistId ?: run {
                    val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artist.artist)
                    val alias = artistAliasDao.findAlias(normalizedName)
                    if (alias != null) {
                        artistDao.getArtistById(alias.targetArtistId)?.id
                    } else {
                        artistDao.getArtistByNormalizedName(normalizedName)?.id
                            ?: artistDao.getArtistByName(artist.artist)?.id
                    }
                }
                artist.copy(
                    artistId = artistId,
                    imageUrl = imageUrl,
                    country = country
                ).apply { rank = artist.rank }
            }
        }.awaitAll()
    }

    /** Aggregator for per-artist listening metrics. */
    private class ArtistAggregator(
        var name: String,
        val artistId: Long? = null,
        val imageUrl: String? = null,
        val country: String? = null
    ) {
        var playCount: Int = 0
        var totalTimeMs: Long = 0
        var uniqueTracks: Int = 0
        var firstPlayed: Long = Long.MAX_VALUE
        var lastPlayed: Long = 0
        
        fun addStats(raw: RawArtistStats, sourceArtistName: String) {
            playCount += raw.playCount
            totalTimeMs += raw.totalTimeMs
            uniqueTracks += raw.uniqueTracks
            if (raw.firstPlayed > 0 && raw.firstPlayed < firstPlayed) firstPlayed = raw.firstPlayed
            if (raw.lastPlayed > lastPlayed) lastPlayed = raw.lastPlayed

            // If we don't have a verified canonical artist ID from the DB, prefer a name casing with uppercase letters
            if (artistId == null && name.all { !it.isLetter() || it.isLowerCase() } && sourceArtistName.any { it.isUpperCase() }) {
                name = sourceArtistName
            }
        }
        
        fun toTopArtist(): TopArtist = TopArtist(
            artistId = artistId,
            artist = name,
            playCount = playCount,
            totalTimeMs = totalTimeMs,
            uniqueTracks = uniqueTracks,
            firstPlayed = if (firstPlayed == Long.MAX_VALUE) 0L else firstPlayed,
            lastPlayed = lastPlayed,
            imageUrl = imageUrl,
            country = country
        )
    }
    
    /**
     * Get artist image URL with smart fallback strategy.
     * 
     * For multi-artist tracks, enriched_metadata stores the TRACK's Spotify-matched primary
     * artist's image — which may differ from the artist we're looking for.
     * e.g., a KARMA track on Spotify might have KR$NA as the first artist, so enriched_metadata
     * stores KR$NA's image even though locally KARMA is marked as PRIMARY.
     * 
     * To avoid this, we prioritize direct API search (iTunes/Spotify) over enriched_metadata.
     * The API search uses the specific artist name, so it always returns the correct artist's image.
     * 
     * Priority order:
     * 0. Artists table image_url (directly assigned to this specific artist - most reliable)
     * 1. Verified enriched_metadata (Spotify artist ID must match this artist's Spotify ID)
     * 2. iTunes API search (high quality, no auth required, searches by this artist's name)
     * 3. Spotify API search (searches by this artist's name, saves to DB)
     */
    private suspend fun getArtistImageUrlWithFallback(artistName: String, dbOnly: Boolean = false): String? {
        // Resolve alias first if it exists
        val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artistName)
        val alias = artistAliasDao.findAlias(normalizedName)
        val artistEntity = if (alias != null) {
            artistDao.getArtistById(alias.targetArtistId)
        } else {
            artistDao.getArtistByNormalizedName(normalizedName)
                ?: artistDao.getArtistByName(artistName)
        }
        
        if (artistEntity != null) {
            // 0a. Check artist entity's own image_url first (most reliable)
            if (!artistEntity.imageUrl.isNullOrBlank()) {
                Log.d(TAG, "Found artist image from artist entity: ${artistEntity.name}")
                return artistEntity.imageUrl
            }
            
            // 0b. Try verified enriched_metadata (checks Spotify artist ID matches)
            // This prevents returning another artist's image from a collab track
            val fromVerifiedEnriched = statsDao.getArtistImageByArtistId(artistEntity.id)
            if (!fromVerifiedEnriched.isNullOrBlank()) {
                Log.d(TAG, "Found verified artist image via track_artists junction: ${artistEntity.name}")
                persistImageToArtistTable(artistEntity.name, fromVerifiedEnriched)
                return fromVerifiedEnriched
            }
        }
        
        
        val resolvedArtistName = artistEntity?.name ?: artistName

        // DB-only mode: skip network API calls entirely (used on Spotlight critical path)
        if (dbOnly) {
            Log.d(TAG, "DB-only mode: no image in DB for '$resolvedArtistName', skipping API calls")
            return null
        }

        // Check session cache - if we already searched for this artist in this session, skip API calls
        val cacheKey = resolvedArtistName.lowercase().trim()
        synchronized(artistImageSearchCache) {
            if (cacheKey in artistImageSearchCache) {
                Log.d(TAG, "Already searched for '$resolvedArtistName' this session, skipping API calls")
                return null
            }
            if (artistImageSearchCache.size >= MAX_ARTIST_IMAGE_SEARCH_CACHE) {
                val iterator = artistImageSearchCache.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            artistImageSearchCache.add(cacheKey)
        }
        
        val fromFallbackApis = coroutineScope {
            val iTunesDeferred = if (iTunesEnrichmentService.isAvailable()) {
                async {
                    Log.d(TAG, "Fetching artist image from iTunes for: $resolvedArtistName")
                    iTunesEnrichmentService.searchAndFetchArtistImage(resolvedArtistName)
                }
            } else {
                null
            }

            val spotifyDeferred = if (spotifyEnrichmentService.isAvailable()) {
                async {
                    Log.d(TAG, "Fetching artist image from Spotify API for: $resolvedArtistName (will save to DB)")
                    spotifyEnrichmentService.searchAndFetchArtistImage(resolvedArtistName)
                }
            } else {
                null
            }

            val fromITunes = iTunesDeferred?.await()
            val fromSpotify = spotifyDeferred?.await()
            fromITunes.takeUnless { it.isNullOrBlank() } ?: fromSpotify.takeUnless { it.isNullOrBlank() }
        }

        if (!fromFallbackApis.isNullOrBlank()) {
            Log.d(TAG, "Found and cached artist image from fallback API: $resolvedArtistName")
            persistImageToArtistTable(resolvedArtistName, fromFallbackApis)
            return fromFallbackApis
        }
        
        Log.d(TAG, "No artist image found for: $artistName")
        return null
    }
    
    /** Caches resolved artist image URL to the artists table. */
    private suspend fun persistImageToArtistTable(artistName: String, imageUrl: String) {
        try {
            val normalizedName = Artist.normalizeName(artistName)
            val alias = artistAliasDao.findAlias(normalizedName)
            val existingArtist = if (alias != null) {
                artistDao.getArtistById(alias.targetArtistId)
            } else {
                artistDao.getArtistByName(artistName)
                    ?: artistDao.getArtistByNormalizedName(normalizedName)
            }
            if (existingArtist != null && existingArtist.imageUrl.isNullOrBlank()) {
                artistDao.updateImageUrl(existingArtist.id, imageUrl)
                Log.d(TAG, "Persisted image to artists table for: ${existingArtist.name} (resolved from $artistName)")
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
        sortBy: SortBy,
        page: Int,
        pageSize: Int,
        withLeeway: Boolean
    ): PaginatedResult<TopAlbum> {
        // Get user content filtering preferences
        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val filterPodcasts = prefs.filterPodcasts
        val filterAudiobooks = prefs.filterAudiobooks
        val key = "top_albums_${timeRange.name}_${sortBy.name}_${page}_${pageSize}_pod${if (filterPodcasts) 1 else 0}_audio${if (filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
            val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
            val offset = page * pageSize

            val items = when (sortBy) {
                SortBy.PLAY_COUNT -> statsDao.getTopAlbumsByPlayCountFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
                SortBy.TOTAL_TIME -> statsDao.getTopAlbumsByTimeFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
                SortBy.COMBINED_SCORE -> statsDao.getTopAlbumsByCombinedScoreFiltered(startTime, endTime, filterPodcasts, filterAudiobooks, pageSize, offset)
            }
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

    // Ranking Search

    /**
     * Search the track ranking. The DAO computes the full ranking once (CTE) and
     * returns only matching rows with their global rank, so at most [limit] rows
     * cross into Kotlin. Results are cached like other top-chart queries.
     */
    override suspend fun searchTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy,
        query: String,
        limit: Int,
        withLeeway: Boolean
    ): List<TopTrack> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "search_tracks_${timeRange.name}_${sortBy.name}_${trimmed.lowercase()}_${limit}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
            val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
            val rows = when (sortBy) {
                SortBy.PLAY_COUNT -> statsDao.searchTopTracksByPlayCount(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
                SortBy.TOTAL_TIME -> statsDao.searchTopTracksByTime(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
                SortBy.COMBINED_SCORE -> statsDao.searchTopTracksByCombinedScore(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
            }
            // Rows arrive ordered by global rank; carry the SQL-computed rank onto the item
            rows.map { it.item.apply { rank = it.globalRank } }
        }
    }

    /**
     * Search the artist ranking.
     *
     * Unlike tracks/albums, artist stats must go through the split/aggregate/alias
     * pipeline (multi-artist strings, renamed/merged artists), so SQL-side filtering
     * is not possible without breaking alias resolution. Instead we scan a bounded
     * raw set (top 1000 by play count — the same cap the list view uses, extended
     * for search depth), aggregate, then filter + rank in Kotlin.
     */
    override suspend fun searchTopArtists(
        timeRange: TimeRange,
        sortBy: SortBy,
        query: String,
        limit: Int,
        withLeeway: Boolean
    ): List<TopArtist> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "search_artists_${timeRange.name}_${sortBy.name}_${trimmed.lowercase()}_${limit}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
            val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)

            // Bounded scan: top 1000 raw artist strings by play count
            val rawStats = statsDao.getAllArtistStatsRawFiltered(
                startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, 1000
            )

            val aggregatedArtists = aggregateArtistStats(rawStats)
            val sortedArtists = sortTopArtists(aggregatedArtists, sortBy)

            val queryLower = trimmed.lowercase()
            val matches = sortedArtists
                .onEachIndexed { index, artist -> artist.rank = index + 1 }
                .filter { it.artist.lowercase().contains(queryLower) }
                .take(limit)

            enrichTopArtistsWithMetadata(matches)
        }
    }

    /**
     * Search the album ranking. The DAO computes the full ranking once (CTE) and
     * returns only matching rows with their global rank.
     */
    override suspend fun searchTopAlbums(
        timeRange: TimeRange,
        sortBy: SortBy,
        query: String,
        limit: Int,
        withLeeway: Boolean
    ): List<TopAlbum> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
        val key = "search_albums_${timeRange.name}_${sortBy.name}_${trimmed.lowercase()}_${limit}_pod${if (prefs.filterPodcasts) 1 else 0}_audio${if (prefs.filterAudiobooks) 1 else 0}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
            val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)
            val rows = when (sortBy) {
                SortBy.PLAY_COUNT -> statsDao.searchTopAlbumsByPlayCount(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
                SortBy.TOTAL_TIME -> statsDao.searchTopAlbumsByTime(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
                SortBy.COMBINED_SCORE -> statsDao.searchTopAlbumsByCombinedScore(startTime, endTime, prefs.filterPodcasts, prefs.filterAudiobooks, trimmed, limit)
            }
            // Rows arrive ordered by global rank; carry the SQL-computed rank onto the item
            rows.map { it.item.apply { rank = it.globalRank } }
        }
    }

    // Temporal Analysis

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

    override suspend fun getDailyListening(timeRange: TimeRange, limit: Int, withLeeway: Boolean): List<DailyListening> {
        val key = "daily_${timeRange.name}_${limit}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            statsDao.getDailyListening(
                timeRange.getStartTimestamp(withLeeway = withLeeway),
                timeRange.getEndTimestamp(withLeeway = withLeeway),
                limit
            )
        }
    }

    override suspend fun getActiveDaysCount(timeRange: TimeRange, withLeeway: Boolean): Int {
        val key = "active_days_${timeRange.name}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            statsDao.getActiveDaysCount(
                timeRange.getStartTimestamp(withLeeway = withLeeway),
                timeRange.getEndTimestamp(withLeeway = withLeeway)
            )
        }
    }

    override suspend fun getPeakDayListeningMs(timeRange: TimeRange, withLeeway: Boolean): Long {
        val key = "peak_day_${timeRange.name}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            statsDao.getPeakDayListeningMs(
                timeRange.getStartTimestamp(withLeeway = withLeeway),
                timeRange.getEndTimestamp(withLeeway = withLeeway)
            ) ?: 0L
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

    override suspend fun getMostActiveHour(timeRange: TimeRange, withLeeway: Boolean): HourlyDistribution? {
        val key = "most_active_hour_${timeRange.name}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            statsDao.getMostActiveHour(
                timeRange.getStartTimestamp(withLeeway = withLeeway),
                timeRange.getEndTimestamp(withLeeway = withLeeway)
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

    // Discovery Metrics

    override suspend fun getDiscoveryStats(timeRange: TimeRange, withLeeway: Boolean): DiscoveryStats {
        val key = "discovery_${timeRange.name}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            computeDiscoveryStats(timeRange, withLeeway)
        }
    }

    private suspend fun computeDiscoveryStats(timeRange: TimeRange, withLeeway: Boolean = true): DiscoveryStats {
        val startTime = timeRange.getStartTimestamp(withLeeway = withLeeway)
        val endTime = timeRange.getEndTimestamp(withLeeway = withLeeway)

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

    // Engagement Metrics

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

    // Spotify Audio Features (Deprecated - Nov 2024)

    /**
     * @deprecated Spotify's audio-features API was deprecated in November 2024.
     * Use TagBasedMoodAnalyzer with MusicBrainz tags and getTrackEngagement() 
     * for mood/energy insights based on user behavior patterns.
     */
    override suspend fun getAudioFeaturesStats(timeRange: TimeRange, withLeeway: Boolean): AudioFeaturesStats? {
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

    // Comparisons

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

    override suspend fun getPeriodComparison(timeRange: TimeRange, withLeeway: Boolean): PeriodComparison {
        val key = "period_comp_${timeRange.name}_leeway${if (withLeeway) 1 else 0}"
        return getCached(key) {
            val currentStart = timeRange.getStartTimestamp(withLeeway = withLeeway)
            val currentEnd = timeRange.getEndTimestamp(withLeeway = withLeeway)
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

    // Detail Screens

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
            val isTrackLocal = isLocalBackupArtwork(fixedTrackUrl)
            
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
            // Priority:
            // 1. artist.imageUrl (directly assigned, most reliable)
            // 2. Verified enriched_metadata (Spotify artist ID must match this artist)
            // 3. Direct API search by artist name (iTunes/Spotify) — always correct
            val fromArtistTable = artist.imageUrl?.takeIf { it.isNotBlank() }
            val fromVerifiedEnriched = if (fromArtistTable == null) {
                // Only use enriched_metadata if we can verify the image belongs to this artist
                statsDao.getArtistImageByArtistId(artistId)?.also {
                    Log.d(TAG, "getArtistDetails: Found verified image via track_artists join: ${it.take(80)}")
                }
            } else null
            val fromDirectSearch = if (fromArtistTable == null && fromVerifiedEnriched == null) {
                // Search APIs directly for this specific artist — avoids the problem
                // where enriched_metadata stores a different artist's image
                getArtistImageUrlWithFallback(artist.name)?.also {
                    Log.d(TAG, "getArtistDetails: Found image via direct search: ${it.take(80)}")
                }
            } else null
            
            val imageUrl = fromArtistTable ?: fromVerifiedEnriched ?: fromDirectSearch
            
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
            // Resolve alias first if it exists
            val normalizedName = me.avinas.tempo.data.local.entities.Artist.normalizeName(artistName)
            val alias = artistAliasDao.findAlias(normalizedName)
            val targetArtist = if (alias != null) {
                artistDao.getArtistById(alias.targetArtistId)
            } else {
                artistDao.getArtistByNormalizedName(normalizedName)
                    ?: artistDao.getArtistByName(artistName)
            }
            
            if (targetArtist != null) {
                // Artist found in database - use ID-based queries for proper relational lookups
                Log.d(TAG, "getArtistDetailsByName: Found artist '${targetArtist.name}' (ID=${targetArtist.id}), " +
                    "existing imageUrl=${targetArtist.imageUrl?.take(80) ?: "null"}")
                
                val playCount = statsDao.getArtistPlayCountById(targetArtist.id)
                val totalTime = statsDao.getArtistTotalTimeById(targetArtist.id)
                val topSongs = statsDao.getTopTracksForArtistById(targetArtist.id, 10)
                val firstDiscovery = statsDao.getArtistFirstListenById(targetArtist.id)
                
                // Extended data using ID-based queries
                val uniqueAlbums = statsDao.getArtistUniqueAlbumsPlayedById(targetArtist.id)
                val uniqueTracks = statsDao.getArtistUniqueTracksPlayedById(targetArtist.id)
                val listeningDates = statsDao.getArtistListeningDatesById(targetArtist.id)
                val peakHour = statsDao.getArtistPeakListeningHourById(targetArtist.id)
                val topAlbums = statsDao.getTopAlbumsForArtistById(targetArtist.id, 5)
                
                // Get artist image URL with fallback strategy
                // Priority: artist table → verified enriched_metadata → direct API search
                val imageUrl = targetArtist.imageUrl?.takeIf { it.isNotBlank() }
                    ?: statsDao.getArtistImageByArtistId(targetArtist.id)?.also {
                        Log.d(TAG, "getArtistDetailsByName: Found verified image via track_artists join")
                    }
                    ?: getArtistImageUrlWithFallback(targetArtist.name)?.also {
                        Log.d(TAG, "getArtistDetailsByName: Found image via direct search")
                    }
                
                if (imageUrl != null) {
                    Log.d(TAG, "getArtistDetailsByName: Resolved image: ${imageUrl.take(80)}")
                    // Update artist table with found image for future lookups
                    if (targetArtist.imageUrl.isNullOrBlank()) {
                        try {
                            artistDao.updateImageUrl(targetArtist.id, imageUrl)
                            Log.d(TAG, "getArtistDetailsByName: Persisted image URL to artists table")
                        } catch (e: Exception) {
                            Log.w(TAG, "getArtistDetailsByName: Failed to persist image URL", e)
                        }
                    }
                } else {
                    Log.w(TAG, "getArtistDetailsByName: No image found for artist '${targetArtist.name}'")
                }
                
                // Create artist with resolved image URL
                val artistWithImage = if (imageUrl != targetArtist.imageUrl) {
                    targetArtist.copy(imageUrl = imageUrl)
                } else {
                    targetArtist
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
            
            val playCount = statsDao.getAlbumPlayCount(album.title, artist.name, artist.id)
            val totalTime = statsDao.getAlbumTotalTime(album.title, artist.name, artist.id)
            
            val rawTracks = statsDao.getTracksForAlbumWithStats(album.title, artist.name, artist.id)
            val tracks = rawTracks.map { 
                TrackWithStats(it.track, it.play_count, it.total_time_ms) 
            }

            // albums.artwork_url is only filled by album-level enrichment/imports, so an
            // album can have no artwork while its tracks do. Fall back to a track's art
            // (remote preferred over a local file backup), matching the stats queries.
            val albumWithArt =
                if (album.artworkUrl.isNullOrBlank()) {
                    val trackArt = tracks.mapNotNull { it.track.albumArtUrl?.takeIf(String::isNotBlank) }
                    val fallback = trackArt.firstOrNull { it.startsWith("http") } ?: trackArt.firstOrNull()
                    if (fallback != null) album.copy(artworkUrl = fallback) else album
                } else {
                    album
                }

            val completionRate = if (tracks.isNotEmpty()) {
                val playedTracks = tracks.count { it.playCount > 0 }
                (playedTracks.toDouble() / tracks.size) * 100
            } else 0.0

            AlbumDetails(
                album = albumWithArt,
                artistName = artist.name,
                totalPlayCount = playCount,
                totalTimeMs = totalTime,
                completionRate = completionRate,
                tracks = tracks
            )
        }
    }

    override suspend fun addTrackToAlbum(albumId: Long, trackId: Long) {
        val album = albumDao.getAlbumById(albumId) ?: return
        val artist = artistDao.getArtistById(album.artistId) ?: return
        val track = trackDao.getTrackById(trackId) ?: return
        // Feat. tracks keep the album artist as primary_artist_id even though their
        // raw artist string differs ("Caskets feat. X"), so match on that first.
        if (track.primaryArtistId == artist.id || track.artist.equals(artist.name, ignoreCase = true)) {
            trackDao.setTrackAlbum(trackId, album.title)
            invalidateCache()
            notifyMetadataUpdate()
        }
    }

    override suspend fun removeTrackFromAlbum(albumId: Long, trackId: Long) {
        trackDao.setTrackAlbum(trackId, null)
        invalidateCache()
        notifyMetadataUpdate()
    }

    override suspend fun getCandidateTracksForAlbum(
        albumId: Long,
        query: String
    ): List<Track> {
        val album = albumDao.getAlbumById(albumId) ?: return emptyList()
        val artist = artistDao.getArtistById(album.artistId) ?: return emptyList()
        return trackDao.getCandidateTracksForAlbum(artist.name, album.title, query.trim(), artist.id)
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

    // History

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

            // Infer hasMore from page saturation to avoid running filtered count query on large tables
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

    // Lookup Methods

    override suspend fun getArtistIdByName(artistName: String): Long? {
        return artistDao.getArtistByName(artistName)?.id
    }

    override suspend fun getAlbumIdByTitleAndArtist(albumTitle: String, artistName: String): Long? {
        val existing = albumDao.getAlbumByTitleAndArtist(albumTitle, artistName)
        if (existing != null) return existing.id
        val artist = artistDao.getArtistByName(artistName)
        if (artist != null) {
            val newAlbum = me.avinas.tempo.data.local.entities.Album(
                title = albumTitle,
                artistId = artist.id,
                releaseYear = null,
                artworkUrl = null
            )
            val id = albumDao.insert(newAlbum)
            if (id > 0) return id
            return albumDao.getAlbumByTitleAndArtist(albumTitle, artistName)?.id
        }
        return null
    }

    override suspend fun getArtistImageUrl(artistName: String): String? {
        return artistDao.getArtistByName(artistName)?.imageUrl
    }

    // Track Audio Features

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

    private fun getTimeRangeBounds(timeRange: TimeRange, withLeeway: Boolean = true): Pair<Long, Long> {
        return Pair(timeRange.getStartTimestamp(withLeeway = withLeeway), timeRange.getEndTimestamp(withLeeway = withLeeway))
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
    
    // Batch Operations (Spotlight Performance)
    
    override suspend fun getArtistPlayCountsBatch(artistNames: List<String>): Map<String, Int> {
        if (artistNames.isEmpty()) return emptyMap()
        
        val key = "artist_playcounts_batch_${artistNames.hashCode()}"
        return getCached(key) {
            // Batch approach: resolve all names to artist IDs with ~3 queries,
            // then fetch all play counts in 1 query.
            val normalizedNames = artistNames.associateWith { name ->
                me.avinas.tempo.data.local.entities.Artist.normalizeName(name)
            }
            val uniqueNormalized = normalizedNames.values.distinct()
            
            // 1. Batch fetch aliases for all normalized names
            val aliases = artistAliasDao.findAliasesByNormalizedNames(uniqueNormalized)
            val aliasMap = aliases.associateBy { it.originalNameNormalized }
            
            // 2. For names without aliases, batch fetch artists by normalized name
            val namesNeedingArtistLookup = uniqueNormalized.filter { it !in aliasMap }
            val artistsByNormalizedName = if (namesNeedingArtistLookup.isNotEmpty()) {
                artistDao.getArtistsByNormalizedNames(namesNeedingArtistLookup).associateBy { it.normalizedName }
            } else {
                emptyMap()
            }
            
            // 3. For names without alias or normalized match, batch fetch by exact name
            val unresolvedNames = normalizedNames.filter { (name, norm) ->
                norm !in aliasMap && norm !in artistsByNormalizedName
            }.keys
            val artistsByExactName = if (unresolvedNames.isNotEmpty()) {
                unresolvedNames.mapNotNull { name ->
                    artistDao.getArtistByName(name)
                }.associateBy { it.name }
            } else {
                emptyMap()
            }
            
            // 4. Resolve all artist IDs
            val nameToArtistId = mutableMapOf<String, Long>()
            artistNames.forEach { name ->
                val norm = normalizedNames[name]!!
                val alias = aliasMap[norm]
                val artist = if (alias != null) {
                    null // will resolve via batch ID lookup below
                } else {
                    artistsByNormalizedName[norm] ?: artistsByExactName[name]
                }
                if (alias != null) {
                    nameToArtistId[name] = alias.targetArtistId
                } else if (artist != null) {
                    nameToArtistId[name] = artist.id
                }
            }
            
            // 5. Batch fetch play counts for all resolved artist IDs
            val artistIds = nameToArtistId.values.distinct()
            val playCountMap = if (artistIds.isNotEmpty()) {
                statsDao.getArtistPlayCountsByIds(artistIds).associate { it.artistId to it.playCount }
            } else {
                emptyMap()
            }
            
            // 6. Build result, falling back to partial match for unresolved names
            val result = mutableMapOf<String, Int>()
            artistNames.forEach { name ->
                val artistId = nameToArtistId[name]
                result[name] = if (artistId != null) {
                    playCountMap[artistId] ?: 0
                } else {
                    // Fallback to partial match for parsed artists not in DB
                    try { statsDao.getArtistPlayCountByPartialMatch(name) } catch (_: Exception) { 0 }
                }
            }
            result
        }
    }
    
    override suspend fun getArtistImageUrlsBatch(artistNames: List<String>): Map<String, String?> {
        // DB-only fast path: returns cached images instantly without network calls.
        // Network enrichment is handled separately by enrichMissingArtistImagesInBackground().
        return coroutineScope {
            artistNames.associateWith { name ->
                async { getArtistImageUrlWithFallback(name, dbOnly = true) }
            }.mapValues { it.value.await() }
        }
    }

    /**
     * Background enrichment: fetches missing artist images via network APIs
     * and persists them to the database for future fast-path lookups.
     * Fire-and-forget — call after cards are displayed so images are ready on next load.
     */
    override suspend fun enrichMissingArtistImagesInBackground(artistNames: List<String>) {
        val missing = artistNames.distinct().filter { name ->
            getArtistImageUrlWithFallback(name, dbOnly = true).isNullOrBlank()
        }
        if (missing.isEmpty()) return
        Log.d(TAG, "Background enriching ${missing.size} missing artist images")
        coroutineScope {
            missing.map { name ->
                async { getArtistImageUrlWithFallback(name, dbOnly = false) }
            }.awaitAll()
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
