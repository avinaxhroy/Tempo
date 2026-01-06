package me.avinas.tempo.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.local.dao.HistoryItem
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val listeningRepository: ListeningRepository,
    private val trackRepository: TrackRepository,
    private val manualContentMarkDao: me.avinas.tempo.data.local.dao.ManualContentMarkDao,
    private val userPreferencesDao: me.avinas.tempo.data.local.dao.UserPreferencesDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        loadHistory()
        observeDataChanges()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            statsRepository.observeListeningOverview(TimeRange.ALL_TIME)
                .collect { _ ->
                    // Refresh history when new listening events are added
                    // Only refresh if we are on the first page to avoid jumping
                    // Also skip if we're in the middle of a delete operation to prevent
                    // overwriting optimistic UI updates
                    if (_uiState.value.page == 0 && !suppressObserverRefresh) {
                        loadHistory()
                    }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500) // Debounce
            _uiState.update { it.copy(page = 0, rawItems = emptyList(), groupedItems = emptyMap(), isLoading = true) }
            loadHistory()
        }
    }

    fun onFilterChanged(startTime: Long?, endTime: Long?, showSkips: Boolean) {
        _uiState.update { it.copy(
            startDate = startTime,
            endDate = endTime,
            showSkips = showSkips,
            page = 0,
            rawItems = emptyList(),
            groupedItems = emptyMap(),
            isLoading = true
        ) }
        loadHistory()
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        loadHistory(isLoadMore = true)
    }

    private fun loadHistory(isLoadMore: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val page = if (isLoadMore) currentState.page + 1 else 0
                
                // Get user preferences for content filtering (use defaults if null)
                val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                val filterPodcasts = prefs.filterPodcasts
                val filterAudiobooks = prefs.filterAudiobooks
                
                // Fetch filtered history
                val result = statsRepository.getHistory(
                    timeRange = if (currentState.startDate == null && currentState.endDate == null) TimeRange.ALL_TIME else null,
                    searchQuery = currentState.searchQuery.takeIf { it.isNotBlank() },
                    startTime = currentState.startDate,
                    endTime = currentState.endDate,
                    includeSkips = currentState.showSkips,
                    filterPodcasts = filterPodcasts,
                    filterAudiobooks = filterAudiobooks,
                    page = page
                )
                
                val hasMore = result.hasMore
                val newItems = if (_uiState.value.rawItems.isEmpty() || !isLoadMore) result.items else _uiState.value.rawItems + result.items
                
                // Compute coach mark BEFORE update (suspend function can't run in update block)
                val shouldShowCoachMark = checkShouldShowCoachMark(newItems)

                _uiState.update { state ->
                    val grouped = groupHistoryItems(newItems)
                    state.copy(
                        rawItems = newItems,
                        groupedItems = grouped,
                        isLoading = false,
                        isLoadingMore = false,
                        page = page,
                        hasMore = hasMore,
                        showCoachMark = shouldShowCoachMark
                    )
                }
            } catch (e: Exception) {
                val currentItems = _uiState.value.rawItems
                val shouldShowCoachMark = checkShouldShowCoachMark(currentItems)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = e.message,
                        showCoachMark = shouldShowCoachMark
                    ) 
                }
            }
        }
    }

    private fun groupHistoryItems(items: List<HistoryItem>): Map<String, List<HistoryItem>> {
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val yesterday = today.minusDays(1)
        val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

        return items.groupBy { item ->
            val itemDate = Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                itemDate.isEqual(today) -> "Today"
                itemDate.isEqual(yesterday) -> "Yesterday"
                else -> itemDate.format(dateFormatter)
            }
        }
    }

    // Track pending deletes to prevent double-delete attempts
    private val pendingDeletes = mutableSetOf<Long>()
    
    // Suppress observer-triggered refresh during delete operations
    // This prevents race conditions where loadHistory() from observer
    // would override our optimistic UI updates
    @Volatile
    private var suppressObserverRefresh = false

    /**
     * Delete a listening event from the database.
     * Also deletes the associated track if it has no other listening events.
     * Updates UI immediately for instant feedback (optimistic update).
     */
    fun deleteListeningEvent(id: Long) {
        // Prevent duplicate delete attempts (e.g., user tapping multiple times quickly)
        if (pendingDeletes.contains(id)) {
            Log.d(TAG, "Delete already in progress for event $id, ignoring duplicate request")
            return
        }
        
        viewModelScope.launch {
            try {
                // Mark as pending and suppress observer refresh
                pendingDeletes.add(id)
                suppressObserverRefresh = true
                
                // Get track_id and item info before deleting
                val historyItem = _uiState.value.rawItems.find { it.id == id }
                val trackId = historyItem?.track_id
                
                if (trackId == null) {
                    Log.w(TAG, "Could not find track_id for listening event $id")
                    _uiState.update { it.copy(error = "Could not find item to delete") }
                    pendingDeletes.remove(id)
                    suppressObserverRefresh = false
                    return@launch
                }
                
                // OPTIMISTIC UPDATE: Remove from UI immediately for instant feedback
                val previousState = _uiState.value
                _uiState.update { state ->
                    val updatedItems = state.rawItems.filter { it.id != id }
                    val grouped = groupHistoryItems(updatedItems)
                    state.copy(
                        rawItems = updatedItems,
                        groupedItems = grouped,
                        error = null
                    )
                }
                
                // Now perform database deletion in the background
                val deletedRows = listeningRepository.deleteById(id)
                
                if (deletedRows > 0) {
                    Log.d(TAG, "Successfully deleted listening event $id (deleted $deletedRows row)")
                    
                    // Check if the track has any other listening events
                    val remainingEvents = listeningRepository.getEventsForTrack(trackId)
                    
                    if (remainingEvents.isEmpty()) {
                        // Track is orphaned, delete it
                        val deletedTrackRows = trackRepository.deleteById(trackId)
                        Log.d(TAG, "Deleted orphaned track $trackId (deleted $deletedTrackRows row)")
                    } else {
                        Log.d(TAG, "Track $trackId still has ${remainingEvents.size} listening event(s), keeping it")
                    }
                    
                    // Keep suppress flag on briefly to let DB changes propagate
                    // then allow observer refreshes again
                    kotlinx.coroutines.delay(500)
                } else {
                    // Database reports 0 rows affected
                    // This could mean: already deleted (by another process), or error
                    // Check if item was already removed from our UI state (already deleted)
                    if (!previousState.rawItems.any { it.id == id }) {
                        // Item wasn't even in our list - already deleted, no rollback needed
                        Log.d(TAG, "Event $id was already deleted (not in previous state)")
                    } else {
                        // Rollback: Database deletion failed, restore previous state
                        Log.w(TAG, "Failed to delete listening event $id - no rows affected, rolling back UI")
                        _uiState.update { previousState.copy(error = "Failed to delete: No rows affected") }
                    }
                }
            } catch (e: Exception) {
                // On error, reload the history to get the correct state
                Log.e(TAG, "Error deleting listening event $id", e)
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
                loadHistory() // Reload to ensure UI matches database
            } finally {
                // Always remove from pending set and re-enable observer
                pendingDeletes.remove(id)
                suppressObserverRefresh = false
            }
        }
    }

    /**
     * Mark a track as a specific content type (PODCAST/AUDIOBOOK).
     * This will:
     * 1. Save the pattern to block future content from this title+artist
     * 2. Delete all listening events for this track
     * 3. Delete the track itself from database
     * 4. Enable the corresponding filter if not already enabled
     */
    fun markContent(trackId: Long, contentType: String, deleteFromHistory: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMarking = true) }
            try {
                val track = trackRepository.getById(trackId).first() ?: run {
                    _uiState.update { it.copy(isMarking = false) }
                    return@launch
                }
                
                // 1. Save the block pattern for future content
                val mark = me.avinas.tempo.data.local.entities.ManualContentMark(
                    targetTrackId = trackId,
                    patternType = "TITLE_ARTIST",
                    originalTitle = track.title,
                    originalArtist = track.artist,
                    patternValue = track.title,
                    contentType = contentType,
                    markedAt = System.currentTimeMillis()
                )
                manualContentMarkDao.insertMark(mark)
                Log.d(TAG, "Saved block pattern for '${track.title}' by '${track.artist}' as $contentType")
                
                // 2. Enable content filtering if not already enabled
                val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                val updatedPrefs = when (contentType) {
                    "PODCAST" -> if (!prefs.filterPodcasts) prefs.copy(filterPodcasts = true) else prefs
                    "AUDIOBOOK" -> if (!prefs.filterAudiobooks) prefs.copy(filterAudiobooks = true) else prefs
                    else -> prefs
                }
                
                // Also dismiss coach mark since user discovered the feature
                val finalPrefs = if (!prefs.hasSeenHistoryCoachMark) {
                    updatedPrefs.copy(hasSeenHistoryCoachMark = true)
                } else {
                    updatedPrefs
                }
                userPreferencesDao.upsert(finalPrefs)
                
                // 3. Delete all listening events for this track
                val deletedEvents = listeningRepository.getEventsForTrack(trackId)
                deletedEvents.forEach { event ->
                    listeningRepository.deleteById(event.id)
                }
                Log.d(TAG, "Deleted ${deletedEvents.size} listening events for track $trackId")
                
                // 4. Delete the track itself
                trackRepository.deleteById(trackId)
                Log.d(TAG, "Deleted track $trackId from database")
                
                // Show success feedback
                val contentTypeName = contentType.lowercase().replaceFirstChar { it.uppercase() }
                val feedbackMsg = "Blocked \"${track.title}\" - removed from history & stats"
                _uiState.update { it.copy(showCoachMark = false, feedbackMessage = feedbackMsg, isMarking = false) }
                
                // Invalidate stats cache
                statsRepository.invalidateCache()
                
                // Refresh history
                loadHistory()

            } catch (e: Exception) {
                Log.e(TAG, "Error marking content", e)
                _uiState.update { it.copy(error = "Failed to block content: ${e.message}", isMarking = false) }
            }
        }
    }

    /**
     * Mark an artist as a specific content type (PODCAST/AUDIOBOOK).
     * This will:
     * 1. Save the pattern to block ALL future content from this artist
     * 2. Delete ALL listening events from this artist
     * 3. Delete ALL tracks from this artist
     * 4. Enable the corresponding filter if not already enabled
     */
    fun markArtistContent(trackId: Long, contentType: String, deleteFromHistory: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMarking = true) }
            try {
                val track = trackRepository.getById(trackId).first() ?: run {
                    _uiState.update { it.copy(isMarking = false) }
                    return@launch
                }
                
                val artistName = track.artist

                // 1. Save the artist-level block pattern for future content
                val mark = me.avinas.tempo.data.local.entities.ManualContentMark(
                    targetTrackId = trackId,
                    patternType = "ARTIST",
                    originalTitle = "", // Empty - we match by artist only
                    originalArtist = artistName,
                    patternValue = artistName,
                    contentType = contentType,
                    markedAt = System.currentTimeMillis()
                )
                manualContentMarkDao.insertMark(mark)
                Log.d(TAG, "Saved artist block pattern for '$artistName' as $contentType")

                // 2. Enable content filtering if not already enabled
                val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                val updatedPrefs = when (contentType) {
                    "PODCAST" -> if (!prefs.filterPodcasts) prefs.copy(filterPodcasts = true) else prefs
                    "AUDIOBOOK" -> if (!prefs.filterAudiobooks) prefs.copy(filterAudiobooks = true) else prefs
                    else -> prefs
                }
                
                // Also dismiss coach mark since user discovered the feature
                val finalPrefs = if (!prefs.hasSeenHistoryCoachMark) {
                    updatedPrefs.copy(hasSeenHistoryCoachMark = true)
                } else {
                    updatedPrefs
                }
                userPreferencesDao.upsert(finalPrefs)

                // 3. Delete ALL listening events from this artist
                val deletedEventsCount = listeningRepository.deleteByArtist(artistName)
                Log.d(TAG, "Deleted $deletedEventsCount listening events from artist '$artistName'")

                // 4. Delete ALL tracks from this artist
                val deletedTracksCount = trackRepository.deleteByArtist(artistName)
                Log.d(TAG, "Deleted $deletedTracksCount tracks from artist '$artistName'")
                
                // Show success feedback
                val contentTypeName = contentType.lowercase().replaceFirstChar { it.uppercase() }
                val feedbackMsg = "Blocked \"$artistName\" - removed all content from history & stats"
                _uiState.update { it.copy(showCoachMark = false, feedbackMessage = feedbackMsg, isMarking = false) }

                // Invalidate stats cache
                statsRepository.invalidateCache()

                // Refresh history
                loadHistory()

            } catch (e: Exception) {
                Log.e(TAG, "Error blocking artist", e)
                _uiState.update { it.copy(error = "Failed to block artist: ${e.message}", isMarking = false) }
            }
        }
    }
    
    private suspend fun checkShouldShowCoachMark(history: List<HistoryItem>): Boolean {
        if (history.isEmpty()) {
            Log.d(TAG, "CoachMark: Not showing - history is empty")
            return false
        }
        val prefs = userPreferencesDao.getSync()
        if (prefs == null) {
            Log.d(TAG, "CoachMark: Not showing - prefs is null")
            return false
        }
        val shouldShow = prefs.hasSeenHistoryCoachMark == false
        Log.d(TAG, "CoachMark: hasSeenHistoryCoachMark=${prefs.hasSeenHistoryCoachMark}, shouldShow=$shouldShow")
        return shouldShow
    }

    fun dismissCoachMark() {
        viewModelScope.launch {
            val prefs = userPreferencesDao.getSync() ?: return@launch
            userPreferencesDao.upsert(prefs.copy(hasSeenHistoryCoachMark = true))
            _uiState.update { it.copy(showCoachMark = false) }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        private const val TAG = "HistoryViewModel"
    }

}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isMarking: Boolean = false, // True when marking content in progress
    val error: String? = null,
    val rawItems: List<HistoryItem> = emptyList(),
    val groupedItems: Map<String, List<HistoryItem>> = emptyMap(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    // Filters
    val searchQuery: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val showSkips: Boolean = true,
    val showCoachMark: Boolean = false,
    // User feedback
    val feedbackMessage: String? = null
)
