package me.avinas.tempo.ui.spotlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val cardGenerator: InsightCardGenerator,
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState: StateFlow<SpotlightUiState> = _uiState.asStateFlow()

    init {
        loadCards(TimeRange.THIS_YEAR)
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            statsRepository.observeListeningOverview(_uiState.value.selectedTimeRange)
                .collect { _ ->
                    // Refresh cards when listening overview changes
                    loadCards(_uiState.value.selectedTimeRange)
                }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = timeRange)
        loadCards(timeRange)
    }

    private fun loadCards(timeRange: TimeRange) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val cards = cardGenerator.generateCards(timeRange)
                val storyPages = cardGenerator.generateStory(timeRange)
                _uiState.value = _uiState.value.copy(
                    cards = cards,
                    storyPages = storyPages,
                    isLoading = false
                )
            } catch (e: Exception) {
                // Handle error
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}

data class SpotlightUiState(
    val cards: List<SpotlightCardData> = emptyList(),
    val storyPages: List<SpotlightStoryPage> = emptyList(),
    val isLoading: Boolean = true,
    val selectedTimeRange: TimeRange = TimeRange.THIS_YEAR
)
