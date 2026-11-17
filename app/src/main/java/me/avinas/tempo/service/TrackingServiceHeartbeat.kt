package me.avinas.tempo.service

import android.content.Context

/**
 * Lightweight process-independent heartbeat for the notification listener.
 *
 * WorkManager cannot ask a NotificationListenerService instance whether it is
 * bound. Persisting these timestamps lets the health worker distinguish
 * "permission is enabled" from "the listener is actually alive".
 */
object TrackingServiceHeartbeat {
    private const val PREFS_NAME = "tempo_tracking_service_heartbeat"

    private const val KEY_LISTENER_CONNECTED = "listener_connected"
    private const val KEY_LAST_SERVICE_CREATED_AT = "last_service_created_at"
    private const val KEY_LAST_SERVICE_ALIVE_AT = "last_service_alive_at"
    private const val KEY_LAST_LISTENER_CONNECTED_AT = "last_listener_connected_at"
    private const val KEY_LAST_LISTENER_DISCONNECTED_AT = "last_listener_disconnected_at"
    private const val KEY_LAST_NOTIFICATION_CALLBACK_AT = "last_notification_callback_at"
    private const val KEY_LAST_MEDIA_SESSION_CALLBACK_AT = "last_media_session_callback_at"
    private const val KEY_LAST_REBIND_REQUESTED_AT = "last_rebind_requested_at"

    const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    const val HEARTBEAT_STALE_MS = 30 * 60 * 1000L
    const val DISCONNECTED_GRACE_MS = 60 * 1000L
    const val FORCE_RESTART_AFTER_REBIND_MS = 10 * 60 * 1000L

    /**
     * Media/notification callbacks fire dozens of times per track. Readers only sample these
     * timestamps once per [HEARTBEAT_INTERVAL_MS] and treat them as stale only after
     * [HEARTBEAT_STALE_MS], so one write every few seconds is indistinguishable to them while
     * saving a SharedPreferences commit on every callback.
     */
    private const val CALLBACK_WRITE_INTERVAL_MS = 5_000L

    @Volatile
    private var lastCallbackWriteAt = 0L

    private fun shouldWriteCallback(now: Long): Boolean = synchronized(this) {
        val last = lastCallbackWriteAt
        if (last != 0L && now - last < CALLBACK_WRITE_INTERVAL_MS) {
            false
        } else {
            lastCallbackWriteAt = now
            true
        }
    }

    fun markServiceCreated(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SERVICE_CREATED_AT, now)
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markServiceAlive(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markListenerConnected(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_LISTENER_CONNECTED, true)
            .putLong(KEY_LAST_LISTENER_CONNECTED_AT, now)
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markListenerDisconnected(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_LISTENER_CONNECTED, false)
            .putLong(KEY_LAST_LISTENER_DISCONNECTED_AT, now)
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markNotificationCallback(context: Context, now: Long = System.currentTimeMillis()) {
        if (!shouldWriteCallback(now)) return
        prefs(context).edit()
            .putLong(KEY_LAST_NOTIFICATION_CALLBACK_AT, now)
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markMediaSessionCallback(context: Context, now: Long = System.currentTimeMillis()) {
        if (!shouldWriteCallback(now)) return
        prefs(context).edit()
            .putLong(KEY_LAST_MEDIA_SESSION_CALLBACK_AT, now)
            .putLong(KEY_LAST_SERVICE_ALIVE_AT, now)
            .apply()
    }

    fun markRebindRequested(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_REBIND_REQUESTED_AT, now)
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        return Snapshot(
            listenerConnected = prefs.getBoolean(KEY_LISTENER_CONNECTED, false),
            lastServiceCreatedAt = prefs.getLong(KEY_LAST_SERVICE_CREATED_AT, 0L),
            lastServiceAliveAt = prefs.getLong(KEY_LAST_SERVICE_ALIVE_AT, 0L),
            lastListenerConnectedAt = prefs.getLong(KEY_LAST_LISTENER_CONNECTED_AT, 0L),
            lastListenerDisconnectedAt = prefs.getLong(KEY_LAST_LISTENER_DISCONNECTED_AT, 0L),
            lastNotificationCallbackAt = prefs.getLong(KEY_LAST_NOTIFICATION_CALLBACK_AT, 0L),
            lastMediaSessionCallbackAt = prefs.getLong(KEY_LAST_MEDIA_SESSION_CALLBACK_AT, 0L),
            lastRebindRequestedAt = prefs.getLong(KEY_LAST_REBIND_REQUESTED_AT, 0L)
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val listenerConnected: Boolean,
        val lastServiceCreatedAt: Long,
        val lastServiceAliveAt: Long,
        val lastListenerConnectedAt: Long,
        val lastListenerDisconnectedAt: Long,
        val lastNotificationCallbackAt: Long,
        val lastMediaSessionCallbackAt: Long,
        val lastRebindRequestedAt: Long
    ) {
        fun hasEverStarted(): Boolean = lastServiceCreatedAt > 0L || lastServiceAliveAt > 0L

        fun shouldRequestRebind(now: Long = System.currentTimeMillis()): Boolean {
            if (!hasEverStarted()) return true

            val aliveIsFresh = lastServiceAliveAt > 0L && now - lastServiceAliveAt <= HEARTBEAT_STALE_MS
            val lastDisconnectWins = lastListenerDisconnectedAt > lastListenerConnectedAt
            val disconnectedTooLong = lastDisconnectWins &&
                now - lastListenerDisconnectedAt > DISCONNECTED_GRACE_MS

            if (disconnectedTooLong) return true
            if (!listenerConnected && now - lastServiceCreatedAt > DISCONNECTED_GRACE_MS) return true

            return !aliveIsFresh
        }

        fun shouldForceRestartAfterRebind(now: Long = System.currentTimeMillis()): Boolean {
            return lastRebindRequestedAt > 0L &&
                lastRebindRequestedAt > lastServiceAliveAt &&
                now - lastRebindRequestedAt >= FORCE_RESTART_AFTER_REBIND_MS
        }
    }
}
