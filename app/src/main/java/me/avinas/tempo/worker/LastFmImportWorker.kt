package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.local.dao.LastFmImportMetadataDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.LastFmImportMetadata
import java.util.concurrent.TimeUnit

/**
 * Background worker for Last.fm import operations.
 * 
 * Supports two modes:
 * 
 * 1. INITIAL IMPORT (One-time)
 *    - Large import that may take 20+ minutes
 *    - Runs in foreground with notification
 *    - Resumable if interrupted
 * 
 * 2. INCREMENTAL SYNC (Periodic)
 *    - Fetches new scrobbles since last sync
 *    - Runs every 24 hours (configurable)
 *    - Quick operation (usually < 1 minute)
 */
@HiltWorker
class LastFmImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val lastFmImportService: LastFmImportService,
    private val importMetadataDao: LastFmImportMetadataDao,
    private val userPreferencesDao: UserPreferencesDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LastFmImportWorker"
        
        // Notification constants
        private const val NOTIFICATION_CHANNEL_ID = "lastfm_import_channel"
        private const val NOTIFICATION_ID = 9001
        private const val NOTIFICATION_COMPLETION_ID = 9002
        
        // Work names
        private const val INITIAL_IMPORT_WORK = "lastfm_initial_import"
        private const val INCREMENTAL_SYNC_WORK = "lastfm_incremental_sync"
        
        // Input keys
        const val KEY_USERNAME = "username"
        const val KEY_TIER = "tier"
        const val KEY_TOTAL_SCROBBLES = "total_scrobbles"
        const val KEY_IS_RESUME = "is_resume"
        const val KEY_IMPORT_ID = "import_id"
        
        // Output keys
        const val KEY_SUCCESS = "success"
        const val KEY_EVENTS_IMPORTED = "events_imported"
        const val KEY_TRACKS_CREATED = "tracks_created"
        const val KEY_ERROR_MESSAGE = "error_message"
        
        /**
         * Create the notification channel for Last.fm imports.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Last.fm Import",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress notifications for Last.fm history import"
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }
        
        /**
         * Enqueue initial import work.
         * 
         * Uses expedited work with foreground service for long-running imports.
         */
        fun enqueueInitialImport(
            context: Context,
            username: String,
            tier: String,
            totalScrobbles: Long
        ): java.util.UUID {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = workDataOf(
                KEY_USERNAME to username,
                KEY_TIER to tier,
                KEY_TOTAL_SCROBBLES to totalScrobbles,
                KEY_IS_RESUME to false
            )

            val workRequest = OneTimeWorkRequestBuilder<LastFmImportWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("lastfm_import")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_IMPORT_WORK,
                ExistingWorkPolicy.KEEP, // Don't interrupt existing import
                workRequest
            )

            Log.i(TAG, "Enqueued initial Last.fm import for $username (tier=$tier)")
            return workRequest.id
        }
        
        /**
         * Resume a previously interrupted import.
         */
        fun enqueueResumeImport(
            context: Context,
            importId: Long
        ): java.util.UUID {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = workDataOf(
                KEY_IS_RESUME to true,
                KEY_IMPORT_ID to importId
            )

            val workRequest = OneTimeWorkRequestBuilder<LastFmImportWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("lastfm_import")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_IMPORT_WORK,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.i(TAG, "Enqueued resume of Last.fm import (id=$importId)")
            return workRequest.id
        }
        
        /**
         * Schedule periodic incremental sync.
         * 
         * @param frequency Sync frequency: "DAILY" or "WEEKLY"
         */
        fun scheduleIncrementalSync(context: Context, frequency: String) {
            val intervalHours = when (frequency.uppercase()) {
                "DAILY" -> 24L
                "WEEKLY" -> 168L // 7 days
                else -> return // "NONE" or invalid - don't schedule
            }
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<LastFmSyncWorker>(
                intervalHours, TimeUnit.HOURS,
                1, TimeUnit.HOURS // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                INCREMENTAL_SYNC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.i(TAG, "Scheduled Last.fm incremental sync (every $intervalHours hours)")
        }
        
        /**
         * Cancel incremental sync.
         */
        fun cancelIncrementalSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(INCREMENTAL_SYNC_WORK)
            Log.i(TAG, "Cancelled Last.fm incremental sync")
        }
        
        /**
         * Cancel all Last.fm import/sync work.
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("lastfm_import")
            WorkManager.getInstance(context).cancelUniqueWork(INCREMENTAL_SYNC_WORK)
            Log.i(TAG, "Cancelled all Last.fm work")
        }
        
        /**
         * Check if an import is currently running.
         */
        suspend fun isImportRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(INITIAL_IMPORT_WORK)
                    .get()
                workInfos.any { it.state == WorkInfo.State.RUNNING }
            } catch (e: Exception) {
                false
            }
        }
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel(applicationContext)
        return createForegroundInfo("Importing Last.fm history...", 0)
    }
    
    private fun createForegroundInfo(message: String, progress: Int): ForegroundInfo {
        // Create intent to open app when notification is clicked
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationBuilder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Last.fm Import")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        
        if (progress > 0) {
            notificationBuilder.setProgress(100, progress, false)
        } else {
            notificationBuilder.setProgress(0, 0, true) // Indeterminate
        }
        
        return ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
    }
    
    /**
     * Show a completion notification.
     */
    private fun showCompletionNotification(eventsImported: Long, tracksCreated: Long) {
        createNotificationChannel(applicationContext)
        
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Last.fm Import Complete!")
            .setContentText("Imported $eventsImported events from $tracksCreated tracks")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val isResume = inputData.getBoolean(KEY_IS_RESUME, false)
        
        // Set foreground immediately
        setForeground(createForegroundInfo("Preparing import...", 0))
        
        return if (isResume) {
            resumeImport()
        } else {
            startNewImport()
        }
    }
    
    private suspend fun startNewImport(): Result {
        val username = inputData.getString(KEY_USERNAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No username provided"))
        
        val tierName = inputData.getString(KEY_TIER) ?: "BALANCED"
        val totalScrobbles = inputData.getLong(KEY_TOTAL_SCROBBLES, 0)
        
        val tier = when (tierName.uppercase()) {
            // New tier names
            "QUICK" -> LastFmImportService.Companion.Tiers.QUICK
            "STANDARD" -> LastFmImportService.Companion.Tiers.STANDARD
            "DEEP" -> LastFmImportService.Companion.Tiers.DEEP
            // Backward compatibility with old tier names stored in database
            "LIGHTWEIGHT" -> LastFmImportService.Companion.Tiers.QUICK
            "BALANCED" -> LastFmImportService.Companion.Tiers.STANDARD
            "COMPREHENSIVE" -> LastFmImportService.Companion.Tiers.DEEP
            "EVERYTHING" -> LastFmImportService.Companion.Tiers.DEEP // Fallback to DEEP for old EVERYTHING
            else -> LastFmImportService.Companion.Tiers.STANDARD
        }
        
        Log.i(TAG, "Starting Last.fm import: username=$username, tier=$tierName, scrobbles=$totalScrobbles")
        
        // Update notification
        setForeground(createForegroundInfo("Importing from Last.fm...", 0))
        
        return try {
            val result = lastFmImportService.startImport(username, tier, totalScrobbles)
            
            result.fold(
                onSuccess = { importResult ->
                    Log.i(TAG, "Import completed: ${importResult.eventsImported} events, ${importResult.tracksCreated} tracks")
                    
                    // Show completion notification
                    showCompletionNotification(importResult.eventsImported, importResult.tracksCreated)
                    
                    // Schedule incremental sync if user wants it
                    val prefs = userPreferencesDao.getSync()
                    if (prefs?.lastfmSyncFrequency != null && prefs.lastfmSyncFrequency != "NONE") {
                        scheduleIncrementalSync(applicationContext, prefs.lastfmSyncFrequency)
                    }
                    
                    Result.success(workDataOf(
                        KEY_SUCCESS to true,
                        KEY_EVENTS_IMPORTED to importResult.eventsImported,
                        KEY_TRACKS_CREATED to importResult.tracksCreated
                    ))
                },
                onFailure = { error ->
                    Log.e(TAG, "Import failed", error)
                    Result.failure(workDataOf(
                        KEY_SUCCESS to false,
                        KEY_ERROR_MESSAGE to (error.message ?: "Import failed")
                    ))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import threw exception", e)
            Result.retry() // Retry on unexpected errors
        }
    }
    
    private suspend fun resumeImport(): Result {
        val importId = inputData.getLong(KEY_IMPORT_ID, -1)
        if (importId == -1L) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No import ID provided"))
        }
        
        val metadata = importMetadataDao.getById(importId)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Import not found"))
        
        if (!metadata.isResumable) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Import is not resumable"))
        }
        
        Log.i(TAG, "Resuming Last.fm import: id=$importId, page=${metadata.currentPage}")
        
        // IMPLEMENTATION NOTE: Currently restarts from beginning rather than resuming from page.
        // This is acceptable because:
        // 1. The deduplication logic in insertAllBatchedWithDedup handles duplicates gracefully
        // 2. Tracks are matched by title/artist, so existing tracks are reused
        // 3. Full resume would require storing active set state and archive batch state
        // 
        // Future enhancement: Store active set keys and archive batch in separate table
        // to enable true resume. For now, re-running is safe and produces correct results.
        val tier = when (metadata.importTier) {
            // New tier names
            "QUICK" -> LastFmImportService.Companion.Tiers.QUICK
            "STANDARD" -> LastFmImportService.Companion.Tiers.STANDARD
            "DEEP" -> LastFmImportService.Companion.Tiers.DEEP
            // Backward compatibility with old tier names stored in database
            "LIGHTWEIGHT" -> LastFmImportService.Companion.Tiers.QUICK
            "BALANCED" -> LastFmImportService.Companion.Tiers.STANDARD
            "COMPREHENSIVE" -> LastFmImportService.Companion.Tiers.DEEP
            "EVERYTHING" -> LastFmImportService.Companion.Tiers.DEEP // Fallback to DEEP for old EVERYTHING
            else -> LastFmImportService.Companion.Tiers.STANDARD
        }
        
        // Update notification
        setForeground(createForegroundInfo("Resuming Last.fm import...", 0))
        
        return try {
            val result = lastFmImportService.startImport(
                metadata.lastfmUsername,
                tier,
                metadata.totalScrobblesFound
            )
            
            result.fold(
                onSuccess = { importResult ->
                    // Show completion notification
                    showCompletionNotification(importResult.eventsImported, importResult.tracksCreated)
                    
                    Result.success(workDataOf(
                        KEY_SUCCESS to true,
                        KEY_EVENTS_IMPORTED to importResult.eventsImported,
                        KEY_TRACKS_CREATED to importResult.tracksCreated
                    ))
                },
                onFailure = { error ->
                    Result.failure(workDataOf(
                        KEY_SUCCESS to false,
                        KEY_ERROR_MESSAGE to (error.message ?: "Resume failed")
                    ))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Resume threw exception", e)
            Result.retry()
        }
    }
}

/**
 * Separate worker for incremental sync to keep it lightweight.
 */
@HiltWorker
class LastFmSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val lastFmImportService: LastFmImportService,
    private val importMetadataDao: LastFmImportMetadataDao,
    private val userPreferencesDao: UserPreferencesDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LastFmSyncWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = userPreferencesDao.getSync() ?: return Result.success()
        
        val username = prefs.lastfmUsername
        if (username.isNullOrBlank() || !prefs.lastfmConnected) {
            Log.d(TAG, "Last.fm not connected, skipping sync")
            return Result.success()
        }
        
        // Check sync frequency setting
        if (prefs.lastfmSyncFrequency == "NONE") {
            Log.d(TAG, "Sync frequency set to NONE, cancelling")
            LastFmImportWorker.cancelIncrementalSync(applicationContext)
            return Result.success()
        }
        
        Log.i(TAG, "Running incremental Last.fm sync for $username")
        
        return try {
            val result = lastFmImportService.syncNewScrobbles()
            
            result.fold(
                onSuccess = { newCount ->
                    Log.i(TAG, "Incremental sync completed: $newCount new scrobbles imported")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Incremental sync failed: ${error.message}")
                    // Don't fail the worker - just log and retry next period
                    Result.success()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Incremental sync threw exception", e)
            Result.retry() // Retry on unexpected errors
        }
    }
}
