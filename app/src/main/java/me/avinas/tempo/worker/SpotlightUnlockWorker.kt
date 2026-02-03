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
import kotlinx.coroutines.flow.first
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.PreferencesRepository
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.stats.TimeRange
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker that checks daily for Spotlight story unlocks and sends Android notifications.
 * 
 * Checks for three types of story unlocks:
 * 1. Monthly Story - Unlocks on the last day of each month
 * 2. Yearly Story - Unlocks on December 1st
 * 3. All-Time Story - Unlocks after 6 months of listening data
 */
@HiltWorker
class SpotlightUnlockWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val statsRepository: RoomStatsRepository,
    private val preferencesRepository: PreferencesRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SpotlightUnlockWorker"
        private const val WORK_NAME = "spotlight_unlock_check"
        private const val CHANNEL_ID = "spotlight_unlocks"
        private const val NOTIFICATION_ID_MONTHLY = 4001
        private const val NOTIFICATION_ID_YEARLY = 4002
        private const val NOTIFICATION_ID_ALL_TIME = 4003

        /**
         * Schedule weekly checks for story unlocks.
         * 
         * Weekly checks are sufficient because:
         * - Monthly: Only unlocks on last day of month (day 28-31)
         * - Yearly: Only unlocks on December 1st
         * - All-Time: 6-month milestone doesn't need daily precision
         * 
         * Runs once per week with an 8-hour flex window for battery optimization.
         * HomeViewModel also checks when app opens, so this is just a backup for inactive users.
         */
        fun scheduleWeekly(context: Context) {
            // Battery-optimized constraints
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true) // Only run when battery is not low
                .build()

            val request = PeriodicWorkRequestBuilder<SpotlightUnlockWorker>(
                7, TimeUnit.DAYS,  // Weekly instead of daily
                1, TimeUnit.DAYS    // 1-day flex window (Android can choose optimal time)
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("spotlight_check")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing schedule to avoid rescheduling
                request
            )
            
            Log.i(TAG, "Scheduled weekly spotlight unlock checks (runs ~4x per month)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled spotlight unlock checks")
        }
        
        /**
         * Trigger an immediate check (useful after app opens or user enables notifications).
         */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SpotlightUnlockWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Triggered immediate spotlight unlock check")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running spotlight unlock check")
        
        val today = java.time.LocalDate.now()
        val todayString = today.toString() // YYYY-MM-DD format
        
        createNotificationChannel()
        
        val preferences = preferencesRepository.preferences().first() ?: return Result.success()
        
        // Check Monthly Story
        // Send if: (1) today is last day OR (2) we're in a new month and haven't notified for last month
        val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
        val lastNotifiedMonth = preferences.lastMonthlyReminderShown?.take(7) // YYYY-MM
        val lastMonth = today.minusMonths(1).toString().take(7) // Previous month YYYY-MM
        val currentMonth = today.toString().take(7) // Current month YYYY-MM
        
        val shouldNotifyMonthly = isLastDayOfMonth && lastNotifiedMonth != currentMonth
        val missedLastMonth = !isLastDayOfMonth && lastNotifiedMonth != lastMonth && lastNotifiedMonth != currentMonth
        
        if (shouldNotifyMonthly || missedLastMonth) {
            Log.i(TAG, "✅ Sending notification for MONTHLY story unlock (isLastDay=$isLastDayOfMonth, missed=$missedLastMonth)")
            sendMonthlyStoryNotification()
            preferencesRepository.updateLastMonthlyReminderShown(todayString)
        }
        
        // Check Yearly Story
        // Send if: (1) today is Dec 1+ OR (2) we're in December and haven't notified this year
        val isDecember = today.monthValue == 12
        val lastNotifiedYear = preferences.lastYearlyReminderShown?.take(4) // YYYY
        val currentYear = today.year.toString()
        
        if (isDecember && lastNotifiedYear != currentYear) {
            Log.i(TAG, "✅ Sending notification for YEARLY story unlock")
            sendYearlyStoryNotification()
            preferencesRepository.updateLastYearlyReminderShown(todayString)
        }
        
        // Check All-Time Story - only if not notified yet
        if (preferences.lastAllTimeReminderShown == null) {
            checkAllTimeStoryUnlock(preferences, todayString)
        }
        
        return Result.success()
    }

    /**
     * Check if the All-Time story has just unlocked (6 months of data reached).
     * Only checks earliest timestamp - SpotlightViewModel verifies data availability.
     */
    private suspend fun checkAllTimeStoryUnlock(
        preferences: me.avinas.tempo.data.local.entities.UserPreferences,
        todayString: String
    ) {
        val earliestTimestamp = statsRepository.getEarliestDataTimestamp()
        if (earliestTimestamp == null) {
            Log.d(TAG, "No listening data yet for all-time check")
            return
        }
        
        val now = java.time.LocalDate.now()
        val earliestDate = java.time.Instant.ofEpochMilli(earliestTimestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        
        val sixMonthsAgo = now.minusMonths(6)
        
        // Check if we've crossed the 6-month threshold
        if (!earliestDate.isAfter(sixMonthsAgo)) {
            // 6 months reached! Send notification
            // SpotlightViewModel will handle showing "not enough data" if needed
            Log.i(TAG, "✅ Sending notification for ALL-TIME story unlock (6 months milestone reached!)")
            sendAllTimeStoryNotification()
            preferencesRepository.updateLastAllTimeReminderShown(todayString)
        } else {
            // Still locked
            val unlockDate = earliestDate.plusMonths(6)
            val daysUntilUnlock = java.time.temporal.ChronoUnit.DAYS.between(now, unlockDate)
            Log.d(TAG, "All-time story still locked. Unlocks in $daysUntilUnlock days on $unlockDate")
        }
    }

    private fun sendMonthlyStoryNotification() {
        // Use previous month if we're sending early in current month (missed notification)
        val today = java.time.LocalDate.now()
        val targetMonth = if (today.dayOfMonth <= 7) {
            // Early in month, likely missed last month's notification
            today.minusMonths(1)
        } else {
            // Last day of month or near end
            today
        }
        
        val monthName = targetMonth.month.name
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "spotlight")
            putExtra("time_range", "THIS_MONTH")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_MONTHLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your $monthName Wrapped is Ready! 🎉")
            .setContentText("Your monthly listening story is now available")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your $monthName listening story is ready! Tap to view your stats, top songs, and personality."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_MONTHLY, notification)
        
        Log.i(TAG, "Sent monthly story notification for $monthName")
    }

    private fun sendYearlyStoryNotification() {
        val year = java.time.LocalDate.now().year
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "spotlight")
            putExtra("time_range", "THIS_YEAR")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_YEARLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your $year Wrapped is Here! 🌟")
            .setContentText("Your yearly listening story is now available")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your $year listening story is ready! Discover your top artists, songs, genres, and listening personality."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_YEARLY, notification)
        
        Log.i(TAG, "Sent yearly story notification for $year")
    }

    private fun sendAllTimeStoryNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "spotlight")
            putExtra("time_range", "ALL_TIME")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_ALL_TIME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("All-Time Wrapped Unlocked! 🏆")
            .setContentText("6 months of listening data - your ultimate story awaits")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Congratulations! You've reached 6 months of listening. Your All-Time Wrapped story is now available with your complete listening journey."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_ALL_TIME, notification)
        
        Log.i(TAG, "Sent all-time story notification (6 months milestone)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Spotlight Story Unlocks"
            val descriptionText = "Notifications when new Tempo Spotlight stories are available"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking for Story Unlocks")
            .setContentText("Checking if new Spotlight stories are available...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID_MONTHLY, notification)
    }
}
