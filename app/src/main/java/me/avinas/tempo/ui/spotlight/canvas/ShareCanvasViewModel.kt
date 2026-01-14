package me.avinas.tempo.ui.spotlight.canvas

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.spotlight.InsightCardGenerator
import me.avinas.tempo.ui.spotlight.SpotlightCardData
import me.avinas.tempo.data.stats.TimeRange
import javax.inject.Inject

@HiltViewModel
class ShareCanvasViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val insightCardGenerator: InsightCardGenerator
) : ViewModel() {
    
    private val _allCards = MutableStateFlow<List<SpotlightCardData>>(emptyList())
    val allCards: StateFlow<List<SpotlightCardData>> = _allCards.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    val initialCardId: String? = savedStateHandle.get<String>("initialCardId")?.takeIf { it != "_empty_" }
    
    // Card types that have Dashboard UI composables
    private val supportedCardTypes = setOf(
        "cosmic_clock",
        "weekend_warrior", 
        "forgotten_favorite",
        "deep_dive",
        "new_obsession",
        "early_adopter",
        "listening_peak",
        "artist_loyalty",
        "discovery"
    )
    
    init {
        loadCards()
    }
    
    private fun loadCards() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Generate cards for ALL time ranges to maximize card availability
                // This ensures we find the initial card regardless of which time range it was generated for
                val timeRanges = listOf(TimeRange.ALL_TIME, TimeRange.THIS_MONTH, TimeRange.THIS_WEEK)
                
                val allCardsFromAllRanges = timeRanges.map { range ->
                    async {
                        try {
                            insightCardGenerator.generateCards(range)
                        } catch (e: Exception) {
                            android.util.Log.w("ShareCanvasViewModel", "Failed to load cards for $range", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
                
                // Deduplicate by card ID (prefer the first occurrence which is ALL_TIME)
                val uniqueCards = allCardsFromAllRanges
                    .distinctBy { it.id }
                    .filter { it.id in supportedCardTypes }
                
                _allCards.value = uniqueCards
                
                android.util.Log.d("ShareCanvasViewModel", "Loaded ${uniqueCards.size} unique cards: ${uniqueCards.map { it.id }}")
                android.util.Log.d("ShareCanvasViewModel", "Looking for initialCardId: $initialCardId")
                
            } catch (e: Exception) {
                android.util.Log.e("ShareCanvasViewModel", "Failed to load cards", e)
                _allCards.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun getInitialCard(): SpotlightCardData? {
        val card = _allCards.value.find { it.id == initialCardId }
        android.util.Log.d("ShareCanvasViewModel", "getInitialCard(): found=${card != null}, id=$initialCardId")
        return card
    }
}
