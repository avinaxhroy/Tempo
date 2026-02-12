package me.avinas.tempo.ui.details

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.ArtistDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ArtistDetailsViewModel"
    }

    // Get artist ID or name from saved state (for deep linking / process death)
    private val savedArtistId: Long = savedStateHandle["artistId"] ?: 0L
    private val savedArtistName: String? = savedStateHandle["artistName"]
    
    // Current loading identifiers
    private var currentArtistId: Long? = null
    private var currentArtistName: String? = null

    private val _uiState = MutableStateFlow(ArtistDetailsUiState())
    val uiState: StateFlow<ArtistDetailsUiState> = _uiState.asStateFlow()

    init {
        // Load from saved state if available
        if (savedArtistId > 0) {
            loadArtistById(savedArtistId)
        } else if (!savedArtistName.isNullOrEmpty()) {
            loadArtistByName(savedArtistName)
        }
    }

    fun loadArtistById(artistId: Long) {
        if (artistId <= 0) return
        if (currentArtistId == artistId && _uiState.value.artistDetails != null) return
        
        currentArtistId = artistId
        currentArtistName = null
        
        Log.d(TAG, "Loading details for artist ID: $artistId")
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // First invalidate cache to ensure fresh data
                statsRepository.invalidateCache()
                // Also clear artist image search cache to retry image fetching
                statsRepository.clearArtistImageSearchCache()
                
                val details = statsRepository.getArtistDetails(artistId)
                
                Log.d(TAG, "Successfully loaded details for artist ID $artistId: name=${details.artist.name}, playCount=${details.personalPlayCount}, imageUrl=${details.artist.imageUrl}")
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        artistDetails = details,
                        error = null
                    ) 
                }
                
                // Fetch percentile
                if (details.personalPlayCount > 0) {
                     val percentile = statsRepository.getArtistRankPercentile(
                         details.personalPlayCount, 
                         me.avinas.tempo.data.stats.TimeRange.ALL_TIME
                     )
                     _uiState.update { it.copy(artistPercentile = percentile) }
                }
            } catch (e: NoSuchElementException) {
            } catch (e: NoSuchElementException) {
                Log.w(TAG, "Artist not found with ID: $artistId")
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = "Artist not found"
                    ) 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artist details for ID $artistId", e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load artist details"
                    ) 
                }
            }
        }
    }
    
    fun loadArtistByName(artistName: String) {
        if (artistName.isBlank()) return
        if (currentArtistName == artistName && _uiState.value.artistDetails != null) return
        
        currentArtistName = artistName
        currentArtistId = null
        
        Log.d(TAG, "Loading details for artist name: '$artistName'")
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // First invalidate cache to ensure fresh data
                statsRepository.invalidateCache()
                // Also clear artist image search cache to retry image fetching
                statsRepository.clearArtistImageSearchCache()
                
                val details = statsRepository.getArtistDetailsByName(artistName)
                
                if (details != null) {
                    Log.d(TAG, "Successfully loaded details for '$artistName': playCount=${details.personalPlayCount}, imageUrl=${details.artist.imageUrl}")
                } else {
                    Log.w(TAG, "No details found for artist: '$artistName'")
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        artistDetails = details,
                        error = if (details == null) "No listening history found for $artistName" else null
                    ) 
                }
                
                // Fetch percentile if details loaded successfully
                if (details != null && details.personalPlayCount > 0) {
                     val percentile = statsRepository.getArtistRankPercentile(
                         details.personalPlayCount, 
                         me.avinas.tempo.data.stats.TimeRange.ALL_TIME
                     )
                     _uiState.update { it.copy(artistPercentile = percentile) }
                }

            } catch (e: Exception) {
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artist details for '$artistName'", e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load artist details"
                    ) 
                }
            }
        }
    }
    
    fun retry() {
        when {
            currentArtistId != null && currentArtistId!! > 0 -> loadArtistById(currentArtistId!!)
            !currentArtistName.isNullOrBlank() -> loadArtistByName(currentArtistName!!)
        }
    }
}

data class ArtistDetailsUiState(
    val isLoading: Boolean = true,
    val artistDetails: ArtistDetails? = null,
    val artistPercentile: Double? = null,
    val error: String? = null
)
