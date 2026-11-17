package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.drive.BackupInterval
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.BackupStatus
import me.avinas.tempo.data.drive.DriveBackupResult
import me.avinas.tempo.data.drive.DriveException
import me.avinas.tempo.data.drive.DriveFailureNotice
import me.avinas.tempo.data.drive.GoogleAuthManager
import me.avinas.tempo.data.drive.GoogleDriveService
import me.avinas.tempo.data.drive.toDriveFailureNotice
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.utils.ForegroundNotificationGuard
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Periodic Google Drive backup worker.
 *
 * Device backups intentionally use [LocalBackupWorker] instead. Keeping the two
 * schedules independent means a Drive retry (no Wi-Fi, expired authorization,
 * server outage, etc.) can never postpone the next Daily/Weekly/Monthly device
 * backup.
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val importExportManager: ImportExportManager,
    private val driveService: GoogleDriveService,
    private val authManager: GoogleAuthManager,
    private val settingsManager: BackupSettingsManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DriveBackupWorker"
        const val WORK_NAME = "drive_backup"
        const val MANUAL_WORK_NAME = "${WORK_NAME}_manual"
        private const val CHANNEL_ID = "backup_notifications"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RUN_RETRIES = 3

        internal fun shouldRetryFailure(runAttemptCount: Int): Boolean =
            runAttemptCount < MAX_RUN_RETRIES

        internal fun isArchiveReusable(
            archiveExists: Boolean,
            archiveLength: Long,
            readyMarkerRunId: String?,
            backupRunId: String
        ): Boolean =
            archiveExists && archiveLength > 0L && readyMarkerRunId == backupRunId

        internal fun networkSatisfiesDrivePolicy(
            hasValidatedInternet: Boolean,
            wifiOnly: Boolean,
            isWifiTransport: Boolean
        ): Boolean = hasValidatedInternet && (!wifiOnly || isWifiTransport)

        /**
         * Drops the "sign in again" failure notification. It is auto-cancel only on
         * tap, so without this it survives the sign-in that fixed the problem and
         * keeps telling the user the session expired.
         */
        internal fun clearFailureNotification(context: Context) {
            try {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clear the Drive backup notification", e)
            }
        }

        internal fun shouldSkipBackup(
            backupInterval: BackupInterval,
            driveEnabled: Boolean,
            isManualRequest: Boolean
        ): Boolean =
            !driveEnabled ||
                (backupInterval == BackupInterval.MANUAL && !isManualRequest)

        fun schedule(
            context: Context,
            intervalHours: Long
        ) {
            if (intervalHours <= 0L) {
                cancel(context)
                return
            }

            // CONNECTED prevents pointless executions while fully offline. Exact
            // Wi-Fi-only behavior is checked in doWork because NetworkType.UNMETERED
            // is not equivalent to Wi-Fi.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                intervalHours,
                TimeUnit.HOURS,
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
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

            Log.i(TAG, "Scheduled Drive backup every $intervalHours hours")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic Drive backups")
        }

        fun cancelManual(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
            Log.i(TAG, "Cancelled manual Drive backup")
        }

        /** True while the periodic Drive worker is actually executing. */
        suspend fun isScheduledRunActive(context: Context): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(WORK_NAME)
                        .get()
                        .any { it.state == WorkInfo.State.RUNNING }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runStateDir = runStateDir()

        try {
            val settings = settingsManager.settings.first()

            // A periodic worker already running/retrying may observe Manual/sign-out
            // before scheduler cancellation reaches it. A one-time manual request uses
            // this same worker class, so Manual must suppress only the periodic path.
            val isManualRequest = tags.contains(MANUAL_WORK_NAME)
            if (shouldSkipBackup(
                    backupInterval = settings.backupInterval,
                    driveEnabled = settings.isGoogleDriveEnabled,
                    isManualRequest = isManualRequest
                )
            ) {
                cleanupRunState()
                return@withContext Result.success()
            }

            if (!isDriveNetworkAvailable(settings.wifiOnly)) {
                // Not a failure: nothing was attempted, the phone simply is not on
                // the configured network. Retrying quietly keeps last night's
                // success from turning into "Drive Backup Failed".
                Log.i(TAG, "Configured Drive network is not available yet; retrying later")
                return@withContext if (shouldRetryFailure(runAttemptCount)) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }

            if ((!runStateDir.exists() && !runStateDir.mkdirs()) || !runStateDir.isDirectory) {
                throw java.io.IOException("Unable to create Drive backup working directory")
            }

            // A PeriodicWorkRequest keeps the same WorkRequest UUID across periods.
            // runAttemptCount, however, resets between periods. Generate a fresh
            // logical backup id on the first attempt of each period, then persist and
            // reuse it for every retry of that same period.
            val backupRunId = getOrCreateBackupRunId(runStateDir)
            val tempFile = File(runStateDir, "backup.tempo")
            val archiveReadyMarker = File(runStateDir, "archive.ready")

            setForeground(getForegroundInfo())
            updateStatusSafely(BackupStatus.IN_PROGRESS)

            if (!authManager.isSignedIn.value) {
                Log.i(TAG, "Restoring Google session silently for scheduled Drive backup")
                if (!authManager.restoreSessionSilently()) {
                    return@withContext retryOrFinish(
                        runStateDir,
                        "Open Tempo and sign in to Google again to resume automatic Drive backups",
                        title = "Drive Backup Needs Sign-In"
                    )
                }
            }

            if (authManager.getAccessToken() == null) {
                return@withContext retryOrFinish(
                    runStateDir,
                    "Google authorization expired. Open Tempo and sign in again.",
                    title = "Drive Backup Needs Sign-In"
                )
            }

            val readyMarkerRunId = runCatching {
                archiveReadyMarker.takeIf { it.exists() }?.readText()?.trim()
            }.getOrNull()
            if (!isArchiveReusable(
                    tempFile.exists(),
                    tempFile.length(),
                    readyMarkerRunId,
                    backupRunId
                )
            ) {
                tempFile.delete()
                archiveReadyMarker.delete()
                setProgress(workDataOf("phase" to "Creating Drive backup..."))

                val exportResult = importExportManager.exportToFile(
                    tempFile,
                    settings.includeLocalImages
                )
                if (exportResult is ImportExportResult.Error ||
                    !tempFile.exists() || tempFile.length() <= 0L
                ) {
                    val message = if (exportResult is ImportExportResult.Error) {
                        exportResult.message
                    } else {
                        "Backup archive was not created correctly"
                    }
                    return@withContext retryOrFinish(
                        runStateDir,
                        message,
                        title = "Backup Could Not Be Created"
                    )
                }
                archiveReadyMarker.writeText(backupRunId)
            } else {
                Log.i(TAG, "Reusing Drive archive from an earlier attempt of this WorkManager run")
            }

            setProgress(workDataOf("phase" to "Uploading to Google Drive..."))
            when (val uploadResult = driveService.uploadBackup(
                tempFile,
                idempotencyKey = backupRunId
            ) { progress ->
                setProgressAsync(
                    workDataOf(
                        "phase" to "Uploading...",
                        "progress" to progress
                    )
                )
            }) {
                is DriveBackupResult.Success -> {
                    updateStatusSafely(BackupStatus.SUCCESS)
                    showNotification(
                        "Drive Backup Complete",
                        "Your Tempo data has been backed up to Google Drive"
                    )
                    runStateDir.deleteRecursively()
                    Result.success()
                }

                is DriveBackupResult.Error -> {
                    retryOrFinish(
                        runStateDir,
                        uploadResult.message,
                        cause = uploadResult.exception
                    )
                }
            }
        } catch (e: CancellationException) {
            // Remove the private archive immediately, but keep the tiny logical
            // run ID. WorkManager can stop and re-enqueue this same work when a
            // constraint changes; the upload may already have reached Drive, so
            // losing the ID here would let the resumed attempt create a duplicate.
            BackupRunState.discardSnapshotPreservingRunId(runStateDir)
            // A run stopped by sign-out or by switching the interval to Manual never
            // reaches retryOrFinish, so without this lastBackupStatus stays
            // IN_PROGRESS until the Backup screen is opened again.
            withContext(NonCancellable) { markInterruptedIfInProgress() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled Drive backup failed", e)
            retryOrFinish(
                runStateDir,
                "The backup stopped unexpectedly. Open Tempo, then try Backup Now to see details.",
                cause = e
            )
        }
    }

    private fun getOrCreateBackupRunId(runStateDir: File): String {
        val marker = File(runStateDir, "backup_run_id")

        // WorkManager documents that runAttemptCount resets between periods. On
        // retries (> 0), keep the marker written by this period's first attempt.
        if (runAttemptCount > 0) {
            val existing = runCatching { marker.readText().trim() }.getOrNull()
            if (!existing.isNullOrBlank()) return existing
        }

        val fresh = UUID.randomUUID().toString()
        marker.writeText(fresh)
        return fresh
    }

    private fun runStateDir(): File =
        File(context.cacheDir, "tempo_drive_backup_runs/$id")

    private fun cleanupRunState() {
        File(context.cacheDir, "tempo_drive_backup_runs/$id").deleteRecursively()
    }

    private suspend fun retryOrFinish(
        runStateDir: File,
        message: String,
        cause: Throwable? = null,
        title: String? = null
    ): Result {
        updateStatusSafely(BackupStatus.FAILED)

        val notice = if (title != null) {
            DriveFailureNotice(title, message)
        } else {
            cause.toDriveFailureNotice(message)
        }
        // Re-uploading the same bytes cannot fix a failed verification, so that case
        // is reported once instead of being retried three times.
        val retryable = cause !is DriveException.Corrupt

        return if (retryable && shouldRetryFailure(runAttemptCount)) {
            Log.w(
                TAG,
                "Retrying Drive backup after failure (attempt=$runAttemptCount): " +
                    "${notice.title} — ${notice.message}"
            )
            notifyFirstAttempt(notice.title, notice.message)
            Result.retry()
        } else {
            showNotification(notice.title, notice.message)
            runStateDir.deleteRecursively()
            Result.success()
        }
    }

    private suspend fun updateStatusSafely(status: BackupStatus) {
        try {
            settingsManager.updateLastBackup(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist Drive backup status $status", e)
        }
    }

    /**
     * Flips a cancelled run out of IN_PROGRESS. Any other status belongs to a
     * different run and is left untouched.
     */
    private suspend fun markInterruptedIfInProgress() {
        val status = try {
            settingsManager.settings.first().lastBackupStatus
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (status == BackupStatus.IN_PROGRESS) {
            updateStatusSafely(BackupStatus.FAILED)
        }
    }

    /**
     * Enforce the user's network policy against Android's active route.
     *
     * Looking for any Wi-Fi network in ConnectivityManager.allNetworks can report
     * a secondary Wi-Fi transport while Android is actually routing app traffic
     * over cellular. For "Wi-Fi only" the safer rule is therefore: the validated
     * active network itself must advertise TRANSPORT_WIFI. This intentionally
     * prefers never using cellular over trying to infer a VPN's underlying route.
     */
    private fun isDriveNetworkAvailable(wifiOnly: Boolean): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        val hasValidatedInternet =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isWifiTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        return networkSatisfiesDrivePolicy(
            hasValidatedInternet = hasValidatedInternet,
            wifiOnly = wifiOnly,
            isWifiTransport = isWifiTransport
        )
    }

    private fun notifyFirstAttempt(title: String, message: String) {
        if (runAttemptCount == 0) {
            showNotification(title, message)
        }
    }

    /**
     * Opens the app when a backup notification is tapped. Without a content
     * intent, tapping "Drive Backup Failed" did nothing at all — the message
     * could not lead anywhere.
     */
    private fun createContentPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showNotification(title: String, message: String) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Backup Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(createContentPendingIntent())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // A denied notification permission must not turn a completed backup
            // into a failed/retried backup.
            Log.w(TAG, "Unable to show Drive backup notification", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backup Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backing up Tempo to Google Drive")
            .setContentText("Creating backup...")
            .setContentIntent(createContentPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return ForegroundNotificationGuard.postableForegroundInfo(
            context = context,
            notificationId = NOTIFICATION_ID,
            requestedChannelId = CHANNEL_ID,
            notification = notification,
            logTag = TAG,
        )
    }
}
