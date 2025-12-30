package me.avinas.tempo.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.local.dao.HistoryItem
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val listeningRepository: ListeningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        loadHistory()
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            statsRepository.observeListeningOverview(TimeRange.ALL_TIME)
                .collect { _ ->
                    // Refresh history when new listening events are added
                    // Only refresh if we are on the first page to avoid jumping
                    if (_uiState.value.page == 0) {
                        loadHistory()
                    }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500) // Debounce
            _uiState.update { it.copy(page = 0, rawItems = emptyList(), groupedItems = emptyMap(), isLoading = true) }
            loadHistory()
        }
    }

    fun onFilterChanged(startTime: Long?, endTime: Long?, showSkips: Boolean) {
        _uiState.update { it.copy(
            startDate = startTime,
            endDate = endTime,
            showSkips = showSkips,
            page = 0,
            rawItems = emptyList(),
            groupedItems = emptyMap(),
            isLoading = true
        ) }
        loadHistory()
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        loadHistory(isLoadMore = true)
    }

    private fun loadHistory(isLoadMore: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val page = if (isLoadMore) currentState.page + 1 else 0
                
                // Fetch filtered history
                val result = statsRepository.getHistory(
                    timeRange = if (currentState.startDate == null && currentState.endDate == null) TimeRange.ALL_TIME else null,
                    searchQuery = currentState.searchQuery.takeIf { it.isNotBlank() },
                    startTime = currentState.startDate,
                    endTime = currentState.endDate,
                    includeSkips = currentState.showSkips,
                    page = page
                )
                
                val hasMore = result.hasMore

                _uiState.update { state ->
                    val newItems = if (isLoadMore) state.rawItems + result.items else result.items
                    val grouped = groupHistoryItems(newItems)
                    state.copy(
                        rawItems = newItems,
                        groupedItems = grouped,
                        isLoading = false,
                        isLoadingMore = false,
                        page = page,
                        hasMore = hasMore
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message) }
            }
        }
    }

    private fun groupHistoryItems(items: List<HistoryItem>): Map<String, List<HistoryItem>> {
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val yesterday = today.minusDays(1)
        val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

        return items.groupBy { item ->
            val itemDate = Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                itemDate.isEqual(today) -> "Today"
                itemDate.isEqual(yesterday) -> "Yesterday"
                else -> itemDate.format(dateFormatter)
            }
        }
    }

    /**
     * Delete a listening event from the database.
     * Refreshes the history list after successful deletion.
     */
    fun deleteListeningEvent(id: Long) {
        viewModelScope.launch {
            try {
                // Delete the event from database
                listeningRepository.deleteById(id)
                
                // Remove from current UI state
                _uiState.update { state ->
                    val updatedItems = state.rawItems.filter { it.id != id }
                    val grouped = groupHistoryItems(updatedItems)
                    state.copy(
                        rawItems = updatedItems,
                        groupedItems = grouped,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val rawItems: List<HistoryItem> = emptyList(),
    val groupedItems: Map<String, List<HistoryItem>> = emptyMap(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    // Filters
    val searchQuery: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val showSkips: Boolean = true
)
