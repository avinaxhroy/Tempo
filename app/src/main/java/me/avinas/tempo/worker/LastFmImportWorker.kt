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
import com.google.common.util.concurrent.ListenableFuture
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.local.dao.LastFmImportMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.LastFmImportMetadata
import me.avinas.tempo.utils.ForegroundNotificationGuard
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Background worker for Last.fm history imports.
 *
 * 1. INITIAL IMPORT (one-time): large enough to take 20+ minutes, runs in the foreground with a
 *    notification, and resumes from the page it reached if interrupted.
 * 2. INCREMENTAL SYNC (periodic, [LastFmSyncWorker]): new scrobbles since the last sync, every
 *    24 hours by default, usually finishing in under a minute.
 */
@HiltWorker
class LastFmImportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val lastFmImportService: LastFmImportService,
        private val importMetadataDao: LastFmImportMetadataDao,
        private val userPreferencesDao: UserPreferencesDao,
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

            /**
             * How many times WorkManager may re-run an import that threw before we give up.
             *
             * An import that fails for a *permanent* reason used to return `Result.retry()`
             * forever. Because the work request is persisted, every app launch re-ran it, so a
             * deterministic failure (bad notification channel, unmarshalled response, corrupt
             * import row) presented as "the app crashes whenever I open it". Bounding the
             * attempts turns that into a visible failed import the user can act on.
             */
            private const val MAX_IMPORT_ATTEMPTS = 3

            // Input keys
            const val KEY_USERNAME = "username"
            const val KEY_TIER = "tier"
            const val KEY_TOTAL_SCROBBLES = "total_scrobbles"

            // Output keys
            const val KEY_SUCCESS = "success"
            const val KEY_EVENTS_IMPORTED = "events_imported"
            const val KEY_TRACKS_CREATED = "tracks_created"
            const val KEY_SKIPPED_PAGES = "skipped_pages"
            const val KEY_ERROR_MESSAGE = "error_message"

            /**
             * Create the notification channel for Last.fm imports and report whether it is usable.
             *
             * Posting a foreground notification on a channel that does not exist makes the system
             * kill the process asynchronously with
             * `RemoteServiceException$CannotPostForegroundServiceNotificationException` - the throw
             * happens on the system's main thread, *not* around our `setForeground()` call, so it
             * cannot be caught here. WorkManager only calls `getForegroundInfo()` for expedited
             * work, so for a normal one-time worker this method is the only thing standing between
             * `setForeground()` and an invalid notification. Callers must therefore not promote to
             * the foreground when this returns false.
             */
            fun ensureNotificationChannel(context: Context): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

                return try {
                    val notificationManager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            ?: return false

                    if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                        val channel =
                            NotificationChannel(
                                NOTIFICATION_CHANNEL_ID,
                                "Last.fm Import",
                                NotificationManager.IMPORTANCE_LOW,
                            ).apply {
                                description = "Progress notifications for Last.fm history import"
                                setShowBadge(false)
                            }
                        notificationManager.createNotificationChannel(channel)
                    }

                    notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null
                } catch (e: Exception) {
                    Log.e(TAG, "Could not create the '$NOTIFICATION_CHANNEL_ID' notification channel", e)
                    false
                }
            }

            /** Enqueues the one-time import, which promotes itself to the foreground while it runs. */
            fun enqueueInitialImport(
                context: Context,
                username: String,
                tier: String,
                totalScrobbles: Long,
            ): java.util.UUID {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()

                val inputData =
                    workDataOf(
                        KEY_USERNAME to username,
                        KEY_TIER to tier,
                        KEY_TOTAL_SCROBBLES to totalScrobbles,
                    )

                val workRequest =
                    OneTimeWorkRequestBuilder<LastFmImportWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            30,
                            TimeUnit.SECONDS,
                        ).addTag("lastfm_import")
                        .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    INITIAL_IMPORT_WORK,
                    ExistingWorkPolicy.KEEP, // Don't interrupt existing import
                    workRequest,
                )

                Log.i(TAG, "Enqueued initial Last.fm import for $username (tier=$tier)")
                return workRequest.id
            }

            /**
             * Schedule periodic incremental sync. `frequency` is "DAILY" or "WEEKLY"; anything
             * else (including "NONE") schedules nothing.
             */
            fun scheduleIncrementalSync(
                context: Context,
                frequency: String,
            ) {
                val intervalHours =
                    when (frequency.uppercase()) {
                        "DAILY" -> 24L

                        "WEEKLY" -> 168L

                        // 7 days
                        else -> return // "NONE" or invalid - don't schedule
                    }

                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()

                val workRequest =
                    PeriodicWorkRequestBuilder<LastFmSyncWorker>(
                        intervalHours,
                        TimeUnit.HOURS,
                        1,
                        TimeUnit.HOURS, // Flex interval
                    ).setConstraints(constraints)
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            WorkRequest.MIN_BACKOFF_MILLIS,
                            TimeUnit.MILLISECONDS,
                        ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    INCREMENTAL_SYNC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest,
                )

                Log.i(TAG, "Scheduled Last.fm incremental sync (every $intervalHours hours)")
            }

            fun cancelIncrementalSync(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(INCREMENTAL_SYNC_WORK)
                Log.i(TAG, "Cancelled Last.fm incremental sync")
            }

            fun cancelAll(context: Context) {
                WorkManager.getInstance(context).cancelAllWorkByTag("lastfm_import")
                WorkManager.getInstance(context).cancelUniqueWork(INCREMENTAL_SYNC_WORK)
                Log.i(TAG, "Cancelled all Last.fm work")
            }

            suspend fun isImportRunning(context: Context): Boolean =
                withContext(Dispatchers.IO) {
                    try {
                        val workInfos =
                            WorkManager
                                .getInstance(context)
                                .getWorkInfosForUniqueWork(INITIAL_IMPORT_WORK)
                                .awaitResult()
                        workInfos.any { it.state == WorkInfo.State.RUNNING }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            ensureNotificationChannel(applicationContext)
            return createForegroundInfo("Importing Last.fm history...", 0)
        }

        /**
         * Promote this worker to the foreground without ever taking the app down with us.
         *
         * The channel is verified first: an invalid channel makes the *system* kill the process
         * when it posts the notification on our behalf, which no `try/catch` around
         * `setForeground()` can intercept. If the channel cannot be created (or the OS refuses the
         * promotion, e.g. the foreground-start window has lapsed) the import continues in the
         * background instead of crashing.
         */
        private suspend fun promoteToForegroundSafely(message: String) {
            if (!ensureNotificationChannel(applicationContext)) {
                Log.e(TAG, "No usable notification channel; running the import without a foreground promotion")
                return
            }

            try {
                setForeground(createForegroundInfo(message, 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not promote the import worker to the foreground; continuing in the background", e)
            }
        }

        private fun createForegroundInfo(
            message: String,
            progress: Int,
        ): ForegroundInfo {
            // The channel must exist *here*, not only in getForegroundInfo(): WorkManager never
            // calls getForegroundInfo() for a non-expedited worker, and this builder is what
            // doWork() hands straight to setForeground().
            ensureNotificationChannel(applicationContext)

            // Create intent to open app when notification is clicked
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notificationBuilder =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Last.fm Import")
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    // Show the update immediately instead of letting the system defer the post
                    // while the app is in the background; the deferred re-post path is the one
                    // that surfaces as a Bad notification for startForeground.
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .setOnlyAlertOnce(true)

            if (progress > 0) {
                notificationBuilder.setProgress(100, progress, false)
            } else {
                notificationBuilder.setProgress(0, 0, true) // Indeterminate
            }

            val notification = notificationBuilder.build()
            return ForegroundNotificationGuard.postableForegroundInfo(
                context = applicationContext,
                notificationId = NOTIFICATION_ID,
                requestedChannelId = NOTIFICATION_CHANNEL_ID,
                notification = notification,
                logTag = TAG,
            )
        }

        private fun showCompletionNotification(
            eventsImported: Long,
            tracksCreated: Long,
        ) {
            if (!ensureNotificationChannel(applicationContext)) {
                Log.w(TAG, "Skipping completion notification: no usable notification channel")
                return
            }

            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
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
            // Enforce the attempt bound *before* doing any work. A worker whose process is killed
            // never reaches the catch block in startNewImport(), so without this check WorkManager
            // - which persists the request - would re-run and re-kill it on every app launch,
            // which is what "the app crashes every time I open it" looks like from the outside.
            if (runAttemptCount >= MAX_IMPORT_ATTEMPTS) {
                val reason = "Last.fm import stopped after $runAttemptCount failed attempts"
                Log.e(TAG, reason)
                closeAbandonedImport(reason)
                // The service never got far enough to emit a terminal state, so tell the UI
                // directly - otherwise the progress card waits on a run that is already over.
                lastFmImportService.reportFailed(reason)
                return Result.failure(
                    workDataOf(
                        KEY_SUCCESS to false,
                        KEY_ERROR_MESSAGE to reason,
                    ),
                )
            }

            // Promote to the foreground before the first (slow) API call so Android does not
            // consider the import an idle background service. A refused promotion is not fatal.
            promoteToForegroundSafely("Preparing import...")
            return startNewImport()
        }

        /**
         * Close out an import row that never got anywhere, so it cannot masquerade as resumable.
         *
         * A row with real progress is left IN_PROGRESS: the next attempt resumes it
         * from the page it reached and carries its recorded counters into the report, which beats
         * re-walking the whole history from the newest page. Only a row with no pages and no
         * imported data has nothing worth resuming.
         */
        private suspend fun closeAbandonedImport(reason: String) {
            try {
                val username = inputData.getString(KEY_USERNAME)
                val row =
                    (username?.let { importMetadataDao.getLatestForUsername(it) })
                        ?: importMetadataDao.getActiveImport()
                val neverProgressed =
                    row != null &&
                        row.currentPage <= 1 &&
                        row.eventsImported == 0L &&
                        row.scrobblesArchived == 0L
                if (row != null && row.isResumable && neverProgressed) {
                    importMetadataDao.markFailed(
                        id = row.id,
                        failedAt = System.currentTimeMillis(),
                        errorMessage = reason,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not close out the abandoned import row", e)
            }
        }

        private suspend fun startNewImport(): Result {
            val username =
                inputData.getString(KEY_USERNAME)
                    ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No username provided"))

            val tierName = inputData.getString(KEY_TIER) ?: "BALANCED"
            val totalScrobbles = inputData.getLong(KEY_TOTAL_SCROBBLES, 0)

            val tier =
                when (tierName.uppercase()) {
                    // New tier names
                    "QUICK" -> LastFmImportService.Companion.Tiers.QUICK

                    "STANDARD" -> LastFmImportService.Companion.Tiers.STANDARD

                    "DEEP" -> LastFmImportService.Companion.Tiers.DEEP

                    // Backward compatibility with old tier names stored in database
                    "LIGHTWEIGHT" -> LastFmImportService.Companion.Tiers.QUICK

                    "BALANCED" -> LastFmImportService.Companion.Tiers.STANDARD

                    "COMPREHENSIVE" -> LastFmImportService.Companion.Tiers.DEEP

                    "EVERYTHING" -> LastFmImportService.Companion.Tiers.DEEP

                    else -> LastFmImportService.Companion.Tiers.STANDARD
                }

            Log.i(TAG, "Starting Last.fm import: username=$username, tier=$tierName, scrobbles=$totalScrobbles")

            // Resume an interrupted import for this user instead of silently starting over from
            // page 1. Only when the tier matches: resuming a QUICK run under DEEP (or vice versa)
            // would apply the wrong active set and recent-cutoff. A stale IN_PROGRESS row with a
            // different tier is closed out so it stops shadowing this import.
            val latest = importMetadataDao.getLatestForUsername(username)
            val resumeFrom = latest?.takeIf { it.isResumable && it.importTier == tier.name }
            if (latest != null && resumeFrom == null && latest.status == LastFmImportMetadata.STATUS_IN_PROGRESS) {
                Log.i(TAG, "Closing out stale import ${latest.id} (tier=${latest.importTier}, wanted $tierName)")
                importMetadataDao.markFailed(
                    id = latest.id,
                    failedAt = System.currentTimeMillis(),
                    errorMessage = "Superseded by a new import",
                )
            }
            if (resumeFrom != null) {
                Log.i(TAG, "Resuming import ${resumeFrom.id} from page ${resumeFrom.currentPage}")
            }

            promoteToForegroundSafely("Importing from Last.fm...")

            return try {
                val result = lastFmImportService.startImport(username, tier, totalScrobbles, resumeFrom)

                result.fold(
                    onSuccess = { importResult ->
                        Log.i(
                            TAG,
                            "Import completed: ${importResult.eventsImported} events, " +
                                "${importResult.tracksCreated} tracks, ${importResult.skippedPages} skipped pages",
                        )

                        // Show completion notification. A run that found nothing new (everything
                        // was already in the library) must not announce "Imported 0 events".
                        val imported = importResult.eventsImported + importResult.scrobblesArchived
                        if (imported > 0) {
                            showCompletionNotification(importResult.eventsImported, importResult.tracksCreated)
                        } else {
                            Log.i(TAG, "Import finished with nothing new to write; skipping the completion notification")
                        }

                        val prefs = userPreferencesDao.getSync()
                        if (prefs?.lastfmSyncFrequency != null && prefs.lastfmSyncFrequency != "NONE") {
                            scheduleIncrementalSync(applicationContext, prefs.lastfmSyncFrequency)
                        }

                        Result.success(
                            workDataOf(
                                KEY_SUCCESS to true,
                                KEY_EVENTS_IMPORTED to importResult.eventsImported,
                                KEY_TRACKS_CREATED to importResult.tracksCreated,
                                KEY_SKIPPED_PAGES to importResult.skippedPages,
                            ),
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Import failed", error)
                        Result.failure(
                            workDataOf(
                                KEY_SUCCESS to false,
                                KEY_ERROR_MESSAGE to (error.message ?: "Import failed"),
                            ),
                        )
                    },
                )
            } catch (e: CancellationException) {
                // A cancelled worker must not be retried - Result.retry() here would resurrect
                // the import the user just cancelled.
                throw e
            } catch (e: Exception) {
                // Bounded retry: the work request survives process death, so an unbounded
                // Result.retry() on a deterministic failure re-runs - and re-crashes - on every
                // app launch. After a few attempts, report a failure the user can see and act on.
                val attempts = runAttemptCount + 1
                Log.e(TAG, "Import threw exception (attempt $attempts/$MAX_IMPORT_ATTEMPTS)", e)
                if (attempts >= MAX_IMPORT_ATTEMPTS) {
                    val message = e.message ?: "Import failed"
                    closeAbandonedImport(message)
                    // An exception thrown outside startImport() leaves no terminal progress
                    // state behind; publish one so the UI does not wait forever.
                    lastFmImportService.reportFailed(message)
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to message,
                        ),
                    )
                } else {
                    Result.retry()
                }
            }
        }
    }

private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            java.util.concurrent.Executor { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

/**
 * Separate worker for incremental sync to keep it lightweight.
 */
@HiltWorker
class LastFmSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val lastFmImportService: LastFmImportService,
        private val importMetadataDao: LastFmImportMetadataDao,
        private val userPreferencesDao: UserPreferencesDao,
        private val listeningEventDao: ListeningEventDao,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "LastFmSyncWorker"

            /**
             * Unique-work name for a foreground catch-up run. Keep it different from the periodic
             * chain's name: enqueueing unique work under that name would replace the user's
             * schedule instead of adding a run.
             */
            private const val CATCH_UP_WORK = "lastfm_catchup_on_open"

            /** Input flag marking a run that the app foregrounding asked for. */
            private const val KEY_FOREGROUND_CATCH_UP = "foreground_catch_up"

            /**
             * How stale the newest local event must be before a foreground run believes something
             * was missed. A typical track is 3–4 minutes, so an event inside this window means the
             * listener was working; there is nothing to recover, and skipping also avoids flashing
             * the import UI at someone who is just using the app.
             */
            private const val CATCH_UP_GAP_MS = 30 * 60_000L

            /**
             * Sync now because the app is in the foreground.
             *
             * This is the only recovery path for a phone that killed the process: the notification
             * listener cannot record what it never saw, so missed plays only come back from
             * Last.fm. The app being visible is the cheapest trigger: no wakeup, alarm,
             * foreground service or extra permission. [ExistingWorkPolicy.KEEP] stops rapid
             * app switching from stacking work, and an idle catch-up costs one request.
             */
            fun enqueueCatchUp(context: Context) {
                val request =
                    OneTimeWorkRequestBuilder<LastFmSyncWorker>()
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        ).setInputData(workDataOf(KEY_FOREGROUND_CATCH_UP to true))
                        .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.KEEP, request)
            }
        }

        override suspend fun doWork(): Result {
            val prefs = userPreferencesDao.getSync() ?: return Result.success()

            val username = prefs.lastfmUsername
            if (username.isNullOrBlank() || !prefs.lastfmConnected) {
                Log.d(TAG, "Last.fm not connected, skipping sync")
                return Result.success()
            }

            if (prefs.lastfmSyncFrequency == "NONE") {
                Log.d(TAG, "Sync frequency set to NONE, cancelling")
                LastFmImportWorker.cancelIncrementalSync(applicationContext)
                return Result.success()
            }

            // Foreground runs are speculative, so only the sighting of a gap justifies them. The
            // periodic run keeps syncing unconditionally: it also covers plays scrobbled from
            // another device while this phone was recording something else.
            if (inputData.getBoolean(KEY_FOREGROUND_CATCH_UP, false)) {
                val newestEvent = listeningEventDao.getLatestEventTimestamp()
                val gap = newestEvent?.let { System.currentTimeMillis() - it }
                if (gap == null || gap < CATCH_UP_GAP_MS) {
                    Log.d(TAG, "No tracking gap suspected (last event ${gap?.div(60_000)} min ago)")
                    return Result.success()
                }
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
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sync is periodic: there is no reason to retry an unexpected throw *now* - the
                // next period will pick it up anyway. Retrying here only produced tight retry
                // loops on permanent failures, and this worker has no foreground notification to
                // justify them.
                Log.e(TAG, "Incremental sync threw exception; deferring to the next period", e)
                Result.success()
            }
        }
    }
