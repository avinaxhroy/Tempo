package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import java.util.concurrent.TimeUnit

@HiltWorker
class SpotifyJsonImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val spotifyJsonImportService: SpotifyJsonImportService
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SpotifyJsonImportWorker"

        private const val NOTIFICATION_CHANNEL_ID = "spotify_json_import_channel"
        private const val NOTIFICATION_ID = 9100
        private const val NOTIFICATION_COMPLETION_ID = 9101

        private const val WORK_NAME = "spotify_json_import"

        const val KEY_FILE_URIS = "file_uris"
        const val KEY_SUCCESS = "success"
        const val KEY_TRACKS_IMPORTED = "tracks_imported"
        const val KEY_EVENTS_CREATED = "events_created"
        const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
        const val KEY_PODCASTS_SKIPPED = "podcasts_skipped"
        const val KEY_LOW_QUALITY_SKIPPED = "low_quality_skipped"
        const val KEY_FILES_PROCESSED = "files_processed"
        const val KEY_TOTAL_ENTRIES = "total_entries"
        const val KEY_ERROR_MESSAGE = "error_message"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Spotify Data Import",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress notifications for Spotify data export import"
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        suspend fun enqueueImport(
            context: Context,
            fileUris: List<String>
        ): java.util.UUID? {
            if (isRunning(context)) {
                Log.w(TAG, "Import already in progress, ignoring enqueue request")
                return null
            }

            // ponytail: WorkManager input Data caps at ~10KB — bound the URI list.
            if (fileUris.size > 50) {
                Log.w(TAG, "Too many files (${fileUris.size}), refusing to enqueue")
                return null
            }

            val inputData = workDataOf(
                KEY_FILE_URIS to fileUris.toTypedArray()
            )

            val workRequest = OneTimeWorkRequestBuilder<SpotifyJsonImportWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("spotify_json_import")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "Enqueued Spotify JSON import (${fileUris.size} files)")
            return workRequest.id
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled Spotify JSON import")
        }

        suspend fun isRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
            workInfos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting Spotify JSON import worker")

        showProgressNotification("Preparing import...", 0, 100)

        val uriStrings = inputData.getNullableStringArray(KEY_FILE_URIS)?.filterNotNull()
        if (uriStrings.isNullOrEmpty()) {
            Log.e(TAG, "No file URIs provided")
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No files selected"))
        }

        val uris = uriStrings.mapNotNull {
            try {
                android.net.Uri.parse(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse URI: $it", e)
                null
            }
        }

        if (uris.isEmpty()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No valid files found"))
        }

        try {
            val result = spotifyJsonImportService.importFromUris(applicationContext, uris)

            if (result.isSuccess) {
                showCompletionNotification(result)
                Result.success(workDataOf(
                    KEY_SUCCESS to true,
                    KEY_TRACKS_IMPORTED to result.tracksImported,
                    KEY_EVENTS_CREATED to result.eventsCreated,
                    KEY_DUPLICATES_SKIPPED to result.duplicatesSkipped,
                    KEY_PODCASTS_SKIPPED to result.podcastsSkipped,
                    KEY_LOW_QUALITY_SKIPPED to result.lowQualitySkipped,
                    KEY_FILES_PROCESSED to result.filesProcessed,
                    KEY_TOTAL_ENTRIES to result.totalEntries
                ))
            } else {
                // ponytail: errors list is user-file-derived — truncate for the notification
                // and the WorkManager output Data (Binder limit).
                val errorMsg = result.errors.take(5).joinToString("; ").take(500)
                showFailureNotification(errorMsg)
                Result.failure(workDataOf(
                    KEY_SUCCESS to false,
                    KEY_ERROR_MESSAGE to errorMsg
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed with exception", e)
            showFailureNotification(e.message ?: "Unknown error")
            Result.failure(workDataOf(
                KEY_SUCCESS to false,
                KEY_ERROR_MESSAGE to (e.message ?: "Import failed")
            ))
        } finally {
            cancelProgressNotification()
        }
    }

    private fun showProgressNotification(message: String, current: Int, total: Int) {
        if (!hasNotificationPermission(applicationContext)) return
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Importing Spotify Data")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(total, current, current == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(result: SpotifyJsonImportService.ImportResult) {
        if (!hasNotificationPermission(applicationContext)) return
        val message = buildString {
            append("${result.tracksImported} tracks imported, ")
            append("${result.eventsCreated} listening events created")
            if (result.podcastsSkipped > 0) {
                append(", ${result.podcastsSkipped} podcasts skipped")
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Spotify Import Complete")
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
        if (!hasNotificationPermission(applicationContext)) return
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Spotify Import Failed")
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
