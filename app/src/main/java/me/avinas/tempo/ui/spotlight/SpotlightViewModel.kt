package me.avinas.tempo.ui.spotlight

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.PreferencesRepository
import me.avinas.tempo.data.spotify.SpotifyHistoryReconstructionService
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val cardGenerator: InsightCardGenerator,
    private val statsRepository: StatsRepository,
    private val imageLoader: ImageLoader,
    private val preferencesRepository: PreferencesRepository,
    private val spotifyReconstructionService: SpotifyHistoryReconstructionService,
    private val lastFmImportService: LastFmImportService,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState: StateFlow<SpotlightUiState> = _uiState.asStateFlow()
    private var cardsJob: Job? = null
    private var storyJob: Job? = null

    private val isSyncActive = combine(
        spotifyReconstructionService.isReconstructing,
        lastFmImportService.progress,
        preferencesRepository.preferences()
    ) { spotifyReconstructing, lastFmProgress, prefs ->
        val spotifyLinked = prefs?.spotifyLinked ?: false
        val spotifyImportCompleted = prefs?.lastSpotifyImportTimestamp != null
        val spotifyInitialImportPending = spotifyLinked && !spotifyImportCompleted
        
        val lastFmImportActive = lastFmProgress !is LastFmImportService.ImportProgress.Idle &&
                                 lastFmProgress !is LastFmImportService.ImportProgress.Completed &&
                                 lastFmProgress !is LastFmImportService.ImportProgress.Failed
                                 
        spotifyReconstructing || lastFmImportActive || spotifyInitialImportPending
    }.distinctUntilChanged()

    init {
        checkIfStoryLocked(TimeRange.THIS_MONTH)
        loadCards(TimeRange.THIS_MONTH)
        observeDataChanges()
        observeSyncStatus()
    }

    private fun observeSyncStatus() {
        viewModelScope.launch {
            isSyncActive.collect { active ->
                if (active) {
                    if (_uiState.value.cards.isEmpty()) {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                } else {
                    val range = _uiState.value.selectedTimeRange
                    val hasCards = _uiState.value.cards.isNotEmpty()
                    loadCards(range, silent = hasCards)
                }
            }
        }
    }

    
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDataChanges() {
        viewModelScope.launch {
            // flatMapLatest ensures that whenever selectedTimeRange changes, we cancel the old
            // repository subscription and immediately re-subscribe with the new range.
            // This prevents stale THIS_MONTH data being pushed when a different filter is active.
            _uiState
                .map { it.selectedTimeRange }
                .distinctUntilChanged()
                .flatMapLatest { range ->
                    statsRepository.observeListeningOverview(range)
                }
                .collect { _ ->
                    val range = _uiState.value.selectedTimeRange
                    // The Room Flow emits the current DB state immediately on collection.
                    // This re-fire catches data that the initial loadCards() may have missed
                    // (e.g. DB queries racing, data settling on cold start).
                    // Use silent mode when cards are already displayed so we don't flash
                    // a loading spinner over visible content.
                    val hasCards = _uiState.value.cards.isNotEmpty()
                    loadCards(range, silent = hasCards)
                    checkIfStoryLocked(range)
                }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        if (timeRange == _uiState.value.selectedTimeRange) return
        _uiState.value = _uiState.value.copy(selectedTimeRange = timeRange)
        checkIfStoryLocked(timeRange)
        loadCards(timeRange)
    }

    /**
     * Load cards first (fast path), then load story in background.
     * User sees cards immediately while story loads.
     *
     * @param silent When `true`, cards are re-fetched without flashing the loading
     *   state over already-visible content (e.g. reactive data-change refresh).
     *   When `false` (initial load, time-range switch), the loading indicator is shown.
     */
    private fun loadCards(timeRange: TimeRange, silent: Boolean = false) {
        cardsJob?.cancel()
        storyJob?.cancel()
        cardsJob = viewModelScope.launch {
            if (!silent) {
                _uiState.value = _uiState.value.copy(isLoading = true, storyLoading = true)
            }
            try {
                // Cards load first (parallelized)
                val cards = cardGenerator.generateCards(timeRange)
                if (_uiState.value.selectedTimeRange != timeRange) return@launch
                
                // Pre-warm image cache in background (non-blocking)
                prefetchCardImages(cards)
                
                val syncActive = isSyncActive.first()
                _uiState.value = _uiState.value.copy(
                    cards = cards,
                    isLoading = syncActive && cards.isEmpty()
                )
                
                // Story loads in background - doesn't block card display
                loadStory(timeRange)
            } catch (e: Exception) {
                val syncActive = isSyncActive.first()
                _uiState.value = _uiState.value.copy(
                    isLoading = syncActive && _uiState.value.cards.isEmpty(),
                    storyLoading = false
                )
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
        storyJob?.cancel()
        storyJob = viewModelScope.launch {
            try {
                val storyPages = cardGenerator.generateStory(timeRange)
                if (_uiState.value.selectedTimeRange != timeRange) return@launch
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
                    // Lock unless it's the last day of the month or within the first 3 days of the next month
                    val lastDay = now.lengthOfMonth()
                    if (now.dayOfMonth > 3 && now.dayOfMonth != lastDay) {
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
                TimeRange.THIS_WEEK -> {
                    // Lock unless it's Sunday or within the first 3 days of the new week
                    if (now.dayOfWeek != java.time.DayOfWeek.SUNDAY && now.dayOfWeek.value > 3) {
                        isLocked = true
                        lockMessage = "Your Weekly Wrapped arrives on Sunday"
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
    val selectedTimeRange: TimeRange = TimeRange.THIS_MONTH,
    val isStoryLocked: Boolean = false,
    val storyLockMessage: String = ""
)
