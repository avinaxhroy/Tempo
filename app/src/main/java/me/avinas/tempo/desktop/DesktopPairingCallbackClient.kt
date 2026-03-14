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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopPairingCallbackClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DesktopPairingCallback"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun confirmDesktopPairing(
        qrData: DesktopQrData,
        phoneIp: String,
        phonePort: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val desktopIp = qrData.ip ?: return@withContext false
        val desktopPort = qrData.port ?: return@withContext false

        val body = JSONObject()
            .put("token", qrData.token)
            .put("phone_ip", phoneIp)
            .put("phone_port", phonePort)
            .put("device_name", Build.MODEL)
            .toString()

        val request = Request.Builder()
            .url("http://$desktopIp:$desktopPort/api/pair/confirm")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.onFailure {
            Log.w(TAG, "Direct desktop pairing callback failed", it)
        }.getOrDefault(false)
    }
}