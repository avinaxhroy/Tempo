package me.avinas.tempo.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.drive.*
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportProgress
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.formatBytes
import me.avinas.tempo.worker.DriveBackupWorker
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the dedicated Backup & Restore screen.
 * 
 * Handles:
 * - Local export/import with optional local image bundling
 * - Google Drive backup and restore
 * - Scheduled backup configuration
 * - Size estimation for exports
 * - Progress tracking
 */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val importExportManager: ImportExportManager,
    private val googleAuthManager: GoogleAuthManager,
    private val driveService: GoogleDriveService,
    private val backupSettingsManager: BackupSettingsManager
) : ViewModel() {
    
    companion object {
        private val INCLUDE_LOCAL_IMAGES_KEY = booleanPreferencesKey("backup_include_local_images")
        private const val ALBUM_ART_DIR = "album_art"
    }
    
    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()
    
    // Progress from ImportExportManager
    val importExportProgress: StateFlow<ImportExportProgress?> = importExportManager.progress
    
    private val _importExportResult = MutableStateFlow<ImportExportResult?>(null)
    val importExportResult: StateFlow<ImportExportResult?> = _importExportResult.asStateFlow()
    
    private val _showConflictDialog = MutableStateFlow<Uri?>(null)
    val showConflictDialog: StateFlow<Uri?> = _showConflictDialog.asStateFlow()
    
    // Google Drive states
    val googleAccount: StateFlow<GoogleAccount?> = googleAuthManager.currentAccount
    val isSignedIn: StateFlow<Boolean> = googleAuthManager.isSignedIn
    val needsDriveConsent: StateFlow<Boolean> = googleAuthManager.needsDriveConsent
    val backupSettings: StateFlow<BackupSettings> = backupSettingsManager.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupSettings())
    
    private val _driveBackups = MutableStateFlow<List<DriveBackupInfo>>(emptyList())
    val driveBackups: StateFlow<List<DriveBackupInfo>> = _driveBackups.asStateFlow()
    
    private val _driveOperation = MutableStateFlow<DriveOperationState>(DriveOperationState.Idle)
    val driveOperation: StateFlow<DriveOperationState> = _driveOperation.asStateFlow()
    
    private val _showDriveRestoreDialog = MutableStateFlow<DriveBackupInfo?>(null)
    val showDriveRestoreDialog: StateFlow<DriveBackupInfo?> = _showDriveRestoreDialog.asStateFlow()
    
    // Flag to trigger sign-in with Activity context
    private val _signInRequested = MutableStateFlow(false)
    val signInRequested: StateFlow<Boolean> = _signInRequested.asStateFlow()
    
    // Flag to trigger session restore with Activity context
    private val _sessionRestoreRequested = MutableStateFlow(false)
    val sessionRestoreRequested: StateFlow<Boolean> = _sessionRestoreRequested.asStateFlow()
    
    // Flag to trigger consent flow with Activity context
    private val _consentRequested = MutableStateFlow(false)
    val consentRequested: StateFlow<Boolean> = _consentRequested.asStateFlow()
    
    init {
        loadSettings()
        calculateStats()
        // Request session restore - will be handled by the Composable with Activity context
        _sessionRestoreRequested.value = true
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                _uiState.value = _uiState.value.copy(
                    includeLocalImages = preferences[INCLUDE_LOCAL_IMAGES_KEY] ?: true
                )
            }
        }
    }
    
    /**
     * Called by the Composable when it has Activity context for session restore.
     */
    fun onSessionRestoreReady(activity: Activity) {
        if (!_sessionRestoreRequested.value) return
        _sessionRestoreRequested.value = false
        
        viewModelScope.launch {
            val restored = googleAuthManager.restoreSession(activity)
            if (restored) {
                // Check if Drive consent is needed after session restore
                if (googleAuthManager.needsDriveConsent.value) {
                    _consentRequested.value = true
                } else {
                    loadDriveBackups()
                }
            }
        }
    }
    
    private fun calculateStats() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trackCount = database.trackDao().getAllSync().size
                val artistCount = database.artistDao().getAllArtistsSync().size
                val eventCount = database.listeningEventDao().getAllEventsSync().size
                
                // Calculate local image count and size - ONLY count images referenced in database
                // This matches the logic in ImportExportManager.collectImageUrls()
                val tracks = database.trackDao().getAllSync()
                val artists = database.artistDao().getAllArtistsSync()
                val albums = database.albumDao().getAllSync()
                val enrichedMetadata = database.enrichedMetadataDao().getAllSync()
                
                // Collect all local image URLs (file://) that are actually referenced
                val localImageUrls = mutableSetOf<String>()
                
                fun collectLocal(url: String?) {
                    url?.let {
                        if (it.startsWith("file://")) {
                            localImageUrls.add(it)
                        }
                    }
                }
                
                tracks.forEach { collectLocal(it.albumArtUrl) }
                artists.forEach { collectLocal(it.imageUrl) }
                albums.forEach { collectLocal(it.artworkUrl) }
                enrichedMetadata.forEach { meta ->
                    collectLocal(meta.albumArtUrl)
                    collectLocal(meta.albumArtUrlSmall)
                    collectLocal(meta.albumArtUrlLarge)
                    collectLocal(meta.spotifyArtistImageUrl)
                    collectLocal(meta.iTunesArtistImageUrl)
                    collectLocal(meta.deezerArtistImageUrl)
                    collectLocal(meta.lastFmArtistImageUrl)
                }
                
                // Calculate size of referenced local images only
                var localImageSize = 0L
                var localImageCount = 0
                localImageUrls.forEach { fileUrl ->
                    try {
                        val file = File(fileUrl.removePrefix("file://"))
                        if (file.exists()) {
                            localImageSize += file.length()
                            localImageCount++
                        }
                    } catch (e: Exception) {
                        // Ignore invalid file paths
                    }
                }
                
                // Estimate export size
                val estimatedJsonSize = (trackCount + artistCount + eventCount) * 500L
                val estimatedTotalSize = estimatedJsonSize + localImageSize
                
                _uiState.value = _uiState.value.copy(
                    trackCount = trackCount,
                    artistCount = artistCount,
                    eventCount = eventCount,
                    localImageCount = localImageCount,
                    localImageSizeBytes = localImageSize,
                    estimatedExportSizeBytes = estimatedTotalSize,
                    isLoading = false
                )
            }
        }
    }
    
    fun toggleIncludeLocalImages(include: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[INCLUDE_LOCAL_IMAGES_KEY] = include }
            backupSettingsManager.setIncludeLocalImages(include)
            _uiState.value = _uiState.value.copy(includeLocalImages = include)
            // Recalculate estimated size
            if (include) {
                _uiState.value = _uiState.value.copy(
                    estimatedExportSizeBytes = (_uiState.value.trackCount + _uiState.value.artistCount + _uiState.value.eventCount) * 500L + _uiState.value.localImageSizeBytes
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    estimatedExportSizeBytes = (_uiState.value.trackCount + _uiState.value.artistCount + _uiState.value.eventCount) * 500L
                )
            }
        }
    }
    
    // ==================== LOCAL BACKUP ====================
    
    /**
     * Export all data to a ZIP file.
     */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            val includeImages = _uiState.value.includeLocalImages
            val result = importExportManager.exportData(uri, includeImages)
            _importExportResult.value = result
            // Refresh stats after export
            calculateStats()
        }
    }
    
    /**
     * Start import process - shows conflict resolution dialog first.
     */
    fun startImport(uri: Uri) {
        _showConflictDialog.value = uri
    }
    
    /**
     * Proceed with import after user selects conflict strategy.
     */
    fun importData(uri: Uri, strategy: ImportConflictStrategy) {
        _showConflictDialog.value = null
        viewModelScope.launch {
            val result = importExportManager.importData(uri, strategy)
            _importExportResult.value = result
            // Refresh stats after import
            calculateStats()
        }
    }
    
    /**
     * Cancel import.
     */
    fun cancelImport() {
        _showConflictDialog.value = null
    }
    
    /**
     * Clear the import/export result after showing to user.
     */
    fun clearImportExportResult() {
        _importExportResult.value = null
    }
    
    /**
     * Refresh stats (useful for pull-to-refresh or manual refresh).
     */
    fun refreshStats() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        calculateStats()
    }
    
    // ==================== GOOGLE DRIVE BACKUP ====================
    
    /**
     * Request sign in - sets flag for Composable to handle with Activity context.
     */
    fun requestSignIn() {
        _signInRequested.value = true
        _driveOperation.value = DriveOperationState.SigningIn
    }
    
    /**
     * Cancel sign in request (e.g., when Activity is unavailable).
     */
    fun cancelSignIn() {
        _signInRequested.value = false
        _driveOperation.value = DriveOperationState.Idle
    }
    
    /**
     * Called by the Composable when it has Activity context for sign-in.
     */
    fun onSignInReady(activity: Activity) {
        if (!_signInRequested.value) return
        _signInRequested.value = false
        
        viewModelScope.launch {
            when (val result = googleAuthManager.signIn(activity)) {
                is GoogleSignInResult.Success -> {
                    backupSettingsManager.setGoogleAccountEmail(result.account.email)
                    backupSettingsManager.setGoogleDriveEnabled(true)
                    
                    // Check if Drive consent is needed after sign-in
                    if (googleAuthManager.needsDriveConsent.value) {
                        _consentRequested.value = true
                        _driveOperation.value = DriveOperationState.SigningIn
                    } else {
                        loadDriveBackups()
                        _driveOperation.value = DriveOperationState.Idle
                    }
                }
                is GoogleSignInResult.Error -> {
                    _driveOperation.value = DriveOperationState.Error(result.message)
                }
                GoogleSignInResult.Cancelled -> {
                    _driveOperation.value = DriveOperationState.Idle
                }
                GoogleSignInResult.NoCredentials -> {
                    _driveOperation.value = DriveOperationState.Error("No Google accounts found on device")
                }
            }
        }
    }
    
    /**
     * Get the pending intent for Drive consent flow.
     */
    fun getDriveConsentPendingIntent() = googleAuthManager.getDriveAuthorizationPendingIntent()
    
    /**
     * Called after user completes consent flow (either approved or denied).
     */
    fun onConsentComplete(approved: Boolean) {
        _consentRequested.value = false
        
        if (approved) {
            viewModelScope.launch {
                // Complete consent flow to get access token
                val success = googleAuthManager.completeConsentFlow()
                if (success) {
                    // Verify that we actually have an access token now
                    val hasToken = googleAuthManager.getAccessToken() != null
                    if (hasToken) {
                        // Clear any cached Drive service to force re-initialization with new token
                        driveService.clearCache()
                        loadDriveBackups()
                        _driveOperation.value = DriveOperationState.Idle
                    } else {
                        _driveOperation.value = DriveOperationState.Error(
                            "Authorization incomplete. Please try signing out and signing in again."
                        )
                    }
                } else {
                    _driveOperation.value = DriveOperationState.Error(
                        "Failed to authorize Google Drive access. Please try again or sign out and sign in again."
                    )
                }
            }
        } else {
            _driveOperation.value = DriveOperationState.Error("Google Drive access was denied. Please grant permissions to use backup features.")
        }
    }
    
    /**
     * Sign out from Google.
     */
    fun signOut() {
        viewModelScope.launch {
            googleAuthManager.signOut()
            driveService.clearCache()
            driveService.cleanupDownloadCache()
            backupSettingsManager.setGoogleAccountEmail(null)
            backupSettingsManager.setGoogleDriveEnabled(false)
            _driveBackups.value = emptyList()
            DriveBackupWorker.cancel(context)
        }
    }
    
    /**
     * Load backups from Google Drive.
     */
    fun loadDriveBackups() {
        viewModelScope.launch {
            _driveOperation.value = DriveOperationState.Loading
            driveService.listBackups()
                .onSuccess { backups ->
                    _driveBackups.value = backups
                    _driveOperation.value = DriveOperationState.Idle
                }
                .onFailure { error ->
                    _driveBackups.value = emptyList()
                    _driveOperation.value = DriveOperationState.Error(
                        error.message ?: "Failed to load backups"
                    )
                }
        }
    }

    
    /**
     * Backup to Google Drive NOW.
     */
    fun backupToDrive() {
        // Guard against concurrent backup operations
        if (_driveOperation.value != DriveOperationState.Idle) {
            return
        }
        
        viewModelScope.launch {
            _driveOperation.value = DriveOperationState.Uploading(0f)
            
            // Create temporary backup file
            val tempFile = File(context.cacheDir, "temp_drive_backup.tempo")
            
            try {
                if (tempFile.exists()) tempFile.delete()
                
                val settings = backupSettings.value
                val exportResult = importExportManager.exportData(
                    Uri.fromFile(tempFile),
                    settings.includeLocalImages
                )
                
                if (exportResult is ImportExportResult.Error) {
                    _driveOperation.value = DriveOperationState.Error("Failed to create backup: ${exportResult.message}")
                    return@launch
                }
                
                // Upload to Drive
                val uploadResult = driveService.uploadBackup(tempFile) { progress ->
                    _driveOperation.value = DriveOperationState.Uploading(progress)
                }
                
                when (uploadResult) {
                    is DriveBackupResult.Success -> {
                        backupSettingsManager.updateLastBackup(BackupStatus.SUCCESS)
                        loadDriveBackups()
                        _driveOperation.value = DriveOperationState.Success("Backup uploaded successfully")
                    }
                    is DriveBackupResult.Error -> {
                        backupSettingsManager.updateLastBackup(BackupStatus.FAILED)
                        _driveOperation.value = DriveOperationState.Error(uploadResult.message)
                    }
                }
            } catch (e: Exception) {
                _driveOperation.value = DriveOperationState.Error("Backup failed: ${e.message}")
            } finally {
                // Always cleanup temp file
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }
    
    /**
     * Start restore from Drive - shows confirmation dialog first.
     */
    fun startDriveRestore(backup: DriveBackupInfo) {
        _showDriveRestoreDialog.value = backup
    }
    
    /**
     * Cancel Drive restore.
     */
    fun cancelDriveRestore() {
        _showDriveRestoreDialog.value = null
    }
    
    /**
     * Restore from Google Drive.
     */
    fun restoreFromDrive(backup: DriveBackupInfo, strategy: ImportConflictStrategy) {
        _showDriveRestoreDialog.value = null
        
        viewModelScope.launch {
            _driveOperation.value = DriveOperationState.Downloading(0f)
            
            try {
                // Download backup
                val downloadResult = driveService.downloadBackup(backup.fileId) { progress ->
                    _driveOperation.value = DriveOperationState.Downloading(progress)
                }
                
                when (downloadResult) {
                    is DriveRestoreResult.Success -> {
                        _driveOperation.value = DriveOperationState.Restoring
                        
                        // Import using existing import mechanism
                        val importResult = importExportManager.importData(
                            Uri.fromFile(downloadResult.localFile),
                            strategy
                        )
                        
                        // Cleanup downloaded file
                        downloadResult.localFile.delete()
                        
                        when (importResult) {
                            is ImportExportResult.Success -> {
                                calculateStats()
                                loadDriveBackups() // Refresh backup list after restore
                                _driveOperation.value = DriveOperationState.Success(
                                    "Restored ${importResult.totalRecords} records"
                                )
                                // Also set import result for RestoreScreen's auto-finish logic
                                _importExportResult.value = importResult
                            }
                            is ImportExportResult.Error -> {
                                _driveOperation.value = DriveOperationState.Error(importResult.message)
                                _importExportResult.value = importResult
                            }
                        }
                    }
                    is DriveRestoreResult.Error -> {
                        driveService.cleanupDownloadCache()
                        _driveOperation.value = DriveOperationState.Error(downloadResult.message)
                    }
                }
            } catch (e: Exception) {
                driveService.cleanupDownloadCache()
                _driveOperation.value = DriveOperationState.Error("Restore failed: ${e.message}")
            }
        }
    }
    
    /**
     * Delete a backup from Google Drive.
     */
    fun deleteDriveBackup(backup: DriveBackupInfo) {
        viewModelScope.launch {
            _driveOperation.value = DriveOperationState.Loading
            val success = driveService.deleteBackup(backup.fileId)
            if (success) {
                loadDriveBackups()
            } else {
                _driveOperation.value = DriveOperationState.Error("Failed to delete backup")
            }
        }
    }
    
    /**
     * Set backup interval and schedule worker.
     */
    fun setBackupInterval(interval: BackupInterval) {
        viewModelScope.launch {
            backupSettingsManager.setBackupInterval(interval)
            
            val settings = backupSettings.value
            if (interval == BackupInterval.MANUAL) {
                DriveBackupWorker.cancel(context)
            } else {
                DriveBackupWorker.schedule(context, interval.hours, settings.wifiOnly)
            }
        }
    }
    
    /**
     * Set Wi-Fi only preference.
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            backupSettingsManager.setWifiOnly(wifiOnly)
            
            // Re-schedule if interval is not manual
            val settings = backupSettings.value
            if (settings.backupInterval != BackupInterval.MANUAL) {
                DriveBackupWorker.schedule(context, settings.backupInterval.hours, wifiOnly)
            }
        }
    }
    
    /**
     * Clear drive operation state.
     */
    fun clearDriveOperation() {
        _driveOperation.value = DriveOperationState.Idle
    }
}

/**
 * UI State for Backup & Restore screen.
 */
data class BackupRestoreUiState(
    val isLoading: Boolean = true,
    val includeLocalImages: Boolean = true,
    
    // Data counts
    val trackCount: Int = 0,
    val artistCount: Int = 0,
    val eventCount: Int = 0,
    
    // Local image stats
    val localImageCount: Int = 0,
    val localImageSizeBytes: Long = 0,
    
    // Export estimation
    val estimatedExportSizeBytes: Long = 0
) {
    val totalRecords: Int get() = trackCount + artistCount + eventCount
    
    val localImageSizeFormatted: String get() = localImageSizeBytes.formatBytes()
    val estimatedExportSizeFormatted: String get() = estimatedExportSizeBytes.formatBytes()
}

/**
 * State for Drive operations.
 */
sealed class DriveOperationState {
    data object Idle : DriveOperationState()
    data object SigningIn : DriveOperationState()
    data object Loading : DriveOperationState()
    data class Uploading(val progress: Float) : DriveOperationState()
    data class Downloading(val progress: Float) : DriveOperationState()
    data object Restoring : DriveOperationState()
    data class Success(val message: String) : DriveOperationState()
    data class Error(val message: String) : DriveOperationState()
}
