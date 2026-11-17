package me.avinas.tempo.ui.details

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtLookupStatus
import me.avinas.tempo.data.enrichment.CoverArtPickerService
import me.avinas.tempo.data.enrichment.CoverArtProvider
import me.avinas.tempo.data.enrichment.UserCoverArtStore
import me.avinas.tempo.data.enrichment.isUserArtistCoverArtUrl
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.repository.ArtistRenameRepository
import me.avinas.tempo.data.repository.ArtistRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.stats.ArtistDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val artistRenameRepository: ArtistRenameRepository,
    private val artistRepository: ArtistRepository,
    private val coverArtPickerService: CoverArtPickerService,
    private val userCoverArtStore: UserCoverArtStore,
    private val trackRepository: TrackRepository,
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

    // Guards on-demand image fetch so it fires once per screen entry, not on every reload
    private var hasTriggeredImageRefresh = false

    /** In-flight provider lookups for the artwork picker. */
    private var coverLookupJob: Job? = null

    private val _uiState = MutableStateFlow(ArtistDetailsUiState())
    val uiState: StateFlow<ArtistDetailsUiState> = _uiState.asStateFlow()

    init {
        // Load from saved state if available
        if (savedArtistId > 0) {
            loadArtistById(savedArtistId)
        } else if (!savedArtistName.isNullOrEmpty()) {
            loadArtistByName(savedArtistName)
        }
        
        // Observe metadata updates from enrichment to refresh artist image
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates().collect {
                // Only reload if we're currently viewing an artist and refreshing
                val artistId = currentArtistId
                if (artistId != null && _uiState.value.isRefreshingImage) {
                    Log.d(TAG, "Metadata updated, reloading artist $artistId")
                    reloadArtistQuietly(artistId)
                }
            }
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
                val details = statsRepository.getArtistDetails(artistId)

                Log.d(TAG, "Successfully loaded details for artist ID $artistId: name=${details.artist.name}, playCount=${details.personalPlayCount}, imageUrl=${details.artist.imageUrl}")

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        artistDetails = details,
                        isManualCover = details.artist.imageArtSource == AlbumArtSource.USER_SELECTED,
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

                // On-demand artist image: if missing, fetch it now (once per screen entry).
                // refreshArtistImage() does the API lookup + reloads, so the observer isn't needed here.
                if (!hasTriggeredImageRefresh
                    && details.artist.imageUrl.isNullOrBlank()
                    && details.personalPlayCount > 0) {
                    hasTriggeredImageRefresh = true
                    refreshArtistImage()
                }
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
                        isManualCover = details?.artist?.imageArtSource == AlbumArtSource.USER_SELECTED,
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

    /**
     * Reload artist details without showing loading state.
     * Used when refreshing after metadata updates from enrichment.
     */
    private suspend fun reloadArtistQuietly(artistId: Long) {
        try {
            // Don't invalidate entire cache - enrichment already did that
            // Just fetch fresh data for this specific artist
            val details = statsRepository.getArtistDetails(artistId)
            
            Log.d(TAG, "Quietly reloaded artist $artistId: imageUrl=${details.artist.imageUrl}")
            
            _uiState.update {
                it.copy(
                    artistDetails = details,
                    isManualCover = details.artist.imageArtSource == AlbumArtSource.USER_SELECTED,
                    isRefreshingImage = false // Hide loading state now that we have fresh data
                )
            }
            
            // Update percentile if needed
            if (details.personalPlayCount > 0) {
                val percentile = statsRepository.getArtistRankPercentile(
                    details.personalPlayCount,
                    me.avinas.tempo.data.stats.TimeRange.ALL_TIME
                )
                _uiState.update { it.copy(artistPercentile = percentile) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quietly reload artist $artistId", e)
            _uiState.update { it.copy(isRefreshingImage = false) }
        }
    }

    /**
     * Refresh artist image by directly searching for this specific artist via API.
     * This searches for the artist by name (not by track), so it always gets
     * the correct image for THIS artist, not a track's primary artist.
     */
    fun refreshArtistImage() {
        // The user's own portrait is locked: a refresh would spend requests and
        // then be refused by the DAO, so skip the work entirely.
        if (_uiState.value.isManualCover) return
        viewModelScope.launch {
            try {
                val artistId = currentArtistId
                if (artistId != null && artistId > 0) {
                    Log.d(TAG, "Refreshing artist image for artist ID: $artistId")
                    _uiState.update { it.copy(isRefreshingImage = true) }

                    // Directly search for this artist's image via API
                    val newImageUrl = statsRepository.refreshArtistImageDirectly(artistId)

                    Log.d(TAG, "Artist image refresh result: ${newImageUrl?.take(80) ?: "null"}")

                    // Reload artist details with the new image
                    val details = statsRepository.getArtistDetails(artistId)
                    _uiState.update {
                        it.copy(
                            artistDetails = details,
                            isManualCover = details.artist.imageArtSource == AlbumArtSource.USER_SELECTED,
                            isRefreshingImage = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh artist image", e)
                _uiState.update { it.copy(error = "Failed to refresh artist image", isRefreshingImage = false) }
            }
        }
    }

    // Artwork picker

    /** Select which provider's candidate is being previewed in the picker. */
    fun setSelectedCoverProvider(provider: CoverArtProvider?) {
        _uiState.update { it.copy(selectedCoverProvider = provider) }
    }

    /**
     * Open the artwork picker and start looking for portraits.
     *
     * Remote work starts only here: the details screen never spends a request on
     * artwork the user did not ask for.
     */
    fun showCoverPicker() {
        val artist = _uiState.value.artistDetails?.artist ?: return
        if (_uiState.value.showCoverPicker) return
        _uiState.update {
            it.copy(
                showCoverPicker = true,
                isLoadingCoverCandidates = true,
                isSavingCover = false,
                isResettingCover = false,
                selectedCoverProvider = null,
                coverPickerError = null,
                coverCandidates = emptyList(),
                coverProviderStatuses = CoverArtPickerService.ARTIST_PROVIDERS
                    .associateWith { CoverArtLookupStatus.LOADING },
                isManualCover = artist.imageArtSource == AlbumArtSource.USER_SELECTED,
            )
        }
        loadCoverCandidates()
    }

    fun dismissCoverPicker() {
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return
        coverLookupJob?.cancel()
        coverLookupJob = null
        _uiState.update {
            it.copy(
                showCoverPicker = false,
                isLoadingCoverCandidates = false,
                isSavingCover = false,
                isResettingCover = false,
                selectedCoverProvider = null,
                coverPickerError = null,
            )
        }
    }

    fun retryCoverCandidates(providerToRetry: CoverArtProvider? = null) {
        if (_uiState.value.showCoverPicker) loadCoverCandidates(providerToRetry)
    }

    private fun loadCoverCandidates(providerToRetry: CoverArtProvider? = null) {
        val artist = _uiState.value.artistDetails?.artist ?: return
        coverLookupJob?.cancel()
        coverLookupJob = viewModelScope.launch {
            val providersToSearch = if (providerToRetry != null) {
                listOf(providerToRetry)
            } else {
                val currentStatuses = _uiState.value.coverProviderStatuses
                val failedProviders = CoverArtPickerService.ARTIST_PROVIDERS.filter {
                    currentStatuses[it] == CoverArtLookupStatus.ERROR
                }
                if (failedProviders.isNotEmpty()) failedProviders
                else CoverArtPickerService.ARTIST_PROVIDERS
            }

            _uiState.update { state ->
                val newStatuses = state.coverProviderStatuses.toMutableMap()
                providersToSearch.forEach { newStatuses[it] = CoverArtLookupStatus.LOADING }
                state.copy(
                    isLoadingCoverCandidates = true,
                    coverPickerError = null,
                    selectedCoverProvider =
                        if (state.selectedCoverProvider in providersToSearch) null else state.selectedCoverProvider,
                    coverCandidates = state.coverCandidates.filterNot { it.provider in providersToSearch },
                    coverProviderStatuses = newStatuses,
                )
            }

            try {
                _uiState.update { state ->
                    if (state.coverCandidates.none { it.provider == CoverArtProvider.CURRENT }) {
                        val current = coverArtPickerService.currentArtistCandidate(artist)
                        if (current != null) {
                            state.copy(coverCandidates = listOf(current) + state.coverCandidates)
                        } else state
                    } else state
                }
                coroutineScope {
                    providersToSearch.forEach { provider ->
                        launch {
                            val result = coverArtPickerService.searchArtistProvider(provider, artist)
                            _uiState.update { state ->
                                val candidates = result.candidate?.let { candidate ->
                                    (state.coverCandidates.filterNot { it.provider == provider } + candidate)
                                        .sortedBy { item ->
                                            when (item.provider) {
                                                CoverArtProvider.CURRENT -> -1
                                                else -> CoverArtPickerService.ARTIST_PROVIDERS.indexOf(item.provider)
                                            }
                                        }
                                } ?: state.coverCandidates.filterNot { it.provider == provider }

                                val statuses = state.coverProviderStatuses + (provider to result.status)
                                state.copy(
                                    coverCandidates = candidates,
                                    coverProviderStatuses = statuses,
                                    isLoadingCoverCandidates =
                                        statuses.values.any { it == CoverArtLookupStatus.LOADING },
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoadingCoverCandidates = false,
                        coverPickerError = e.message ?: context.getString(R.string.details_cover_load_error),
                        coverProviderStatuses = state.coverProviderStatuses.mapValues { (_, status) ->
                            if (status == CoverArtLookupStatus.LOADING) CoverArtLookupStatus.ERROR else status
                        },
                    )
                }
            }
        }
    }

    /**
     * Persist a portrait the user picked from a provider.
     *
     * The write also sets [AlbumArtSource.USER_SELECTED], so no enrichment pass
     * can replace it later.
     */
    fun selectCover(candidate: CoverArtCandidate) {
        val details = _uiState.value.artistDetails ?: return
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return

        coverLookupJob?.cancel()
        coverLookupJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingCover = true, coverPickerError = null) }
            try {
                val selectedUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
                    .fixHttpUrl(
                        candidate.albumArtUrlLarge?.takeIf { it.isNotBlank() } ?: candidate.albumArtUrl
                    )
                    ?: candidate.albumArtUrl
                val previousUrl = details.artist.imageUrl
                artistRepository.setUserSelectedImage(details.artist.id, selectedUrl)
                if (previousUrl != selectedUrl) discardGalleryPortraitIfOurs(previousUrl)

                applySavedPortrait(selectedUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCoverCandidates = false,
                        isSavingCover = false,
                        isResettingCover = false,
                        coverPickerError = e.message ?: context.getString(R.string.details_cover_save_error),
                        coverProviderStatuses = it.coverProviderStatuses.mapValues { (_, status) ->
                            if (status == CoverArtLookupStatus.LOADING) CoverArtLookupStatus.ERROR else status
                        },
                    )
                }
            }
        }
    }

    /**
     * Save a portrait the user picked from their own gallery.
     *
     * The image is copied into Tempo's managed artwork directory and locked with
     * [AlbumArtSource.USER_SELECTED]. This path performs no network work.
     */
    fun selectCoverFromGallery(source: android.net.Uri) {
        val details = _uiState.value.artistDetails ?: return
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return

        coverLookupJob?.cancel()
        coverLookupJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingCover = true, coverPickerError = null) }
            try {
                val previousUrl = details.artist.imageUrl
                val savedUrl = userCoverArtStore.saveGalleryArtistCover(details.artist.id, source)
                artistRepository.setUserSelectedImage(details.artist.id, savedUrl)
                if (previousUrl != savedUrl) discardGalleryPortraitIfOurs(previousUrl)

                applySavedPortrait(savedUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One user-facing failure mode; UserCoverArtStore logs the cause.
                _uiState.update {
                    it.copy(
                        isLoadingCoverCandidates = false,
                        isSavingCover = false,
                        coverPickerError = context.getString(R.string.details_cover_gallery_error),
                    )
                }
            }
        }
    }

    /**
     * Release the manual lock so enrichment may choose a portrait again.
     *
     * The currently displayed image is kept in state until the reload brings a
     * replacement, which avoids a blank hero while the worker runs.
     */
    fun resetCoverToAutomatic() {
        val details = _uiState.value.artistDetails ?: return
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return

        coverLookupJob?.cancel()
        coverLookupJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(isResettingCover = true, coverPickerError = null) }
            try {
                val obsoleteGalleryPortrait = details.artist.imageUrl
                    ?.takeIf { isUserArtistCoverArtUrl(it, details.artist.id) }
                artistRepository.resetImageToAutomatic(details.artist.id)
                discardGalleryPortraitIfOurs(obsoleteGalleryPortrait)

                statsRepository.invalidateCache()
                statsRepository.notifyMetadataUpdate()
                _uiState.update {
                    it.copy(
                        isManualCover = false,
                        showCoverPicker = false,
                        isLoadingCoverCandidates = false,
                        isSavingCover = false,
                        isResettingCover = false,
                        selectedCoverProvider = null,
                        coverPickerError = null,
                    )
                }
                // Fetch an automatic portrait now instead of leaving the hero
                // empty until the next enrichment pass happens to run. The reset
                // is already persisted, so a lookup failure must not surface as
                // a failure of the reset itself.
                try {
                    statsRepository.refreshArtistImageDirectly(details.artist.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch an automatic portrait after reset", e)
                }
                reloadDetailsAfterArtworkChange()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCoverCandidates = false,
                        isSavingCover = false,
                        isResettingCover = false,
                        coverPickerError =
                            e.message ?: context.getString(R.string.details_cover_restore_auto_error),
                        coverProviderStatuses = it.coverProviderStatuses.mapValues { (_, status) ->
                            if (status == CoverArtLookupStatus.LOADING) CoverArtLookupStatus.ERROR else status
                        },
                    )
                }
            }
        }
    }

    /**
     * Reflect a persisted portrait in the hero and close the picker.
     *
     * Updating state directly keeps the UI responsive; the reload afterwards
     * picks up everything the repository derived from the new URL.
     */
    private suspend fun applySavedPortrait(savedUrl: String) {
        _uiState.update { state ->
            val details = state.artistDetails
            state.copy(
                artistDetails = details?.copy(
                    artist = details.artist.copy(
                        imageUrl = savedUrl,
                        imageArtSource = AlbumArtSource.USER_SELECTED,
                    ),
                ),
                isManualCover = true,
                showCoverPicker = false,
                isLoadingCoverCandidates = false,
                isSavingCover = false,
                isResettingCover = false,
                selectedCoverProvider = null,
                coverPickerError = null,
            )
        }
        statsRepository.invalidateCache()
        statsRepository.notifyMetadataUpdate()
        reloadDetailsAfterArtworkChange()
    }

    /** Reload the row so the hero and accent colours match what was persisted. */
    private suspend fun reloadDetailsAfterArtworkChange() {
        // Key off the loaded row, not currentArtistId: the screen can be opened
        // with a name only, in which case currentArtistId is null.
        val artistId = _uiState.value.artistDetails?.artist?.id ?: return
        try {
            val details = statsRepository.getArtistDetails(artistId)
            _uiState.update {
                it.copy(
                    artistDetails = details,
                    isManualCover = details.artist.imageArtSource == AlbumArtSource.USER_SELECTED,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not reload artist $artistId after artwork change", e)
        }
    }

    /**
     * Delete a gallery portrait this artist no longer uses, once nothing in the
     * database references it. The repository re-checks references and refuses
     * any path outside Tempo's managed artwork directory.
     */
    private suspend fun discardGalleryPortraitIfOurs(url: String?) {
        val artistId = _uiState.value.artistDetails?.artist?.id ?: return
        if (!isUserArtistCoverArtUrl(url, artistId)) return
        try {
            trackRepository.discardLocalAlbumArtBackup(url!!)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort cleanup; the artwork change is already persisted.
        }
    }

    // Rename Flow
    /** Show the rename dialog. */
    fun showRenameDialog() {
        _uiState.update { it.copy(
            showRenameDialog = true,
            detectedSplitArtists = emptyList(),
            isDetectingSplits = false,
            isRenaming = false,
            renameSuccess = null
        ) }
    }

    /** Hide the rename dialog and reset rename state. */
    fun dismissRenameDialog() {
        _uiState.update { it.copy(
            showRenameDialog = false,
            detectedSplitArtists = emptyList(),
            isDetectingSplits = false,
            isRenaming = false,
            renameSuccess = null
        ) }
    }

    // Split Flow

    /** Show the split-artist dialog. */
    fun showSplitDialog() {
        _uiState.update { it.copy(showSplitDialog = true) }
    }

    /** Hide the split-artist dialog. */
    fun dismissSplitDialog() {
        _uiState.update { it.copy(showSplitDialog = false) }
    }

    /**
     * Detect if the new name matches split fragments of other artists in the DB.
     * If no splits found, immediately performs a simple rename.
     */
    fun detectSplitsAndRename(newName: String) {
        val artistId = currentArtistId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDetectingSplits = true, renameSuccess = null) }
            try {
                val splits = artistRenameRepository.detectSplitArtists(newName, artistId)
                if (splits.isEmpty()) {
                    // No splits detected — just rename directly
                    _uiState.update { it.copy(isDetectingSplits = false) }
                    performRename(artistId, newName, emptyList())
                } else {
                    // Found split artists — show merge confirmation
                    _uiState.update { it.copy(
                        detectedSplitArtists = splits,
                        isDetectingSplits = false
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detect splits", e)
                _uiState.update { it.copy(isDetectingSplits = false, renameSuccess = false) }
            }
        }
    }

    /** Rename and merge the detected split artists. */
    fun confirmRenameAndMerge(newName: String, mergeIds: List<Long>) {
        val artistId = currentArtistId ?: return
        performRename(artistId, newName, mergeIds)
    }

    /** Rename only, without merging any splits. */
    fun confirmRenameOnly(newName: String) {
        val artistId = currentArtistId ?: return
        performRename(artistId, newName, emptyList())
    }

    private fun performRename(artistId: Long, newName: String, mergeIds: List<Long>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRenaming = true, renameSuccess = null) }
            try {
                val resultArtistId = artistRenameRepository.renameAndMerge(artistId, newName, mergeIds)
                _uiState.update { it.copy(isRenaming = false, renameSuccess = resultArtistId != null) }

                if (resultArtistId != null) {
                    Log.i(TAG, "Successfully renamed artist $artistId to '$newName' (result ID: $resultArtistId)")
                    // Force reload to show updated name/stats
                    // Use resultArtistId because a merge may have moved data to a different artist
                    currentArtistId = null // Reset so loadArtistById doesn't skip
                    loadArtistById(resultArtistId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rename failed", e)
                _uiState.update { it.copy(isRenaming = false, renameSuccess = false) }
            }
        }
    }
}

