package me.avinas.tempo.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import javax.inject.Inject

data class MergeTrackUiState(
    val query: String = "",
    val searchResults: List<Track> = emptyList(),
    val isSearching: Boolean = false,
    val mergeStatus: MergeStatus = MergeStatus.Idle
)

sealed class MergeStatus {
    object Idle : MergeStatus()
    object Processing : MergeStatus()
    object Success : MergeStatus()
    data class Error(val message: String) : MergeStatus()
}

@HiltViewModel
class MergeTrackViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val trackAliasRepository: TrackAliasRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeTrackUiState())
    val uiState: StateFlow<MergeTrackUiState> = _uiState.asStateFlow()
    
    // Store the source track ID
    private var sourceTrackId: Long = -1

    fun setSourceTrackId(id: Long) {
        sourceTrackId = id
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.length >= 2) {
            searchTracks(query)
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    private fun searchTracks(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            try {
                // Use the searchTracks method we added to TrackRepository
                val results = trackRepository.searchTracks(query)
                val filtered = results.filter { 
                    it.id != sourceTrackId // Exclude self
                }.take(20)
                
                _uiState.value = _uiState.value.copy(
                    searchResults = filtered,
                    isSearching = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearching = false)
            }
        }
    }

    fun mergeTracks(targetTrack: Track) {
        if (sourceTrackId == -1L) return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Processing)
            try {
                // 1. Get source track details
                // 2. Create Alias (Source -> Target)
                // 3. Move history (ListeningEvents) from Source to Target
                // 4. Delete Source Track
                
                // This logic should ideally be in a Repository or UseCase
                trackAliasRepository.mergeTracks(sourceTrackId, targetTrack.id)
                
                _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Success)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Error(e.message ?: "Merge failed"))
            }
        }
    }
    
    fun resetStatus() {
        _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Idle)
    }
}
