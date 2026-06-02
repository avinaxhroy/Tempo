package me.avinas.tempo.desktop

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopPairingCallbackClient @Inject constructor(
    private val pairingManager: DesktopPairingManager
) {
    companion object {
        private const val TAG = "DesktopPairingCallback"
        private const val HMAC_SHA256 = "HmacSHA256"
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun confirmDesktopPairing(
        qrData: DesktopQrData,
        phoneIp: String,
        phonePort: Int,
        authToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        val desktopIp = qrData.ip ?: return@withContext false
        val desktopPort = qrData.port ?: return@withContext false
        val desktopAddress = resolveLocalIpv4(desktopIp) ?: return@withContext false

        val body = JSONObject().apply {
            put("phone_ip", phoneIp)
            put("phone_port", phonePort)
            put("device_name", Build.MODEL)
            if (qrData.version >= 3) {
                pairingManager.getPhonePublicKey()?.let { put("phone_pub_key", it) }
            }
        }.toString()

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val signedMessage = body + "\n" + timestamp + "\n" + nonce
        val signature = computeHmac(authToken, signedMessage)

        runCatching {
            postPairingConfirmation(desktopAddress, desktopPort, authToken, body, signature, timestamp, nonce)
        }.onFailure {
            Log.w(TAG, "Direct desktop pairing callback failed", it)
        }.getOrDefault(false)
    }

    private fun computeHmac(key: String, data: String): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun postPairingConfirmation(
        address: InetAddress,
        port: Int,
        authToken: String,
        body: String,
        signature: String,
        timestamp: String,
        nonce: String
    ): Boolean {
        val url = "http://${address.hostAddress}:$port/api/pair/confirm"

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $authToken")
            .header("X-Tempo-Signature", signature)
            .header("X-Tempo-Timestamp", timestamp)
            .header("X-Tempo-Nonce", nonce)
            .build()

        val response = httpClient.newCall(request).execute()
        return response.isSuccessful
    }

    private fun resolveLocalIpv4(ip: String): Inet4Address? {
        if (!IPV4_LITERAL.matches(ip)) return null
        val address = InetAddress.getByName(ip) as? Inet4Address ?: return null
        return if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            address
        } else {
            Log.w(TAG, "Rejecting desktop pairing callback to non-local IP")
            null
        }
    }
}
