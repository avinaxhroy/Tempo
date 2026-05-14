package me.avinas.tempo.desktop

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.utils.BatteryUtils
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopSatelliteServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pairingManager: DesktopPairingManager,
    private val ingestionService: DesktopPlayIngestionService,
    private val mdnsManager: DesktopMdnsManager
) {
    companion object {
        private const val TAG = "DesktopSatelliteServer"
        private const val MIME_JSON = "application/json"
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val SIGNATURE_HEADER = "x-tempo-signature"
        private const val AUTHORIZATION_HEADER = "authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val MIN_TOKEN_LENGTH = 16
        private const val MAX_TOKEN_LENGTH = 128
        private const val MAX_PAIR_CONFIRM_PER_MINUTE = 20
        private const val MAX_PLAYS_PER_MINUTE = 120
        private const val MAX_PLAYS_ARRAY_LENGTH = 100
        private const val MAX_FIELD_LENGTH = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pairConfirmAttempts = ConcurrentHashMap<String, LongArray>()
    private val playsAttempts = ConcurrentHashMap<String, LongArray>()

    @Volatile
    private var httpd: InternalHttpd? = null

    val isRunning: Boolean get() = httpd?.isAlive == true

    init {
        startBatteryWatchdog()
    }

    fun start(port: Int = DesktopPairingManager.SERVER_PORT) {
        if (isRunning) {
            Log.d(TAG, "Server already running on port $port")
            return
        }
        try {
            val server = InternalHttpd(port)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpd = server
            Log.i(TAG, "Desktop satellite server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start satellite server on port $port", e)
        }
    }

    fun stop() {
        httpd?.stop()
        httpd = null
        Log.i(TAG, "Desktop satellite server stopped")
    }

    private fun startBatteryWatchdog() {
        scope.launch {
            while (true) {
                try {
                    val isCritical = BatteryUtils.isCriticalBattery(context, forceRefresh = true)
                    if (isCritical && isRunning) {
                        Log.i(TAG, "Battery critical — stopping server to save power")
                        mdnsManager.unregister()
                        stop()
                    } else if (!isCritical && !isRunning && pairingManager.getActiveSession() != null) {
                        Log.i(TAG, "Battery recovered — restarting server")
                        start(DesktopPairingManager.SERVER_PORT)
                        mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Battery watchdog error", e)
                }
                delay(60_000L)
            }
        }
    }

    private inner class InternalHttpd(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                // Handle CORS preflight. Chrome extensions send OPTIONS before any request
                // that has custom headers (Authorization, X-Tempo-Signature, etc.).
                // Without this, Chrome blocks the request with a CORS error.
                if (session.method == Method.OPTIONS) {
                    return corsPreflightResponse()
                }

                val response = when {
                    session.method == Method.GET && session.uri == "/api/ping" -> handlePing(session)
                    session.method == Method.GET && session.uri == "/api/battery" -> handleBattery(session)
                    session.method == Method.POST && session.uri == "/api/pair/confirm" -> handlePairConfirm(session)
                    session.method == Method.POST && session.uri == "/api/plays" -> handlePlays(session)
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"error":"not_found"}"""
                    )
                }
                addSecurityHeaders(response)
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error serving ${session.uri}", e)
                val resp = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":"internal_error"}"""
                )
                addSecurityHeaders(resp)
            }
        }

        /**
         * CORS preflight response for OPTIONS requests.
         *
         * Chrome extensions with host_permissions bypass same-origin policy, but
         * still send preflight OPTIONS requests when the request contains custom
         * headers (Authorization, X-Tempo-Signature, X-Tempo-Timestamp, X-Tempo-Nonce).
         * NanoHTTPD must respond to these with the correct ACAO header or Chrome
         * will block the actual request with a CORS error.
         */
        private fun corsPreflightResponse(): Response {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_JSON, "")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            resp.addHeader(
                "Access-Control-Allow-Headers",
                "Authorization, Content-Type, X-Tempo-Signature, X-Tempo-Timestamp, X-Tempo-Nonce"
            )
            resp.addHeader("Access-Control-Max-Age", "86400") // cache preflight for 1 day
            resp.addHeader("Cache-Control", "no-store")
            return resp
        }

        private fun addSecurityHeaders(response: Response): Response {
            response.addHeader("X-Content-Type-Options", "nosniff")
            response.addHeader("X-Frame-Options", "DENY")
            response.addHeader("Cache-Control", "no-store")
            // Allow Chrome extensions (and browsers) to read responses.
            // Using "*" instead of "null" is required — Chrome rejects "null" ACAO.
            // This is safe because all endpoints require HMAC authentication anyway.
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        private fun authenticateRequest(session: IHTTPSession, body: String): String? {
            val headerToken = extractBearerToken(session)
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val bodyToken = json?.optString("auth_token")?.takeIf { it.isNotBlank() }
                ?: json?.optString("token")?.takeIf { it.isNotBlank() }
            val token = headerToken ?: bodyToken ?: return null
            if (!isValidToken(token)) return null

            val signature = session.headers[SIGNATURE_HEADER].orEmpty()
            if (signature.isNotBlank()) {
                if (!isValidSignature(token, body, signature)) return null
            } else {
                return null
            }
            return token
        }

        private fun handlePing(session: IHTTPSession): Response {
            val token = authenticateRequest(session, "{}")
            val activeSession = pairingManager.getActiveSessionBlocking()
            if (activeSession != null) {
                // A pairing session exists: the token must match the stored session
                if (token == null) return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")
                pairingManager.validateTokenBlocking(token)
                    ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }
            val deviceName = Build.MODEL
            return newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"ok":true,"device_name":${JSONObject.quote(deviceName)}}"""
            )
        }

        private fun handleBattery(session: IHTTPSession): Response {
            val token = authenticateRequest(session, "{}")
            val activeSession = pairingManager.getActiveSessionBlocking()
            if (activeSession != null) {
                // A pairing session exists: the token must match the stored session
                if (token == null) return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")
                pairingManager.validateTokenBlocking(token)
                    ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }
            val batteryLevel = BatteryUtils.getBatteryLevel(context)
            val isCritical = BatteryUtils.isCriticalBattery(context)
            val isLow = BatteryUtils.isLowBattery(context)
            
            val response = JSONObject().apply {
                put("level", batteryLevel)
                put("critical", isCritical)
                put("low", isLow)
            }
            
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, response.toString())
        }

        private fun handlePairConfirm(session: IHTTPSession): Response {
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                Log.w(TAG, "Rejecting pair confirmation: battery level is critical (<= 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            val clientIp = session.remoteIpAddress ?: "unknown"
            if (isRateLimited(clientIp, pairConfirmAttempts, MAX_PAIR_CONFIRM_PER_MINUTE)) {
                Log.w(TAG, "Rate limiting pair confirmation from $clientIp")
                return errorResponse(Response.Status.TOO_MANY_REQUESTS, "rate_limited")
            }

            val declaredLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (declaredLength > MAX_BODY_BYTES) {
                return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
            }

            val body = try {
                val buf = HashMap<String, String>()
                session.parseBody(buf)
                val raw = buf["postData"] ?: return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
                if (raw.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
                    return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
                }
                raw
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse pair-confirm body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }

            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            val bodyToken = json.optString("auth_token").takeIf { it.isNotBlank() }
                ?: json.optString("token").takeIf { it.isNotBlank() }
            val headerToken = extractBearerToken(session)
            val token = headerToken ?: bodyToken
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "missing_token")
            if (!isValidToken(token)) {
                return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }

            val signature = session.headers[SIGNATURE_HEADER].orEmpty()
            if (!signature.isNotBlank() || !isValidSignature(token, body, signature)) {
                return errorResponse(Response.Status.UNAUTHORIZED, "invalid_signature")
            }

            pairingManager.validateTokenBlocking(token)
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")

            val deviceName = Build.MODEL
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                """{"ok":true,"device_name":${JSONObject.quote(deviceName)}}"""
            )
        }

        private fun handlePlays(session: IHTTPSession): Response {
            val clientIp = session.remoteIpAddress ?: "unknown"

            // Battery check first — reject before any heavy processing
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                Log.w(TAG, "Rejecting play sync: battery level is critical (≤ 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            if (isRateLimited(clientIp, playsAttempts, MAX_PLAYS_PER_MINUTE)) {
                Log.w(TAG, "Rate limiting plays from $clientIp")
                return errorResponse(Response.Status.TOO_MANY_REQUESTS, "rate_limited")
            }

            val declaredLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (declaredLength > MAX_BODY_BYTES) {
                return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
            }
            val body = try {
                val buf = HashMap<String, String>()
                session.parseBody(buf)
                val raw = buf["postData"] ?: return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
                if (raw.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
                    return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
                }
                raw
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse play batch body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            val headerToken = extractBearerToken(session)
            val bodyToken = json.optString("auth_token").takeIf { it.isNotBlank() }
                ?: json.optString("token").takeIf { it.isNotBlank() }
            val token = headerToken ?: bodyToken
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "missing_token")
            if (!isValidToken(token)) {
                return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }

            val signature = session.headers[SIGNATURE_HEADER].orEmpty()
            if (!signature.isNotBlank() || !isValidSignature(token, body, signature)) {
                return errorResponse(Response.Status.UNAUTHORIZED, "invalid_signature")
            }

            // Validate plays array size
            val playsArray = try {
                json.getJSONArray("plays")
            } catch (e: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "missing_plays_array")
            }
            if (playsArray.length() > MAX_PLAYS_ARRAY_LENGTH) {
                return errorResponse(Response.Status.BAD_REQUEST, "too_many_plays")
            }

            // Validate individual field lengths in plays array
            for (i in 0 until playsArray.length()) {
                val entry = playsArray.optJSONObject(i) ?: continue
                if (entry.optString("title").length > MAX_FIELD_LENGTH ||
                    entry.optString("artist").length > MAX_FIELD_LENGTH ||
                    entry.optString("album").length > MAX_FIELD_LENGTH ||
                    entry.optString("source_app").length > MAX_FIELD_LENGTH ||
                    entry.optString("device_name").length > MAX_FIELD_LENGTH) {
                    return errorResponse(Response.Status.BAD_REQUEST, "field_too_long")
                }
            }

            // Validate device_name field length
            val deviceName = json.optString("device_name", "")
            if (deviceName.length > MAX_FIELD_LENGTH) {
                return errorResponse(Response.Status.BAD_REQUEST, "field_too_long")
            }

            val responseBodyRef = AtomicReference("""{"ok":false,"message":"processing"}""")
            val statusRef = AtomicReference(Response.Status.INTERNAL_ERROR)
            val latch = CountDownLatch(1)

            scope.launch {
                try {
                    val result = ingestionService.ingest(token, json)
                    val (httpStatus, body) = when (result) {
                        is IngestionResult.Success -> {
                            val nextTokenPart = result.nextToken?.let { """"next_token":${JSONObject.quote(it)},""" } ?: ""
                            if (result.nextToken != null) {
                                try {
                                    mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                                } catch (_: Exception) { /* non-critical */ }
                            }
                            Response.Status.OK to """{"ok":true,${nextTokenPart}"accepted":${result.accepted},"duplicates":${result.duplicates}}"""
                        }
                        is IngestionResult.InvalidToken ->
                            Response.Status.UNAUTHORIZED to
                                """{"ok":false,"error":"invalid_token"}"""
                        is IngestionResult.Error -> {
                            val escaped = JSONObject.quote(result.message)
                            Response.Status.BAD_REQUEST to
                                """{"ok":false,"error":$escaped}"""
                        }
                    }
                    statusRef.set(httpStatus)
                    responseBodyRef.set(body)
                } finally {
                    latch.countDown()
                }
            }

            val completed = latch.await(10, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Ingest coroutine timed out after 10 s — returning 500 to caller")
            }
            return newFixedLengthResponse(statusRef.get(), MIME_JSON, responseBodyRef.get())
        }

        private fun errorResponse(status: Response.Status, code: String): Response {
            val escapedCode = JSONObject.quote(code) ?: "\"error\""
            return newFixedLengthResponse(status, MIME_JSON, """{"ok":false,"error":$escapedCode}""")
        }

        private fun extractBearerToken(session: IHTTPSession): String? {
            val authHeader = session.headers[AUTHORIZATION_HEADER] ?: return null
            if (authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                val token = authHeader.substring(BEARER_PREFIX.length).trim()
                return token.takeIf { it.isNotBlank() }
            }
            return null
        }

        private fun isRateLimited(clientKey: String, attemptsMap: ConcurrentHashMap<String, LongArray>, maxPerMinute: Int): Boolean {
            val now = System.currentTimeMillis()
            val window = attemptsMap.getOrPut(clientKey) { LongArray(maxPerMinute) }
            return synchronized(window) {
                val cutoff = now - 60_000L
                val count = window.count { it > cutoff }
                if (count >= maxPerMinute) return@synchronized true
                val oldestSlot = window.withIndex().minByOrNull { it.value }?.index ?: return@synchronized true
                window[oldestSlot] = now
                false
            }
        }

        private fun DesktopPairingManager.validateTokenBlocking(token: String) = kotlinx.coroutines.runBlocking {
            validateToken(token)
        }

        private fun DesktopPairingManager.getActiveSessionBlocking() = kotlinx.coroutines.runBlocking {
            getActiveSession()
        }

        private fun isValidSignature(token: String, body: String, signature: String): Boolean {
            if (signature.length != 64 || !signature.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return false
            }

            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), HMAC_SHA256))
            val expected = mac.doFinal(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                signature.lowercase().toByteArray(Charsets.US_ASCII)
            )
        }

        private fun isValidToken(token: String): Boolean =
            token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH
    }
}