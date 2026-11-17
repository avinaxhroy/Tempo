package me.avinas.tempo.data.analytics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.service.TrackingServiceHeartbeat
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits the once-a-day active-use marker, and remembers that it did.
 *
 * Called from wherever the user proves they used Tempo today: opening the app, or a play
 * being committed by the tracking service. Whichever happens first wins and the rest of the
 * day's calls are no-ops, so a device opened five times still contributes exactly one active
 * day — which is the whole difference between counting users and counting taps.
 *
 * An in-memory mirror of "what has already gone out today" short-circuits every call after
 * the first, so this is safe to call from a per-play path without paying a disk read each
 * time. The mirror is a cache, never the source of truth: it starts empty in every process
 * and the ledger is consulted the first time through.
 *
 * Failures are swallowed on purpose. A missing presence marker costs one day of one metric;
 * an exception escaping into app start-up or a media callback would cost the user their
 * tracking.
 */
@Singleton
class AnalyticsDailyReporter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val tracker: AnalyticsTracker,
        private val ledger: AnalyticsDailyLedger,
    ) {
        private val reportedDay = AtomicLong(NEVER)
        private val markedUiOpenedDay = AtomicLong(NEVER)

        /** Serialises the slow path so two callers cannot both decide to send. */
        private val presenceLock = Mutex()

        /**
         * Records that the app's own UI was opened today.
         *
         * Kept separate from [reportPresenceIfDue] because opening the UI and being tracked
         * in the background are different kinds of active, and the usage aggregate reports
         * them separately. This only writes a day number; it sends nothing.
         */
        suspend fun markUiOpened(nowMillis: Long = System.currentTimeMillis()) {
            // A build that cannot report must not accumulate reporting state: no aggregate will
            // ever read it, and `markUiOpened` is called on every resume. Consent is
            // not consulted here — this only records that the UI was opened today, so a user
            // who switches reporting back on later the same day still gets an honest
            // `ui_opened` rather than a zero.
            if (!isConfigured()) return

            runCatching {
                val today = AnalyticsDaily.dayOf(nowMillis)
                if (markedUiOpenedDay.get() == today) return@runCatching

                ledger.markUiOpened(today)
                markedUiOpenedDay.set(today)
            }
        }

        /**
         * Sends [DailyActivity] if today's has not gone out yet.
         *
         * Safe to call on every app start and on every committed play: after the first call
         * of the day this is one atomic comparison.
         */
        suspend fun reportPresenceIfDue(nowMillis: Long = System.currentTimeMillis()) {
            if (!isConfigured()) return

            val today = AnalyticsDaily.dayOf(nowMillis)
            if (reportedDay.get() == today) return

            // NonCancellable because this is a track-and-mark pair, and a caller being
            // cancelled between the two halves is worse than either outcome alone. `track`
            // dispatches onto a scope that outlives the caller, so a plain cancellation could
            // send the marker and then lose the "already sent" write — and the next call of the
            // day would send a second one, inventing active users out of a lifecycle event.
            withContext(NonCancellable) {
                runCatching {
                    presenceLock.withLock {
                        // Re-checked inside the lock: another caller may have just finished.
                        if (reportedDay.get() == today) return@withLock

                        if (!tracker.canReport()) {
                            // Nothing may be marked while the gate is shut. Marking here would
                            // spend today's slot without sending anything, so the day a user
                            // switches reporting back on would be missing from the active-user
                            // count — the undercount this ledger exists to prevent.
                            //
                            // `reportedDay` is left unset: consent can change
                            // within the same day, and this is the caller that must notice.
                            return@withLock
                        }

                        if (!AnalyticsDaily.shouldReportPresence(ledger.presenceDay(), today)) {
                            // Already sent by an earlier process. Adopt that as today's answer
                            // rather than sending a second marker for the same day.
                            reportedDay.set(today)
                            return@withLock
                        }

                        tracker.track(DailyActivity(listenerDown = isListenerDown()))
                        // Marked only after the event has been handed to the queue. Losing a
                        // day is preferable to repeating one, which would invent active users.
                        ledger.markPresenceReported(today)
                        reportedDay.set(today)
                    }
                }
            }
        }

        /** Source builds and debug builds report nothing, so they should not even track state. */
        private fun isConfigured(): Boolean =
            AnalyticsGate.isBuildConfigured(
                endpoint = BuildConfig.ANALYTICS_ENDPOINT,
                isDebug = BuildConfig.DEBUG,
                debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
            )

        /**
         * True only when tracking *started and then stopped*.
         *
         * The naive form — "not currently healthy" — also fires on a fresh install, where
         * nothing has started yet because the user has not reached the permission screen.
         * That reports a fault for every new user, and would make a launch spike look like a
         * reliability regression. `hasEverStarted()` is what separates the two.
         */
        private fun isListenerDown(): Boolean {
            val heartbeat = TrackingServiceHeartbeat.snapshot(context)
            return heartbeat.hasEverStarted() && heartbeat.shouldRequestRebind()
        }

        private companion object {
            const val NEVER = Long.MIN_VALUE
        }
    }
