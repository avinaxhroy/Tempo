package me.avinas.tempo.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.InsightPayload
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
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val tokenStorage: me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage,
    private val preferencesRepository: me.avinas.tempo.data.repository.PreferencesRepository,
    private val listeningEventDao: ListeningEventDao,
    val gamificationRepository: me.avinas.tempo.data.repository.GamificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeDataChanges()
        checkSpotlightReminder()
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
                // Dynamic data limit based on time range for chart visualization
                val dataLimit = when (timeRange) {
                    TimeRange.TODAY, TimeRange.THIS_WEEK -> 7
                    TimeRange.THIS_MONTH -> 31
                    TimeRange.THIS_YEAR -> 365
                    TimeRange.ALL_TIME -> 365
                }
                val rawDailyListeningDeferred = async { statsRepository.getDailyListening(timeRange, dataLimit) }
                val topTracksDeferred = async { statsRepository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1) }
                val topArtistsDeferred = async { statsRepository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1) }
                val discoveryStatsDeferred = async { statsRepository.getDiscoveryStats(timeRange) }
                val mostActiveHourDeferred = async { statsRepository.getMostActiveHour(timeRange) }
                val audioFeaturesDeferred = async { statsRepository.getAudioFeaturesStats(timeRange) }
                val insightsDeferred = async { statsRepository.getInsights(timeRange) }
                // Use ALL_TIME stats for rate app check - ensures consistent behavior regardless of current filter
                val allTimeOverviewDeferred = async { statsRepository.getListeningOverview(TimeRange.ALL_TIME) }
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
                val allTimeOverview = allTimeOverviewDeferred.await()
                val userName = userNameDeferred.await()
                
                // Fetch Gamification Data
                val userLevel = gamificationRepository.getUserLevel()
                val nextBadge = gamificationRepository.getNextEarnableBadge()
                
                // Process chart data and generate labels for interactive trend line
                val (dailyListening, chartLabels) = processChartData(timeRange, rawDailyListening)
                
                // Inject Gamification Card
                val gamificationInsight = InsightCardData(
                    title = "Level Progress", // Not displayed by GamificationCard
                    description = "Your current level and next badge",
                    type = InsightType.LOYALTY,
                    payload = InsightPayload.GamificationProgress(
                        level = userLevel,
                        nextBadge = nextBadge
                    )
                )
                
                // Combine insights (Gamification Card first, then others)
                val combinedInsights = listOf(gamificationInsight) + insightsDeferred.await()
                
                val hasData = overview.totalPlayCount > 0
                val earliestTimestamp = statsRepository.getEarliestDataTimestamp()
                val isNewUser = earliestTimestamp == null
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listeningOverview = overview,
                        periodComparison = periodComparison,
                        chartLabels = chartLabels,
                        dailyListening = dailyListening,
                        topTrack = topTracks.items.firstOrNull(),
                        topArtist = topArtists.items.firstOrNull(),
                        discoveryStats = discoveryStats,
                        mostActiveHour = mostActiveHour,
                        audioFeatures = audioFeatures,
                        insights = combinedInsights,
                        userName = userName,
                        hasData = hasData,
                        isNewUser = isNewUser,
                        showRateAppPopup = shouldShowRateApp(allTimeOverview.totalListeningTimeMs, allTimeOverview.totalPlayCount)
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private suspend fun shouldShowRateApp(totalTimeMs: Long, playCount: Int): Boolean {
        // Get REAL usage stats (excluding imported data from Spotify)
        // We want to prompt users who actually use the app, not just imported history
        val realListeningTimeMs = listeningEventDao.getRealListeningTimeMs()
        val realPlayCount = listeningEventDao.getRealPlayCount()
        
        // Engagement Check: > 2 hours REAL listening AND > 50 REAL plays
        if (realListeningTimeMs < 2 * 60 * 60 * 1000 || realPlayCount < 50) return false

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
    
    /**
     * Process chart data and generate labels based on time range.
     * Returns chronologically sorted data (oldest to newest) with matching labels.
     * Aggregates data by month when there are too many days for better visualization.
     * 
     * Feature contributed by @FlazeIGuess (PR #1) with modifications.
     */
    private fun processChartData(
        timeRange: TimeRange,
        rawData: List<me.avinas.tempo.data.stats.DailyListening>
    ): Pair<List<me.avinas.tempo.data.stats.DailyListening>, List<String>> {
        val today = java.time.LocalDate.now()
        
        return when (timeRange) {
            TimeRange.TODAY, TimeRange.THIS_WEEK -> {
                val last7Days = (0..6).map { dayOffset -> 
                    today.minusDays(dayOffset.toLong()) 
                }.reversed()
                
                val data = last7Days.map { date ->
                    val dateStr = date.toString()
                    rawData.find { it.date == dateStr } ?: me.avinas.tempo.data.stats.DailyListening(
                        date = dateStr,
                        playCount = 0,
                        totalTimeMs = 0,
                        uniqueTracks = 0,
                        uniqueArtists = 0
                    )
                }
                
                // Force English locale for day names
                val labels = last7Days.map { date ->
                    date.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.SHORT,
                        java.util.Locale.ENGLISH
                    )
                }
                
                Pair(data, labels)
            }
            
            TimeRange.THIS_MONTH -> {
                val firstDayOfMonth = today.withDayOfMonth(1)
                val daysInMonth = (0 until today.dayOfMonth).map { 
                    firstDayOfMonth.plusDays(it.toLong()) 
                }
                
                val data = daysInMonth.map { date ->
                    val dateStr = date.toString()
                    rawData.find { it.date == dateStr } ?: me.avinas.tempo.data.stats.DailyListening(
                        date = dateStr,
                        playCount = 0,
                        totalTimeMs = 0,
                        uniqueTracks = 0,
                        uniqueArtists = 0
                    )
                }
                
                // Show "Day 1", "Day 2", etc.
                val labels = daysInMonth.map { date ->
                    "Day ${date.dayOfMonth}"
                }
                
                Pair(data, labels)
            }
            
            TimeRange.THIS_YEAR -> {
                val sortedData = rawData.sortedBy { it.date }
                
                // If more than 60 days, aggregate by month
                if (sortedData.size > 60) {
                    val monthlyData = aggregateByMonth(sortedData)
                    val labels = monthlyData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            date.month.getDisplayName(
                                java.time.format.TextStyle.SHORT,
                                java.util.Locale.ENGLISH
                            )
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(monthlyData, labels)
                } else {
                    // Show daily data with day + month labels
                    val labels = sortedData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            "${date.dayOfMonth} ${date.month.getDisplayName(
                                java.time.format.TextStyle.SHORT,
                                java.util.Locale.ENGLISH
                            )}"
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(sortedData, labels)
                }
            }
            
            TimeRange.ALL_TIME -> {
                val sortedData = rawData.sortedBy { it.date }
                
                // If 60 days or less, show daily data like THIS_YEAR
                // If more than 60 days, aggregate by month
                if (sortedData.size <= 60) {
                    // Show daily data with day + month labels
                    val labels = sortedData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            val year = date.year
                            val currentYear = today.year
                            
                            // For current year, show "15 Jan"
                            // For past years, show "15 Jan 2023"
                            if (year == currentYear) {
                                "${date.dayOfMonth} ${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )}"
                            } else {
                                "${date.dayOfMonth} ${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )} ${year}"
                            }
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(sortedData, labels)
                } else {
                    // Aggregate by month for better visualization
                    val monthlyData = aggregateByMonth(sortedData)
                    val labels = monthlyData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            val year = date.year
                            val currentYear = today.year
                            
                            // For current year, show "Jan"
                            // For past years, show "Jan 2023"
                            if (year == currentYear) {
                                date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )
                            } else {
                                "${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )} ${year}"
                            }
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(monthlyData, labels)
                }
            }
        }
    }
    
    /**
     * Aggregates daily listening data by month.
     * Returns one data point per month with the first day of the month as the date.
     */
    private fun aggregateByMonth(
        dailyData: List<me.avinas.tempo.data.stats.DailyListening>
    ): List<me.avinas.tempo.data.stats.DailyListening> {
        return dailyData
            .groupBy { daily ->
                try {
                    val date = java.time.LocalDate.parse(daily.date)
                    "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
                } catch (e: Exception) {
                    daily.date
                }
            }
            .map { (yearMonth, monthData) ->
                // Use the first day of the month as the representative date
                val firstDate = try {
                    val parts = yearMonth.split("-")
                    "${parts[0]}-${parts[1]}-01"
                } catch (e: Exception) {
                    monthData.first().date
                }
                
                me.avinas.tempo.data.stats.DailyListening(
                    date = firstDate,
                    playCount = monthData.sumOf { it.playCount },
                    totalTimeMs = monthData.sumOf { it.totalTimeMs },
                    // Use sum for unique counts to approximate monthly totals
                    uniqueTracks = monthData.sumOf { it.uniqueTracks },
                    uniqueArtists = monthData.sumOf { it.uniqueArtists }
                )
            }
            .sortedBy { it.date }
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
    
    /**
     * Check if we should show a Spotlight Story reminder.
     * Shows reminder on:
     * - Last day of the month (for THIS_MONTH story)
     * - December 1st (for THIS_YEAR story)
     */
    private fun checkSpotlightReminder() {
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val todayString = today.toString() // YYYY-MM-DD format
            
            android.util.Log.d("HomeViewModel", "Checking Spotlight reminder for date: $todayString")
            
            // Get user preferences to check if reminder already shown
            val preferences = preferencesRepository.preferences().first() ?: return@launch
            
            // Check for monthly reminder (last day of month)
            val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
            if (isLastDayOfMonth && preferences.lastMonthlyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is last day of month, checking data availability...")
                // Ensure we have data for this month
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_MONTH)
                android.util.Log.d("HomeViewModel", "Monthly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing MONTHLY Spotlight reminder")
                    _uiState.update { 
                        it.copy(
                            showSpotlightReminder = true,
                            reminderTimeRange = TimeRange.THIS_MONTH,
                            reminderType = me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY
                        ) 
                    }
                    return@launch
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping monthly reminder: no data for THIS_MONTH")
                }
            } else if (isLastDayOfMonth) {
                android.util.Log.d("HomeViewModel", "Last day of month, but already shown: ${preferences.lastMonthlyReminderShown}")
            }
            
            // Check for yearly reminder (December 1st)
            val isDecemberFirst = today.monthValue == 12 && today.dayOfMonth == 1
            if (isDecemberFirst && preferences.lastYearlyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is December 1st, checking data availability...")
                // Ensure we have data for this year
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_YEAR)
                android.util.Log.d("HomeViewModel", "Yearly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing YEARLY Spotlight reminder")
                    _uiState.update { 
                        it.copy(
                            showSpotlightReminder = true,
                            reminderTimeRange = TimeRange.THIS_YEAR,
                            reminderType = me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY
                        ) 
                    }
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping yearly reminder: no data for THIS_YEAR")
                }
            } else if (isDecemberFirst) {
                android.util.Log.d("HomeViewModel", "December 1st, but already shown: ${preferences.lastYearlyReminderShown}")
            }
        }
    }
    
    /**
     * Dismiss the Spotlight reminder and save state to prevent showing again.
     */
    fun dismissSpotlightReminder() {
        viewModelScope.launch {
            val today = java.time.LocalDate.now().toString()
            val preferences = preferencesRepository.preferences().first() ?: return@launch
            
            // Update preferences based on reminder type
            val updatedPrefs = when (_uiState.value.reminderType) {
                me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY -> 
                    preferences.copy(lastMonthlyReminderShown = today)
                me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY -> 
                    preferences.copy(lastYearlyReminderShown = today)
                null -> preferences
            }
            
            preferencesRepository.upsert(updatedPrefs)
            
            // Hide popup
            _uiState.update { 
                it.copy(
                    showSpotlightReminder = false,
                    reminderTimeRange = null,
                    reminderType = null
                ) 
            }
        }
    }
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.THIS_WEEK,
    val hasData: Boolean = false,
    val isNewUser: Boolean = false,
    
    // Data fields
    val listeningOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val periodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null,
    val dailyListening: List<me.avinas.tempo.data.stats.DailyListening> = emptyList(),
    val chartLabels: List<String> = emptyList(),  // Labels for interactive chart
    val topTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val topArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats? = null,
    val mostActiveHour: me.avinas.tempo.data.stats.HourlyDistribution? = null,
    val audioFeatures: me.avinas.tempo.data.stats.AudioFeaturesStats? = null,
    val insights: List<me.avinas.tempo.data.stats.InsightCardData> = emptyList(),
    val userName: String? = null,
    val showRateAppPopup: Boolean = false,
    
    // Spotlight Story Reminder
    val showSpotlightReminder: Boolean = false,
    val reminderTimeRange: TimeRange? = null,
    val reminderType: me.avinas.tempo.ui.components.SpotlightReminderType? = null
)
