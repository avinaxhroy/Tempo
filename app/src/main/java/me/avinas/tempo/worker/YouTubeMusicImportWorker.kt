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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.RunPhase
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import me.avinas.tempo.utils.ForegroundNotificationGuard
import java.util.concurrent.TimeUnit

@HiltWorker
class YouTubeMusicImportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val youTubeMusicImportService: YouTubeMusicImportService,
        private val tracker: AnalyticsTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "YouTubeMusicImportWorker"

            private const val NOTIFICATION_CHANNEL_ID = "youtube_music_import_channel"
            private const val NOTIFICATION_ID = 9200
            private const val NOTIFICATION_COMPLETION_ID = 9201

            private const val WORK_NAME = "youtube_music_import"

            const val KEY_FILE_URIS = "file_uris"
            const val KEY_SUCCESS = "success"
            const val KEY_TRACKS_IMPORTED = "tracks_imported"
            const val KEY_EVENTS_CREATED = "events_created"
            const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
            const val KEY_PODCASTS_SKIPPED = "podcasts_skipped"
            const val KEY_FILES_PROCESSED = "files_processed"
            const val KEY_TOTAL_ENTRIES = "total_entries"
            const val KEY_ERROR_MESSAGE = "error_message"

            /** WorkManager result Data is limited to 10KB; keep well under it. */
            private const val MAX_ERROR_MESSAGE_CHARS = 2_000

            fun createNotificationChannel(context: Context) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            "YouTube Music Data Import",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = "Progress notifications for YouTube Music data import"
                        }
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.createNotificationChannel(channel)
                }
            }

            fun enqueueImport(
                context: Context,
                fileUris: List<String>,
            ): java.util.UUID {
                val inputData =
                    workDataOf(
                        KEY_FILE_URIS to fileUris.toTypedArray(),
                    )

                val workRequest =
                    OneTimeWorkRequestBuilder<YouTubeMusicImportWorker>()
                        .setInputData(inputData)
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            30,
                            TimeUnit.SECONDS,
                        ).addTag("youtube_music_import")
                        .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest,
                )

                Log.i(TAG, "Enqueued YouTube Music import (${fileUris.size} files)")
                return workRequest.id
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Cancelled YouTube Music import")
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
                Log.i(TAG, "Starting YouTube Music import worker")
                val startedAt = System.currentTimeMillis()

                // Multi-part Takeouts can be gigabytes and take minutes to walk
                // and parse; a plain background CoroutineWorker is killed at the
                // ~10-minute mark. A foreground dataSync service (already declared
                // in the manifest) keeps the import alive while the user navigates
                // away or the screen turns off.
                try {
                    setForeground(createForegroundInfo("Preparing import...", 0))
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (API 31+, app in
                    // background on a WorkManager restart): continue as background
                    // work — and if it's killed, WorkManager re-runs the import;
                    // per-file flushes + dedup make the re-run safe.
                    Log.w(TAG, "Foreground start not allowed; importing in background", e)
                }

                // Mirror the service's shared state flow into the notification so
                // a long import shows live progress instead of a frozen
                // "Preparing..." for its whole duration. Same flow drives the UI.
                val progressJob =
                    launch {
                        var lastNotifyAt = 0L
                        youTubeMusicImportService.importState.collect { state ->
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyAt < 1_000L) return@collect
                            when (state) {
                                is YouTubeMusicImportService.ImportState.Parsing -> {
                                    showProgressNotification(
                                        "Parsing ${state.fileName.ifBlank { "files" }} (${state.filesProcessed + 1}/${state.totalFiles})",
                                        state.filesProcessed,
                                        state.totalFiles,
                                    )
                                }

                                is YouTubeMusicImportService.ImportState.Importing -> {
                                    showProgressNotification(
                                        "Importing ${state.current}/${state.total} entries",
                                        state.current,
                                        state.total,
                                    )
                                }

                                else -> {
                                    return@collect
                                }
                            }
                            lastNotifyAt = now
                        }
                    }

                val uriStrings = inputData.getNullableStringArray(KEY_FILE_URIS)?.filterNotNull()
                if (uriStrings.isNullOrEmpty()) {
                    Log.e(TAG, "No file URIs provided")
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No files selected"))
                }

                val uris =
                    uriStrings.mapNotNull {
                        try {
                            android.net.Uri.parse(it)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse URI: $it", e)
                            null
                        }
                    }

                if (uris.isEmpty()) {
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No valid files found"))
                }

                try {
                    val result = youTubeMusicImportService.importFromUris(applicationContext, uris)

                    if (result.isSuccess) {
                        reportImport(RunPhase.COMPLETED, records = result.totalEntries, failure = null, startedAt = startedAt)
                        showCompletionNotification(result)
                        Result.success(
                            workDataOf(
                                KEY_SUCCESS to true,
                                KEY_TRACKS_IMPORTED to result.tracksImported,
                                KEY_EVENTS_CREATED to result.eventsCreated,
                                KEY_DUPLICATES_SKIPPED to result.duplicatesSkipped,
                                KEY_PODCASTS_SKIPPED to result.podcastsSkipped,
                                KEY_FILES_PROCESSED to result.filesProcessed,
                                KEY_TOTAL_ENTRIES to result.totalEntries,
                            ),
                        )
                    } else {
                        // Truncated: WorkManager Data caps at 10KB total, and the full
                        // joined errors would crash result delivery on hostile files.
                        // The service already caps its list at 50 + a summary line.
                        val errorMsg = result.errors.joinToString("; ").take(MAX_ERROR_MESSAGE_CHARS)
                        // The service reports failures as strings, so the category cannot be
                        // recovered from them without parsing free text — which the schema forbids.
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
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Import ran out of memory", e)
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.OUT_OF_MEMORY, startedAt = startedAt)
                    showFailureNotification("Not enough memory for this export. Try fewer files at once.")
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to "Not enough memory for this export. Try fewer files at once.",
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed with exception", e)
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClassifier.of(e), startedAt = startedAt)
                    showFailureNotification(e.message ?: "Unknown error")
                    Result.failure(
                        workDataOf(
                            KEY_SUCCESS to false,
                            KEY_ERROR_MESSAGE to (e.message ?: "Import failed"),
                        ),
                    )
                } finally {
                    progressJob.cancel()
                    cancelProgressNotification()
                }
            }

        override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Importing YouTube Music Data...", 0)

        private fun createForegroundInfo(
            message: String,
            progress: Int,
        ): ForegroundInfo {
            // Also the only place the channel used to be missing from: without
            // this call every import notification was silently dropped on API 26+.
            createNotificationChannel(applicationContext)

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
                    .setContentTitle("Importing YouTube Music Data")
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setProgress(100, progress, progress == 0)
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

        /** Never includes an error message — only the closed [FailureClass] category. */
        private fun reportImport(
            phase: RunPhase,
            records: Int,
            failure: FailureClass?,
            startedAt: Long,
        ) {
            tracker.track(
                ImportRun(
                    provider = ImportProvider.YOUTUBE_MUSIC,
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
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Importing YouTube Music Data")
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setProgress(total, current, current == 0)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        private fun showCompletionNotification(result: YouTubeMusicImportService.ImportResult) {
            val message =
                buildString {
                    append("${result.tracksImported} tracks imported, ")
                    append("${result.eventsCreated} listening events created")
                    if (result.podcastsSkipped > 0) {
                        append(", ${result.podcastsSkipped} podcasts skipped")
                    }
                    if (result.nonMusicSkipped > 0) {
                        append(", ${result.nonMusicSkipped} non-music skipped")
                    }
                }

            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("YouTube Music Import Complete")
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
        }

        private fun showFailureNotification(error: String) {
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("YouTube Music Import Failed")
                    .setContentText(error)
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
