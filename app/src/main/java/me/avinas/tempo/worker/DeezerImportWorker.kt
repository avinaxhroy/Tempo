package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.ImportPhase
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.deezer.DeezerDataImportService
import java.util.concurrent.TimeUnit

@HiltWorker
class DeezerImportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val deezerDataImportService: DeezerDataImportService,
        private val tracker: AnalyticsTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        data class EnqueueResult(
            val workId: java.util.UUID,
            val requestAccepted: Boolean,
        )

        companion object {
            private const val TAG = "DeezerImportWorker"

            internal const val NOTIFICATION_CHANNEL_ID = "deezer_import_channel"
            private const val NOTIFICATION_ID = 9300
            private const val NOTIFICATION_COMPLETION_ID = 9301

            internal const val WORK_NAME = "deezer_import"

            const val KEY_FILE_URI = "file_uri"
            const val KEY_SUCCESS = "success"
            const val KEY_TRACKS_IMPORTED = "tracks_imported"
            const val KEY_EVENTS_CREATED = "events_created"
            const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
            const val KEY_SHORT_PLAYS_SKIPPED = "short_plays_skipped"
            const val KEY_MALFORMED_ROWS = "malformed_rows"
            const val KEY_TOTAL_ENTRIES = "total_entries"
            const val KEY_WARNINGS = "warnings"
            const val KEY_ERROR_MESSAGE = "error_message"

            private const val MAX_ERROR_MESSAGE_CHARS = 2_000

            fun createNotificationChannel(context: Context) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            context.getString(R.string.deezer_import_notification_channel_name),
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = context.getString(R.string.deezer_import_notification_channel_desc)
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
                    progress =
                        if (isIndeterminate) {
                            0
                        } else {
                            current.coerceIn(0, max)
                        },
                    indeterminate = isIndeterminate,
                )
            }

            private fun isActiveWork(info: WorkInfo): Boolean =
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED

            suspend fun enqueueImport(
                context: Context,
                fileUri: String,
            ): EnqueueResult =
                withContext(Dispatchers.IO) {
                    val workManager = WorkManager.getInstance(context)
                    val existing =
                        workManager
                            .getWorkInfosForUniqueWork(WORK_NAME)
                            .get()
                            .firstOrNull(::isActiveWork)
                    if (existing != null) {
                        Log.i(TAG, "Deezer import already active; keeping ${existing.id}")
                        return@withContext EnqueueResult(existing.id, requestAccepted = false)
                    }

                    val inputData =
                        workDataOf(
                            KEY_FILE_URI to fileUri,
                        )

                    val workRequest =
                        OneTimeWorkRequestBuilder<DeezerImportWorker>()
                            .setInputData(inputData)
                            .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                30,
                                TimeUnit.SECONDS,
                            ).addTag("deezer_import")
                            .build()

                    // KEEP is still the final concurrency guard. Waiting for the enqueue
                    // operation and then resolving the unique-work chain tells the caller
                    // which UUID actually owns the slot if another enqueue won a race.
                    workManager
                        .enqueueUniqueWork(
                            WORK_NAME,
                            ExistingWorkPolicy.KEEP,
                            workRequest,
                        ).result
                        .get()

                    val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    val requested = infos.firstOrNull { it.id == workRequest.id }
                    val owner = requested ?: infos.firstOrNull(::isActiveWork)
                    val ownerId = owner?.id ?: workRequest.id
                    val accepted = requested != null

                    if (accepted) {
                        Log.i(TAG, "Enqueued Deezer import ${workRequest.id}")
                    } else {
                        Log.i(TAG, "Another Deezer import won enqueue race; keeping $ownerId")
                    }
                    EnqueueResult(ownerId, requestAccepted = accepted)
                }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Cancelled Deezer import")
            }

            fun isRunning(context: Context): Boolean {
                val workManager = WorkManager.getInstance(context)
                val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                return workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
            }
        }

        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                Log.i(TAG, "Starting Deezer import worker")
                val startedAt = System.currentTimeMillis()

                val uriString = inputData.getString(KEY_FILE_URI)
                if (uriString.isNullOrBlank()) {
                    Log.e(TAG, "No file URI provided")
                    reportImport(ImportPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to applicationContext.getString(R.string.deezer_import_error_no_file),
                        ),
                    )
                }

                val uri =
                    try {
                        Uri.parse(uriString)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Deezer import URI", e)
                        null
                    }

                if (uri == null) {
                    reportImport(ImportPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to applicationContext.getString(R.string.deezer_import_error_invalid_uri),
                        ),
                    )
                }

                try {
                    setForeground(createForegroundInfo(applicationContext.getString(R.string.deezer_import_preparing), 0, 0))
                } catch (e: IllegalStateException) {
                    // Long-running imports must not silently continue without foreground
                    // execution. Let WorkManager retry when foreground promotion is allowed.
                    Log.w(TAG, "Foreground start not allowed; retrying Deezer import later", e)
                    return@withContext Result.retry()
                }

                val progressJob =
                    launch {
                        var lastNotifyAt = 0L
                        deezerDataImportService.importState.collect { state ->
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyAt < 1_000L) return@collect
                            when (state) {
                                is DeezerDataImportService.ImportState.Parsing -> {
                                    showProgressNotification(
                                        applicationContext.getString(R.string.deezer_import_reading, state.fileName),
                                        0,
                                        0,
                                    )
                                }

                                is DeezerDataImportService.ImportState.Importing -> {
                                    showProgressNotification(
                                        applicationContext.getString(
                                            R.string.deezer_import_notification_progress,
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
                    val result = deezerDataImportService.importFromUri(applicationContext, uri)

                    if (result.isSuccess) {
                        reportImport(ImportPhase.COMPLETED, records = result.totalEntries, failure = null, startedAt = startedAt)
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
                                KEY_WARNINGS to result.errors.toTypedArray(),
                            ),
                        )
                    } else {
                        val errorMsg =
                            localizedFailureMessage(
                                result.errors.joinToString("; ").take(MAX_ERROR_MESSAGE_CHARS),
                            )
                        reportImport(
                            ImportPhase.FAILED,
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
                    Log.i(TAG, "Deezer import worker was cancelled")
                    reportImport(ImportPhase.FAILED, records = 0, failure = FailureClass.CANCELLED, startedAt = startedAt)
                    throw e
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Deezer import ran out of memory", e)
                    reportImport(ImportPhase.FAILED, records = 0, failure = FailureClass.OUT_OF_MEMORY, startedAt = startedAt)
                    val errorMsg = applicationContext.getString(R.string.deezer_import_error_out_of_memory)
                    showFailureNotification(errorMsg)
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to errorMsg,
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Deezer import failed with exception", e)
                    reportImport(ImportPhase.FAILED, records = 0, failure = FailureClassifier.of(e), startedAt = startedAt)
                    val errorMsg = applicationContext.getString(R.string.deezer_import_error_generic)
                    showFailureNotification(errorMsg)
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to errorMsg,
                        ),
                    )
                } finally {
                    progressJob.cancel()
                    cancelProgressNotification()
                    if (uri.scheme == "file") {
                        deezerDataImportService.deleteStagedImportFile(
                            applicationContext,
                            uri.path,
                        )
                    } else {
                        // Backward compatibility for work enqueued by an older build.
                        releasePersistedReadPermission(uri)
                    }
                }
            }

        override suspend fun getForegroundInfo(): ForegroundInfo =
            createForegroundInfo(applicationContext.getString(R.string.deezer_import_preparing), 0, 0)

        private fun createForegroundInfo(
            message: String,
            current: Int,
            total: Int,
        ): ForegroundInfo {
            createNotificationChannel(applicationContext)

            val pendingIntent = createContentPendingIntent()

            val progressState = calculateProgress(current, total)
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(R.string.deezer_import_notification_title))
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setProgress(progressState.max, progressState.progress, progressState.indeterminate)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        private fun reportImport(
            phase: ImportPhase,
            records: Int,
            failure: FailureClass?,
            startedAt: Long,
        ) {
            tracker.track(
                ImportRun(
                    provider = ImportProvider.DEEZER,
                    phase = phase,
                    records = records,
                    failure = failure,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }

        private fun showProgressNotification(
            message: String,
            current: Int,
            total: Int,
        ) {
            val progressState = calculateProgress(current, total)
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(R.string.deezer_import_notification_title))
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

        private fun showCompletionNotification(result: DeezerDataImportService.ImportResult) {
            val message = applicationContext.getString(R.string.deezer_import_events_imported, result.eventsCreated)

            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(R.string.deezer_import_complete))
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
                message.contains("does not contain Deezer listening history", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_missing_history)
                message.contains("listening-history columns", ignoreCase = true) &&
                    message.contains("not supported", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_unsupported_columns)
                message.contains("No Deezer listening-history entries", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_no_entries)
                message.contains("too large", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_too_large)
                message.contains("not a valid Deezer XLSX", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_invalid_xlsx)
                message.contains("Not enough memory", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_out_of_memory)
                message.contains("No file selected", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_no_file)
                message.contains("Invalid file URI", ignoreCase = true) ->
                    applicationContext.getString(R.string.deezer_import_error_invalid_uri)
                else -> applicationContext.getString(R.string.deezer_import_error_generic)
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
                    .setContentTitle(applicationContext.getString(R.string.deezer_import_failed))
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

        private fun releasePersistedReadPermission(uri: Uri) {
            try {
                applicationContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // The provider did not grant a persistable permission.
            } catch (_: IllegalArgumentException) {
                // The URI/provider does not support persisted permissions.
            }
        }

        private fun cancelProgressNotification() {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }
