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
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.worker.NotificationWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val importExportManager: ImportExportManager,
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

    // Keys
    private val NOTIF_DAILY_KEY = booleanPreferencesKey("notif_daily_summary")
    private val NOTIF_WEEKLY_KEY = booleanPreferencesKey("notif_weekly_recap")
    private val NOTIF_ACHIEVEMENTS_KEY = booleanPreferencesKey("notif_achievements")
    private val EXTENDED_AUDIO_ANALYSIS_KEY = booleanPreferencesKey("extended_audio_analysis")
    private val USER_NAME_KEY = androidx.datastore.preferences.core.stringPreferencesKey("user_name")

    init {
        viewModelScope.launch {
            // Load DataStore preferences
            val dataStorePrefs = context.dataStore.data.first()
            
            // Load Room preferences
            val roomPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            
            _uiState.value = SettingsUiState(
                dailySummaryEnabled = dataStorePrefs[NOTIF_DAILY_KEY] ?: true,
                weeklyRecapEnabled = dataStorePrefs[NOTIF_WEEKLY_KEY] ?: true,
                achievementsEnabled = dataStorePrefs[NOTIF_ACHIEVEMENTS_KEY] ?: true,
                extendedAudioAnalysisEnabled = dataStorePrefs[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false,
                userName = dataStorePrefs[USER_NAME_KEY] ?: "User",
                mergeAlternateVersions = roomPrefs.mergeAlternateVersions,
                filterPodcasts = roomPrefs.filterPodcasts,
                filterAudiobooks = roomPrefs.filterAudiobooks,
                spotifyApiOnlyMode = roomPrefs.spotifyApiOnlyMode,
                isLastFmConnected = roomPrefs.lastfmConnected,
                lastFmUsername = roomPrefs.lastfmUsername,
                lastFmSyncFrequency = roomPrefs.lastfmSyncFrequency
            )
        }
        
        // Watch DataStore for updates
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                _uiState.value = _uiState.value.copy(
                    dailySummaryEnabled = preferences[NOTIF_DAILY_KEY] ?: true,
                    weeklyRecapEnabled = preferences[NOTIF_WEEKLY_KEY] ?: true,
                    achievementsEnabled = preferences[NOTIF_ACHIEVEMENTS_KEY] ?: true,
                    extendedAudioAnalysisEnabled = preferences[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false,
                    userName = preferences[USER_NAME_KEY] ?: "User"
                )
            }
        }
        
        // Watch Room preferences for updates (Last.fm state, filters, etc.)
        viewModelScope.launch {
            userPreferencesDao.preferences().collect { roomPrefs ->
                val prefs = roomPrefs ?: me.avinas.tempo.data.local.entities.UserPreferences()
                _uiState.value = _uiState.value.copy(
                    mergeAlternateVersions = prefs.mergeAlternateVersions,
                    filterPodcasts = prefs.filterPodcasts,
                    filterAudiobooks = prefs.filterAudiobooks,
                    spotifyApiOnlyMode = prefs.spotifyApiOnlyMode,
                    isLastFmConnected = prefs.lastfmConnected,
                    lastFmUsername = prefs.lastfmUsername,
                    lastFmSyncFrequency = prefs.lastfmSyncFrequency
                )
            }
        }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[USER_NAME_KEY] = name }
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
    
    fun toggleExtendedAudioAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[EXTENDED_AUDIO_ANALYSIS_KEY] = enabled }
        }
    }
    
    fun toggleMergeAlternateVersions(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(mergeAlternateVersions = enabled))
            // Update UI state immediately
            _uiState.value = _uiState.value.copy(mergeAlternateVersions = enabled)
        }
    }
    
    fun toggleFilterPodcasts(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(filterPodcasts = enabled))
            _uiState.value = _uiState.value.copy(filterPodcasts = enabled)
        }
    }
    
    fun toggleFilterAudiobooks(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(filterAudiobooks = enabled))
            _uiState.value = _uiState.value.copy(filterAudiobooks = enabled)
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
            _uiState.value = _uiState.value.copy(spotifyApiOnlyMode = enabled)
            
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
}

data class SettingsUiState(
    val dailySummaryEnabled: Boolean = true,
    val weeklyRecapEnabled: Boolean = true,
    val achievementsEnabled: Boolean = true,
    val extendedAudioAnalysisEnabled: Boolean = false,
    val isSpotifyConnected: Boolean = false,
    val spotifyUsername: String? = null,
    val userName: String = "User",
    val mergeAlternateVersions: Boolean = true,
    val filterPodcasts: Boolean = true,
    val filterAudiobooks: Boolean = true,
    val spotifyApiOnlyMode: Boolean = false,
    // Last.fm connection state
    val isLastFmConnected: Boolean = false,
    val lastFmUsername: String? = null,
    val lastFmSyncFrequency: String = "NONE"
)
