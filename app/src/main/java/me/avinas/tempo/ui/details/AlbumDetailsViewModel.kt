package me.avinas.tempo.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.AlbumDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    private val _uiState = MutableStateFlow(AlbumDetailsUiState())
    val uiState: StateFlow<AlbumDetailsUiState> = _uiState.asStateFlow()

    init {
        loadAlbumDetails()
    }

    private fun loadAlbumDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val details = statsRepository.getAlbumDetails(albumId)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        albumDetails = details
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load album details"
                    ) 
                }
            }
        }
    }
}

data class AlbumDetailsUiState(
    val isLoading: Boolean = true,
    val albumDetails: AlbumDetails? = null,
    val error: String? = null
)
