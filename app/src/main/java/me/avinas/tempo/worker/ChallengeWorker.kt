package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
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
import me.avinas.tempo.data.local.dao.StatsDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.repository.ChallengeRepository
import me.avinas.tempo.ui.onboarding.dataStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltWorker
class ChallengeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
    private val statsDao: StatsDao,
    private val userPreferencesDao: UserPreferencesDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ChallengeWorker"
        private const val DAILY_WORK_NAME = "daily_challenge_work"

        // Default fallback: notify at 9 AM if no listening history exists
        private const val DEFAULT_NOTIF_HOUR = 9

        // Recalculate preferred hour every 14 days
        private const val RECALC_INTERVAL_MS = 14L * 24 * 60 * 60 * 1000L

        fun scheduleDaily(context: Context) {
            val now = Calendar.getInstance()
            // ChallengeWorker runs at midnight (12:05 AM) to GENERATE challenges for today.
            // The actual notification is dispatched separately via NotificationWorker at the
            // user's preferred listening hour (smart timing).
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
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

        return try {
            val isGamificationEnabled = userPreferencesDao.getSync()?.isGamificationEnabled ?: true
            if (!isGamificationEnabled) {
                Log.i(TAG, "Gamification is disabled. Skipping challenge generation.")
                return Result.success()
            }

            // 1. Generate new challenges for today
            challengeRepository.generateDailyChallengesIfNeeded()

            // 2. Check if user wants notifications for new challenges
            val notifPrefKey = booleanPreferencesKey("notif_daily_challenges")
            val notificationsEnabled = context.dataStore.data.map { prefs ->
                prefs[notifPrefKey] ?: true // Default is ON
            }.first()

            if (notificationsEnabled) {
                // 3. Calculate (or retrieve cached) smart preferred notification hour
                val preferredHour = computePreferredNotifHour()
                Log.d(TAG, "Scheduling challenge-ready notification at hour $preferredHour")

                // 4. Schedule a one-shot NotificationWorker to fire at the preferred hour
                //    (this replaces the old approach of notifying directly at midnight)
                NotificationWorker.scheduleChallengeReady(context, preferredHour)
            } else {
                Log.d(TAG, "Challenge notifications disabled — skipping notification scheduling.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating daily challenges", e)
            Result.retry()
        }
    }

    /**
     * Compute the preferred notification hour for challenge notifications.
     *
     * Strategy:
     *  - Check stored smart hour in UserPreferences. If it was computed within [RECALC_INTERVAL_MS]
     *    AND is within the morning window (8 AM – 12 PM), reuse it.
     *  - Otherwise, look at the average first-listen hour per day over the last 28 days
     *    (i.e. when the user typically starts their listening session).
     *    Clamp the result to the morning window (8 AM – 12 PM) so the user always has the
     *    full day to complete their challenges.
     *  - Fall back to [DEFAULT_NOTIF_HOUR] (9 AM) if there is no history yet.
     *  - Persist the newly computed hour so future runs are fast.
     */
    private suspend fun computePreferredNotifHour(): Int {
        val prefs = userPreferencesDao.getSync()

        // Check if we have a recently calculated hour we can reuse.
        // Discard cached hours outside the valid waking-hours window (7–21),
        // which can happen if the cache was written with the old morning-only cap.
        val storedHour = prefs?.smartChallengeNotifHour
        val storedCalcTime = prefs?.smartChallengeNotifCalcTime ?: 0L
        val now = System.currentTimeMillis()
        val isStoredHourValid = storedHour != null && storedHour in 7..21

        if (isStoredHourValid && (now - storedCalcTime) < RECALC_INTERVAL_MS) {
            Log.d(TAG, "Reusing cached smart notification hour: $storedHour")
            return storedHour!!
        }

        // Query the typical hour the user starts listening each day over the last 28 days.
        // Using start-of-listening rather than peak hour ensures the notification arrives
        // around the time the user naturally opens their music app for the day.
        val startMs = now - (28L * 24 * 60 * 60 * 1000L)
        val typicalStartHour: Int = try {
            val hourly = statsDao.getTypicalStartHour(startMs, now)
            hourly?.hour ?: DEFAULT_NOTIF_HOUR
        } catch (e: Exception) {
            Log.w(TAG, "Could not query typical start hour, using default", e)
            DEFAULT_NOTIF_HOUR
        }

        // Notify 1 hour before the user's typical first-listen hour so the challenge is
        // visible right as they're about to open their music app. Clamped to waking hours
        // (7 AM – 9 PM) regardless of listening schedule.
        val clampedHour = (typicalStartHour - 1).coerceIn(7, 21)
        Log.i(TAG, "Computed smart notification hour: typical start=$typicalStartHour → scheduled at $clampedHour")

        // Persist for future use
        try {
            val updatedPrefs = (prefs ?: me.avinas.tempo.data.local.entities.UserPreferences()).copy(
                smartChallengeNotifHour = clampedHour,
                smartChallengeNotifCalcTime = now
            )
            userPreferencesDao.upsert(updatedPrefs)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist smart notification hour", e)
        }

        return clampedHour
    }
}