@androidx.compose.runtime.Immutable
data class ArtistDetailsUiState(
    val isLoading: Boolean = true,
    val artistDetails: ArtistDetails? = null,
    val artistPercentile: Double? = null,
    val error: String? = null,
    val isRefreshingImage: Boolean = false,
    // Rename state
    val showRenameDialog: Boolean = false,
    val detectedSplitArtists: List<Artist> = emptyList(),
    val isDetectingSplits: Boolean = false,
    val isRenaming: Boolean = false,
    val renameSuccess: Boolean? = null,
    // Split state
    val showSplitDialog: Boolean = false,
    // Artwork picker state (mirrors SongDetailsUiState so the shared sheet can render it)
    val showCoverPicker: Boolean = false,
    val isLoadingCoverCandidates: Boolean = false,
    val isSavingCover: Boolean = false,
    val isResettingCover: Boolean = false,
    val selectedCoverProvider: CoverArtProvider? = null,
    val coverPickerError: String? = null,
    val coverCandidates: List<CoverArtCandidate> = emptyList(),
    val coverProviderStatuses: Map<CoverArtProvider, CoverArtLookupStatus> = emptyMap(),
    val isManualCover: Boolean = false,
) {
    /**
     * Project the artwork fields into the shape the shared picker sheet renders.
     *
     * The song and artist screens use one sheet so the two flows cannot drift.
     */
    internal val coverPickerState: CoverPickerUiState
        get() = CoverPickerUiState(
            selectedProvider = selectedCoverProvider,
            candidates = coverCandidates,
            providerStatuses = coverProviderStatuses,
            error = coverPickerError,
            isLoadingCandidates = isLoadingCoverCandidates,
            isSaving = isSavingCover,
            isResetting = isResettingCover,
            isManual = isManualCover,
        )
}
