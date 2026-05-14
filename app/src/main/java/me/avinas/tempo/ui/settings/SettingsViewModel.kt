package me.avinas.tempo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportProgress
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.GamificationWorker
import me.avinas.tempo.worker.NotificationWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.Immutable

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val importExportManager: ImportExportManager,
    private val profileIdentityManager: ProfileIdentityManager,
    private val userPreferencesDao: me.avinas.tempo.data.local.dao.UserPreferencesDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    // Import/Export states
    val importExportProgress: StateFlow<ImportExportProgress?> = importExportManager.progress
    
    private val _importExportResult = MutableStateFlow<ImportExportResult?>(null)
    val importExportResult: StateFlow<ImportExportResult?> = _importExportResult.asStateFlow()
    
    private val _showConflictDialog = MutableStateFlow<Uri?>(null)
    val showConflictDialog: StateFlow<Uri?> = _showConflictDialog.asStateFlow()

    private val _profileImageMessage = MutableStateFlow<String?>(null)
    val profileImageMessage: StateFlow<String?> = _profileImageMessage.asStateFlow()

    // Keys
    private val NOTIF_DAILY_KEY = booleanPreferencesKey("notif_daily_summary")
    private val NOTIF_WEEKLY_KEY = booleanPreferencesKey("notif_weekly_recap")
    private val NOTIF_ACHIEVEMENTS_KEY = booleanPreferencesKey("notif_achievements")
    private val NOTIF_CHALLENGES_KEY = booleanPreferencesKey("notif_daily_challenges")
    private val EXTENDED_AUDIO_ANALYSIS_KEY = booleanPreferencesKey("extended_audio_analysis")
    private val USER_NAME_KEY = androidx.datastore.preferences.core.stringPreferencesKey("user_name")

    init {
        viewModelScope.launch {
            // Load DataStore preferences
            val dataStorePrefs = context.dataStore.data.first()
            
            // Load Room preferences
            val roomPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            
            _uiState.update {
                SettingsUiState(
                dailySummaryEnabled = dataStorePrefs[NOTIF_DAILY_KEY] ?: true,
                weeklyRecapEnabled = dataStorePrefs[NOTIF_WEEKLY_KEY] ?: true,
                achievementsEnabled = dataStorePrefs[NOTIF_ACHIEVEMENTS_KEY] ?: true,
                dailyChallengesEnabled = dataStorePrefs[NOTIF_CHALLENGES_KEY] ?: true,
                extendedAudioAnalysisEnabled = dataStorePrefs[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false,
                userName = dataStorePrefs[USER_NAME_KEY] ?: "User",
                profileImagePath = profileIdentityManager.getStoredProfileImagePath(),
                mergeAlternateVersions = roomPrefs.mergeAlternateVersions,
                filterPodcasts = roomPrefs.filterPodcasts,
                filterAudiobooks = roomPrefs.filterAudiobooks,
                spotifyApiOnlyMode = roomPrefs.spotifyApiOnlyMode,
                isLastFmConnected = roomPrefs.lastfmConnected,
                lastFmUsername = roomPrefs.lastfmUsername,
                lastFmSyncFrequency = roomPrefs.lastfmSyncFrequency,
                isGamificationEnabled = roomPrefs.isGamificationEnabled,
                pauseTrackingOnLowBattery = roomPrefs.pauseTrackingOnLowBattery
                )
            }
        }
        
        // Watch DataStore for updates
        viewModelScope.launch {
            profileIdentityManager.profileIdentity.collect { identity ->
                _uiState.update { it.copy(
                    userName = identity.userName,
                    profileImagePath = identity.profileImagePath
                ) }
            }
        }

        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                _uiState.update { it.copy(
                    dailySummaryEnabled = preferences[NOTIF_DAILY_KEY] ?: true,
                    weeklyRecapEnabled = preferences[NOTIF_WEEKLY_KEY] ?: true,
                    achievementsEnabled = preferences[NOTIF_ACHIEVEMENTS_KEY] ?: true,
                    dailyChallengesEnabled = preferences[NOTIF_CHALLENGES_KEY] ?: true,
                    extendedAudioAnalysisEnabled = preferences[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false
                ) }
            }
        }
        
        // Watch Room preferences for updates (Last.fm state, filters, etc.)
        viewModelScope.launch {
            userPreferencesDao.preferences().collect { roomPrefs ->
                val prefs = roomPrefs ?: me.avinas.tempo.data.local.entities.UserPreferences()
                _uiState.update { it.copy(
                    mergeAlternateVersions = prefs.mergeAlternateVersions,
                    filterPodcasts = prefs.filterPodcasts,
                    filterAudiobooks = prefs.filterAudiobooks,
                    spotifyApiOnlyMode = prefs.spotifyApiOnlyMode,
                    isLastFmConnected = prefs.lastfmConnected,
                    lastFmUsername = prefs.lastfmUsername,
                    lastFmSyncFrequency = prefs.lastfmSyncFrequency,
                    isGamificationEnabled = prefs.isGamificationEnabled,
                    pauseTrackingOnLowBattery = prefs.pauseTrackingOnLowBattery
                ) }
            }
        }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch {
            profileIdentityManager.updateUserName(name)
        }
    }

    fun updateProfileImage(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                profileIdentityManager.updateProfileImage(uri)
            }.onSuccess {
                _profileImageMessage.value = "Profile photo updated"
            }.onFailure { error ->
                _profileImageMessage.value = error.message ?: "Failed to update profile photo"
            }
        }
    }

    fun removeProfileImage() {
        viewModelScope.launch {
            runCatching {
                profileIdentityManager.clearProfileImage()
            }.onSuccess {
                _profileImageMessage.value = "Profile photo removed"
            }.onFailure { error ->
                _profileImageMessage.value = error.message ?: "Failed to remove profile photo"
            }
        }
    }

    fun toggleDailySummary(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[NOTIF_DAILY_KEY] = enabled }
            if (enabled) {
                NotificationWorker.scheduleDaily(context)
            } else {
                NotificationWorker.cancelDaily(context)
            }
        }
    }

    fun toggleWeeklyRecap(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[NOTIF_WEEKLY_KEY] = enabled }
            if (enabled) {
                NotificationWorker.scheduleWeekly(context)
            } else {
                NotificationWorker.cancelWeekly(context)
            }
        }
    }

    fun toggleAchievements(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[NOTIF_ACHIEVEMENTS_KEY] = enabled }
        }
    }
    
    fun toggleDailyChallenges(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[NOTIF_CHALLENGES_KEY] = enabled }
            if (enabled) {
                // Re-enable: schedule a notification for today at the stored smart hour,
                // falling back to 9 AM if no smart hour has been computed yet.
                val storedHour = userPreferencesDao.getSync()?.smartChallengeNotifHour ?: 9
                NotificationWorker.scheduleChallengeReady(context, storedHour)
            } else {
                // Disabled: cancel any pending challenge notification
                NotificationWorker.cancelChallengeReady(context)
            }
        }
    }
    
    fun toggleExtendedAudioAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[EXTENDED_AUDIO_ANALYSIS_KEY] = enabled }
        }
    }
    
    fun toggleGamificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(isGamificationEnabled = enabled))
            _uiState.update { it.copy(isGamificationEnabled = enabled) }
            if (!enabled) {
                // If gamification is completely disabled, cancel workers that compute XP/challenges
                NotificationWorker.cancelChallengeReady(context)
                ChallengeWorker.cancelDaily(context)
                GamificationWorker.cancelPeriodicRefresh(context)
            } else {
                // Re-enable workers
                ChallengeWorker.scheduleDaily(context)
                GamificationWorker.enqueuePeriodicRefresh(context)
            }
        }
    }
    
    fun toggleMergeAlternateVersions(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(mergeAlternateVersions = enabled))
            _uiState.update { it.copy(mergeAlternateVersions = enabled) }
        }
    }
    
    fun toggleFilterPodcasts(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(filterPodcasts = enabled))
            _uiState.update { it.copy(filterPodcasts = enabled) }
        }
    }
    
    fun toggleFilterAudiobooks(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(filterAudiobooks = enabled))
            _uiState.update { it.copy(filterAudiobooks = enabled) }
        }
    }
    
    /**
     * Toggle whether music tracking pauses automatically when battery drops ≤ 20%.
     * Persists to Room so the setting survives app restarts.
     */
    fun togglePauseTrackingOnLowBattery(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(pauseTrackingOnLowBattery = enabled))
            _uiState.update { it.copy(pauseTrackingOnLowBattery = enabled) }
        }
    }
    
    /**
     * Toggle Spotify-API-Only mode.
     * When enabled: Spotify listening data is fetched from API instead of notifications.
     * When disabled (default): All apps including Spotify use notification tracking.
     */
    fun toggleSpotifyApiOnlyMode(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(spotifyApiOnlyMode = enabled))
            _uiState.update { it.copy(spotifyApiOnlyMode = enabled) }
            
            // Schedule or cancel the polling worker based on mode
            if (enabled) {
                SpotifyPollingWorker.schedule(context)
            } else {
                SpotifyPollingWorker.cancel(context)
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.clearAllTables()
            }
            profileIdentityManager.clearProfileImage()
            context.dataStore.edit { it.clear() }
            NotificationWorker.cancelDaily(context)
            NotificationWorker.cancelWeekly(context)
        }
    }
    
    /**
     * Export all data to a ZIP file.
     */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            val result = importExportManager.exportData(uri)
            _importExportResult.value = result
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

    fun clearProfileImageMessage() {
        _profileImageMessage.value = null
    }
}

@Immutable
data class SettingsUiState(
    val dailySummaryEnabled: Boolean = true,
    val weeklyRecapEnabled: Boolean = true,
    val achievementsEnabled: Boolean = true,
    val dailyChallengesEnabled: Boolean = true,
    val extendedAudioAnalysisEnabled: Boolean = false,
    val isSpotifyConnected: Boolean = false,
    val spotifyUsername: String? = null,
    val userName: String = "User",
    val profileImagePath: String? = null,
    val mergeAlternateVersions: Boolean = true,
    val filterPodcasts: Boolean = true,
    val filterAudiobooks: Boolean = true,
    val spotifyApiOnlyMode: Boolean = false,
    // Last.fm connection state
    val isLastFmConnected: Boolean = false,
    val lastFmUsername: String? = null,
    val lastFmSyncFrequency: String = "NONE",
    // Gamification state
    val isGamificationEnabled: Boolean = true,
    // Battery saver tracking pause
    val pauseTrackingOnLowBattery: Boolean = true
)
