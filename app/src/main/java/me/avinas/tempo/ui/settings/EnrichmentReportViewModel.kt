package me.avinas.tempo.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.worker.EnrichmentWorker
import javax.inject.Inject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.TempoFeature

/**
 * ViewModel for the Enrichment Report screen.
 *
 * Exposes library-wide enrichment analytics (reusing [EnrichedMetadataRepository] counts) and
 * observes the bulk "Enrich All" WorkManager job via [WorkManager.getWorkInfosByTagFlow] so the
 * progress bar updates live and survives the user leaving the screen (the worker is foreground).
 */
@HiltViewModel
class EnrichmentReportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val tracker: AnalyticsTracker
) : ViewModel() {

    data class Stats(
        val totalTracks: Int = 0,
        val enriched: Int = 0,
        val pending: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val notFound: Int = 0,
        val notQueued: Int = 0,
        val withAlbumArt: Int = 0,
    ) {
        val withoutAlbumArt: Int get() = (totalTracks - withAlbumArt).coerceAtLeast(0)
        val coveragePercent: Int get() = if (totalTracks > 0) (enriched * 100 / totalTracks) else 0
        val artCoveragePercent: Int get() = if (totalTracks > 0) (withAlbumArt * 100 / totalTracks) else 0
        // Everything that is not ENRICHED, including tracks that never got a
        // metadata row at all (notQueued) — the button label and the sweep both
        // cover the full library.
        val unenriched: Int get() = pending + failed + skipped + notFound + notQueued
    }

    data class BulkProgress(
        val isRunning: Boolean = false,
        val isWaiting: Boolean = false,
        val processed: Int = 0,
        val total: Int = 0,
        val isDone: Boolean = false,
    ) {
        val percent: Int get() = if (total > 0) (processed * 100 / total) else 0
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /**
     * Songs that are NOT_FOUND and still inside the 7-day re-enrichment block, so the
     * current sweep Skips them (they were already searched on every source).
     */
    private val _blockedNotFound = MutableStateFlow(0)
    val blockedNotFound: StateFlow<Int> = _blockedNotFound.asStateFlow()

    val bulkProgress: StateFlow<BulkProgress> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(EnrichmentWorker.TAG_ENRICH_ALL)
            .map { infos ->
                // Prefer the live job; only fall back to a finished one when nothing
                // is active. Picking infos.firstOrNull() unconditionally could surface
                // a stale finished run while a new sweep is still queued.
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val enqueued = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                val info = running ?: enqueued ?: infos.firstOrNull()
                if (info == null) {
                    BulkProgress()
                } else {
                    val p = info.progress
                    val done = p.getBoolean("done", false) || info.state == WorkInfo.State.SUCCEEDED
                    val active = (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) && !done
                    BulkProgress(
                        isRunning = active,
                        // Enqueued with no progress yet = waiting on constraints
                        // (network / battery). Surface it so the user does not see a
                        // frozen "Starting enrichment…" forever.
                        isWaiting = active && info.state == WorkInfo.State.ENQUEUED && p.getInt("total", 0) == 0,
                        processed = p.getInt("processed", 0),
                        total = p.getInt("total", 0),
                        isDone = done,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BulkProgress())

    init {
        refresh()
    }

    /** Re-fetch all counts from the database. */
    fun refresh() {
        viewModelScope.launch {
            val statusCounts = enrichedMetadataRepository.getEnrichmentStats()
            _stats.value = Stats(
                totalTracks = enrichedMetadataRepository.countAllTracks(),
                enriched = statusCounts[EnrichmentStatus.ENRICHED] ?: 0,
                pending = statusCounts[EnrichmentStatus.PENDING] ?: 0,
                failed = statusCounts[EnrichmentStatus.FAILED] ?: 0,
                skipped = statusCounts[EnrichmentStatus.SKIPPED] ?: 0,
                notFound = statusCounts[EnrichmentStatus.NOT_FOUND] ?: 0,
                notQueued = enrichedMetadataRepository.countTracksWithoutEnrichedMetadata(),
                withAlbumArt = enrichedMetadataRepository.countTracksWithAlbumArt(),
            )
            _blockedNotFound.value = enrichedMetadataRepository.countNotFoundBlockedFromRequeue()
        }
    }

    /** Requeue every non-enriched track to PENDING and start the bulk foreground sweep. */
    fun startEnrichAll() {
        viewModelScope.launch {
            // First queue tracks that never got a metadata row (invisible to the
            // status counts until now), then requeue every non-enriched track back
            // to PENDING so the sweep covers the whole library. Tracks that came back
            // NOT_FOUND within the last 7 days are NOT requeued — re-querying every
            // source for them would only burn API quota (exploit guard).
            enrichedMetadataRepository.ensurePendingForAllTracks()
            enrichedMetadataRepository.requeueAllForEnrichment()
            val pending = enrichedMetadataRepository.getEnrichmentStats()[EnrichmentStatus.PENDING] ?: 0
            if (pending > 0) {
                EnrichmentWorker.enqueueEnrichAll(context, pending)
            }
            refresh()
        }
    }

    /** Cancel a running "Enrich All" sweep. */
    fun cancelEnrichAll() {
        EnrichmentWorker.cancelEnrichAll(context)
    }
}
