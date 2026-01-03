package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.drive.*
import me.avinas.tempo.data.importexport.ImportExportManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for scheduled Google Drive backups.
 * 
 * Features:
 * - Creates backup using ImportExportManager
 * - Uploads to Google Drive via GoogleDriveService
 * - Shows notification on completion
 * - Auto-cleans old backups (keeps last 5)
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
        private const val WORK_NAME = "drive_backup"
        private const val CHANNEL_ID = "backup_notifications"
        private const val NOTIFICATION_ID = 2001
        
        /**
         * Schedule periodic backup with given interval.
         */
        fun schedule(context: Context, intervalHours: Long, wifiOnly: Boolean = true) {
            if (intervalHours <= 0) {
                cancel(context)
                return
            }
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresBatteryNotLow(true)
                .build()
            
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                intervalHours, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("drive_backup")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            
            Log.i(TAG, "Scheduled periodic backup every $intervalHours hours")
        }
        
        /**
         * Cancel scheduled backups.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled scheduled backups")
        }
        
        /**
         * Schedule a one-time backup (for manual trigger).
         */
        fun scheduleOneTime(context: Context, wifiOnly: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()
            
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("drive_backup_manual")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${WORK_NAME}_manual",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            
            Log.i(TAG, "Scheduled one-time backup")
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting Drive backup")
        
        // Update status to in progress
        settingsManager.updateLastBackup(BackupStatus.IN_PROGRESS)
        
        try {
            // Check if signed in - Workers cannot show UI, so use silent restore
            if (!authManager.isSignedIn.value) {
                Log.w(TAG, "Not signed in to Google, attempting silent session restore")
                if (!authManager.restoreSessionSilently()) {
                    Log.e(TAG, "Could not restore Google session - user needs to sign in via app")
                    settingsManager.updateLastBackup(BackupStatus.FAILED)
                    showNotification("Backup Failed", "Please sign in to Google in the app to enable backups")
                    return@withContext Result.failure()
                }
            }
            
            // Verify we have access token
            if (authManager.getAccessToken() == null) {
                Log.e(TAG, "No access token available - authorization may have expired")
                settingsManager.updateLastBackup(BackupStatus.FAILED)
                showNotification("Backup Failed", "Google authorization expired. Please sign in again.")
                return@withContext Result.failure()
            }
            
            // Get settings
            val settings = settingsManager.settings.first()
            
        // Create temporary file for backup
            val tempFile = File(context.cacheDir, "temp_backup.tempo")
            
            try {
                if (tempFile.exists()) tempFile.delete()
                
                // Create local backup using existing ImportExportManager
                setProgress(workDataOf("phase" to "Creating backup..."))
                
                val backupUri = Uri.fromFile(tempFile)
                val exportResult = importExportManager.exportData(
                    backupUri, 
                    settings.includeLocalImages
                )
                
                if (exportResult is me.avinas.tempo.data.importexport.ImportExportResult.Error) {
                    Log.e(TAG, "Failed to create local backup: ${exportResult.message}")
                    settingsManager.updateLastBackup(BackupStatus.FAILED)
                    showNotification("Backup Failed", exportResult.message)
                    return@withContext Result.failure()
                }
                
                // Upload to Google Drive
                setProgress(workDataOf("phase" to "Uploading to Google Drive..."))
                
                val uploadResult = driveService.uploadBackup(tempFile) { progress ->
                    setProgressAsync(workDataOf(
                        "phase" to "Uploading...",
                        "progress" to progress
                    ))
                }
                
                when (uploadResult) {
                    is DriveBackupResult.Success -> {
                        Log.i(TAG, "Backup uploaded successfully: ${uploadResult.fileName}")
                        settingsManager.updateLastBackup(BackupStatus.SUCCESS)
                        showNotification("Backup Complete", "Your data has been backed up to Google Drive")
                        Result.success()
                    }
                    is DriveBackupResult.Error -> {
                        Log.e(TAG, "Upload failed: ${uploadResult.message}")
                        settingsManager.updateLastBackup(BackupStatus.FAILED)
                        showNotification("Backup Failed", uploadResult.message)
                        Result.retry()
                    }
                }
            } finally {
                // Always clean up temp file
                if (tempFile.exists()) tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup worker failed", e)
            settingsManager.updateLastBackup(BackupStatus.FAILED)
            showNotification("Backup Failed", "An unexpected error occurred")
            Result.failure()
        }
    }
    
    private fun showNotification(title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create channel if needed
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Backup Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for backup status"
        }
        notificationManager.createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Backup Notifications",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Backing up to Google Drive")
            .setContentText("Creating backup...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
