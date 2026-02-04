package me.avinas.tempo.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.repository.ArtistMergeRepository
import javax.inject.Inject

/**
 * UI state for artist merge dialog.
 */
data class MergeArtistUiState(
    val query: String = "",
    val searchResults: List<Artist> = emptyList(),
    val isSearching: Boolean = false,
    val mergeStatus: ArtistMergeStatus = ArtistMergeStatus.Idle,
    val pendingMergeTarget: Artist? = null  // For confirmation before merge
)

/**
 * Status of artist merge operation.
 */
sealed class ArtistMergeStatus {
    object Idle : ArtistMergeStatus()
    object Processing : ArtistMergeStatus()
    object Success : ArtistMergeStatus()
    data class Error(val message: String) : ArtistMergeStatus()
}

/**
 * ViewModel for merging artists.
 * 
 * Allows users to search for a target artist and merge the source artist into it.
 * After merge:
 * - All tracks from source artist are re-linked to target artist
 * - Source artist is deleted
 * - An alias is created so future plays with source name go to target
 */
@HiltViewModel
class MergeArtistViewModel @Inject constructor(
    private val artistMergeRepository: ArtistMergeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeArtistUiState())
    val uiState: StateFlow<MergeArtistUiState> = _uiState.asStateFlow()

    // Store the source artist ID (the one being merged FROM)
    private var sourceArtistId: Long = -1

    /**
     * Set the source artist ID (the artist to be merged into another).
     * Always resets the UI state for a fresh dialog.
     */
    fun setSourceArtistId(id: Long) {
        // Always reset state when dialog opens - this fixes issue where
        // previous error/success states persisted if same artist selected again
        _uiState.value = MergeArtistUiState()
        sourceArtistId = id
    }

    /**
     * Handle search query changes.
     */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.length >= 2) {
            searchArtists(query)
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    /**
     * Search for artists matching the query.
     * Excludes the source artist from results.
     */
    private fun searchArtists(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            try {
                val results = artistMergeRepository.searchArtists(
                    query = query, 
                    excludeArtistId = if (sourceArtistId > 0) sourceArtistId else null
                )
                // Limit results to 20 for UI performance
                val filtered = results.take(20)
                
                _uiState.value = _uiState.value.copy(
                    searchResults = filtered,
                    isSearching = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    mergeStatus = ArtistMergeStatus.Error(e.message ?: "Search failed")
                )
            }
        }
    }

    /**
     * Select an artist as the merge target, showing confirmation first.
     * This is the first step - does NOT execute the merge yet.
     * 
     * @param targetArtist The artist to potentially merge INTO
     */
    fun selectArtistForMerge(targetArtist: Artist) {
        _uiState.value = _uiState.value.copy(pendingMergeTarget = targetArtist)
    }
    
    /**
     * Cancel the pending merge and clear selection.
     */
    fun cancelMerge() {
        _uiState.value = _uiState.value.copy(pendingMergeTarget = null)
    }
    
    /**
     * Confirm and execute the merge with the pending target.
     * This is the second step - actually performs the merge.
     */
    fun confirmMerge() {
        val targetArtist = _uiState.value.pendingMergeTarget ?: return
        
        if (sourceArtistId <= 0) {
            _uiState.value = _uiState.value.copy(
                mergeStatus = ArtistMergeStatus.Error("Source artist not set"),
                pendingMergeTarget = null
            )
            return
        }
        
        // Prevent merging while already processing
        if (_uiState.value.mergeStatus is ArtistMergeStatus.Processing) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                mergeStatus = ArtistMergeStatus.Processing,
                pendingMergeTarget = null
            )
            try {
                val success = artistMergeRepository.mergeArtists(
                    sourceArtistId = sourceArtistId,
                    targetArtistId = targetArtist.id
                )

                if (success) {
                    _uiState.value = _uiState.value.copy(mergeStatus = ArtistMergeStatus.Success)
                } else {
                    _uiState.value = _uiState.value.copy(
                        mergeStatus = ArtistMergeStatus.Error("Merge failed")
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    mergeStatus = ArtistMergeStatus.Error(e.message ?: "Merge failed")
                )
            }
        }
    }
    
    /**
     * Legacy method for direct merge without confirmation.
     * @deprecated Use selectArtistForMerge + confirmMerge instead
     */
    @Deprecated("Use selectArtistForMerge + confirmMerge for confirmation flow")
    fun mergeArtists(targetArtist: Artist) {
        selectArtistForMerge(targetArtist)
        confirmMerge()
    }

    /**
     * Reset the merge status to idle.
     */
    fun resetStatus() {
        _uiState.value = _uiState.value.copy(mergeStatus = ArtistMergeStatus.Idle)
    }

    /**
     * Reset the entire UI state.
     */
    fun reset() {
        sourceArtistId = -1
        _uiState.value = MergeArtistUiState()
    }
}
