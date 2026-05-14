package me.avinas.tempo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.utils.BatteryUtils

/**
 * BatteryStateReceiver listens for battery level changes and notifies
 * [MusicTrackingService] so it can smoothly resume tracking the moment
 * the battery recovers above the low-battery threshold.
 *
 * We listen for both:
 *  - [Intent.ACTION_BATTERY_OKAY]   – system signal that battery is no longer low
 *  - [Intent.ACTION_BATTERY_CHANGED] – fine-grained level updates (only act when
 *    we cross the threshold upward, to avoid unnecessary wake-ups)
 *
 * This receiver is registered *dynamically* inside [MusicTrackingService] so it
 * is only active while the service is running. No static manifest entry needed.
 *
 * ## Sticky-broadcast safety
 * [Intent.ACTION_BATTERY_CHANGED] is a sticky broadcast. Android delivers it
 * *immediately* upon [Context.registerReceiver] with the last known battery status,
 * even if the battery level has not changed. Without a guard this causes a spurious
 * "battery recovered" dispatch every time the service starts.
 *
 * The [markReady] call (made by [MusicTrackingService] right after registration)
 * signals that the initial sticky delivery has been absorbed and subsequent
 * deliveries are genuine level-change events.
 */
class BatteryStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryStateReceiver"
    }

    /**
     * Set to true after the first [Intent.ACTION_BATTERY_CHANGED] delivery is absorbed.
     * The service calls [markReady] immediately after [Context.registerReceiver] to
     * let the sticky delivery pass through without triggering a recovery scan.
     */
    @Volatile
    private var isReady: Boolean = false

    /** Called by [MusicTrackingService] to arm the threshold-crossing detection. */
    fun markReady() {
        isReady = true
        Log.d(TAG, "BatteryStateReceiver is now armed for threshold-crossing detection")
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_OKAY -> {
                // System broadcast: battery level is no longer considered low.
                // This is NOT a sticky broadcast so no spurious delivery guard needed.
                Log.i(TAG, "ACTION_BATTERY_OKAY received — instructing service to resume tracking scan")
                BatteryUtils.invalidateCache()
                notifyService(context, MusicTrackingService.ACTION_BATTERY_RECOVERED)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                // ACTION_BATTERY_CHANGED is sticky: first delivery on registration is the
                // current level, not a change. Ignore it until markReady() has been called.
                if (!isReady) {
                    Log.d(TAG, "Ignoring initial sticky ACTION_BATTERY_CHANGED on registration")
                    return
                }

                // Fine-grained update: only act when we cross the threshold upward.
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return

                val pct = level * 100 / scale
                // Only act when we cross from low → normal to avoid constant noise.
                // Read wasLow from the CACHED value (pre-invalidation) then refresh.
                if (pct > BatteryUtils.CRITICAL_BATTERY_LEVEL) {
                    val wasLow = BatteryUtils.isCriticalBattery(context) // uses cache
                    if (wasLow) {
                        BatteryUtils.invalidateCache()
                        val isNowOkay = !BatteryUtils.isCriticalBattery(context, forceRefresh = true)
                        if (isNowOkay) {
                            Log.i(TAG, "Battery recovered ($pct%) — instructing service to resume tracking scan")
                            notifyService(context, MusicTrackingService.ACTION_BATTERY_RECOVERED)
                        }
                    }
                }
            }
        }
    }

    /**
     * Notifies [MusicTrackingService] via [Context.startForegroundService] (Android 8+) or
     * [Context.startService] (older). The service is already running as a foreground service
     * so this is not a background-start — it merely delivers the action intent.
     */
    private fun notifyService(context: Context, action: String) {
        val intent = Intent(context, MusicTrackingService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Service may not be running; that's fine — it will check battery on next wake
            Log.w(TAG, "Could not deliver $action to MusicTrackingService: ${e.message}")
        }
    }
}
