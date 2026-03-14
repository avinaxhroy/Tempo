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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A lightweight NanoHTTPD-based HTTP server that runs on the Android phone.
 *
 * Exposed endpoints:
 * - `GET  /api/ping`             — liveness check; no auth required; returns `{"ok":true}`
 * - `GET  /api/battery`          — battery status; no auth required; returns `{"level":XX,"critical":false}`
 * - `POST /api/plays`         — receives a batch of desktop plays; requires valid Bearer token
 *
 * The token is validated against [DesktopPairingManager] on every POST. All actual
 * business logic (deduplication, DB insertion) is delegated to [DesktopPlayIngestionService].
 *
 * Battery Optimization:
 * - Play sync requests are rejected if battery level is ≤ 20% (critical)
 * - Desktop app should check battery status before attempting sync
 * - Battery level is cached for 2 minutes to minimize system calls
 *
 * Thread model: NanoHTTPD delivers requests on its own background threads. We bridge to
 * coroutines via a dedicated [CoroutineScope] so we can call suspend functions safely.
 */
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
        private const val MAX_BODY_BYTES = 512 * 1024 // 512 KB guard – plenty for any play batch
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var httpd: InternalHttpd? = null

    val isRunning: Boolean get() = httpd?.isAlive == true

    init {
        startBatteryWatchdog()
    }

    // --------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------

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

    // --------------------------------------------------------------------------
    // Battery watchdog — runs for the whole app lifetime (singleton scope)
    // --------------------------------------------------------------------------

    /**
     * Monitors battery every 60 seconds regardless of which screen is open.
     * Stops the server (and unregisters mDNS) when battery drops to critical (≤ 20%),
     * and restarts it when battery recovers — as long as a pairing session exists.
     */
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

    // --------------------------------------------------------------------------
    // Inner NanoHTTPD implementation
    // --------------------------------------------------------------------------

    private inner class InternalHttpd(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.method == Method.GET && session.uri == "/api/ping" -> handlePing()
                    session.method == Method.GET && session.uri == "/api/battery" -> handleBattery()
                    session.method == Method.POST && session.uri == "/api/pair/confirm" -> handlePairConfirm(session)
                    session.method == Method.POST && session.uri == "/api/plays" -> handlePlays(session)
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"error":"not_found"}"""
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error serving ${session.uri}", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":"internal_error"}"""
                )
            }
        }

        // GET /api/ping  -------------------------------------------------------
        private fun handlePing(): Response {
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"ok":true}""")
        }

        // GET /api/battery  -------------------------------------------------------
        private fun handleBattery(): Response {
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

        // POST /api/pair/confirm  ----------------------------------------------
        private fun handlePairConfirm(session: IHTTPSession): Response {
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                Log.w(TAG, "Rejecting pair confirmation: battery level is critical (<= 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            val body = try {
                val buf = HashMap<String, String>()
                session.parseBody(buf)
                buf["postData"] ?: return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse pair-confirm body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }

            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            val token = json.optString("auth_token").takeIf { it.isNotBlank() }
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "missing_token")

            runBlockingPairingLookup(token)
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "invalid_token")

            val deviceName = Build.MODEL
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                """{"ok":true,"device_name":${JSONObject.quote(deviceName)}}"""
            )
        }

        // POST /api/plays  --------------------------------------------------
        private fun handlePlays(session: IHTTPSession): Response {
            // 1. Read body safely
            // Check Content-Length header first as an early fast-reject, but do NOT rely on it
            // alone: a client can omit the header. The actual body is checked after parseBody.
            val declaredLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (declaredLength > MAX_BODY_BYTES) {
                return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
            }
            val body = try {
                val buf = HashMap<String, String>()
                session.parseBody(buf)
                val raw = buf["postData"] ?: return errorResponse(Response.Status.BAD_REQUEST, "empty_body")
                // Guard actual body size regardless of Content-Length header
                if (raw.length > MAX_BODY_BYTES) {
                    return errorResponse(Response.Status.BAD_REQUEST, "payload_too_large")
                }
                raw
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse play batch body", e)
                return errorResponse(Response.Status.BAD_REQUEST, "parse_error")
            }

            // 2. Parse JSON
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_json")
            }

            // 3. Extract and validate token from payload
            val token = json.optString("auth_token").takeIf { it.isNotBlank() }
                ?: return errorResponse(Response.Status.UNAUTHORIZED, "missing_token")

            // 3.5 Battery check: reject plays if battery is critically low (≤ 20%)
            if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
                Log.w(TAG, "Rejecting play sync: battery level is critical (≤ 20%)")
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "battery_critical")
            }

            // 4. Delegate to coroutine-aware ingestion — result is sent back synchronously
            //    via a blocking latch so NanoHTTPD's thread stays alive long enough.
            val responseBodyRef = AtomicReference("""{"ok":false,"message":"processing"}""")
            val statusRef = AtomicReference(Response.Status.INTERNAL_ERROR)
            val latch = CountDownLatch(1)

            scope.launch {
                try {
                    val result = ingestionService.ingest(token, json)
                    val (httpStatus, body) = when (result) {
                        is IngestionResult.Success ->
                            Response.Status.OK to
                                """{"ok":true,"accepted":${result.accepted},"duplicates":${result.duplicates}}"""
                        is IngestionResult.InvalidToken ->
                            Response.Status.UNAUTHORIZED to
                                """{"ok":false,"error":"invalid_token"}"""
                        is IngestionResult.Error -> {
                            // Escape the error message to prevent JSON injection via malformed strings
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

            // Wait up to 10 s for the coroutine to finish (DB insert is fast)
            val completed = latch.await(10, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Ingest coroutine timed out after 10 s — returning 500 to caller")
            }
            return newFixedLengthResponse(statusRef.get(), MIME_JSON, responseBodyRef.get())
        }

        private fun errorResponse(status: Response.Status, code: String): Response =
            newFixedLengthResponse(status, MIME_JSON, """{"ok":false,"error":"$code"}""")

        private fun runBlockingPairingLookup(token: String) = kotlinx.coroutines.runBlocking {
            pairingManager.validateToken(token)
        }
    }
}
