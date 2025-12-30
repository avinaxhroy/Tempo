package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authManager: SpotifyAuthManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TokenRefreshWorker"
        private const val WORK_NAME = "spotify_token_refresh"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Refresh every 45 minutes (token expires in 60 mins)
            val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(45, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!authManager.isConnected()) {
            return Result.success()
        }

        return try {
            Log.d(TAG, "Refreshing Spotify token")
            val token = authManager.getValidAccessToken()
            if (token != null) {
                Log.d(TAG, "Token refreshed successfully")
                Result.success()
            } else {
                Log.e(TAG, "Failed to refresh token")
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            Result.retry()
        }
    }
}
