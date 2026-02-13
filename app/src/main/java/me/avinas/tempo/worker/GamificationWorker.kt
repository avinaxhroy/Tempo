package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.repository.GamificationRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that recomputes XP, levels, and badges from listening history.
 * 
 * Runs every 6 hours (or on-demand when triggered).
 * XP is always deterministic: computed from the full listening event history,
 * so it's safe to re-run at any time without drift.
 */
@HiltWorker
class GamificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val gamificationRepository: GamificationRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "GamificationWorker"
        const val WORK_NAME = "gamification_refresh"
        
        /**
         * Enqueue periodic gamification refresh.
         */
        fun enqueuePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<GamificationWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(TAG)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.d(TAG, "Periodic gamification refresh enqueued")
        }
        
        /**
         * Trigger an immediate one-time refresh.
         */
        fun enqueueImmediateRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<GamificationWorker>()
                .addTag(TAG)
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Immediate gamification refresh enqueued")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting gamification refresh...")
            
            val result = gamificationRepository.fullRefresh()
            
            Log.i(TAG, "Gamification refresh complete: " +
                    "Level ${result.levelUpResult.newLevel}, " +
                    "XP ${result.levelUpResult.totalXp}, " +
                    "${result.newlyEarnedBadgeIds.size} new badges")
            
            if (result.levelUpResult.didLevelUp) {
                Log.i(TAG, "🎉 Level up! ${result.levelUpResult.previousLevel} → ${result.levelUpResult.newLevel}")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Gamification refresh failed", e)
            Result.retry()
        }
    }
}
