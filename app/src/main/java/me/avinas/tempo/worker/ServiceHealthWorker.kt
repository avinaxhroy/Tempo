package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsHealthReporter
import me.avinas.tempo.data.analytics.RecoveryAction
import me.avinas.tempo.data.analytics.RevivedBy
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.service.TrackingServiceHeartbeat
import me.avinas.tempo.utils.ForegroundNotificationGuard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * ServiceHealthWorker periodically checks if MusicTrackingService is running
 * and attempts to restart it if needed.
 * 
 * This is a safety net for cases where the system might kill the service
 * and fail to restart it automatically.
 *
 * This is also the single best vantage point for tracking-health reporting: it already
 * distinguishes "permission is granted" from "the listener is actually alive", so recoveries
 * are reported from here rather than by instrumenting the 3,000-line listener service.
 */
@HiltWorker
class ServiceHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val healthReporter: AnalyticsHealthReporter
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
        private const val WORK_NAME = "service_health_check"
        private const val WORK_NAME_ON_OPEN = "service_health_check_on_open"
        private const val NOTIFICATION_CHANNEL_ID = "service_health_worker"
        private const val NOTIFICATION_ID = 3007 // Distinct from ChallengeWorker (3005) and NotificationWorker challenge (3006)

        /**
         * Schedule periodic health checks for the tracking service.
         *
         * The interval is matched to
         * [TrackingServiceHeartbeat.HEARTBEAT_STALE_MS] (30 minutes): a heartbeat is only ever
         * considered stale once it is that old, so checking every 15 minutes — the platform
         * minimum, which this used to do — woke the whole process twice as often as the check
         * could possibly have anything to report. The work itself is trivial, but each run starts
         * the process and builds the Hilt graph, which is the real cost.
         *
         * `setRequiresBatteryNotLow(true)` replaces the previous explicit "run even on low
         * battery": restarting a dead listener is a repair, not time-critical, and it should not
         * be starting a process while the battery is already low. A still-dead listener is picked
         * up by the next run once the battery recovers.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                30, TimeUnit.MINUTES,
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
                // UPDATE, not KEEP: KEEP would leave every existing install on the old
                // 15-minute cadence, so the change would only reach new installs.
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.i(TAG, "Service health check scheduled")
        }

        /**
         * Run the health check once, now.
         *
         * The periodic cadence is what bounds the kill window while the app is never opened; it
         * also means a listener killed by an aggressive OEM stays dead for up to half an hour
         * after the user is already looking at the app. Opening the app is the one moment where
         * that repair is free: the process is up, the device is awake, and the user is about to
         * notice the missing history.
         *
         * A one-time request under its own name rather than [schedule]: re-enqueueing
         * the periodic work clears its cadence and would push the next real check out by a full
         * interval.
         */
        fun enqueueCheckNow(context: Context) {
            // No constraints: this is a few local reads plus, at worst, one rebind, and it only
            // ever runs because the app is already in the foreground.
            val workRequest = OneTimeWorkRequestBuilder<ServiceHealthWorker>().build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ON_OPEN,
                // KEEP: two resumes in quick succession must not stack up checks behind each other.
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Service health check requested (app opened)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Service health check cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running service health check")
        val componentName = ComponentName(
            applicationContext,
            MusicTrackingService::class.java
        )

        // Self-heal: if the listener component is stuck DISABLED (e.g. a previous
        // restart attempt died mid-toggle), re-enable it so tracking can recover
        // without a reinstall. The health check runs about every 30 minutes even if
        // the app is never opened, so the kill window is bounded.
        try {
            val state = applicationContext.packageManager.getComponentEnabledSetting(componentName)
            if (state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                Log.w(TAG, "Listener component is DISABLED — re-enabling (self-heal)")
                applicationContext.packageManager.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                requestListenerRebind(componentName)
                // The component was found outright disabled, so the gap is not measurable
                // here — reported as unknown rather than guessed at.
                healthReporter.reportIfDue(
                    gapMillis = null,
                    by = RevivedBy.HEALTH_WORKER,
                    recovery = RecoveryAction.COMPONENT_REENABLE,
                )
                return Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/re-enable listener component", e)
        }

        // Check if notification listener permission is granted
        if (!isNotificationListenerEnabled(componentName)) {
            Log.w(TAG, "Notification listener not enabled, cannot restart service")
            return Result.success() // Don't retry, user action needed
        }

        val heartbeat = TrackingServiceHeartbeat.snapshot(applicationContext)
        if (heartbeat.shouldRequestRebind()) {
            Log.w(TAG, "Tracking listener heartbeat stale: $heartbeat")
            if (heartbeat.shouldForceRestartAfterRebind()) {
                Log.w(TAG, "Previous rebind did not refresh heartbeat, forcing component restart")
                reportStaleTracking(heartbeat, RecoveryAction.FORCE_RESTART)
                restartService(componentName)
            } else {
                reportStaleTracking(heartbeat, RecoveryAction.REBIND)
                requestListenerRebind(componentName)
            }
        } else {
            Log.d(TAG, "Tracking listener heartbeat healthy: $heartbeat")
        }

        return Result.success()
    }

    /**
     * Reports that tracking had gone stale and how this worker responded.
     *
     * `gap` is how long the listener had been dead, which is the number that actually matters:
     * a thirty-second gap is a non-event, a three-day gap means a user silently lost history.
     *
     * Skipped entirely when the listener has never been alive, because on a fresh install
     * `shouldRequestRebind()` is true simply because nothing has started yet. Reporting that
     * as a gap would make every new user look like a tracking failure.
     */
    private suspend fun reportStaleTracking(
        heartbeat: TrackingServiceHeartbeat.Snapshot,
        recovery: RecoveryAction
    ) {
        if (!heartbeat.hasEverStarted()) return

        val gapMillis = if (heartbeat.lastServiceAliveAt > 0L) {
            System.currentTimeMillis() - heartbeat.lastServiceAliveAt
        } else {
            null
        }

        healthReporter.reportIfDue(
            gapMillis = gapMillis,
            by = RevivedBy.HEALTH_WORKER,
            recovery = recovery,
        )
    }

    private fun isNotificationListenerEnabled(componentName: ComponentName): Boolean {
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        
        val expectedComponent = componentName.flattenToString()
        val expectedShortComponent = componentName.flattenToShortString()

        return (flat
            ?.split(':')
            ?.any { it == expectedComponent || it == expectedShortComponent }
            == true)
    }

    private fun requestListenerRebind(componentName: ComponentName) {
        try {
            NotificationListenerService.requestRebind(componentName)
            TrackingServiceHeartbeat.markRebindRequested(applicationContext)
            Log.i(TAG, "Notification listener rebind requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request listener rebind", e)
        }
    }

    private suspend fun restartService(componentName: ComponentName) {
        val pm = applicationContext.packageManager
        try {
            // Toggle component state to force system rebind.
            //
            // The re-enable MUST run even if the disable or the delay
            // throws — otherwise the listener component is left DISABLED and
            // tracking stays dead until the user reinstalls (the exact failure
            // users report). Re-enable in a finally block and verify the final
            // state afterwards.
            try {
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                kotlinx.coroutines.delay(100)
            } finally {
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }

            // Verify we actually ended up enabled; if not, force it once more.
            val finalState = pm.getComponentEnabledSetting(componentName)
            if (finalState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                Log.e(TAG, "Component still DISABLED after toggle — forcing ENABLED")
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }

            TrackingServiceHeartbeat.markRebindRequested(applicationContext)
            Log.i(TAG, "Service restart requested via component toggle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service", e)
            // Last resort: make sure we never leave the component disabled.
            try {
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            } catch (ignored: Exception) {
                Log.e(TAG, "Failed to re-enable component after restart error", ignored)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Service Health Check",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Service Health Check")
            .setContentText("Monitoring music tracking service...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundNotificationGuard.postableForegroundInfo(
            context = applicationContext,
            notificationId = NOTIFICATION_ID,
            requestedChannelId = NOTIFICATION_CHANNEL_ID,
            notification = notification,
            logTag = TAG,
        )
    }
}
