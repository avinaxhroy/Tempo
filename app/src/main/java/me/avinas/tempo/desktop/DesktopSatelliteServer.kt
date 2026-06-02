package me.avinas.tempo.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
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
        private const val TIMESTAMP_HEADER = "x-tempo-timestamp"
        private const val NONCE_HEADER = "x-tempo-nonce"
        private const val ENCRYPTED_HEADER = "x-tempo-encrypted"
        private const val COMPRESSED_HEADER = "x-tempo-compressed"
        private const val MAX_TIMESTAMP_DRIFT_SECONDS = 60L
        private const val NONCE_TTL_SECONDS = 120L
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val MAX_RATE_LIMIT_ENTRIES = 50
        private const val MAX_NONCE_CACHE_SIZE = 500
        private const val PREFS_NAME = "desktop_satellite_prefs"
        private const val KEY_BATTERY_THRESHOLD = "battery_critical_threshold"
        private const val DEFAULT_BATTERY_THRESHOLD = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pairConfirmAttempts = ConcurrentHashMap<String, LongArray>()
    private val playsAttempts = ConcurrentHashMap<String, LongArray>()
    private val nonceCache = ConcurrentHashMap<String, Long>()

    @Volatile
    private var cachedBatteryCritical: Boolean = false
    @Volatile
    private var batteryCacheTime: Long = 0L
    private val BATTERY_CACHE_TTL_MS = 15_000L

    @Volatile
    private var totalPlaysReceived: Long = 0L
    @Volatile
    private var lastSyncTimestamp: Long = 0L
    @Volatile
    private var syncSuccessCount: Long = 0L
    @Volatile
    private var syncFailureCount: Long = 0L

    private fun getBatteryThreshold(): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BATTERY_THRESHOLD, DEFAULT_BATTERY_THRESHOLD)
    }

    fun setBatteryThreshold(threshold: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BATTERY_THRESHOLD, threshold.coerceIn(5, 50))
            .apply()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100) / scale
            val wasCritical = cachedBatteryCritical
            val isCritical = pct <= getBatteryThreshold()
            cachedBatteryCritical = isCritical
            batteryCacheTime = System.currentTimeMillis()
            if (isCritical && !wasCritical && isRunning) {
                Log.i(TAG, "Battery critical (broadcast) — stopping server")
                mdnsManager.unregister()
                stop()
            } else if (!isCritical && wasCritical && !isRunning && kotlinx.coroutines.runBlocking { pairingManager.getActiveSession() } != null) {
                Log.i(TAG, "Battery recovered (broadcast) — restarting server")
                start(DesktopPairingManager.SERVER_PORT)
                mdnsManager.register(DesktopPairingManager.SERVER_PORT)
            }
        }
    }

    private fun isBatteryCriticalCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - batteryCacheTime < BATTERY_CACHE_TTL_MS) return cachedBatteryCritical
        val result = BatteryUtils.isCriticalBattery(context, forceRefresh = true)
        cachedBatteryCritical = result
        batteryCacheTime = now
        return result
    }

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
            try {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (_: Exception) { /* already registered */ }
            Log.i(TAG, "Desktop satellite server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start satellite server on port $port", e)
        }
    }

    fun stop() {
        httpd?.stop()
        httpd = null
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) { /* not registered */ }
        Log.i(TAG, "Desktop satellite server stopped")
    }

    private fun cleanupRateLimitMaps() {
        val cutoff = System.currentTimeMillis() - 120_000L
        for (map in listOf(pairConfirmAttempts, playsAttempts)) {
            val staleKeys = map.entries
                .filter { entry -> entry.value.all { it < cutoff } }
                .map { it.key }
            staleKeys.forEach { map.remove(it) }
        }
        val nonceCutoff = System.currentTimeMillis() / 1000 - NONCE_TTL_SECONDS
        val staleNonces = nonceCache.entries
            .filter { it.value < nonceCutoff }
            .map { it.key }
        staleNonces.forEach { nonceCache.remove(it) }
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun startBatteryWatchdog() {
        scope.launch {
            while (true) {
                try {
                    val isCritical = BatteryUtils.isCriticalBattery(context, forceRefresh = true)
                    cachedBatteryCritical = isCritical
                    batteryCacheTime = System.currentTimeMillis()
                    if (isCritical && isRunning) {
                        Log.i(TAG, "Battery critical — stopping server to save power")
                        mdnsManager.unregister()
                        stop()
                    } else if (!isCritical && !isRunning && pairingManager.getActiveSession() != null) {
                        Log.i(TAG, "Battery recovered — restarting server")
                        start(DesktopPairingManager.SERVER_PORT)
                        mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                    }
                    cleanupRateLimitMaps()
                } catch (e: Exception) {
                    Log.w(TAG, "Battery watchdog error", e)
                }
                val pollInterval = if (isCharging()) 60_000L else 15_000L
                delay(pollInterval)
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
                    return corsPreflightResponse(session)
                }

                val response = when {
                    session.method == Method.GET && session.uri == "/api/ping" -> handlePing(session)
                    session.method == Method.GET && session.uri == "/api/battery" -> handleBattery(session)
                    session.method == Method.GET && session.uri == "/api/sync-stats" -> handleSyncStats(session)
                    session.method == Method.POST && session.uri == "/api/pair/confirm" -> handlePairConfirm(session)
                    session.method == Method.POST && session.uri == "/api/plays" -> handlePlays(session)
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"error":"not_found"}"""
                    )
                }
                addSecurityHeaders(response, session)
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error serving ${session.uri}", e)
                val resp = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":"internal_error"}"""
                )
                addSecurityHeaders(resp, session)
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
        private fun corsPreflightResponse(session: IHTTPSession): Response {
            val origin = session.headers["origin"].orEmpty()
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_JSON, "")
            if (isAllowedOrigin(origin)) {
                resp.addHeader("Access-Control-Allow-Origin", origin)
            }
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            resp.addHeader(
                "Access-Control-Allow-Headers",
                "Authorization, Content-Type, X-Tempo-Signature, X-Tempo-Timestamp, X-Tempo-Nonce, X-Tempo-Encrypted, X-Tempo-Compressed"
            )
            resp.addHeader("Access-Control-Max-Age", "86400")
            resp.addHeader("Cache-Control", "no-store")
            return resp
        }

        private fun isAllowedOrigin(origin: String): Boolean {
            return origin.startsWith("chrome-extension://") ||
                origin.startsWith("moz-extension://")
        }

        private fun addSecurityHeaders(response: Response, session: IHTTPSession? = null): Response {
            response.addHeader("X-Content-Type-Options", "nosniff")
            response.addHeader("X-Frame-Options", "DENY")
            response.addHeader("Cache-Control", "no-store")
            val origin = session?.headers?.get("origin").orEmpty()
            if (isAllowedOrigin(origin)) {
                response.addHeader("Access-Control-Allow-Origin", origin)
            }
            return response
        }

        private fun authenticateRequest(session: IHTTPSession, bodyBytes: ByteArray): String? {
            val headerToken = extractBearerToken(session) ?: return null
            if (!isValidToken(headerToken)) return null

            val signature = session.headers[SIGNATURE_HEADER].orEmpty()
            if (signature.isBlank()) return null

            val timestamp = session.headers[TIMESTAMP_HEADER].orEmpty()
            val nonce = session.headers[NONCE_HEADER].orEmpty()

            val signedMessage = if (timestamp.isNotBlank() && nonce.isNotBlank()) {
                if (!validateTimestamp(timestamp)) return null
                if (!validateNonce(nonce)) return null
                String(bodyBytes, Charsets.UTF_8) + "\n" + timestamp + "\n" + nonce
            } else {
                String(bodyBytes, Charsets.UTF_8)
            }

            if (!isValidSignature(headerToken, signedMessage.toByteArray(Charsets.UTF_8), signature)) return null
            return headerToken
        }

        private fun handlePing(session: IHTTPSession): Response {
            val token = authenticateRequest(session, "{}".toByteArray(Charsets.UTF_8))
            val activeSession = pairingManager.getActiveSessionBlocking()
            if (activeSession != null) {
                if (token == null) return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")
                pairingManager.validateTokenBlocking(token)
                    ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }
            if (isBatteryCriticalCached()) {
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }
            val deviceName = Build.MODEL
            val response = newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"ok":true,"device_name":${JSONObject.quote(deviceName)}}"""
            )
            if (session.headers[ENCRYPTED_HEADER] == "1" && token != null) {
                encryptResponse(response, token)
            }
            return response
        }

        private fun handleBattery(session: IHTTPSession): Response {
            val token = authenticateRequest(session, "{}".toByteArray(Charsets.UTF_8))
            val activeSession = pairingManager.getActiveSessionBlocking()
            if (activeSession != null) {
                if (token == null) return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")
                pairingManager.validateTokenBlocking(token)
                    ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }
            val batteryLevel = BatteryUtils.getBatteryLevel(context)
            val isCritical = BatteryUtils.isCriticalBattery(context)
            val isLow = BatteryUtils.isLowBattery(context)
            
            val responseObj = JSONObject().apply {
                put("level", batteryLevel)
                put("critical", isCritical)
                put("low", isLow)
            }
            
            val response = newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseObj.toString())
            if (session.headers[ENCRYPTED_HEADER] == "1" && token != null) {
                encryptResponse(response, token)
            }
            return response
        }

        private fun handleSyncStats(session: IHTTPSession): Response {
            val token = authenticateRequest(session, "{}".toByteArray(Charsets.UTF_8))
            val activeSession = pairingManager.getActiveSessionBlocking()
            if (activeSession != null) {
                if (token == null) return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")
                pairingManager.validateTokenBlocking(token)
                    ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")
            }
            val responseObj = JSONObject().apply {
                put("total_plays_received", totalPlaysReceived)
                put("last_sync_timestamp", lastSyncTimestamp)
                put("sync_success_count", syncSuccessCount)
                put("sync_failure_count", syncFailureCount)
                put("server_running", isRunning)
                put("battery_threshold", getBatteryThreshold())
            }
            val response = newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseObj.toString())
            if (session.headers[ENCRYPTED_HEADER] == "1" && token != null) {
                encryptResponse(response, token)
            }
            return response
        }

        private fun handlePairConfirm(session: IHTTPSession): Response {
            if (isBatteryCriticalCached()) {
                Log.w(TAG, "Rejecting pair confirmation: battery level is critical (<= 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            val clientIp = session.remoteIpAddress ?: "unknown"
            if (isRateLimited(clientIp, pairConfirmAttempts, MAX_PAIR_CONFIRM_PER_MINUTE)) {
                Log.w(TAG, "Rate limiting pair confirmation from $clientIp")
                return errorResponse(Response.Status.TOO_MANY_REQUESTS, "rate_limited")
            }

            val rawBytes = try {
                readRawBody(session)
            } catch (e: IllegalStateException) {
                if (e.message == "payload_too_large") {
                    val resp = newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, MIME_JSON,
                        """{"ok":false,"error":"payload_too_large"}"""
                    )
                    resp.status = object : Response.IStatus {
                        override fun getRequestStatus() = "413 Payload Too Large"
                        override fun getDescription() = "Payload Too Large"
                    }
                    return resp
                }
                Log.w(TAG, "Failed to read pair-confirm body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read pair-confirm body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }
            if (rawBytes.isEmpty()) {
                return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
            }

            val isEncrypted = session.headers[ENCRYPTED_HEADER] == "1"
            val isCompressed = session.headers[COMPRESSED_HEADER] == "1"
            val token = authenticateRequest(session, rawBytes)
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")

            val decryptedBytes = if (isEncrypted) {
                try {
                    if (isCompressed) decryptBodyCompressed(rawBytes, token) else decryptBody(rawBytes, token)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decrypt pair-confirm body", e)
                    return errorResponse(Response.Status.BAD_REQUEST, "decryption_failed")
                }
            } else {
                rawBytes
            }

            val body = String(decryptedBytes, Charsets.UTF_8)
            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            pairingManager.validateTokenBlocking(token)
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")

            val deviceName = Build.MODEL
            val responseBody = """{"ok":true,"device_name":${JSONObject.quote(deviceName)}}"""
            val response = newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseBody)
            if (isEncrypted) encryptResponse(response, token)
            return response
        }

        private fun handlePlays(session: IHTTPSession): Response {
            val clientIp = session.remoteIpAddress ?: "unknown"

            if (isBatteryCriticalCached()) {
                Log.w(TAG, "Rejecting play sync: battery level is critical (≤ 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            if (isRateLimited(clientIp, playsAttempts, MAX_PLAYS_PER_MINUTE)) {
                Log.w(TAG, "Rate limiting plays from $clientIp")
                return errorResponse(Response.Status.TOO_MANY_REQUESTS, "rate_limited")
            }

            val rawBytes = try {
                readRawBody(session)
            } catch (e: IllegalStateException) {
                if (e.message == "payload_too_large") {
                    val resp = newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, MIME_JSON,
                        """{"ok":false,"error":"payload_too_large"}"""
                    )
                    resp.status = object : Response.IStatus {
                        override fun getRequestStatus() = "413 Payload Too Large"
                        override fun getDescription() = "Payload Too Large"
                    }
                    return resp
                }
                Log.w(TAG, "Failed to read play batch body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read play batch body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }
            if (rawBytes.isEmpty()) {
                return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
            }

            val isEncrypted = session.headers[ENCRYPTED_HEADER] == "1"
            val isCompressed = session.headers[COMPRESSED_HEADER] == "1"
            val token = authenticateRequest(session, rawBytes)
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized")

            val decryptedBytes = if (isEncrypted) {
                try {
                    if (isCompressed) decryptBodyCompressed(rawBytes, token) else decryptBody(rawBytes, token)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decrypt plays body", e)
                    return errorResponse(Response.Status.BAD_REQUEST, "decryption_failed")
                }
            } else {
                rawBytes
            }

            val body = String(decryptedBytes, Charsets.UTF_8)
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            val playsArray = try {
                json.getJSONArray("plays")
            } catch (e: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "missing_plays_array")
            }
            if (playsArray.length() > MAX_PLAYS_ARRAY_LENGTH) {
                return errorResponse(Response.Status.BAD_REQUEST, "too_many_plays")
            }

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

            val deviceName = json.optString("device_name", "")
            if (deviceName.length > MAX_FIELD_LENGTH) {
                return errorResponse(Response.Status.BAD_REQUEST, "field_too_long")
            }

            data class IngestOutcome(val status: Response.Status, val body: String)

            val outcome = try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                        val result = ingestionService.ingest(token, json)
                        when (result) {
                            is IngestionResult.Success -> {
                                val nextTokenPart = result.nextToken?.let { """"next_token":${JSONObject.quote(it)},""" } ?: ""
                                if (result.nextToken != null) {
                                    try {
                                        mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                                    } catch (_: Exception) { /* non-critical */ }
                                }
                                totalPlaysReceived += result.accepted
                                lastSyncTimestamp = System.currentTimeMillis()
                                syncSuccessCount++
                                IngestOutcome(Response.Status.OK, """{"ok":true,${nextTokenPart}"accepted":${result.accepted},"duplicates":${result.duplicates}}""")
                            }
                            is IngestionResult.InvalidToken ->
                                IngestOutcome(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"invalid_token"}""")
                            is IngestionResult.Error -> {
                                val escaped = JSONObject.quote(result.message)
                                syncFailureCount++
                                IngestOutcome(Response.Status.BAD_REQUEST, """{"ok":false,"error":$escaped}""")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ingest coroutine failed", e)
                null
            }

            if (outcome == null) {
                Log.e(TAG, "Ingest coroutine timed out or failed — returning 500 to caller")
            }
            val response = newFixedLengthResponse(
                outcome?.status ?: Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                outcome?.body ?: """{"ok":false,"error":"processing_timeout"}"""
            )
            if (isEncrypted) encryptResponse(response, token)
            return response
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
            if (attemptsMap.size > MAX_RATE_LIMIT_ENTRIES) {
                evictStaleRateLimitEntries(attemptsMap)
            }
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

        private fun evictStaleRateLimitEntries(map: ConcurrentHashMap<String, LongArray>) {
            val cutoff = System.currentTimeMillis() - 120_000L
            val staleKeys = map.entries
                .filter { entry -> entry.value.all { it < cutoff } }
                .map { it.key }
            staleKeys.forEach { map.remove(it) }
            if (map.size > MAX_RATE_LIMIT_ENTRIES) {
                val excess = map.size - MAX_RATE_LIMIT_ENTRIES
                map.keys.take(excess).forEach { map.remove(it) }
            }
        }

        private fun DesktopPairingManager.validateTokenBlocking(token: String) = kotlinx.coroutines.runBlocking {
            validateToken(token)
        }

        private fun DesktopPairingManager.getActiveSessionBlocking() = kotlinx.coroutines.runBlocking {
            getActiveSession()
        }

        /**
         * Read raw body bytes directly from the session input stream.
         *
         * Avoids NanoHTTPD's parseBody() which may decode bytes using ISO-8859-1
         * when Content-Type lacks an explicit charset. Re-encoding such a garbled
         * String to UTF-8 produces different bytes than the original wire bytes,
         * breaking HMAC verification for any non-ASCII content (e.g. song titles).
         */
        private fun readRawBody(session: IHTTPSession): ByteArray {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) return ByteArray(0)
            if (contentLength > MAX_BODY_BYTES) throw IllegalStateException("payload_too_large")

            val bytes = ByteArray(contentLength)
            var offset = 0
            val inputStream = session.inputStream
            while (offset < contentLength) {
                val read = inputStream.read(bytes, offset, contentLength - offset)
                if (read == -1) break
                offset += read
            }
            return bytes.copyOf(offset)
        }

        private fun isValidSignature(token: String, bodyBytes: ByteArray, signature: String): Boolean {
            val isHex = signature.length == 64 && signature.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            val isBase64Url = signature.length in 42..44 && signature.all { it.isLetterOrDigit() || it == '-' || it == '_' }

            if (!isHex && !isBase64Url) return false

            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), HMAC_SHA256))
            val expected = mac.doFinal(bodyBytes)

            return if (isHex) {
                val expectedHex = expected.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                MessageDigest.isEqual(
                    expectedHex.toByteArray(Charsets.US_ASCII),
                    signature.lowercase().toByteArray(Charsets.US_ASCII)
                )
            } else {
                val expectedB64 = Base64.encodeToString(expected, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                MessageDigest.isEqual(
                    expectedB64.toByteArray(Charsets.US_ASCII),
                    signature.toByteArray(Charsets.US_ASCII)
                )
            }
        }

        private fun isValidToken(token: String): Boolean =
            token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH

        private fun validateTimestamp(timestampStr: String): Boolean {
            val timestamp = timestampStr.toLongOrNull() ?: return false
            val now = System.currentTimeMillis() / 1000
            return kotlin.math.abs(now - timestamp) <= MAX_TIMESTAMP_DRIFT_SECONDS
        }

        private fun validateNonce(nonce: String): Boolean {
            if (nonce.isBlank() || nonce.length > 64) return false
            val now = System.currentTimeMillis() / 1000
            if (nonceCache.size > MAX_NONCE_CACHE_SIZE) {
                val cutoff = now - NONCE_TTL_SECONDS
                nonceCache.entries.removeAll { it.value < cutoff }
            }
            val existing = nonceCache.putIfAbsent(nonce, now)
            return existing == null
        }

        private fun deriveEncryptionKey(token: String): ByteArray {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec("tempo-body-encrypt-v1".toByteArray(Charsets.UTF_8), HMAC_SHA256))
            return mac.doFinal(token.toByteArray(Charsets.UTF_8))
        }

        private fun decryptBody(encryptedBytes: ByteArray, token: String): ByteArray {
            val encoded = String(encryptedBytes, Charsets.UTF_8).trim()
            val combined = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if (combined.size < GCM_IV_LENGTH + 1) throw IllegalStateException("ciphertext too short")
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val key = SecretKeySpec(deriveEncryptionKey(token), "AES")
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            return cipher.doFinal(ciphertext)
        }

        private fun encryptBody(plaintext: ByteArray, token: String): ByteArray {
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val key = SecretKeySpec(deriveEncryptionKey(token), "AES")
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            return Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .toByteArray(Charsets.UTF_8)
        }

        private fun gzipDecompress(data: ByteArray): ByteArray {
            val bais = ByteArrayInputStream(data)
            val baos = ByteArrayOutputStream()
            GZIPInputStream(bais).use { gis ->
                val buffer = ByteArray(4096)
                var len: Int
                while (gis.read(buffer).also { len = it } != -1) {
                    baos.write(buffer, 0, len)
                }
            }
            return baos.toByteArray()
        }

        private fun gzipCompress(data: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gos -> gos.write(data) }
            return baos.toByteArray()
        }

        private fun decryptBodyCompressed(encryptedBytes: ByteArray, token: String): ByteArray {
            val decrypted = decryptBody(encryptedBytes, token)
            return gzipDecompress(decrypted)
        }

        private fun encryptResponse(response: Response, token: String) {
            try {
                val originalData = response.data ?: return
                val plaintext = originalData.readBytes()
                val toEncrypt = if (plaintext.size > 1024) {
                    response.addHeader(COMPRESSED_HEADER, "1")
                    gzipCompress(plaintext)
                } else {
                    plaintext
                }
                val encrypted = encryptBody(toEncrypt, token)
                response.addHeader(ENCRYPTED_HEADER, "1")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to encrypt response body", e)
            }
        }
    }
}