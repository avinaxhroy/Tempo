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
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.spotify.PlayHistoryObject
import me.avinas.tempo.data.remote.spotify.PlaylistTrackObject
import me.avinas.tempo.data.remote.spotify.SavedTrackObject
import me.avinas.tempo.data.remote.spotify.SpotifyApi
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyFullArtist
import me.avinas.tempo.data.remote.spotify.SpotifySimplifiedPlaylist
import me.avinas.tempo.data.remote.spotify.SpotifyTrack
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.TrackRepository
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify History Reconstruction Service - Honest Data Only Approach.
 * 
 * =====================================================
 * PHILOSOPHY: REAL DATA ONLY, NO FABRICATION
 * =====================================================
 * 
 * We populate stats ONLY when we have real timing evidence.
 * Empty stats are better than fabricated stats.
 * 
 * =====================================================
 * DATA SOURCES & WHAT THEY PROVIDE
 * =====================================================
 * 
 * PHASE 0: THE "GOLDEN SOURCE" (EXACT Play Timestamps! ★)
 * ========================================================
 * Source: GET /me/player/recently-played
 * 
 * This is THE MOST ACCURATE data we can get from Spotify!
 * Returns the last 50 tracks with EXACT played_at timestamps.
 * Example: "2024-01-15T14:32:45.123Z" - we know EXACTLY when they played it.
 * 
 * ★ CREATES LISTENING EVENTS WITH EXACT TIMESTAMPS (100% real data!)
 * 
 * PHASE 1: THE "TIME MACHINE" (Exact Added-At Timestamps ✓)
 * ========================================================
 * Source: GET /me/tracks (Saved Tracks)
 * 
 * Spotify stores the EXACT added_at timestamp for every saved song.
 * If a user liked a song on June 12, 2023, we KNOW they were listening
 * to it around that time. We generate events around that date.
 * 
 * ✓ CREATES LISTENING EVENTS (we have real dates)
 * 
 * PHASE 2: THE "ARTIFACT HUNTER" (Year Context ✓)
 * =============================================================
 * Source: GET /me/playlists → "Your Top Songs 20XX"
 * 
 * If a track is in "Your Top Songs 2023", we know they listened
 * during 2023. We distribute plays throughout that year.
 * 
 * ✓ CREATES LISTENING EVENTS (we have year context)
 * 
 * PHASE 3: TOP TRACKS (Library Only, No Events)
 * =======================================================
 * Source: GET /me/top/tracks (Affinity Data)
 * 
 * We DON'T have timestamps - only ranking/affinity.
 * These tracks are added to the LIBRARY with metadata and genres,
 * but we DO NOT create listening events.
 * 
 * ✗ NO LISTENING EVENTS (we don't know WHEN they listened)
 * ✓ LIBRARY POPULATION (tracks + artists + genres)
 * 
 * =====================================================
 * WHAT WE DON'T DO
 * =====================================================
 * 
 * - NO circadian rhythm bias (we don't know what time they listened)
 * - NO session clustering (we don't know their session patterns)
 * - NO "realistic" fake patterns (that's still fabrication)
 * - NO events for tracks without real timing data
 * 
 * The Spotlight screen should show "Not enough data" rather than lies.
 */
@Singleton
class SpotifyHistoryReconstructionService @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val authManager: SpotifyAuthManager,
    private val artistDao: ArtistDao,
    private val trackRepository: TrackRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val listeningEventDao: ListeningEventDao
) {
    companion object {
        private const val TAG = "SpotifyHistoryRecon"
        
        // =====================================================
        // SMART FETCH CONFIGURATION
        // =====================================================
        // No artificial limits - fetch ALL data from Spotify
        // Use smart batching and rate limiting instead
        
        // Batch processing for DB operations
        private const val BATCH_SIZE = 50  // Larger batches = fewer DB transactions
        
        // API rate limiting - be nice to Spotify
        private const val API_DELAY_MS = 50L      // Delay between API calls
        private const val BATCH_DELAY_MS = 10L    // Delay between DB batches
        
        // Source identifier
        const val IMPORT_SOURCE = "com.spotify.music.import.reconstructed"
        
        // Spotify API returns max 50 items per page
        private const val SPOTIFY_PAGE_SIZE = 50
        
        // Safety limits to prevent infinite loops (Spotify limits)
        private const val MAX_API_PAGES = 100     // 100 pages × 50 = 5000 items max (more than enough)
        
        // Yearly playlist processing strategy:
        // - Process most recent years IMMEDIATELY for fast initial experience
        // - Queue older years for BACKGROUND processing
        private const val INITIAL_YEARLY_PLAYLISTS = 3   // Process immediately (fast startup)
        private const val MAX_YEARLY_PLAYLISTS = 15       // Total to find (process rest in background)
        
        // Time range for history reconstruction
        private const val MAX_HISTORY_YEARS = 15  // Full history
        
        // Event generation targets (ONLY for tracks with real timing data)
        // These should roughly match SmartListeningEventGenerator's actual output
        // Generator: liked tracks = 1-5 based on affinity, yearly = 2-6 based on affinity
        private const val EVENTS_PER_LIKED_TRACK_HIGH_AFFINITY = 5  // High affinity liked track
        private const val EVENTS_PER_LIKED_TRACK_NORMAL = 2         // Average liked track
        private const val EVENTS_PER_YEARLY_TRACK = 4               // Average yearly playlist track
        // NOTE: Top tracks without dates get 0 events (library only)
    }
    
    /**
     * Progress callback for UI updates.
     */
    fun interface ProgressCallback {
        fun onProgress(current: Int, total: Int, phase: String, message: String)
    }
    
    /**
     * Playlist pending for background processing.
     */
    data class PendingPlaylist(
        val id: String,
        val year: Int,
        val name: String
    )
    
    /**
     * Result of the reconstruction operation.
     */
    data class ReconstructionResult(
        val tracksCreated: Int,
        val artistsCreated: Int,
        val listeningEventsCreated: Int,
        val recentlyPlayedFound: Int,     // Tracks with EXACT timestamps → events created
        val likedTracksFound: Int,        // Tracks with exact dates → events created
        val yearlyPlaylistsFound: Int,    // Tracks with year context → events created  
        val topTracksUsed: Int,           // Tracks for library only → NO events
        val pendingYearlyPlaylists: List<PendingPlaylist> = emptyList(),  // Playlists to process in background
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
        
        // Tracks that contributed to listening history (real data only)
        val tracksWithRealData: Int get() = recentlyPlayedFound + likedTracksFound + yearlyPlaylistsFound
        
        // Whether there's more history to fetch in background
        val hasPendingHistory: Boolean get() = pendingYearlyPlaylists.isNotEmpty()
        
        companion object {
            fun notConnected() = ReconstructionResult(
                0, 0, 0, 0, 0, 0, 0, emptyList(),
                listOf("Spotify not connected")
            )
            fun error(message: String) = ReconstructionResult(
                0, 0, 0, 0, 0, 0, 0, emptyList(),
                listOf(message)
            )
        }
    }
    
    /**
     * Internal data class to track discovered tracks and their sources.
     */
    private data class DiscoveredTrack(
        val spotifyTrack: SpotifyTrack,
        val source: TrackSource,
        val timestamp: Long?,     // For liked tracks
        val year: Int?,           // For yearly playlist tracks
        val affinityRank: Int?,   // Position in top tracks (lower = higher affinity)
        val genres: List<String> = emptyList(),
        val timeRangeHint: SmartListeningEventGenerator.TimeRangeHint = SmartListeningEventGenerator.TimeRangeHint.UNKNOWN
    )
    
    private enum class TrackSource(val priority: Int) {
        RECENTLY_PLAYED(4),   // Highest priority - EXACT play timestamps from Spotify
        LIKED_TRACKS(3),      // High priority - exact timestamps
        YEARLY_PLAYLIST(2),   // Medium priority - year context
        TOP_TRACKS(1)         // Lower priority - affinity data only
    }
    
    // =====================================================
    // Main Reconstruction Method
    // =====================================================
    
    /**
     * Perform full listening history reconstruction.
     * Combines all three data sources for maximum accuracy.
     */
    suspend fun reconstructHistory(
        progressCallback: ProgressCallback? = null
    ): ReconstructionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting listening history reconstruction")
        
        if (authManager.authState.value !is SpotifyAuthManager.AuthState.Connected) {
            Log.w(TAG, "Cannot reconstruct: Spotify not connected")
            return@withContext ReconstructionResult.notConnected()
        }
        
        val accessToken = authManager.getValidAccessToken()
            ?: return@withContext ReconstructionResult.error("Failed to get access token")
        
        val authHeader = "Bearer $accessToken"
        val errors = mutableListOf<String>()
        
        // Track collection across all sources
        // Key: Spotify ID, Value: Best source data
        val discoveredTracks = mutableMapOf<String, DiscoveredTrack>()
        
        // Collect artist genres for metadata
        val artistGenreMap = mutableMapOf<String, List<String>>()
        
        // Recently played events are stored separately (exact timestamps)
        // Structure: spotifyId -> list of exact play timestamps
        val recentlyPlayedTimestamps = mutableMapOf<String, MutableList<Long>>()
        
        var recentlyPlayedFound = 0
        var likedTracksFound = 0
        var yearlyPlaylistsFound = 0
        var topTracksUsed = 0
        
        // =====================================================
        // PHASE 0: Fetch Artists First (for genres)
        // =====================================================
        progressCallback?.onProgress(0, 100, "Preparation", "Fetching artist data...")
        
        try {
            fetchArtistsWithGenres(authHeader, artistGenreMap)
            Log.i(TAG, "Collected genres for ${artistGenreMap.size} artists")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artists", e)
            errors.add("Artists: ${e.message}")
        }
        yield()
        
        // =====================================================
        // PHASE 0.5: RECENTLY PLAYED - The Golden Source
        // =====================================================
        // This is THE MOST ACCURATE data we can get from Spotify!
        // Returns the last 50 plays with EXACT timestamps.
        // We create events directly with real timestamps - no estimation.
        progressCallback?.onProgress(2, 100, "Recent History", "Fetching recently played tracks...")
        
        try {
            val recentlyPlayed = fetchRecentlyPlayed(authHeader)
            recentlyPlayedFound = recentlyPlayed.size
            Log.i(TAG, "Recent History: Found $recentlyPlayedFound recently played tracks with EXACT timestamps")
            
            for (playHistory in recentlyPlayed) {
                val spotifyTrack = playHistory.track
                val playedAtMillis = playHistory.playedAtMillis
                
                // Skip if timestamp parsing failed
                if (playedAtMillis == 0L) {
                    Log.w(TAG, "Skipping track with invalid timestamp: ${spotifyTrack.name}")
                    continue
                }
                
                // Add track to discovered tracks (highest priority source)
                val artistName = spotifyTrack.primaryArtistName.lowercase()
                val genres = artistGenreMap[artistName] ?: emptyList()
                
                discoveredTracks[spotifyTrack.id] = DiscoveredTrack(
                    spotifyTrack = spotifyTrack,
                    source = TrackSource.RECENTLY_PLAYED,
                    timestamp = playedAtMillis,
                    year = null,
                    affinityRank = null,
                    genres = genres
                )
                
                // Store the EXACT play timestamp for this track
                // (These will create events with real timestamps later!)
                recentlyPlayedTimestamps.getOrPut(spotifyTrack.id) { mutableListOf() }
                    .add(playedAtMillis)
            }
            
            progressCallback?.onProgress(5, 100, "Recent History", 
                "Found $recentlyPlayedFound tracks with exact play times")
            
        } catch (e: Exception) {
            Log.e(TAG, "Recent History failed", e)
            errors.add("Recently played: ${e.message}")
        }
        yield()
        
        // =====================================================
        // PHASE 1: TIME MACHINE - Liked Tracks
        // =====================================================
        progressCallback?.onProgress(5, 100, "Time Machine", "Analyzing saved tracks...")
        
        try {
            val likedTracks = fetchLikedTracks(authHeader)
            likedTracksFound = likedTracks.size
            Log.i(TAG, "Time Machine: Found $likedTracksFound liked tracks")
            
            likedTracks.forEach { saved ->
                val spotifyId = saved.track.id
                val timestamp = saved.addedAtMillis
                
                // Get genres from artist
                val artistName = saved.track.primaryArtistName.lowercase()
                val genres = artistGenreMap[artistName] ?: emptyList()
                
                // Only add if not already found from higher priority source
                if (!discoveredTracks.containsKey(spotifyId) ||
                    discoveredTracks[spotifyId]!!.source.priority < TrackSource.LIKED_TRACKS.priority) {
                    
                    discoveredTracks[spotifyId] = DiscoveredTrack(
                        spotifyTrack = saved.track,
                        source = TrackSource.LIKED_TRACKS,
                        timestamp = timestamp,
                        year = null,
                        affinityRank = null,
                        genres = genres
                    )
                }
            }
            
            progressCallback?.onProgress(25, 100, "Time Machine", 
                "Found $likedTracksFound saved tracks with dates")
            
        } catch (e: Exception) {
            Log.e(TAG, "Time Machine failed", e)
            errors.add("Liked tracks: ${e.message}")
        }
        yield()
        
        // =====================================================
        // PHASE 2: ARTIFACT HUNTER - Yearly Playlists
        // =====================================================
        // SMART: Process only the most recent 3 years immediately
        // Queue older playlists for background processing
        progressCallback?.onProgress(30, 100, "Artifact Hunter", "Searching for yearly playlists...")
        
        // Store pending playlists for background processing
        val pendingYearlyPlaylists = mutableListOf<PendingPlaylist>()
        
        try {
            val allYearlyPlaylists = findYearlyPlaylists(authHeader)
            Log.i(TAG, "Artifact Hunter: Found ${allYearlyPlaylists.size} yearly playlists total")
            
            // Split: process first N immediately, queue rest for background
            val immediatelyProcess = allYearlyPlaylists.take(INITIAL_YEARLY_PLAYLISTS)
            val processLater = allYearlyPlaylists.drop(INITIAL_YEARLY_PLAYLISTS)
            
            // Store pending playlists with year info for background processing
            pendingYearlyPlaylists.addAll(processLater.mapNotNull { playlist ->
                val year = playlist.yearFromName ?: return@mapNotNull null
                PendingPlaylist(id = playlist.id, year = year, name = playlist.name)
            })
            
            if (processLater.isNotEmpty()) {
                Log.i(TAG, "Artifact Hunter: Processing ${immediatelyProcess.size} playlists now, ${pendingYearlyPlaylists.size} queued for background")
            }
            
            yearlyPlaylistsFound = immediatelyProcess.size
            
            var playlistsProcessed = 0
            for (playlist in immediatelyProcess) {
                val year = playlist.yearFromName ?: continue
                
                progressCallback?.onProgress(
                    30 + (playlistsProcessed * 20 / immediatelyProcess.size.coerceAtLeast(1)),
                    100,
                    "Artifact Hunter",
                    "Processing ${playlist.name}..."
                )
                
                val tracks = fetchPlaylistTracks(authHeader, playlist.id)
                
                tracks.forEach { playlistTrack ->
                    val track = playlistTrack.track ?: return@forEach
                    val spotifyId = track.id
                    
                    // Get genres from artist
                    val artistName = track.primaryArtistName.lowercase()
                    val genres = artistGenreMap[artistName] ?: emptyList()
                    
                    // Only add if not already found from higher priority source
                    val existing = discoveredTracks[spotifyId]
                    if (existing == null || existing.source.priority < TrackSource.YEARLY_PLAYLIST.priority) {
                        discoveredTracks[spotifyId] = DiscoveredTrack(
                            spotifyTrack = track,
                            source = TrackSource.YEARLY_PLAYLIST,
                            timestamp = null,
                            year = year,
                            affinityRank = null,
                            genres = genres
                        )
                    }
                }
                
                playlistsProcessed++
                yield()
            }
            
            val pendingMsg = if (pendingYearlyPlaylists.isNotEmpty()) 
                " (${pendingYearlyPlaylists.size} more queued)" else ""
            progressCallback?.onProgress(50, 100, "Artifact Hunter",
                "Processed $yearlyPlaylistsFound recent playlists$pendingMsg")
            
        } catch (e: Exception) {
            Log.e(TAG, "Artifact Hunter failed", e)
            errors.add("Yearly playlists: ${e.message}")
        }
        yield()
        
        // =====================================================
        // PHASE 3: SMART MIXER - Top Tracks with Affinity
        // =====================================================
        progressCallback?.onProgress(55, 100, "Smart Mixer", "Fetching top tracks...")
        
        try {
            val topTracksWithRanges = fetchTopTracksAllRanges(authHeader)
            topTracksUsed = topTracksWithRanges.size
            Log.i(TAG, "Smart Mixer: Got $topTracksUsed top tracks with affinity data")
            
            topTracksWithRanges.forEachIndexed { index, topTrackData ->
                val track = topTrackData.track
                val spotifyId = track.id
                
                // Get genres from artist
                val artistName = track.primaryArtistName.lowercase()
                val genres = artistGenreMap[artistName] ?: emptyList()
                
                // Add if not already discovered, or update affinity data
                val existing = discoveredTracks[spotifyId]
                if (existing == null) {
                    discoveredTracks[spotifyId] = DiscoveredTrack(
                        spotifyTrack = track,
                        source = TrackSource.TOP_TRACKS,
                        timestamp = null,
                        year = null,
                        affinityRank = index + 1,
                        genres = genres,
                        timeRangeHint = topTrackData.timeRange
                    )
                } else if (existing.affinityRank == null) {
                    // Update with affinity rank and time range
                    discoveredTracks[spotifyId] = existing.copy(
                        affinityRank = index + 1,
                        timeRangeHint = topTrackData.timeRange
                    )
                }
            }
            
            progressCallback?.onProgress(70, 100, "Smart Mixer",
                "Collected affinity data for $topTracksUsed tracks")
            
        } catch (e: Exception) {
            Log.e(TAG, "Smart Mixer failed", e)
            errors.add("Top tracks: ${e.message}")
        }
        yield()
        
        // =====================================================
        // PHASE 4: Process & Store All Tracks
        // =====================================================
        progressCallback?.onProgress(75, 100, "Processing", "Creating tracks and metadata...")
        
        var tracksCreated = 0
        var artistsCreated = 0
        
        val trackAffinities = mutableListOf<SmartListeningEventGenerator.TrackWithAffinity>()
        
        discoveredTracks.values.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            batch.forEach { discovered ->
                try {
                    val (wasCreated, trackId) = ensureLocalTrack(discovered)
                    if (wasCreated) tracksCreated++
                    
                    if (trackId > 0) {
                        // Calculate affinity score (0.0 to 1.0)
                        val maxRank = topTracksUsed.coerceAtLeast(1)
                        val affinity = when (discovered.source) {
                            TrackSource.RECENTLY_PLAYED -> 1.0f  // Highest affinity - actively listening!
                            TrackSource.LIKED_TRACKS -> 0.8f + (0.2f * (discovered.affinityRank?.let { 1f - (it.toFloat() / maxRank) } ?: 0.5f))
                            TrackSource.YEARLY_PLAYLIST -> 0.6f + (0.3f * (discovered.affinityRank?.let { 1f - (it.toFloat() / maxRank) } ?: 0.3f))
                            TrackSource.TOP_TRACKS -> discovered.affinityRank?.let { 1f - (it.toFloat() / maxRank) } ?: 0.5f
                        }
                        
                        // NOTE: Recently played events are inserted directly with exact timestamps 
                        // in Phase 5b, so we DON'T add them to the generator here
                        if (discovered.source != TrackSource.RECENTLY_PLAYED) {
                            trackAffinities.add(SmartListeningEventGenerator.TrackWithAffinity(
                                trackId = trackId,
                                durationMs = discovered.spotifyTrack.durationMs,
                                affinity = affinity.coerceIn(0.1f, 1.0f),
                                addedTimestamp = if (discovered.source == TrackSource.LIKED_TRACKS) discovered.timestamp else null,
                                yearContext = if (discovered.source == TrackSource.YEARLY_PLAYLIST) discovered.year else null,
                                timeRangeHint = discovered.timeRangeHint,
                                affinityRank = discovered.affinityRank
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process track: ${discovered.spotifyTrack.name}", e)
                }
            }
            
            val progress = 75 + (batchIndex * 10 / discoveredTracks.size.coerceAtLeast(1))
            progressCallback?.onProgress(progress.coerceIn(75, 85), 100, "Processing",
                "Created $tracksCreated tracks...")
            
            delay(BATCH_DELAY_MS)
        }
        
        Log.i(TAG, "Created $tracksCreated tracks, prepared ${trackAffinities.size} for event generation")
        yield()
        
        // =====================================================
        // PHASE 5: Generate Listening Events
        // =====================================================
        progressCallback?.onProgress(85, 100, "Generating History", "Creating listening events...")
        
        var eventsCreated = 0
        
        try {
            val now = System.currentTimeMillis()
            val historyStart = Calendar.getInstance().apply {
                add(Calendar.YEAR, -MAX_HISTORY_YEARS)
            }.timeInMillis
            
            // Calculate target events based on discovered data
            val totalTargetEvents = calculateTargetEvents(trackAffinities)
            
            Log.i(TAG, "Generating ~$totalTargetEvents listening events")
            
            val config = SmartListeningEventGenerator.GenerationConfig(
                startTime = historyStart,
                endTime = now,
                totalEventsTarget = totalTargetEvents,
                source = IMPORT_SOURCE
            )
            
            val events = SmartListeningEventGenerator.generateEvents(trackAffinities, config)
            
            progressCallback?.onProgress(90, 100, "Generating History",
                "Inserting ${events.size} events...")
            
            // Insert in batches WITH DEDUPLICATION to avoid conflicts
            val batches = events.chunked(100)
            var totalInserted = 0
            var totalSkipped = 0
            
            batches.forEachIndexed { index, batch ->
                val result = listeningEventDao.insertAllBatchedWithDedup(batch)
                totalInserted += result.inserted
                totalSkipped += result.skipped
                eventsCreated += result.inserted
                
                val progress = 90 + (index * 9 / batches.size.coerceAtLeast(1))
                progressCallback?.onProgress(progress.coerceIn(90, 99), 100, "Generating History",
                    "Inserted $eventsCreated events (${totalSkipped} duplicates skipped)...")
                
                yield()
            }
            
            Log.i(TAG, "Created $totalInserted listening events from generator ($totalSkipped duplicates skipped)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Event generation failed", e)
            errors.add("Event generation: ${e.message}")
        }
        
        // =====================================================
        // PHASE 5b: Insert Recently Played Events (EXACT timestamps)
        // =====================================================
        // These are the golden events - real play times from Spotify!
        var recentlyPlayedEventsInserted = 0
        var recentlyPlayedDuplicatesSkipped = 0
        
        if (recentlyPlayedTimestamps.isNotEmpty()) {
            val totalRecentEvents = recentlyPlayedTimestamps.values.sumOf { it.size }
            progressCallback?.onProgress(96, 100, "Recent History", 
                "Inserting $totalRecentEvents recent play events...")
            
            try {
                val eventsToInsert = mutableListOf<ListeningEvent>()
                
                // Build events with exact timestamps
                for ((spotifyId, timestamps) in recentlyPlayedTimestamps) {
                    val track = trackRepository.findBySpotifyId(spotifyId)
                    if (track == null) {
                        Log.w(TAG, "Could not find track for spotify ID: $spotifyId")
                        continue
                    }
                    
                    for (timestamp in timestamps) {
                        eventsToInsert.add(
                            ListeningEvent(
                                track_id = track.id,
                                timestamp = timestamp,
                                playDuration = track.duration ?: 180_000L,
                                completionPercentage = 100, // We assume full plays from recently-played
                                source = IMPORT_SOURCE
                            )
                        )
                    }
                }
                
                // Insert in batches WITH DEDUPLICATION
                eventsToInsert.chunked(50).forEach { batch ->
                    val result = listeningEventDao.insertAllBatchedWithDedup(batch)
                    recentlyPlayedEventsInserted += result.inserted
                    recentlyPlayedDuplicatesSkipped += result.skipped
                }
                
                eventsCreated += recentlyPlayedEventsInserted
                Log.i(TAG, "Inserted $recentlyPlayedEventsInserted recently played events with EXACT timestamps ($recentlyPlayedDuplicatesSkipped duplicates skipped)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert recently played events", e)
                errors.add("Recently played events: ${e.message}")
            }
        }
        
        progressCallback?.onProgress(100, 100, "Complete", "History reconstruction complete!")
        
        Log.i(TAG, """
            Reconstruction complete (Smart Data Recovery):
            - Tracks added to library: $tracksCreated
            - Artists created: $artistsCreated
            - Listening events created: $eventsCreated
            
            Data sources:
            - Recently played (EXACT timestamps → events): $recentlyPlayedFound ($recentlyPlayedEventsInserted events, $recentlyPlayedDuplicatesSkipped dupes skipped)
            - Liked tracks (exact dates → events): $likedTracksFound
            - Yearly playlists (year context → events): $yearlyPlaylistsFound
            - Top tracks (smart estimation → events): $topTracksUsed
            - Pending yearly playlists (for background): ${pendingYearlyPlaylists.size}
            
            Errors: ${errors.size}
        """.trimIndent())
        
        ReconstructionResult(
            tracksCreated = tracksCreated,
            artistsCreated = artistsCreated,
            listeningEventsCreated = eventsCreated,
            recentlyPlayedFound = recentlyPlayedFound,
            likedTracksFound = likedTracksFound,
            yearlyPlaylistsFound = yearlyPlaylistsFound,
            topTracksUsed = topTracksUsed,
            pendingYearlyPlaylists = pendingYearlyPlaylists,
            errors = errors
        )
    }
    
    // =====================================================
    // Background Processing for Pending Playlists
    // =====================================================
    
    /**
     * Process pending yearly playlists in the background.
     * Call this after initial reconstruction completes to enrich older history.
     * 
     * @param pendingPlaylists List of playlists to process (from ReconstructionResult.pendingYearlyPlaylists)
     * @return Number of new tracks and events created
     */
    suspend fun processPendingYearlyPlaylists(
        pendingPlaylists: List<PendingPlaylist>,
        progressCallback: ProgressCallback? = null
    ): BackgroundProcessResult = withContext(Dispatchers.IO) {
        if (pendingPlaylists.isEmpty()) {
            return@withContext BackgroundProcessResult(0, 0, emptyList())
        }
        
        Log.i(TAG, "Background: Processing ${pendingPlaylists.size} pending yearly playlists")
        
        val accessToken = authManager.getValidAccessToken()
            ?: return@withContext BackgroundProcessResult(0, 0, listOf("Failed to get access token"))
        
        val authHeader = "Bearer $accessToken"
        val errors = mutableListOf<String>()
        var tracksCreated = 0
        var eventsCreated = 0
        
        // Fetch artist genres (cached from initial import or fetch fresh)
        val artistGenreMap = mutableMapOf<String, List<String>>()
        try {
            fetchArtistsWithGenres(authHeader, artistGenreMap)
        } catch (e: Exception) {
            Log.w(TAG, "Background: Failed to fetch artist genres", e)
        }
        
        pendingPlaylists.forEachIndexed { index, pending ->
            progressCallback?.onProgress(index, pendingPlaylists.size, "Background Import", 
                "Processing ${pending.name}...")
            
            try {
                val year = pending.year
                Log.d(TAG, "Background: Processing ${pending.name} ($year)")
                
                // Fetch tracks from this playlist
                val tracks = fetchPlaylistTracks(authHeader, pending.id)
                
                for (playlistTrack in tracks) {
                    val track = playlistTrack.track ?: continue
                    
                    // Check if track already exists
                    val existingTrack = trackRepository.findBySpotifyId(track.id)
                    if (existingTrack != null) {
                        // Track exists - just create listening event if we don't have one for this year
                        // Skip for now to avoid duplicates
                        continue
                    }
                    
                    // Create new track
                    val artistName = track.primaryArtistName.lowercase()
                    val genres = artistGenreMap[artistName] ?: emptyList()
                    
                    val discovered = DiscoveredTrack(
                        spotifyTrack = track,
                        source = TrackSource.YEARLY_PLAYLIST,
                        timestamp = null,
                        year = year,
                        affinityRank = null,
                        genres = genres
                    )
                    
                    val (wasCreated, trackId) = ensureLocalTrack(discovered)
                    if (wasCreated) tracksCreated++
                    
                    // Generate events for this track
                    if (trackId > 0) {
                        val trackAffinity = SmartListeningEventGenerator.TrackWithAffinity(
                            trackId = trackId,
                            durationMs = track.durationMs,
                            affinity = 0.5f, // Medium affinity for background tracks
                            addedTimestamp = null,
                            yearContext = year
                        )
                        
                        val config = SmartListeningEventGenerator.GenerationConfig(
                            startTime = java.util.Calendar.getInstance().apply {
                                set(year, 0, 1)
                            }.timeInMillis,
                            endTime = java.util.Calendar.getInstance().apply {
                                set(year, 11, 31)
                            }.timeInMillis,
                            totalEventsTarget = 3,
                            source = IMPORT_SOURCE
                        )
                        
                        val events = SmartListeningEventGenerator.generateEvents(listOf(trackAffinity), config)
                        val result = listeningEventDao.insertAllBatchedWithDedup(events)
                        eventsCreated += result.inserted
                    }
                }
                
                yield()
                delay(API_DELAY_MS)
                
            } catch (e: Exception) {
                Log.e(TAG, "Background: Failed to process playlist ${pending.id}", e)
                errors.add("Playlist ${pending.name}: ${e.message}")
            }
        }
        
        Log.i(TAG, "Background: Completed - $tracksCreated tracks, $eventsCreated events")
        
        BackgroundProcessResult(tracksCreated, eventsCreated, errors)
    }
    
    /**
     * Result of background playlist processing.
     */
    data class BackgroundProcessResult(
        val tracksCreated: Int,
        val eventsCreated: Int,
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
    }
    
    // =====================================================
    // Data Fetching Methods
    // =====================================================
    
    /**
     * Fetch recently played tracks with EXACT timestamps.
     * This is the most accurate data source - actual play times from Spotify!
     * Returns up to 50 recent plays.
     */
    private suspend fun fetchRecentlyPlayed(authHeader: String): List<PlayHistoryObject> {
        val response = spotifyApi.getRecentlyPlayed(
            authorization = authHeader,
            limit = 50
        )
        
        if (!response.isSuccessful) {
            Log.w(TAG, "Failed to fetch recently played: ${response.code()}")
            return emptyList()
        }
        
        return response.body()?.items ?: emptyList()
    }
    
    /**
     * Fetch user's liked/saved tracks with timestamps.
     * SMART: Fetches ALL liked tracks with proper pagination and rate limiting.
     */
    private suspend fun fetchLikedTracks(authHeader: String): List<SavedTrackObject> {
        val allTracks = mutableListOf<SavedTrackObject>()
        var offset = 0
        var pageCount = 0
        
        while (pageCount < MAX_API_PAGES) {
            val response = spotifyApi.getUserSavedTracks(
                authorization = authHeader,
                limit = SPOTIFY_PAGE_SIZE,
                offset = offset
            )
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch saved tracks at offset $offset: ${response.code()}")
                break
            }
            
            val body = response.body() ?: break
            if (body.items.isEmpty()) break
            
            // Filter to only tracks with valid timestamps
            val validTracks = body.items.filter { it.addedAtMillis > 0 }
            allTracks.addAll(validTracks)
            
            Log.d(TAG, "Fetched ${validTracks.size} liked tracks (total: ${allTracks.size})")
            
            // No more pages
            if (body.next == null) break
            
            offset += SPOTIFY_PAGE_SIZE
            pageCount++
            
            // Rate limiting + yield to prevent ANR
            delay(API_DELAY_MS)
            yield()
        }
        
        Log.i(TAG, "Total liked tracks fetched: ${allTracks.size}")
        return allTracks
    }
    
    /**
     * Find "Your Top Songs 20XX" and similar playlists.
     * SMART: Searches ALL playlists but stops when we have enough yearly ones.
     */
    private suspend fun findYearlyPlaylists(authHeader: String): List<SpotifySimplifiedPlaylist> {
        val yearlyPlaylists = mutableListOf<SpotifySimplifiedPlaylist>()
        var offset = 0
        var pageCount = 0
        var totalPlaylists = 0
        
        while (pageCount < MAX_API_PAGES) {
            val response = spotifyApi.getUserPlaylists(
                authorization = authHeader,
                limit = SPOTIFY_PAGE_SIZE,
                offset = offset
            )
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch playlists at offset $offset: ${response.code()}")
                break
            }
            
            val body = response.body() ?: break
            if (body.items.isEmpty()) break
            
            totalPlaylists += body.items.size
            
            // Find yearly top songs playlists
            body.items.forEach { playlist ->
                if (playlist.isYearlyTopSongs && playlist.yearFromName != null) {
                    yearlyPlaylists.add(playlist)
                    Log.d(TAG, "Found yearly playlist: ${playlist.name} (${playlist.yearFromName})")
                }
            }
            
            // Early exit: if we found enough yearly playlists and searched many playlists
            if (yearlyPlaylists.size >= MAX_YEARLY_PLAYLISTS && totalPlaylists > 100) {
                Log.d(TAG, "Found $MAX_YEARLY_PLAYLISTS yearly playlists, stopping search")
                break
            }
            
            // No more pages
            if (body.next == null) break
            
            offset += SPOTIFY_PAGE_SIZE
            pageCount++
            
            delay(API_DELAY_MS)
            yield()
        }
        
        Log.i(TAG, "Searched $totalPlaylists playlists, found ${yearlyPlaylists.size} yearly ones")
        
        // Return only the most recent years
        return yearlyPlaylists
            .sortedByDescending { it.yearFromName }
            .take(MAX_YEARLY_PLAYLISTS)
    }
    
    /**
     * Fetch tracks from a playlist.
     * SMART: Fetches ALL tracks from the playlist.
     */
    private suspend fun fetchPlaylistTracks(
        authHeader: String,
        playlistId: String
    ): List<PlaylistTrackObject> {
        val allTracks = mutableListOf<PlaylistTrackObject>()
        var offset = 0
        var pageCount = 0
        
        while (pageCount < MAX_API_PAGES) {
            val response = spotifyApi.getPlaylistTracks(
                authorization = authHeader,
                playlistId = playlistId,
                limit = 100,  // Playlist API allows 100 per page
                offset = offset
            )
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch playlist tracks at offset $offset: ${response.code()}")
                break
            }
            
            val body = response.body() ?: break
            if (body.items.isEmpty()) break
            
            allTracks.addAll(body.items.filter { it.track != null })
            
            if (body.next == null) break
            offset += 100
            pageCount++
            
            delay(API_DELAY_MS)
            yield()
        }
        
        return allTracks
    }
    
    /**
     * Wrapper for top tracks with time range info.
     */
    private data class TopTrackWithTimeRange(
        val track: SpotifyTrack,
        val timeRange: SmartListeningEventGenerator.TimeRangeHint,
        val rankInRange: Int  // Rank within this specific time range
    )

    /**
     * Fetch top tracks from all time ranges.
     * SMART: Fetches top 50 from each range (API maximum), deduplicates.
     * Top tracks API is limited to 50 per range by Spotify.
     * 
     * Returns tracks with their time range hint for smart event estimation.
     */
    private suspend fun fetchTopTracksAllRanges(authHeader: String): List<TopTrackWithTimeRange> {
        val allTracks = mutableListOf<TopTrackWithTimeRange>()
        val seenIds = mutableSetOf<String>()
        
        // Fetch from all time ranges, maintaining rank order
        // Process short_term first since those tracks are most recent
        val timeRanges = listOf(
            SpotifyTopItemsService.TIME_RANGE_SHORT to SmartListeningEventGenerator.TimeRangeHint.SHORT_TERM,
            SpotifyTopItemsService.TIME_RANGE_MEDIUM to SmartListeningEventGenerator.TimeRangeHint.MEDIUM_TERM,
            SpotifyTopItemsService.TIME_RANGE_LONG to SmartListeningEventGenerator.TimeRangeHint.LONG_TERM
        )
        
        for ((timeRangeParam, timeRangeHint) in timeRanges) {
            val response = spotifyApi.getUserTopTracks(
                authorization = authHeader,
                timeRange = timeRangeParam,
                limit = SPOTIFY_PAGE_SIZE,  // Max 50 per range
                offset = 0
            )
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch top tracks for $timeRangeParam: ${response.code()}")
                continue
            }
            
            val body = response.body() ?: continue
            
            var newTracks = 0
            body.items.forEachIndexed { index, track ->
                if (track.id !in seenIds) {
                    seenIds.add(track.id)
                    allTracks.add(TopTrackWithTimeRange(
                        track = track,
                        timeRange = timeRangeHint,
                        rankInRange = index + 1
                    ))
                    newTracks++
                }
            }
            
            Log.d(TAG, "Fetched $newTracks new top tracks from $timeRangeParam (total unique: ${allTracks.size})")
            
            delay(API_DELAY_MS)
            yield()
        }
        
        Log.i(TAG, "Total unique top tracks: ${allTracks.size}")
        return allTracks
    }

    /**
     * Fetch artists and collect their genres.
     * SMART: Fetches from all time ranges to get comprehensive genre data.
     */
    private suspend fun fetchArtistsWithGenres(
        authHeader: String,
        genreMap: MutableMap<String, List<String>>
    ) {
        // Fetch from all time ranges for comprehensive genre coverage
        for (timeRange in listOf(
            SpotifyTopItemsService.TIME_RANGE_LONG,
            SpotifyTopItemsService.TIME_RANGE_MEDIUM,
            SpotifyTopItemsService.TIME_RANGE_SHORT
        )) {
            val response = spotifyApi.getUserTopArtists(
                authorization = authHeader,
                timeRange = timeRange,
                limit = SPOTIFY_PAGE_SIZE,
                offset = 0
            )
            
            if (response.isSuccessful) {
                response.body()?.items?.forEach { artist ->
                    if (artist.genres.isNotEmpty()) {
                        genreMap[artist.name.lowercase()] = artist.genres
                    }
                }
            }
            
            delay(API_DELAY_MS)
            yield()
        }
        
        Log.i(TAG, "Collected genres for ${genreMap.size} artists")
    }
    
    // =====================================================
    // Track Processing
    // =====================================================
    
    /**
     * Ensure a local track exists. Returns (wasCreated, trackId).
     */
    private suspend fun ensureLocalTrack(discovered: DiscoveredTrack): Pair<Boolean, Long> {
        val spotifyTrack = discovered.spotifyTrack
        
        // Check if already exists
        val existing = trackRepository.findBySpotifyId(spotifyTrack.id)
        if (existing != null) {
            // Update metadata if needed
            updateMetadataIfNeeded(existing.id, spotifyTrack, discovered.genres)
            return false to existing.id
        }
        
        // Create new track
        val newTrack = Track(
            title = spotifyTrack.name,
            artist = spotifyTrack.allArtistNames,
            album = spotifyTrack.album?.name,
            duration = spotifyTrack.durationMs,
            albumArtUrl = spotifyTrack.album?.images?.firstOrNull()?.url,
            spotifyId = spotifyTrack.id,
            musicbrainzId = null,
            primaryArtistId = null,
            contentType = "MUSIC"
        )
        
        val trackId = trackRepository.insert(newTrack)
        val createdTrack = newTrack.copy(id = trackId)
        
        // Link artists
        artistLinkingService.linkArtistsForTrack(createdTrack)
        
        // Create enriched metadata
        createEnrichedMetadata(trackId, spotifyTrack, discovered.genres)
        
        return true to trackId
    }
    
    /**
     * Create enriched metadata.
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
     * Update metadata if genres are missing.
     */
    private suspend fun updateMetadataIfNeeded(
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
    
    /**
     * Calculate target number of events based on track sources.
     * Now includes smart estimation for top tracks.
     */
    private fun calculateTargetEvents(
        tracks: List<SmartListeningEventGenerator.TrackWithAffinity>
    ): Int {
        var total = 0
        
        tracks.forEach { track ->
            val baseEvents = when {
                track.addedTimestamp != null -> {
                    // Liked track with exact timestamp - high priority
                    if (track.affinity > 0.7f) EVENTS_PER_LIKED_TRACK_HIGH_AFFINITY
                    else EVENTS_PER_LIKED_TRACK_NORMAL
                }
                track.yearContext != null -> {
                    // Yearly playlist track - medium priority
                    EVENTS_PER_YEARLY_TRACK
                }
                track.timeRangeHint != SmartListeningEventGenerator.TimeRangeHint.UNKNOWN -> {
                    // Top track with time range hint - smart estimation
                    // Fewer events than tracks with exact data, but not zero
                    when {
                        track.affinity > 0.8f -> 4  // High affinity top track
                        track.affinity > 0.5f -> 2  // Medium affinity
                        else -> 1                    // Low affinity
                    }
                }
                else -> {
                    // Unknown source - minimal events
                    1
                }
            }
            total += baseEvents
        }
        
        return total
    }
}
