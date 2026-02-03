package me.avinas.tempo.ui.lastfm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.worker.LastFmImportWorker
import javax.inject.Inject

/**
 * ViewModel for Last.fm import UI.
 */
@HiltViewModel
class LastFmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lastFmImportService: LastFmImportService,
    private val userPreferencesDao: UserPreferencesDao
) : ViewModel() {

    companion object {
        private const val TAG = "LastFmViewModel"
    }

    // UI State
    private val _uiState = MutableStateFlow(LastFmUiState())
    val uiState: StateFlow<LastFmUiState> = _uiState.asStateFlow()

    // Import progress from service
    val importProgress = lastFmImportService.progress

    init {
        loadConnectionState()
    }

    /**
     * Load the current Last.fm connection state from preferences.
     */
    private fun loadConnectionState() {
        viewModelScope.launch {
            val prefs = userPreferencesDao.getSync() ?: UserPreferences()
            _uiState.value = _uiState.value.copy(
                isConnected = prefs.lastfmConnected,
                username = prefs.lastfmUsername,
                syncFrequency = prefs.lastfmSyncFrequency ?: "NONE"
            )
        }
    }

    /**
     * Discover a Last.fm user's account info.
     */
    fun discoverUser(username: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null
            )

            val result = lastFmImportService.discoverUser(username)

            result.fold(
                onSuccess = { discovery ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        discoveryResult = discovery,
                        inputUsername = username,
                        showTierSelection = true
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to connect to Last.fm"
                    )
                }
            )
        }
    }

    /**
     * Start the import with selected tier.
     */
    fun startImport(tier: LastFmImportService.TierConfig) {
        val discovery = _uiState.value.discoveryResult ?: return
        val username = _uiState.value.inputUsername ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isImporting = true,
                selectedTier = tier,
                showTierSelection = false,
                error = null
            )

            // Start import via WorkManager for background support
            LastFmImportWorker.enqueueInitialImport(
                context = context,
                username = username,
                tier = tier.name,
                totalScrobbles = discovery.totalScrobbles
            )

            Log.i(TAG, "Enqueued Last.fm import for $username with tier ${tier.name}")
        }
    }

    /**
     * Start import directly (not via worker, for immediate feedback).
     */
    fun startImportDirect(tier: LastFmImportService.TierConfig) {
        val discovery = _uiState.value.discoveryResult ?: return
        val username = _uiState.value.inputUsername ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isImporting = true,
                selectedTier = tier,
                showTierSelection = false,
                error = null
            )

            val result = lastFmImportService.startImport(
                username = username,
                tier = tier,
                totalScrobbles = discovery.totalScrobbles
            )

            result.fold(
                onSuccess = { importResult ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        isConnected = true,
                        username = username,
                        importResult = importResult
                    )
                    // Refresh connection state
                    loadConnectionState()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        error = error.message ?: "Import failed"
                    )
                }
            )
        }
    }

    /**
     * Disconnect Last.fm (doesn't delete data).
     */
    fun disconnect() {
        viewModelScope.launch {
            val prefs = userPreferencesDao.getSync() ?: UserPreferences()
            userPreferencesDao.upsert(
                prefs.copy(
                    lastfmConnected = false,
                    lastfmUsername = null,
                    lastfmSyncFrequency = "NONE"
                )
            )

            // Cancel any scheduled sync
            LastFmImportWorker.cancelIncrementalSync(context)

            _uiState.value = _uiState.value.copy(
                isConnected = false,
                username = null,
                syncFrequency = "NONE"
            )
        }
    }

    /**
     * Update sync frequency setting.
     */
    fun setSyncFrequency(frequency: String) {
        viewModelScope.launch {
            val prefs = userPreferencesDao.getSync() ?: UserPreferences()
            userPreferencesDao.upsert(prefs.copy(lastfmSyncFrequency = frequency))

            if (frequency != "NONE") {
                LastFmImportWorker.scheduleIncrementalSync(context, frequency)
            } else {
                LastFmImportWorker.cancelIncrementalSync(context)
            }

            _uiState.value = _uiState.value.copy(syncFrequency = frequency)
        }
    }

    /**
     * Trigger manual sync.
     */
    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)

            val result = lastFmImportService.syncNewScrobbles()

            result.fold(
                onSuccess = { count ->
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncResult = "Synced $count new scrobbles"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        error = error.message
                    )
                }
            )
        }
    }

    /**
     * Get archive statistics.
     */
    fun loadArchiveStats() {
        viewModelScope.launch {
            val stats = lastFmImportService.getArchiveStats()
            _uiState.value = _uiState.value.copy(archiveStats = stats)
        }
    }

    /**
     * Cancel current import.
     */
    fun cancelImport() {
        lastFmImportService.cancelImport()
        LastFmImportWorker.cancelAll(context)
        _uiState.value = _uiState.value.copy(
            isImporting = false,
            showTierSelection = false
        )
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Clear sync result message.
     */
    fun clearSyncResult() {
        _uiState.value = _uiState.value.copy(lastSyncResult = null)
    }

    /**
     * Reset to initial state (for starting over).
     */
    fun reset() {
        _uiState.value = LastFmUiState(
            isConnected = _uiState.value.isConnected,
            username = _uiState.value.username,
            syncFrequency = _uiState.value.syncFrequency
        )
    }
}

/**
 * UI state for Last.fm screens.
 */
data class LastFmUiState(
    // Connection state
    val isConnected: Boolean = false,
    val username: String? = null,
    val syncFrequency: String = "NONE",

    // Discovery state
    val isLoading: Boolean = false,
    val inputUsername: String? = null,
    val discoveryResult: LastFmImportService.DiscoveryResult? = null,
    val showTierSelection: Boolean = false,

    // Import state
    val isImporting: Boolean = false,
    val selectedTier: LastFmImportService.TierConfig? = null,
    val importResult: LastFmImportService.ImportResult? = null,

    // Sync state
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,

    // Archive stats
    val archiveStats: LastFmImportService.ArchiveStats? = null,

    // Error state
    val error: String? = null
)
