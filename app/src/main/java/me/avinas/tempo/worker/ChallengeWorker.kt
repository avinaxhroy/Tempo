package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.ChallengeRepository
import me.avinas.tempo.ui.onboarding.dataStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltWorker
class ChallengeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val challengeRepository: ChallengeRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ChallengeWorker"
        private const val DAILY_WORK_NAME = "daily_challenge_work"
        private const val CHANNEL_ID = "tempo_challenges"
        private const val NOTIFICATION_ID = 3005

        fun scheduleDaily(context: Context) {
            val now = Calendar.getInstance()
            // Schedule for shortly after midnight (e.g., 12:05 AM)
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
            }

            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val initialDelay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<ChallengeWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled ChallengeWorker to run daily at 12:05 AM.")
        }
        
        fun cancelDaily(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running daily ChallengeWorker...")
        
        try {
            // 1. Generate new challenges for today
            challengeRepository.generateDailyChallengesIfNeeded()
            
            // 2. Check if user wants notifications for new challenges
            val notifPrefKey = booleanPreferencesKey("notif_daily_challenges")
            val notificationsEnabled = context.dataStore.data.map { prefs -> 
                prefs[notifPrefKey] ?: true // Default is ON
            }.first()

            if (notificationsEnabled) {
                sendChallengeReadyNotification()
            }
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating daily challenges", e)
            return Result.retry()
        }
    }

    private fun sendChallengeReadyNotification() {
        createNotificationChannel()
        
        // Create an intent that opens the app when notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "profile_challenges")
            }
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Daily Challenges! 🎯")
            .setContentText("Your personalized challenges are ready for today. Earn bonus XP!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Daily Challenges"
            val descriptionText = "Notifications when your new daily challenges are ready"
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
