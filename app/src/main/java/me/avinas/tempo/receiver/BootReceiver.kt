package me.avinas.tempo.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import me.avinas.tempo.service.MusicTrackingService

/**
 * BootReceiver restarts the MusicTrackingService after device reboot.
 * 
 * Handles:
 * - BOOT_COMPLETED: Standard boot complete (preferred, wait for this)
 * - QUICKBOOT_POWERON: Quick boot (some manufacturers)
 * - LOCKED_BOOT_COMPLETED: Direct boot mode (ignored, wait for full boot)
 * 
 * Note: The NotificationListenerService is automatically started by the system
 * when notification access is granted. We only need to ensure the component
 * is enabled, not force rebinds which can cause duplicate listener connections.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        
        @Volatile
        private var hasHandledBoot = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Boot completed: ${intent.action}")

        when (intent.action) {
            // Only handle BOOT_COMPLETED (not LOCKED_BOOT_COMPLETED) to avoid duplicate bindings
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Skip LOCKED_BOOT_COMPLETED - the system will bind the NotificationListenerService
                // automatically when the user unlocks and we receive BOOT_COMPLETED
                Log.d(TAG, "Ignoring LOCKED_BOOT_COMPLETED, waiting for full BOOT_COMPLETED")
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        // Prevent handling boot multiple times (BOOT_COMPLETED can be sent multiple times)
        synchronized(BootReceiver::class.java) {
            if (hasHandledBoot) {
                Log.d(TAG, "Boot already handled, ignoring duplicate")
                return
            }
            hasHandledBoot = true
        }
        
        // Check if notification listener permission is granted
        if (!isNotificationListenerEnabled(context)) {
            Log.w(TAG, "Notification listener not enabled, skipping service start")
            return
        }

        Log.i(TAG, "Ensuring MusicTrackingService is enabled after boot")
        
        try {
            val componentName = ComponentName(context, MusicTrackingService::class.java)
            val currentState = context.packageManager.getComponentEnabledSetting(componentName)
            
            // Only re-enable if the component was disabled
            // The system will automatically bind the NotificationListenerService when enabled
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "MusicTrackingService component re-enabled")
            } else {
                Log.d(TAG, "MusicTrackingService component already enabled (state=$currentState)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/enable service after boot", e)
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }
}
