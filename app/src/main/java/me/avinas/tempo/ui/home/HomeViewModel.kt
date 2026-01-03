package me.avinas.tempo.ui.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.onboarding.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val tokenStorage: me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            statsRepository.observeListeningOverview(_uiState.value.selectedTimeRange)
                .collect { overview ->
                    _uiState.update { it.copy(listeningOverview = overview, hasData = overview.totalPlayCount > 0) }
                    // Refresh other data when overview changes
                    loadData()
                }
        }
        
        // Also observe metadata updates (album art, artist images) from enrichment
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates()
                .collect {
                    // Refresh data to pick up new album art, artist images, etc.
                    loadData()
                }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.update { it.copy(selectedTimeRange = timeRange, isLoading = true) }
        loadData()
    }

    suspend fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        fetchData()
    }

    private fun loadData() {
        viewModelScope.launch {
            fetchData()
        }
    }

    private suspend fun fetchData() {
        try {
            val timeRange = _uiState.value.selectedTimeRange
            
            // Fetch all required data in PARALLEL using async/await
            // This reduces total loading time from sum of all calls to max of all calls
            coroutineScope {
                val overviewDeferred = async { statsRepository.getListeningOverview(timeRange) }
                val periodComparisonDeferred = async { statsRepository.getPeriodComparison(timeRange) }
                val rawDailyListeningDeferred = async { statsRepository.getDailyListening(timeRange, 7) }
                val topTracksDeferred = async { statsRepository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1) }
                val topArtistsDeferred = async { statsRepository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1) }
                val discoveryStatsDeferred = async { statsRepository.getDiscoveryStats(timeRange) }
                val mostActiveHourDeferred = async { statsRepository.getMostActiveHour(timeRange) }
                val audioFeaturesDeferred = async { statsRepository.getAudioFeaturesStats(timeRange) }
                val insightsDeferred = async { statsRepository.getInsights(timeRange) }
                val userNameDeferred = async { 
                    val preferences = context.dataStore.data.first()
                    val savedName = preferences[stringPreferencesKey("user_name")]
                    if (!savedName.isNullOrBlank()) {
                        savedName
                    } else {
                        tokenStorage.getUserDisplayName()?.split(" ")?.firstOrNull() ?: "User" 
                    }
                }
                
                // Await all results
                val overview = overviewDeferred.await()
                val periodComparison = periodComparisonDeferred.await()
                val rawDailyListening = rawDailyListeningDeferred.await()
                val topTracks = topTracksDeferred.await()
                val topArtists = topArtistsDeferred.await()
                val discoveryStats = discoveryStatsDeferred.await()
                val mostActiveHour = mostActiveHourDeferred.await()
                val audioFeatures = audioFeaturesDeferred.await()
                val insights = insightsDeferred.await()
                val userName = userNameDeferred.await()
                
                // Ensure we have exactly 7 days of data, filling missing days with 0
                val dailyListening = if (timeRange == TimeRange.THIS_WEEK || timeRange == TimeRange.TODAY) {
                    val today = java.time.LocalDate.now()
                    val last7Days = (0..6).map { dayOffset -> today.minusDays(dayOffset.toLong()) }.reversed()
                    
                    last7Days.map { date ->
                        val dateStr = date.toString()
                        rawDailyListening.find { daily -> daily.date == dateStr } ?: me.avinas.tempo.data.stats.DailyListening(
                            date = dateStr,
                            playCount = 0,
                            totalTimeMs = 0,
                            uniqueTracks = 0,
                            uniqueArtists = 0
                        )
                    }
                } else {
                    rawDailyListening
                }
                
                // For spotlight, check if there are enough stats
                val hasData = overview.totalPlayCount > 0
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listeningOverview = overview,
                        periodComparison = periodComparison,
                        dailyListening = dailyListening,
                        topTrack = topTracks.items.firstOrNull(),
                        topArtist = topArtists.items.firstOrNull(),
                        discoveryStats = discoveryStats,
                        mostActiveHour = mostActiveHour,
                        audioFeatures = audioFeatures,
                        insights = insights,
                        userName = userName,
                        hasData = hasData,
                        showRateAppPopup = shouldShowRateApp(overview.totalListeningTimeMs, overview.totalPlayCount)
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private suspend fun shouldShowRateApp(totalTimeMs: Long, playCount: Int): Boolean {
        // Engagement Check: > 2 hours listening AND > 50 plays
        if (totalTimeMs < 2 * 60 * 60 * 1000 || playCount < 50) return false

        val preferences = context.dataStore.data.first()
        val hasRated = preferences[booleanPreferencesKey("rate_app_rated")] ?: false
        if (hasRated) return false

        val dismissCount = preferences[intPreferencesKey("rate_app_dismiss_count")] ?: 0
        if (dismissCount >= 2) return false // Don't show if dismissed twice

        val lastShown = preferences[longPreferencesKey("rate_app_last_shown")] ?: 0L
        val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
        
        // If never shown or shown more than 3 days ago
        if (System.currentTimeMillis() - lastShown > threeDaysMs) {
             // Mark as shown today
             context.dataStore.edit { prefs ->
                 prefs[longPreferencesKey("rate_app_last_shown")] = System.currentTimeMillis()
             }
             return true
        }
        
        return false // Shown recently
    }

    fun onRateAppClicked() {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[booleanPreferencesKey("rate_app_rated")] = true
            }
            // Hide popup
            _uiState.update { it.copy(showRateAppPopup = false) }
        }
    }
    
    fun onRateAppDismissed() {
         viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[intPreferencesKey("rate_app_dismiss_count")] ?: 0
                prefs[intPreferencesKey("rate_app_dismiss_count")] = current + 1
            }
            // Hide popup
            _uiState.update { it.copy(showRateAppPopup = false) }
        }
    }
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.THIS_WEEK,
    val hasData: Boolean = false,
    
    // Data fields
    val listeningOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val periodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null,
    val dailyListening: List<me.avinas.tempo.data.stats.DailyListening> = emptyList(),
    val topTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val topArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats? = null,
    val mostActiveHour: me.avinas.tempo.data.stats.HourlyDistribution? = null,
    val audioFeatures: me.avinas.tempo.data.stats.AudioFeaturesStats? = null,
    val insights: List<me.avinas.tempo.data.stats.InsightCardData> = emptyList(),
    val userName: String? = null,
    val showRateAppPopup: Boolean = false
)
