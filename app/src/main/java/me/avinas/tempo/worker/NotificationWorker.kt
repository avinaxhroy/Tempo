package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.onboarding.dataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val statsRepository: RoomStatsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "NotificationWorker"
        private const val DAILY_WORK_NAME = "daily_notification_work"
        private const val WEEKLY_WORK_NAME = "weekly_notification_work"
        private const val CHANNEL_ID = "tempo_updates"

        fun scheduleDaily(context: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20) // 8 PM
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }

            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val initialDelay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<NotificationWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .addTag("daily")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleWeekly(context: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 19) // 7 PM
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }

            if (target.before(now)) {
                target.add(Calendar.WEEK_OF_YEAR, 1)
            }

            val initialDelay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<NotificationWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .addTag("weekly")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WEEKLY_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelDaily(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK_NAME)
        }

        fun cancelWeekly(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val isDaily = tags.contains("daily")
        val isWeekly = tags.contains("weekly")

        if (isDaily) {
            sendDailySummary()
        } else if (isWeekly) {
            sendWeeklyRecap()
        }

        return Result.success()
    }

    private suspend fun sendDailySummary() {
        // Check preference
        val enabled = context.dataStore.data.map { it[androidx.datastore.preferences.core.booleanPreferencesKey("notif_daily_summary")] ?: true }.first()
        if (!enabled) return

        val overview = statsRepository.getListeningOverview(TimeRange.TODAY)
        val totalMinutes = (overview.totalListeningTimeMs) / 1000 / 60
        if (totalMinutes < 5) return // Don't notify if barely listened

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val timeString = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

        showNotification(
            title = "Daily Summary 🎵",
            text = "You listened for $timeString today!"
        )
    }

    private suspend fun sendWeeklyRecap() {
        // Check preference
        val enabled = context.dataStore.data.map { it[androidx.datastore.preferences.core.booleanPreferencesKey("notif_weekly_recap")] ?: true }.first()
        if (!enabled) return

        val topArtistsResult = statsRepository.getTopArtists(TimeRange.THIS_WEEK, pageSize = 1)
        if (topArtistsResult.items.isEmpty()) return

        val topArtist = topArtistsResult.items.first()
        showNotification(
            title = "Weekly Recap 📅",
            text = "Your top artist this week was ${topArtist.artist}!"
        )
    }

    private fun showNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Tempo Updates"
            val descriptionText = "Daily summaries and weekly recaps"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
