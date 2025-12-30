package me.avinas.tempo.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Song Details screen.
 * 
 * Data Flow Pattern: Enrichment → Database → UI
 * - This ViewModel ONLY reads from database via Repository (never makes API calls)
 * - Track metadata is fetched from database cache
 * - Mood/genre derived from MusicBrainz tags
 * - Engagement metrics computed from listening behavior
 * - Background EnrichmentWorker keeps the data fresh via API calls
 * - UI always displays cached data for fast, offline-first experience
 */
@HiltViewModel
class SongDetailsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val trackId: Long = checkNotNull(savedStateHandle["trackId"])

    private val _uiState = MutableStateFlow(SongDetailsUiState())
    val uiState: StateFlow<SongDetailsUiState> = _uiState.asStateFlow()

    init {
        loadTrackDetails()
    }

    /**
     * Load track details from database (cached enriched data).
     * No API calls are made - all data comes from locally cached enrichment.
     */
    private fun loadTrackDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val details = statsRepository.getTrackDetails(trackId)
                val history = statsRepository.getTrackListeningHistory(trackId, TimeRange.ALL_TIME)
                
                // Get enriched metadata from database cache (no API call)
                // This data was populated by EnrichmentWorker in background
                val enrichedMetadata = enrichedMetadataRepository.forTrackSync(trackId)
                
                // Derive mood from MusicBrainz tags instead of Spotify audio features
                val moodSummary = if (enrichedMetadata != null) {
                    val tags = enrichedMetadata.tags
                    val genres = enrichedMetadata.genres
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        TagBasedMoodAnalyzer.getMoodSummary(tags, genres)
                    } else null
                } else null
                
                // Get engagement metrics from user behavior
                val engagement = statsRepository.getTrackEngagement(trackId)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        trackDetails = details,
                        listeningHistory = history,
                        moodSummary = moodSummary,
                        engagement = engagement,
                        genre = enrichedMetadata?.genres?.firstOrNull() 
                            ?: enrichedMetadata?.tags?.firstOrNull(),
                        releaseDate = enrichedMetadata?.releaseDateFull ?: enrichedMetadata?.releaseDate,
                        releaseYear = enrichedMetadata?.releaseYear,
                        recordLabel = enrichedMetadata?.recordLabel
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load track details"
                    ) 
                }
            }
        }
    }

    fun refresh() {
        loadTrackDetails()
    }
}

data class SongDetailsUiState(
    val isLoading: Boolean = true,
    val trackDetails: TrackDetails? = null,
    val listeningHistory: List<DailyListening> = emptyList(),
    val moodSummary: TagBasedMoodAnalyzer.MoodSummary? = null,
    val engagement: TrackEngagement? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val error: String? = null
)
