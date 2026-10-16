package me.avinas.tempo.ui.details

import me.avinas.tempo.data.local.dao.isLocalBackupArtwork

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtLookupStatus
import me.avinas.tempo.data.enrichment.CoverArtPickerService
import me.avinas.tempo.data.enrichment.CoverArtProvider
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.repository.TrackAudioFeatures
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.worker.EnrichmentWorker
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature

/**
 * ViewModel for Song Details screen.
 *
 * Data Flow Pattern: Enrichment → Database → UI
 * - Normal screen rendering reads cached metadata from the database
 * - Remote artwork lookup runs only after the user explicitly opens the cover picker
 * - Track metadata is fetched from database cache
 * - Mood/genre derived from MusicBrainz tags & Spotify audio features
 * - Engagement metrics computed from listening behavior
 * - Audio preview handled locally via Media3 ExoPlayer
 */
@HiltViewModel
class SongDetailsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val trackRepository: TrackRepository,
    private val trackAliasRepository: TrackAliasRepository,
    private val listeningEventDao: ListeningEventDao,
    private val coverArtPickerService: CoverArtPickerService,
    private val tracker: AnalyticsTracker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val trackId: Long = checkNotNull(savedStateHandle["trackId"])

    private val _uiState = MutableStateFlow(SongDetailsUiState())
    val uiState: StateFlow<SongDetailsUiState> = _uiState.asStateFlow()

    // ExoPlayer for 30-second audio preview
    private var exoPlayer: ExoPlayer? = null
    private var previewProgressJob: Job? = null
    private var coverLookupJob: Job? = null

    private val _isPlayingPreview = MutableStateFlow(false)
    val isPlayingPreview: StateFlow<Boolean> = _isPlayingPreview.asStateFlow()

    private val _previewProgress = MutableStateFlow(0f)
    val previewProgress: StateFlow<Float> = _previewProgress.asStateFlow()

    private val _previewPositionMs = MutableStateFlow(0L)
    val previewPositionMs: StateFlow<Long> = _previewPositionMs.asStateFlow()

    // Guards on-demand enrichment so it fires once per screen entry, not on every reload
    private var hasTriggeredOnDemandEnrichment = false
    private var trackDetailsLoadGeneration = 0L

    init {
        loadTrackDetails()
        // Auto-refresh when enrichment completes so newly fetched album art/metadata appears
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates().collect {
                if (_uiState.value.trackDetails?.track?.albumArtUrl.isNullOrBlank() ||
                    _uiState.value.audioFeatures == null
                ) {
                    loadTrackDetails(quiet = true)
                }
            }
        }
    }

    /**
     * Load track details from database cache.
     */
    private fun loadTrackDetails(quiet: Boolean = false) {
        val currentGeneration = ++trackDetailsLoadGeneration
        viewModelScope.launch {
            if (!quiet) _uiState.update { it.copy(isLoading = true) }
            try {
                // Fetch stats and metadata queries concurrently.
                lateinit var details: TrackDetails
                lateinit var history: List<DailyListening>
                var enrichedMetadata: EnrichedMetadata? = null
                var audioFeatures: TrackAudioFeatures? = null
                var engagement: TrackEngagement? = null
                lateinit var events: List<ListeningEvent>
                coroutineScope {
                    val detailsDeferred = async { statsRepository.getTrackDetails(trackId) }
                    val historyDeferred = async { statsRepository.getTrackListeningHistory(trackId, TimeRange.ALL_TIME) }
                    val enrichedDeferred = async { enrichedMetadataRepository.forTrackSync(trackId) }
                    val featuresDeferred = async { statsRepository.getTrackAudioFeatures(trackId) }
                    val engagementDeferred = async { statsRepository.getTrackEngagement(trackId) }
                    val eventsDeferred = async { listeningEventDao.getEventsForTrack(trackId) }
                    details = detailsDeferred.await()
                    history = historyDeferred.await()
                    enrichedMetadata = enrichedDeferred.await()
                    audioFeatures = featuresDeferred.await()
                    engagement = engagementDeferred.await()
                    events = eventsDeferred.await()
                }

                // Derive mood from MusicBrainz tags if available
                val moodSummary = if (enrichedMetadata != null) {
                    val tags = enrichedMetadata.tags
                    val genres = enrichedMetadata.genres
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        TagBasedMoodAnalyzer.getMoodSummary(tags, genres)
                    } else null
                } else null

                // Reported only when a mood was actually derived, and not on the quiet reload that
                // fires when metadata arrives late, so a user reading this track is counted once.
                if (!quiet && moodSummary != null) {
                    tracker.track(FeatureUsed(TempoFeature.MOOD_ANALYSIS))
                }

                // Compute peak binge day
                val peakBinge = history.maxByOrNull { it.playCount }?.let {
                    if (it.playCount > 0) Pair(it.date, it.playCount) else null
                }

                // Compute peak listening hour across all recorded events
                var habitualHour: String? = null
                var habitualHourOfDay: Int? = null
                if (events.isNotEmpty()) {
                    val hourHistogram = IntArray(24)
                    for (event in events) {
                        val hour = try {
                            Instant.ofEpochMilli(event.timestamp)
                                .atZone(ZoneId.systemDefault())
                                .hour
                        } catch (_: Exception) {
                            12
                        }
                        hourHistogram[hour.coerceIn(0, 23)]++
                    }
                    val peakHour = hourHistogram.indices.maxByOrNull { hourHistogram[it] }
                    if (peakHour != null && hourHistogram[peakHour] > 0) {
                        habitualHourOfDay = peakHour
                        val h12 = when {
                            peakHour == 0 -> 12
                            peakHour > 12 -> peakHour - 12
                            else -> peakHour
                        }
                        val amPm = if (peakHour < 12) "AM" else "PM"
                        habitualHour = "$h12 $amPm"
                    }
                }

                // Audio preview URL
                val previewUrl = enrichedMetadata?.previewUrl ?: enrichedMetadata?.spotifyPreviewUrl

                // Streaming URLs
                val spotifyTrackUrl = details.spotifyUrl 
                    ?: enrichedMetadata?.spotifyTrackUrl 
                    ?: enrichedMetadata?.spotifyId?.let { "https://open.spotify.com/track/$it" }
                val appleMusicUrl = details.appleMusicUrl ?: enrichedMetadata?.appleMusicUrl

                if (currentGeneration != trackDetailsLoadGeneration) return@launch

                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        trackDetails = details,
                        listeningHistory = history,
                        moodSummary = moodSummary,
                        audioFeatures = audioFeatures,
                        engagement = engagement,
                        genre = enrichedMetadata?.genres?.firstOrNull() 
                            ?: enrichedMetadata?.tags?.firstOrNull(),
                        releaseDate = enrichedMetadata?.releaseDateFull ?: enrichedMetadata?.releaseDate,
                        releaseYear = enrichedMetadata?.releaseYear,
                        recordLabel = enrichedMetadata?.recordLabel,
                        previewUrl = previewUrl,
                        spotifyTrackUrl = spotifyTrackUrl,
                        appleMusicUrl = appleMusicUrl,
                        peakBingeDay = peakBinge,
                        habitualHour = habitualHour,
                        habitualHourOfDay = habitualHourOfDay,
                        isManualCover = enrichedMetadata?.albumArtSource == AlbumArtSource.USER_SELECTED
                    ) 
                }

                // On-demand enrichment trigger
                if (!hasTriggeredOnDemandEnrichment
                    && details.track.albumArtUrl.isNullOrBlank()
                    && enrichedMetadata?.enrichmentStatus != EnrichmentStatus.ENRICHED) {
                    hasTriggeredOnDemandEnrichment = true
                    enrichedMetadataRepository.markForReEnrichment(trackId)
                    EnrichmentWorker.enqueueImmediate(context, trackId)
                }
            } catch (e: Exception) {
                if (currentGeneration != trackDetailsLoadGeneration) return@launch
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

    fun setSelectedCoverProvider(provider: CoverArtProvider?) {
        _uiState.update { it.copy(selectedCoverProvider = provider) }
    }

    fun showCoverPicker() {
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
                coverProviderStatuses = CoverArtPickerService.REMOTE_PROVIDERS
                    .associateWith { CoverArtLookupStatus.LOADING },
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
        val track = _uiState.value.trackDetails?.track ?: return
        coverLookupJob?.cancel()
        coverLookupJob = viewModelScope.launch {
            val providersToSearch = if (providerToRetry != null) {
                listOf(providerToRetry)
            } else {
                val currentStatuses = _uiState.value.coverProviderStatuses
                val failedProviders = CoverArtPickerService.REMOTE_PROVIDERS.filter {
                    currentStatuses[it] == CoverArtLookupStatus.ERROR
                }
                if (failedProviders.isNotEmpty()) failedProviders else CoverArtPickerService.REMOTE_PROVIDERS
            }

            _uiState.update { state ->
                val newStatuses = state.coverProviderStatuses.toMutableMap()
                providersToSearch.forEach { newStatuses[it] = CoverArtLookupStatus.LOADING }
                state.copy(
                    isLoadingCoverCandidates = true,
                    coverPickerError = null,
                    selectedCoverProvider = if (state.selectedCoverProvider in providersToSearch) null else state.selectedCoverProvider,
                    coverCandidates = state.coverCandidates.filterNot { it.provider in providersToSearch },
                    coverProviderStatuses = newStatuses,
                )
            }

            try {
                val metadata = enrichedMetadataRepository.forTrackSync(trackId)
                _uiState.update { state ->
                    if (state.coverCandidates.none { it.provider == CoverArtProvider.CURRENT }) {
                        val current = coverArtPickerService.currentCandidate(track, metadata)
                        if (current != null) {
                            state.copy(coverCandidates = listOf(current) + state.coverCandidates)
                        } else state
                    } else state
                }
                coroutineScope {
                    providersToSearch.forEach { provider ->
                        launch {
                            val result = coverArtPickerService.searchProvider(provider, track, metadata)
                            _uiState.update { state ->
                                val candidates = result.candidate?.let { candidate ->
                                    (state.coverCandidates.filterNot { it.provider == provider } + candidate)
                                        .sortedBy { item ->
                                            when (item.provider) {
                                                CoverArtProvider.CURRENT -> -1
                                                else -> CoverArtPickerService.REMOTE_PROVIDERS.indexOf(item.provider)
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
                            if (status == CoverArtLookupStatus.LOADING) {
                                CoverArtLookupStatus.ERROR
                            } else {
                                status
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Retire the exact local fallback after the remote artwork has loaded.
     *
     * The repository atomically promotes Track.albumArtUrl back to the current
     * canonical remote URL before deleting the local file. Delayed local writers
     * are guarded from persisting a file:// path that no longer exists.
     */
    fun consumeLocalArtworkBackup(localBackupArtUrl: String?) {
        val expectedLocalUrl =
            localBackupArtUrl?.takeIf(::isLocalBackupArtwork) ?: return

        viewModelScope.launch {
            try {
                val canonicalRemote =
                    trackRepository.consumeLocalAlbumArtBackup(
                        trackId = trackId,
                        expectedLocalUrl = expectedLocalUrl,
                    )
                if (canonicalRemote != null) {
                    statsRepository.invalidateCache()
                    _uiState.update { state ->
                        val details = state.trackDetails
                        if (details?.localBackupArtUrl == expectedLocalUrl) {
                            state.copy(
                                trackDetails =
                                    details.copy(
                                        track = details.track.copy(albumArtUrl = canonicalRemote),
                                        localBackupArtUrl = null,
                                    ),
                            )
                        } else {
                            state
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Cleanup is best-effort; artwork display has already succeeded.
            }
        }
    }

    private suspend fun discardObsoleteLocalArtworkBackup(localBackupArtUrl: String?) {
        val expectedLocalUrl =
            localBackupArtUrl?.takeIf(::isLocalBackupArtwork) ?: return

        try {
            trackRepository.discardLocalAlbumArtBackup(expectedLocalUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort storage cleanup. Artwork persistence already succeeded.
        }
    }

    fun selectCover(candidate: CoverArtCandidate) {
        if (_uiState.value.trackDetails == null) return
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return

        coverLookupJob?.cancel()
        coverLookupJob = null
        trackDetailsLoadGeneration++
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingCover = true, coverPickerError = null) }
            try {
                val selectedUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
                    .fixHttpUrl(
                        candidate.albumArtUrlLarge
                            ?.takeIf { it.isNotBlank() }
                            ?: candidate.albumArtUrl
                    )
                    ?: candidate.albumArtUrl
                val selectedSmallUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
                    .fixHttpUrl(candidate.albumArtUrlSmall ?: selectedUrl)
                    ?: selectedUrl
                val selectedLargeUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
                    .fixHttpUrl(candidate.albumArtUrlLarge ?: selectedUrl)
                    ?: selectedUrl
                val obsoleteLocalBackup = _uiState.value.trackDetails?.localBackupArtUrl
                enrichedMetadataRepository.setUserSelectedArtwork(
                    trackId = trackId,
                    albumArtUrl = selectedUrl,
                    albumArtUrlSmall = selectedSmallUrl,
                    albumArtUrlLarge = selectedLargeUrl,
                )
                discardObsoleteLocalArtworkBackup(obsoleteLocalBackup)

                statsRepository.invalidateCache()
                statsRepository.notifyMetadataUpdate()
                _uiState.update { state ->
                    state.copy(
                        trackDetails = state.trackDetails?.copy(
                            track = state.trackDetails.track.copy(albumArtUrl = selectedUrl),
                            localBackupArtUrl = null,
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
                            if (status == CoverArtLookupStatus.LOADING) {
                                CoverArtLookupStatus.ERROR
                            } else {
                                status
                            }
                        },
                    )
                }
            }
        }
    }

    fun resetCoverToAutomatic() {
        if (_uiState.value.trackDetails == null) return
        if (_uiState.value.isSavingCover || _uiState.value.isResettingCover) return

        coverLookupJob?.cancel()
        coverLookupJob = null
        trackDetailsLoadGeneration++
        viewModelScope.launch {
            _uiState.update { it.copy(isResettingCover = true, coverPickerError = null) }
            try {
                val obsoleteLocalBackup = _uiState.value.trackDetails?.localBackupArtUrl
                enrichedMetadataRepository.resetArtworkToAutomatic(trackId)
                discardObsoleteLocalArtworkBackup(obsoleteLocalBackup)
                statsRepository.invalidateCache()
                statsRepository.notifyMetadataUpdate()
                EnrichmentWorker.enqueueImmediate(
                    context = context,
                    trackId = trackId,
                    appendAfterExisting = true,
                )

                // Preserve the currently displayed image during transition to prevent blank screen flashing.
                // The post-reset enrichment worker will repopulate Track.albumArtUrl once ready.
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCoverCandidates = false,
                        isSavingCover = false,
                        isResettingCover = false,
                        coverPickerError = e.message ?: context.getString(R.string.details_cover_restore_auto_error),
                        coverProviderStatuses = it.coverProviderStatuses.mapValues { (_, status) ->
                            if (status == CoverArtLookupStatus.LOADING) {
                                CoverArtLookupStatus.ERROR
                            } else {
                                status
                            }
                        },
                    )
                }
            }
        }
    }

    // Audio Preview Controls
    fun toggleAudioPreview() {
        val url = _uiState.value.previewUrl ?: return
        if (_isPlayingPreview.value) {
            pauseAudioPreview()
        } else {
            playAudioPreview(url)
        }
    }

    fun pauseAudioPreview() {
        _isPlayingPreview.value = false
        previewProgressJob?.cancel()
        exoPlayer?.pause()
    }

    private fun playAudioPreview(url: String) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            _isPlayingPreview.value = false
                            _previewProgress.value = 0f
                            _previewPositionMs.value = 0L
                            previewProgressJob?.cancel()
                        }
                    }
                })
            }
        }

        val player = exoPlayer ?: return
        val currentMedia = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentMedia != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
        player.play()
        _isPlayingPreview.value = true

        previewProgressJob?.cancel()
        previewProgressJob = viewModelScope.launch {
            while (_isPlayingPreview.value) {
                val current = player.currentPosition
                val duration = player.duration.coerceAtLeast(30_000L)
                _previewPositionMs.value = current
                _previewProgress.value = if (duration > 0) (current.toFloat() / duration).coerceIn(0f, 1f) else 0f
                // 4Hz is enough for the timecode (second precision) — the UI
                // tweens the fill between samples, and skipping 20Hz state
                // churn keeps the whole screen from recomposing constantly.
                delay(250)
            }
        }
    }

    fun stopAudioPreview() {
        _isPlayingPreview.value = false
        _previewProgress.value = 0f
        _previewPositionMs.value = 0L
        previewProgressJob?.cancel()
        exoPlayer?.stop()
    }

    override fun onCleared() {
        super.onCleared()
        coverLookupJob?.cancel()
        coverLookupJob = null
        stopAudioPreview()
        exoPlayer?.release()
        exoPlayer = null
    }

    /**
     * Delete the track and all its associated data from the database.
     */
    fun deleteTrack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                val result = trackRepository.deleteTrackWithAllData(trackId)
                if (result.success) {
                    _uiState.update { it.copy(isDeleting = false, showDeleteDialog = false, trackDetails = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            error = result.error ?: "Failed to delete song"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        error = e.message ?: "Failed to delete song"
                    )
                }
            }
        }
    }

    fun showDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun showEditTitleDialog() {
        _uiState.update {
            it.copy(
                showEditTitleDialog = true,
                editTitleError = null,
                mergeTargetTrack = null
            )
        }
    }

    fun dismissEditTitleDialog() {
        _uiState.update {
            it.copy(
                showEditTitleDialog = false,
                isSavingTitle = false,
                editTitleError = null,
                mergeTargetTrack = null
            )
        }
    }

    fun clearEditTitleWarnings() {
        _uiState.update {
            if (it.editTitleError == null) it
            else it.copy(editTitleError = null)
        }
    }

    /**
     * Update the track title with duplicate check.
     */
    fun updateTrackTitle(newTitle: String) {
        val trimmed = newTitle.trim()
        val currentTitle = _uiState.value.trackDetails?.track?.title

        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(editTitleError = "Title cannot be empty") }
            return
        }
        if (currentTitle != null && trimmed == currentTitle) {
            _uiState.update { it.copy(editTitleError = "Title is unchanged") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true, editTitleError = null, mergeTargetTrack = null) }
            try {
                val artist = _uiState.value.trackDetails?.track?.artist.orEmpty()
                val existing = trackRepository.findByTitleAndArtist(trimmed, artist)
                if (existing != null && existing.id != trackId) {
                    _uiState.update {
                        it.copy(isSavingTitle = false, mergeTargetTrack = existing)
                    }
                    return@launch
                }

                if (currentTitle != null) {
                    trackAliasRepository.createAlias(trackId, currentTitle, artist)
                }

                trackRepository.updateTitle(trackId, trimmed)

                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        showEditTitleDialog = false,
                        mergeTargetTrack = null,
                        editTitleError = null
                    )
                }
                loadTrackDetails()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        editTitleError = e.message ?: "Failed to update title"
                    )
                }
            }
        }
    }

    /**
     * Confirm merging track into existing duplicate.
     */
    fun confirmEditTitleMerge() {
        val target = _uiState.value.mergeTargetTrack ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true) }
            try {
                trackAliasRepository.mergeTracks(trackId, target.id)
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        showEditTitleDialog = false,
                        mergeTargetTrack = null,
                        trackDetails = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        editTitleError = e.message ?: "Merge failed"
                    )
                }
            }
        }
    }

    fun cancelEditTitleMerge() {
        _uiState.update { it.copy(mergeTargetTrack = null) }
    }
}

