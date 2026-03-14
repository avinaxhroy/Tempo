package me.avinas.tempo.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Battery optimization utilities for Desktop Sync and other features.
 *
 * Provides efficient battery level checking with caching to minimize system calls.
 */
object BatteryUtils {
    private const val TAG = "BatteryUtils"
    
    // Battery level thresholds
    const val CRITICAL_BATTERY_LEVEL = 20 // Don't sync when battery <= 20%
    const val LOW_BATTERY_LEVEL = 30      // Warn when battery <= 30%
    
    private var lastBatteryCheckTime = 0L
    @Volatile
    private var cachedBatteryLevel = 100
    private const val BATTERY_CHECK_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
    
    /**
     * Get the current battery level percentage.
     * Results are cached for 2 minutes to minimize system calls.
     *
     * @param context Android context for accessing BatteryManager
     * @return Battery level as percentage (0-100), or 100 if unable to determine
     */
    fun getBatteryLevel(context: Context, forceRefresh: Boolean = false): Int {
        val now = System.currentTimeMillis()
        if (forceRefresh || now - lastBatteryCheckTime > BATTERY_CHECK_INTERVAL_MS) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                cachedBatteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                lastBatteryCheckTime = now
                Log.d(TAG, "Battery level: $cachedBatteryLevel%")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery level", e)
                cachedBatteryLevel = 100 // Assume full battery if unable to check
            }
        }
        return cachedBatteryLevel
    }
    
    /**
     * Check if battery is critically low (≤ 20%).
     * Desktop Sync should not operate at this level.
     *
     * @param context Android context
     * @return true if battery is at or below critical threshold
     */
    fun isCriticalBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) <= CRITICAL_BATTERY_LEVEL
    }
    
    /**
     * Check if battery is low (≤ 30%).
     * Used for warnings and throttling high-battery tasks.
     *
     * @param context Android context
     * @return true if battery is at or below low threshold
     */
    fun isLowBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) <= LOW_BATTERY_LEVEL
    }
    
    /**
     * Check if battery is healthy (> 30%).
     *
     * @param context Android context
     * @return true if battery level is healthy
     */
    fun isHealthyBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) > LOW_BATTERY_LEVEL
    }
    
    /**
     * Check if device is currently charging.
     *
     * @param context Android context
     * @return true if device is charging (AC, USB, or wireless)
     */
    fun isCharging(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.e(TAG, "Error checking charging status", e)
            false
        }
    }
    
    /**
     * Force refresh battery cache (for testing or immediate updates).
     */
    fun invalidateCache() {
        lastBatteryCheckTime = 0
    }
}
