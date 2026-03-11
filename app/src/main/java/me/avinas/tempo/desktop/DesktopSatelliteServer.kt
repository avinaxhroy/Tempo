package me.avinas.tempo.desktop

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * - `GET  /api/ping`      — liveness check; no auth required; returns `{"ok":true}`
 * - `POST /api/scrobble`  — receives a batch of desktop scrobbles; requires valid Bearer token
 *
 * The token is validated against [DesktopPairingManager] on every POST. All actual
 * business logic (deduplication, DB insertion) is delegated to [DesktopScrobbleIngestionService].
 *
 * Thread model: NanoHTTPD delivers requests on its own background threads. We bridge to
 * coroutines via a dedicated [CoroutineScope] so we can call suspend functions safely.
 */
@Singleton
class DesktopSatelliteServer @Inject constructor(
    private val pairingManager: DesktopPairingManager,
    private val ingestionService: DesktopScrobbleIngestionService
) {
    companion object {
        private const val TAG = "DesktopSatelliteServer"
        private const val MIME_JSON = "application/json"
        private const val MAX_BODY_BYTES = 512 * 1024 // 512 KB guard – plenty for any scrobble batch
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var httpd: InternalHttpd? = null

    val isRunning: Boolean get() = httpd?.isAlive == true

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
    // Inner NanoHTTPD implementation
    // --------------------------------------------------------------------------

    private inner class InternalHttpd(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.method == Method.GET && session.uri == "/api/ping" -> handlePing()
                    session.method == Method.POST && session.uri == "/api/scrobble" -> handleScrobble(session)
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
        private fun handlePing(): Response =
            newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"ok":true}""")

        // POST /api/scrobble  --------------------------------------------------
        private fun handleScrobble(session: IHTTPSession): Response {
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
                Log.w(TAG, "Failed to parse scrobble body", e)
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
            latch.await(10, TimeUnit.SECONDS)
            return newFixedLengthResponse(statusRef.get(), MIME_JSON, responseBodyRef.get())
        }

        private fun errorResponse(status: Response.Status, code: String): Response =
            newFixedLengthResponse(status, MIME_JSON, """{"ok":false,"error":"$code"}""")
    }
}
