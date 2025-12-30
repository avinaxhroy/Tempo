package me.avinas.tempo.worker

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.service.MusicTrackingService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * ServiceHealthWorker periodically checks if MusicTrackingService is running
 * and attempts to restart it if needed.
 * 
 * This is a safety net for cases where the system might kill the service
 * and fail to restart it automatically.
 */
@HiltWorker
class ServiceHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
        private const val WORK_NAME = "service_health_check"

        /**
         * Schedule periodic health checks for the tracking service.
         * Runs every 15 minutes (minimum for periodic work).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
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
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Log.i(TAG, "Service health check scheduled")
        }

        /**
         * Cancel the periodic health check.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Service health check cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running service health check")

        // Check if notification listener permission is granted
        if (!isNotificationListenerEnabled()) {
            Log.w(TAG, "Notification listener not enabled, cannot restart service")
            return Result.success() // Don't retry, user action needed
        }

        // Check if service is connected
        if (!isServiceRunning()) {
            Log.w(TAG, "Service not running, attempting restart")
            restartService()
        } else {
            Log.d(TAG, "Service is running normally")
        }

        return Result.success()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = applicationContext.packageName
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }

    private fun isServiceRunning(): Boolean {
        // Check if the notification listener service is in the enabled list
        // The system manages NotificationListenerService lifecycle
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        
        val expectedComponent = ComponentName(
            applicationContext,
            MusicTrackingService::class.java
        ).flattenToString()
        
        return flat?.contains(expectedComponent) == true
    }

    private fun restartService() {
        try {
            val componentName = ComponentName(
                applicationContext,
                MusicTrackingService::class.java
            )
            
            // Toggle component state to force system rebind
            val pm = applicationContext.packageManager
            
            pm.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            
            Thread.sleep(100)
            
            pm.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            
            Log.i(TAG, "Service restart requested via component toggle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service", e)
        }
    }
}
