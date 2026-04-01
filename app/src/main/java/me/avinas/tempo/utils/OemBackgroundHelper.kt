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
     *
     * Note: On Redmi phones, Build.MANUFACTURER = "Xiaomi" and Build.BRAND = "Redmi".
     * We check both to cover all sub-brands.
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return manufacturer in listOf("xiaomi", "redmi", "poco", "blackshark") ||
               brand in listOf("xiaomi", "redmi", "poco", "blackshark") ||
               // Fallback: some OEM variants identify by model prefix
               model.startsWith("redmi") || model.startsWith("poco")
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
     * Falls back to brand name when system properties are unavailable (Android 10+ restriction).
     */
    fun getOsDisplayName(): String? {
        if (!isXiaomiDevice()) return null
        // Try to detect HyperOS first (newer Xiaomi OS replacing MIUI)
        val hyperOs = getHyperOSVersion()
        if (!hyperOs.isNullOrBlank()) return "HyperOS $hyperOs".trim()
        // Try MIUI
        val miui = getMiuiVersion()
        if (!miui.isNullOrBlank()) return "MIUI $miui"
        // Fallback: system properties unavailable (Android 10+ restriction on getprop)
        // Use a friendly brand name based on Build fields
        val brand = Build.BRAND.let {
            it.replaceFirstChar { c -> c.uppercaseChar() }
        }
        return brand // e.g. "Redmi", "Poco", "Xiaomi"
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
     * Uses reflection to access MIUI/HyperOS internal AppOps.
     * OP codes vary across MIUI versions:
     *   - MIUI (older): 10008
     *   - HyperOS / MIUI 14+: may also be 10021 or checked differently
     * Falls back to content provider if reflection fails.
     */
    fun getAutostartState(context: Context): AutostartState {
        if (!isXiaomiDevice()) return AutostartState.UNKNOWN

        // Try reflection with multiple known OP codes for MIUI/HyperOS autostart
        val autostartOpCodes = listOf(10008, 10021)
        for (opCode in autostartOpCodes) {
            val state = checkAutostartViaReflection(context, opCode)
            if (state != AutostartState.UNKNOWN) return state
        }

        // Fallback: content provider (works on some MIUI versions)
        return getAutostartStateFromProvider(context)
    }

    /**
     * Attempt to read autostart state via AppOps reflection for a given op code.
     */
    private fun checkAutostartViaReflection(context: Context, opCode: Int): AutostartState {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            val appOpsClass = Class.forName("android.app.AppOpsManager")
            // Signature: checkOpNoThrow(int op, int uid, String packageName)
            val checkOpMethod = appOpsClass.getMethod(
                "checkOpNoThrow",
                Integer.TYPE,
                Integer.TYPE,
                String::class.java
            )
            val result = checkOpMethod.invoke(
                appOps,
                opCode,
                android.os.Process.myUid(),
                context.packageName
            ) as Int
            Log.d(TAG, "Autostart op=$opCode result=$result")
            // MODE_ALLOWED = 0, anything else = not allowed
            if (result == 0) AutostartState.ENABLED else AutostartState.DISABLED
        } catch (e: Exception) {
            Log.d(TAG, "Reflection check for op=$opCode failed: ${e.message}")
            AutostartState.UNKNOWN
        }
    }

    /**
     * Fallback method using MIUI's content provider.
     *
     * Status column semantics on MIUI content provider:
     *   1 = enabled/allowed, 0 or other = disabled
     * (Note: This is the OPPOSITE of AppOps MODE_ALLOWED=0 convention)
     */
    private fun getAutostartStateFromProvider(context: Context): AutostartState {
        return try {
            val uri = Uri.parse("content://com.miui.securitycenter.provider/autostart")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val pkg = cursor.getString(cursor.getColumnIndexOrThrow("package_name"))
                    if (pkg == context.packageName) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow("status"))
                        // In MIUI's content provider: status=1 means autostart ENABLED
                        Log.d(TAG, "Autostart content-provider status=$status for $pkg")
                        return@use if (status == 1) AutostartState.ENABLED else AutostartState.DISABLED
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
            // BUG FIX: Pass as array to avoid the shell treating "getprop key" as a single
            // command name on Android 10+ (where Runtime.exec(String) splits on spaces
            // inconsistently across OEM kernels). Using array form is always safe.
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getSystemProperty($key) failed: ${e.message}")
            null
        }
    }
}
