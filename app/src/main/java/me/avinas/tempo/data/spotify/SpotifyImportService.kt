package me.avinas.tempo.data.spotify

import android.util.Log
import kotlinx.coroutines.delay
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.data.remote.spotify.PlayHistoryObject
import me.avinas.tempo.data.remote.spotify.SpotifyApi
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyTrack
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.TrackRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for importing listening history from Spotify's recently-played API.
 * 
 * =====================================================
 * PURPOSE: Onboarding Import
 * =====================================================
 * 
 * This service is designed for NEW USERS with zero data in Tempo.
 * It bootstraps their library with recent Spotify activity so they
 * have something to see immediately.
 * 
 * The Spotify API only provides the last ~50 plays (~24 hours of history).
 * This is intentionally limited and NOT a full historical import.
 * 
 * =====================================================
 * USAGE MODES
 * =====================================================
 * 
 * 1. ONE-TIME IMPORT (Onboarding)
 *    - Called during onboarding after Spotify connection
 *    - Imports available recent plays
 *    - User proceeds with notification-based tracking from then on
 * 
 * 2. SPOTIFY-API-ONLY MODE (Optional)
 *    - User chooses to rely on Spotify API instead of notifications
 *    - SpotifyPollingWorker periodically calls this service
 *    - Uses cursor-based pagination to only fetch new plays
 *    - Disables Spotify notification tracking to avoid duplicates
 * 
 * =====================================================
 * DATA FLOW
 * =====================================================
 * 
 * Spotify API → SpotifyImportService → Track + ListeningEvent → Room DB
 * 
 * Each PlayHistoryObject becomes:
 * - 1 Track (if not already exists by spotify_id)
 * - 1 ListeningEvent (always created, with played_at as timestamp)
 */
