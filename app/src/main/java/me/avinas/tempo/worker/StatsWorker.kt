package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.stats.TimeRange
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for pre-computing statistics.
 * This worker runs periodically to warm the stats cache for common time ranges,
 * ensuring fast access when the user opens the stats screen.
 */
@HiltWorker
class StatsPrecomputeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val statsRepository: RoomStatsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "StatsPrecomputeWorker"
        const val WORK_NAME = "stats_precompute_work"
        private const val NOTIFICATION_CHANNEL_ID = "stats_precompute_worker"
        private const val NOTIFICATION_ID = 3006

        /**
         * Schedule periodic precomputation of stats.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<StatsPrecomputeWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
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
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Stats precompute work scheduled")
        }

        /**
         * Trigger immediate one-time precomputation.
         */
        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<StatsPrecomputeWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "One-time stats precompute triggered")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting stats precomputation")

        return try {
            // Precompute overview stats for common time ranges
            val timeRanges = listOf(
                TimeRange.TODAY,
                TimeRange.THIS_WEEK,
                TimeRange.THIS_MONTH
            )

            for (timeRange in timeRanges) {
                Log.d(TAG, "Precomputing stats for $timeRange")
                
                // These calls will populate the cache
                statsRepository.getListeningOverview(timeRange)
                statsRepository.getTopArtists(timeRange, page = 0, pageSize = 10)
                statsRepository.getTopTracks(timeRange, page = 0, pageSize = 10)
                statsRepository.getHourlyDistribution(timeRange)
                statsRepository.getDayOfWeekDistribution(timeRange)
                
                // Set progress for each completed time range
                setProgress(workDataOf("timeRange" to timeRange.name))
            }

            // Also precompute streak (time range independent)
            statsRepository.getListeningStreak()

            Log.d(TAG, "Stats precomputation completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Stats precomputation failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Stats Processing",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Computing Statistics")
            .setContentText("Updating your music stats...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}

/**
 * Worker that invalidates stats cache when new listening events are detected.
 * Triggered by the MusicTrackingService when new tracks are logged.
 */
@HiltWorker
class StatsCacheInvalidationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val statsRepository: RoomStatsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "StatsCacheInvalidation"
        const val WORK_NAME = "stats_cache_invalidation"
        const val KEY_EVENT_TIMESTAMP = "event_timestamp"
        private const val NOTIFICATION_CHANNEL_ID = "stats_cache_worker"
        private const val NOTIFICATION_ID = 3007

        /**
         * Trigger cache invalidation after a new listening event.
         */
        fun trigger(context: Context, eventTimestamp: Long) {
            val workRequest = OneTimeWorkRequestBuilder<StatsCacheInvalidationWorker>()
                .setInputData(workDataOf(KEY_EVENT_TIMESTAMP to eventTimestamp))
                .setInitialDelay(1, TimeUnit.SECONDS) // Small delay to batch events
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val eventTimestamp = inputData.getLong(KEY_EVENT_TIMESTAMP, System.currentTimeMillis())
        
        Log.d(TAG, "Invalidating stats cache for event at $eventTimestamp")
        
        // Notify repository of new event (triggers smart cache invalidation)
        statsRepository.onNewListeningEvent(eventTimestamp)
        
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Cache Update",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Updating Cache")
            .setContentText("Refreshing stats...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
