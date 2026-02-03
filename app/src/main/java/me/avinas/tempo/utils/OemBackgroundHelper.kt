package me.avinas.tempo.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper for OEM-specific background process management.
 * 
 * Based on https://dontkillmyapp.com/xiaomi recommendations.
 * Xiaomi/MIUI is rated 5/5 💩 for aggressive background app killing.
 * 
 * Required user settings for Xiaomi devices:
 * 1. Autostart permission
 * 2. App locking in recent apps
 * 3. Battery Saver → No restriction
 * 4. Boost Speed lock
 * 5. MIUI Optimizations OFF (Developer Options)
 */
object OemBackgroundHelper {
    
    private const val TAG = "OemBackgroundHelper"
    
    // ============================
    // Device Detection
    // ============================
    
    /**
     * Check if device is manufactured by Xiaomi (includes Redmi, POCO, Black Shark).
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer in listOf("xiaomi", "redmi", "poco", "blackshark") ||
               brand in listOf("xiaomi", "redmi", "poco", "blackshark")
    }
    
    /**
     * Get MIUI version string (e.g., "14", "13.0.5").
     * Returns null if not a MIUI device.
     */
    fun getMiuiVersion(): String? {
        return getSystemProperty("ro.miui.ui.version.name")
            ?: getSystemProperty("ro.miui.ui.version.code")
    }
    
    /**
     * Check if device runs HyperOS (Xiaomi's new OS replacing MIUI).
     */
    fun isHyperOS(): Boolean {
        val hyperOsVersion = getSystemProperty("ro.mi.os.version.name")
        return !hyperOsVersion.isNullOrBlank()
    }
    
    /**
     * Get HyperOS version string if available.
     */
    fun getHyperOSVersion(): String? {
        return getSystemProperty("ro.mi.os.version.name")
    }
    
    /**
     * Get display name for the OS (MIUI or HyperOS).
     */
    fun getOsDisplayName(): String? {
        return when {
            isHyperOS() -> "HyperOS ${getHyperOSVersion() ?: ""}".trim()
            getMiuiVersion() != null -> "MIUI ${getMiuiVersion()}"
            isXiaomiDevice() -> "Xiaomi"
            else -> null
        }
    }
    
    // ============================
    // Autostart State Detection
    // ============================
    
    enum class AutostartState {
        ENABLED,
        DISABLED,
        UNKNOWN  // Can't determine (not Xiaomi or API not available)
    }
    
    /**
     * Check autostart permission state.
     * 
     * Uses reflection to access MIUI's internal AppOpsUtils.
     * Based on https://github.com/nicholassm/MIUI-autostart
     */
    fun getAutostartState(context: Context): AutostartState {
        if (!isXiaomiDevice()) return AutostartState.UNKNOWN
        
        return try {
            // MIUI uses AppOps permission OP_AUTO_START (10008)
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            val appOpsClass = Class.forName("android.app.AppOpsManager")
            val checkOpMethod = appOpsClass.getMethod(
                "checkOpNoThrow",
                Integer.TYPE,  // Use primitive int type for reflection
                Integer.TYPE,
                String::class.java
            )
            
            // OP_AUTO_START = 10008 in MIUI
            val result = checkOpMethod.invoke(
                appOps,
                10008,  // OP_AUTO_START
                android.os.Process.myUid(),
                context.packageName
            ) as Int
            
            // MODE_ALLOWED = 0, MODE_IGNORED/DENIED = 1/2
            when (result) {
                0 -> AutostartState.ENABLED
                else -> AutostartState.DISABLED
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not determine autostart state: ${e.message}")
            // Fallback: try content provider method
            getAutostartStateFromProvider(context)
        }
    }
    
    /**
     * Fallback method using MIUI's content provider.
     */
    private fun getAutostartStateFromProvider(context: Context): AutostartState {
        return try {
            val uri = Uri.parse("content://com.miui.securitycenter.provider/autostart")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val pkg = cursor.getString(cursor.getColumnIndexOrThrow("package_name"))
                    if (pkg == context.packageName) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow("status"))
                        // MIUI content provider: 0 typically means enabled, 3 means disabled
                        // But this can vary - log the value for debugging
                        Log.d(TAG, "Autostart status from content provider: $status")
                        return@use if (status == 0 || status == 1) AutostartState.ENABLED else AutostartState.DISABLED
                    }
                }
                AutostartState.UNKNOWN
            } ?: AutostartState.UNKNOWN
        } catch (e: Exception) {
            Log.d(TAG, "Content provider fallback failed: ${e.message}")
            AutostartState.UNKNOWN
        }
    }
    
    // ============================
    // Settings Launchers
    // ============================
    
    /**
     * Open MIUI Autostart settings.
     * Returns true if launched successfully.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI 14+ path
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            // Alternative path
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            },
            // HyperOS path
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.MainAc498500Activity"
                )
            }
        )
        return tryLaunchIntents(context, intents)
    }
    
    /**
     * Open Battery Saver settings for the app.
     */
    fun openBatterySaverSettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI Battery saver
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", "Tempo")
            },
            // Security Center battery
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerMainActivity"
                )
            },
            // Standard Android battery settings
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        return tryLaunchIntents(context, intents)
    }
    
    /**
     * Open App Lock / Boost Speed settings.
     */
    fun openAppLockSettings(context: Context): Boolean {
        val intents = listOf(
            // Boost speed lock apps
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.MainActivity"
                )
                putExtra("target_fragment", "BoostSpeedFragment")
            },
            // Security center main
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.MainActivity"
                )
            }
        )
        return tryLaunchIntents(context, intents)
    }
    
    /**
     * Open Developer Options (for MIUI Optimizations toggle).
     */
    fun openDeveloperOptions(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not open developer options", e)
            false
        }
    }
    
    /**
     * Open app's specific battery settings page.
     */
    fun openAppBatterySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app settings", e)
            false
        }
    }
    
    /**
     * Open dontkillmyapp.com Xiaomi page in browser.
     */
    fun openDontKillMyAppPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://dontkillmyapp.com/xiaomi")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open browser", e)
        }
    }
    
    // ============================
    // Utilities
    // ============================
    
    private fun tryLaunchIntents(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        // Last resort: open app settings
        return openAppBatterySettings(context)
    }
    
    private fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
