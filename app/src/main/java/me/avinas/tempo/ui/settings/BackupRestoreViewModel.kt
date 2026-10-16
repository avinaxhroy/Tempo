package me.avinas.tempo.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.drive.*
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportProgress
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.BackupRun
import me.avinas.tempo.data.analytics.BackupTarget
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.formatBytes
import me.avinas.tempo.worker.DriveBackupWorker
import me.avinas.tempo.worker.LocalBackupWorker
import java.io.File
import java.util.UUID
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
    private val profileIdentityManager: ProfileIdentityManager,
    private val googleAuthManager: GoogleAuthManager,
    private val driveService: GoogleDriveService,
    private val backupSettingsManager: BackupSettingsManager,
    private val driveHistorySyncManager: DriveHistorySyncManager,
    private val applicationScope: CoroutineScope,
    private val tracker: AnalyticsTracker
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettings())
    
    private val _driveBackups = MutableStateFlow<List<DriveBackupInfo>>(emptyList())
    val driveBackups: StateFlow<List<DriveBackupInfo>> = _driveBackups.asStateFlow()
    
    private val _driveOperation = MutableStateFlow<DriveOperationState>(DriveOperationState.Idle)
    val driveOperation: StateFlow<DriveOperationState> = _driveOperation.asStateFlow()
    
    private val _showDriveRestoreDialog = MutableStateFlow<DriveBackupInfo?>(null)
    val showDriveRestoreDialog: StateFlow<DriveBackupInfo?> = _showDriveRestoreDialog.asStateFlow()
    
    // Flag to trigger sign-in with Activity context
    private val _signInRequested = MutableStateFlow(false)
    val signInRequested: StateFlow<Boolean> = _signInRequested.asStateFlow()
    
    // Kept for UI compatibility. Automatic screen entry no longer toggles this;
    // session restoration is performed silently from persisted account/token data.
    private val _sessionRestoreRequested = MutableStateFlow(false)
    val sessionRestoreRequested: StateFlow<Boolean> = _sessionRestoreRequested.asStateFlow()
    
    // Flag to trigger consent flow with Activity context
    private val _consentRequested = MutableStateFlow(false)
    val consentRequested: StateFlow<Boolean> = _consentRequested.asStateFlow()
    
    init {
        loadSettings()
        calculateStats()
        healStaleBackupStatus()
        initializeBackupState()
    }

    /**
     * Restore an existing Google/Drive session without invoking Credential
     * Manager UI. Also repairs a missing periodic WorkManager registration when
     * an interval is already persisted from an older app version.
     */
    private fun initializeBackupState() {
        viewModelScope.launch {
            try {
                val settings = backupSettingsManager.settings.first()
                ensureAutomaticBackupScheduled(settings)

                if (!settings.isGoogleDriveEnabled) return@launch

                val restored = googleAuthManager.restoreSessionSilently()
                if (restored) {
                    loadDriveBackups()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _driveOperation.value = DriveOperationState.Error(
                    e.message ?: "Could not initialize automatic backup state"
                )
            }
        }
    }

    /**
     * A process death mid-backup leaves lastBackupStatus stuck at IN_PROGRESS
     * forever — the worker that would have flipped it is gone. If no backup
     * work is actually running, mark the run failed so the UI stops showing a
     * phantom "backup in progress".
     */
    private fun healStaleBackupStatus() {
        viewModelScope.launch {
            val settings = backupSettingsManager.settings.first()
            if (settings.lastBackupStatus != BackupStatus.IN_PROGRESS) return@launch

            val running: Boolean? = withContext(Dispatchers.Default) {
                try {
                    val workManager = WorkManager.getInstance(context)
                    listOf(
                        DriveBackupWorker.WORK_NAME,
                        DriveBackupWorker.MANUAL_WORK_NAME
                    ).any { workName ->
                        // A periodic WorkRequest is normally ENQUEUED between
                        // executions, so ENQUEUED/BLOCKED do not mean a backup is
                        // currently in progress. Only RUNNING can legitimately keep
                        // lastBackupStatus at IN_PROGRESS. If a process died during
                        // an attempt, marking that interrupted attempt FAILED here is
                        // correct; a WorkManager retry will set IN_PROGRESS again
                        // when it actually starts running.
                        workManager.getWorkInfosForUniqueWork(workName)
                            .get()
                            .any { info -> info.state == WorkInfo.State.RUNNING }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            // A WorkManager query failure is not evidence that no worker is
            // running. Only heal the status after a successful negative query.
            if (running == false) {
                backupSettingsManager.updateLastBackup(BackupStatus.FAILED)
            }
        }
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                _uiState.update { it.copy(
                    includeLocalImages = preferences[INCLUDE_LOCAL_IMAGES_KEY] ?: true
                ) }
            }
        }
    }

    /**
     * UI bridge retained for compatibility with BackupRestoreScreen. If it is
     * ever requested, keep the restore non-interactive so entering the screen
     * cannot show the Google "Signing you in" sheet.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onSessionRestoreReady(activity: Activity) {
        if (!_sessionRestoreRequested.value) return
        _sessionRestoreRequested.value = false

        viewModelScope.launch {
            val restored = googleAuthManager.restoreSessionSilently()
            if (restored) {
                loadDriveBackups()
            }
        }
    }

    /**
     * Schedule the persisted automatic interval without resetting a healthy
     * existing periodic worker every time this screen is opened.
     */
    private suspend fun ensureAutomaticBackupScheduled(settings: BackupSettings) {
        if (settings.backupInterval == BackupInterval.MANUAL) {
            LocalBackupWorker.cancel(context)
            DriveBackupWorker.cancel(context)
            return
        }

        val workManager = WorkManager.getInstance(context)

        suspend fun hasActiveUniqueWork(name: String): Boolean = withContext(Dispatchers.IO) {
            try {
                workManager.getWorkInfosForUniqueWork(name)
                    .get()
                    .any { info ->
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

        if (LocalBackupStorage.hasSelectedDirectory(context)) {
            if (!hasActiveUniqueWork(LocalBackupWorker.WORK_NAME)) {
                LocalBackupWorker.schedule(context, settings.backupInterval.hours)
            }
        } else {
            LocalBackupWorker.cancel(context)
        }

        if (settings.isGoogleDriveEnabled) {
            if (!hasActiveUniqueWork(DriveBackupWorker.WORK_NAME)) {
                DriveBackupWorker.schedule(
                    context,
                    settings.backupInterval.hours,
                    settings.wifiOnly
                )
            }
        } else {
            DriveBackupWorker.cancel(context)
        }
    }

    /**
     * Apply the current automatic backup settings to two independent workers.
     * Drive retries can therefore never postpone the next device backup period.
     */
    private fun scheduleAutomaticBackups(settings: BackupSettings) {
        if (settings.backupInterval == BackupInterval.MANUAL) {
            LocalBackupWorker.cancel(context)
            DriveBackupWorker.cancel(context)
            return
        }

        if (LocalBackupStorage.hasSelectedDirectory(context)) {
            LocalBackupWorker.schedule(context, settings.backupInterval.hours)
        } else {
            LocalBackupWorker.cancel(context)
        }

        if (settings.isGoogleDriveEnabled) {
            DriveBackupWorker.schedule(
                context,
                settings.backupInterval.hours,
                settings.wifiOnly
            )
        } else {
            DriveBackupWorker.cancel(context)
        }
    }

    private fun calculateStats() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trackCount = database.trackDao().getCount()
                val artistCount = database.artistDao().getCount()
                val eventCount = database.listeningEventDao().getCount()
                
                // Collect local image URLs using targeted queries instead of loading entire tables
                val localImageUrls = mutableSetOf<String>()
                
                localImageUrls.addAll(database.trackDao().getLocalImageUrls())
                localImageUrls.addAll(database.artistDao().getLocalImageUrls())
                localImageUrls.addAll(database.albumDao().getLocalImageUrls())
                database.enrichedMetadataDao().getLocalImageUrls().forEach { url ->
                    if (url.startsWith("file://")) localImageUrls.add(url)
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

                var profileImageSize = 0L
                val profileImagePath = profileIdentityManager.getStoredProfileImagePath()
                if (!profileImagePath.isNullOrBlank() && profileImagePath.startsWith("file://")) {
                    try {
                        val file = File(profileImagePath.removePrefix("file://"))
                        if (file.exists()) {
                            profileImageSize = file.length()
                            localImageSize += profileImageSize
                            localImageCount++
                        }
                    } catch (e: Exception) {
                        // Ignore invalid file paths
                    }
                }
                
                // Estimate export size
                val estimatedJsonSize = (trackCount + artistCount + eventCount) * 500L
                val estimatedTotalSize = estimatedJsonSize + if (_uiState.value.includeLocalImages) {
                    localImageSize
                } else {
                    profileImageSize
                }
                
                _uiState.update { it.copy(
                    trackCount = trackCount,
                    artistCount = artistCount,
                    eventCount = eventCount,
                    localImageCount = localImageCount,
                    localImageSizeBytes = localImageSize,
                    estimatedExportSizeBytes = estimatedTotalSize,
                    isLoading = false
                ) }
            }
        }
    }
    
    fun toggleIncludeLocalImages(include: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[INCLUDE_LOCAL_IMAGES_KEY] = include }
            backupSettingsManager.setIncludeLocalImages(include)
            _uiState.update { it.copy(includeLocalImages = include)
            }
            calculateStats()
        }
    }
    
    // LOCAL BACKUP

    /**
     * Open Android's folder picker for the automatic local-backup destination.
     */
    fun chooseLocalBackupFolder() {
        LocalBackupFolderPickerActivity.launch(context)
    }
    
    /**
     * Export all data to a ZIP file.
     */
    fun exportData(uri: Uri) {
        tracker.track(FeatureUsed(TempoFeature.LOCAL_BACKUP))
        exportDataInternal(uri)
    }

    private fun exportDataInternal(uri: Uri) {
        // Application scope: an export must survive the user navigating away
        // mid-operation; progress is observed via the singleton manager's flow.
        applicationScope.launch {
            val includeImages = _uiState.value.includeLocalImages
            val result = importExportManager.exportData(uri, includeImages)
            _importExportResult.value = result
            // Refresh stats after export
            calculateStats()
        }
    }
    
    /**
     * Start import process - shows conflict resolution dialog only if
     * existing data could conflict. On a fresh install, imports directly.
     */
    fun startImport(uri: Uri) {
        viewModelScope.launch {
            if (hasExistingData()) {
                _showConflictDialog.value = uri
            } else {
                importData(uri, ImportConflictStrategy.REPLACE)
            }
        }
    }

    private suspend fun hasExistingData(): Boolean =
        database.trackDao().getCount() > 0 ||
            database.artistDao().getCount() > 0 ||
            database.listeningEventDao().getCount() > 0 ||
            database.scrobbleArchiveDao().getTotalCount() > 0
    
    /**
     * Proceed with import after user selects conflict strategy.
     */
    fun importData(uri: Uri, strategy: ImportConflictStrategy) {
        _showConflictDialog.value = null
        applicationScope.launch {
            val result = driveHistorySyncManager.withLocalHistoryRestore {
                importExportManager.importData(uri, strategy)
            }
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
        _uiState.update { it.copy(isLoading = true) }
        calculateStats()
    }
    
    // GOOGLE DRIVE BACKUP
    
    /**
     * Request sign in - sets flag for Composable to handle with Activity context.
     */
    fun requestSignIn() {
        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.SigningIn
            )
        ) return
        _signInRequested.value = true
    }
    
    /**
     * Cancel sign in request (e.g., when Activity is unavailable).
     */
    fun cancelSignIn() {
        _signInRequested.value = false
        _driveOperation.compareAndSet(
            DriveOperationState.SigningIn,
            DriveOperationState.Idle
        )
    }
    
    /**
     * Called by the Composable when it has Activity context for sign-in.
     */
    fun onSignInReady(activity: Activity) {
        if (!_signInRequested.value) return
        _signInRequested.value = false
        
        viewModelScope.launch {
            try {
                when (val result = googleAuthManager.signIn(activity)) {
                    is GoogleSignInResult.Success -> {
                        // Folder IDs are account-specific. Drop both the Drive client
                        // and folder cache before any operation under the selected
                        // account, including the no-consent fast path.
                        driveService.clearCache()
                        // Merely authorizing Google must not opt the user into
                        // device backups (history sync is a separate opt-in).
                        // Never carry an old account's backup consent into a
                        // different Google identity.
                        val previousBackup = backupSettingsManager.settings.first()
                        if (!previousBackup.googleAccountEmail.equals(
                                result.account.email, ignoreCase = true
                            )) {
                            backupSettingsManager.setGoogleDriveEnabled(false)
                        }
                        backupSettingsManager.setGoogleAccountEmail(result.account.email)

                        // Google identity sign-in and Drive authorization are separate.
                        // Never mark Drive enabled until a drive.file-scoped access token
                        // actually exists; otherwise a failed/denied consent can leave a
                        // periodic Drive worker scheduled with unusable credentials.
                        if (googleAuthManager.needsDriveConsent.value) {
                            backupSettingsManager.setGoogleDriveEnabled(false)
                            scheduleAutomaticBackups(backupSettingsManager.settings.first())
                            _consentRequested.value = true
                            _driveOperation.value = DriveOperationState.SigningIn
                        } else if (googleAuthManager.getAccessToken() != null) {
                            // Google sign-in authorizes Drive only: backup
                            // uploads start after an explicit opt-in below.
                            scheduleAutomaticBackups(backupSettingsManager.settings.first())
                            refreshDriveBackups()
                                .onSuccess {
                                    _driveOperation.value = DriveOperationState.Idle
                                }
                                .onFailure { error ->
                                    _driveOperation.value = DriveOperationState.Error(
                                        error.message
                                            ?: "Google Drive connected, but backups could not be loaded"
                                    )
                                }
                        } else {
                            backupSettingsManager.setGoogleDriveEnabled(false)
                            scheduleAutomaticBackups(backupSettingsManager.settings.first())
                            _driveOperation.value = DriveOperationState.Error(
                                "Google account connected, but Drive authorization did not complete. Please try signing in again."
                            )
                        }
                    }
                    is GoogleSignInResult.Error -> {
                        _driveOperation.value = DriveOperationState.Error(result.message)
                    }
                    GoogleSignInResult.Cancelled -> {
                        _driveOperation.value = DriveOperationState.Idle
                    }
                }
            } catch (e: CancellationException) {
                _driveOperation.value = DriveOperationState.Idle
                throw e
            } catch (e: Exception) {
                _driveOperation.value = DriveOperationState.Error(
                    e.message ?: "Could not finish Google Drive sign-in"
                )
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
                try {
                    // Complete consent flow to get access token
                    val success = googleAuthManager.completeConsentFlow()
                    if (success) {
                        // Verify that we actually have an access token now
                        val hasToken = googleAuthManager.getAccessToken() != null
                        if (hasToken) {
                            // Do not enable device backups when the user only
                            // connected Google for cross-device music history.
                            driveService.clearCache()
                            scheduleAutomaticBackups(backupSettingsManager.settings.first())
                            refreshDriveBackups()
                                .onSuccess {
                                    _driveOperation.value = DriveOperationState.Idle
                                }
                                .onFailure { error ->
                                    _driveOperation.value = DriveOperationState.Error(
                                        error.message
                                            ?: "Google Drive connected, but backups could not be loaded"
                                    )
                                }
                        } else {
                            backupSettingsManager.setGoogleDriveEnabled(false)
                            scheduleAutomaticBackups(backupSettingsManager.settings.first())
                            if (googleAuthManager.needsDriveConsent.value &&
                                googleAuthManager.getDriveAuthorizationPendingIntent() != null
                            ) {
                                _consentRequested.value = true
                                _driveOperation.value = DriveOperationState.SigningIn
                            } else {
                                _driveOperation.value = DriveOperationState.Error(
                                    "Authorization incomplete. Please try signing out and signing in again."
                                )
                            }
                        }
                    } else {
                        backupSettingsManager.setGoogleDriveEnabled(false)
                        scheduleAutomaticBackups(backupSettingsManager.settings.first())
                        if (googleAuthManager.needsDriveConsent.value &&
                            googleAuthManager.getDriveAuthorizationPendingIntent() != null
                        ) {
                            _consentRequested.value = true
                            _driveOperation.value = DriveOperationState.SigningIn
                        } else {
                            _driveOperation.value = DriveOperationState.Error(
                                "Failed to authorize Google Drive access. Please try again or sign out and sign in again."
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    _driveOperation.value = DriveOperationState.Idle
                    throw e
                } catch (e: Exception) {
                    _driveOperation.value = DriveOperationState.Error(
                        e.message ?: "Could not finish Google Drive authorization"
                    )
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    backupSettingsManager.setGoogleDriveEnabled(false)
                    scheduleAutomaticBackups(backupSettingsManager.settings.first())
                    _driveOperation.value = DriveOperationState.Error(
                        "Google Drive access was denied. Sign out and sign in again when you want to enable Drive backups."
                    )
                } catch (e: CancellationException) {
                    _driveOperation.value = DriveOperationState.Idle
                    throw e
                } catch (e: Exception) {
                    _driveOperation.value = DriveOperationState.Error(
                        e.message ?: "Could not disable Google Drive backups after consent was denied"
                    )
                }
            }
        }
    }
    
    /**
     * Sign out from Google. Automatic device backups keep running when an
     * interval is configured; only the Drive portion is disabled.
     */
    fun signOut() {
        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.Loading
            )
        ) return

        applicationScope.launch {
            try {
                DriveBackupWorker.cancel(context)
                DriveBackupWorker.cancelManual(context)
                googleAuthManager.signOut()
                driveService.clearCache()
                driveService.cleanupDownloadCache()
                backupSettingsManager.setGoogleAccountEmail(null)
                backupSettingsManager.setGoogleDriveEnabled(false)
                _driveBackups.value = emptyList()
                scheduleAutomaticBackups(backupSettingsManager.settings.first())
                _driveOperation.value = DriveOperationState.Idle
            } catch (e: CancellationException) {
                _driveOperation.value = DriveOperationState.Idle
                throw e
            } catch (e: Exception) {
                _driveOperation.value = DriveOperationState.Error(
                    e.message ?: "Could not finish Google sign-out"
                )
            }
        }
    }
    
    /**
     * Load backups from Google Drive.
     */
    fun loadDriveBackups() {
        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.Loading
            )
        ) return

        viewModelScope.launch {
            refreshDriveBackups()
                .onSuccess {
                    _driveOperation.value = DriveOperationState.Idle
                }
                .onFailure { error ->
                    _driveOperation.value = DriveOperationState.Error(
                        error.message ?: "Failed to load backups"
                    )
                }
        }
    }

    private suspend fun refreshDriveBackups(): Result<List<DriveBackupInfo>> {
        val result = driveService.listBackups()
        result.onSuccess { backups -> _driveBackups.value = backups }
        return result
    }

    
    /**
     * Backup to Google Drive NOW.
     */
    /**
     * Reports the run that just finished. A cancelled run leaves the operation Idle, which is
     * not a failure and is deliberately not reported.
     */
    private fun reportDriveBackup(operation: DriveOperationState, sizeBytes: Long, startedAt: Long) {
        val success = when (operation) {
            is DriveOperationState.Success -> true
            is DriveOperationState.Error -> false
            else -> return
        }
        tracker.track(
            BackupRun(
                target = BackupTarget.DRIVE,
                success = success,
                sizeBytes = sizeBytes,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        )
    }

    fun backupToDrive() {
        tracker.track(FeatureUsed(TempoFeature.DRIVE_BACKUP))
        backupToDriveInternal()
    }

    private fun backupToDriveInternal() {
        // Reserve the operation synchronously so rapid taps cannot start two
        // exports that race on UI state or temporary files.
        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.Uploading(0f)
            )
        ) return
        
        applicationScope.launch {
            val settings = backupSettingsManager.settings.first()
            if (!settings.isGoogleDriveEnabled || googleAuthManager.getAccessToken() == null) {
                _driveOperation.value = DriveOperationState.Error(
                    "Google Drive is not authorized. Sign out and sign in again to enable Drive backups."
                )
                return@launch
            }

            val tempFile = File(
                context.cacheDir,
                "temp_drive_backup_${UUID.randomUUID()}.tempo"
            )
            var statusStarted = false
            val startedAt = System.currentTimeMillis()
            
            try {
                updateLastBackupSafely(BackupStatus.IN_PROGRESS)
                statusStarted = true
                
                val exportResult = importExportManager.exportToFile(
                    tempFile,
                    settings.includeLocalImages
                )
                
                if (exportResult is ImportExportResult.Error) {
                    updateLastBackupSafely(BackupStatus.FAILED)
                    _driveOperation.value = DriveOperationState.Error("Failed to create backup: ${exportResult.message}")
                    return@launch
                }
                
                // Upload to Drive
                val uploadResult = driveService.uploadBackup(tempFile) { progress ->
                    _driveOperation.value = DriveOperationState.Uploading(progress)
                }
                
                when (uploadResult) {
                    is DriveBackupResult.Success -> {
                        updateLastBackupSafely(BackupStatus.SUCCESS)
                        refreshDriveBackups()
                        _driveOperation.value = DriveOperationState.Success("Backup uploaded successfully")
                    }
                    is DriveBackupResult.Error -> {
                        updateLastBackupSafely(BackupStatus.FAILED)
                        _driveOperation.value = DriveOperationState.Error(uploadResult.message)
                    }
                }
            } catch (e: CancellationException) {
                _driveOperation.value = DriveOperationState.Idle
                throw e
            } catch (e: Exception) {
                if (statusStarted) updateLastBackupSafely(BackupStatus.FAILED)
                _driveOperation.value = DriveOperationState.Error("Backup failed: ${e.message}")
            } finally {
                // Always cleanup temp file
                // Captured before deletion, and reported once here rather than at each of the
                // four exit paths above so a run can never be counted twice.
                val uploadedBytes = if (tempFile.exists()) tempFile.length() else 0L
                reportDriveBackup(_driveOperation.value, uploadedBytes, startedAt)
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }
    
    /**
     * Start restore from Drive - shows confirmation dialog only if
     * existing data could conflict. On a fresh install, restores directly.
     */
    fun startDriveRestore(backup: DriveBackupInfo) {
        viewModelScope.launch {
            if (hasExistingData()) {
                _showDriveRestoreDialog.value = backup
            } else {
                restoreFromDrive(backup, ImportConflictStrategy.REPLACE)
            }
        }
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

        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.Downloading(0f)
            )
        ) return
        
        applicationScope.launch {
            try {
                // Download backup
                val downloadResult = driveService.downloadBackup(backup.fileId) { progress ->
                    _driveOperation.value = DriveOperationState.Downloading(progress)
                }
                
                when (downloadResult) {
                    is DriveRestoreResult.Success -> {
                        _driveOperation.value = DriveOperationState.Restoring

                        val importResult = try {
                            driveHistorySyncManager.withLocalHistoryRestore {
                                importExportManager.importData(
                                    Uri.fromFile(downloadResult.localFile),
                                    strategy
                                )
                            }
                        } finally {
                            downloadResult.localFile.delete()
                        }
                        when (importResult) {
                            is ImportExportResult.Success -> {
                                calculateStats()
                                refreshDriveBackups()
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
            } catch (e: CancellationException) {
                _driveOperation.value = DriveOperationState.Idle
                throw e
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
        if (!_driveOperation.compareAndSet(
                DriveOperationState.Idle,
                DriveOperationState.Loading
            )
        ) return

        viewModelScope.launch {
            val success = driveService.deleteBackup(backup.fileId)
            if (success) {
                refreshDriveBackups()
                    .onSuccess {
                        _driveOperation.value = DriveOperationState.Idle
                    }
                    .onFailure { error ->
                        _driveOperation.value = DriveOperationState.Error(
                            error.message ?: "Backup deleted, but the list could not be refreshed"
                        )
                    }
            } else {
                _driveOperation.value = DriveOperationState.Error("Failed to delete backup")
            }
        }
    }
    
    /**
     * Device backups require their own opt-in, independent from authentication
     * and the Google Drive listening-history transport.
     */
    fun setDriveBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && (!googleAuthManager.isSignedIn.value ||
                    googleAuthManager.getAccessToken() == null)) {
                _driveOperation.value = DriveOperationState.Error(
                    "Connect and authorize Google Drive before enabling device backups."
                )
                return@launch
            }
            backupSettingsManager.setGoogleDriveEnabled(enabled)
            scheduleAutomaticBackups(backupSettingsManager.settings.first())
        }
    }

    /**
     * Set backup interval and schedule the automatic device/Drive worker.
     */
    fun setBackupInterval(interval: BackupInterval) {
        viewModelScope.launch {
            if (interval != BackupInterval.MANUAL &&
                !LocalBackupStorage.hasSelectedDirectory(context)
            ) {
                LocalBackupFolderPickerActivity.launch(context, requestedInterval = interval)
                return@launch
            }

            backupSettingsManager.setBackupInterval(interval)
            scheduleAutomaticBackups(backupSettingsManager.settings.first())
        }
    }
    
    /**
     * Set Wi-Fi only preference and update Drive constraints without disabling
     * the device-copy portion of automatic backups.
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            backupSettingsManager.setWifiOnly(wifiOnly)
            scheduleAutomaticBackups(backupSettingsManager.settings.first())
        }
    }
    
    /**
     * Clear drive operation state.
     */
    fun clearDriveOperation() {
        _driveOperation.value = DriveOperationState.Idle
    }

    private suspend fun updateLastBackupSafely(status: BackupStatus) {
        try {
            backupSettingsManager.updateLastBackup(status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Preserve the original backup failure in the operation state even if
            // persisting the secondary status update is temporarily unavailable.
        }
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
