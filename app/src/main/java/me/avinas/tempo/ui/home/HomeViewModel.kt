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
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.ui.onboarding.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val tokenStorage: me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage,
    private val preferencesRepository: me.avinas.tempo.data.repository.PreferencesRepository,
    private val listeningEventDao: ListeningEventDao,
    private val profileIdentityManager: ProfileIdentityManager,
    val gamificationRepository: me.avinas.tempo.data.repository.GamificationRepository,
    private val refreshCoordinator: me.avinas.tempo.data.repository.RefreshCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _flagsState = MutableStateFlow(HomeFlags())
    val flagsState: StateFlow<HomeFlags> = _flagsState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Paces the dashboard's Room access. [fetchData] launches ~20 concurrent
     * repository fetches and several of those fan out again (getInsights alone runs
     * ~10 statements), while Room's default query executor only has 4 threads — firing
     * the whole burst at once just piles work onto that queue and head-of-line blocks
     * every other query in the app. Four permits keeps at most one burst's worth of
     * statements in flight, matching the executor.
     *
     * A field (shared across loads) rather than scoped to one
     * coroutineScope: [refresh] can run [fetchData] while a debounced reactive load is
     * still winding down, and a cancelled load releases its permits asynchronously, so
     * a per-load semaphore would allow double the concurrency in that overlap window.
     * Permits are never leaked on cancellation: withPermit releases in a finally and a
     * cancelled acquire() never takes one.
     */
    private val dbSemaphore = Semaphore(permits = 4)

    // Coalesced reactive reload signal (see observeDataChanges).
    private val reactiveReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Rate-app prompt is evaluated at most once per app session.
    private var rateAppChecked = false

    init {
        loadData()
        observeDataChanges()
        checkSpotlightReminder()
    }
    
    private fun observeDataChanges() {
        // All reactive reload sources funnel into one debounced signal so a single
        // listening event (which fires BOTH the Room overview flow and refreshEvents)
        // produces one data reload instead of two full multi-query fetches.
        viewModelScope.launch {
            reactiveReload
                .debounce(1_500)
                .collect { loadData() }
        }

        viewModelScope.launch {
            _uiState
                .map { it.selectedTimeRange }
                .distinctUntilChanged()
                .flatMapLatest { timeRange ->
                    statsRepository.observeListeningOverview(timeRange, withLeeway = false)
                }
                .distinctUntilChanged()
                .collect { overview ->
                    _uiState.update { it.copy(listeningOverview = overview, hasData = overview.totalPlayCount > 0) }
                    reactiveReload.tryEmit(Unit)
                }
        }

        // Also observe metadata updates (album art, artist images) from enrichment
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates()
                .collect { reactiveReload.tryEmit(Unit) }
        }

        // Listen for new track events from MusicTrackingService
        viewModelScope.launch {
            refreshCoordinator.refreshEvents
                .collect { reactiveReload.tryEmit(Unit) }
        }

        // Listen for preference changes (e.g. Gamification toggle, spotlight viewed state)
        viewModelScope.launch {
            var lastGamificationState: Boolean? = null
            preferencesRepository.preferences().collect { prefs ->
                val state = prefs?.isGamificationEnabled ?: true
                if (lastGamificationState != null && lastGamificationState != state) {
                    loadData() // Reload if toggle changed (deliberate user action, no debounce)
                }
                lastGamificationState = state

                // Compute spotlight story viewed state
                val storyTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                val currentKey = if (storyTimeRange != null) {
                    me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.storyPeriodKey(storyTimeRange)
                } else null
                val viewed = currentKey != null && currentKey == prefs?.lastSpotlightStoryViewed
                _flagsState.update { it.copy(spotlightStoryViewed = viewed) }
            }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.update { it.copy(selectedTimeRange = timeRange) }
        _flagsState.update { it.copy(isLoading = true) }
        loadData()
    }

    suspend fun refresh() {
        val startTime = System.currentTimeMillis()
        _flagsState.update { it.copy(isRefreshing = true, isLoading = true) }
        try {
            fetchData()
        } finally {
            // Keep spinner visible for at least 600ms to prevent flicker
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 600) delay(600 - elapsed)
            _flagsState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            fetchData()
        }
    }

    private suspend fun fetchData() {
        try {
            val timeRange = _uiState.value.selectedTimeRange
            
            // Parallel fetch for dashboard metrics
            coroutineScope {
                val overviewDeferred = async { dbSemaphore.withPermit { statsRepository.getListeningOverview(timeRange, withLeeway = false) } }
                val periodComparisonDeferred = async { dbSemaphore.withPermit { statsRepository.getPeriodComparison(timeRange, withLeeway = false) } }
                // Point limit by time range for chart display
                val dataLimit = when (timeRange) {
                    TimeRange.TODAY, TimeRange.THIS_WEEK -> 7
                    TimeRange.THIS_MONTH -> 31
                    TimeRange.THIS_YEAR -> 366
                    TimeRange.ALL_TIME -> 5000
                }
                val rawDailyListeningDeferred = async { dbSemaphore.withPermit { statsRepository.getDailyListening(timeRange, dataLimit, withLeeway = false) } }
                val activeDaysDeferred = async { dbSemaphore.withPermit { statsRepository.getActiveDaysCount(timeRange, withLeeway = false) } }
                val peakDayMsDeferred = async { dbSemaphore.withPermit { statsRepository.getPeakDayListeningMs(timeRange, withLeeway = false) } }
                val topTracksDeferred = async { dbSemaphore.withPermit { statsRepository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1, withLeeway = false) } }
                val topArtistsDeferred = async { dbSemaphore.withPermit { statsRepository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1, withLeeway = false) } }
                val discoveryStatsDeferred = async { dbSemaphore.withPermit { statsRepository.getDiscoveryStats(timeRange, withLeeway = false) } }
                val mostActiveHourDeferred = async { dbSemaphore.withPermit { statsRepository.getMostActiveHour(timeRange, withLeeway = false) } }
                val audioFeaturesDeferred = async { dbSemaphore.withPermit { statsRepository.getAudioFeaturesStats(timeRange, withLeeway = false) } }
                val insightsDeferred = async { dbSemaphore.withPermit { statsRepository.getInsights(timeRange, withLeeway = false) } }
                val profileIdentityDeferred = async { profileIdentityManager.getProfileIdentity() }
                val earliestTimestampDeferred = async { dbSemaphore.withPermit { statsRepository.getEarliestDataTimestamp() } }
                
                // Today's Listen Widget data
                val todayOverviewDeferred = async { dbSemaphore.withPermit { statsRepository.getListeningOverview(TimeRange.TODAY) } }
                val todayTopTracksDeferred = async { dbSemaphore.withPermit { statsRepository.getTopTracks(TimeRange.TODAY, sortBy = me.avinas.tempo.data.repository.SortBy.PLAY_COUNT, pageSize = 5) } }
                val todayTopArtistsDeferred = async { dbSemaphore.withPermit { statsRepository.getTopArtists(TimeRange.TODAY, sortBy = me.avinas.tempo.data.repository.SortBy.PLAY_COUNT, pageSize = 5) } }
                val todayHourlyDeferred = async { dbSemaphore.withPermit { statsRepository.getHourlyDistribution(TimeRange.TODAY) } }
                val todayPeriodComparisonDeferred = async { dbSemaphore.withPermit { statsRepository.getPeriodComparison(TimeRange.TODAY, withLeeway = false) } }
                
                // Read isGamificationEnabled setting
                val isGamificationEnabledDeferred = async {
                    dbSemaphore.withPermit {
                        preferencesRepository.preferences().first()?.isGamificationEnabled ?: true
                    }
                }
                
                // Spotlight story top track — fetched for the unlocked story period with leeway
                // so the ring shows the correct album art from the story's time period.
                val spotlightTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                val spotlightTopTrackDeferred = if (spotlightTimeRange != null) {
                    async {
                        dbSemaphore.withPermit {
                            statsRepository.getTopTracks(
                                spotlightTimeRange,
                                sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE,
                                pageSize = 1,
                                withLeeway = true  // Match story system's period calculation
                            )
                        }
                    }
                } else null
                
                val overview = overviewDeferred.await()
                val periodComparison = periodComparisonDeferred.await()
                val rawDailyListening = rawDailyListeningDeferred.await()
                val topTracks = topTracksDeferred.await()
                val topArtists = topArtistsDeferred.await()
                val discoveryStats = discoveryStatsDeferred.await()
                val mostActiveHour = mostActiveHourDeferred.await()
                val audioFeatures = audioFeaturesDeferred.await()
                val profileIdentity = profileIdentityDeferred.await()
                val spotlightTopTrack = spotlightTopTrackDeferred?.await()?.items?.firstOrNull()
                val userName = profileIdentity.userName
                    .takeIf { it.isNotBlank() }
                    ?: tokenStorage.getUserDisplayName()?.split(" ")?.firstOrNull()
                    ?: "User"
                
                val todayOverview = todayOverviewDeferred.await()
                val todayTopTracks = todayTopTracksDeferred.await()
                val todayTopArtists = todayTopArtistsDeferred.await()
                val todayHourly = todayHourlyDeferred.await()
                val todayPeriodComparison = todayPeriodComparisonDeferred.await()
                
                val isGamificationEnabled = isGamificationEnabledDeferred.await()
                val userLevel = if (isGamificationEnabled) gamificationRepository.getUserLevel() else null
                val nextBadge = if (isGamificationEnabled) gamificationRepository.getNextEarnableBadge() else null
                
                val (dailyListening, chartLabels) = processChartData(timeRange, rawDailyListening)
                
                val combinedInsights = mutableListOf<InsightCardData>()
                if (isGamificationEnabled && userLevel != null) {
                    combinedInsights.add(
                        InsightCardData(
                            title = "Level Progress", // Not displayed by GamificationCard
                            description = "Your current level and next badge",
                            type = InsightType.LOYALTY,
                            payload = InsightPayload.GamificationProgress(
                                level = userLevel,
                                nextBadge = nextBadge
                            )
                        )
                    )
                }
                
                // Combine insights (Gamification Card first, then others)
                combinedInsights.addAll(insightsDeferred.await())

                val activeDaysCount = activeDaysDeferred.await()
                val peakDayMs = peakDayMsDeferred.await()
                val peakDayMinutes = peakDayMs / 1000 / 60
                val earliestTimestamp = earliestTimestampDeferred.await()

                val today = java.time.LocalDate.now()
                val totalDaysCount = when (timeRange) {
                    TimeRange.TODAY -> 1
                    TimeRange.THIS_WEEK -> today.dayOfWeek.value
                    TimeRange.THIS_MONTH -> today.dayOfMonth
                    TimeRange.THIS_YEAR -> today.dayOfYear
                    TimeRange.ALL_TIME -> {
                        if (earliestTimestamp != null) {
                            val earliestDate = java.time.Instant.ofEpochMilli(earliestTimestamp)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                            (java.time.temporal.ChronoUnit.DAYS.between(earliestDate, today).toInt() + 1).coerceAtLeast(1)
                        } else {
                            1
                        }
                    }
                }

                val totalListeningMinutes = overview.totalListeningTimeMs / 1000 / 60
                val dailyAvgMinutes = if (activeDaysCount > 0) totalListeningMinutes / activeDaysCount else 0L

                val hasData = overview.totalPlayCount > 0
                val isNewUser = earliestTimestamp == null
                
                // Hoist the suspend rate-app check out of the state-update lambda, and
                // preserve an already-visible popup across reloads (shouldShowRateApp()
                // writes a 3-day cooldown on success and would otherwise dismiss it).
                val showRateApp = _flagsState.value.showRateAppPopup || shouldShowRateApp()

                _uiState.update {
                    it.copy(
                        listeningOverview = overview,
                        periodComparison = periodComparison,
                        chartLabels = chartLabels,
                        dailyListening = dailyListening,
                        dailyAvgMinutes = dailyAvgMinutes,
                        activeDaysCount = activeDaysCount,
                        totalDaysCount = totalDaysCount,
                        peakDayMinutes = peakDayMinutes,
                        topTrack = topTracks.items.firstOrNull(),
                        topArtist = topArtists.items.firstOrNull(),
                        spotlightTopTrack = spotlightTopTrack,
                        discoveryStats = discoveryStats,
                        mostActiveHour = mostActiveHour,
                        audioFeatures = audioFeatures,
                        insights = combinedInsights,
                        userName = userName,
                        profileImagePath = profileIdentity.profileImagePath,
                        hasData = hasData,
                        isNewUser = isNewUser,
                        isGamificationEnabled = isGamificationEnabled,
                        todayOverview = todayOverview,
                        todayTopTrack = todayTopTracks.items.firstOrNull(),
                        todayTopArtist = todayTopArtists.items.firstOrNull(),
                        todayTopTracks = todayTopTracks.items.take(5),
                        todayTopArtists = todayTopArtists.items.take(5),
                        todayHourlyDistribution = todayHourly,
                        todayPeriodComparison = todayPeriodComparison
                    )
                }
                _flagsState.update {
                    it.copy(
                        isLoading = false,
                        showRateAppPopup = showRateApp
                    )
                }

                // Share nudge — evaluate once per session, once home data is ready.
                maybeShowShareNudge()
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message) }
            _flagsState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun shouldShowRateApp(): Boolean {
        // Evaluated at most once per app session: the engagement gates are two full-table
        // DAO scans (getRealListeningTimeMs/getRealPlayCount) and must not run on every
        // data reload. If engagement is below threshold now, the prompt simply waits for
        // the next launch rather than re-scanning on every track change.
        if (rateAppChecked) return false
        rateAppChecked = true
        // Check cheapest gates first to short-circuit before hitting the DB.
        val preferences = context.dataStore.data.first()

        // 1. Already handled — permanent suppression.
        val hasRated = preferences[booleanPreferencesKey("rate_app_rated")] ?: false
        if (hasRated) return false

        // 2. Dismissed too many times.
        val dismissCount = preferences[intPreferencesKey("rate_app_dismiss_count")] ?: 0
        if (dismissCount >= 2) return false

        // 3. Cooldown: don't re-prompt within 3 days of the last showing.
        val lastShown = preferences[longPreferencesKey("rate_app_last_shown")] ?: 0L
        val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastShown <= threeDaysMs) return false

        // 4. Install age: only prompt users who have had the app for at least 7 days.
        //    firstInstallTime survives app updates but not reinstalls.
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        val firstInstallTime = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .firstInstallTime
        } catch (_: Exception) {
            System.currentTimeMillis() // fail-open: don't block the prompt if we can't read
        }
        if (System.currentTimeMillis() - firstInstallTime < sevenDaysMs) return false

        // 5. Engagement: > 2 hours REAL listening AND > 50 REAL plays
        //    (excludes imported Spotify history — source NOT LIKE '%import%')
        val realListeningTimeMs = listeningEventDao.getRealListeningTimeMs()
        val realPlayCount = listeningEventDao.getRealPlayCount()
        if (realListeningTimeMs < 2 * 60 * 60 * 1000 || realPlayCount < 50) return false

        context.dataStore.edit { prefs ->
            prefs[longPreferencesKey("rate_app_last_shown")] = System.currentTimeMillis()
        }
        return true
    }
    
    /**
     * Returns chronologically sorted data (oldest to newest) with matching labels,
     * aggregated by month when a range holds too many days to plot.
     *
     * Feature contributed by @FlazeIGuess (PR #1) with modifications.
     */
    private fun processChartData(
        timeRange: TimeRange,
        rawData: List<me.avinas.tempo.data.stats.DailyListening>
    ): Pair<List<me.avinas.tempo.data.stats.DailyListening>, List<String>> {
        val today = java.time.LocalDate.now()
        
        return when (timeRange) {
            TimeRange.TODAY -> {
                val todayStr = today.toString()
                val todayData = rawData.firstOrNull { it.date == todayStr } ?: me.avinas.tempo.data.stats.DailyListening(
                    date = todayStr,
                    playCount = 0,
                    totalTimeMs = 0,
                    uniqueTracks = 0,
                    uniqueArtists = 0
                )
                Pair(listOf(todayData), listOf("Today"))
            }

            TimeRange.THIS_WEEK -> {
                val monday = today.minusDays(today.dayOfWeek.value.toLong() - 1)
                val daysInWeek = (0 until today.dayOfWeek.value).map { 
                    monday.plusDays(it.toLong()) 
                }
                
                val rawDataMap = rawData.associateBy { it.date }
                val data = daysInWeek.map { date ->
                    val dateStr = date.toString()
                    rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
                        date = dateStr,
                        playCount = 0,
                        totalTimeMs = 0,
                        uniqueTracks = 0,
                        uniqueArtists = 0
                    )
                }
                
                // Force English locale for day names
                val labels = daysInWeek.map { date ->
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
                
                val rawDataMap = rawData.associateBy { it.date }
                val data = daysInMonth.map { date ->
                    val dateStr = date.toString()
                    rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
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
                // If more than 60 days have passed this year, aggregate by month
                if (today.dayOfYear > 60) {
                    val currentMonth = today.monthValue
                    val rawByMonth = rawData.groupBy { it.date.take(7) } // single-pass grouping
                    val monthlyData = (1..currentMonth).map { monthNum ->
                        val yearMonth = "${today.year}-${monthNum.toString().padStart(2, '0')}"
                        val firstDate = "$yearMonth-01"
                        val monthListens = rawByMonth[yearMonth].orEmpty()
                        me.avinas.tempo.data.stats.DailyListening(
                            date = firstDate,
                            playCount = monthListens.sumOf { it.playCount },
                            totalTimeMs = monthListens.sumOf { it.totalTimeMs },
                            uniqueTracks = monthListens.sumOf { it.uniqueTracks },
                            uniqueArtists = monthListens.sumOf { it.uniqueArtists }
                        )
                    }
                    val labels = (1..currentMonth).map { monthNum ->
                        java.time.Month.of(monthNum).getDisplayName(
                            java.time.format.TextStyle.SHORT,
                            java.util.Locale.ENGLISH
                        )
                    }
                    Pair(monthlyData, labels)
                } else {
                    // Continuous daily data from Jan 1 to today
                    val firstDayOfYear = today.withDayOfYear(1)
                    val daysInYear = (0 until today.dayOfYear).map {
                        firstDayOfYear.plusDays(it.toLong())
                    }
                    val rawDataMap = rawData.associateBy { it.date }
                    val data = daysInYear.map { date ->
                        val dateStr = date.toString()
                        rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
                            date = dateStr,
                            playCount = 0,
                            totalTimeMs = 0,
                            uniqueTracks = 0,
                            uniqueArtists = 0
                        )
                    }
                    val labels = daysInYear.map { date ->
                        "${date.dayOfMonth} ${date.month.getDisplayName(
                            java.time.format.TextStyle.SHORT,
                            java.util.Locale.ENGLISH
                        )}"
                    }
                    Pair(data, labels)
                }
            }
            
            TimeRange.ALL_TIME -> {
                val sortedData = rawData.sortedBy { it.date }
                val earliestDate = sortedData.firstOrNull()?.let {
                    try { java.time.LocalDate.parse(it.date) } catch (e: Exception) { null }
                } ?: today
                
                val totalDays = java.time.temporal.ChronoUnit.DAYS.between(earliestDate, today).toInt() + 1
                if (totalDays > 60) {
                    val startYearMonth = java.time.YearMonth.from(earliestDate)
                    val endYearMonth = java.time.YearMonth.from(today)
                    val months = mutableListOf<java.time.YearMonth>()
                    var current = startYearMonth
                    while (!current.isAfter(endYearMonth)) {
                        months.add(current)
                        current = current.plusMonths(1)
                    }
                    
                    // Single-pass grouping avoids the O(months × n) filter scan.
                    val rawByMonth = rawData.groupBy { it.date.take(7) }
                    val monthlyData = months.map { ym ->
                        val ymPrefix = ym.toString()
                        val monthListens = rawByMonth[ymPrefix].orEmpty()
                        me.avinas.tempo.data.stats.DailyListening(
                            date = "$ymPrefix-01",
                            playCount = monthListens.sumOf { it.playCount },
                            totalTimeMs = monthListens.sumOf { it.totalTimeMs },
                            uniqueTracks = monthListens.sumOf { it.uniqueTracks },
                            uniqueArtists = monthListens.sumOf { it.uniqueArtists }
                        )
                    }
                    val currentYear = today.year
                    val labels = months.map { ym ->
                        if (ym.year == currentYear) {
                            ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                        } else {
                            "${ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${ym.year}"
                        }
                    }
                    Pair(monthlyData, labels)
                } else {
                    val rawDataMap = rawData.associateBy { it.date }
                    val days = (0 until totalDays).map { earliestDate.plusDays(it.toLong()) }
                    val data = days.map { date ->
                        val dateStr = date.toString()
                        rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
                            date = dateStr,
                            playCount = 0,
                            totalTimeMs = 0,
                            uniqueTracks = 0,
                            uniqueArtists = 0
                        )
                    }
                    val currentYear = today.year
                    val labels = days.map { date ->
                        if (date.year == currentYear) {
                            "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"
                        } else {
                            "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${date.year}"
                        }
                    }
                    Pair(data, labels)
                }
            }
        }
    }
    
    /**
     * Aggregates daily listening data by month, dated the first of that month.
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

    fun onRateAppFlowHandled() {
        // Dismiss immediately so the user sees instant feedback.
        _flagsState.update { it.copy(showRateAppPopup = false) }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                // The Play Review API does not tell us whether the user submitted a rating.
                // We use this flag only to suppress re-prompting after handling the CTA.
                prefs[booleanPreferencesKey("rate_app_rated")] = true
            }
        }
    }
    
    fun onRateAppDismissed() {
        // Dismiss immediately so the user sees instant feedback.
        _flagsState.update { it.copy(showRateAppPopup = false) }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[intPreferencesKey("rate_app_dismiss_count")] ?: 0
                prefs[intPreferencesKey("rate_app_dismiss_count")] = current + 1
            }
        }
    }
    
    /**
     * Session guard: the share nudge is evaluated at most once per app session so a
     * time-range change or pull-to-refresh can never re-trigger it mid-use.
     */
    private var shareNudgeEvaluated = false

    /**
     * Opens the artist share preview on the home screen at most once per session,
     * after home data settles, when every gate below passes (cheapest first):
     *  1. Dismissed 3 times — the user isn't interested; stop permanently.
     *  2. Cooldown — at most once every 7 days.
     *  3. Shared anything in the last 14 days — they already know the feature.
     *  4. Fewer than 20 real plays — nothing worth sharing yet (also skips new users).
     *  5. Never stacks on top of the rate popup or spotlight reminder.
     */
    private fun maybeShowShareNudge() {
        if (shareNudgeEvaluated) return
        shareNudgeEvaluated = true
        viewModelScope.launch {
            try {
                // Let the home screen settle before any overlay appears.
                delay(1500)

                val flags = _flagsState.value
                if (flags.showRateAppPopup || flags.showSpotlightReminder || flags.showShareNudge) return@launch

                val preferences = context.dataStore.data.first()

                // 1. Dismissed too many times — permanent suppression.
                val dismissCount = preferences[intPreferencesKey("share_nudge_dismiss_count")] ?: 0
                if (dismissCount >= 3) return@launch

                // 2. Cooldown: at most once every 7 days.
                val lastShown = preferences[longPreferencesKey("share_nudge_last_shown")] ?: 0L
                if (System.currentTimeMillis() - lastShown <= 7 * 24 * 60 * 60 * 1000L) return@launch

                // 3. Shared in the last 14 days — they already know the feature.
                val lastShared = preferences[longPreferencesKey("share_last_success")] ?: 0L
                if (lastShared > 0L && System.currentTimeMillis() - lastShared <= 14 * 24 * 60 * 60 * 1000L) return@launch

                // 4. Engagement: enough real plays for a card worth sharing.
                if (listeningEventDao.getRealPlayCount() < 20) return@launch

                // Freshest recap period wins for the card; weekly otherwise.
                val today = java.time.LocalDate.now()
                val timeRange =
                    if (today.dayOfMonth == today.lengthOfMonth()) TimeRange.THIS_MONTH
                    else TimeRange.THIS_WEEK
                val artists = statsRepository.getTopArtists(
                    timeRange,
                    sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE,
                    pageSize = 10,
                    withLeeway = false
                ).items
                if (artists.isEmpty()) return@launch
                val overview = statsRepository.getListeningOverview(timeRange, withLeeway = false)
                if (overview.totalPlayCount <= 0) return@launch

                // Mark shown immediately so re-entry can't double-fire.
                context.dataStore.edit { prefs ->
                    prefs[longPreferencesKey("share_nudge_last_shown")] = System.currentTimeMillis()
                }

                _flagsState.update {
                    it.copy(
                        showShareNudge = true,
                        shareNudgeTimeRange = timeRange,
                        shareNudgeArtists = artists,
                        shareNudgeOverview = overview
                    )
                }
            } catch (e: Exception) {
                android.util.Log.d("HomeViewModel", "Share nudge skipped: ${e.message}")
            }
        }
    }

    /** Counts toward the 3-dismiss permanent suppression. */
    fun onShareNudgeDismissed() {
        _flagsState.update {
            it.copy(
                showShareNudge = false,
                shareNudgeTimeRange = null,
                shareNudgeArtists = emptyList(),
                shareNudgeOverview = null
            )
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[intPreferencesKey("share_nudge_dismiss_count")] ?: 0
                prefs[intPreferencesKey("share_nudge_dismiss_count")] = current + 1
            }
        }
    }

    /**
     * Hides the nudge because its share sheet was handed off.
     *
     * Nothing is recorded here: opening the sheet is not sharing, and a user who backs out of
     * it must not lose the dismiss counter. `TempoApplication` owns the one write per real
     * share, from the chooser's picked-target callback.
     */
    fun onShareNudgeShared() {
        _flagsState.update {
            it.copy(
                showShareNudge = false,
                shareNudgeTimeRange = null,
                shareNudgeArtists = emptyList(),
                shareNudgeOverview = null
            )
        }
    }

    /**
     * Raise the recap prompt with everything it needs to render in one pass:
     * the period's headline numbers and the five sleeves it fans out.
     */
    private suspend fun armSpotlightReminder(
        type: me.avinas.tempo.ui.components.SpotlightReminderType,
        timeRange: TimeRange
    ) {
        // Only sleeve art: the prompt teases the period, the story reveals it.
        val topTracks = runCatching {
            statsRepository.getTopTracks(
                timeRange = timeRange,
                sortBy = SortBy.PLAY_COUNT,
                page = 0,
                pageSize = 5,
                withLeeway = true
            ).items
        }.getOrElse { emptyList() }

        _flagsState.update {
            it.copy(
                showSpotlightReminder = true,
                reminderTimeRange = timeRange,
                reminderType = type,
                reminderPreview = me.avinas.tempo.ui.components.SpotlightReminderPreview(
                    artUrls = topTracks.mapNotNull { track -> track.albumArtUrl }.distinct()
                )
            )
        }
    }

    /**
     * Check if we should show a Spotlight Story reminder.
     * Shows reminder on:
     * - Sunday (for THIS_WEEK story)
     * - Last day of the month (for THIS_MONTH story)
     * - December 1st (for THIS_YEAR story)
     */
    private fun checkSpotlightReminder() {
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val todayString = today.toString() // YYYY-MM-DD format
            
            android.util.Log.d("HomeViewModel", "Checking Spotlight reminder for date: $todayString")
            
            val preferences = preferencesRepository.preferences().first() ?: return@launch

            // Check for monthly reminder (last day of month)
            val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
            if (isLastDayOfMonth && preferences.lastMonthlyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is last day of month, checking data availability...")
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_MONTH)
                android.util.Log.d("HomeViewModel", "Monthly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing MONTHLY Spotlight reminder")
                    armSpotlightReminder(
                        type = me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY,
                        timeRange = TimeRange.THIS_MONTH
                    )
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
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_YEAR)
                android.util.Log.d("HomeViewModel", "Yearly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing YEARLY Spotlight reminder")
                    armSpotlightReminder(
                        type = me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY,
                        timeRange = TimeRange.THIS_YEAR
                    )
                    return@launch
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping yearly reminder: no data for THIS_YEAR")
                }
            } else if (isDecemberFirst) {
                android.util.Log.d("HomeViewModel", "December 1st, but already shown: ${preferences.lastYearlyReminderShown}")
            }
            
            // Check for weekly reminder (Sunday)
            val isSunday = today.dayOfWeek == java.time.DayOfWeek.SUNDAY
            if (isSunday && preferences.lastWeeklyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is Sunday, checking data availability...")
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_WEEK)
                android.util.Log.d("HomeViewModel", "Weekly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing WEEKLY Spotlight reminder")
                    armSpotlightReminder(
                        type = me.avinas.tempo.ui.components.SpotlightReminderType.WEEKLY,
                        timeRange = TimeRange.THIS_WEEK
                    )
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping weekly reminder: no data for THIS_WEEK")
                }
            } else if (isSunday) {
                android.util.Log.d("HomeViewModel", "Sunday, but already shown: ${preferences.lastWeeklyReminderShown}")
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
            
            val updatedPrefs = when (_flagsState.value.reminderType) {
                me.avinas.tempo.ui.components.SpotlightReminderType.WEEKLY -> 
                    preferences.copy(lastWeeklyReminderShown = today)
                me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY -> 
                    preferences.copy(lastMonthlyReminderShown = today)
                me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY -> 
                    preferences.copy(lastYearlyReminderShown = today)
                null -> preferences
            }
            
            preferencesRepository.upsert(updatedPrefs)
            
            _flagsState.update {
                it.copy(
                    showSpotlightReminder = false,
                    reminderTimeRange = null,
                    reminderType = null,
                    reminderPreview = null
                )
            }
        }
    }

    /**
     * Persists the current story period key so the ring reads as viewed until a new
     * story period arrives.
     */
    fun onSpotlightViewed() {
        viewModelScope.launch {
            val storyTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                ?: return@launch
            val key = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.storyPeriodKey(storyTimeRange)
            preferencesRepository.updateLastSpotlightStoryViewed(key)
        }
    }
}

