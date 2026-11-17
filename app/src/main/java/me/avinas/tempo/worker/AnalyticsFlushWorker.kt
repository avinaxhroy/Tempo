package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import java.util.concurrent.TimeUnit

/**
 * Uploads buffered anonymous app-health events.
 *
 * Any working connection is enough: the whole payload is a few hundred bytes per event and
 * ~16 KB on a busy day, so holding it back for Wi-Fi would suppress reporting for the
 * majority of users who are on mobile data only. The constraint is `CONNECTED`
 * rather than `UNMETERED` for that reason — see docs/ANALYTICS.md.
 *
 * Events older than 24h are dropped by the queue before they can be sent, so running this rarely
 * costs nothing but latency.
 */
@HiltWorker
class AnalyticsFlushWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val tracker: AnalyticsTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "AnalyticsFlushWorker"
            private const val WORK_NAME = "analytics_flush"
            private const val IMMEDIATE_WORK_NAME = "analytics_flush_now"

            /**
             * The upload constraints, exposed as a named function so a test can assert them.
             *
             * `CONNECTED` rather than `UNMETERED` on purpose: the payload is a few hundred
             * bytes per event, and gating on Wi-Fi silenced reporting for anyone on mobile
             * data only — who, given the 24h event expiry, then sent nothing at all.
             */
            fun flushConstraints(): Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            fun schedule(context: Context) {
                val request =
                    PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(
                        6,
                        TimeUnit.HOURS,
                        1,
                        TimeUnit.HOURS,
                    ).setConstraints(flushConstraints())
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            WorkRequest.MIN_BACKOFF_MILLIS,
                            TimeUnit.MILLISECONDS,
                        ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // UPDATE so a changed constraint or cadence repairs existing registrations.
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )

                Log.i(TAG, "Analytics flush scheduled")
            }

            /**
             * One-shot flush, used the moment collection first becomes possible and on every app
             * start afterwards.
             *
             * The periodic worker alone is not enough to get a first event out: its first run is
             * scheduled a full flex-adjusted interval ahead — `calculateNextRunTime()` adds
             * `interval - flex` to the first period — so with a 6h interval and 1h flex nothing
             * uploads for the first 5 hours, and a tester sees an empty dashboard until then.
             * This closes that gap, and any working connection is enough to carry it.
             */
            fun enqueueImmediate(context: Context) {
                val request =
                    OneTimeWorkRequestBuilder<AnalyticsFlushWorker>()
                        .setConstraints(flushConstraints())
                        .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    // KEEP so several app starts in quick succession collapse into one flush.
                    ExistingWorkPolicy.KEEP,
                    request,
                )

                Log.i(TAG, "Immediate analytics flush requested")
            }

            /** Called when the user opts out, so no scheduled work remains. */
            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
                Log.i(TAG, "Analytics flush cancelled")
            }
        }

        override suspend fun doWork(): Result {
            tracker.flush()
            return Result.success()
        }
    }
