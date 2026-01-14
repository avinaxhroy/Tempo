package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.enrichment.LastFmEnrichmentService
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.data.enrichment.ReccoBeatsEnrichmentService
import me.avinas.tempo.data.enrichment.SpotifyEnrichmentService
import me.avinas.tempo.data.enrichment.ITunesEnrichmentService
import me.avinas.tempo.data.enrichment.DeezerEnrichmentService
import me.avinas.tempo.data.enrichment.EnrichmentSource
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.AudioFeaturesSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.ArtistParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that enriches unenriched tracks with metadata from external APIs.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This worker is the ONLY component that makes external API calls for enrichment.
 * It follows a strict data flow pattern to minimize API requests and ensure
 * consistent data management:
 * 
 * 1. ENRICHMENT (This Worker)
 *    - Makes API calls to Spotify, ReccoBeats, MusicBrainz, and Last.fm
 *    - Processes tracks in background batches
 *    - Respects rate limits and retries with backoff
 *    
 * 2. DATABASE (Room via DAOs)
 *    - All enriched data is stored locally
 *    - Acts as the single source of truth
 *    - Caches data indefinitely (songs don't change)
 *    
 * 3. UI (ViewModels via Repositories)
 *    - NEVER makes direct API calls
 *    - Always reads from database cache
 *    - Gets fast, offline-first experience
 * 
 * Benefits:
 * - API requests are batched and scheduled efficiently
 * - UI is never blocked waiting for API responses
 * - Works offline with cached data
 * - Rate limits are respected in background
 * - User gets instant access to whatever data is cached
 * 
 * =====================================================
 * MODULAR Enrichment Strategy (Fallback Chain):
 * 
 * AUDIO FEATURES FALLBACK (since Spotify deprecated audio-features API for indie devs):
 * Spotify History → MusicBrainz/Last.fm → ReccoBeats DB → ReccoBeats Analysis → Local Media → Spotify Artist Features
 * 
 * Data sources are combined to get the best possible metadata:
 * 1. Spotify (if user connected) - Most accurate: album, artist, artwork, ISRC
 *    - Audio features from user's listening history (if track was played before with OAuth)
 * 2. MusicBrainz - Community curated: genres, tags, record labels, release info  
 * 3. Last.fm - Excellent tag/genre data, similar artists
 * 4. ReccoBeats DB - FREE audio features lookup (if track exists in their database)
 * 5. ReccoBeats Analysis - Analyze Spotify's 30s preview audio (slower, requires preview URL)
 * 6. Local MediaSession - Fallback from music player metadata
 * 7. Spotify Artist Features - Derive approximate audio features from artist's top tracks (last resort)
 * 
 * The key insight: Sources are NOT mutually exclusive. If one source provides
 * partial data, we SUPPLEMENT with other sources rather than skipping entirely.
 * 
 * Flow:
 * - If Spotify connected → Use Spotify for album, artist, artwork, ISRC
 *   - Check if we have cached audio features from user's history
 *   - If no audio features → Supplement with MusicBrainz/Last.fm for genres/tags
 *   - If still no audio features → Try ReccoBeats DB lookup
 *   - If ReccoBeats DB fails → Try ReccoBeats Analysis (30s preview)
 *   - If still nothing → Try deriving from Spotify Artist's top tracks features
 * - If Spotify not connected → Use MusicBrainz as primary source
 *   - If MusicBrainz fails → Try Last.fm
 *   - If still no audio features → Try ReccoBeats DB/Analysis
 * - If no external source finds track → Fall back to MediaSession data
 * 
 * This multi-source approach ensures best coverage especially for:
 * - Regional/indie music (Indian hip-hop, etc.)
 * - Recently released tracks
 * - Non-Western music catalogs
 * 
 * Features:
 * - Prioritizes frequently played tracks
 * - Respects API rate limits
 * - Retries failed enrichments with backoff
 * - Refreshes stale cache (>6 months old)
 * - Runs periodically in background
 */
@HiltWorker
class EnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyEnrichmentSource: me.avinas.tempo.data.enrichment.SpotifyEnrichmentSource,
    private val musicBrainzEnrichmentSource: me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentSource,
    private val lastFmEnrichmentSource: me.avinas.tempo.data.enrichment.LastFmEnrichmentSource,
    private val iTunesEnrichmentSource: me.avinas.tempo.data.enrichment.ITunesEnrichmentSource,
    private val deezerEnrichmentSource: me.avinas.tempo.data.enrichment.DeezerEnrichmentSource,
    private val reccoBeatsEnrichmentSource: me.avinas.tempo.data.enrichment.ReccoBeatsEnrichmentSource,
    private val spotifyArtistFeaturesSource: me.avinas.tempo.data.enrichment.SpotifyArtistFeaturesSource,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val trackDao: TrackDao,
    private val statsRepository: StatsRepository
) : CoroutineWorker(appContext, workerParams) {

    private val strategies: List<EnrichmentSource> = listOf(
        spotifyEnrichmentSource,
        musicBrainzEnrichmentSource,
        lastFmEnrichmentSource,
        iTunesEnrichmentSource,
        deezerEnrichmentSource,
        reccoBeatsEnrichmentSource,
        spotifyArtistFeaturesSource
    ).sortedBy { it.priority }

    companion object {
        private const val TAG = "EnrichmentWorker"
        private const val WORK_NAME = "music_enrichment"
        private const val WORK_NAME_IMMEDIATE = "music_enrichment_immediate"
        private const val NOTIFICATION_CHANNEL_ID = "enrichment_worker"
        private const val NOTIFICATION_ID = 3002
        
        // How many tracks to process per run
        private const val BATCH_SIZE = 10
        private const val RETRY_BATCH_SIZE = 5
        
        // Delay between processing each track (respects rate limit)
        private const val INTER_TRACK_DELAY_MS = 1500L

        /**
         * Schedule periodic enrichment work.
         * Runs every 1 hour when device is idle and connected.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<EnrichmentWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("enrichment")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "Periodic enrichment scheduled")
        }

        /**
         * Trigger immediate enrichment for a specific track or batch.
         * When trackId is null, only processes completely unenriched tracks to avoid excessive API calls.
         */
        fun enqueueImmediate(context: Context, trackId: Long? = null) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = if (trackId != null) {
                workDataOf("track_id" to trackId)
            } else {
                // Flag to indicate this is app-startup enrichment (only process pending tracks)
                workDataOf("is_immediate" to true)
            }

            val workRequest = OneTimeWorkRequestBuilder<EnrichmentWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("enrichment_immediate")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )

            Log.i(TAG, "Immediate enrichment enqueued" + (trackId?.let { " for track $it" } ?: ""))
        }

        /**
         * Cancel all enrichment work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Log.i(TAG, "Enrichment work cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting enrichment work")

        // Check if specific track ID was provided
        val specificTrackId = inputData.getLong("track_id", -1).takeIf { it > 0 }

        return try {
            // First, backfill any missing album art URLs to tracks table
            backfillAlbumArtUrls()
            
            if (specificTrackId != null) {
                // Enrich specific track
                enrichSpecificTrack(specificTrackId)
            } else {
                // Batch enrichment
                enrichBatch()
            }
            
            Log.i(TAG, "Enrichment work completed successfully")
            
            // Invalidate stats cache to ensure UI picks up new metadata immediately
            statsRepository.invalidateCache()
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Enrichment work failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    /**
     * Backfill album art URLs from enriched_metadata to tracks table.
     * This fixes tracks that were enriched before the Track update was added.
     */
    private suspend fun backfillAlbumArtUrls() {
        try {
            val tracksToBackfill = enrichedMetadataDao.getEnrichedTracksWithMissingAlbumArt(50)
            if (tracksToBackfill.isEmpty()) return
            
            Log.d(TAG, "Backfilling album art for ${tracksToBackfill.size} tracks")
            
            for (metadata in tracksToBackfill) {
                val track = trackDao.getTrackById(metadata.trackId)
                if (track != null && metadata.albumArtUrl != null) {
                    // Fix HTTP URLs to HTTPS for better reliability
                    val fixedArtUrl = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrl)
                    val updatedTrack = track.copy(
                        albumArtUrl = fixedArtUrl,
                        album = if (track.album.isNullOrBlank()) metadata.albumTitle else track.album
                    )
                    trackDao.update(updatedTrack)
                    
                    // Also update the enriched metadata if URL was fixed
                    if (fixedArtUrl != metadata.albumArtUrl) {
                        enrichedMetadataDao.upsert(metadata.copy(
                            albumArtUrl = fixedArtUrl,
                            albumArtUrlSmall = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrlSmall),
                            albumArtUrlLarge = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrlLarge)
                        ))
                    }
                }
            }
            
            Log.i(TAG, "Backfilled album art for ${tracksToBackfill.size} tracks")
        } catch (e: Exception) {
            Log.w(TAG, "Error backfilling album art", e)
        }
    }

    private suspend fun enrichSpecificTrack(trackId: Long) {
        val track = trackDao.getTrackById(trackId)
        if (track == null) {
            Log.w(TAG, "Track $trackId not found")
            return
        }
        
        // Skip enrichment if artist is unknown - metadata hasn't settled yet
        if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
            Log.d(TAG, "Skipping enrichment for track $trackId: artist is unknown")
            return
        }

        // Modular Fallback Loop
        Log.d(TAG, "Starting modular enrichment for track $trackId")
        
        var metadata = enrichedMetadataDao.forTrackSync(trackId)
        
        // Loop through strategies in priority order
        // Only run a strategy if there is a gap it can fill
        for (strategy in strategies) {
            val currentGap = metadata?.identifyGap() ?: me.avinas.tempo.data.enrichment.EnrichmentGap(
                missingAlbumArt = true,
                missingGenres = true,
                missingAudioFeatures = true,
                missingArtistImage = true,
                missingPreviewUrl = true
            )
            
            if (currentGap.isEmpty()) {
                Log.d(TAG, "Track $trackId is fully enriched. Stopping chain.")
                break
            }
            
            if (strategy.canProvide(currentGap)) {
                Log.d(TAG, "Applying strategy ${strategy.name} for track $trackId (Gap: $currentGap)")
                val updated = strategy.enrich(track, metadata)
                if (updated != null) {
                    metadata = updated
                    Log.d(TAG, "Strategy ${strategy.name} updated metadata for $trackId")
                } else {
                    Log.d(TAG, "Strategy ${strategy.name} provided no updates for $trackId")
                }
            } else {
                 Log.v(TAG, "Strategy ${strategy.name} skipped (Cannot provide for gap: $currentGap)")
            }
        }
        
        // Genre Fallback: Infer from artist's other tracks if still missing genres
        if (metadata != null) {
            if (metadata.genres.isNullOrEmpty()) {
                Log.d(TAG, "Track $trackId: No genres from external sources, attempting inference from artist's other tracks")
                
                val primaryArtist = ArtistParser.getPrimaryArtist(track.artist)
                val artistGenreJsonList = enrichedMetadataDao.getGenresFromArtistOtherTracks(
                    artistName = primaryArtist,
                    excludeTrackId = trackId,
                    limit = 5
                )
                
                // Parse genre JSON strings and find the most common ones
                val allGenres = mutableListOf<String>()
                for (genreJson in artistGenreJsonList) {
                    try {
                        // Genres are stored as JSON arrays like ["pop", "rock"]
                        val cleanJson = genreJson.trim()
                        if (cleanJson.startsWith("[") && cleanJson.endsWith("]")) {
                            val genreList = cleanJson
                                .removePrefix("[").removeSuffix("]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"") }
                                .filter { it.isNotBlank() }
                            allGenres.addAll(genreList)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse genre JSON: $genreJson", e)
                    }
                }
                
                if (allGenres.isNotEmpty()) {
                    // Find the most common genre
                    val genreCounts = allGenres.groupingBy { it.lowercase() }.eachCount()
                    val topGenres = genreCounts.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { it.key.replaceFirstChar { c -> c.uppercase() } }
                    
                    Log.i(TAG, "Track $trackId: Inferred genres from artist's other tracks: $topGenres")
                    
                    val updatedMetadata = metadata.copy(
                        genres = topGenres,
                        enrichmentStatus = me.avinas.tempo.data.local.entities.EnrichmentStatus.ENRICHED,
                        cacheTimestamp = System.currentTimeMillis()
                    )
                    enrichedMetadataDao.upsert(updatedMetadata)
                } else {
                    Log.d(TAG, "Track $trackId: No genres found from artist's other tracks either")
                }
            }
        }
        
        // CRITICAL FIX: Explicitly update the Track entity with the enriched album art
        // The UI observes the Track table, not EnrichedMetadata, so we must propagate the URL immediately
        // This fixes the issue where enrichment succeeds but the UI still shows no cover art
        try {
            // Re-fetch latest metadata to get the most up-to-date URL (from strategies or genre inference)
            val finalMetadata = enrichedMetadataDao.forTrackSync(trackId)
            
            if (finalMetadata?.albumArtUrl != null) {
                // Fix HTTP URLs to HTTPS for better reliability
                val fixedArtUrl = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrl)
                
                // We have a URL, ensure it's on the track
                // Note: We don't need to re-fetch the track, we can use the ID
                val currentTrack = trackDao.getTrackById(trackId)
                if (currentTrack != null && currentTrack.albumArtUrl != fixedArtUrl) {
                    Log.i(TAG, "Propagating enriched album art to Track $trackId: $fixedArtUrl")
                    val updatedTrack = currentTrack.copy(
                        albumArtUrl = fixedArtUrl,
                        // Also update album name if track is missing it
                        album = if (currentTrack.album.isNullOrBlank()) finalMetadata.albumTitle else currentTrack.album
                    )
                    trackDao.update(updatedTrack)
                    
                    // Also update the enriched metadata if URL was changed
                    if (fixedArtUrl != finalMetadata.albumArtUrl) {
                        Log.d(TAG, "Fixed HTTP URL to HTTPS for track $trackId")
                        enrichedMetadataDao.upsert(finalMetadata.copy(
                            albumArtUrl = fixedArtUrl,
                            albumArtUrlSmall = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrlSmall),
                            albumArtUrlLarge = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrlLarge)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to propagate album art to Track entity", e)
        }
    }

    private suspend fun enrichBatch() {
        var enrichedCount = 0
        var failedCount = 0
        
        // Check if this is an immediate enrichment (triggered on app start)
        // If so, only process completely unenriched tracks to avoid hammering APIs
        val isImmediateEnrichment = inputData.getBoolean("is_immediate", false)

        // 1. Process pending tracks (completely unenriched)
        val pendingTracks = enrichedMetadataDao.getTracksNeedingEnrichment(
            status = EnrichmentStatus.PENDING,
            limit = BATCH_SIZE
        )
        
        Log.d(TAG, "Found ${pendingTracks.size} pending tracks to enrich")
        
        for (metadata in pendingTracks) {
            if (isStopped) break

            val trackId = metadata.trackId
            enrichSpecificTrack(trackId)
            
            // Check success based on updated metadata
            val updated = enrichedMetadataDao.forTrackSync(trackId)
            val success = updated?.enrichmentStatus == EnrichmentStatus.ENRICHED || 
                          (updated?.genres?.isNotEmpty() == true) || 
                          (updated?.audioFeaturesJson != null)
            
            if (success) enrichedCount++ else failedCount++

            kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
        }
        
        // Skip retry/refresh steps for immediate enrichment to avoid excessive API calls on app start
        if (isImmediateEnrichment) {
            Log.d(TAG, "Immediate enrichment complete: ${enrichedCount} enriched, ${failedCount} failed")
            Log.i(TAG, "Batch complete: enriched=$enrichedCount, failed=$failedCount")
            return
        }

        // 2. Retry enriched tracks with missing cover art or genres (only for periodic work)
        if (!isStopped) {
            val incompleteTracks = enrichedMetadataDao.getEnrichedTracksWithIncompleteData(
                retryAfter = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L),
                limit = 3
            )
            
            Log.d(TAG, "Found ${incompleteTracks.size} enriched tracks with incomplete data")
            
            for (metadata in incompleteTracks) {
                if (isStopped) break
                
                Log.d(TAG, "Re-enriching incomplete track ${metadata.trackId}")
                enrichSpecificTrack(metadata.trackId)
                kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
            }
        }

        // 3. Retry failed tracks (only for periodic work)
        if (!isStopped) {
            val retryTracks = enrichedMetadataDao.getTracksToRetry(
                status = EnrichmentStatus.FAILED,
                maxRetries = EnrichedMetadata.MAX_RETRY_COUNT,
                limit = RETRY_BATCH_SIZE
            )
            
            Log.d(TAG, "Found ${retryTracks.size} failed tracks to retry")
            
            for (metadata in retryTracks) {
                if (isStopped) break
                
                enrichSpecificTrack(metadata.trackId) // This will try all strategies again
                kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
            }
        }

        // Note: We don't refresh stale cache for fully enriched tracks because:
        // - Album art URLs rarely change
        // - Genres/metadata are stable once set
        // - Refreshing wastes API quota and bandwidth
        // Only incomplete/failed tracks get retried above
        
        // 5. Fetch missing artist images (Legacy loop reduced, mostly handled by Strategies now)
        // We still keep a small check for tracks that might have missed it
        if (!isStopped && spotifyEnrichmentSource.isAvailable()) {
             // This is largely redundant now but good for cleanup of old data
             val artistsNeedingImages = enrichedMetadataDao.getTracksNeedingArtistImage(limit = 10)
             for (metadata in artistsNeedingImages) {
                 if (isStopped) break
                 if (metadata.spotifyArtistId != null) {
                     // Trigger Spotify strategy specifically? 
                     // Or just let enrichSpecificTrack handle it if we passed the track ID?
                     // Since we have the ID, calling enrichSpecificTrack is safer and consistent
                     enrichSpecificTrack(metadata.trackId) 
                 }
                 kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
             }
        }

        Log.i(TAG, "Batch complete: enriched=$enrichedCount, failed=$failedCount")
    }
    
    /**
     * Required for expedited work on Android 10 (SDK 29).
     * Returns ForegroundInfo with notification when work runs as foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Music Enrichment",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Enriching music data")
            .setContentText("Adding metadata to your tracks...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
