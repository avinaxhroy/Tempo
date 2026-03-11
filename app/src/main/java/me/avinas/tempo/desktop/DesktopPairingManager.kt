package me.avinas.tempo.desktop

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.local.dao.DesktopPairingDao
import me.avinas.tempo.data.local.entities.DesktopPairingSession
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the data extracted from a desktop Tempo Satellite QR code.
 *
 * QR format expected from the desktop app:
 * ```json
 * {"ip":"192.168.1.10","port":8765,"token":"abc123...","v":1}
 * ```
 */
data class DesktopQrData(
    val ip: String,
    val port: Int,
    /** Auth token the desktop will use when POSTing scrobbles to the phone's server. */
    val token: String
)

/**
 * Manages the lifecycle of a Desktop Satellite pairing session.
 *
 * Pairing direction: the **desktop app** shows a QR code containing its scrobble
 * endpoint address and auth token. The **phone** (this app) scans it with the camera,
 * stores the credentials, and starts the local NanoHTTPD receiver so the desktop can
 * begin forwarding plays.
 */
@Singleton
class DesktopPairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val desktopPairingDao: DesktopPairingDao
) {

    companion object {
        private const val TAG = "DesktopPairingManager"
        /** Port the phone's NanoHTTPD receiver listens on. */
        const val SERVER_PORT = 8765
    }

    // ---------------------------------------------------------------------------
    // QR parsing (desktop shows QR → phone scans it)
    // ---------------------------------------------------------------------------

    /**
     * Parses the JSON payload from a desktop Tempo Satellite QR code.
     *
     * @return [DesktopQrData] on success, or null if the payload is not a valid
     *         Tempo Desktop QR code (e.g., user accidentally scanned something else).
     */
    fun parseDesktopQrPayload(json: String): DesktopQrData? {
        return try {
            val obj = JSONObject(json)
            val ip = obj.getString("ip")
            val port = obj.getInt("port")
            val token = obj.getString("token")
            if (ip.isBlank() || port <= 0 || token.length < 8) null
            else DesktopQrData(ip = ip, port = port, token = token)
        } catch (e: JSONException) {
            Log.w(TAG, "Invalid QR payload: $json", e)
            null
        }
    }

    /**
     * Creates and persists a new pairing session from a successfully scanned QR code.
     * Deactivates any previously active session beforehand (one active session at a time).
     */
    suspend fun completePairingFromDesktopQr(qrData: DesktopQrData): DesktopPairingSession {
        desktopPairingDao.deactivateAll()
        val session = DesktopPairingSession(
            id = UUID.randomUUID().toString(),
            authToken = qrData.token,
            desktopIp = qrData.ip,
            desktopPort = qrData.port,
            pairedAtMs = System.currentTimeMillis(),
            isActive = true
        )
        desktopPairingDao.insertOrUpdate(session)
        Log.i(TAG, "Paired with desktop at ${qrData.ip}:${qrData.port}")
        return session
    }

    // ---------------------------------------------------------------------------
    // Session management
    // ---------------------------------------------------------------------------

    /** Returns the active pairing session, or null if not yet paired. */
    suspend fun getActiveSession(): DesktopPairingSession? =
        desktopPairingDao.getActiveSession()

    /** Validates an incoming auth token from a desktop scrobble request. */
    suspend fun validateToken(token: String): DesktopPairingSession? =
        desktopPairingDao.findByToken(token)

    /** Updates last-seen metadata after a successful ingestion batch. */
    suspend fun recordSuccessfulSync(token: String, deviceName: String) {
        desktopPairingDao.updateLastSeen(token, System.currentTimeMillis(), deviceName)
    }

    /** Soft-deactivates the current pairing (user taps Disconnect). */
    suspend fun deactivate() {
        desktopPairingDao.deactivateAll()
        Log.i(TAG, "Desktop pairing session deactivated")
    }

    // ---------------------------------------------------------------------------
    // Network utilities — phone IP discovery for the receiver address display
    // ---------------------------------------------------------------------------

    /**
     * Returns the best available local IP address of this phone.
     *
     * Priority: WiFi → physical network interface → "0.0.0.0" fallback.
     */
    fun getLocalIpAddress(): String {
        val wifiIp = getWifiIpAddress()
        if (wifiIp != null) return wifiIp

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate network interfaces", e)
        }

        return "0.0.0.0"
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not get WiFi IP", e)
            null
        }
    }

}
