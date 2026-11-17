package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.analytics.RunPhase
import me.avinas.tempo.data.applemusic.AppleMusicImportService
import me.avinas.tempo.utils.ForegroundNotificationGuard

/**
 * Runs the Apple Music import in the background so a multi-minute parse survives the user
 * leaving the screen. Mirrors [DeezerImportWorker] — same keys, same foreground
 * contract — so the import screens stay interchangeable.
 */
@HiltWorker
class AppleMusicImportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val importService: AppleMusicImportService,
        private val tracker: AnalyticsTracker,
    ) : CoroutineWorker(appContext, workerParams) {
    data class EnqueueResult(
        val workId: java.util.UUID,
        val requestAccepted: Boolean,
    )

    companion object {
        private const val TAG = "AppleMusicImportWorker"

        internal const val NOTIFICATION_CHANNEL_ID = "apple_music_import_channel"
        private const val NOTIFICATION_ID = 9400
        private const val NOTIFICATION_COMPLETION_ID = 9401

        internal const val WORK_NAME = "apple_music_import"

        const val KEY_FILE_URI = "file_uri"
        const val KEY_SUCCESS = "success"
        const val KEY_TRACKS_IMPORTED = "tracks_imported"
        const val KEY_EVENTS_CREATED = "events_created"
        const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
        const val KEY_SHORT_PLAYS_SKIPPED = "short_plays_skipped"
        const val KEY_MALFORMED_ROWS = "malformed_rows"
        const val KEY_TOTAL_ENTRIES = "total_entries"
        const val KEY_ARTIST_RECOVERED = "artist_recovered"
        const val KEY_WARNINGS = "warnings"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val MAX_ERROR_MESSAGE_CHARS = 2_000

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        context.getString(R.string.apple_music_import_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.apple_music_import_notification_channel_desc)
                    }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        internal data class ProgressState(
            val max: Int,
            val progress: Int,
            val indeterminate: Boolean,
        )

        internal fun calculateProgress(current: Int, total: Int): ProgressState {
            val isIndeterminate = total <= 0
            val max = if (isIndeterminate) 0 else total
            return ProgressState(
                max = max,
                progress = if (isIndeterminate) 0 else current.coerceIn(0, max),
                indeterminate = isIndeterminate,
            )
        }

        private fun isActiveWork(info: WorkInfo): Boolean =
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED

        suspend fun enqueueImport(context: Context, fileUri: String): EnqueueResult =
            withContext(Dispatchers.IO) {
                val workManager = WorkManager.getInstance(context)
                val existing = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().firstOrNull(::isActiveWork)
                if (existing != null) {
                    Log.i(TAG, "Apple Music import already active; keeping ${existing.id}")
                    return@withContext EnqueueResult(existing.id, requestAccepted = false)
                }

                val workRequest =
                    OneTimeWorkRequestBuilder<AppleMusicImportWorker>()
                        .setInputData(workDataOf(KEY_FILE_URI to fileUri))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(WORK_NAME)
                        .build()

                // KEEP stays the real concurrency guard; resolving the unique-work chain after
                // enqueue tells the caller which UUID actually owns the slot in a race.
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, workRequest)
                    .result
                    .get()

                val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                val requested = infos.firstOrNull { it.id == workRequest.id }
                val owner = requested ?: infos.firstOrNull(::isActiveWork)
                val ownerId = owner?.id ?: workRequest.id
                val accepted = requested != null

                if (accepted) {
                    Log.i(TAG, "Enqueued Apple Music import ${workRequest.id}")
                } else {
                    Log.i(TAG, "Another Apple Music import won the enqueue race; keeping $ownerId")
                }
                EnqueueResult(ownerId, requestAccepted = accepted)
            }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled Apple Music import")
        }

        fun isRunning(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
            return workInfos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting Apple Music import worker")
            val startedAt = System.currentTimeMillis()

            val uriString = inputData.getString(KEY_FILE_URI)
            if (uriString.isNullOrBlank()) {
                Log.e(TAG, "No file URI provided")
                reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                return@withContext Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to applicationContext.getString(R.string.apple_music_import_error_no_file),
                    ),
                )
            }

            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse the Apple Music import URI", e)
                null
            }

            if (uri == null) {
                reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                return@withContext Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to applicationContext.getString(R.string.apple_music_import_error_invalid_uri),
                    ),
                )
            }

            try {
                setForeground(createForegroundInfo(applicationContext.getString(R.string.apple_music_import_preparing), 0, 0))
            } catch (e: IllegalStateException) {
                // Never continue a long parse without foreground execution: let WorkManager retry.
                Log.w(TAG, "Foreground start not allowed; retrying Apple Music import later", e)
                return@withContext Result.retry()
            }

            val progressJob = launch {
                var lastNotifyAt = 0L
                importService.importState.collect { state ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyAt < 1_000L) return@collect
                    when (state) {
                        is AppleMusicImportService.ImportState.Parsing -> {
                            showProgressNotification(
                                applicationContext.getString(R.string.apple_music_import_reading, state.fileName),
                                0,
                                0,
                            )
                        }

                        is AppleMusicImportService.ImportState.Importing -> {
                            showProgressNotification(
                                applicationContext.getString(
                                    R.string.apple_music_import_notification_progress,
                                    state.current,
                                    state.total,
                                ),
                                state.current,
                                state.total,
                            )
                        }

                        else -> return@collect
                    }
                    lastNotifyAt = now
                }
            }

            try {
                val result = importService.importFromUri(applicationContext, uri)

                if (result.isSuccess) {
                    reportImport(RunPhase.COMPLETED, records = result.totalEntries, failure = null, startedAt = startedAt)
                    showCompletionNotification(result)
                    Result.success(
                        workDataOf(
                            KEY_SUCCESS to true,
                            KEY_TRACKS_IMPORTED to result.tracksImported,
                            KEY_EVENTS_CREATED to result.eventsCreated,
                            KEY_DUPLICATES_SKIPPED to result.duplicatesSkipped,
                            KEY_SHORT_PLAYS_SKIPPED to result.shortPlaysSkipped,
                            KEY_MALFORMED_ROWS to result.malformedRows,
                            KEY_TOTAL_ENTRIES to result.totalEntries,
                            KEY_ARTIST_RECOVERED to result.artistRecoveredRows,
                            KEY_WARNINGS to result.errors.toTypedArray(),
                        ),
                    )
                } else {
                    val errorMsg = localizedFailureMessage(
                        result.errors.joinToString("; ").take(MAX_ERROR_MESSAGE_CHARS),
                    )
                    reportImport(
                        RunPhase.FAILED,
                        records = result.totalEntries,
                        failure = FailureClass.UNKNOWN,
                        startedAt = startedAt,
                    )
                    showFailureNotification(errorMsg)
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to errorMsg,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Apple Music import worker was cancelled")
                reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.CANCELLED, startedAt = startedAt)
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Apple Music import ran out of memory", e)
                reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.OUT_OF_MEMORY, startedAt = startedAt)
                val errorMsg = applicationContext.getString(R.string.apple_music_import_error_out_of_memory)
                showFailureNotification(errorMsg)
                Result.failure(workDataOf(KEY_SUCCESS to false, KEY_ERROR_MESSAGE to errorMsg))
            } catch (e: Exception) {
                Log.e(TAG, "Apple Music import failed with an exception", e)
                reportImport(RunPhase.FAILED, records = 0, failure = FailureClassifier.of(e), startedAt = startedAt)
                val errorMsg = applicationContext.getString(R.string.apple_music_import_error_generic)
                showFailureNotification(errorMsg)
                Result.failure(workDataOf(KEY_SUCCESS to false, KEY_ERROR_MESSAGE to errorMsg))
            } finally {
                progressJob.cancel()
                cancelProgressNotification()
                if (uri.scheme == "file") {
                    importService.deleteStagedImportFile(applicationContext, uri.path)
                }
            }
        }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(applicationContext.getString(R.string.apple_music_import_preparing), 0, 0)

    private fun createForegroundInfo(message: String, current: Int, total: Int): ForegroundInfo {
        createNotificationChannel(applicationContext)

        val progressState = calculateProgress(current, total)
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.apple_music_import_notification_title))
                .setContentText(message)
                .setContentIntent(createContentPendingIntent())
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(progressState.max, progressState.progress, progressState.indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        return ForegroundNotificationGuard.postableForegroundInfo(
            context = applicationContext,
            notificationId = NOTIFICATION_ID,
            requestedChannelId = NOTIFICATION_CHANNEL_ID,
            notification = notification,
            logTag = TAG,
        )
    }

    private fun reportImport(phase: RunPhase, records: Int, failure: FailureClass?, startedAt: Long) {
        tracker.track(
            ImportRun(
                provider = ImportProvider.APPLE_MUSIC,
                phase = phase,
                records = records,
                failure = failure,
                durationMillis = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    private fun showProgressNotification(message: String, current: Int, total: Int) {
        val progressState = calculateProgress(current, total)
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.apple_music_import_notification_title))
                .setContentText(message)
                .setContentIntent(createContentPendingIntent())
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(progressState.max, progressState.progress, progressState.indeterminate)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(result: AppleMusicImportService.ImportResult) {
        val message = applicationContext.getString(R.string.apple_music_import_events_imported, result.eventsCreated)

        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.apple_music_import_complete))
                .setContentText(message)
                .setContentIntent(createContentPendingIntent())
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
    }

    private fun localizedFailureMessage(message: String): String =
        when {
            message.contains("does not contain Apple Music play activity", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_missing_history)
            message.contains("play-activity columns", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_unsupported_columns)
            message.contains("No valid Apple Music listening", ignoreCase = true) ||
                message.contains("No Apple Music listening-history entries", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_no_entries)
            message.contains("too large", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_too_large)
            message.contains("not a valid Apple Music export", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_invalid_zip)
            message.contains("Not enough memory", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_out_of_memory)
            message.contains("No file selected", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_no_file)
            message.contains("Invalid file URI", ignoreCase = true) ->
                applicationContext.getString(R.string.apple_music_import_error_invalid_uri)
            else -> applicationContext.getString(R.string.apple_music_import_error_generic)
        }

    private fun createContentPendingIntent(): PendingIntent {
        val intent =
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showFailureNotification(error: String) {
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.apple_music_import_failed))
                .setContentText(error)
                .setContentIntent(createContentPendingIntent())
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
    }

    private fun cancelProgressNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
