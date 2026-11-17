package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import me.avinas.tempo.data.repository.ChallengeRepository
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.data.repository.PreferencesRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that recomputes XP, levels, and badges from listening history.
 *
 * Runs every 6 hours (or on-demand when triggered).
 * XP is always deterministic: computed from the full listening event history,
 * so it's safe to re-run at any time without drift.
 */
@HiltWorker
class GamificationWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val gamificationRepository: GamificationRepository,
        private val preferencesRepository: PreferencesRepository,
        private val challengeRepository: ChallengeRepository,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            const val TAG = "GamificationWorker"
            const val WORK_NAME = "gamification_refresh"
            private const val IMMEDIATE_WORK_NAME = "gamification_immediate_refresh"

            /**
             * Enqueue periodic gamification refresh.
             */
            fun enqueuePeriodicRefresh(context: Context) {
                val request =
                    PeriodicWorkRequestBuilder<GamificationWorker>(
                        6,
                        TimeUnit.HOURS,
                        30,
                        TimeUnit.MINUTES, // flex interval
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).addTag(TAG)
                        .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )

                Log.d(TAG, "Periodic gamification refresh enqueued")
            }

            /**
             * Cancel periodic gamification refresh.
             */
            fun cancelPeriodicRefresh(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Periodic gamification refresh cancelled")
            }

            /**
             * Trigger an immediate one-time refresh.
             */
            fun enqueueImmediateRefresh(context: Context) {
                val request =
                    OneTimeWorkRequestBuilder<GamificationWorker>()
                        .addTag(TAG)
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        ).build()

                // This runs on every process start (including widget ticks and boot), and each run
                // costs a full XP/badge recompute. KEEP collapses starts that arrive while one is
                // already queued or running; the 6-hourly periodic refresh is the backstop.
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        IMMEDIATE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        request,
                    )
                Log.d(TAG, "Immediate gamification refresh enqueued")
            }
        }

        override suspend fun doWork(): Result {
            return try {
                val isGamificationEnabled = preferencesRepository.preferences().first()?.isGamificationEnabled ?: true
                if (!isGamificationEnabled) {
                    Log.i(TAG, "Gamification is disabled. Skipping refresh.")
                    return Result.success()
                }

                Log.i(TAG, "Starting gamification refresh...")

                // Refresh daily-challenge progress from listening history BEFORE the
                // full XP/badge recompute. Challenge progress was previously only
                // updated when the user opened the Profile screen, so plays recorded
                // in the background never advanced (or completed) challenges until
                // the UI was visited — the "played music not counted in challenges"
                // complaint. Doing it here keeps challenges current even when the
                // app is only running its background workers.
                try {
                    challengeRepository.refreshChallengeProgress()
                } catch (e: Exception) {
                    // Non-fatal: a challenge-progress failure must not block the
                    // deterministic XP/badge recompute below.
                    Log.w(TAG, "Challenge progress refresh failed", e)
                }

                // Prune challenges older than 90 days so daily_challenges stays bounded and the
                // getAllCompletedChallenges() scan in every XP recompute stays cheap. The pruned
                // rows' XP is banked into user_level first, so total XP is never reduced.
                try {
                    val cutoff =
                        java.time.LocalDate
                            .now()
                            .minusDays(90)
                            .toString()
                    gamificationRepository.pruneChallengesPreservingXp(cutoff)
                } catch (e: Exception) {
                    Log.w(TAG, "Challenge prune failed", e)
                }

                val result = gamificationRepository.fullRefresh()

                Log.i(
                    TAG,
                    "Gamification refresh complete: " +
                        "Level ${result.levelUpResult.newLevel}, " +
                        "XP ${result.levelUpResult.totalXp}, " +
                        "${result.newlyEarnedBadgeIds.size} new badges",
                )

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
