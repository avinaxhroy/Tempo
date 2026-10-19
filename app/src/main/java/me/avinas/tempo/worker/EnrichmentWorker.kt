package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.EnrichmentRun
import me.avinas.tempo.data.analytics.EnrichmentTrigger
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.RunPhase
import me.avinas.tempo.data.enrichment.CoverArtSourcePreference
import me.avinas.tempo.data.enrichment.DeezerEnrichmentService
import me.avinas.tempo.data.enrichment.EnrichmentSource
import me.avinas.tempo.data.enrichment.ITunesEnrichmentService
import me.avinas.tempo.data.enrichment.LastFmEnrichmentService
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.data.enrichment.ReccoBeatsEnrichmentService
import me.avinas.tempo.data.enrichment.SpotifyEnrichmentService
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.AudioFeaturesSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.utils.ForegroundNotificationGuard
import me.avinas.tempo.worker.LastFmImportWorker
import java.util.concurrent.TimeUnit

internal fun immediateEnrichmentWorkPolicy(
    trackId: Long?,
    appendAfterExisting: Boolean,
): ExistingWorkPolicy =
    if (trackId == null || appendAfterExisting) {
        ExistingWorkPolicy.APPEND_OR_REPLACE
    } else {
        ExistingWorkPolicy.KEEP
    }

/**
 * WorkManager worker that enriches unenriched tracks with metadata from external APIs.
 */
