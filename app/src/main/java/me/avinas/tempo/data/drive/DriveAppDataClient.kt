package me.avinas.tempo.data.drive

import android.util.Log
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive appDataFolder client dedicated to cross-device history.
 * It intentionally does not share the visible "Tempo Backups" folder used by
 * [GoogleDriveService].
 */
@Singleton
class DriveAppDataClient @Inject constructor(
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "DriveAppDataClient"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val MIME_GZIP = "application/gzip"
        private const val MIME_JSON = "application/json"
        private const val DISABLE_MARKER_NAME = "tempo_history_control_v1.json"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1_500L
    }

    @Volatile private var cachedClient: AuthorizedDriveClient? = null

    private suspend fun service(): AuthorizedDriveClient = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: throw DriveException.Auth("Google Drive authorization is unavailable")

        cachedClient?.takeIf { it.accessToken == token }?.let { return@withContext it }
        synchronized(this@DriveAppDataClient) {
            cachedClient?.takeIf { it.accessToken == token } ?: AuthorizedDriveClient(
                accessToken = token,
                service = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    HttpRequestInitializer { request ->
                        request.headers.authorization = "Bearer $token"
                        request.connectTimeout = 30_000
                        request.readTimeout = 30_000
                    }
                )
                .setApplicationName("Tempo/${BuildConfig.VERSION_NAME}")
                .build()
            ).also { cachedClient = it }
        }
    }

    private suspend fun <T> executeWithRetry(
        authAttempts: Int = 0,
        transientAttempts: Int = 0,
        block: suspend (Drive) -> T
    ): T {
        val client = service()
        return try {
            block(client.service)
        } catch (e: GoogleJsonResponseException) {
            when (e.statusCode) {
                401 -> {
                    clearCacheForAccessToken(client.accessToken)
                    if (authAttempts < 1 &&
                        authManager.refreshAccessTokenAfterFailure(client.accessToken)
                    ) {
                        executeWithRetry(authAttempts + 1, transientAttempts, block)
                    } else {
                        throw DriveException.Auth("Google Drive session expired", e)
                    }
                }
                403 -> {
                    val reasons = e.details?.errors.orEmpty().mapNotNull { it.reason }.toSet()
                    when {
                        reasons.any { it == "authError" || it == "insufficientPermissions" } -> {
                            clearCacheForAccessToken(client.accessToken)
                            val invalidated = authManager.invalidateAuthorizationForAccessToken(
                                client.accessToken
                            )
                            if (!invalidated && authAttempts < 1) {
                                executeWithRetry(authAttempts + 1, transientAttempts, block)
                            } else {
                                throw DriveException.Auth(
                                    "Google Drive app-data permission is missing. Reconnect Google Drive.",
                                    e
                                )
                            }
                        }
                        reasons.any { it.contains("rateLimit", ignoreCase = true) } ->
                            retryTransient(authAttempts, transientAttempts, e, block)
                        else -> throw DriveException.Server("Google Drive rejected the request: ${e.message}", e)
                    }
                }
                429, 500, 502, 503, 504 ->
                    retryTransient(authAttempts, transientAttempts, e, block)
                else -> throw DriveException.Server("Drive error ${e.statusCode}: ${e.message}", e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            retryTransient(authAttempts, transientAttempts, e, block)
        }
    }

    private suspend fun <T> retryTransient(
        authAttempts: Int,
        transientAttempts: Int,
        error: Exception,
        block: suspend (Drive) -> T
    ): T {
        if (transientAttempts >= MAX_RETRIES) {
            if (error is IOException) throw DriveException.Network("Drive network request failed", error)
            throw DriveException.Server("Drive is temporarily unavailable", error)
        }
        val delayMs = RETRY_BASE_DELAY_MS * (1L shl transientAttempts)
        Log.w(TAG, "Transient Drive failure; retrying in ${delayMs}ms", error)
        delay(delayMs)
        clearCache()
        return executeWithRetry(authAttempts, transientAttempts + 1, block)
    }

    /**
     * Create an immutable history batch unless the same deterministic filename is
     * already present. The existence check makes the operation idempotent across
     * the dangerous window where Drive accepted an upload but the process died
     * before Tempo could advance its local cursor.
     */
    suspend fun uploadHistoryBatch(
        fileName: String,
        compressedBytes: ByteArray,
        appProperties: Map<String, String>
    ): DriveAppDataFile = withContext(Dispatchers.IO) {
        require(compressedBytes.size <= DriveHistoryProtocol.MAX_COMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the compressed size limit"
        }
        val expectedMd5 = md5Hex(compressedBytes)
        val expectedSha256 = sha256Hex(compressedBytes)
        executeWithRetry { api ->
            findFilesByExactName(api, fileName).firstOrNull { existing ->
                existing.getSize()?.toLong() == compressedBytes.size.toLong() &&
                    existing.md5Checksum.equals(expectedMd5, ignoreCase = true) &&
                    existing.appProperties?.get(DriveHistoryProtocol.APP_PROPERTY_SHA256) == expectedSha256
            }?.let { existing ->
                Log.d(TAG, "Verified existing history batch; treating retry as success: $fileName")
                return@executeWithRetry existing.toAppDataFile()
            }
            // A same-name object with different bytes is never a successful
            // idempotent retry. Remove it before publishing the deterministic
            // payload so later clients cannot accept corrupted history.
            findFilesByExactName(api, fileName).forEach { existing ->
                api.files().delete(existing.id).execute()
            }

            val metadata = DriveFile().apply {
                name = fileName
                parents = listOf(APP_DATA_FOLDER)
                this.appProperties = appProperties +
                    (DriveHistoryProtocol.APP_PROPERTY_SHA256 to expectedSha256)
            }
            val result = api.files()
                .create(metadata, ByteArrayContent(MIME_GZIP, compressedBytes))
                .setFields("id,name,size,md5Checksum,createdTime,modifiedTime,appProperties")
                .execute()
            require(
                result.getSize()?.toLong() == compressedBytes.size.toLong() &&
                    result.md5Checksum.equals(expectedMd5, ignoreCase = true)
            ) { "Google Drive returned mismatched metadata for the uploaded history batch" }
            result.toAppDataFile()
        }
    }

    /**
     * Lists history batches. Passing [createdAfterMillis] keeps steady-state sync
     * cheap while a new device can pass null to discover the full history.
     */
    suspend fun listHistoryBatches(createdAfterMillis: Long? = null): List<DriveAppDataFile> =
        withContext(Dispatchers.IO) {
            executeWithRetry { api ->
                val clauses = mutableListOf(
                    "name contains '${DriveHistoryProtocol.FILE_PREFIX}'",
                    "appProperties has { key='${DriveHistoryProtocol.APP_PROPERTY_KIND}' and value='${DriveHistoryProtocol.KIND_HISTORY_BATCH}' }",
                    "trashed = false"
                )
                if (createdAfterMillis != null && createdAfterMillis > 0L) {
                    clauses += "createdTime > '${Instant.ofEpochMilli(createdAfterMillis)}'"
                }
                val query = clauses.joinToString(" and ")
                val files = mutableListOf<DriveAppDataFile>()
                var pageToken: String? = null
                do {
                    val result = api.files().list()
                        .setSpaces(APP_DATA_FOLDER)
                        .setQ(query)
                        .setOrderBy("createdTime asc")
                        .setPageSize(1_000)
                        .setPageToken(pageToken)
                        .setFields("nextPageToken,files(id,name,size,md5Checksum,createdTime,modifiedTime,appProperties)")
                        .execute()
                    result.files.orEmpty().forEach { files += it.toAppDataFile() }
                    pageToken = result.nextPageToken
                } while (!pageToken.isNullOrBlank())
                files
            }
        }

    suspend fun download(file: DriveAppDataFile): ByteArray = withContext(Dispatchers.IO) {
        require(file.sizeBytes in 1..DriveHistoryProtocol.MAX_COMPRESSED_BYTES.toLong()) {
            "Tempo Drive history batch has an invalid compressed size"
        }
        executeWithRetry { api ->
            val output = BoundedByteArrayOutputStream(DriveHistoryProtocol.MAX_COMPRESSED_BYTES)
            api.files().get(file.fileId).executeMediaAndDownloadTo(output)
            output.toByteArray().also { bytes ->
                require(bytes.size.toLong() == file.sizeBytes) {
                    "Tempo Drive history batch size does not match its metadata"
                }
                file.md5Checksum?.takeIf { it.isNotBlank() }?.let { expected ->
                    require(md5Hex(bytes).equals(expected, ignoreCase = true)) {
                        "Tempo Drive history batch checksum does not match its metadata"
                    }
                }
                val expectedSha256 = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_SHA256]
                require(expectedSha256?.matches(Regex("^[0-9a-f]{64}$")) == true &&
                    sha256Hex(bytes) == expectedSha256
                ) { "Tempo Drive history batch SHA-256 does not match its metadata" }
            }
        }
    }

    /**
     * Server-side version of the shared deletion marker. Clients persist the
     * marker version they explicitly accepted when enabling sync. If another
     * client later updates this marker, stale clients know a global delete was
     * requested and must stop before uploading anything new.
     */
    suspend fun getHistoryDisableMarkerVersion(): Long = withContext(Dispatchers.IO) {
        executeWithRetry { api ->
            val markerVersion = findFilesByExactName(api, DISABLE_MARKER_NAME)
                .maxOfOrNull { it.modifiedTime?.value ?: 0L }
                ?: return@executeWithRetry 0L
            markerVersion.takeIf { it > 0L }
                ?: throw DriveException.Server("Google Drive did not return a valid deletion marker version")
        }
    }

    /**
     * Create or update the single shared deletion marker and return Google's
     * server-side modifiedTime. Using Drive's timestamp avoids device-clock skew.
     */
    suspend fun bumpHistoryDisableMarker(): Long = withContext(Dispatchers.IO) {
        executeWithRetry { api ->
            val payload = JSONObject()
                .put("schema_version", 1)
                .put("history_sync_disabled", true)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val media = ByteArrayContent(MIME_JSON, payload)
            val previousVersion = findFilesByExactName(api, DISABLE_MARKER_NAME)
                .maxOfOrNull { it.modifiedTime?.value ?: 0L }
                ?: 0L
            var markers = findFilesByExactName(api, DISABLE_MARKER_NAME)
            if (markers.isEmpty()) {
                api.files()
                    .create(
                        DriveFile().apply {
                            name = DISABLE_MARKER_NAME
                            parents = listOf(APP_DATA_FOLDER)
                            appProperties = mapOf("tempo_kind" to "history_sync_control")
                        },
                        media
                    )
                    .setFields("id,name,size,md5Checksum,createdTime,modifiedTime,appProperties")
                    .execute()
            }
            // Update every same-name marker. This converges concurrent first-use
            // creates and ensures all clients observe the maximum server version.
            var markerVersion = 0L
            for (attempt in 0 until 3) {
                markers = findFilesByExactName(api, DISABLE_MARKER_NAME)
                for (marker in markers) {
                    val updated = api.files()
                        .update(
                            marker.id,
                            DriveFile().apply {
                                appProperties = mapOf(
                                    "tempo_kind" to "history_sync_control",
                                    "revision" to UUID.randomUUID().toString()
                                )
                            },
                            media
                        )
                        .setFields("id,name,size,md5Checksum,createdTime,modifiedTime,appProperties")
                        .execute()
                    markerVersion = maxOf(markerVersion, updated.modifiedTime?.value ?: 0L)
                }
                if (markerVersion > previousVersion) break
                if (attempt < 2) delay(5L)
            }
            markerVersion.takeIf { it > previousVersion }
                ?: throw DriveException.Server("Google Drive did not return a valid deletion marker version")
        }
    }

    /** Best-effort single-file delete retained for non-destructive callers. */
    suspend fun delete(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            deleteStrict(fileId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete appData file $fileId", e)
            false
        }
    }

    /**
     * Delete only batches from generations older than [generation]. Old clients
     * and pre-upgrade files have no generation property and are treated as 0.
     * This is the key race guard: after a user deliberately re-enables sync and
     * starts publishing generation N, a stale device honoring the deletion marker
     * may clean generation < N but can never erase the newly seeded generation N.
     */
    suspend fun deleteHistoryBatchesBeforeGeneration(generation: Long): Int =
        withContext(Dispatchers.IO) {
            require(generation >= 0L) { "Drive history generation cannot be negative" }
            val files = listHistoryBatches()
            var deleted = 0
            for (file in files) {
                val fileGeneration = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION]
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                if (fileGeneration >= generation) continue
                deleteStrict(file.fileId)
                deleted++
            }
            deleted
        }

    /**
     * Legacy/global cleanup retained for diagnostics and migration tooling. New
     * deletion-marker flows must use generation-aware cleanup above.
     */
    suspend fun deleteAllHistoryBatches(): Int = withContext(Dispatchers.IO) {
        val files = listHistoryBatches()
        var deleted = 0
        for (file in files) {
            deleteStrict(file.fileId)
            deleted++
        }
        deleted
    }

    private suspend fun deleteStrict(fileId: String) {
        try {
            executeWithRetry { api -> api.files().delete(fileId).execute() }
        } catch (e: DriveException.Server) {
            // Another linked client may have deleted the same immutable batch.
            if (!e.message.orEmpty().contains("Drive error 404")) throw e
        }
    }

    @Synchronized
    fun clearCache() {
        cachedClient = null
    }

    @Synchronized
    private fun clearCacheForAccessToken(accessToken: String) {
        if (cachedClient?.accessToken == accessToken) cachedClient = null
    }

    private fun findFilesByExactName(api: Drive, fileName: String): List<DriveFile> {
        val safeName = fileName
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return api.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ("name = '$safeName' and trashed = false")
            .setPageSize(1_000)
            .setFields("files(id,name,size,md5Checksum,createdTime,modifiedTime,appProperties)")
            .execute()
            .files
            .orEmpty()
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun DriveFile.toAppDataFile(): DriveAppDataFile = DriveAppDataFile(
        fileId = id,
        fileName = name,
        sizeBytes = getSize()?.toLong() ?: 0L,
        md5Checksum = md5Checksum,
        createdAt = createdTime?.value ?: 0L,
        modifiedAt = modifiedTime?.value ?: 0L,
        appProperties = appProperties.orEmpty()
    )

    /**
     * ByteArrayOutputStream with a hard cap so a corrupt or unexpectedly large
     * remote file cannot allocate unbounded heap before protocol validation runs.
     */
    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int
    ) : ByteArrayOutputStream() {
        private fun checkAdditional(additional: Int) {
            require(additional >= 0 && count <= maxBytes - additional) {
                "Tempo Drive history batch exceeds the compressed size limit"
            }
        }

        override fun write(b: Int) {
            checkAdditional(1)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkAdditional(len)
            super.write(b, off, len)
        }
    }

    /** Keep the Drive service and its immutable Authorization header together. */
    private data class AuthorizedDriveClient(
        val accessToken: String,
        val service: Drive
    )
}

data class DriveAppDataFile(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val md5Checksum: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    val appProperties: Map<String, String>
)
