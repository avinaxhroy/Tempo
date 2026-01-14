package me.avinas.tempo.ui.spotlight

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val cardGenerator: InsightCardGenerator,
    private val statsRepository: StatsRepository,
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState: StateFlow<SpotlightUiState> = _uiState.asStateFlow()

    init {
        checkIfStoryLocked(TimeRange.THIS_YEAR)
        loadCards(TimeRange.THIS_YEAR)
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            statsRepository.observeListeningOverview(_uiState.value.selectedTimeRange)
                .collect { _ ->
                    // Refresh cards when listening overview changes
                    loadCards(_uiState.value.selectedTimeRange)
                    // Re-check lock status (e.g. for All Time if new data comes in, though unlikely to change fast)
                    checkIfStoryLocked(_uiState.value.selectedTimeRange)
                }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = timeRange)
        checkIfStoryLocked(timeRange)
        loadCards(timeRange)
    }

    /**
     * Load cards first (fast path), then load story in background.
     * User sees cards immediately while story loads.
     */
    private fun loadCards(timeRange: TimeRange) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, storyLoading = true)
            try {
                // Cards load first (parallelized)
                val cards = cardGenerator.generateCards(timeRange)
                
                // Pre-warm image cache in background (non-blocking)
                prefetchCardImages(cards)
                
                _uiState.value = _uiState.value.copy(
                    cards = cards,
                    isLoading = false
                )
                
                // Story loads in background - doesn't block card display
                loadStory(timeRange)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, storyLoading = false)
            }
        }
    }
    
    /**
     * Pre-warm the image cache by prefetching all card images in background.
     * This starts loading images before the UI renders, so they're often 
     * already cached when the cards are displayed.
     */
    private fun prefetchCardImages(cards: List<SpotlightCardData>) {
        viewModelScope.launch {
            // Extract all image URLs from cards
            val imageUrls = cards.mapNotNull { card ->
                when (card) {
                    is SpotlightCardData.ForgottenFavorite -> card.albumArtUrl
                    is SpotlightCardData.NewObsession -> card.artistImageUrl
                    is SpotlightCardData.EarlyAdopter -> card.artistImageUrl
                    is SpotlightCardData.ArtistLoyalty -> card.artistImageUrl
                    else -> null
                }
            }.distinct().filterNot { it.isBlank() }
            
            // Enqueue prefetch requests for each image
            imageUrls.forEach { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    // Use typical card display size to reduce memory and decode time
                    .size(300, 300)
                    .build()
                
                imageLoader.enqueue(request)
            }
        }
    }
    
    /**
     * Load story pages in background.
     * Called after cards display - story is usually ready before user taps "Your Wrapped".
     */
    private fun loadStory(timeRange: TimeRange) {
        viewModelScope.launch {
            try {
                val storyPages = cardGenerator.generateStory(timeRange)
                _uiState.value = _uiState.value.copy(
                    storyPages = storyPages,
                    storyLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(storyLoading = false)
            }
        }
    }

    private fun checkIfStoryLocked(timeRange: TimeRange) {
        viewModelScope.launch {
            val now = java.time.LocalDate.now()
            
            var isLocked = false
            var lockMessage = ""
            
            when (timeRange) {
                TimeRange.THIS_MONTH -> {
                    // Lock unless it's the last day of the month
                    val lastDay = now.lengthOfMonth()
                    if (now.dayOfMonth != lastDay) {
                        isLocked = true
                        val monthName = now.month.name.lowercase().replaceFirstChar { it.uppercase() }
                        lockMessage = "Your $monthName Wrapped arrives on ${monthName.take(3)} $lastDay"
                    }
                }
                TimeRange.THIS_YEAR -> {
                    // Lock unless it's December 1st or later
                    if (now.monthValue < 12) {
                        isLocked = true
                        lockMessage = "Your 2026 Wrapped arrives on Dec 1st"
                    }
                }
                TimeRange.ALL_TIME -> {
                    // Lock if data is less than 6 months old
                    val earliestTimestamp = statsRepository.getEarliestDataTimestamp()
                    if (earliestTimestamp != null) {
                        val earliestDate = java.time.Instant.ofEpochMilli(earliestTimestamp)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                        
                        val sixMonthsAgo = now.minusMonths(6)
                        
                        if (earliestDate.isAfter(sixMonthsAgo)) {
                            isLocked = true
                            
                            // Calculate when it unlocks
                            val unlockDate = earliestDate.plusMonths(6)
                            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                            lockMessage = "Needs 6 months of data. Unlocks on ${unlockDate.format(formatter)}"
                        }
                    } else {
                        // No data yet
                        isLocked = true
                        lockMessage = "Start listening to unlock your All Time story"
                    }
                }
                else -> {
                    // Other ranges unlocked by default
                    isLocked = false
                }
            }
            
            _uiState.value = _uiState.value.copy(
                isStoryLocked = isLocked,
                storyLockMessage = lockMessage
            )
        }
    }
}

@Immutable
data class SpotlightUiState(
    val cards: List<SpotlightCardData> = emptyList(),
    val storyPages: List<SpotlightStoryPage> = emptyList(),
    val isLoading: Boolean = true,
    val storyLoading: Boolean = true,
    val selectedTimeRange: TimeRange = TimeRange.THIS_YEAR,
    val isStoryLocked: Boolean = false,
    val storyLockMessage: String = ""
)

