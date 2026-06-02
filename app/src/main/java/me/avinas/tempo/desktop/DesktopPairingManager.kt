package me.avinas.tempo.desktop

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.local.dao.DesktopPairingDao
import me.avinas.tempo.data.local.entities.DesktopPairingSession
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the data extracted from a desktop Tempo QR code.
 *
 * Supported QR formats:
 * ```json
 * {"token":"abc123...","device_name":"MacBook Pro","v":1}       // v1: legacy plaintext token
 * {"ip":"192.168.1.10","port":8765,"token":"abc123...","v":2}  // v2: legacy with IP
 * {"pub_key":"B64_ECDH_P256_KEY","device_name":"MacBook Pro","v":3}  // v3: ECDH key exchange
 * {"pub_key":"B64_ECDH_P256_KEY","ip":"192.168.1.10","port":8765,"device_name":"MacBook Pro","v":3}
 * ```
 */
data class DesktopQrData(
    val token: String? = null,
    val pubKey: String? = null,
    val ip: String? = null,
    val port: Int? = null,
    val deviceName: String? = null,
    val version: Int = 1
)

@Singleton
class DesktopPairingManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val desktopPairingDao: DesktopPairingDao
) {
    companion object {
        private const val TAG = "DesktopPairingManager"
        const val SERVER_PORT = 8765
        private const val MIN_TOKEN_LENGTH = 16
        private const val MAX_TOKEN_LENGTH = 128
        private const val MAX_BACKWARD_ROTATION_STEPS = 5
    }

    private var phoneKeyPair: KeyPair? = null

    fun parseDesktopQrPayload(json: String): DesktopQrData? {
        return try {
            val obj = JSONObject(json)
            val version = obj.optInt("v", 1)

            when (version) {
                3 -> {
                    val pubKey = obj.optString("pub_key").trim().ifBlank { null }
                    val ip = obj.optString("ip").trim().ifBlank { null }
                    val port = obj.optInt("port", 0).takeIf { it > 0 }
                    val deviceName = obj.optString("device_name").trim().ifBlank { null }
                    if (pubKey == null) {
                        Log.w(TAG, "v3 QR missing pub_key")
                        return null
                    }
                    DesktopQrData(
                        pubKey = pubKey,
                        ip = ip,
                        port = port,
                        deviceName = deviceName,
                        version = 3
                    )
                }
                else -> {
                    Log.w(TAG, "v${version} QR pairing is deprecated — upgrade desktop app to use v3 (ECDH)")
                    val token = obj.optString("token").trim().ifBlank { null }
                    val ip = obj.optString("ip").trim().ifBlank { null }
                    val port = obj.optInt("port", 0).takeIf { it > 0 }
                    val deviceName = obj.optString("device_name").trim().ifBlank { null }
                    if (token == null || token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) return null
                    DesktopQrData(
                        token = token,
                        ip = ip,
                        port = port,
                        deviceName = deviceName,
                        version = version
                    )
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Invalid QR payload: $json", e)
            null
        }
    }

    suspend fun completePairingFromDesktopQr(qrData: DesktopQrData): DesktopPairingSession {
        val authToken: String
        val phonePublicKey: String?
        val desktopPublicKey: String?

        if (qrData.version == 3 && qrData.pubKey != null) {
            val keyPair = EcdhKeyExchange.generateKeyPair()
            val desktopPubKey = EcdhKeyExchange.base64ToPublicKey(qrData.pubKey)
            if (desktopPubKey != null) {
                phoneKeyPair = keyPair
                phonePublicKey = EcdhKeyExchange.publicKeyToBase64(keyPair.public)
                desktopPublicKey = qrData.pubKey

                val sharedSecret = EcdhKeyExchange.deriveSharedSecret(keyPair.private, desktopPubKey)
                authToken = EcdhKeyExchange.deriveAuthToken(sharedSecret)
            } else {
                Log.e(TAG, "Failed to parse desktop ECDH public key — cannot complete v3 pairing")
                throw IllegalStateException("Failed to parse desktop ECDH public key. Try generating a new QR code.")
            }
        } else {
            authToken = qrData.token ?: UUID.randomUUID().toString().replace("-", "")
            phonePublicKey = null
            desktopPublicKey = null
        }

        val encryptedToken = TokenEncryptor.encrypt(authToken)
        val session = DesktopPairingSession(
            id = UUID.randomUUID().toString(),
            authToken = encryptedToken ?: authToken,
            deviceName = qrData.deviceName.orEmpty(),
            desktopIp = qrData.ip,
            desktopPort = qrData.port,
            pairedAtMs = System.currentTimeMillis(),
            isActive = true,
            phonePublicKey = phonePublicKey,
            desktopPublicKey = desktopPublicKey,
            tokenVersion = 0
        )
        desktopPairingDao.deactivateAndInsert(session)

        Log.i(
            TAG,
            if (qrData.ip != null && qrData.port != null) {
                "Paired with desktop at ${qrData.ip}:${qrData.port} (v${qrData.version})"
            } else {
                "Paired with desktop (v${qrData.version})"
            }
        )
        return session
    }

    suspend fun getActiveSession(): DesktopPairingSession? =
        desktopPairingDao.getActiveSession()

    suspend fun validateToken(token: String): DesktopPairingSession? {
        val session = desktopPairingDao.getActiveSession() ?: return null
        val storedToken = TokenEncryptor.decrypt(session.authToken) ?: session.authToken
        if (constantTimeEquals(storedToken, token)) return session
        var candidate = token
        for (step in 1..MAX_BACKWARD_ROTATION_STEPS) {
            candidate = EcdhKeyExchange.rotateAuthToken(candidate)
            if (constantTimeEquals(storedToken, candidate)) return session
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val maxLen = maxOf(a.length, b.length)
        val aBytes = a.toByteArray(Charsets.UTF_8).copyOf(maxLen)
        val bBytes = b.toByteArray(Charsets.UTF_8).copyOf(maxLen)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    suspend fun recordSuccessfulSync(token: String, deviceName: String) {
        val session = desktopPairingDao.getActiveSession() ?: return
        desktopPairingDao.updateLastSeen(session.id, System.currentTimeMillis(), deviceName)
    }

    suspend fun rotateToken(clientToken: String): String? {
        val session = desktopPairingDao.getActiveSession() ?: return null
        val storedToken = TokenEncryptor.decrypt(session.authToken) ?: session.authToken

        if (constantTimeEquals(storedToken, clientToken)) {
            val newToken = EcdhKeyExchange.rotateAuthToken(clientToken)
            val encryptedNew = TokenEncryptor.encrypt(newToken) ?: return null
            desktopPairingDao.updateToken(session.id, encryptedNew)
            return newToken
        }

        return storedToken
    }

    suspend fun deactivate() {
        desktopPairingDao.deactivateAll()
        phoneKeyPair = null
        Log.i(TAG, "Desktop pairing session deactivated")
    }

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

    fun getPhonePublicKey(): String? {
        return phoneKeyPair?.let { EcdhKeyExchange.publicKeyToBase64(it.public) }
    }

    fun getPhoneKeyPair(): KeyPair? = phoneKeyPair

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