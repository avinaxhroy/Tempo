package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import me.avinas.tempo.data.drive.DriveException
import me.avinas.tempo.data.drive.DriveHistorySyncManager
import me.avinas.tempo.data.drive.DriveHistorySyncResult
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import java.util.concurrent.TimeUnit

@HiltWorker
class DriveHistorySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: DriveHistorySyncManager,
    private val settingsManager: DriveHistorySyncSettingsManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DriveHistorySyncWorker"
        const val WORK_NAME = "drive_history_sync"
        private const val MANUAL_WORK_NAME = "drive_history_sync_manual"
        private const val DEFAULT_INTERVAL_HOURS = 6L

        fun schedule(context: Context, intervalHours: Long = DEFAULT_INTERVAL_HOURS) {
            val request = PeriodicWorkRequestBuilder<DriveHistorySyncWorker>(
                intervalHours.coerceAtLeast(1L), TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveHistorySyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(MANUAL_WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val settings = settingsManager.settings.first()
        if (!settings.enabled) return Result.success()

        return when (val result = syncManager.syncNow()) {
            DriveHistorySyncResult.Disabled -> Result.success()
            is DriveHistorySyncResult.RemoteDisabled -> {
                Log.i(TAG, result.message)
                // The manager already cleared the opt-in after it safely cleaned
                // any remaining cloud batches. Remove future wakeups immediately.
                cancel(applicationContext)
                Result.success()
            }
            is DriveHistorySyncResult.Success -> {
                Log.i(
                    TAG,
                    "Drive history sync complete: uploaded=${result.uploaded}, imported=${result.imported}, duplicates=${result.duplicates}"
                )
                Result.success()
            }
            is DriveHistorySyncResult.Error -> {
                Log.w(TAG, "Drive history sync failed: ${result.message}", result.exception)

                // Consent/session problems require a user gesture in the settings
                // screen. Re-running the same WorkManager attempt cannot fix them
                // and would only waste battery. The periodic schedule remains and
                // will try again later after the user reconnects.
                val authFailure = result.exception is DriveException.Auth ||
                    result.message.contains("authorization", ignoreCase = true) ||
                    result.message.contains("reconnect", ignoreCase = true) ||
                    result.message.contains("session expired", ignoreCase = true)
                if (authFailure || runAttemptCount >= 3) Result.success() else Result.retry()
            }
        }
    }
}