@Immutable
data class HomeUiState(
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.THIS_WEEK,
    val hasData: Boolean = false,
    val isNewUser: Boolean = false,
    val isGamificationEnabled: Boolean = true,
    
    // Data fields
    val listeningOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val periodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null,
    val dailyListening: List<me.avinas.tempo.data.stats.DailyListening> = emptyList(),
    val chartLabels: List<String> = emptyList(),
    val dailyAvgMinutes: Long = 0L,
    val activeDaysCount: Int = 0,
    val totalDaysCount: Int = 0,
    val peakDayMinutes: Long = 0L,
    val topTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val topArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats? = null,
    val mostActiveHour: me.avinas.tempo.data.stats.HourlyDistribution? = null,
    val audioFeatures: me.avinas.tempo.data.stats.AudioFeaturesStats? = null,
    val insights: List<me.avinas.tempo.data.stats.InsightCardData> = emptyList(),
    val userName: String? = null,
    val profileImagePath: String? = null,
    
    // Spotlight story top track (correct period, with leeway)
    val spotlightTopTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    
    // Today's Listen Widget
    val todayOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val todayTopTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val todayTopArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val todayTopTracks: List<me.avinas.tempo.data.stats.TopTrack> = emptyList(),
    val todayTopArtists: List<me.avinas.tempo.data.stats.TopArtist> = emptyList(),
    val todayHourlyDistribution: List<me.avinas.tempo.data.stats.HourlyDistribution> = emptyList(),
    val todayPeriodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null
)

/**
 * Transient UI flags that toggle independently of the stats payload. Split out of
 * [HomeUiState] so that a pull-to-refresh spinner toggle or popup dismissal no longer
 * recomposes the whole data feed (which shares a single StateFlow).
 */
@Immutable
data class HomeFlags(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val showRateAppPopup: Boolean = false,
    val showShareNudge: Boolean = false,
    val shareNudgeTimeRange: TimeRange? = null,
    val shareNudgeArtists: List<me.avinas.tempo.data.stats.TopArtist> = emptyList(),
    val shareNudgeOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val showSpotlightReminder: Boolean = false,
    val reminderTimeRange: TimeRange? = null,
    val reminderType: me.avinas.tempo.ui.components.SpotlightReminderType? = null,
    val reminderPreview: me.avinas.tempo.ui.components.SpotlightReminderPreview? = null,
    val spotlightStoryViewed: Boolean = false
)
