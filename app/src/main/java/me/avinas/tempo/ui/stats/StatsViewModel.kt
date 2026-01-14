package me.avinas.tempo.ui.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()
    
    // Refresh trigger to force data reload with debounce
    private val refreshTrigger = MutableStateFlow(0L)
    
    // Track last loaded analytics time range to skip redundant reloads
    // Analytics is time-range dependent, not tab-dependent
    private var lastAnalyticsTimeRange: TimeRange? = null

    init {
        loadData()
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            // Observe listening overview changes - this triggers when new events are added
            statsRepository.observeListeningOverview(_uiState.value.selectedTimeRange)
                .collect { _ ->
                    // Force refresh when new listening events are detected
                    refreshTrigger.value = System.currentTimeMillis()
                }
        }
        
        viewModelScope.launch {
            // Observe metadata updates - this triggers when content is marked as podcast/audiobook
            // or when track metadata is enriched
            statsRepository.observeMetadataUpdates()
                .collect {
                    // Force refresh when metadata changes (e.g., content marked as podcast)
                    refreshTrigger.value = System.currentTimeMillis()
                }
        }
        
        // React to refresh triggers with debounce to prevent excessive refreshes
        viewModelScope.launch {
            refreshTrigger
                .filter { it > 0 }
                .debounce(500) // Wait 500ms before triggering refresh
                .distinctUntilChanged()
                .collect { _ ->
                    // Invalidate cache and reload data
                    statsRepository.invalidateCache(_uiState.value.selectedTimeRange)
                    loadData()
                }
        }
    }

    fun onTabSelected(tab: StatsTab) {
        _uiState.update { it.copy(selectedTab = tab, isLoading = true, items = emptyList(), page = 0, hasMore = true) }
        loadData()
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.update { it.copy(selectedTimeRange = timeRange, isLoading = true, items = emptyList(), page = 0, hasMore = true) }
        loadData()
    }
    
    fun onSortBySelected(sortBy: SortBy) {
        _uiState.update { it.copy(selectedSortBy = sortBy, isLoading = true, items = emptyList(), page = 0, hasMore = true) }
        loadData()
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        loadData(isLoadMore = true)
    }

    private fun loadData(isLoadMore: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val page = if (isLoadMore) currentState.page + 1 else 0
                val timeRange = currentState.selectedTimeRange
                val sortBy = currentState.selectedSortBy

                val result = when (currentState.selectedTab) {
                    StatsTab.TOP_SONGS -> {
                        val res = statsRepository.getTopTracks(timeRange, sortBy, page)
                        res.items
                    }
                    StatsTab.TOP_ARTISTS -> {
                        val res = statsRepository.getTopArtists(timeRange, sortBy, page)
                        res.items
                    }
                    StatsTab.TOP_ALBUMS -> {
                        val res = statsRepository.getTopAlbums(timeRange, page)
                        res.items
                    }
                }

                val hasMore = result.isNotEmpty() // Simplified check, ideally use totalCount from PaginatedResult

                _uiState.update { state ->
                    val newItems = if (isLoadMore) state.items + result else result
                    state.copy(
                        items = newItems,
                        isLoading = false,
                        isLoadingMore = false,
                        page = page,
                        hasMore = hasMore
                    )
                }
                
                // Fetch analytics data only if time range changed (analytics is time-range dependent, not tab-dependent)
                if (page == 0 && timeRange != lastAnalyticsTimeRange) {
                    lastAnalyticsTimeRange = timeRange
                    loadAnalyticsData(timeRange)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message) }
            }
        }
    }

    private suspend fun loadAnalyticsData(timeRange: TimeRange) {
        // Parallelize the 3 independent analytics calls for ~50% faster loading
        coroutineScope {
            val overviewDeferred = async { statsRepository.getListeningOverview(timeRange) }
            val hourlyDistDeferred = async { statsRepository.getHourlyDistribution(timeRange) }
            val insightsDeferred = async { statsRepository.getInsights(timeRange) }
            
            val overview = overviewDeferred.await()
            val hourlyDist = hourlyDistDeferred.await()
            val insights = insightsDeferred.await()
            
            _uiState.update { 
                it.copy(
                    analyticsData = AnalyticsUiData(
                        overview = overview,
                        hourlyDistribution = hourlyDist,
                        insightCards = insights
                    ),
                    isLoading = false
                ) 
            }
        }
    }

    fun refresh() {
        statsRepository.invalidateCache()
        lastAnalyticsTimeRange = null // Force analytics reload on manual refresh
        _uiState.update { it.copy(isLoading = true, items = emptyList(), page = 0, hasMore = true) }
        loadData()
    }
}

enum class StatsTab {
    TOP_SONGS,
    TOP_ARTISTS,
    TOP_ALBUMS
}

@Immutable
data class StatsUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val selectedTab: StatsTab = StatsTab.TOP_SONGS,
    val selectedTimeRange: TimeRange = TimeRange.THIS_WEEK,
    val selectedSortBy: SortBy = SortBy.COMBINED_SCORE, // Default to combined score
    val items: List<Any> = emptyList(), // Can be TopTrack, TopArtist, or TopAlbum
    val page: Int = 0,
    val hasMore: Boolean = true,
    val analyticsData: AnalyticsUiData? = null
)

@Immutable
data class AnalyticsUiData(
    val overview: me.avinas.tempo.data.stats.ListeningOverview,
    val hourlyDistribution: List<me.avinas.tempo.data.stats.HourlyDistribution>,
    val insightCards: List<me.avinas.tempo.data.stats.InsightCardData>
)
