package me.avinas.tempo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.analytics.AnalyticsConsent
import me.avinas.tempo.data.analytics.AnalyticsDefaults
import me.avinas.tempo.data.analytics.AnalyticsGate
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportProgress
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.enrichment.CoverArtSourcePreference
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.GamificationWorker
import me.avinas.tempo.worker.NotificationWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val database: AppDatabase,
        private val importExportManager: ImportExportManager,
        private val profileIdentityManager: ProfileIdentityManager,
        private val userPreferencesDao: me.avinas.tempo.data.local.dao.UserPreferencesDao,
        private val analyticsConsent: AnalyticsConsent,
        private val analyticsInstallId: me.avinas.tempo.data.analytics.AnalyticsInstallId,
        private val analyticsDailyLedger: me.avinas.tempo.data.analytics.AnalyticsDailyLedger,
        private val analyticsFeatureLedger: me.avinas.tempo.data.analytics.AnalyticsFeatureLedger,
        private val coverArtSourcePreference: CoverArtSourcePreference,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(
            SettingsUiState(
                analyticsConfigured = AnalyticsGate.isBuildConfigured(
                    endpoint = BuildConfig.ANALYTICS_ENDPOINT,
                    isDebug = BuildConfig.DEBUG,
                    debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
                ),
            ),
        )
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

        init {
            viewModelScope.launch {
                combine(
                    profileIdentityManager.profileIdentity,
                    context.dataStore.data,
                    coverArtSourcePreference.preferredSource,
                    analyticsConsent.isEnabled,
                    userPreferencesDao.preferences(),
                ) { identity, dataStorePrefs, preferredCover, analyticsEnabled, roomPrefs ->
                    val prefs =
                        roomPrefs ?: me.avinas.tempo.data.local.entities
                            .UserPreferences()
                    SettingsUiState(
                        dailySummaryEnabled = dataStorePrefs[NOTIF_DAILY_KEY] ?: true,
                        weeklyRecapEnabled = dataStorePrefs[NOTIF_WEEKLY_KEY] ?: true,
                        achievementsEnabled = dataStorePrefs[NOTIF_ACHIEVEMENTS_KEY] ?: true,
                        dailyChallengesEnabled = dataStorePrefs[NOTIF_CHALLENGES_KEY] ?: true,
                        extendedAudioAnalysisEnabled = dataStorePrefs[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false,
                        preferredCoverSource = preferredCover,
                        userName = identity.userName,
                        profileImagePath = identity.profileImagePath,
                        mergeAlternateVersions = prefs.mergeAlternateVersions,
                        filterPodcasts = prefs.filterPodcasts,
                        filterAudiobooks = prefs.filterAudiobooks,
                        spotifyApiOnlyMode = prefs.spotifyApiOnlyMode,
                        isLastFmConnected = prefs.lastfmConnected,
                        lastFmUsername = prefs.lastfmUsername,
                        lastFmSyncFrequency = prefs.lastfmSyncFrequency,
                        isGamificationEnabled = prefs.isGamificationEnabled,
                        hideStreaks = prefs.hideStreaks,
                        pauseTrackingOnLowBattery = prefs.pauseTrackingOnLowBattery,
                        analyticsEnabled = analyticsEnabled,
                        analyticsConfigured = _uiState.value.analyticsConfigured,
                    )
                }.collect { combinedState ->
                    _uiState.update { current ->
                        combinedState.copy(analyticsIdResetAt = current.analyticsIdResetAt)
                    }
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

        fun setPreferredCoverSource(source: AlbumArtSource?) {
            viewModelScope.launch {
                coverArtSourcePreference.setPreferredSource(source)
                // Requeue rows that still hold art from another automatic provider so the
                // new preference can win. Returning to Automatic needs the same sweep: it
                // hands arbitration back to the fixed priority order, which may well pick
                // a different cover than the one the old preference pinned.
                val dao = database.enrichedMetadataDao()
                if (source != null) {
                    dao.requeueTracksNotMatchingCoverSource(source)
                } else {
                    dao.requeueAllAutomaticArtworkSources()
                }
                // Requeuing only flips rows to PENDING, so the preference does nothing until
                // something drains them. A plain immediate run is not enough: it processes one
                // 10-track batch, while the periodic worker only picks up 10 more per hour —
                // a library-wide preference change would take weeks to apply. The Enrich All
                // sweep is the existing mechanism that drains a backlog to empty.
                val pending = dao.countByStatus(EnrichmentStatus.PENDING)
                if (pending > 0) {
                    EnrichmentWorker.enqueueEnrichAll(context, pending)
                }
            }
        }

        fun toggleExtendedAudioAnalysis(enabled: Boolean) {
            viewModelScope.launch {
                context.dataStore.edit { it[EXTENDED_AUDIO_ANALYSIS_KEY] = enabled }
            }
        }

        /**
         * Turns anonymous app-health reporting on or off.
         *
         * Only the flag is written here. The app-level observer in `TempoApplication`
         * reacts to every change of it — from here, from the Home disclosure card, from a
         * data wipe — by purging anything buffered, cancelling the uploader when off and
         * re-registering it when on, so opting out takes effect immediately everywhere.
         */
        fun setAnalyticsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                analyticsConsent.setEnabled(enabled)
                _uiState.update { it.copy(analyticsEnabled = enabled) }
            }
        }

        /**
         * Replaces the pseudonymous install id and forgets the daily reporting bookkeeping.
         *
         * This is the user-facing half of the promise in `docs/ANALYTICS.md`: everything
         * recorded under the old id becomes unlinkable to anything sent afterwards.
         *
         * No queue purge is needed, and that is a property worth keeping. The install id is
         * attached when a batch is *sent*, not when an event is enqueued, so rotating it
         * unlinks events already sitting in the queue — they simply go out under the new id.
         * Buffered-but-unsent events are only deleted when reporting is turned off.
         */
        fun resetAnalyticsId() {
            viewModelScope.launch {
                runCatching {
                    analyticsInstallId.reset()
                    analyticsDailyLedger.clear()
                    // The new id inherits no history, so feature adoption starts over too.
                    // Leaving the set behind would let an install inherit the previous one's
                    // "already reported" state, which is exactly the link this breaks.
                    analyticsFeatureLedger.clear()
                }
                _uiState.update { it.copy(analyticsIdResetAt = System.currentTimeMillis()) }
            }
        }

        /** Called once the reset has been acknowledged, so the confirmation does not repeat. */
        fun clearAnalyticsIdReset() {
            _uiState.update { it.copy(analyticsIdResetAt = null) }
        }

        fun toggleGamificationEnabled(enabled: Boolean) {
            viewModelScope.launch {
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
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

        /**
         * Turns the display of listening streaks on or off.
         *
         * Only the preference is written here. Every streak surface reads it, so the change
         * applies on the next recomposition without clearing caches or recomputing statistics.
         */
        fun toggleHideStreaks(enabled: Boolean) {
            viewModelScope.launch {
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
                userPreferencesDao.upsert(currentPrefs.copy(hideStreaks = enabled))
                _uiState.update { it.copy(hideStreaks = enabled) }
            }
        }

        fun toggleMergeAlternateVersions(enabled: Boolean) {
            viewModelScope.launch {
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
                userPreferencesDao.upsert(currentPrefs.copy(mergeAlternateVersions = enabled))
                _uiState.update { it.copy(mergeAlternateVersions = enabled) }
            }
        }

        fun toggleFilterPodcasts(enabled: Boolean) {
            viewModelScope.launch {
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
                userPreferencesDao.upsert(currentPrefs.copy(filterPodcasts = enabled))
                _uiState.update { it.copy(filterPodcasts = enabled) }
            }
        }

        fun toggleFilterAudiobooks(enabled: Boolean) {
            viewModelScope.launch {
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
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
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
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
                val currentPrefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
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
         * Start import process - shows conflict resolution dialog only if
         * existing data could conflict. On a fresh install, imports directly.
         */
        fun startImport(uri: Uri) {
            viewModelScope.launch {
                val hasExistingData =
                    database.trackDao().getCount() > 0 ||
                        database.artistDao().getCount() > 0 ||
                        database.listeningEventDao().getCount() > 0
                if (hasExistingData) {
                    _showConflictDialog.value = uri
                } else {
                    importData(uri, ImportConflictStrategy.REPLACE)
                }
            }
        }

        /**
         * Proceed with import after user selects conflict strategy.
         */
        fun importData(
            uri: Uri,
            strategy: ImportConflictStrategy,
        ) {
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
    val preferredCoverSource: AlbumArtSource? = null,
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
    // Listening streaks hidden from the interface at the user's request
    val hideStreaks: Boolean = false,
    // Battery saver tracking pause
    val pauseTrackingOnLowBattery: Boolean = true,
    // Anonymous app-health reporting. `analyticsConfigured` is a build constant: builds
    // without an analytics endpoint (e.g. compiled from source) never show the controls,
    // because offering a switch for collection that cannot happen would be misleading.
    val analyticsEnabled: Boolean = AnalyticsDefaults.ENABLED,
    val analyticsConfigured: Boolean = false,
    /**
     * Set when the user resets their analytics id, purely so the UI can confirm it happened.
     * Never sent anywhere — a local acknowledgement, not an event.
     */
    val analyticsIdResetAt: Long? = null,
)
