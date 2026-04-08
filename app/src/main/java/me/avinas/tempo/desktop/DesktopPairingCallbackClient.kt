package me.avinas.tempo.desktop

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopPairingCallbackClient @Inject constructor() {
    companion object {
        private const val TAG = "DesktopPairingCallback"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }

    suspend fun confirmDesktopPairing(
        qrData: DesktopQrData,
        phoneIp: String,
        phonePort: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val desktopIp = qrData.ip ?: return@withContext false
        val desktopPort = qrData.port ?: return@withContext false
        val desktopAddress = resolveLocalIpv4(desktopIp) ?: return@withContext false

        val body = JSONObject()
            .put("token", qrData.token)
            .put("phone_ip", phoneIp)
            .put("phone_port", phonePort)
            .put("device_name", Build.MODEL)
            .toString()

        runCatching {
            postPairingConfirmation(desktopAddress, desktopPort, body)
        }.onFailure {
            Log.w(TAG, "Direct desktop pairing callback failed", it)
        }.getOrDefault(false)
    }

    private fun postPairingConfirmation(address: InetAddress, port: Int, body: String): Boolean {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val requestHead = buildString {
            append("POST /api/pair/confirm HTTP/1.1\r\n")
            append("Host: ${address.hostAddress}:$port\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.write(requestHead)
            output.write(bodyBytes)
            output.flush()

            val statusLine = socket.getInputStream()
                .bufferedReader(Charsets.US_ASCII)
                .readLine()
            return statusLine?.contains(" 200 ") == true
        }
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
