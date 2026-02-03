package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.spotify.SpotifyImportService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * SpotifyPollingWorker periodically fetches recently played tracks from Spotify
 * when Spotify-API-Only mode is enabled.
 * 
 * This worker runs every 15 minutes (minimum for WorkManager) and uses cursor-based
 * pagination to only fetch new plays since the last poll.
 */
@HiltWorker
class SpotifyPollingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyImportService: SpotifyImportService,
    private val userPreferencesDao: UserPreferencesDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SpotifyPollingWorker"
        private const val WORK_NAME = "spotify_polling"
        
        /**
         * Schedule periodic polling when Spotify-API-Only mode is enabled.
         * Runs every 15 minutes (minimum for WorkManager periodic work).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // Need network for API calls
                .setRequiresBatteryNotLow(true) // Be nice to battery
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SpotifyPollingWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // Replace if already scheduled
                workRequest
            )

            Log.i(TAG, "Spotify polling scheduled (15-minute intervals)")
        }

        /**
         * Cancel the periodic polling when mode is disabled.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Spotify polling cancelled")
        }
        
        /**
         * Check if polling is currently scheduled.
         */
        suspend fun isScheduled(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
            return workInfos.any { 
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING 
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running Spotify polling")

        // Check if Spotify-API-Only mode is still enabled
        val prefs = userPreferencesDao.getSync()
        if (prefs == null || !prefs.spotifyApiOnlyMode) {
            Log.i(TAG, "Spotify-API-Only mode disabled, skipping poll and cancelling future runs")
            // Cancel self since mode is disabled
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
            return Result.success()
        }

        // Get cursor for incremental polling
        val cursor = prefs.spotifyImportCursor?.toLongOrNull()
        
        return try {
            Log.i(TAG, "Polling Spotify for new plays (cursor=$cursor)")
            
            val result = spotifyImportService.importRecentlyPlayed(
                limit = 50,
                afterCursor = cursor
            )
            
            if (result.isSuccess) {
                Log.i(TAG, "Polling complete: ${result.eventsCreated} new events, ${result.duplicatesSkipped} duplicates")
                Result.success()
            } else {
                val error = result.errors.firstOrNull() ?: "Unknown error"
                Log.e(TAG, "Polling failed: $error")
                
                // Retry on transient errors, fail on auth errors
                if (error.contains("session expired", ignoreCase = true) ||
                    error.contains("not connected", ignoreCase = true)) {
                    Result.failure() // Don't retry auth issues
                } else {
                    Result.retry() // Retry network issues, etc.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Polling failed with exception", e)
            Result.retry()
        }
    }
}
