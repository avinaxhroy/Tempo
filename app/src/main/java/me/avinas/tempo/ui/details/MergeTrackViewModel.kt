package me.avinas.tempo.ui.details

import android.util.Log
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
    val mergeStatus: MergeStatus = MergeStatus.Idle,
    val pendingMergeTarget: Track? = null  // For confirmation before merge
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

    companion object {
        private const val TAG = "MergeTrackViewModel"
    }

    private val _uiState = MutableStateFlow(MergeTrackUiState())
    val uiState: StateFlow<MergeTrackUiState> = _uiState.asStateFlow()
    
    // Store the source track ID
    private var sourceTrackId: Long = -1

    /**
     * Set the source track ID (the track to be merged into another).
     * Always resets the UI state for a fresh dialog.
     */
    fun setSourceTrackId(id: Long) {
        // Always reset state when dialog opens - this fixes issue where
        // previous error/success states persisted if same track selected again
        _uiState.value = MergeTrackUiState()
        sourceTrackId = id
    }

    /**
     * Handle search query changes.
     */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.length >= 2) {
            searchTracks(query)
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    /**
     * Search for tracks matching the query.
     * Excludes the source track from results.
     */
    private fun searchTracks(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            try {
                val results = trackRepository.searchTracks(query)
                val filtered = results.filter { 
                    it.id != sourceTrackId // Exclude self
                }.take(20)
                
                _uiState.value = _uiState.value.copy(
                    searchResults = filtered,
                    isSearching = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    mergeStatus = MergeStatus.Error(e.message ?: "Search failed")
                )
            }
        }
    }

    /**
     * Select a track as the merge target, showing confirmation first.
     * This is the first step - does NOT execute the merge yet.
     *
     * @param targetTrack The track to potentially merge INTO
     */
    fun selectTrackForMerge(targetTrack: Track) {
        _uiState.value = _uiState.value.copy(pendingMergeTarget = targetTrack)
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
        val targetTrack = _uiState.value.pendingMergeTarget ?: return

        if (sourceTrackId <= 0) {
            _uiState.value = _uiState.value.copy(
                mergeStatus = MergeStatus.Error("Source track not set"),
                pendingMergeTarget = null
            )
            return
        }

        // Prevent merging while already processing
        if (_uiState.value.mergeStatus is MergeStatus.Processing) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                mergeStatus = MergeStatus.Processing,
                pendingMergeTarget = null
            )
            try {
                val success = trackAliasRepository.mergeTracks(sourceTrackId, targetTrack.id)
                
                if (success) {
                    _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Success)
                } else {
                    _uiState.value = _uiState.value.copy(
                        mergeStatus = MergeStatus.Error("Merge failed")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Merge failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    mergeStatus = MergeStatus.Error(e.message ?: "Merge failed")
                )
            }
        }
    }

    /**
     * Legacy method for direct merge without confirmation.
     * @deprecated Use selectTrackForMerge + confirmMerge instead
     */
    @Deprecated("Use selectTrackForMerge + confirmMerge for confirmation flow")
    fun mergeTracks(targetTrack: Track) {
        selectTrackForMerge(targetTrack)
        confirmMerge()
    }

    /**
     * Reset the merge status to idle.
     */
    fun resetStatus() {
        _uiState.value = _uiState.value.copy(mergeStatus = MergeStatus.Idle)
    }

    /**
     * Reset the entire UI state.
     */
    fun reset() {
        sourceTrackId = -1
        _uiState.value = MergeTrackUiState()
    }
}