@androidx.compose.runtime.Immutable
data class SongDetailsUiState(
    val isLoading: Boolean = true,
    val trackDetails: TrackDetails? = null,
    val listeningHistory: List<DailyListening> = emptyList(),
    val moodSummary: TagBasedMoodAnalyzer.MoodSummary? = null,
    val audioFeatures: TrackAudioFeatures? = null,
    val engagement: TrackEngagement? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val previewUrl: String? = null,
    val spotifyTrackUrl: String? = null,
    val appleMusicUrl: String? = null,
    val peakBingeDay: Pair<String, Int>? = null,
    val habitualHour: String? = null,
    val habitualHourOfDay: Int? = null,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val showEditTitleDialog: Boolean = false,
    val isSavingTitle: Boolean = false,
    val editTitleError: String? = null,
    val mergeTargetTrack: Track? = null,
    val showCoverPicker: Boolean = false,
    val isLoadingCoverCandidates: Boolean = false,
    val isSavingCover: Boolean = false,
    val isResettingCover: Boolean = false,
    val selectedCoverProvider: CoverArtProvider? = null,
    val coverPickerError: String? = null,
    val coverCandidates: List<CoverArtCandidate> = emptyList(),
    val coverProviderStatuses: Map<CoverArtProvider, CoverArtLookupStatus> = emptyMap(),
    val isManualCover: Boolean = false,
)
