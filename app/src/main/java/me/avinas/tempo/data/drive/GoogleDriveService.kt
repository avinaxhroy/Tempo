package me.avinas.tempo.data.drive

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import javax.inject.Inject
import javax.inject.Singleton

sealed class DriveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth(message: String = "Authorization failed", cause: Throwable? = null) : DriveException(message, cause)
    class Network(message: String = "Network error", cause: Throwable? = null) : DriveException(message, cause)
    class Server(message: String, cause: Throwable? = null) : DriveException(message, cause)
    class Unknown(message: String, cause: Throwable? = null) : DriveException(message, cause)
}

/**
 * Handles Google Drive REST API operations for backup and restore.
 * 
 * Features:
 * - Upload backup files to dedicated "Tempo Backups" folder
 * - List, download, and delete backups
 * - Auto-cleanup: maintains maximum of 5 backups
 * - Progress callbacks for upload/download operations
 */
@Singleton
class GoogleDriveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "GoogleDriveService"
        const val BACKUP_FOLDER_NAME = "Tempo Backups"
        const val MAX_BACKUPS = 5
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_TYPE_ZIP = "application/zip"
    }
    
    private var driveService: Drive? = null
    private var backupFolderId: String? = null
    private var cachedAccessToken: String? = null
    
    // Mutex to prevent race conditions when creating backup folder
    private val folderCreationMutex = Mutex()
    
    /**
     * Initialize the Drive service with current access token.
     * Returns null if authorization is not complete or token is unavailable.
     */
    private suspend fun getDriveService(): Drive? = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "No access token available - authorization may be incomplete")
            return@withContext null
        }
        
        if (accessToken.isBlank()) {
            Log.w(TAG, "Access token is empty - authorization failed")
            return@withContext null
        }
        
        // Recreate service if token changed
        if (driveService == null || cachedAccessToken != accessToken) {
            Log.d(TAG, "Initializing Drive service with fresh access token")
            cachedAccessToken = accessToken
            
            // Create a simple HTTP request initializer that adds the Bearer token
            val httpRequestInitializer = HttpRequestInitializer { request ->
                request.headers.authorization = "Bearer $accessToken"
                request.connectTimeout = 30000
                request.readTimeout = 30000
            }
            
            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                httpRequestInitializer
            )
                .setApplicationName("Tempo/${BuildConfig.VERSION_NAME}")
                .build()
        }
        
        driveService
    }
    
    /**
     * Get or create the Tempo backup folder in Google Drive.
     * Throws DriveException on failure.
     */
    
    /**
     * Execute a Drive API operation with automatic retry on 401 Unauthorized and network errors.
     */
    private suspend fun <T> executeWithRetry(
        retryCount: Int = 0,
        block: suspend (Drive) -> T
    ): T {
        val service = getDriveService() ?: throw DriveException.Auth("Not signed in")
        
        return try {
            block(service)
        } catch (e: GoogleJsonResponseException) {
            when (e.statusCode) {
                401 -> {
                    Log.w(TAG, "Authorization failed (401) - attempting refresh")
                    driveService = null
                    cachedAccessToken = null
                    
                    if (retryCount < 1 && authManager.refreshAccessToken()) {
                        Log.i(TAG, "Token refreshed, retrying operation")
                        executeWithRetry(retryCount + 1, block)
                    } else {
                        throw DriveException.Auth("Session expired. Please sign in again.", e)
                    }
                }
                403 -> throw DriveException.Auth("Permission denied. Check Drive access.", e)
                404 -> {
                    // Resource may have been deleted externally, clear cached folder ID
                    backupFolderId = null
                    throw DriveException.Server("Resource not found. Please try again.", e)
                }
                else -> throw DriveException.Server("Drive Error: ${e.message}", e)
            }
        } catch (e: IOException) {
            if (retryCount < 1) {
                Log.w(TAG, "Network error (attempt ${retryCount + 1}), retrying...")
                delay(2000)
                return executeWithRetry(retryCount + 1, block)
            }
            throw DriveException.Network("Network unavailable. Please check your connection.", e)
        } catch (e: Exception) {
            if (e is DriveException) throw e
            throw DriveException.Unknown("Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Get or create the Tempo backup folder in Google Drive.
     * Throws DriveException on failure.
     */
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
     * Upload a backup file to Google Drive.
     * 
     * @param localFile The backup ZIP file to upload
     * @param progressCallback Optional callback for upload progress (0.0 to 1.0)
     * @return Result of the upload operation
     */
    suspend fun uploadBackup(
        localFile: File,
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
            
            // This handles auth checks via getOrCreateBackupFolder -> executeWithRetry
            val folderId = getOrCreateBackupFolder()
            
            progressCallback?.invoke(0.15f)
            
            executeWithRetry { service ->
                // Generate backup filename with timestamp
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                val deviceName = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
                val fileName = "tempo_backup_${timestamp}_${deviceName}.tempo"
                
                // Create file metadata with custom properties
                val fileMetadata = DriveFile().apply {
                    name = fileName
                    parents = listOf(folderId)
                    description = "Tempo backup - App version: ${BuildConfig.VERSION_NAME}"
                    appProperties = mapOf(
                        "app_version" to BuildConfig.VERSION_NAME,
                        "device_name" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "backup_timestamp" to System.currentTimeMillis().toString()
                    )
                }
                
                progressCallback?.invoke(0.25f)
                
                // Upload file with progress tracking
                val mediaContent = FileContent(MIME_TYPE_ZIP, localFile)
                progressCallback?.invoke(0.3f)
                
                val uploadedFile = service.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, size, createdTime")
                    .execute()
                
                progressCallback?.invoke(0.85f)
                
                Log.i(TAG, "Uploaded backup: ${uploadedFile.name} (${uploadedFile.id})")
                
                // Return success immediately, cleanup happens after
                DriveBackupResult.Success(uploadedFile.id, uploadedFile.name)
            }.also {
                // Determine if we need to cleanup (best effort)
                try { cleanupOldBackups() } catch (e: Exception) { Log.w(TAG, "Cleanup failed", e) }
                progressCallback?.invoke(1.0f)
            }
        } catch (e: DriveException) {
            Log.e(TAG, "Upload failed with DriveException", e)
            DriveBackupResult.Error(e.message ?: "Upload failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed unexpectedly", e)
            DriveBackupResult.Error("Upload failed: ${e.message}", e)
        }
    }
    
    /**
     * List all backups from Google Drive.
     * Returns backups sorted by creation date (newest first).
     * Returns Result.failure if an error occurs (e.g., network, auth).
     */
    suspend fun listBackups(): Result<List<DriveBackupInfo>> = withContext(Dispatchers.IO) {
        try {
            val folderId = getOrCreateBackupFolder()
            
            val backups = executeWithRetry { service ->
                val query = "'$folderId' in parents and trashed = false"
                val result = service.files().list()
                    .setQ(query)
                    .setSpaces("drive")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list backups", e)
            Result.failure(e)
        }
    }

    
    /**
     * Download a backup file from Google Drive.
     * 
     * @param fileId The Drive file ID to download
     * @param progressCallback Optional callback for download progress
     * @return Result containing the downloaded local file
     */
    suspend fun downloadBackup(
        fileId: String,
        progressCallback: ((Float) -> Unit)? = null
    ): DriveRestoreResult = withContext(Dispatchers.IO) {
        try {
            val localFile = executeWithRetry { service ->
                progressCallback?.invoke(0.1f)
                
                // Get file metadata first
                val driveFile = service.files().get(fileId)
                    .setFields("id, name, size")
                    .execute()
                
                progressCallback?.invoke(0.2f)
                
                // Create local file
                val cacheDir = File(context.cacheDir, "drive_downloads")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                val file = File(cacheDir, driveFile.name ?: "backup.tempo")
                
                // Download file
                FileOutputStream(file).use { outputStream ->
                    service.files().get(fileId)
                        .executeMediaAndDownloadTo(outputStream)
                }
                
                progressCallback?.invoke(1.0f)
                
                Log.i(TAG, "Downloaded backup: ${file.name}")
                file
            }
            DriveRestoreResult.Success(localFile)
        } catch (e: DriveException) {
            Log.e(TAG, "Download failed with DriveException", e)
            DriveRestoreResult.Error(e.message ?: "Download failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DriveRestoreResult.Error("Download failed: ${e.message}", e)
        }
    }
    
    /**
     * Delete a backup from Google Drive.
     * 
     * @param fileId The Drive file ID to delete
     * @return true if deletion was successful
     */
    suspend fun deleteBackup(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            executeWithRetry { service ->
                service.files().delete(fileId).execute()
                Log.i(TAG, "Deleted backup: $fileId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup", e)
            false
        }
    }
    
    /**
     * Cleanup old backups, keeping only the most recent MAX_BACKUPS.
     */
    suspend fun cleanupOldBackups() = withContext(Dispatchers.IO) {
        try {
            val backups = listBackups().getOrNull() ?: return@withContext
            
            if (backups.size > MAX_BACKUPS) {
                Log.i(TAG, "Cleaning up old backups (${backups.size} > $MAX_BACKUPS)")
                
                // Delete oldest backups beyond MAX_BACKUPS
                val toDelete = backups.drop(MAX_BACKUPS)
                toDelete.forEach { backup ->
                    deleteBackup(backup.fileId)
                    Log.d(TAG, "Deleted old backup: ${backup.fileName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }
    
    /**
     * Check if Drive service is available and authenticated.
     */
    suspend fun isAvailable(): Boolean = getDriveService() != null
    
    /**
     * Clear cached service (call on sign out).
     */
    fun clearCache() {
        driveService = null
        backupFolderId = null
        cachedAccessToken = null
    }
    
    /**
     * Cleanup the local download cache directory.
     * Call this periodically or after failed downloads.
     */
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
