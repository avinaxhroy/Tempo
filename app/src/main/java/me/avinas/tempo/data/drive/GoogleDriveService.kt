package me.avinas.tempo.data.drive

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class DriveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth(message: String = "Authorization failed", cause: Throwable? = null) : DriveException(message, cause)
    class Network(message: String = "Network error", cause: Throwable? = null) : DriveException(message, cause)
    class Server(message: String, cause: Throwable? = null) : DriveException(message, cause)

    /** Bytes did not match Drive's own metadata. Retrying cannot fix a corrupt backup. */
    class Corrupt(message: String, cause: Throwable? = null) : DriveException(message, cause)
    class Unknown(message: String, cause: Throwable? = null) : DriveException(message, cause)
}

/** Google Drive REST operations for Tempo backups: upload, list, download and delete. */
@Singleton
class GoogleDriveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "GoogleDriveService"
        const val BACKUP_FOLDER_NAME = "Tempo Backups"
        const val BACKUP_FILE_PREFIX = "tempo_backup_"
        const val MAX_BACKUPS = 5
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_TYPE_ZIP = "application/zip"
        // Transient-failure retry policy for executeWithRetry
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
        private const val DOWNLOAD_FREE_SPACE_BUFFER_BYTES = 16L * 1024L * 1024L

        internal fun isRetryable403Reason(reason: String?): Boolean = reason in setOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "sharingRateLimitExceeded",
            "backendError"
        )

        internal fun shouldInvalidateAuthFor403(reason: String?): Boolean = reason in setOf(
            "insufficientPermissions",
            "appNotAuthorizedToFile"
        )

        internal fun hasEnoughDownloadSpace(
            expectedSize: Long,
            usableSpace: Long
        ): Boolean = usableSpace <= 0L ||
            expectedSize <= (usableSpace - DOWNLOAD_FREE_SPACE_BUFFER_BYTES).coerceAtLeast(0L)
    }
    
    private data class CachedDriveClient(
        val service: Drive,
        val accessToken: String
    )

    @Volatile
    private var driveClient: CachedDriveClient? = null
    @Volatile
    private var backupFolderId: String? = null

    // The Drive client and its token form one cache entry. Building/updating
    // them without a lock can leave a client initialized with token A while the
    // cache says token B, causing every later call to reuse the wrong account.
    private val driveServiceMutex = Mutex()

    // Mutex to prevent race conditions when creating backup folder
    private val folderCreationMutex = Mutex()
    
    /** Drive client for the current access token, or null when there is no usable token. */
    private suspend fun getDriveClient(): CachedDriveClient? = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "No access token available - authorization may be incomplete")
            return@withContext null
        }
        
        if (accessToken.isBlank()) {
            Log.w(TAG, "Access token is empty - authorization failed")
            return@withContext null
        }
        
        driveServiceMutex.withLock {
            driveClient?.takeIf { it.accessToken == accessToken }?.let {
                return@withLock it
            }

            Log.d(TAG, "Initializing Drive service with fresh access token")

            // Create a simple HTTP request initializer that adds the Bearer token
            val httpRequestInitializer = HttpRequestInitializer { request ->
                request.headers.authorization = "Bearer $accessToken"
                request.connectTimeout = 30000
                request.readTimeout = 30000
            }

            val newClient = CachedDriveClient(
                service = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    httpRequestInitializer
                )
                    .setApplicationName("Tempo/${BuildConfig.VERSION_NAME}")
                    .build(),
                accessToken = accessToken
            )

            // Publish the matching client/token pair as one immutable value only
            // after construction is complete, while holding the same mutex.
            driveClient = newClient
            newClient
        }
    }

    private suspend fun clearDriveClient(expectedAccessToken: String? = null) {
        driveServiceMutex.withLock {
            if (expectedAccessToken == null || driveClient?.accessToken == expectedAccessToken) {
                driveClient = null
            }
        }
    }
    
    /**
     * Execute a Drive API operation with automatic retry on 401 Unauthorized,
     * transient server errors (429/5xx) and network errors.
     *
     * Transient failures get up to [MAX_TRANSIENT_RETRIES] attempts with
     * exponential backoff (2s, 4s, 8s). A single 401 triggers one token
     * refresh + retry.
     */
    private suspend fun <T> executeWithRetry(
        transientRetryCount: Int = 0,
        authRetryCount: Int = 0,
        notFoundRetryCount: Int = 0,
        block: suspend (Drive) -> T
    ): T {
        val client = getDriveClient() ?: throw DriveException.Auth(
            "Google Drive authorization incomplete. Please sign out and sign in again."
        )
        
        return try {
            block(client.service)
        } catch (e: GoogleJsonResponseException) {
            when (e.statusCode) {
                401 -> {
                    val errorReason = e.details?.errors?.firstOrNull()?.reason
                    val detail = e.details?.message ?: e.statusMessage ?: e.message
                    // The reason is what distinguishes "the grant was revoked" from
                    // "the token was minted for another account/client", and it is the
                    // only clue we get for a failure that is specific to one account.
                    Log.w(
                        TAG,
                        "Authorization failed (401) for account " +
                            "${authManager.currentAccount.value?.email}: " +
                            "reason=$errorReason detail=$detail"
                    )
                    clearDriveClient(client.accessToken)
                    
                    if (authRetryCount < 1 &&
                        authManager.refreshAccessTokenAfterFailure(client.accessToken)
                    ) {
                        Log.i(TAG, "Token refreshed, retrying operation")
                        executeWithRetry(
                            transientRetryCount = transientRetryCount,
                            authRetryCount = authRetryCount + 1,
                            block = block
                        )
                    } else {
                        // Definitive: a token minted seconds ago was rejected as well.
                        // Stop reporting the account as connected.
                        authManager.markSessionRejected()
                        throw DriveException.Auth(
                            buildAuthFailureMessage(errorReason, detail),
                            e
                        )
                    }
                }
                403 -> {
                    // Drive uses HTTP 403 for several unrelated conditions. Quota,
                    // storage and administrator-policy failures must never wipe a
                    // valid OAuth session; only known scope/authorization reasons do.
                    val errorReason = e.details?.errors?.firstOrNull()?.reason
                    val detail = e.details?.message ?: e.statusMessage ?: e.message

                    if (isRetryable403Reason(errorReason)) {
                        if (transientRetryCount < MAX_TRANSIENT_RETRIES) {
                            val backoffMs = RETRY_BASE_DELAY_MS * (1L shl transientRetryCount)
                            Log.w(
                                TAG,
                                "Transient Drive 403 ($errorReason), retrying in ${backoffMs}ms"
                            )
                            delay(backoffMs)
                            return executeWithRetry(
                                transientRetryCount = transientRetryCount + 1,
                                authRetryCount = authRetryCount,
                                block = block
                            )
                        }
                        throw DriveException.Server(
                            "Google Drive rate limit reached. Please try again later.",
                            e
                        )
                    }

                    when (errorReason) {
                        "storageQuotaExceeded" -> throw DriveException.Server(
                            "Google Drive storage is full. Free some Drive storage and try again.",
                            e
                        )

                        "dailyLimitExceeded" -> throw DriveException.Server(
                            "The Google Drive API daily quota has been reached. Try again later.",
                            e
                        )

                        "activeItemCreationLimitExceeded",
                        "numChildrenInNonRootLimitExceeded",
                        "teamDriveFileLimitExceeded" -> throw DriveException.Server(
                            "Google Drive cannot create another backup because an item or folder limit was reached. $detail",
                            e
                        )

                        "domainPolicy" -> throw DriveException.Server(
                            "Google Drive access is blocked by your account or organization policy. $detail",
                            e
                        )
                    }

                    if (shouldInvalidateAuthFor403(errorReason)) {
                        Log.e(TAG, "Drive authorization is insufficient ($errorReason): $detail", e)
                        clearDriveClient(client.accessToken)
                        val invalidated = authManager.invalidateAuthorizationForAccessToken(
                            client.accessToken
                        )
                        if (!invalidated && authRetryCount < 1) {
                            Log.i(TAG, "Authorization changed during the failed request; retrying with the current token")
                            return executeWithRetry(
                                transientRetryCount = transientRetryCount,
                                authRetryCount = authRetryCount + 1,
                                block = block
                            )
                        }
                        throw DriveException.Auth(
                            "Google Drive permission is missing. Sign in again to restore Drive access.",
                            e
                        )
                    }

                    // Unknown 403s are surfaced without destroying credentials. A
                    // future request may succeed, and re-signing in cannot fix quota,
                    // policy, file-specific or other non-OAuth Drive restrictions.
                    Log.e(TAG, "Drive API returned 403 ($errorReason): $detail", e)
                    throw DriveException.Server(
                        "Google Drive refused the request${if (errorReason != null) " ($errorReason)" else ""}. $detail",
                        e
                    )
                }
                404 -> {
                    // The "Tempo Backups" folder (or the file itself) was deleted
                    // outside the app. Drop the cached folder id and retry once: the
                    // block re-runs getOrCreateBackupFolder(), which recreates it.
                    backupFolderId = null
                    if (notFoundRetryCount < 1) {
                        Log.w(TAG, "Drive resource missing; recreating the backup folder and retrying")
                        return executeWithRetry(
                            transientRetryCount = transientRetryCount,
                            authRetryCount = authRetryCount,
                            notFoundRetryCount = notFoundRetryCount + 1,
                            block = block
                        )
                    }
                    throw DriveException.Server(
                        "The backup file or folder no longer exists in Google Drive.",
                        e
                    )
                }
                408, 429, 500, 502, 503, 504 -> {
                    // Rate-limited or transient server error — retry with backoff.
                    // Keep this counter independent from the single OAuth refresh.
                    if (transientRetryCount < MAX_TRANSIENT_RETRIES) {
                        val backoffMs = RETRY_BASE_DELAY_MS * (1L shl transientRetryCount)
                        Log.w(
                            TAG,
                            "Transient Drive error ${e.statusCode} " +
                                "(attempt ${transientRetryCount + 1}), retrying in ${backoffMs}ms"
                        )
                        delay(backoffMs)
                        return executeWithRetry(
                            transientRetryCount = transientRetryCount + 1,
                            authRetryCount = authRetryCount,
                            block = block
                        )
                    }
                    throw DriveException.Server("Drive is temporarily unavailable (${e.statusCode}). Please try again later.", e)
                }
                else -> throw DriveException.Server("Drive Error: ${e.message}", e)
            }
        } catch (e: IOException) {
            if (transientRetryCount < MAX_TRANSIENT_RETRIES) {
                val backoffMs = RETRY_BASE_DELAY_MS * (1L shl transientRetryCount)
                Log.w(
                    TAG,
                    "Network error (attempt ${transientRetryCount + 1}), retrying in ${backoffMs}ms..."
                )
                delay(backoffMs)
                return executeWithRetry(
                    transientRetryCount = transientRetryCount + 1,
                    authRetryCount = authRetryCount,
                    block = block
                )
            }
            throw DriveException.Network("Network unavailable. Please check your connection.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DriveException) throw e
            throw DriveException.Unknown("Unexpected error: ${e.message}", e)
        }
    }

    /**
     * "Session expired" plus Google's own reason. The reason is the only way to tell
     * a revoked grant (sign in again) from a scope problem (re-consent) or from the
     * wrong account being authorized, so it belongs in front of the user, not only
     * in logcat.
     */
    private fun buildAuthFailureMessage(reason: String?, detail: String?): String {
        val note = reason?.takeIf { it.isNotBlank() }
            ?: detail?.takeIf { it.isNotBlank() }?.take(80)
        return if (note == null) {
            "Session expired. Please sign in again."
        } else {
            "Session expired. Please sign in again. (Google: $note)"
        }
    }

    /** Id of the "Tempo Backups" folder, created on first use. Throws DriveException on failure. */
    private suspend fun getOrCreateBackupFolder(): String = withContext(Dispatchers.IO) {
        // Return cached folder ID if available and valid
        backupFolderId?.let { return@withContext it }
        
        // Use mutex to prevent race condition where two concurrent operations
        // might both create a backup folder
        folderCreationMutex.withLock {
            // Double-check after acquiring lock
            backupFolderId?.let { return@withContext it }
        
        val folderId = executeWithRetry { service ->
            // Search for existing folder
            val query = "name = '$BACKUP_FOLDER_NAME' and mimeType = '$MIME_TYPE_FOLDER' and trashed = false"
            Log.d(TAG, "Searching for existing backup folder")
            
            val result = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            val existingFolder = result.files?.firstOrNull()
            if (existingFolder != null) {
                Log.i(TAG, "Found existing backup folder: ${existingFolder.id}")
                return@executeWithRetry existingFolder.id
            }
            
            // Create new folder
            Log.i(TAG, "Creating new backup folder in Drive")
            val folderMetadata = DriveFile().apply {
                name = BACKUP_FOLDER_NAME
                mimeType = MIME_TYPE_FOLDER
            }
            
            val folder = service.files().create(folderMetadata)
                .setFields("id")
                .execute()
            
            Log.i(TAG, "Successfully created backup folder: ${folder.id}")
            folder.id
        }
        
            backupFolderId = folderId
            folderId
        }
    }
    
    /**
     * Upload a backup ZIP to the "Tempo Backups" folder. [progressCallback] receives upload
     * progress from 0.0 to 1.0.
     */
    suspend fun uploadBackup(
        localFile: File,
        idempotencyKey: String = UUID.randomUUID().toString(),
        progressCallback: ((Float) -> Unit)? = null
    ): DriveBackupResult = withContext(Dispatchers.IO) {
        try {
            // Validate file before attempting upload
            if (!localFile.exists()) {
                Log.e(TAG, "Backup file does not exist: ${localFile.absolutePath}")
                return@withContext DriveBackupResult.Error("Backup file not found")
            }
            
            if (localFile.length() == 0L) {
                Log.e(TAG, "Backup file is empty: ${localFile.absolutePath}")
                return@withContext DriveBackupResult.Error("Backup file is empty")
            }
            
            val validExtensions = listOf(".tempo", ".zip")
            if (!validExtensions.any { localFile.name.endsWith(it, ignoreCase = true) }) {
                Log.w(TAG, "Unexpected file extension for backup: ${localFile.name}")
            }
            
            Log.d(TAG, "Uploading backup file: ${localFile.name} (${localFile.length()} bytes)")
            progressCallback?.invoke(0.05f)

            val sourceSize = localFile.length()
            val sourceMd5 = md5Hex(localFile)
            
            // This handles auth checks via getOrCreateBackupFolder -> executeWithRetry
            val folderId = getOrCreateBackupFolder()
            
            progressCallback?.invoke(0.15f)
            
            // Keep a stable key for the whole logical upload. The scheduled worker
            // passes a stable logical run ID, so the key also survives WorkManager
            // retries. If Google created the file but the response was lost, the
            // next attempt finds that exact file instead of creating a duplicate.
            val safeIdempotencyKey = idempotencyKey
                .replace(Regex("[^A-Za-z0-9._:-]"), "_")
                .take(120)
                .ifBlank { UUID.randomUUID().toString() }

            executeWithRetry { service ->
                val existingFiles = service.files().list()
                    .setQ(
                        "'$folderId' in parents and trashed = false and " +
                            "appProperties has { key='backup_run_id' and value='$safeIdempotencyKey' }"
                    )
                    .setFields("files(id, name, size, md5Checksum, createdTime)")
                    .setPageSize(100)
                    .execute()
                    .files
                    .orEmpty()

                val completedExisting = existingFiles.firstOrNull { existing ->
                    existing.getSize()?.toLong() == sourceSize &&
                        existing.md5Checksum?.equals(sourceMd5, ignoreCase = true) == true
                }
                if (completedExisting != null) {
                    // A previous request may have reached Drive even when its HTTP
                    // response never reached Tempo. Reuse the byte-identical file
                    // and remove any corrupt duplicates left by an older client.
                    existingFiles
                        .filterNot { it.id == completedExisting.id }
                        .forEach { duplicate ->
                            runCatching { service.files().delete(duplicate.id).execute() }
                                .onFailure { error ->
                                    Log.w(TAG, "Could not remove duplicate same-run Drive backup", error)
                                }
                        }
                    Log.i(TAG, "Reusing already-created Drive backup for run $safeIdempotencyKey")
                    progressCallback?.invoke(0.85f)
                    return@executeWithRetry DriveBackupResult.Success(
                        completedExisting.id,
                        completedExisting.name
                    )
                }

                // Never create another same-run file while a corrupt/incomplete one
                // still exists: a later retry could select the wrong duplicate and
                // incorrectly report success. Deletion failure therefore aborts this
                // attempt and is handled by the normal retry policy.
                existingFiles.forEach { existing ->
                    try {
                        service.files().delete(existing.id).execute()
                    } catch (error: Exception) {
                        throw IOException(
                            "Unable to replace an incomplete same-run Drive backup",
                            error
                        )
                    }
                }

                // Generate backup filename with timestamp
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val deviceName = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
                val fileName = "${BACKUP_FILE_PREFIX}${timestamp}_${deviceName}.tempo"
                
                // Create file metadata with custom properties
                val fileMetadata = DriveFile().apply {
                    name = fileName
                    parents = listOf(folderId)
                    description = "Tempo backup - App version: ${BuildConfig.VERSION_NAME}"
                    appProperties = mapOf(
                        "app_version" to BuildConfig.VERSION_NAME,
                        "device_name" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "backup_timestamp" to System.currentTimeMillis().toString(),
                        "backup_run_id" to safeIdempotencyKey
                    )
                }
                
                progressCallback?.invoke(0.25f)
                
                // Upload file with progress tracking
                val mediaContent = FileContent(MIME_TYPE_ZIP, localFile)
                progressCallback?.invoke(0.3f)
                
                val uploadedFile = service.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, size, md5Checksum, createdTime")
                    .execute()

                progressCallback?.invoke(0.85f)

                // Verify Drive stored every byte. A mismatch means the upload was
                // silently truncated: delete the bad copy so it can never be
                // restored or occupy one of the MAX_BACKUPS retention slots, then
                // fail with Corrupt — re-uploading the same bytes cannot fix it.
                val uploadedSize = uploadedFile.getSize()?.toLong()
                val uploadedMd5 = uploadedFile.md5Checksum
                if (uploadedSize != sourceSize ||
                    !uploadedMd5.equals(sourceMd5, ignoreCase = true)
                ) {
                    runCatching { service.files().delete(uploadedFile.id).execute() }
                    throw DriveException.Corrupt(
                        "Google Drive did not store the exact backup bytes. Please try backing up again."
                    )
                }

                Log.i(TAG, "Uploaded backup: ${uploadedFile.name} (${uploadedFile.id})")

                // Return success immediately, cleanup happens after
                DriveBackupResult.Success(uploadedFile.id, uploadedFile.name)
            }.also {
                // Determine if we need to cleanup (best effort)
                try {
                    cleanupOldBackups()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup failed", e)
                }
                progressCallback?.invoke(1.0f)
            }
        } catch (e: DriveException) {
            Log.e(TAG, "Upload failed with DriveException", e)
            DriveBackupResult.Error(e.message ?: "Upload failed", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed unexpectedly", e)
            DriveBackupResult.Error("Upload failed: ${e.message}", e)
        }
    }
    
    /** Backups in Drive, newest first. Returns a failure instead of throwing on network/auth errors. */
    suspend fun listBackups(): Result<List<DriveBackupInfo>> = withContext(Dispatchers.IO) {
        try {
            val folderId = getOrCreateBackupFolder()
            
            val backups = executeWithRetry { service ->
                val result = service.files().list()
                    .setQ("'$folderId' in parents and name contains '$BACKUP_FILE_PREFIX' and trashed = false")
                    .setFields("files(id, name, size, createdTime, appProperties)")
                    .setOrderBy("createdTime desc")
                    .execute()
                
                result.files?.map { file ->
                    DriveBackupInfo(
                        fileId = file.id,
                        fileName = file.name,
                        createdAt = file.createdTime?.value ?: 0L,
                        sizeBytes = file.getSize()?.toLong() ?: 0L,
                        appVersion = file.appProperties?.get("app_version"),
                        deviceName = file.appProperties?.get("device_name")
                    )
                } ?: emptyList()
            }
            Result.success(backups)
        } catch (e: DriveException) {
            Log.e(TAG, "Failed to list backups: ${e.message}", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list backups", e)
            Result.failure(e)
        }
    }

    
    /** Downloads a backup into the local `drive_downloads` cache directory. */
    suspend fun downloadBackup(
        fileId: String,
        progressCallback: ((Float) -> Unit)? = null
    ): DriveRestoreResult = withContext(Dispatchers.IO) {
        try {
            val localFile = executeWithRetry { service ->
                progressCallback?.invoke(0.1f)
                
                // Get file metadata first
                val driveFile = service.files().get(fileId)
                    .setFields("id, name, size, md5Checksum")
                    .execute()
                
                progressCallback?.invoke(0.2f)
                
                val cacheDir = File(context.cacheDir, "drive_downloads")
                if ((!cacheDir.exists() && !cacheDir.mkdirs()) || !cacheDir.isDirectory) {
                    throw IOException("Unable to create the Drive download cache")
                }

                val expectedSize = driveFile.getSize()?.toLong()
                val expectedMd5 = driveFile.md5Checksum
                if (expectedSize == null || expectedSize <= 0L || expectedMd5.isNullOrBlank()) {
                    throw DriveException.Server("Drive backup metadata is incomplete")
                }
                val usableSpace = cacheDir.usableSpace
                if (!hasEnoughDownloadSpace(expectedSize, usableSpace)) {
                    throw DriveException.Unknown(
                        "Not enough free device storage to download this backup"
                    )
                }

                // Never trust the remote display name as a local path and never
                // share a target between concurrent/retried downloads.
                val file = File.createTempFile("tempo_drive_", ".tempo", cacheDir)

                try {
                    FileOutputStream(file).use { outputStream ->
                        service.files().get(fileId)
                            .executeMediaAndDownloadTo(outputStream)
                    }

                    if (file.length() != expectedSize ||
                        !md5Hex(file).equals(expectedMd5, ignoreCase = true)
                    ) {
                        // Corruption is not transient: retrying would only download the
                        // same bad bytes again and misreport it as a network problem.
                        throw DriveException.Corrupt(
                            "This backup is damaged and cannot be restored. Please try another backup."
                        )
                    }

                    progressCallback?.invoke(1.0f)

                    Log.i(TAG, "Downloaded and verified Drive backup (${file.length()} bytes)")
                    file
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
            DriveRestoreResult.Success(localFile)
        } catch (e: DriveException) {
            Log.e(TAG, "Download failed with DriveException", e)
            DriveRestoreResult.Error(e.message ?: "Download failed", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DriveRestoreResult.Error("Download failed: ${e.message}", e)
        }
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    
    suspend fun deleteBackup(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            executeWithRetry { service ->
                service.files().delete(fileId).execute()
                Log.i(TAG, "Deleted backup: $fileId")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup", e)
            false
        }
    }
    
    /** Deletes all but the newest [MAX_BACKUPS] backups. */
    suspend fun cleanupOldBackups() = withContext(Dispatchers.IO) {
        try {
            val backups = listBackups().getOrNull() ?: return@withContext
            
            if (backups.size > MAX_BACKUPS) {
                Log.i(TAG, "Cleaning up old backups (${backups.size} > $MAX_BACKUPS)")
                
                // Delete oldest backups beyond MAX_BACKUPS
                val toDelete = backups.drop(MAX_BACKUPS)
                toDelete.forEach { backup ->
                    if (deleteBackup(backup.fileId)) {
                        Log.i(TAG, "Deleted old backup: ${backup.fileName}")
                    } else {
                        Log.w(TAG, "Could not delete old backup: ${backup.fileName}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }
    
    suspend fun isAvailable(): Boolean = getDriveClient() != null
    
    suspend fun clearCache() {
        // Match getOrCreateBackupFolder's lock order. Waiting for an in-flight
        // folder lookup prevents it from repopulating an old account's folder ID
        // immediately after an explicit account switch/sign-out cleared it.
        folderCreationMutex.withLock {
            driveServiceMutex.withLock {
                driveClient = null
                backupFolderId = null
            }
        }
    }
    
    /** Deletes the local `drive_downloads` cache. */
    fun cleanupDownloadCache() {
        try {
            val cacheDir = File(context.cacheDir, "drive_downloads")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted cached file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup download cache", e)
        }
    }
}
