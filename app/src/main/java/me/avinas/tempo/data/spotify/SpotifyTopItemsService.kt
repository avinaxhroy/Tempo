package me.avinas.tempo.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.spotify.SpotifyApi
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyFullArtist
import me.avinas.tempo.data.remote.spotify.SpotifyTrack
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.TrackRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for importing user's top tracks and artists from Spotify's /me/top endpoints.
 * 
 * =====================================================
 * PURPOSE: Library Population & Listening History Seeding
 * =====================================================
 * 
 * This service fetches user's top tracks/artists from Spotify and uses them to:
 * 1. Populate the Track/Artist tables with high-quality metadata
 * 2. Get GENRES from top artists (Spotify only provides genres at artist level)
 * 3. Create LISTENING EVENTS that reflect actual Spotify usage patterns
 * 4. Pre-populate the library for new users
 * 
 * =====================================================
 * DESIGN PHILOSOPHY: Smart Data That Feels Authentic
 * =====================================================
 * 
 * The goal is NOT to avoid creating data - an empty stats page is terrible UX.
 * Instead, we CREATE DATA INTELLIGENTLY based on real signals:
 * 
 * - Spotify's "top tracks" API returns tracks RANKED by actual play frequency
 * - A #1 ranked track was genuinely listened to MORE than a #50 ranked track
 * - We use this ranking to generate proportional listening events
 * - Higher ranked = more events, lower ranked = fewer events
 * - Events are spread naturally over time (not clustered)
 * - All imported events are tagged with IMPORT_SOURCE for transparency
 * 
 * This approach:
 * ✓ Gives new users immediate value (populated stats)
 * ✓ Reflects their REAL listening preferences (based on Spotify's ranking)
 * ✓ Creates realistic-looking history (time distribution)
 * ✓ Maintains data integrity (clearly marked as imported)
 * 
 * =====================================================
 * PERFORMANCE CONSIDERATIONS
 * =====================================================
 * 
 * - Uses batched inserts with transactions
 * - Yields between batches to prevent ANR
 * - Reports progress for UI feedback
 * - Skips re-enrichment (data already has album art, etc.)
 */
@Singleton
class SpotifyTopItemsService @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val authManager: SpotifyAuthManager,
    private val artistDao: ArtistDao,
    private val trackRepository: TrackRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val listeningEventDao: ListeningEventDao
) {
    companion object {
        private const val TAG = "SpotifyTopItemsService"
        
        // Batch size for inserts to prevent memory pressure
        private const val BATCH_SIZE = 10
        
        // Delay between batches to prevent ANR (ms)
        private const val BATCH_DELAY_MS = 50L
        
        // Source identifier for imported events
        const val IMPORT_SOURCE = "com.spotify.music.import"
        
        // Time range API values
        const val TIME_RANGE_SHORT = "short_term"   // ~4 weeks
        const val TIME_RANGE_MEDIUM = "medium_term" // ~6 months
        const val TIME_RANGE_LONG = "long_term"     // Several years
        
        // Event generation constants - based on realistic listening patterns
        // Top tracks get more plays (they're favorites), lower ranked get fewer
        private const val MAX_PLAYS_TOP_TRACK = 12
        private const val MIN_PLAYS_BOTTOM_TRACK = 1
        
        // Spread events over this many days in the past
        private const val HISTORY_SPREAD_DAYS = 90
        
        // Realistic variation parameters
        private const val MIN_COMPLETION_PERCENT = 70  // Even favorites sometimes aren't finished
        private const val SKIP_PROBABILITY_LOW_RANK = 0.15  // Lower ranked tracks sometimes skipped
    }
    
    /**
     * Progress callback for UI updates.
     */
    fun interface ProgressCallback {
        fun onProgress(current: Int, total: Int, message: String)
    }
    
    /**
     * Result of import operation.
     */
    data class ImportResult(
        val tracksCreated: Int,
        val artistsCreated: Int,
        val genresCollected: Int,
        val listeningEventsCreated: Int,
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
        val totalItems: Int get() = tracksCreated + artistsCreated
        
        companion object {
            fun notConnected() = ImportResult(0, 0, 0, 0, listOf("Spotify not connected"))
            fun error(message: String) = ImportResult(0, 0, 0, 0, listOf(message))
        }
    }
    
    /**
     * Import user's top tracks and artists from all time ranges.
     * This populates the library with tracks/artists, gets genre data,
     * and generates listening history based on Spotify's ranking data.
     * 
     * @param progressCallback Optional callback for progress updates
     * @return ImportResult with statistics
     */
    suspend fun importTopItems(
        progressCallback: ProgressCallback? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting import of top items from Spotify")
        
        if (authManager.authState.value !is SpotifyAuthManager.AuthState.Connected) {
            Log.w(TAG, "Cannot import top items: Spotify not connected")
            return@withContext ImportResult.notConnected()
        }
        
        val accessToken = authManager.getValidAccessToken()
            ?: return@withContext ImportResult.error("Failed to get access token")
        
        val authHeader = "Bearer $accessToken"
        val errors = mutableListOf<String>()
        
        var totalTracksCreated = 0
        var totalArtistsCreated = 0
        var totalGenres = 0
        var totalEventsCreated = 0
        
        // Track IDs with their rank for listening event generation
        // Map of trackId -> best rank (lower is better)
        val trackRanks = mutableMapOf<Long, Int>()
        // Map of trackId -> duration for event generation
        val trackDurations = mutableMapOf<Long, Long>()
        
        // Step 1: Import top artists FIRST to get genres
        progressCallback?.onProgress(0, 100, "Fetching top artists...")
        
        val artistGenreMap = mutableMapOf<String, List<String>>()
        
        for (timeRange in listOf(TIME_RANGE_LONG, TIME_RANGE_MEDIUM, TIME_RANGE_SHORT)) {
            try {
                val artistResult = importTopArtists(authHeader, timeRange, artistGenreMap)
                totalArtistsCreated += artistResult.first
                
                // Yield to prevent blocking
                yield()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import artists for $timeRange", e)
                errors.add("Artists ($timeRange): ${e.message}")
            }
        }
        
        totalGenres = artistGenreMap.values.flatten().distinct().size
        progressCallback?.onProgress(30, 100, "Got ${totalGenres} genres from ${artistGenreMap.size} artists")
        
        // Step 2: Import top tracks and collect track IDs with ranks
        progressCallback?.onProgress(35, 100, "Fetching top tracks...")
        
        for ((index, timeRange) in listOf(TIME_RANGE_LONG, TIME_RANGE_MEDIUM, TIME_RANGE_SHORT).withIndex()) {
            try {
                val trackResult = importTopTracksWithRanks(
                    authHeader, timeRange, artistGenreMap, trackRanks, trackDurations
                ) { current, total ->
                    val baseProgress = 35 + (index * 15)
                    val rangeProgress = (current.toFloat() / total * 15).toInt()
                    progressCallback?.onProgress(baseProgress + rangeProgress, 100, 
                        "Importing tracks ($timeRange): $current/$total")
                }
                totalTracksCreated += trackResult
                
                yield()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import tracks for $timeRange", e)
                errors.add("Tracks ($timeRange): ${e.message}")
            }
        }
        
        // Step 3: Generate listening events based on Spotify's ranking (reflects real preferences)
        progressCallback?.onProgress(85, 100, "Generating listening history...")
        
        try {
            totalEventsCreated = generateListeningEvents(trackRanks, trackDurations)
            Log.i(TAG, "Created $totalEventsCreated listening events from Spotify ranking data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate listening events", e)
            errors.add("Listening events: ${e.message}")
        }
        
        progressCallback?.onProgress(100, 100, "Import complete!")
        
        Log.i(TAG, "Import complete: $totalTracksCreated tracks, $totalArtistsCreated artists, $totalGenres genres, $totalEventsCreated events")
        
        ImportResult(
            tracksCreated = totalTracksCreated,
            artistsCreated = totalArtistsCreated,
            genresCollected = totalGenres,
            listeningEventsCreated = totalEventsCreated,
            errors = errors
        )
    }
    
    /**
     * Import top artists and collect their genres.
     * Returns pair of (artists created, genre map updated)
     */
    private suspend fun importTopArtists(
        authHeader: String,
        timeRange: String,
        genreMap: MutableMap<String, List<String>>
    ): Pair<Int, Int> {
        var artistsCreated = 0
        var offset = 0
        
        while (true) {
            val response = spotifyApi.getUserTopArtists(
                authorization = authHeader,
                timeRange = timeRange,
                limit = 50,
                offset = offset
            )
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch top artists: ${response.code()}")
                break
            }
            
            val body = response.body() ?: break
            if (body.items.isEmpty()) break
            
            // Process in batches
            body.items.chunked(BATCH_SIZE).forEach { batch ->
                batch.forEach { spotifyArtist ->
                    try {
                        // Store genres for later use
                        if (spotifyArtist.genres?.isNotEmpty() == true) {
                            genreMap[spotifyArtist.name.lowercase()] = spotifyArtist.genres
                        }
                        
                        // Create/update local artist with genres
                        val created = ensureLocalArtist(spotifyArtist)
                        if (created) artistsCreated++
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process artist: ${spotifyArtist.name}", e)
                    }
                }
                
                // Yield between batches
                delay(BATCH_DELAY_MS)
            }
            
            if (body.next == null) break
            offset += 50
            
            // Safety limit
            if (offset >= 200) break
        }
        
        return artistsCreated to genreMap.size
    }
    
    /**
     * Import top tracks with genre information from artists.
     * Also collects track IDs and their ranks for listening event generation.
     */
    private suspend fun importTopTracksWithRanks(
        authHeader: String,
        timeRange: String,
        artistGenreMap: Map<String, List<String>>,
        trackRanks: MutableMap<Long, Int>,
        trackDurations: MutableMap<Long, Long>,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Int {
        var tracksCreated = 0
        var offset = 0
        var totalProcessed = 0
        var currentRank = 0

        // First, get total count
        val initialResponse = spotifyApi.getUserTopTracks(
            authorization = authHeader,
            timeRange = timeRange,
            limit = 1,
            offset = 0
        )
        val totalTracks = initialResponse.body()?.total ?: 50

        while (true) {
            val response = spotifyApi.getUserTopTracks(
                authorization = authHeader,
                timeRange = timeRange,
                limit = 50,
                offset = offset
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch top tracks: ${response.code()}")
                break
            }

            val body = response.body() ?: break
            if (body.items.isEmpty()) break

            // Process in batches
            body.items.chunked(BATCH_SIZE).forEach { batch ->
                batch.forEach { spotifyTrack ->
                    try {
                        currentRank++
                        
                        // Get genres from primary artist
                        val primaryArtist = spotifyTrack.artists.firstOrNull()?.name?.lowercase()
                        val genres = primaryArtist?.let { artistGenreMap[it] } ?: emptyList()

                        val trackIdResult = ensureLocalTrackAndGetId(spotifyTrack, genres)
                        if (trackIdResult.first) tracksCreated++
                        
                        val trackId = trackIdResult.second
                        if (trackId > 0) {
                            // Store the best (lowest) rank for this track
                            val existingRank = trackRanks[trackId]
                            if (existingRank == null || currentRank < existingRank) {
                                trackRanks[trackId] = currentRank
                            }
                            // Store duration for event generation
                            trackDurations[trackId] = spotifyTrack.durationMs.toLong()
                        }

                        totalProcessed++
                        progressCallback?.invoke(totalProcessed, totalTracks)

                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process track: ${spotifyTrack.name}", e)
                    }
                }

                // Yield between batches
                delay(BATCH_DELAY_MS)
            }

            if (body.next == null) break
            offset += 50

            // Safety limit
            if (offset >= 200) break
        }

        return tracksCreated
    }
    
    /**
     * Generate listening events based on Spotify's actual ranking data.
     * 
     * This data is derived from REAL usage patterns:
     * - Spotify's API ranks tracks by actual play frequency
     * - We translate that ranking into proportional event counts
     * - A #1 track genuinely deserves more events than a #200 track
     * - Time distribution feels natural (spread over history period)
     * - Adds realistic variation: completion %, occasional skips, varied durations
     */
    private suspend fun generateListeningEvents(
        trackRanks: Map<Long, Int>,
        trackDurations: Map<Long, Long>
    ): Int {
        if (trackRanks.isEmpty()) return 0
        
        val events = mutableListOf<ListeningEvent>()
        val now = System.currentTimeMillis()
        val historyStartTime = now - (HISTORY_SPREAD_DAYS * 24 * 60 * 60 * 1000L)
        
        // Sort tracks by rank
        val sortedTracks = trackRanks.entries.sortedBy { it.value }
        val maxRank = sortedTracks.maxOfOrNull { it.value } ?: 1
        
        // Generate unique session IDs for different "listening sessions"
        // Group events into ~20 sessions for realism
        val sessionCount = 20
        val sessionIds = (1..sessionCount).map { "spotify-import-${UUID.randomUUID()}" }
        
        for ((trackId, rank) in sortedTracks) {
            val playCount = calculatePlayCount(rank, maxRank)
            val baseDuration = trackDurations[trackId] ?: 180_000L
            
            // Normalized rank (0.0 = top, 1.0 = bottom)
            val normalizedRank = if (maxRank > 1) (rank - 1).toFloat() / (maxRank - 1) else 0f
            
            for (i in 0 until playCount) {
                // Distribute events with slight bias toward more recent for top tracks
                val timeWeight = if (normalizedRank < 0.3f) 0.6f else 0.5f // Top tracks more recent
                val randomOffset = generateWeightedRandomTime(historyStartTime, now, timeWeight)
                val eventTime = historyStartTime + randomOffset
                
                // Realistic completion percentage: top tracks -> higher completion
                val baseCompletion = 100 - (normalizedRank * (100 - MIN_COMPLETION_PERCENT)).toInt()
                val completionVariation = (-10..5).random()
                val completion = (baseCompletion + completionVariation).coerceIn(50, 100)
                
                // Calculate actual play duration based on completion
                val actualDuration = (baseDuration * completion / 100)
                
                // Lower ranked tracks occasionally skipped
                val wasSkipped = normalizedRank > 0.5 && Math.random() < SKIP_PROBABILITY_LOW_RANK
                val finalCompletion = if (wasSkipped) (30..60).random() else completion
                val finalDuration = if (wasSkipped) (baseDuration * finalCompletion / 100) else actualDuration
                
                // Assign to a random session
                val sessionId = sessionIds[(eventTime % sessionCount).toInt().coerceIn(0, sessionCount - 1)]
                
                val event = ListeningEvent(
                    track_id = trackId,
                    timestamp = eventTime,
                    playDuration = finalDuration,
                    completionPercentage = finalCompletion,
                    source = IMPORT_SOURCE,
                    wasSkipped = wasSkipped,
                    isReplay = i > 0, // Mark subsequent plays as replays
                    estimatedDurationMs = baseDuration,
                    pauseCount = if (Math.random() < 0.1) 1 else 0, // Occasional pause
                    sessionId = sessionId,
                    endTimestamp = eventTime + finalDuration
                )
                events.add(event)
            }
        }
        
        // Sort events by timestamp and insert in batches WITH DEDUPLICATION
        val sortedEvents = events.sortedBy { it.timestamp }
        val result = listeningEventDao.insertAllBatchedWithDedup(sortedEvents)
        
        Log.d(TAG, "Event generation: ${result.inserted} inserted, ${result.skipped} duplicates skipped")
        
        return result.inserted
    }
    
    /**
     * Generate a weighted random time offset.
     * Weight > 0.5 biases toward more recent times.
     */
    private fun generateWeightedRandomTime(start: Long, end: Long, recentBias: Float): Long {
        val range = end - start
        // Use power function to bias distribution
        val exponent = (1.0 / recentBias).coerceIn(0.5, 2.0)
        val random = Math.pow(Math.random(), exponent)
        return (random * range).toLong()
    }
    
    /**
     * Calculate number of plays for a track based on its rank.
     * Uses a linear interpolation from MAX to MIN plays.
     */
    private fun calculatePlayCount(rank: Int, maxRank: Int): Int {
        if (maxRank <= 1) return MAX_PLAYS_TOP_TRACK
        
        // Linear interpolation: rank 1 -> MAX_PLAYS, maxRank -> MIN_PLAYS
        val normalizedRank = (rank - 1).toFloat() / (maxRank - 1)
        val playCount = MAX_PLAYS_TOP_TRACK - (normalizedRank * (MAX_PLAYS_TOP_TRACK - MIN_PLAYS_BOTTOM_TRACK)).toInt()
        return playCount.coerceIn(MIN_PLAYS_BOTTOM_TRACK, MAX_PLAYS_TOP_TRACK)
    }
    
    /**
     * Ensure a local Track exists. Returns pair of (wasCreated, trackId).
     * 
     * IMPORTANT: Tracks from Spotify API already have all metadata.
     * We mark them as ENRICHED to skip re-enrichment.
     */
    private suspend fun ensureLocalTrackAndGetId(
        spotifyTrack: SpotifyTrack,
        genres: List<String>
    ): Pair<Boolean, Long> {
        // Check if track already exists
        val existing = trackRepository.findBySpotifyId(spotifyTrack.id)
        if (existing != null) {
            // Track exists - just ensure metadata is up to date
            updateEnrichedMetadataIfNeeded(existing.id, spotifyTrack, genres)
            return false to existing.id
        }
        
        // Create new track
        val artistNames = spotifyTrack.artists.joinToString(", ") { it.name }
        val newTrack = Track(
            title = spotifyTrack.name,
            artist = artistNames,
            album = spotifyTrack.album?.name,
            duration = spotifyTrack.durationMs.toLong(),
            albumArtUrl = spotifyTrack.album?.images?.firstOrNull()?.url,
            spotifyId = spotifyTrack.id,
            musicbrainzId = null,
            primaryArtistId = null, // Will be linked by ArtistLinkingService
            contentType = "MUSIC"
        )
        
        val trackId = trackRepository.insert(newTrack)
        val createdTrack = newTrack.copy(id = trackId)
        
        // Link artists
        artistLinkingService.linkArtistsForTrack(createdTrack)
        
        // Create enriched metadata - ALREADY ENRICHED (no need for worker)
        createEnrichedMetadata(trackId, spotifyTrack, genres)
        
        return true to trackId
    }
    
    /**
     * Ensure a local Artist exists. Returns true if newly created.
     */
    private suspend fun ensureLocalArtist(spotifyArtist: SpotifyFullArtist): Boolean {
        // Check if artist exists by Spotify ID
        val existingBySpotify = artistDao.getArtistBySpotifyId(spotifyArtist.id)
        if (existingBySpotify != null) {
            // Update genres if missing
            if (existingBySpotify.genres.isEmpty() && spotifyArtist.genres?.isNotEmpty() == true) {
                artistDao.update(existingBySpotify.copy(genres = spotifyArtist.genres))
            }
            return false
        }
        
        // Create/update via linking service
        artistLinkingService.getOrCreateArtistWithMetadata(
            name = spotifyArtist.name,
            imageUrl = spotifyArtist.images?.firstOrNull()?.url,
            spotifyId = spotifyArtist.id,
            genres = spotifyArtist.genres ?: emptyList()
        )
        
        return true
    }
    
    /**
     * Create enriched metadata with COMPLETED status (no re-enrichment needed).
     */
    private suspend fun createEnrichedMetadata(
        trackId: Long,
        spotifyTrack: SpotifyTrack,
        genres: List<String>
    ) {
        val metadata = EnrichedMetadata(
            trackId = trackId,
            spotifyId = spotifyTrack.id,
            isrc = spotifyTrack.externalIds?.isrc,
            albumArtUrl = spotifyTrack.album?.images?.firstOrNull()?.url,
            albumArtSource = AlbumArtSource.SPOTIFY,
            genres = genres,
            // Mark as ENRICHED - skip enrichment worker
            enrichmentStatus = EnrichmentStatus.ENRICHED,
            spotifyEnrichmentStatus = SpotifyEnrichmentStatus.ENRICHED,
            lastEnrichmentAttempt = System.currentTimeMillis(),
            cacheTimestamp = System.currentTimeMillis()
        )
        
        try {
            enrichedMetadataDao.upsert(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create metadata for track $trackId", e)
        }
    }
    
    /**
     * Update existing metadata if genres are missing.
     */
    private suspend fun updateEnrichedMetadataIfNeeded(
        trackId: Long,
        spotifyTrack: SpotifyTrack,
        genres: List<String>
    ) {
        if (genres.isEmpty()) return
        
        try {
            val existing = enrichedMetadataDao.forTrackSync(trackId)
            if (existing != null && existing.genres.isEmpty()) {
                enrichedMetadataDao.update(existing.copy(
                    genres = genres,
                    cacheTimestamp = System.currentTimeMillis()
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update metadata for track $trackId", e)
        }
    }
}