@HiltWorker
class EnrichmentWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val spotifyEnrichmentSource: me.avinas.tempo.data.enrichment.SpotifyEnrichmentSource,
        private val lastFmMbidPreEnrichmentSource: me.avinas.tempo.data.enrichment.LastFmMbidPreEnrichmentSource,
        private val musicBrainzEnrichmentSource: me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentSource,
        private val lastFmEnrichmentSource: me.avinas.tempo.data.enrichment.LastFmEnrichmentSource,
        private val iTunesEnrichmentSource: me.avinas.tempo.data.enrichment.ITunesEnrichmentSource,
        private val deezerEnrichmentSource: me.avinas.tempo.data.enrichment.DeezerEnrichmentSource,
        private val reccoBeatsEnrichmentSource: me.avinas.tempo.data.enrichment.ReccoBeatsEnrichmentSource,
        private val spotifyArtistFeaturesSource: me.avinas.tempo.data.enrichment.SpotifyArtistFeaturesSource,
        private val enrichedMetadataDao: EnrichedMetadataDao,
        private val trackDao: TrackDao,
        private val statsRepository: StatsRepository,
        private val artistLinkingService: me.avinas.tempo.data.repository.ArtistLinkingService,
        private val enrichedMetadataRepository: EnrichedMetadataRepository,
        private val analyticsTracker: AnalyticsTracker,
        private val coverArtSourcePreference: me.avinas.tempo.data.enrichment.CoverArtSourcePreference,
    ) : CoroutineWorker(appContext, workerParams) {
        private val strategies: List<EnrichmentSource> =
            listOf(
                spotifyEnrichmentSource,
                lastFmMbidPreEnrichmentSource, // Pre-enrich with MBIDs from Last.fm before MusicBrainz
                musicBrainzEnrichmentSource,
                lastFmEnrichmentSource,
                iTunesEnrichmentSource,
                deezerEnrichmentSource,
                reccoBeatsEnrichmentSource,
                spotifyArtistFeaturesSource,
            ).sortedBy { it.priority }

        companion object {
            private const val TAG = "EnrichmentWorker"
            private const val WORK_NAME = "music_enrichment"
            private const val WORK_NAME_IMMEDIATE = "music_enrichment_immediate"
            private const val WORK_NAME_POST_IMPORT = "music_enrichment_post_import"
            private const val NOTIFICATION_CHANNEL_ID = "enrichment_worker"
            private const val NOTIFICATION_ID = 3002
            private const val WORK_NAME_ENRICH_ALL = "music_enrichment_enrich_all"
            internal const val TAG_ENRICH_ALL = "enrichment_enrich_all"

            // How many tracks to process per run
            private const val BATCH_SIZE = 10
            private const val RETRY_BATCH_SIZE = 5

            // Larger batch size for post-import accelerated enrichment
            private const val POST_IMPORT_BATCH_SIZE = 50

            // Scaled batch sizes for large post-import backlogs (e.g. YouTube Music imports)
            private const val POST_IMPORT_BATCH_SIZE_LARGE = 100 // pending > 1000
            private const val POST_IMPORT_BATCH_SIZE_HUGE = 150 // pending > 5000

            // Minimum play count threshold for two-tier post-import enrichment
            private const val POST_IMPORT_MIN_PLAY_COUNT = 2
            private const val POST_IMPORT_LARGE_BACKLOG_THRESHOLD = 1000

            // Delay between processing each track (respects rate limit)
            private const val INTER_TRACK_DELAY_MS = 1500L

            // Shorter delay for post-import (still respects rate limits but processes faster)
            private const val POST_IMPORT_INTER_TRACK_DELAY_MS = 1000L

            /**
             * Schedule periodic enrichment work. Runs every hour when connected and the battery is
             * not low.
             */
            fun schedulePeriodic(context: Context) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()

                val workRequest =
                    PeriodicWorkRequestBuilder<EnrichmentWorker>(
                        1,
                        TimeUnit.HOURS,
                        15,
                        TimeUnit.MINUTES, // Flex interval
                    ).setConstraints(constraints)
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            WorkRequest.MIN_BACKOFF_MILLIS,
                            TimeUnit.MILLISECONDS,
                        ).addTag("enrichment")
                        .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest,
                )

                Log.i(TAG, "Periodic enrichment scheduled")
            }

            /**
             * Trigger immediate enrichment for a specific track or batch.
             * When trackId is null, only processes completely unenriched tracks to avoid excessive API calls.
             */
            fun enqueueImmediate(
                context: Context,
                trackId: Long? = null,
                appendAfterExisting: Boolean = false,
            ) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()

                val inputData =
                    if (trackId != null) {
                        workDataOf("track_id" to trackId)
                    } else {
                        // Flag to indicate this is app-startup enrichment (only process pending tracks)
                        workDataOf("is_immediate" to true)
                    }

                val workRequest =
                    OneTimeWorkRequestBuilder<EnrichmentWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag("enrichment_immediate")
                        .build()

                // Per-track enrichments normally use KEEP to deduplicate repeated requests.
                // A user action that changes enrichment state (notably "return to automatic
                // artwork") must get a fresh pass *after* any in-flight request that may have
                // started from an older snapshot. APPEND_OR_REPLACE preserves the current work
                // when it is healthy and guarantees a successor; if the chain is already failed
                // or cancelled WorkManager starts a replacement instead.
                val workName = if (trackId != null) "${WORK_NAME_IMMEDIATE}_$trackId" else WORK_NAME_IMMEDIATE
                val workPolicy =
                    immediateEnrichmentWorkPolicy(
                        trackId = trackId,
                        appendAfterExisting = appendAfterExisting,
                    )

                WorkManager.getInstance(context).enqueueUniqueWork(
                    workName,
                    workPolicy,
                    workRequest,
                )

                Log.i(TAG, "Immediate enrichment enqueued" + (trackId?.let { " for track $it" } ?: ""))
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_POST_IMPORT)
                Log.i(TAG, "Enrichment work cancelled")
            }

            /**
             * Accelerated post-import enrichment: 50-track batches (up to 150 for large backlogs)
             * every 15 minutes instead of 10 per hour, ordered by play count so frequently played
             * tracks are enriched first. Stops itself once the backlog is cleared, and requires
             * the battery not be low.
             */
            fun schedulePostImportEnrichment(
                context: Context,
                tracksCreated: Long = 0,
            ) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()

                val inputData =
                    workDataOf(
                        "is_post_import" to true,
                        "tracks_to_enrich" to tracksCreated,
                    )

                // Run every 15 minutes with 5 minute flex
                val workRequest =
                    PeriodicWorkRequestBuilder<EnrichmentWorker>(
                        15,
                        TimeUnit.MINUTES,
                        5,
                        TimeUnit.MINUTES,
                    ).setConstraints(constraints)
                        .setInputData(inputData)
                        .setBackoffCriteria(
                            BackoffPolicy.LINEAR,
                            1,
                            TimeUnit.MINUTES,
                        ).addTag("enrichment_post_import")
                        .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME_POST_IMPORT,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    workRequest,
                )

                Log.i(TAG, "Post-import accelerated enrichment scheduled for $tracksCreated tracks")
            }

            /**
             * Cancel only the post-import accelerated enrichment.
             * Called when the backlog is cleared or user cancels.
             */
            fun cancelPostImportEnrichment(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_POST_IMPORT)
                Log.i(TAG, "Post-import enrichment cancelled")
            }

            /**
             * Start a bulk "Enrich All" sweep. The caller (EnrichmentReportViewModel) requeues
             * all non-enriched tracks to PENDING first, then passes the resulting backlog size as
             * [total]. Runs as a foreground OneTime worker (tagged TAG_ENRICH_ALL) that survives
             * the user leaving the screen; cancellable via [cancelEnrichAll]. Progress is reported
             * via setProgress("processed", "total") and observed through getWorkInfosByTagFlow.
             *
             * Expedited so the sweep starts immediately when the user taps the button instead
             * of waiting for the periodic scheduler. REPLACE (not KEEP) so a sweep can be
             * started again after a previous run was cancelled or failed — with KEEP the
             * finished/cancelled work would block every future enqueue and the button would
             * appear to do nothing.
             */
            fun enqueueEnrichAll(
                context: Context,
                total: Int,
            ) {
                // Expedited work only supports network/storage constraints, so
                // setRequiresBatteryNotLow() must NOT be used here — it throws
                // IllegalArgumentException at build() time. This is user-initiated
                // work, so running regardless of battery level is acceptable.
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                val inputData =
                    workDataOf(
                        "is_enrich_all" to true,
                        "total_to_process" to total,
                    )
                val workRequest =
                    OneTimeWorkRequestBuilder<EnrichmentWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                        .addTag(TAG_ENRICH_ALL)
                        .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_ENRICH_ALL,
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )
                Log.i(TAG, "Enrich All enqueued for $total tracks")
            }

            fun cancelEnrichAll(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_ENRICH_ALL)
                Log.i(TAG, "Enrich All cancelled")
            }
        }

        override suspend fun doWork(): Result = doWorkInternal()

        private suspend fun doWorkInternal(): Result {
            Log.i(TAG, "Starting enrichment work")

            val specificTrackId = inputData.getLong("track_id", -1).takeIf { it > 0 }
            val isPostImport = inputData.getBoolean("is_post_import", false)
            val isEnrichAll = inputData.getBoolean("is_enrich_all", false)

            // Check if a Last.fm import is currently running - pause ALL enrichment to avoid resource contention
            // The import is the priority - enrichment happens AFTER import completes.
            // Exception: post-import enrichment runs after import completes (by design).
            val isImportRunning = LastFmImportWorker.isImportRunning(applicationContext)
            if (isImportRunning && !isPostImport) {
                if (isEnrichAll) {
                    // User-initiated bulk sweep: retry after backoff so it runs once import finishes
                    Log.i(TAG, "Last.fm import in progress - deferring Enrich All (will retry)")
                    return Result.retry()
                }
                Log.i(TAG, "Last.fm import in progress - deferring ALL enrichment to avoid resource contention")
                // Return success but don't do work - periodic will retry later
                return Result.success()
            }

            val startedAt = System.currentTimeMillis()
            val trigger =
                when {
                    isEnrichAll -> EnrichmentTrigger.ENRICH_ALL
                    specificTrackId != null -> EnrichmentTrigger.SINGLE_TRACK
                    else -> EnrichmentTrigger.BATCH
                }
            // Read once, before the work, so the reduction can be derived afterwards. Taken
            // after the deferral guards above, so a run that did nothing reports nothing.
            val backlogBefore = pendingEnrichmentCount()

            return try {
                // First, link any unlinked artists (fixes imported tracks without artist relationships)
                // Skip during active import to reduce DB contention
                if (!isImportRunning) {
                    linkUnlinkedArtists()
                }

                // Second, backfill any missing album art URLs to tracks table
                backfillAlbumArtUrls()

                if (isEnrichAll) {
                    // Bulk "Enrich All" sweep with progress reporting
                    enrichAll()
                } else if (specificTrackId != null) {
                    // Enrich specific track (only for real-time listening, not during import)
                    enrichSpecificTrack(specificTrackId)
                } else {
                    // Batch enrichment
                    enrichBatch()
                }

                Log.i(TAG, "Enrichment work completed successfully")

                // Invalidate stats cache so screens reload updated metadata immediately
                statsRepository.invalidateCache()

                // Only notify UI for user-triggered enrichments (e.g., refresh artist image)
                // Background batch enrichments should not trigger global UI refreshes
                if (specificTrackId != null) {
                    statsRepository.notifyMetadataUpdate()
                    Log.d(TAG, "Notified UI of metadata update for track $specificTrackId")
                }

                reportEnrichmentRun(trigger, RunPhase.COMPLETED, backlogBefore, startedAt)
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Enrichment work failed", e)
                if (runAttemptCount < 3) {
                    // A retry is not a reported outcome: the run has not finished failing yet,
                    // and reporting each attempt would triple-count one failure.
                    Result.retry()
                } else {
                    reportEnrichmentRun(
                        trigger = trigger,
                        phase = RunPhase.FAILED,
                        backlogBefore = backlogBefore,
                        startedAt = startedAt,
                        failure = FailureClassifier.of(e),
                    )
                    Result.failure()
                }
            }
        }

        /** Pending tracks, or 0 when the lookup fails — reporting must not break enrichment. */
        private suspend fun pendingEnrichmentCount(): Int =
            runCatching {
                enrichedMetadataRepository.getEnrichmentStats()[EnrichmentStatus.PENDING] ?: 0
            }.getOrDefault(0)

        /**
         * Reports one enrichment run.
         *
         * `cleared` is derived from the backlog before and after rather than counted per track,
         * so it cannot disagree with what the database holds.
         */
        private suspend fun reportEnrichmentRun(
            trigger: EnrichmentTrigger,
            phase: RunPhase,
            backlogBefore: Int,
            startedAt: Long,
            failure: me.avinas.tempo.data.analytics.FailureClass? = null,
        ) {
            runCatching {
                val backlogAfter = pendingEnrichmentCount()
                analyticsTracker.track(
                    EnrichmentRun(
                        trigger = trigger,
                        phase = phase,
                        backlog = backlogAfter,
                        cleared = (backlogBefore - backlogAfter).coerceAtLeast(0),
                        durationMillis = System.currentTimeMillis() - startedAt,
                        failure = failure,
                    ),
                )
            }
        }

        /**
         * Link artists for tracks that don't have artist relationships.
         * This fixes imported Spotify tracks and any legacy tracks without proper linking.
         */
        private suspend fun linkUnlinkedArtists() {
            try {
                val linkedCount = artistLinkingService.processUnlinkedTracks(50)
                if (linkedCount > 0) {
                    Log.i(TAG, "Linked artists for $linkedCount tracks")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error linking unlinked artists", e)
            }
        }

        /**
         * Backfill album art URLs from enriched_metadata to tracks table.
         * This fixes tracks that were enriched before the Track update was added.
         */
        private suspend fun backfillAlbumArtUrls() {
            try {
                val tracksToBackfill = enrichedMetadataDao.getEnrichedTracksWithMissingAlbumArt(50)
                if (tracksToBackfill.isEmpty()) return

                Log.d(TAG, "Backfilling album art for ${tracksToBackfill.size} tracks")

                for (metadata in tracksToBackfill) {
                    val track = trackDao.getTrackById(metadata.trackId)
                    if (track != null && metadata.albumArtUrl != null) {
                        // Fix HTTP URLs to HTTPS for better reliability
                        val fixedArtUrl = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrl)
                        val updatedTrack =
                            track.copy(
                                album = if (track.album.isNullOrBlank()) metadata.albumTitle else track.album,
                            )
                        trackDao.updatePreservingManualArtwork(updatedTrack)
                        trackDao.updateAutomaticAlbumArtUrl(metadata.trackId, fixedArtUrl)

                        // Also update the enriched metadata if URL was fixed
                        if (fixedArtUrl != metadata.albumArtUrl) {
                            enrichedMetadataDao.upsertFromAutomaticEnrichment(
                                metadata.copy(
                                    albumArtUrl = fixedArtUrl,
                                    albumArtUrlSmall = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrlSmall),
                                    albumArtUrlLarge = MusicBrainzEnrichmentService.fixHttpUrl(metadata.albumArtUrlLarge),
                                ),
                            )
                        }
                    }
                }

                Log.i(TAG, "Backfilled album art for ${tracksToBackfill.size} tracks")
            } catch (e: Exception) {
                Log.w(TAG, "Error backfilling album art", e)
            }
        }

        private suspend fun enrichSpecificTrack(trackId: Long) {
            val track = trackDao.getTrackById(trackId)
            if (track == null) {
                Log.w(TAG, "Track $trackId not found")
                // Orphan metadata row (track deleted): remove it so it can never be
                // picked up by a sweep again.
                enrichedMetadataDao.deleteByTrackId(trackId)
                return
            }

            // Skip enrichment if artist is unknown - metadata hasn't settled yet.
            // Mark SKIPPED so it does not stay PENDING: bulk sweeps ("Enrich All") fetch
            // PENDING rows until none remain, and an un-enrichable PENDING track would be
            // re-processed forever, freezing the progress bar. When the real artist arrives
            // the track is re-queued (markForReEnrichment) and enriched normally.
            if (me.avinas.tempo.utils.ArtistParser
                    .isUnknownArtist(track.artist)
            ) {
                Log.d(TAG, "Skipping enrichment for track $trackId: artist is unknown")
                val meta = enrichedMetadataDao.forTrackSync(trackId)
                if (meta != null && meta.enrichmentStatus == EnrichmentStatus.PENDING) {
                    enrichedMetadataDao.upsertFromAutomaticEnrichment(meta.copy(enrichmentStatus = EnrichmentStatus.SKIPPED))
                }
                return
            }

            Log.d(TAG, "Starting modular enrichment for track $trackId")

            var metadata = enrichedMetadataDao.forTrackSync(trackId)
            val preferred = coverArtSourcePreference.getPreferredSource()
            // Preserve the normal enrichment order: the preference applies to
            // artwork arbitration only, not genres, previews or audio features.
            // Only keep the artwork gap open for strategies that can actually provide or replace the artwork.
            for (strategy in strategies) {
                val originalGap =
                    metadata?.identifyGap() ?: me.avinas.tempo.data.enrichment.EnrichmentGap(
                        missingAlbumArt = true,
                        missingGenres = true,
                        missingAudioFeatures = true,
                        missingArtistImage = true,
                        missingPreviewUrl = true,
                    )

                val currentGap = if (preferred != null && metadata?.albumArtSource != preferred) {
                    val strategySource = strategy.albumArtSource
                    val canReplace = strategySource != null &&
                        (strategySource == preferred ||
                            CoverArtSourcePreference.shouldReplace(
                                metadata?.albumArtSource ?: AlbumArtSource.NONE,
                                strategySource,
                                preferred,
                            ))
                    if (canReplace) {
                        originalGap.copy(missingAlbumArt = true)
                    } else {
                        originalGap
                    }
                } else {
                    originalGap
                }
                if (currentGap.isEmpty()) {
                    Log.d(TAG, "Track $trackId is fully enriched. Stopping chain.")
                    break
                }

                if (strategy.canProvide(currentGap)) {
                    Log.d(TAG, "Applying strategy ${strategy.name} for track $trackId (Gap: $currentGap)")
                    val updated = strategy.enrich(track, metadata)
                    if (updated != null) {
                        metadata = updated
                        Log.d(TAG, "Strategy ${strategy.name} updated metadata for $trackId")
                    } else {
                        Log.d(TAG, "Strategy ${strategy.name} provided no updates for $trackId")
                    }
                } else {
                    Log.v(TAG, "Strategy ${strategy.name} skipped (Cannot provide for gap: $currentGap)")
                }
            }

            // Genre Fallback: Infer from artist's other tracks if still missing genres
            if (metadata != null) {
                if (metadata.genres.isNullOrEmpty()) {
                    Log.d(TAG, "Track $trackId: No genres from external sources, attempting inference from artist's other tracks")

                    val primaryArtist = ArtistParser.getPrimaryArtist(track.artist)
                    val rawGenreColumns =
                        enrichedMetadataDao.getGenresFromArtistOtherTracks(
                            artistName = primaryArtist,
                            excludeTrackId = trackId,
                            limit = 5,
                        )

                    // Columns historically stored genres as JSON-array text; current
                    // format is "|||"-delimited. Converters.repairListColumnValue
                    // handles both, so inference works across both generations of rows.
                    val allGenres =
                        rawGenreColumns
                            .flatMap {
                                me.avinas.tempo.data.local.Converters
                                    .repairListColumnValue(it)
                            }
                    if (allGenres.isNotEmpty()) {
                        val genreCounts = allGenres.groupingBy { it.lowercase() }.eachCount()
                        val topGenres =
                            genreCounts.entries
                                .sortedByDescending { it.value }
                                .take(3)
                                .map { it.key.replaceFirstChar { c -> c.uppercase() } }

                        Log.i(TAG, "Track $trackId: Inferred genres from artist's other tracks: $topGenres")

                        val updatedMetadata =
                            metadata.copy(
                                genres = topGenres,
                                enrichmentStatus = me.avinas.tempo.data.local.entities.EnrichmentStatus.ENRICHED,
                                cacheTimestamp = System.currentTimeMillis(),
                            )
                        enrichedMetadataDao.upsertFromAutomaticEnrichment(updatedMetadata)
                    } else {
                        Log.d(TAG, "Track $trackId: No genres found from artist's other tracks either")
                    }
                }
            }

            // CRITICAL FIX: Explicitly update the Track entity with the enriched album art
            // The UI observes the Track table, not EnrichedMetadata, so we must propagate the URL immediately
            // This fixes the issue where enrichment succeeds but the UI still shows no cover art
            try {
                // Re-fetch latest metadata to get the most up-to-date URL (from strategies or genre inference)
                val finalMetadata = enrichedMetadataDao.forTrackSync(trackId)

                if (finalMetadata?.albumArtUrl != null) {
                    // Fix HTTP URLs to HTTPS for better reliability
                    val fixedArtUrl = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrl)

                    // Sync resolved art URL to the track record
                    val currentTrack = trackDao.getTrackById(trackId)
                    if (currentTrack != null && currentTrack.albumArtUrl != fixedArtUrl) {
                        Log.i(TAG, "Propagating enriched album art to Track $trackId: $fixedArtUrl")
                        val updatedTrack =
                            currentTrack.copy(
                                // Also update album name if track is missing it
                                album = if (currentTrack.album.isNullOrBlank()) finalMetadata.albumTitle else currentTrack.album,
                            )
                        trackDao.updatePreservingManualArtwork(updatedTrack)
                        trackDao.updateAutomaticAlbumArtUrl(trackId, fixedArtUrl)

                        // Also update the enriched metadata if URL was changed
                        if (fixedArtUrl != finalMetadata.albumArtUrl) {
                            Log.d(TAG, "Fixed HTTP URL to HTTPS for track $trackId")
                            enrichedMetadataDao.upsertFromAutomaticEnrichment(
                                finalMetadata.copy(
                                    albumArtUrl = fixedArtUrl,
                                    albumArtUrlSmall = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrlSmall),
                                    albumArtUrlLarge = MusicBrainzEnrichmentService.fixHttpUrl(finalMetadata.albumArtUrlLarge),
                                ),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to propagate album art to Track entity", e)
            }

            // Guarantee a terminal status: every processed track must leave PENDING.
            // If all strategies ran and the row is still PENDING, settle it based on what
            // was actually gathered. Without this, tracks no source could match stay
            // PENDING forever and bulk sweeps ("Enrich All") re-process them in an
            // infinite loop, freezing the progress bar.
            // Also upgrade NOT_FOUND/FAILED rows that DID gain data from a later source
            // in the chain (e.g. MusicBrainz missed it but iTunes found art) — otherwise
            // the report would count them as "not found" even though they have metadata.
            val settled = enrichedMetadataDao.forTrackSync(trackId)
            if (settled != null &&
                settled.enrichmentStatus in
                listOf(
                    EnrichmentStatus.PENDING,
                    EnrichmentStatus.NOT_FOUND,
                    EnrichmentStatus.FAILED,
                    EnrichmentStatus.SKIPPED,
                )
            ) {
                val gainedData =
                    !settled.albumArtUrl.isNullOrBlank() ||
                        settled.genres.isNotEmpty() ||
                        settled.audioFeaturesJson != null ||
                        settled.hasArtistImage() ||
                        settled.previewUrl != null
                if (gainedData || settled.enrichmentStatus == EnrichmentStatus.PENDING) {
                    val terminalStatus = if (gainedData) EnrichmentStatus.ENRICHED else EnrichmentStatus.NOT_FOUND
                    enrichedMetadataDao.upsertFromAutomaticEnrichment(
                        settled.copy(
                            enrichmentStatus = terminalStatus,
                            enrichmentError = if (gainedData) null else "No source matched this track",
                            retryCount = 0,
                            lastEnrichmentAttempt = System.currentTimeMillis(),
                            cacheTimestamp = System.currentTimeMillis(),
                        ),
                    )
                    Log.d(TAG, "Track $trackId settled to $terminalStatus after full strategy chain")
                }
            }
        }

        private suspend fun enrichBatch() {
            var enrichedCount = 0
            var failedCount = 0

            // Check enrichment mode
            val isImmediateEnrichment = inputData.getBoolean("is_immediate", false)
            val isPostImportEnrichment = inputData.getBoolean("is_post_import", false)

            // For post-import mode, check if we should continue or stop
            if (isPostImportEnrichment) {
                val pendingCount = enrichedMetadataDao.countByStatus(EnrichmentStatus.PENDING)

                if (pendingCount == 0) {
                    Log.i(TAG, "Post-import enrichment complete: no more pending tracks")
                    // Cancel the periodic work since we're done
                    cancelPostImportEnrichment(applicationContext)
                    return
                }

                // Large-backlog cap: defer one-play wonders (play_count < threshold) to SKIPPED so
                // post-import stops after the frequently-played tracks are enriched instead of
                // churning through thousands of rare tracks. Deferred tracks are enriched on-demand
                // (when opened) or via "Enrich All". Idempotent: already-deferred tracks are SKIPPED.
                if (pendingCount > POST_IMPORT_LARGE_BACKLOG_THRESHOLD) {
                    val deferred = enrichedMetadataDao.markLowPlayPendingAsSkipped(POST_IMPORT_MIN_PLAY_COUNT)
                    if (deferred > 0) {
                        Log.i(TAG, "Deferred $deferred low-play tracks for large backlog (now SKIPPED)")
                    }
                }

                Log.i(TAG, "Post-import enrichment: $pendingCount tracks remaining")
            }

            // Determine batch size and delay based on mode
            val batchSize: Int
            val interTrackDelay: Long

            if (isPostImportEnrichment) {
                val pendingCount = enrichedMetadataDao.countByStatus(EnrichmentStatus.PENDING)
                // Scale batch size based on backlog size for faster throughput on large imports
                batchSize =
                    when {
                        pendingCount > 5000 -> POST_IMPORT_BATCH_SIZE_HUGE
                        pendingCount > 1000 -> POST_IMPORT_BATCH_SIZE_LARGE
                        else -> POST_IMPORT_BATCH_SIZE
                    }
                // Shorter delay for larger backlogs (still respects rate limits across different APIs)
                interTrackDelay =
                    when {
                        pendingCount > 5000 -> 500L
                        pendingCount > 1000 -> 750L
                        else -> POST_IMPORT_INTER_TRACK_DELAY_MS
                    }
                Log.i(TAG, "Post-import batch config: batchSize=$batchSize, delay=${interTrackDelay}ms (pending=$pendingCount)")
            } else {
                batchSize = BATCH_SIZE
                interTrackDelay = INTER_TRACK_DELAY_MS
            }

            // 1. Process pending tracks (completely unenriched)
            // For large post-import backlogs, use two-tier enrichment:
            //   Tier 1: Top-played tracks (play_count >= 2) — enriched first
            //   Tier 2: Remaining pending tracks — filled in if tier 1 doesn't fill the batch
            val pendingTracks: List<EnrichedMetadata> =
                if (isPostImportEnrichment) {
                    val pendingCount = enrichedMetadataDao.countByStatus(EnrichmentStatus.PENDING)
                    if (pendingCount > POST_IMPORT_LARGE_BACKLOG_THRESHOLD) {
                        // Two-tier: get top-played first
                        val topPlayed =
                            enrichedMetadataDao.getTopPlayedTracksNeedingEnrichment(
                                status = EnrichmentStatus.PENDING,
                                minPlayCount = POST_IMPORT_MIN_PLAY_COUNT,
                                limit = batchSize,
                            )
                        if (topPlayed.size < batchSize) {
                            // Fill remaining slots with any pending track (ordered by play count)
                            val remaining = batchSize - topPlayed.size
                            val fillTracks =
                                enrichedMetadataDao.getTracksNeedingEnrichment(
                                    status = EnrichmentStatus.PENDING,
                                    limit = remaining,
                                )
                            // Merge, avoiding duplicates
                            val topPlayedIds = topPlayed.map { it.trackId }.toSet()
                            topPlayed + fillTracks.filter { it.trackId !in topPlayedIds }
                        } else {
                            topPlayed
                        }
                    } else {
                        enrichedMetadataDao.getTracksNeedingEnrichment(
                            status = EnrichmentStatus.PENDING,
                            limit = batchSize,
                        )
                    }
                } else {
                    enrichedMetadataDao.getTracksNeedingEnrichment(
                        status = EnrichmentStatus.PENDING,
                        limit = batchSize,
                    )
                }

            Log.d(
                TAG,
                "Found ${pendingTracks.size} pending tracks to enrich (mode: ${
                    when {
                        isPostImportEnrichment -> "post-import"
                        isImmediateEnrichment -> "immediate"
                        else -> "periodic"
                    }
                })",
            )

            for (metadata in pendingTracks) {
                if (isStopped) break

                val trackId = metadata.trackId
                enrichSpecificTrack(trackId)

                // Check success based on updated metadata
                val updated = enrichedMetadataDao.forTrackSync(trackId)
                val success =
                    updated?.enrichmentStatus == EnrichmentStatus.ENRICHED ||
                        (updated?.genres?.isNotEmpty() == true) ||
                        (updated?.audioFeaturesJson != null)

                if (success) enrichedCount++ else failedCount++

                kotlinx.coroutines.delay(interTrackDelay)
            }

            // Skip retry/refresh steps for immediate or post-import enrichment
            // Post-import focuses on clearing the backlog first; retries happen later via periodic work
            if (isImmediateEnrichment || isPostImportEnrichment) {
                val mode = if (isPostImportEnrichment) "Post-import" else "Immediate"
                Log.d(TAG, "$mode enrichment complete: $enrichedCount enriched, $failedCount failed")
                Log.i(TAG, "Batch complete: enriched=$enrichedCount, failed=$failedCount")
                return
            }

            // 2. Retry enriched tracks with missing cover art or genres (only for periodic work)
            if (!isStopped) {
                val incompleteTracks =
                    enrichedMetadataDao.getEnrichedTracksWithIncompleteData(
                        retryAfter = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L),
                        limit = 3,
                    )

                Log.d(TAG, "Found ${incompleteTracks.size} enriched tracks with incomplete data")

                for (metadata in incompleteTracks) {
                    if (isStopped) break

                    Log.d(TAG, "Re-enriching incomplete track ${metadata.trackId}")
                    enrichSpecificTrack(metadata.trackId)
                    kotlinx.coroutines.delay(interTrackDelay)
                }
            }

            // 3. Retry failed tracks (only for periodic work)
            if (!isStopped) {
                val retryTracks =
                    enrichedMetadataDao.getTracksToRetry(
                        status = EnrichmentStatus.FAILED,
                        maxRetries = EnrichedMetadata.MAX_RETRY_COUNT,
                        limit = RETRY_BATCH_SIZE,
                    )

                Log.d(TAG, "Found ${retryTracks.size} failed tracks to retry")

                for (metadata in retryTracks) {
                    if (isStopped) break

                    enrichSpecificTrack(metadata.trackId) // This will try all strategies again
                    kotlinx.coroutines.delay(interTrackDelay)
                }
            }

            // Note: We don't refresh stale cache for fully enriched tracks because:
            // - Album art URLs rarely change
            // - Genres/metadata are stable once set
            // - Refreshing wastes API quota and bandwidth
            // Only incomplete/failed tracks get retried above

            // 5. Fetch missing artist images (Legacy loop reduced, mostly handled by Strategies now)
            // We still keep a small check for tracks that might have missed it
            if (!isStopped && spotifyEnrichmentSource.isAvailable()) {
                // This is largely redundant now but good for cleanup of old data
                val artistsNeedingImages = enrichedMetadataDao.getTracksNeedingArtistImage(limit = 10)
                for (metadata in artistsNeedingImages) {
                    if (isStopped) break
                    if (metadata.spotifyArtistId != null) {
                        enrichSpecificTrack(metadata.trackId)
                    }
                    kotlinx.coroutines.delay(interTrackDelay)
                }
            }

            Log.i(TAG, "Batch complete: enriched=$enrichedCount, failed=$failedCount")
        }

        /**
         * Bulk "Enrich All" mode: sweep every PENDING track (requeued by the report screen) in
         * rate-limited batches, reporting progress via setProgress so the Enrichment Report screen
         * can render a progress bar. Runs as a foreground worker so it survives the user leaving
         * the screen; cancellable via cancelEnrichAll(). Progress is stateless (total - remaining
         * PENDING) so it stays correct even if the worker is killed and the user re-taps.
         *
         * Termination guarantees:
         * - enrichSpecificTrack() always moves a processed track out of PENDING (to ENRICHED,
         *   NOT_FOUND, FAILED or SKIPPED), so the PENDING count strictly decreases.
         * - As a belt-and-braces guard, if a full batch makes no progress (PENDING count does
         *   not drop), the sweep stops instead of looping forever — the user previously saw
         *   this as the progress bar freezing and never finishing.
         */
        private suspend fun enrichAll(): Result {
            val total = inputData.getInt("total_to_process", 0).coerceAtLeast(1)
            val batchSize = 25
            // Track IDs already processed in this sweep. enrichSpecificTrack() guarantees each
            // processed track leaves PENDING, so a healthy sweep never returns the same ID
            // twice. If a whole batch comes back already-seen, the sweep is stuck — stop
            // instead of looping forever (the user-visible "freezes and never finishes").
            val processedIds = HashSet<Long>()
            // Progress is tracked locally rather than re-counted from the database.
            //
            // This loop used to call countByStatus() *and* setProgress() after every single
            // track. countByStatus is index-only so it is cheap but not free, and setProgress
            // persists to WorkManager's own database — so a 25-track batch cost ~50 extra
            // database writes purely to move a progress bar. enrichSpecificTrack() guarantees a
            // processed track leaves PENDING, so a local counter is equivalent for reporting.
            var completed = 0
            var lastReportedCompleted = 0
            val progressInterval = 5
            while (!isStopped) {
                val batch = enrichedMetadataDao.getTracksNeedingEnrichment(EnrichmentStatus.PENDING, batchSize)
                if (batch.isEmpty()) break
                if (batch.all { it.trackId in processedIds }) {
                    Log.w(TAG, "Enrich All stalled: batch of ${batch.size} tracks already processed but still PENDING, stopping sweep")
                    break
                }
                for (metadata in batch) {
                    if (isStopped) break
                    processedIds.add(metadata.trackId)
                    try {
                        enrichSpecificTrack(metadata.trackId)
                    } catch (e: Exception) {
                        // One bad track must not abort the whole sweep. Mark it FAILED so
                        // it leaves PENDING (the periodic worker retries it later) and move on.
                        Log.e(TAG, "Enrich All: track ${metadata.trackId} threw, marking FAILED", e)
                        val meta = enrichedMetadataDao.forTrackSync(metadata.trackId)
                        if (meta != null && meta.enrichmentStatus == EnrichmentStatus.PENDING) {
                            enrichedMetadataDao.upsertFromAutomaticEnrichment(
                                meta.copy(
                                    enrichmentStatus = EnrichmentStatus.FAILED,
                                    enrichmentError = e.message ?: "Enrichment crashed",
                                    retryCount = meta.retryCount + 1,
                                    lastEnrichmentAttempt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                    completed++
                    // Report in small steps so the bar still moves smoothly, without paying a
                    // database write per track. The final count is reported once after the loop.
                    if (completed - lastReportedCompleted >= progressInterval) {
                        lastReportedCompleted = completed
                        setProgress(
                            workDataOf(
                                "processed" to completed.coerceIn(0, total),
                                "total" to total,
                            ),
                        )
                    }
                    kotlinx.coroutines.delay(INTER_TRACK_DELAY_MS)
                }
            }
            statsRepository.invalidateCache()
            val remaining = enrichedMetadataDao.countByStatus(EnrichmentStatus.PENDING)
            setProgress(
                workDataOf(
                    "processed" to (total - remaining).coerceIn(0, total),
                    "total" to total,
                    "done" to true,
                ),
            )
            Log.i(TAG, "Enrich All complete: ${total - remaining}/$total processed")
            return Result.success()
        }

        /**
         * Required for expedited work on Android 10 (SDK 29).
         * Returns ForegroundInfo with notification when work runs as foreground service.
         */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Music Enrichment",
                    NotificationManager.IMPORTANCE_LOW,
                )
            notificationManager.createNotificationChannel(channel)

            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Enriching music data")
                    .setContentText("Adding metadata to your tracks...")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()

            return ForegroundNotificationGuard.postableForegroundInfo(
                context = applicationContext,
                notificationId = NOTIFICATION_ID,
                requestedChannelId = NOTIFICATION_CHANNEL_ID,
                notification = notification,
                logTag = TAG,
            )
        }
    }
