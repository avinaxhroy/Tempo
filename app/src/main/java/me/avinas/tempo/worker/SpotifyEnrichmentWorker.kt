package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.data.enrichment.SpotifyEnrichmentService
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that enriches tracks with Spotify audio features.
 * 
 * This worker is OPTIONAL and only runs when the user has connected their Spotify account.
 * It enriches tracks in background to add advanced audio analysis features.
 * 
 * Features:
 * - Prioritizes frequently played tracks
 * - Respects API rate limits
 * - Only runs when Spotify is connected
 * - Gracefully handles disconnection during enrichment
 */
@HiltWorker
class SpotifyEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyEnrichmentService: SpotifyEnrichmentService,
    private val authManager: SpotifyAuthManager,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val trackDao: TrackDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SpotifyEnrichWorker"
        private const val WORK_NAME = "spotify_enrichment"
        private const val WORK_NAME_IMMEDIATE = "spotify_enrichment_immediate"
        
        // How many tracks to process per run
        private const val BATCH_SIZE = 10
        
        // Delay between processing each track (ms)
        private const val INTER_TRACK_DELAY_MS = 200L

        /**
         * Schedule periodic Spotify enrichment work.
         * Runs every 30 minutes when connected to network.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SpotifyEnrichmentWorker>(
                30, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("spotify_enrichment")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "Periodic Spotify enrichment scheduled")
        }

        /**
         * Trigger immediate Spotify enrichment.
         * Called after user connects Spotify to quickly enrich existing tracks.
         */
        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SpotifyEnrichmentWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("spotify_enrichment_immediate")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.i(TAG, "Immediate Spotify enrichment enqueued")
        }

        /**
         * Cancel all Spotify enrichment work.
         * Called when user disconnects Spotify.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Log.i(TAG, "Spotify enrichment work cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting Spotify enrichment work")

        // Check if Spotify is connected
        if (!authManager.isConnected()) {
            Log.i(TAG, "Spotify not connected, skipping enrichment")
            return Result.success()
        }

        return try {
            val enrichedCount = enrichBatch()
            Log.i(TAG, "Spotify enrichment completed: $enrichedCount tracks enriched")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Spotify enrichment failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun enrichBatch(): Int {
        // Get tracks needing Spotify enrichment
        val tracksToEnrich = enrichedMetadataDao.getTracksNeedingSpotifyEnrichment(BATCH_SIZE)
        
        if (tracksToEnrich.isEmpty()) {
            Log.d(TAG, "No tracks need Spotify enrichment")
            return 0
        }

        Log.d(TAG, "Found ${tracksToEnrich.size} tracks to enrich with Spotify")

        // OPTIMIZATION: Check if we have tracks with Spotify IDs but missing audio features
        // These can be bulk-enriched in a single API call per 100 tracks!
        val withSpotifyId = tracksToEnrich.filter { it.spotifyId != null && it.audioFeaturesJson == null }
        val withoutSpotifyId = tracksToEnrich.filter { it.spotifyId == null }
        
        var successCount = 0
        
        // Phase 1: Bulk enrich tracks that already have Spotify IDs (super efficient!)
        if (withSpotifyId.isNotEmpty()) {
            Log.d(TAG, "Bulk enriching ${withSpotifyId.size} tracks that have Spotify IDs")
            val bulkSuccess = spotifyEnrichmentService.enrichAudioFeaturesForKnownTracks(withSpotifyId)
            successCount += bulkSuccess
            
            // Update status for successful ones
            withSpotifyId.forEach { metadata ->
                if (metadata.audioFeaturesJson != null) {
                    enrichedMetadataDao.updateSpotifyEnrichmentStatus(
                        trackId = metadata.trackId,
                        status = SpotifyEnrichmentStatus.ENRICHED
                    )
                }
            }
        }
        
        // Phase 2: Search and enrich tracks without Spotify IDs
        for (metadata in withoutSpotifyId) {
            // Check if still connected
            if (!authManager.isConnected()) {
                Log.w(TAG, "Spotify disconnected during enrichment, stopping")
                break
            }

            // Get the track
            val track = trackDao.all().first().find { it.id == metadata.trackId }
            if (track == null) {
                Log.w(TAG, "Track ${metadata.trackId} not found")
                continue
            }

            // Attempt enrichment
            when (val result = spotifyEnrichmentService.enrichTrack(track, metadata)) {
                is SpotifyEnrichmentService.SpotifyEnrichmentResult.Success -> {
                    enrichedMetadataDao.updateSpotifyEnrichmentStatus(
                        trackId = metadata.trackId,
                        status = SpotifyEnrichmentStatus.ENRICHED
                    )
                    successCount++
                    Log.d(TAG, "Enriched track ${track.id}: '${track.title}'")
                }
                
                is SpotifyEnrichmentService.SpotifyEnrichmentResult.TrackNotFound -> {
                    enrichedMetadataDao.updateSpotifyEnrichmentStatus(
                        trackId = metadata.trackId,
                        status = SpotifyEnrichmentStatus.NOT_FOUND
                    )
                    Log.d(TAG, "Track not found on Spotify: '${track.title}'")
                }
                
                is SpotifyEnrichmentService.SpotifyEnrichmentResult.AudioFeaturesNotAvailable -> {
                    enrichedMetadataDao.updateSpotifyEnrichmentStatus(
                        trackId = metadata.trackId,
                        status = SpotifyEnrichmentStatus.UNAVAILABLE
                    )
                    Log.d(TAG, "Audio features not available: '${track.title}'")
                }
                
                is SpotifyEnrichmentService.SpotifyEnrichmentResult.NotConnected -> {
                    Log.w(TAG, "Spotify not connected, stopping batch")
                    break
                }
                
                is SpotifyEnrichmentService.SpotifyEnrichmentResult.Error -> {
                    enrichedMetadataDao.updateSpotifyEnrichmentStatus(
                        trackId = metadata.trackId,
                        status = SpotifyEnrichmentStatus.FAILED,
                        error = result.message
                    )
                    Log.e(TAG, "Error enriching track: ${result.message}")
                }
            }

            // Rate limiting delay
            kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
        }

        return successCount
    }
}
