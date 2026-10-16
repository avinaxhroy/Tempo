package me.avinas.tempo.data.lastfm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.local.ArchiveTimestampCodec
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.LastFmImportMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.LastFmImportMetadata
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.data.remote.lastfm.LastFmApi
import me.avinas.tempo.data.remote.lastfm.LastFmScrobble
import me.avinas.tempo.data.remote.lastfm.LastFmTopTrack
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for importing listening history from Last.fm using tiered active/archive storage.
 */
@Singleton
class LastFmImportService
    @Inject
    constructor(
        @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
        private val lastFmApi: LastFmApi,
        private val trackRepository: TrackRepository,
        private val trackResolver: TrackResolver,
        private val listeningRepository: ListeningRepository,
        private val artistLinkingService: ArtistLinkingService,
        private val enrichedMetadataDao: EnrichedMetadataDao,
        private val userPreferencesDao: UserPreferencesDao,
        private val importMetadataDao: LastFmImportMetadataDao,
        private val scrobbleArchiveDao: ScrobbleArchiveDao,
        private val listeningEventDao: ListeningEventDao,
        private val appDatabase: me.avinas.tempo.data.local.AppDatabase,
        private val statsRepository: me.avinas.tempo.data.repository.StatsRepository,
    ) {
        companion object {
            private const val TAG = "LastFmImportService"

            // API configuration - uses BuildConfig from local.properties
            private val API_KEY: String
                get() = BuildConfig.LASTFM_API_KEY

            private const val PAGE_SIZE = 200 // Max safe page size
            private const val RATE_LIMIT_MS = 100L // ~10 req/sec, will back off on 429
            private const val RATE_LIMIT_BACKOFF_MS = 5000L // Backoff when rate limited
            private const val MAX_RETRIES_PER_PAGE = 3

            // Hard ceiling on sync pagination. Sync walks newest→oldest, so hitting the cap
            // means more history is waiting; the caller must not treat that as "caught up".
            private const val SYNC_MAX_PAGES = 50

            // Hard ceiling on full-import pagination. The loop used to be bounded only by the
            // `total_pages` field of the API response, i.e. by remote input: a bogus value
            // ("2147483647" parses fine into an Int) would keep it fetching and writing forever.
            // 20k pages x 200 = 4M scrobbles, past any real Last.fm history.
            private const val MAX_IMPORT_PAGES = 20_000

            // Cap on retries for a body-level "rate limit exceeded" response. Without this the
            // page loop spins forever when Last.fm keeps answering 200 OK + error code 29.
            private const val MAX_RATE_LIMIT_RETRIES = 5

            // Cap on the in-memory resolved-track cache. Bounds worst-case retention to a few MB
            // on a pathological history; the cache is a per-run optimisation, not a session store.
            private const val TRACK_CACHE_MAX_ENTRIES = 20_000

            // SQLite auto_vacuum = INCREMENTAL. Required for PRAGMA incremental_vacuum to do anything.
            private const val AUTO_VACUUM_INCREMENTAL = 2

            // Source identifier for imported events
            const val IMPORT_SOURCE = "fm.last.import"

            // Default values for imported events (Last.fm doesn't provide these)
            private const val DEFAULT_DURATION_MS = 210_000L // 3.5 minutes

            // An imported scrobble means the track was played, not that it was played to the
            // end. 80 matches the other importers (Spotify/YouTube) and is the documented
            // "assume a full play" value; 100 claimed perfect completion for every scrobble,
            // which drove AVG(completionPercentage) to 100 and getSkipsCount to 0 in StatsDao.
            private const val DEFAULT_COMPLETION_PERCENTAGE = 80

            // Replay detection threshold (same track within 5 minutes)
            private const val REPLAY_THRESHOLD_MS = 5 * 60 * 1000L

            // Batch size for active event inserts
            private const val EVENT_BATCH_SIZE = 80

            // Batch size for enriched metadata inserts
            private const val METADATA_BATCH_SIZE = 500

            // Tier configurations
            // NOTE: "Everything" tier removed intentionally. For 10+ year Last.fm users,
            // importing all scrobbles as active events (100K-300K rows) degrades SQLite
            // query performance and uses 150-200MB storage. Archive handles long-tail data.
            object Tiers {
                val QUICK =
                    TierConfig(
                        name = "QUICK",
                        topTracksCount = 500,
                        recentMonths = 3,
                        estimatedCoverage = 60,
                    )
                val STANDARD =
                    TierConfig(
                        name = "STANDARD",
                        topTracksCount = 1000,
                        recentMonths = 12,
                        estimatedCoverage = 85,
                    )
                val DEEP =
                    TierConfig(
                        name = "DEEP",
                        topTracksCount = 2000,
                        recentMonths = 24,
                        estimatedCoverage = 92,
                    )
            }
        }

        /**
         * Configuration for an import tier.
         */
        data class TierConfig(
            val name: String,
            val topTracksCount: Int,
            val recentMonths: Int,
            val estimatedCoverage: Int,
        )

        /**
         * Progress state for UI updates.
         */
        sealed class ImportProgress {
            object Idle : ImportProgress()

            data class Discovering(
                val message: String,
            ) : ImportProgress()

            data class Processing(
                val message: String,
            ) : ImportProgress()

            data class Importing(
                val phase: String,
                val current: Long,
                val total: Long,
                val eventsCreated: Long,
                val tracksCreated: Long,
                val archived: Long,
                val tierName: String = "", // For UI to show appropriate labels
            ) : ImportProgress()

            data class Completed(
                val result: ImportResult,
            ) : ImportProgress()

            data class Failed(
                val error: String,
                val isRecoverable: Boolean = false,
            ) : ImportProgress()

            data class RateLimited(
                val retryAfterMs: Long,
            ) : ImportProgress()
        }

        /**
         * API error types for better error handling.
         */
        sealed class LastFmApiError : Exception() {
            data class UserNotFound(
                override val message: String,
            ) : LastFmApiError()

            data class PrivateProfile(
                override val message: String,
            ) : LastFmApiError()

            data class InvalidApiKey(
                override val message: String,
            ) : LastFmApiError()

            data class RateLimited(
                val retryAfterMs: Long,
            ) : LastFmApiError()

            data class ServiceUnavailable(
                override val message: String,
            ) : LastFmApiError()

            data class NetworkError(
                override val message: String,
                override val cause: Throwable?,
            ) : LastFmApiError()

            data class UnknownError(
                override val message: String,
                val errorCode: Int?,
            ) : LastFmApiError()
        }

        /**
         * Result of user discovery phase.
         */
        data class DiscoveryResult(
            val username: String,
            val totalScrobbles: Long,
            val registeredDate: Long?,
            val latestScrobble: Long?,
            val profileImageUrl: String?,
            val topTracksCount: Int,
            val lovedTracksCount: Int,
        )

        /**
         * Result of import operation.
         */
        data class ImportResult(
            val success: Boolean,
            val eventsImported: Long,
            val tracksCreated: Long,
            val artistsCreated: Long,
            val scrobblesArchived: Long,
            val duplicatesSkipped: Long,
            val totalProcessed: Long,
            val durationSeconds: Long = 0,
            val errorMessage: String? = null,
            /**
             * Pages the API refused to return after retries, which were skipped to keep going.
             * Any value above zero means the imported history has holes and should be re-run.
             */
            val skippedPages: Int = 0,
        ) {
            // Aliases for UI compatibility
            val activeSetCount: Long get() = eventsImported
            val archivedCount: Long get() = scrobblesArchived
            val isComplete: Boolean get() = skippedPages == 0
        }

        // Progress state flow for UI observation
        private val _progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)
        val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

        // Active set tracking (built during discovery)
        // synchronizedSet: cancelImport() may clear these from the ViewModel thread while
        // buildActiveSet() is iterating them on the IO dispatcher.
        private val activeSetKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val lovedTrackKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Performance Optimization Caches

        // In-memory track cache to avoid N+1 queries during import
        // Key: "title|artist" normalized, Value: Track
        // ConcurrentHashMap: cancelImport() may clear this while import iterates it.
        private val trackCache = java.util.concurrent.ConcurrentHashMap<String, Track>()

        // Batch pending EnrichedMetadata for bulk insert.
        // Mutated from the IO dispatcher (queueEnrichedMetadata) and from the ViewModel thread
        // (cancelImport), so every access is taken under the list's own monitor.
        private val pendingMetadata = mutableListOf<EnrichedMetadata>()

        // Real track durations harvested from user.getTopTracks (seconds → ms), keyed like
        // [trackCache]. Last.fm scrobbles carry no duration, so without this every imported play
        // is stamped with DEFAULT_DURATION_MS and reported listening time is inflated.
        private val knownDurations = java.util.concurrent.ConcurrentHashMap<String, Long>()

        // API Error Handling

        /**
         * Check Last.fm API response for errors.
         * Last.fm returns error codes in the JSON body even with 200 OK responses.
         *
         * Common error codes:
         * - 6: Invalid parameters (user not found)
         * - 10: Invalid API key
         * - 17: Login required (private profile or restricted data)
         * - 26: Suspended API key
         * - 29: Rate limit exceeded
         */
        private fun checkApiError(
            errorCode: Int?,
            errorMessage: String?,
        ): LastFmApiError? {
            if (errorCode == null || errorCode == 0) return null

            return when (errorCode) {
                6 -> LastFmApiError.UserNotFound(errorMessage ?: "User not found")
                10 -> LastFmApiError.InvalidApiKey(errorMessage ?: "Invalid API key")
                17 -> LastFmApiError.PrivateProfile(errorMessage ?: "Profile is private or restricted")
                26 -> LastFmApiError.InvalidApiKey(errorMessage ?: "API key has been suspended")
                29 -> LastFmApiError.RateLimited(RATE_LIMIT_BACKOFF_MS)
                else -> LastFmApiError.UnknownError(errorMessage ?: "Unknown Last.fm error", errorCode)
            }
        }

        /**
         * Handle HTTP response codes.
         */
        private fun <T> handleHttpError(response: retrofit2.Response<T>): LastFmApiError =
            when (response.code()) {
                429 -> {
                    LastFmApiError.RateLimited(RATE_LIMIT_BACKOFF_MS)
                }

                500, 502, 503, 504 -> {
                    LastFmApiError.ServiceUnavailable(
                        "Last.fm service temporarily unavailable (${response.code()})",
                    )
                }

                401 -> {
                    LastFmApiError.InvalidApiKey("Authentication failed")
                }

                403 -> {
                    LastFmApiError.PrivateProfile("Access forbidden - profile may be private")
                }

                404 -> {
                    LastFmApiError.UserNotFound("User not found")
                }

                else -> {
                    LastFmApiError.UnknownError("HTTP error: ${response.code()}", response.code())
                }
            }

        /**
         * Execute an API call with retry logic for transient failures.
         * Handles rate limits with exponential backoff.
         */
        private suspend fun <T> executeWithRetry(
            operation: String,
            maxRetries: Int = MAX_RETRIES_PER_PAGE,
            apiCall: suspend () -> retrofit2.Response<T>,
        ): Result<T> {
            var lastError: Exception? = null
            var backoffMs = RATE_LIMIT_BACKOFF_MS

            repeat(maxRetries) { attempt ->
                try {
                    val response = apiCall()

                    if (!response.isSuccessful) {
                        val httpError = handleHttpError(response)

                        // Rate limited - back off and retry
                        if (httpError is LastFmApiError.RateLimited) {
                            Log.w(TAG, "$operation: Rate limited, waiting ${backoffMs}ms before retry ${attempt + 1}/$maxRetries")
                            _progress.value = ImportProgress.RateLimited(backoffMs)
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(60_000L) // Max 60 seconds
                            lastError = httpError
                            return@repeat
                        }

                        // Service unavailable - retry with backoff
                        if (httpError is LastFmApiError.ServiceUnavailable) {
                            Log.w(TAG, "$operation: Service unavailable, waiting ${backoffMs}ms before retry ${attempt + 1}/$maxRetries")
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                            lastError = httpError
                            return@repeat
                        }

                        // Other HTTP errors - fail immediately
                        return Result.failure(httpError)
                    }

                    val body = response.body()
                    if (body == null) {
                        lastError = LastFmApiError.UnknownError("Empty response body", null)
                        return@repeat
                    }

                    return Result.success(body)
                } catch (e: java.net.UnknownHostException) {
                    lastError = LastFmApiError.NetworkError("No internet connection", e)
                    Log.w(TAG, "$operation: Network error, retry ${attempt + 1}/$maxRetries", e)
                    delay(backoffMs)
                } catch (e: java.net.SocketTimeoutException) {
                    lastError = LastFmApiError.NetworkError("Connection timed out", e)
                    Log.w(TAG, "$operation: Timeout, retry ${attempt + 1}/$maxRetries", e)
                    delay(backoffMs)
                } catch (e: java.io.IOException) {
                    lastError = LastFmApiError.NetworkError("Network error: ${e.message}", e)
                    Log.w(TAG, "$operation: IO error, retry ${attempt + 1}/$maxRetries", e)
                    delay(backoffMs)
                }
            }

            return Result.failure(lastError ?: LastFmApiError.UnknownError("Unknown error after $maxRetries retries", null))
        }

        /**
         * Discover user's Last.fm account information.
         * This is the first step - shows user what will be imported.
         */
        suspend fun discoverUser(username: String): Result<DiscoveryResult> =
            withContext(Dispatchers.IO) {
                try {
                    _progress.value = ImportProgress.Discovering("Connecting to Last.fm...")

                    val userInfoResult =
                        executeWithRetry("getUserInfo") {
                            lastFmApi.getUserInfo(user = username, apiKey = API_KEY)
                        }

                    val userInfo =
                        userInfoResult.getOrElse { error ->
                            val errorMessage =
                                when (error) {
                                    is LastFmApiError.UserNotFound -> "User '$username' not found on Last.fm"
                                    is LastFmApiError.PrivateProfile -> "User '$username' has a private profile"
                                    is LastFmApiError.InvalidApiKey -> "Last.fm API configuration error"
                                    is LastFmApiError.NetworkError -> "Network error: ${error.message}"
                                    is LastFmApiError.RateLimited -> "Rate limited, please try again later"
                                    else -> error.message ?: "Failed to fetch user info"
                                }
                            val isRecoverable =
                                error is LastFmApiError.RateLimited ||
                                    error is LastFmApiError.NetworkError ||
                                    error is LastFmApiError.ServiceUnavailable
                            _progress.value = ImportProgress.Failed(errorMessage, isRecoverable)
                            return@withContext Result.failure(error)
                        }

                    // Check for API-level errors in the response body
                    val apiError = checkApiError(userInfo.error, userInfo.message)
                    if (apiError != null) {
                        val errorMessage =
                            when (apiError) {
                                is LastFmApiError.UserNotFound -> "User '$username' not found on Last.fm"
                                is LastFmApiError.PrivateProfile -> "User '$username' has a private profile"
                                else -> apiError.message ?: "Last.fm API error"
                            }
                        _progress.value = ImportProgress.Failed(errorMessage, apiError is LastFmApiError.RateLimited)
                        return@withContext Result.failure(apiError)
                    }

                    val user = userInfo.user
                    if (user == null) {
                        _progress.value = ImportProgress.Failed("User not found: $username", false)
                        return@withContext Result.failure(LastFmApiError.UserNotFound("User not found: $username"))
                    }

                    _progress.value = ImportProgress.Discovering("Analyzing your listening history...")

                    // Fetch top tracks count with error handling
                    delay(RATE_LIMIT_MS)
                    val topTracksResult =
                        executeWithRetry("getTopTracks") {
                            lastFmApi.getTopTracks(user = username, apiKey = API_KEY, limit = 1, page = 1)
                        }
                    val topTracksCount =
                        topTracksResult
                            .getOrNull()
                            ?.toptracks
                            ?.attr
                            ?.getTotal() ?: 0

                    // Fetch loved tracks count with error handling
                    delay(RATE_LIMIT_MS)
                    val lovedTracksResult =
                        executeWithRetry("getLovedTracks") {
                            lastFmApi.getLovedTracks(user = username, apiKey = API_KEY, limit = 1, page = 1)
                        }
                    val lovedTracksCount =
                        lovedTracksResult
                            .getOrNull()
                            ?.lovedtracks
                            ?.attr
                            ?.getTotal() ?: 0

                    // Fetch most recent scrobble timestamp (first non-nowplaying track on page 1)
                    delay(RATE_LIMIT_MS)
                    val recentTracksResult =
                        executeWithRetry("getRecentTracks") {
                            lastFmApi.getRecentTracks(user = username, apiKey = API_KEY, limit = 1, page = 1)
                        }
                    val latestScrobble =
                        recentTracksResult
                            .getOrNull()
                            ?.recenttracks
                            ?.track
                            ?.firstOrNull { it.date != null }
                            ?.getTimestampMs()

                    _progress.value = ImportProgress.Idle

                    Result.success(
                        DiscoveryResult(
                            username = user.name ?: username,
                            totalScrobbles = user.getPlayCountLong() ?: 0,
                            registeredDate = user.getRegisteredTimestampMs(),
                            latestScrobble = latestScrobble,
                            profileImageUrl = user.getBestImageUrl(),
                            topTracksCount = topTracksCount,
                            lovedTracksCount = lovedTracksCount,
                        ),
                    )
                } catch (e: LastFmApiError) {
                    Log.e(TAG, "Discovery failed with API error", e)
                    val isRecoverable =
                        e is LastFmApiError.RateLimited ||
                            e is LastFmApiError.NetworkError ||
                            e is LastFmApiError.ServiceUnavailable
                    _progress.value = ImportProgress.Failed(e.message ?: "Discovery failed", isRecoverable)
                    Result.failure(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery failed", e)
                    _progress.value = ImportProgress.Failed(e.message ?: "Discovery failed", false)
                    Result.failure(e)
                }
            }

        /**
         * Start the full import process.
         */
        suspend fun startImport(
            username: String,
            tier: TierConfig,
            totalScrobbles: Long,
            resumeFrom: LastFmImportMetadata? = null,
        ): Result<ImportResult> =
            withContext(Dispatchers.IO) {
                // Reference instant for the whole import. On resume this must stay the *original*
                // start so the tier's "recent N months" cutoff and the final sync cursor do not
                // shift between attempts.
                val startTimeMs = resumeFrom?.importStartedAt ?: System.currentTimeMillis()

                try {
                    Log.i(TAG, "Starting Last.fm import for $username with tier ${tier.name}")

                    // Create or reuse the import metadata record
                    val importId =
                        if (resumeFrom != null) {
                            Log.i(TAG, "Resuming import ${resumeFrom.id} from page ${resumeFrom.currentPage}")
                            importMetadataDao.updateStatus(resumeFrom.id, LastFmImportMetadata.STATUS_DISCOVERING)
                            resumeFrom.id
                        } else {
                            importMetadataDao.insert(
                                LastFmImportMetadata(
                                    lastfmUsername = username,
                                    importTier = tier.name,
                                    activeTrackThreshold = tier.topTracksCount,
                                    recentMonthsIncluded = tier.recentMonths,
                                    totalScrobblesFound = totalScrobbles,
                                    status = LastFmImportMetadata.STATUS_DISCOVERING,
                                ),
                            )
                        }

                    // Phase 1: Build active set. Rebuilt on resume too - it is ~12 API calls
                    // (loved + up to 2000 top tracks) versus persisting the whole key set.
                    _progress.value = ImportProgress.Discovering("Building active set...")
                    val activeSetBuilt = buildActiveSet(username, tier)

                    if (!activeSetBuilt) {
                        val errorMessage = "Failed to build active set - network or rate limit error"
                        importMetadataDao.markFailed(
                            id = importId,
                            failedAt = System.currentTimeMillis(),
                            errorMessage = errorMessage,
                        )
                        _progress.value = ImportProgress.Failed(errorMessage, true)
                        return@withContext Result.failure(
                            LastFmApiError.NetworkError(errorMessage, null),
                        )
                    }

                    // Phase 2: Import scrobbles.
                    // Resume one page back from where the previous attempt died: its last partial
                    // batch was never flushed, so re-reading that page recovers those scrobbles
                    // (dedup handles the overlap).
                    importMetadataDao.updateStatus(importId, LastFmImportMetadata.STATUS_IN_PROGRESS)
                    val startPage = resumeFrom?.currentPage?.let { (it - 1).coerceAtLeast(1) } ?: 1
                    val result = importScrobbles(username, importId, totalScrobbles, tier, startTimeMs, startPage)

                    // Phase 3: Mark complete
                    if (result.success) {
                        importMetadataDao.markCompleted(
                            id = importId,
                            completedAt = System.currentTimeMillis(),
                            // The cursor is the instant the import *started*, not "now". Anything the
                            // user scrobbled while the import ran is then re-scanned by sync instead
                            // of falling into the gap between the import window and completion.
                            syncCursor = startTimeMs / 1000,
                        )

                        // Update user preferences
                        val prefs = userPreferencesDao.getSync() ?: UserPreferences()
                        userPreferencesDao.upsert(
                            prefs.copy(
                                lastfmUsername = username,
                                lastfmConnected = true,
                            ),
                        )

                        // Surface the result *before* the slow ANALYZE/checkpoint pass below:
                        // that takes tens of seconds on a large history and must not gate the
                        // completion UI. runPostImportOptimization emits no progress of its own.
                        _progress.value = ImportProgress.Completed(result)

                        // Phase 6: Post-import optimization
                        runPostImportOptimization()

                        // Phase 7: Schedule accelerated enrichment for imported tracks
                        // This runs in background with larger batches to quickly enrich
                        // imported tracks (album art, genres, etc.)
                        // NOTE: Only active tracks (tracksCreated) are enriched.
                        // Archived tracks use Last.fm album art and are never enriched.
                        if (result.tracksCreated > 0) {
                            Log.i(
                                TAG,
                                "Scheduling post-import enrichment for ${result.tracksCreated} active tracks (${result.scrobblesArchived} archived - not enriched)",
                            )
                            me.avinas.tempo.worker.EnrichmentWorker.schedulePostImportEnrichment(
                                context = context,
                                tracksCreated = result.tracksCreated,
                            )
                        }
                    } else {
                        importMetadataDao.markFailed(
                            id = importId,
                            failedAt = System.currentTimeMillis(),
                            errorMessage = result.errorMessage ?: "Unknown error",
                        )
                        _progress.value = ImportProgress.Completed(result)
                    }

                    Result.success(result)
                } catch (e: CancellationException) {
                    // Cancellation is not failure: leave the metadata row IN_PROGRESS so the next
                    // start resumes it (currentPage was written as pages completed), and put the UI
                    // back to idle instead of showing a bogus error.
                    Log.i(TAG, "Import cancelled")
                    _progress.value = ImportProgress.Idle
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed", e)
                    _progress.value = ImportProgress.Failed(e.message ?: "Import failed")
                    Result.failure(e)
                }
            }

        /**
         * Sync new scrobbles since the last import.
         * Uses the syncCursor from the previous import to only fetch new data.
         *
         * @return Number of new scrobbles imported, or -1 if sync failed
         */
        suspend fun syncNewScrobbles(): Result<Int> =
            withContext(Dispatchers.IO) {
                try {
                    // Get the latest completed import
                    val lastImport = importMetadataDao.getLatestCompleted()
                    if (lastImport == null) {
                        Log.w(TAG, "No completed import found, cannot sync")
                        return@withContext Result.failure(IllegalStateException("No completed import found"))
                    }

                    val username = lastImport.lastfmUsername
                    // Fall back to importCompletedAt (in seconds) so we catch any scrobbles
                    // that happened after the initial import finished. Never fall back to "now"
                    // because that would fetch zero results (asking for scrobbles in the future).
                    val syncCursor =
                        lastImport.lastSyncCursor
                            ?: lastImport.importCompletedAt?.let { it / 1000 }
                            ?: 0L

                    Log.i(TAG, "Syncing scrobbles for $username since ${java.util.Date(syncCursor * 1000)}")

                    _progress.value = ImportProgress.Processing("Syncing recent scrobbles...")

                    var newScrobbles = 0
                    var page = 1
                    var hasMore = true
                    var skippedPages = 0
                    // Oldest/newest scrobble timestamps we actually saw this run. The cursor advances
                    // only as far as we got - see the watermark update after the loop.
                    var minProcessedMs = Long.MAX_VALUE
                    var maxProcessedMs = Long.MIN_VALUE
                    var processedAny = false
                    // Local resolved-track cache: sync resolves one track per scrobble, and without
                    // this each resolution is 2-3 full scans of the tracks table.
                    val syncTrackCache = HashMap<String, Track>()

                    // Fetch recent scrobbles after the sync cursor
                    while (hasMore && page <= SYNC_MAX_PAGES) {
                        val apiResult =
                            executeWithRetry("syncNewScrobbles page $page") {
                                lastFmApi.getRecentTracks(
                                    user = username,
                                    apiKey = API_KEY,
                                    limit = PAGE_SIZE,
                                    page = page,
                                    from = syncCursor, // Only get scrobbles after this timestamp
                                )
                            }

                        val response =
                            apiResult.getOrElse { error ->
                                Log.e(TAG, "Failed to fetch sync page $page", error)
                                skippedPages++
                                break
                            }

                        val recentTracks = response.recenttracks
                        val scrobbles = recentTracks?.track ?: emptyList()

                        if (scrobbles.isEmpty()) {
                            hasMore = false
                            break
                        }

                        // Process each scrobble
                        val eventsToInsert = mutableListOf<ListeningEvent>()

                        for (scrobble in scrobbles) {
                            // Skip "now playing" tracks (no timestamp)
                            if (scrobble.isNowPlaying()) continue

                            val timestamp = scrobble.getTimestampMs() ?: continue
                            val artistName = scrobble.artist?.getArtistName() ?: continue
                            val trackTitle = scrobble.name ?: continue

                            processedAny = true
                            if (timestamp < minProcessedMs) minProcessedMs = timestamp
                            if (timestamp > maxProcessedMs) maxProcessedMs = timestamp

                            // Find or create track (cached per sync run)
                            val cacheKey = cacheKeyFor(trackTitle, artistName)
                            var track = syncTrackCache[cacheKey]
                            if (track == null) {
                                track =
                                    trackResolver
                                        .resolve(
                                            TrackResolver.Query(
                                                title = trackTitle,
                                                artist = artistName,
                                                album = scrobble.album?.name,
                                                albumArtUrl = scrobble.getBestImageUrl(),
                                                musicbrainzId = scrobble.mbid,
                                            ),
                                        ).track
                                if (syncTrackCache.size >= TRACK_CACHE_MAX_ENTRIES) syncTrackCache.clear()
                                syncTrackCache[cacheKey] = track
                            }
                            val trackId = track.id
                            // Real track length if the row has one, otherwise the app-wide assumed
                            // play length. Only the real one is claimed as the track's duration.
                            val knownDurationMs = track.duration?.takeIf { it > 0 }
                            val playDurationMs = knownDurationMs ?: DEFAULT_DURATION_MS

                            // Replay detection from the page already in memory, matching the
                            // import path. The old per-scrobble wasRecentlyPlayed() call was one
                            // query per scrobble (up to 10k per sync) for a badge that is only
                            // best-effort on imported data; a replay spanning a page boundary is
                            // now missed.
                            val isReplay =
                                eventsToInsert.any { event ->
                                    event.track_id == trackId &&
                                        kotlin.math.abs(event.timestamp - timestamp) <= REPLAY_THRESHOLD_MS &&
                                        event.timestamp != timestamp
                                }

                            eventsToInsert.add(
                                ListeningEvent(
                                    track_id = trackId,
                                    timestamp = timestamp,
                                    playDuration = playDurationMs,
                                    completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                                    source = IMPORT_SOURCE,
                                    wasSkipped = false,
                                    isReplay = isReplay,
                                    estimatedDurationMs = knownDurationMs,
                                    endTimestamp = knownDurationMs?.let { timestamp + it },
                                ),
                            )
                        }

                        // Batch insert with deduplication
                        if (eventsToInsert.isNotEmpty()) {
                            val insertResult = listeningEventDao.insertAllBatchedWithDedup(eventsToInsert)
                            newScrobbles += insertResult.inserted
                            Log.d(
                                TAG,
                                "Sync page $page: inserted ${insertResult.inserted}, skipped ${insertResult.skipped}, replaced ${insertResult.replaced}",
                            )
                        }

                        // Check if there are more pages
                        val attr = recentTracks?.attr
                        val totalPages = attr?.getTotalPages() ?: 1
                        hasMore = page < totalPages
                        page++

                        delay(RATE_LIMIT_MS)
                    }

                    // Advance the cursor only as far as this run actually got - never to "now".
                    // A failed or capped page would otherwise be skipped forever and those scrobbles
                    // silently lost. On a clean full walk we can jump to the newest scrobble seen;
                    // on an early stop we only advance to the oldest one, so the next sync re-reads
                    // the gap (Last.fm's `from` is inclusive and dedup handles the overlap).
                    if (page > SYNC_MAX_PAGES && hasMore) {
                        Log.w(TAG, "Sync stopped at the $SYNC_MAX_PAGES page cap with more history pending")
                    }
                    val now = System.currentTimeMillis()
                    if (processedAny) {
                        val fullyScanned = !hasMore && skippedPages == 0
                        val newCursor =
                            SyncWatermark.nextCursor(
                                currentCursor = syncCursor,
                                minProcessedMs = minProcessedMs,
                                maxProcessedMs = maxProcessedMs,
                                fullyScanned = fullyScanned,
                            )
                        if (newCursor > syncCursor) {
                            importMetadataDao.updateSyncCursor(id = lastImport.id, cursor = newCursor, timestamp = now)
                            Log.i(TAG, "Sync cursor advanced $syncCursor → $newCursor (fullyScanned=$fullyScanned)")
                        }
                    } else {
                        // Nothing to process: just record that we checked.
                        importMetadataDao.updateSyncCursor(id = lastImport.id, cursor = syncCursor, timestamp = now)
                    }

                    // Invalidate cache if we added new scrobbles
                    if (newScrobbles > 0) {
                        statsRepository.invalidateCache()
                    }

                    Log.i(TAG, "Sync complete: $newScrobbles new scrobbles imported (skippedPages=$skippedPages)")
                    _progress.value =
                        ImportProgress.Completed(
                            ImportResult(
                                success = true,
                                eventsImported = newScrobbles.toLong(),
                                tracksCreated = 0,
                                artistsCreated = 0,
                                scrobblesArchived = 0,
                                duplicatesSkipped = 0,
                                totalProcessed = newScrobbles.toLong(),
                                skippedPages = skippedPages,
                            ),
                        )

                    Result.success(newScrobbles)
                } catch (e: CancellationException) {
                    _progress.value = ImportProgress.Idle
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed", e)
                    _progress.value = ImportProgress.Failed(e.message ?: "Sync failed")
                    Result.failure(e)
                }
            }

        /**
         * Build the active set from top tracks and loved tracks.
         * Returns true if successful, false if a critical error occurred.
         *
         * Algorithm (per plan):
         * 1. Fetch ALL loved tracks → Always in active set
         * 2. Fetch top tracks (up to tier limit)
         * 3. Calculate cumulative coverage percentage
         * 4. Log actual coverage for analytics
         */
        private suspend fun buildActiveSet(
            username: String,
            tier: TierConfig,
        ): Boolean {
            activeSetKeys.clear()
            lovedTrackKeys.clear()
            knownDurations.clear()

            // Fetch loved tracks (always in active set)
            _progress.value = ImportProgress.Discovering("Fetching loved tracks...")
            var lovedPage = 1
            var lovedTracksProcessed = false

            do {
                val result =
                    executeWithRetry("getLovedTracks page $lovedPage") {
                        lastFmApi.getLovedTracks(
                            user = username,
                            apiKey = API_KEY,
                            limit = PAGE_SIZE,
                            page = lovedPage,
                        )
                    }

                val response =
                    result.getOrElse { error ->
                        Log.e(TAG, "Failed to fetch loved tracks page $lovedPage", error)
                        // Continue with what we have if we got some pages
                        if (lovedTracksProcessed) {
                            Log.w(TAG, "Continuing with ${lovedTrackKeys.size} loved tracks from ${lovedPage - 1} pages")
                            break
                        }
                        // First page failed - this is a critical error
                        if (error is LastFmApiError.NetworkError || error is LastFmApiError.RateLimited) {
                            return false
                        }
                        break
                    }

                // Check for API errors in response
                val apiError = checkApiError(response.error, response.message)
                if (apiError != null) {
                    Log.e(TAG, "API error fetching loved tracks: ${apiError.message}")
                    break
                }

                val tracks = response.lovedtracks?.track ?: break
                tracks.forEach { track ->
                    val key = track.getNormalizedKey()
                    lovedTrackKeys.add(key)
                    activeSetKeys.add(key)
                }
                lovedTracksProcessed = true

                val totalPages = response.lovedtracks?.attr?.getTotalPages() ?: 0
                lovedPage++
                delay(RATE_LIMIT_MS)
                // `totalPages` is remote input; the local cap keeps a bogus value from spinning
                // this loop forever (the active set is a Set, so repeated pages never grow it).
            } while (lovedPage <= totalPages && lovedPage <= MAX_IMPORT_PAGES)
            if (lovedPage > MAX_IMPORT_PAGES) {
                Log.w(TAG, "Loved tracks hit the $MAX_IMPORT_PAGES page cap")
            }

            Log.i(TAG, "Added ${lovedTrackKeys.size} loved tracks to active set")

            // For EVERYTHING tier, we don't need to fetch top tracks - everything goes to active.
            // For other tiers, fetch top tracks to build the active set.
            if (tier.topTracksCount >= Int.MAX_VALUE) {
                // EVERYTHING tier: all tracks will be processed as active during import
                Log.i(TAG, "EVERYTHING tier selected - all tracks will be active (no top tracks fetch needed)")
                return true
            }

            // Fetch top tracks (up to tier limit) with coverage calculation
            _progress.value = ImportProgress.Discovering("Fetching top tracks...")
            var topPage = 1
            var topTracksFromApi = 0 // Tracks received from API (may include duplicates with loved)
            var topTracksProcessed = false

            // Track play counts for coverage calculation
            var cumulativePlayCount = 0L

            do {
                // Calculate how many more unique tracks we need
                // activeSetKeys already contains loved tracks, so we need (tier limit - current unique top count)
                val currentUniqueTopTracks = activeSetKeys.size - lovedTrackKeys.size
                if (currentUniqueTopTracks >= tier.topTracksCount) {
                    Log.i(TAG, "Already have enough unique top tracks ($currentUniqueTopTracks >= ${tier.topTracksCount})")
                    break
                }

                val result =
                    executeWithRetry("getTopTracks page $topPage") {
                        lastFmApi.getTopTracks(
                            user = username,
                            apiKey = API_KEY,
                            limit = PAGE_SIZE, // Always fetch full page, filter locally
                            page = topPage,
                        )
                    }

                val response =
                    result.getOrElse { error ->
                        Log.e(TAG, "Failed to fetch top tracks page $topPage", error)
                        if (topTracksProcessed) {
                            Log.w(TAG, "Continuing with ${activeSetKeys.size} tracks from ${topPage - 1} pages")
                            break
                        }
                        if (error is LastFmApiError.NetworkError || error is LastFmApiError.RateLimited) {
                            return false
                        }
                        break
                    }

                // Check for API errors
                val apiError = checkApiError(response.error, response.message)
                if (apiError != null) {
                    Log.e(TAG, "API error fetching top tracks: ${apiError.message}")
                    break
                }

                val tracks = response.toptracks?.track ?: break

                val sizeBefore = activeSetKeys.size
                tracks.forEach { track ->
                    activeSetKeys.add(track.getNormalizedKey())
                    cumulativePlayCount += track.getPlayCountInt()
                    topTracksFromApi++
                    // Harvest real durations while we are here: scrobbles have no duration field,
                    // and this is the only cheap source of one during import.
                    val name = track.name
                    val artist = track.artist?.getArtistName()
                    val durationMs = track.getDurationMs()
                    if (!name.isNullOrBlank() && !artist.isNullOrBlank() && durationMs != null && durationMs > 0) {
                        knownDurations[cacheKeyFor(name, artist)] = durationMs
                    }
                }
                val newUniqueAdded = activeSetKeys.size - sizeBefore
                topTracksProcessed = true

                Log.d(TAG, "Page $topPage: received ${tracks.size} tracks, $newUniqueAdded new unique added")

                val totalPages = response.toptracks?.attr?.getTotalPages() ?: 0
                topPage++

                // Check if we've reached our target unique count
                val uniqueTopTracks = activeSetKeys.size - lovedTrackKeys.size
                if (uniqueTopTracks >= tier.topTracksCount) {
                    Log.i(TAG, "Reached target of ${tier.topTracksCount} unique top tracks")
                    break
                }

                delay(RATE_LIMIT_MS)
                // Same remote-input bound as the loved-tracks walk above. The tier target normally
                // ends this loop; the cap covers a response that never adds new unique tracks.
            } while (topPage <= totalPages && topPage <= MAX_IMPORT_PAGES)
            if (topPage > MAX_IMPORT_PAGES) {
                Log.w(TAG, "Top tracks hit the $MAX_IMPORT_PAGES page cap")
            }

            // Log coverage statistics
            val uniqueTopTracks = activeSetKeys.size - lovedTrackKeys.size
            Log.i(TAG, "Fetched $topTracksFromApi top tracks from API, $uniqueTopTracks unique (excluding loved)")
            Log.i(TAG, "Total active set: ${activeSetKeys.size} (${lovedTrackKeys.size} loved + $uniqueTopTracks top)")
            Log.i(TAG, "Top tracks represent $cumulativePlayCount total plays")

            // Calculate and log coverage milestones for future analytics
            if (uniqueTopTracks >= 500) {
                Log.i(TAG, "Coverage checkpoint: 500+ unique top tracks in active set")
            }
            if (uniqueTopTracks >= 1000) {
                Log.i(TAG, "Coverage checkpoint: 1000+ unique top tracks in active set")
            }
            if (uniqueTopTracks >= 2000) {
                Log.i(TAG, "Coverage checkpoint: 2000+ unique top tracks in active set")
            }

            return true
        }

        /**
         * Import scrobbles from Last.fm.
         */
        private suspend fun importScrobbles(
            username: String,
            importId: Long,
            totalScrobbles: Long,
            tier: TierConfig,
            startTimeMs: Long,
            startPage: Int = 1,
        ): ImportResult {
            var eventsImported = 0L
            var tracksCreated = 0L
            var artistsCreated = 0L
            var scrobblesArchived = 0L
            var duplicatesSkipped = 0L
            var scrobblesProcessed = 0L
            var skippedPages = 0
            var rateLimitRetries = 0

            // Duration of *this attempt*, which differs from startTimeMs when resuming.
            val attemptStartMs = System.currentTimeMillis()

            // Track archived scrobbles for batch compression
            val archiveBatch = mutableMapOf<String, ArchivePendingTrack>()

            // Batch active events for efficient inserts
            val activeEventsBatch = mutableListOf<ListeningEvent>()

            // Calculate recent cutoff timestamp for tiered imports.
            // Anchored to the import's start instant, not "now": a resumed import must classify
            // the same scrobbles as recent that the original attempt did.
            val recentCutoffMs =
                if (tier.recentMonths < Int.MAX_VALUE) {
                    startTimeMs - (tier.recentMonths.toLong() * 30 * 24 * 60 * 60 * 1000)
                } else {
                    0L
                }

            var page = startPage.coerceAtLeast(1)
            var hasMore = true
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5

            while (hasMore) {
                val result =
                    executeWithRetry("getRecentTracks page $page") {
                        lastFmApi.getRecentTracks(
                            user = username,
                            apiKey = API_KEY,
                            limit = PAGE_SIZE,
                            page = page,
                        )
                    }

                val response =
                    result.getOrElse { error ->
                        Log.e(TAG, "Failed to fetch page $page after retries", error)
                        consecutiveErrors++

                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            Log.e(TAG, "Too many consecutive errors ($consecutiveErrors), aborting")
                            return ImportResult(
                                success = false,
                                eventsImported = eventsImported,
                                tracksCreated = tracksCreated,
                                artistsCreated = artistsCreated,
                                scrobblesArchived = scrobblesArchived,
                                duplicatesSkipped = duplicatesSkipped,
                                totalProcessed = scrobblesProcessed,
                                skippedPages = skippedPages,
                                errorMessage = "Import stopped after $consecutiveErrors consecutive errors: ${error.message}",
                            )
                        }

                        // Skip this page and continue with next
                        skippedPages++
                        page++
                        delay(RATE_LIMIT_BACKOFF_MS)
                        continue
                    }

                // Reset consecutive errors on successful fetch
                consecutiveErrors = 0

                // Check for API-level errors
                val apiError = checkApiError(response.error, response.message)
                if (apiError != null) {
                    Log.e(TAG, "API error on page $page: ${apiError.message}")

                    if (apiError is LastFmApiError.RateLimited) {
                        // Bounded: Last.fm can keep answering 200 OK + "rate limit exceeded"
                        // indefinitely, which used to spin this loop forever.
                        rateLimitRetries++
                        if (rateLimitRetries > MAX_RATE_LIMIT_RETRIES) {
                            Log.e(TAG, "Page $page still rate limited after $rateLimitRetries attempts, skipping")
                            rateLimitRetries = 0
                            skippedPages++
                            page++
                            continue
                        }
                        _progress.value = ImportProgress.RateLimited(RATE_LIMIT_BACKOFF_MS)
                        delay(RATE_LIMIT_BACKOFF_MS)
                        // Retry same page
                        continue
                    }

                    // Other API errors - skip page and continue
                    rateLimitRetries = 0
                    skippedPages++
                    page++
                    continue
                }

                rateLimitRetries = 0

                val recentTracks = response.recenttracks
                val scrobbles = recentTracks?.track ?: emptyList()
                val totalPages = recentTracks?.attr?.getTotalPages() ?: 0

                for (scrobble in scrobbles) {
                    // Skip currently playing track
                    if (scrobble.isNowPlaying()) continue

                    val timestampMs = scrobble.getTimestampMs() ?: continue
                    val normalizedKey = scrobble.getNormalizedKey()

                    scrobblesProcessed++

                    // Determine if this goes to active set or archive
                    val isInActiveSet = activeSetKeys.contains(normalizedKey)
                    val isRecent = timestampMs >= recentCutoffMs
                    val shouldBeActive = isInActiveSet || isRecent || tier.name == "EVERYTHING"

                    if (shouldBeActive) {
                        // Process as active event with batch support
                        val result = prepareScrobbleForActive(scrobble, timestampMs, activeEventsBatch)
                        when (result) {
                            is ScrobblePrepareResult.Ready -> {
                                activeEventsBatch.add(result.event)
                                // Don't increment eventsImported here - wait for actual insert
                                if (result.newTrack) tracksCreated++
                                if (result.newArtist) artistsCreated++
                            }

                            is ScrobblePrepareResult.Duplicate -> {
                                duplicatesSkipped++
                            }

                            is ScrobblePrepareResult.Error -> {
                                Log.w(TAG, "Failed to process: ${result.message}")
                            }
                        }

                        // Batch insert when we hit the threshold
                        if (activeEventsBatch.size >= EVENT_BATCH_SIZE) {
                            val batchResult = listeningEventDao.insertAllBatchedWithDedup(activeEventsBatch)
                            // Count actual insertions (not duplicates skipped at batch level)
                            eventsImported += batchResult.inserted
                            duplicatesSkipped += batchResult.skipped
                            if (batchResult.replaced > 0) {
                                Log.d(
                                    TAG,
                                    "Batch: inserted ${batchResult.inserted}, skipped ${batchResult.skipped}, replaced ${batchResult.replaced}",
                                )
                            }
                            activeEventsBatch.clear()
                        }
                    } else {
                        // Archived tracks store Last.fm album art without creating Track/EnrichedMetadata entities.
                        val pending =
                            archiveBatch.getOrPut(normalizedKey) {
                                ArchivePendingTrack(
                                    trackTitle = scrobble.name ?: "",
                                    artistName = scrobble.artist?.getArtistName() ?: "",
                                    albumName = scrobble.album?.name,
                                    musicbrainzId = scrobble.mbid,
                                    albumArtUrl = scrobble.getBestImageUrl(), // Last.fm art - NOT enriched later
                                    wasLoved = lovedTrackKeys.contains(normalizedKey),
                                    timestamps = mutableListOf(),
                                )
                            }
                        pending.timestamps.add(timestampMs)
                        scrobblesArchived++
                    }

                    // Update progress periodically
                    if (scrobblesProcessed % 100 == 0L) {
                        _progress.value =
                            ImportProgress.Importing(
                                phase = "Importing scrobbles",
                                current = scrobblesProcessed,
                                total = totalScrobbles,
                                eventsCreated = eventsImported,
                                tracksCreated = tracksCreated,
                                archived = scrobblesArchived,
                                tierName = tier.name,
                            )

                        importMetadataDao.updateProgress(importId, page, scrobblesProcessed)
                    }
                }

                // Flush *everything* pending at the end of every page, not on a size threshold.
                // The persisted resume page is only `currentPage - 1`, so whatever is still
                // buffered here when the worker is cancelled (or the process is killed) is never
                // re-read and is lost for good. A size threshold let the archive batch span
                // hundreds of pages, so a cancel could drop tens of thousands of scrobbles.
                // One transaction per page is ~200 rows and the loop is network-bound anyway.
                if (activeEventsBatch.isNotEmpty()) {
                    val batchResult = listeningEventDao.insertAllBatchedWithDedup(activeEventsBatch)
                    eventsImported += batchResult.inserted
                    duplicatesSkipped += batchResult.skipped
                    activeEventsBatch.clear()
                }
                if (archiveBatch.isNotEmpty()) {
                    saveArchiveBatch(archiveBatch, importId)
                    archiveBatch.clear()
                }
                // Tracks only reach the enrichment sweep through their enriched_metadata row
                // (every query there selects FROM enriched_metadata), so a dropped batch means
                // those tracks are never enriched rather than merely enriched late.
                flushPendingMetadata()

                // `totalPages` is remote input. Bound the walk locally as well, so a bogus value
                // cannot keep this loop alive (and the DB growing) indefinitely.
                val morePagesRemain = page < totalPages
                val cappedOut = morePagesRemain && page >= MAX_IMPORT_PAGES
                if (cappedOut) {
                    Log.e(TAG, "Import hit the $MAX_IMPORT_PAGES page cap with ${totalPages - page} page(s) pending")
                    skippedPages++
                }
                hasMore = morePagesRemain && !cappedOut
                page++
                delay(RATE_LIMIT_MS)
            }

            // Flush remaining active events batch
            if (activeEventsBatch.isNotEmpty()) {
                val batchResult = listeningEventDao.insertAllBatchedWithDedup(activeEventsBatch)
                // Count actual insertions for final batch
                eventsImported += batchResult.inserted
                duplicatesSkipped += batchResult.skipped
                activeEventsBatch.clear()
            }

            // Save remaining archive batch
            if (archiveBatch.isNotEmpty()) {
                saveArchiveBatch(archiveBatch, importId)
            }

            // Flush any remaining pending metadata
            flushPendingMetadata()

            // Drop every per-run working cache, not just trackCache. knownDurations is keyed by
            // every distinct track seen, so on a large history it held tens of thousands of
            // entries resident in this singleton until the *next* import or a cancel.
            clearWorkingCaches()
            Log.d(TAG, "Import complete - working caches cleared")

            // Calculate duration
            val durationSeconds = (System.currentTimeMillis() - attemptStartMs) / 1000

            // Update final results
            importMetadataDao.updateResults(
                id = importId,
                eventsImported = eventsImported,
                tracksCreated = tracksCreated,
                artistsCreated = artistsCreated,
                scrobblesArchived = scrobblesArchived,
                duplicatesSkipped = duplicatesSkipped,
            )

            if (skippedPages > 0) {
                Log.w(TAG, "Import finished with $skippedPages skipped page(s) - history has holes")
            }
            return ImportResult(
                success = true,
                eventsImported = eventsImported,
                tracksCreated = tracksCreated,
                artistsCreated = artistsCreated,
                scrobblesArchived = scrobblesArchived,
                duplicatesSkipped = duplicatesSkipped,
                totalProcessed = scrobblesProcessed,
                durationSeconds = durationSeconds,
                skippedPages = skippedPages,
            )
        }

        /**
         * Prepare a scrobble for batch insertion (doesn't insert yet).
         * Returns the event ready for batch or error status.
         */
        private sealed class ScrobblePrepareResult {
            data class Ready(
                val event: ListeningEvent,
                val newTrack: Boolean,
                val newArtist: Boolean,
            ) : ScrobblePrepareResult()

            object Duplicate : ScrobblePrepareResult()

            data class Error(
                val message: String,
            ) : ScrobblePrepareResult()
        }

        private suspend fun prepareScrobbleForActive(
            scrobble: LastFmScrobble,
            timestampMs: Long,
            pendingEvents: List<ListeningEvent>,
        ): ScrobblePrepareResult {
            val trackTitle = scrobble.name ?: return ScrobblePrepareResult.Error("No track name")
            val artistName =
                scrobble.artist?.getArtistName()
                    ?: return ScrobblePrepareResult.Error("No artist name")

            // PERFORMANCE OPTIMIZATION: Use in-memory cache
            val cacheKey = cacheKeyFor(trackTitle, artistName)
            var track = trackCache[cacheKey]
            var isNewTrack = false
            var isNewArtist = false

            if (track == null) {
                val resolution =
                    trackResolver.resolve(
                        TrackResolver.Query(
                            title = trackTitle,
                            artist = artistName,
                            album = scrobble.album?.name,
                            albumArtUrl = scrobble.getBestImageUrl(),
                            musicbrainzId = scrobble.mbid,
                            // Persist the duration harvested from user.getTopTracks onto the track
                            // row. Without this it only lives in the in-memory map for this run and
                            // every later sync falls back to DEFAULT_DURATION_MS.
                            duration = knownDurations[cacheKey],
                        ),
                    )
                track = resolution.track
                cacheTrack(cacheKey, resolution.track)
                isNewTrack = resolution.isNewTrack
                if (isNewTrack) {
                    queueEnrichedMetadata(track, scrobble)
                    isNewArtist = true
                }
            }

            val finalTrack = track ?: return ScrobblePrepareResult.Error("Failed to create or find track")

            // OPTIMIZATION: Skip per-scrobble duplicate check during import.
            // The batch insert (insertAllBatchedWithDedup) handles deduplication efficiently
            // by fetching all timestamps for the batch in one query instead of N queries.

            // Calculate isReplay: Only check in-memory batch for replay detection during import.
            // This avoids N database queries per scrobble. The replay flag is not critical
            // for imported data - it's primarily for real-time listening feedback.
            val hasNearbyInBatch =
                pendingEvents.any { event ->
                    event.track_id == finalTrack.id &&
                        kotlin.math.abs(event.timestamp - timestampMs) <= REPLAY_THRESHOLD_MS &&
                        event.timestamp != timestampMs // Not the same event
                }

            // Mark as replay only if there's a nearby play in the current batch
            // (DB check skipped for performance - imported data replay detection is best-effort)
            val isReplay = hasNearbyInBatch

            // Create listening event.
            // Duration: prefer the real value from user.getTopTracks, then whatever the resolved
            // track already carries (e.g. learned from live playback). Last.fm scrobbles have no
            // duration of their own.
            //
            // When neither source has it, playDuration falls back to the app-wide 3.5-minute
            // assumption (Last.fm only scrobbles a play of at least 4 minutes or half the track, so
            // this is a conservative estimate rather than a measurement). estimatedDurationMs and
            // endTimestamp stay NULL in that case so nothing downstream mistakes the assumed length
            // for the track's real length.
            val knownDurationMs = knownDurations[cacheKey] ?: finalTrack.duration?.takeIf { it > 0 }
            val playDurationMs = knownDurationMs ?: DEFAULT_DURATION_MS
            val event =
                ListeningEvent(
                    track_id = finalTrack.id,
                    timestamp = timestampMs,
                    playDuration = playDurationMs,
                    completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                    source = IMPORT_SOURCE,
                    wasSkipped = false,
                    isReplay = isReplay,
                    estimatedDurationMs = knownDurationMs,
                    pauseCount = 0,
                    sessionId = null,
                    endTimestamp = knownDurationMs?.let { timestampMs + it },
                )

            return ScrobblePrepareResult.Ready(event, isNewTrack, isNewArtist)
        }

        /**
         * Queue enriched metadata for batch insert (performance optimization).
         */
        private suspend fun queueEnrichedMetadata(
            track: Track,
            scrobble: LastFmScrobble,
        ) {
            val albumArtUrl = scrobble.getBestImageUrl()
            val metadata =
                EnrichedMetadata(
                    trackId = track.id,
                    musicbrainzRecordingId = scrobble.mbid,
                    musicbrainzArtistId = scrobble.artist?.mbid,
                    albumTitle = scrobble.album?.name,
                    musicbrainzReleaseId = scrobble.album?.mbid,
                    albumArtUrl = albumArtUrl,
                    albumArtSource = if (albumArtUrl != null) AlbumArtSource.DEEZER else AlbumArtSource.NONE,
                    artistName = scrobble.artist?.getArtistName(),
                    enrichmentStatus = EnrichmentStatus.PENDING,
                    cacheTimestamp = System.currentTimeMillis(),
                )
            // Size check must happen under the same lock as the add: reading it outside was the
            // remaining unsynchronized access to this list.
            val batchFull =
                synchronized(pendingMetadata) {
                    pendingMetadata.add(metadata)
                    pendingMetadata.size >= METADATA_BATCH_SIZE
                }
            if (batchFull) flushPendingMetadata()
        }

        /**
         * Flush pending metadata to database in batch.
         */
        private suspend fun flushPendingMetadata() {
            // Swap the batch out under the lock so no DAO call is made while holding it.
            val batch =
                synchronized(pendingMetadata) {
                    if (pendingMetadata.isEmpty()) return
                    val copy = pendingMetadata.toList()
                    pendingMetadata.clear()
                    copy
                }
            try {
                enrichedMetadataDao.upsertAllFromAutomaticEnrichment(batch)
                Log.d(TAG, "Flushed ${batch.size} pending metadata records")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush ${batch.size} metadata records", e)
            }
        }

        /**
         * Compress and save archived scrobbles with proper merge handling.
         * Uses upsertWithMerge to correctly handle tracks that span multiple batches.
         */
        private suspend fun saveArchiveBatch(
            batch: Map<String, ArchivePendingTrack>,
            importId: Long,
        ) {
            val archives = ArrayList<ScrobbleArchive>(batch.size)
            for ((_, pending) in batch) {
                val timestamps = pending.timestamps.sorted()
                if (timestamps.isEmpty()) continue

                archives.add(
                    ScrobbleArchive(
                        trackHash = ScrobbleArchive.generateTrackHash(pending.artistName, pending.trackTitle),
                        trackTitle = pending.trackTitle,
                        artistName = pending.artistName,
                        artistNameNormalized = pending.artistName.lowercase().trim(),
                        albumName = pending.albumName,
                        musicbrainzId = pending.musicbrainzId,
                        timestampsBlob = ArchiveTimestampCodec.compress(timestamps),
                        playCount = timestamps.size,
                        firstScrobble = timestamps.first(),
                        lastScrobble = timestamps.last(),
                        albumArtUrl = pending.albumArtUrl,
                        wasLoved = pending.wasLoved,
                        importId = importId,
                    ),
                )
            }
            if (archives.isEmpty()) return

            // One transaction for the whole batch. Merging per row means one implicit transaction
            // (and one WAL fsync) per archive - ~105 of them on a 300K-scrobble history.
            scrobbleArchiveDao.upsertAllWithMerge(
                archives = archives,
                decompressTimestamps = ArchiveTimestampCodec::decompress,
                compressTimestamps = ArchiveTimestampCodec::compress,
            )
        }

        /**
         * Pending track for archive batch.
         */
        private data class ArchivePendingTrack(
            val trackTitle: String,
            val artistName: String,
            val albumName: String?,
            val musicbrainzId: String?,
            val albumArtUrl: String?,
            val wasLoved: Boolean,
            val timestamps: MutableList<Long>,
        )

        /**
         * Cancel current import.
         */
        fun cancelImport() {
            _progress.value = ImportProgress.Idle
            clearWorkingCaches()
        }

        /**
         * Drop a terminal progress state (Completed/Failed) so the UI stops showing the previous
         * import's result once the user dismisses it. In-flight states are left alone.
         */
        fun resetProgress() {
            when (_progress.value) {
                is ImportProgress.Completed, is ImportProgress.Failed, ImportProgress.Idle -> {
                    _progress.value = ImportProgress.Idle
                }

                else -> {
                    Unit
                }
            }
        }

        private fun clearWorkingCaches() {
            activeSetKeys.clear()
            lovedTrackKeys.clear()
            trackCache.clear()
            knownDurations.clear()
            synchronized(pendingMetadata) { pendingMetadata.clear() }
        }

        /** Cache key for resolved tracks and known durations: title|artist, case/space-insensitive. */
        private fun cacheKeyFor(
            title: String,
            artist: String,
        ): String = "${title.lowercase().trim()}|${artist.lowercase().trim()}"

        /**
         * Store a resolved track, evicting the whole cache when it grows past the ceiling.
         * Re-resolving is cheap next to unbounded growth on a large history.
         */
        private fun cacheTrack(
            key: String,
            track: Track,
        ) {
            if (trackCache.size >= TRACK_CACHE_MAX_ENTRIES) trackCache.clear()
            trackCache[key] = track
        }

        /**
         * Check if an import is currently in progress.
         */
        suspend fun hasActiveImport(): Boolean = importMetadataDao.getActiveImport() != null

        /**
         * Get the most recent completed import for a user.
         */
        suspend fun getLastImport(username: String): LastFmImportMetadata? = importMetadataDao.getLatestCompletedForUsername(username)

        // Archive Query Methods

        /**
         * Search result that can come from either active tracks or archive.
         */
        data class UnifiedSearchResult(
            val title: String,
            val artist: String,
            val album: String?,
            val playCount: Int,
            val isFromArchive: Boolean,
            val archiveId: Long? = null,
            val trackId: Long? = null,
            val albumArtUrl: String? = null,
            val firstPlayed: Long? = null,
            val lastPlayed: Long? = null,
        )

        /**
         * Search both active tracks and archive, returning unified results.
         * Archive results are marked with isFromArchive = true.
         *
         * NOTE: This method has an N+1 query pattern for active tracks (3 queries per track).
         * For better performance with large result sets, consider adding a batch query to
         * ListeningEventDao that returns track stats in bulk.
         */
        suspend fun searchUnified(
            query: String,
            limit: Int = 50,
        ): List<UnifiedSearchResult> {
            val results = mutableListOf<UnifiedSearchResult>()

            // Search active tracks first
            val activeTracks = trackRepository.searchTracks(query)
            for (track in activeTracks.take(limit)) {
                // Get play count from listening events
                val playCount = listeningEventDao.countByTrackId(track.id)
                val firstPlayed = listeningEventDao.getFirstPlayTimestampForTrack(track.id)
                val lastPlayed = listeningEventDao.getLastPlayTimestampForTrack(track.id)

                results.add(
                    UnifiedSearchResult(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        playCount = playCount,
                        isFromArchive = false,
                        trackId = track.id,
                        albumArtUrl = track.albumArtUrl,
                        firstPlayed = firstPlayed,
                        lastPlayed = lastPlayed,
                    ),
                )
            }

            // Search archive for additional results
            val archiveResults = scrobbleArchiveDao.search(query, limit)
            for (archive in archiveResults) {
                // Skip if we already have this track from active
                val alreadyHave =
                    results.any {
                        it.title.equals(archive.trackTitle, ignoreCase = true) &&
                            it.artist.equals(archive.artistName, ignoreCase = true)
                    }

                if (!alreadyHave) {
                    results.add(
                        UnifiedSearchResult(
                            title = archive.trackTitle,
                            artist = archive.artistName,
                            album = archive.albumName,
                            playCount = archive.playCount,
                            isFromArchive = true,
                            archiveId = archive.id,
                            albumArtUrl = archive.albumArtUrl,
                            firstPlayed = archive.firstScrobble,
                            lastPlayed = archive.lastScrobble,
                        ),
                    )
                }
            }

            // Sort by play count descending
            return results.sortedByDescending { it.playCount }.take(limit)
        }

        /**
         * Promote an archived track to active set.
         * Creates full ListeningEvent records for all archived timestamps.
         */
        suspend fun promoteFromArchive(archiveId: Long): Result<PromotionResult> =
            withContext(Dispatchers.IO) {
                try {
                    val archive =
                        scrobbleArchiveDao.getById(archiveId)
                            ?: return@withContext Result.failure(Exception("Archive entry not found"))

                    // Find or create the track
                    val resolution =
                        trackResolver.resolve(
                            TrackResolver.Query(
                                title = archive.trackTitle,
                                artist = archive.artistName,
                                album = archive.albumName,
                                albumArtUrl = archive.albumArtUrl,
                                musicbrainzId = archive.musicbrainzId,
                            ),
                        )
                    val track = resolution.track
                    val isNewTrack = resolution.isNewTrack

                    if (isNewTrack) {
                        // Link artists
                        try {
                            artistLinkingService.linkArtistsForTrack(track)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to link artists during promotion", e)
                        }
                    }

                    // Decompress timestamps
                    val timestamps = ArchiveTimestampCodec.decompress(archive.timestampsBlob).sorted()

                    // Create listening events for each timestamp, calculating isReplay
                    val events = mutableListOf<ListeningEvent>()
                    var previousTimestamp: Long? = null

                    for (timestampMs in timestamps) {
                        // Check if this is a replay (same track within REPLAY_THRESHOLD_MS of previous)
                        val isReplay =
                            previousTimestamp != null &&
                                (timestampMs - previousTimestamp) <= REPLAY_THRESHOLD_MS

                        val knownDurationMs = track.duration?.takeIf { it > 0 }
                        val playDurationMs = knownDurationMs ?: DEFAULT_DURATION_MS
                        events.add(
                            ListeningEvent(
                                track_id = track.id,
                                timestamp = timestampMs,
                                playDuration = playDurationMs,
                                completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                                source = "$IMPORT_SOURCE.promoted",
                                wasSkipped = false,
                                isReplay = isReplay,
                                estimatedDurationMs = knownDurationMs,
                                pauseCount = 0,
                                sessionId = null,
                                endTimestamp = knownDurationMs?.let { timestampMs + it },
                            ),
                        )

                        previousTimestamp = timestampMs
                    }

                    // Batch insert with dedup
                    val insertResult = listeningEventDao.insertAllBatchedWithDedup(events)

                    // Delete from archive
                    scrobbleArchiveDao.deleteById(archiveId)

                    // Invalidate stats cache since play counts changed
                    statsRepository.invalidateCache()

                    Log.i(
                        TAG,
                        "Promoted ${archive.trackTitle} - ${archive.artistName}: " +
                            "${insertResult.inserted} events created, ${insertResult.skipped} duplicates skipped, ${insertResult.replaced} replaced",
                    )

                    Result.success(
                        PromotionResult(
                            trackId = track.id,
                            eventsCreated = insertResult.inserted,
                            duplicatesSkipped = insertResult.skipped,
                            isNewTrack = isNewTrack,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to promote from archive", e)
                    Result.failure(e)
                }
            }

        /**
         * Result of promoting an archived track.
         */
        data class PromotionResult(
            val trackId: Long,
            val eventsCreated: Int,
            val duplicatesSkipped: Int,
            val isNewTrack: Boolean,
        )

        /**
         * Get archive statistics for UI display.
         */
        suspend fun getArchiveStats(): ArchiveStats =
            ArchiveStats(
                totalTracks = scrobbleArchiveDao.getTotalCount(),
                totalPlays = scrobbleArchiveDao.getTotalPlayCount(),
                storageBytes = scrobbleArchiveDao.getStorageSizeBytes(),
                uniqueArtists = scrobbleArchiveDao.getUniqueArtistCount(),
            )

        /**
         * Archive statistics for display.
         */
        data class ArchiveStats(
            val totalTracks: Int,
            val totalPlays: Long,
            val storageBytes: Long,
            val uniqueArtists: Int,
        )

        // History Timeline Integration

        /**
         * Represents a listening event from the archive for history display.
         */
        data class ArchiveHistoryItem(
            val archiveId: Long,
            val trackTitle: String,
            val artistName: String,
            val albumName: String?,
            val timestamp: Long,
            val albumArtUrl: String?,
            val isFromArchive: Boolean = true,
        )

        /**
         * Get archived history items for a date range.
         * Used when displaying "All History" including archived scrobbles.
         *
         * @param startTime Start of date range (epoch millis)
         * @param endTime End of date range (epoch millis)
         * @param searchQuery Optional search term to filter results
         * @param limit Maximum number of results
         * @return List of archive history items sorted by timestamp descending
         */
        suspend fun getArchiveHistoryInRange(
            startTime: Long,
            endTime: Long,
            searchQuery: String? = null,
            limit: Int = 100,
        ): List<ArchiveHistoryItem> {
            // Get archives that overlap with the date range
            // Use a higher limit for archives since each archive can have multiple timestamps
            // and we'll filter/limit after expanding
            val archiveLimit = limit * 5 // Fetch more archives to account for filtering
            val archives =
                if (searchQuery.isNullOrBlank()) {
                    scrobbleArchiveDao.getInDateRange(startTime, endTime, archiveLimit)
                } else {
                    // Search within date range
                    scrobbleArchiveDao.searchByTitle(searchQuery, limit).filter { archive ->
                        // Check if any timestamps fall within range
                        archive.firstScrobble <= endTime && archive.lastScrobble >= startTime
                    }
                }

            // Expand compressed timestamps and keep only the newest `limit` plays.
            return newestArchivePlays(archives, startTime, endTime, limit)
        }

        /**
         * Collect the newest [limit] archived plays in [startTime]..[endTime].
         *
         * Uses a bounded min-heap instead of expanding every timestamp of every candidate
         * archive into a list: a `limit*5` archive window can carry hundreds of thousands of
         * timestamps, all of which used to be materialised just to discard all but `limit`.
         */
        private fun newestArchivePlays(
            archives: List<ScrobbleArchive>,
            startTime: Long,
            endTime: Long,
            limit: Int,
        ): List<ArchiveHistoryItem> {
            if (limit <= 0) return emptyList()
            val newest = java.util.PriorityQueue<ArchiveHistoryItem>(limit + 1, compareBy { it.timestamp })
            for (archive in archives) {
                for (ts in ArchiveTimestampCodec.decompress(archive.timestampsBlob)) {
                    if (ts < startTime || ts > endTime) continue
                    val oldestKept = newest.peek()
                    if (oldestKept != null && newest.size >= limit && ts <= oldestKept.timestamp) continue
                    newest.add(
                        ArchiveHistoryItem(
                            archiveId = archive.id,
                            trackTitle = archive.trackTitle,
                            artistName = archive.artistName,
                            albumName = archive.albumName,
                            timestamp = ts,
                            albumArtUrl = archive.albumArtUrl,
                        ),
                    )
                    if (newest.size > limit) newest.poll()
                }
            }
            return newest.sortedByDescending { it.timestamp }
        }

        /**
         * Get total archive play count in a date range.
         * Used for stats calculations that need to include archived data.
         *
         * WARNING: This method can be slow for large archives (O(n) where n = total archived scrobbles)
         * as it decompresses each archive's timestamps and filters them.
         *
         * For performance-critical paths, consider:
         * - Using getArchiveStats() for approximate counts
         * - Caching results for repeated queries
         * - Adding a denormalized monthly count to ScrobbleArchive
         */
        suspend fun getArchivePlayCountInRange(
            startTime: Long,
            endTime: Long,
        ): Int {
            // Use a very high limit to get all archives in range for accurate count
            // This is a stats method, so accuracy is more important than speed
            val archives = scrobbleArchiveDao.getInDateRange(startTime, endTime, limit = 10000)

            var totalCount = 0
            for (archive in archives) {
                val timestamps = ArchiveTimestampCodec.decompress(archive.timestampsBlob)
                totalCount += timestamps.count { it in startTime..endTime }
            }

            return totalCount
        }

        /**
         * Get archived history items for a specific artist.
         * Used when displaying artist details with complete history.
         */
        suspend fun getArchiveHistoryForArtist(
            artistName: String,
            startTime: Long? = null,
            endTime: Long? = null,
            limit: Int = 100,
        ): List<ArchiveHistoryItem> {
            val archives = scrobbleArchiveDao.searchByArtist(artistName, limit * 2)

            return newestArchivePlays(
                archives = archives,
                startTime = startTime ?: Long.MIN_VALUE,
                endTime = endTime ?: Long.MAX_VALUE,
                limit = limit,
            )
        }

        /**
         * Check if archive data exists for better UI hints.
         */
        suspend fun hasArchiveData(): Boolean = scrobbleArchiveDao.getTotalCount() > 0

        /**
         * Run database optimization after import completes.
         *
         * Per plan Phase 6:
         * 1. Run ANALYZE on listening_events to update query planner statistics
         * 2. Run incremental VACUUM if free space > 10%
         * 3. Checkpoint WAL
         * 4. Invalidate stats cache
         */
        private suspend fun runPostImportOptimization() {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Starting post-import optimization...")

                    // 1. Run ANALYZE on key tables to update query planner statistics
                    // This helps SQLite make better query plan decisions after bulk insert
                    appDatabase.openHelper.writableDatabase.let { db ->
                        Log.d(TAG, "Running ANALYZE on listening_events...")
                        db.execSQL("ANALYZE listening_events")

                        Log.d(TAG, "Running ANALYZE on scrobbles_archive...")
                        db.execSQL("ANALYZE scrobbles_archive")

                        Log.d(TAG, "Running ANALYZE on tracks...")
                        db.execSQL("ANALYZE tracks")

                        // 2. Check free space and run incremental VACUUM if needed
                        // Note: Full VACUUM can be slow and doubles storage temporarily
                        // So we use incremental vacuum which is more efficient
                        val freeListCount =
                            db.query("PRAGMA freelist_count").use { cursor ->
                                if (cursor.moveToFirst()) cursor.getInt(0) else 0
                            }
                        val pageCount =
                            db.query("PRAGMA page_count").use { cursor ->
                                if (cursor.moveToFirst()) cursor.getInt(0) else 1
                            }

                        val freeSpaceRatio = freeListCount.toFloat() / pageCount
                        Log.d(TAG, "Database free space: ${(freeSpaceRatio * 100).toInt()}% (freelist=$freeListCount, pages=$pageCount)")

                        // PRAGMA incremental_vacuum only reclaims anything when the database was
                        // created with auto_vacuum=INCREMENTAL. Nothing in DatabaseModule sets that
                        // (Room 2.8 has no builder hook for it), so this is normally a silent no-op -
                        // check first instead of logging a fake "freed N pages".
                        val autoVacuum =
                            db.query("PRAGMA auto_vacuum").use { cursor ->
                                if (cursor.moveToFirst()) cursor.getInt(0) else 0
                            }
                        if (autoVacuum != AUTO_VACUUM_INCREMENTAL) {
                            Log.d(TAG, "Skipping incremental VACUUM: auto_vacuum=$autoVacuum (not INCREMENTAL)")
                        } else if (freeSpaceRatio > 0.10) {
                            Log.d(TAG, "Running incremental VACUUM (free space > 10%)...")
                            // Vacuum up to 1000 pages at a time to avoid blocking
                            // Note: PRAGMA incremental_vacuum returns a result set
                            db.query("PRAGMA incremental_vacuum(1000)").use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val pagesFreed = cursor.getInt(0)
                                    Log.d(TAG, "Incremental VACUUM freed $pagesFreed pages")
                                }
                            }
                        }

                        // 3. Checkpoint WAL to merge changes into main database
                        // Note: PRAGMA wal_checkpoint returns a result set, so we must use query()
                        // instead of execSQL() to avoid "Queries can be performed using SQLiteDatabase
                        // query or rawQuery methods only" error on some Android versions.
                        Log.d(TAG, "Checkpointing WAL...")
                        db.query("PRAGMA wal_checkpoint(PASSIVE)").use { cursor ->
                            if (cursor.moveToFirst()) {
                                val busy = cursor.getInt(0)
                                val log = cursor.getInt(1)
                                val checkpointed = cursor.getInt(2)
                                Log.d(TAG, "WAL checkpoint result: busy=$busy, log=$log, checkpointed=$checkpointed")
                            }
                        }
                    }

                    // 4. Invalidate stats cache
                    Log.d(TAG, "Invalidating stats cache...")
                    statsRepository.invalidateCache()

                    Log.i(TAG, "Post-import optimization complete")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log but don't fail - optimization is best-effort
                    Log.e(TAG, "Post-import optimization failed (non-critical)", e)
                }
            }
        }

        /**
         * Remove archived tracks that have been promoted to active set.
         * Called periodically to clean up redundant archive data.
         *
         * Uses batched processing to avoid loading all archives into memory.
         */
        suspend fun prunePromotedArchives(): Int =
            withContext(Dispatchers.IO) {
                var pruned = 0

                try {
                    // Process archives in batches to avoid OOM for large archives
                    // Get candidates that might have been promoted (high play count suggests activity)
                    val candidates = scrobbleArchiveDao.getCandidatesForPromotion(minPlayCount = 1, limit = 500)

                    for (archive in candidates) {
                        // Check if this track now exists in active set
                        val existingTrack =
                            trackRepository.findByTitleAndArtist(
                                title = archive.trackTitle,
                                artist = archive.artistName,
                            )

                        if (existingTrack != null) {
                            // Check if it has significant play history in active set
                            val activePlayCount = listeningEventDao.countByTrackId(existingTrack.id)

                            // Archive is redundant if active plays cover at least 50% of archived plays.
                            // Use (archiveCount + 1) / 2 to properly handle integer division:
                            // - archive=1: threshold=1 (need 1 active)
                            // - archive=2: threshold=1 (need 1 active)
                            // - archive=3: threshold=2 (need 2 active)
                            // - archive=4: threshold=2 (need 2 active)
                            // Only prune archive rows once active plays meet coverage threshold.
                            val threshold = (archive.playCount + 1) / 2
                            if (activePlayCount >= threshold) {
                                scrobbleArchiveDao.delete(archive)
                                pruned++
                            }
                        }
                    }

                    if (pruned > 0) {
                        Log.i(TAG, "Pruned $pruned redundant archives")

                        // Run ANALYZE after pruning
                        appDatabase.openHelper.writableDatabase.execSQL("ANALYZE scrobbles_archive")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Archive pruning failed", e)
                }

                pruned
            }
    }

/**
 * Where the incremental-sync cursor is allowed to move to after a sync run.
 *
 * The cursor is the `from` timestamp of the next Last.fm `getRecentTracks` call, so moving it
 * too far forward loses scrobbles permanently: anything between the old and new cursor is never
 * fetched again. The rule is therefore "advance only as far as this run actually got":
 *
 * - nothing processed → leave the cursor alone (a failed first page must be retried);
 * - a clean full walk → the newest scrobble seen is safe, everything up to it is in the DB;
 * - early stop (page cap, fetch failure, skipped page) → only the *oldest* scrobble seen, so
 *   the next run re-reads the gap. Last.fm's `from` is inclusive and the dedup pass absorbs
 *   the overlap, so re-reading is free of duplicates.
 */
internal object SyncWatermark {
    fun nextCursor(
        currentCursor: Long,
        minProcessedMs: Long,
        maxProcessedMs: Long,
        fullyScanned: Boolean,
    ): Long {
        val watermarkMs = if (fullyScanned) maxProcessedMs else minProcessedMs
        return maxOf(watermarkMs / 1000, currentCursor)
    }
}
