package me.avinas.tempo.data.analytics

/**
 * Single entry point for anonymous app-health reporting.
 *
 * The implementation is chosen once in `AnalyticsModule`, so call sites never change when
 * the backend does (or when analytics are off entirely).
 *
 * Contract:
 *  - [track] must never throw and must never block the calling thread.
 *  - [flush] must never throw; failures leave events queued for a later attempt.
 *  - [purge] must delete anything buffered but not yet transmitted, so opting out takes
 *    effect immediately rather than at the next flush.
 *  - [canReport] must answer whether [track] would accept an event *right now*.
 */
interface AnalyticsTracker {

    fun track(event: AnalyticsEvent)

    /**
     * Whether an event handed to [track] right now would actually be queued.
     *
     * Callers that write a "we already reported this" marker alongside an event need this, and
     * only this: [track] is fire-and-forget and silently drops events when the build cannot
     * report or the user is opted out, so a caller that marks first and sends second consumes
     * its once-a-day slot while nothing left the device. Asking here keeps the two halves —
     * send, then mark — from disagreeing about whether the send happened, and keeps the rule
     * itself in the one implementation that owns it.
     *
     * Not a cached flag: consent can change between two calls.
     */
    suspend fun canReport(): Boolean

    /** Best-effort send of anything buffered. Safe to call from a background worker. */
    suspend fun flush()

    /** Drops all buffered-but-unsent events. Called when the user opts out. */
    suspend fun purge()
}