@Singleton
class SpotifyImportService @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val authManager: SpotifyAuthManager,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository,
    private val userPreferencesDao: UserPreferencesDao,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao
) {
    companion object {
        private const val TAG = "SpotifyImportService"
        
        // Rate limit protection
        private const val RATE_LIMIT_DELAY_MS = 100L
        
        // Source identifier for imported events
        const val IMPORT_SOURCE = "com.spotify.music.import"
        
        // Default assumed duration percentage for imported plays
        // We can't know actual play duration from recently-played API
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
    }
    
    /**
     * Result of an import operation.
     */
    data class ImportResult(
        val tracksImported: Int,
        val eventsCreated: Int,
        val duplicatesSkipped: Int,
        val errors: List<String>,
        val newCursor: String? = null // For incremental polling
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
        val totalProcessed: Int get() = tracksImported + duplicatesSkipped
        
        companion object {
            fun notConnected() = ImportResult(
                tracksImported = 0,
                eventsCreated = 0,
                duplicatesSkipped = 0,
                errors = listOf("Spotify not connected")
            )
            
            fun error(message: String) = ImportResult(
                tracksImported = 0,
                eventsCreated = 0,
                duplicatesSkipped = 0,
                errors = listOf(message)
            )
        }
    }
    
    /**
     * Import recently played tracks from Spotify.
     * 
     * For onboarding: Call with default parameters to fetch last 50 plays.
     * For polling: Pass afterCursor to only get new plays since last poll.
     * 
     * @param limit Max tracks to fetch (1-50)
     * @param afterCursor Unix timestamp cursor for incremental polling
     * @param onProgress Callback for progress updates (current, total)
     * @return ImportResult with statistics
     */
    suspend fun importRecentlyPlayed(
        limit: Int = 50,
        afterCursor: Long? = null,
        onProgress: ((imported: Int, total: Int) -> Unit)? = null
    ): ImportResult {
        // Check Spotify connection
        if (!authManager.isConnected()) {
            Log.w(TAG, "Cannot import: Spotify not connected")
            return ImportResult.notConnected()
        }
        
        // Get access token
        val accessToken = authManager.getValidAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "Failed to get valid access token")
            return ImportResult.error("Failed to authenticate with Spotify")
        }
        
        val authHeader = "Bearer $accessToken"
        
        return try {
            Log.i(TAG, "Starting Spotify import (limit=$limit, afterCursor=$afterCursor)")
            
            // Fetch recently played from Spotify
            val response = spotifyApi.getRecentlyPlayed(
                authorization = authHeader,
                limit = limit.coerceIn(1, 50),
                after = afterCursor
            )
            
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Spotify API error: ${response.code()} - $errorBody")
                
                return when (response.code()) {
                    401 -> ImportResult.error("Spotify session expired. Please reconnect.")
                    429 -> ImportResult.error("Rate limited by Spotify. Try again later.")
                    else -> ImportResult.error("Spotify API error: ${response.code()}")
                }
            }
            
            val recentlyPlayed = response.body()
            if (recentlyPlayed == null) {
                Log.w(TAG, "Empty response from Spotify")
                return ImportResult(0, 0, 0, emptyList())
            }
            
            val items = recentlyPlayed.items
            Log.i(TAG, "Fetched ${items.size} recently played tracks from Spotify")
            
            // Log sample of what we received for debugging
            if (items.isNotEmpty()) {
                val firstItem = items.first()
                Log.d(TAG, "Sample item - Track: '${firstItem.track.name}' by '${firstItem.track.allArtistNames}'")
                Log.d(TAG, "Sample item - played_at raw: '${firstItem.playedAt}'")
                Log.d(TAG, "Sample item - played_at parsed: ${firstItem.playedAtMillis}")
            }
            
            if (items.isEmpty()) {
                return ImportResult(0, 0, 0, emptyList(), recentlyPlayed.cursors?.after)
            }
            
            // Process each play history item
            var tracksImported = 0
            var eventsCreated = 0
            var duplicatesSkipped = 0
            val errors = mutableListOf<String>()
            
            items.forEachIndexed { index, playHistory ->
                try {
                    val result = processPlayHistoryItem(playHistory)
                    when (result) {
                        is ProcessResult.NewTrack -> {
                            tracksImported++
                            eventsCreated++
                        }
                        is ProcessResult.ExistingTrack -> {
                            eventsCreated++
                        }
                        is ProcessResult.Duplicate -> {
                            duplicatesSkipped++
                        }
                    }
                    onProgress?.invoke(index + 1, items.size)
                    
                    // Small delay to be nice to the database
                    if (index < items.size - 1) {
                        delay(RATE_LIMIT_DELAY_MS)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing track: ${playHistory.track.name}", e)
                    errors.add("Failed to import '${playHistory.track.name}'")
                }
            }
            
            // Save cursor for future polling
            val newCursor = recentlyPlayed.cursors?.after
            if (newCursor != null) {
                saveCursor(newCursor)
            }
            
            Log.i(TAG, "Import complete: $tracksImported new tracks, $eventsCreated events, $duplicatesSkipped duplicates")
            
            ImportResult(
                tracksImported = tracksImported,
                eventsCreated = eventsCreated,
                duplicatesSkipped = duplicatesSkipped,
                errors = errors,
                newCursor = newCursor
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.error("Import failed: ${e.message}")
        }
    }
    
    /**
     * Result of processing a single play history item.
     */
    private sealed class ProcessResult {
        object NewTrack : ProcessResult()
        object ExistingTrack : ProcessResult()
        object Duplicate : ProcessResult()
    }
    
    /**
     * Process a single play history item from Spotify.
     * Creates Track (if new), links artists, creates enriched metadata, and creates ListeningEvent.
     */
    private suspend fun processPlayHistoryItem(playHistory: PlayHistoryObject): ProcessResult {
        val spotifyTrack = playHistory.track
        val playedAtMillis = playHistory.playedAtMillis
        
        // CRITICAL: Validate the timestamp from Spotify
        // If parsing failed (playedAtMillis == 0), log error and skip this item
        if (playedAtMillis == 0L) {
            Log.e(TAG, "Invalid timestamp for track '${spotifyTrack.name}' - raw value: '${playHistory.playedAt}'")
            return ProcessResult.Duplicate // Treat as duplicate to skip but not count as error
        }
        
        // Log the actual played time for debugging
        val playedAtFormatted = java.time.Instant.ofEpochMilli(playedAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        Log.d(TAG, "Processing: '${spotifyTrack.name}' played at $playedAtFormatted (raw: ${playHistory.playedAt})")
        
        // Check if this exact play already exists (same track + same timestamp)
        // This prevents duplicates from multiple import runs
        val existingTrack = trackRepository.findBySpotifyId(spotifyTrack.id)
        
        if (existingTrack != null) {
            // Track exists - check if this specific listening event already exists
            val existingEvents = listeningRepository.getEventsForTrack(existingTrack.id)
            val alreadyImported = existingEvents.any { event ->
                // Consider it duplicate if timestamp is within 1 minute
                // (API timestamps might have slight variations)
                kotlin.math.abs(event.timestamp - playedAtMillis) < 60_000
            }
            
            if (alreadyImported) {
                Log.d(TAG, "Skipping duplicate: '${spotifyTrack.name}' at $playedAtFormatted")
                return ProcessResult.Duplicate
            }
            
            // Ensure existing track has artists linked (may have been imported before fix)
            if (existingTrack.primaryArtistId == null) {
                try {
                    artistLinkingService.linkArtistsForTrack(existingTrack)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to link artists for existing track ${existingTrack.id}", e)
                }
            }
            
            // Create listening event for existing track
            createListeningEvent(existingTrack.id, playHistory)
            return ProcessResult.ExistingTrack
        }
        
        // Create new track
        val track = convertToTrack(spotifyTrack)
        val trackId = trackRepository.insert(track)
        
        // CRITICAL: Link artists to the newly created track
        // This populates the artists table and track_artists junction table
        try {
            val insertedTrack = track.copy(id = trackId)
            artistLinkingService.linkArtistsForTrack(insertedTrack)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link artists for track $trackId: ${e.message}", e)
            // Continue anyway - track is created, artists can be linked later
        }
        
        // CRITICAL: Create enriched metadata with Spotify data
        // This ensures the track has album art, spotify IDs, and enrichment status
        createEnrichedMetadata(trackId, spotifyTrack)
        
        // Create listening event
        createListeningEvent(trackId, playHistory)
        
        Log.d(TAG, "Imported new track: '${spotifyTrack.name}' by '${spotifyTrack.allArtistNames}'")
        return ProcessResult.NewTrack
    }
    
    /**
     * Convert Spotify track to Tempo Track entity.
     */
    private fun convertToTrack(spotifyTrack: SpotifyTrack): Track {
        return Track(
            title = spotifyTrack.name,
            artist = spotifyTrack.allArtistNames,
            album = spotifyTrack.album.name,
            duration = spotifyTrack.durationMs,
            albumArtUrl = spotifyTrack.album.mediumImageUrl ?: spotifyTrack.album.largeImageUrl,
            spotifyId = spotifyTrack.id,
            musicbrainzId = null,
            primaryArtistId = null, // Will be linked by ArtistLinkingService later
            contentType = "MUSIC"
        )
    }
    
    /**
     * Create a ListeningEvent from Spotify play history.
     */
    private suspend fun createListeningEvent(trackId: Long, playHistory: PlayHistoryObject) {
        val spotifyTrack = playHistory.track
        val playedAtMillis = playHistory.playedAtMillis
        
        // Calculate estimated play duration
        // We assume 80% completion for imported plays since we don't have actual data
        val estimatedDuration = (spotifyTrack.durationMs * DEFAULT_COMPLETION_PERCENTAGE / 100)
        
        val event = ListeningEvent(
            track_id = trackId,
            timestamp = playedAtMillis,
            playDuration = estimatedDuration,
            completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
            source = IMPORT_SOURCE,
            wasSkipped = false,
            isReplay = false,
            estimatedDurationMs = spotifyTrack.durationMs,
            pauseCount = 0,
            sessionId = null,
            endTimestamp = playedAtMillis + estimatedDuration
        )
        
        listeningRepository.insert(event)
    }
    
    /**
     * Create enriched metadata record with Spotify data.
     * 
     * This is CRITICAL for imported tracks to have:
     * - Spotify IDs for future enrichment
     * - Album art with proper source tracking
     * - Artist IDs for fetching artist images
     * - Proper enrichment status so they're not re-processed unnecessarily
     */
    private suspend fun createEnrichedMetadata(trackId: Long, spotifyTrack: SpotifyTrack) {
        try {
            val primaryArtistId = spotifyTrack.artists.firstOrNull()?.id
            val allArtistIds = spotifyTrack.artists.map { it.id }.joinToString(",")
            val primaryArtistName = spotifyTrack.artists.firstOrNull()?.name
            
            val metadata = EnrichedMetadata(
                trackId = trackId,
                // Spotify IDs
                spotifyId = spotifyTrack.id,
                spotifyArtistId = primaryArtistId,
                spotifyArtistIds = allArtistIds,
                spotifyVerifiedArtist = primaryArtistName,
                spotifyTrackUrl = spotifyTrack.externalUrls.spotify,
                spotifyPreviewUrl = spotifyTrack.previewUrl,
                // Album info
                albumTitle = spotifyTrack.album.name,
                releaseDate = spotifyTrack.album.releaseDate,
                releaseYear = try { spotifyTrack.album.releaseDate.take(4).toIntOrNull() } catch (e: Exception) { null },
                releaseType = spotifyTrack.album.albumType,
                // Album art with source tracking
                albumArtUrl = spotifyTrack.album.mediumImageUrl,
                albumArtUrlSmall = spotifyTrack.album.smallImageUrl,
                albumArtUrlLarge = spotifyTrack.album.largeImageUrl,
                albumArtSource = if (spotifyTrack.album.mediumImageUrl != null) AlbumArtSource.SPOTIFY else AlbumArtSource.NONE,
                // Track info
                trackDurationMs = spotifyTrack.durationMs,
                isrc = spotifyTrack.externalIds?.isrc,
                previewUrl = spotifyTrack.previewUrl,
                // Artist info (from simplified artist object - images fetched separately)
                artistName = primaryArtistName,
                // Enrichment status - mark as having Spotify data but needing additional enrichment
                enrichmentStatus = EnrichmentStatus.PENDING, // Still needs genre/tags from Last.fm etc.
                spotifyEnrichmentStatus = SpotifyEnrichmentStatus.ENRICHED,
                spotifyLastAttempt = System.currentTimeMillis(),
                // Timestamp
                cacheTimestamp = System.currentTimeMillis()
            )
            
            enrichedMetadataDao.upsert(metadata)
            Log.d(TAG, "Created enriched metadata for track $trackId (spotify: ${spotifyTrack.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create enriched metadata for track $trackId", e)
            // Non-fatal - track is still imported, metadata can be enriched later
        }
    }
    
    /**
     * Save cursor for future incremental polling.
     */
    private suspend fun saveCursor(cursor: String) {
        try {
            val prefs = userPreferencesDao.getSync() ?: UserPreferences()
            userPreferencesDao.upsert(
                prefs.copy(
                    spotifyImportCursor = cursor,
                    lastSpotifyImportTimestamp = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Saved import cursor: $cursor")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save import cursor", e)
        }
    }
    
    /**
     * Get saved cursor for incremental polling.
     * Returns the cursor as a Long timestamp for the Spotify API's 'after' parameter.
     */
    suspend fun getSavedCursor(): Long? {
        return try {
            val prefs = userPreferencesDao.getSync()
            // spotifyImportCursor is the actual cursor from Spotify API
            // It's stored as a String but represents a Unix timestamp
            prefs?.spotifyImportCursor?.toLongOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get saved cursor", e)
            null
        }
    }
    
    /**
     * Check if import is available (Spotify connected and has valid token).
     */
    fun isAvailable(): Boolean = authManager.isConnected()
}
