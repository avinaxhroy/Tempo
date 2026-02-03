package me.avinas.tempo.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for importing listening history from Last.fm.
 * 
 * =====================================================
 * TWO-TIER ARCHITECTURE
 * =====================================================
 * 
 * Last.fm users can have massive histories (200K-300K+ scrobbles spanning 20 years).
 * To preserve query performance while importing complete history, we use two tiers:
 * 
 * 1. ACTIVE SET: Top tracks + recent history → Full ListeningEvent records
 *    - These affect leaderboard rankings
 *    - Stored in normal listening_events table
 *    - Query performance preserved
 * 
 * 2. ARCHIVE: Long-tail tracks (played 1-5 times) → Compressed storage
 *    - Never affect rankings (too few plays)
 *    - Stored in scrobbles_archive table with compressed timestamps
 *    - Accessible via explicit search/browsing
 * 
 * =====================================================
 * IMPORT TIERS
 * =====================================================
 * 
 * - LIGHTWEIGHT: Loved + Top 500 + 3 months (~75% coverage)
 * - BALANCED: Top 1000 + 6 months (~85% coverage) [Recommended]
 * - COMPREHENSIVE: Top 2000 + 12 months (~92% coverage)
 * - EVERYTHING: All tracks (100% but slower)
 */
@Singleton
class LastFmImportService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val lastFmApi: LastFmApi,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val userPreferencesDao: UserPreferencesDao,
    private val importMetadataDao: LastFmImportMetadataDao,
    private val scrobbleArchiveDao: ScrobbleArchiveDao,
    private val listeningEventDao: ListeningEventDao,
    private val appDatabase: me.avinas.tempo.data.local.AppDatabase,
    private val statsRepository: me.avinas.tempo.data.repository.StatsRepository
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
        
        // Source identifier for imported events
        const val IMPORT_SOURCE = "fm.last.import"
        
        // Default values for imported events (Last.fm doesn't provide these)
        private const val DEFAULT_DURATION_MS = 210_000L // 3.5 minutes
        private const val DEFAULT_COMPLETION_PERCENTAGE = 100
        
        // Duplicate detection tolerance (60 seconds)
        private const val DUPLICATE_TOLERANCE_MS = 60_000L
        
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
            val QUICK = TierConfig(
                name = "QUICK",
                topTracksCount = 500,
                recentMonths = 3,
                estimatedCoverage = 60
            )
            val STANDARD = TierConfig(
                name = "STANDARD",
                topTracksCount = 1000,
                recentMonths = 12,
                estimatedCoverage = 85
            )
            val DEEP = TierConfig(
                name = "DEEP",
                topTracksCount = 2000,
                recentMonths = 24,
                estimatedCoverage = 92
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
        val estimatedCoverage: Int
    )
    
    /**
     * Progress state for UI updates.
     */
    sealed class ImportProgress {
        object Idle : ImportProgress()
        data class Discovering(val message: String) : ImportProgress()
        data class Processing(val message: String) : ImportProgress()
        data class Importing(
            val phase: String,
            val current: Long,
            val total: Long,
            val eventsCreated: Long,
            val tracksCreated: Long,
            val archived: Long,
            val tierName: String = ""  // For UI to show appropriate labels
        ) : ImportProgress()
        data class Completed(val result: ImportResult) : ImportProgress()
        data class Failed(val error: String, val isRecoverable: Boolean = false) : ImportProgress()
        data class RateLimited(val retryAfterMs: Long) : ImportProgress()
    }
    
    /**
     * API error types for better error handling.
     */
    sealed class LastFmApiError : Exception() {
        data class UserNotFound(override val message: String) : LastFmApiError()
        data class PrivateProfile(override val message: String) : LastFmApiError()
        data class InvalidApiKey(override val message: String) : LastFmApiError()
        data class RateLimited(val retryAfterMs: Long) : LastFmApiError()
        data class ServiceUnavailable(override val message: String) : LastFmApiError()
        data class NetworkError(override val message: String, override val cause: Throwable?) : LastFmApiError()
        data class UnknownError(override val message: String, val errorCode: Int?) : LastFmApiError()
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
        val lovedTracksCount: Int
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
        val errorMessage: String? = null
    ) {
        // Aliases for UI compatibility
        val activeSetCount: Long get() = eventsImported
        val archivedCount: Long get() = scrobblesArchived
    }
    
    // Progress state flow for UI observation
    private val _progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()
    
    // Active set tracking (built during discovery)
    private var activeSetKeys = mutableSetOf<String>()
    private var lovedTrackKeys = mutableSetOf<String>()
    
    // ==================== Performance Optimization Caches ====================
    
    // In-memory track cache to avoid N+1 queries during import
    // Key: "title|artist" normalized, Value: Track or null
    private val trackCache = mutableMapOf<String, Track?>()
    
    // Batch pending EnrichedMetadata for bulk insert
    private val pendingMetadata = mutableListOf<EnrichedMetadata>()
    
    // Batch pending Tracks for bulk insert
    // Key: cacheKey ("title|artist"), Value: PendingTrack with scrobble data
    private data class PendingTrack(
        val track: Track,
        val cacheKey: String,
        val scrobble: LastFmScrobble
    )
    private val pendingTracks = mutableMapOf<String, PendingTrack>()
    
    // ==================== API Error Handling ====================
    
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
    private fun checkApiError(errorCode: Int?, errorMessage: String?): LastFmApiError? {
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
    private fun <T> handleHttpError(response: retrofit2.Response<T>): LastFmApiError {
        return when (response.code()) {
            429 -> LastFmApiError.RateLimited(RATE_LIMIT_BACKOFF_MS)
            500, 502, 503, 504 -> LastFmApiError.ServiceUnavailable(
                "Last.fm service temporarily unavailable (${response.code()})"
            )
            401 -> LastFmApiError.InvalidApiKey("Authentication failed")
            403 -> LastFmApiError.PrivateProfile("Access forbidden - profile may be private")
            404 -> LastFmApiError.UserNotFound("User not found")
            else -> LastFmApiError.UnknownError("HTTP error: ${response.code()}", response.code())
        }
    }
    
    /**
     * Execute an API call with retry logic for transient failures.
     * Handles rate limits with exponential backoff.
     */
    private suspend fun <T> executeWithRetry(
        operation: String,
        maxRetries: Int = MAX_RETRIES_PER_PAGE,
        apiCall: suspend () -> retrofit2.Response<T>
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
     * Check if we have network connectivity before starting long operations.
     */
    private fun isNetworkAvailable(): Boolean {
        // This would ideally use ConnectivityManager, but for now we'll rely on
        // the network errors being caught during API calls
        return true
    }

    /**
     * Discover user's Last.fm account information.
     * This is the first step - shows user what will be imported.
     */
    suspend fun discoverUser(username: String): Result<DiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            _progress.value = ImportProgress.Discovering("Connecting to Last.fm...")
            
            // Fetch user info with robust error handling
            val userInfoResult = executeWithRetry("getUserInfo") {
                lastFmApi.getUserInfo(user = username, apiKey = API_KEY)
            }
            
            val userInfo = userInfoResult.getOrElse { error ->
                val errorMessage = when (error) {
                    is LastFmApiError.UserNotFound -> "User '$username' not found on Last.fm"
                    is LastFmApiError.PrivateProfile -> "User '$username' has a private profile"
                    is LastFmApiError.InvalidApiKey -> "Last.fm API configuration error"
                    is LastFmApiError.NetworkError -> "Network error: ${error.message}"
                    is LastFmApiError.RateLimited -> "Rate limited, please try again later"
                    else -> error.message ?: "Failed to fetch user info"
                }
                val isRecoverable = error is LastFmApiError.RateLimited || 
                                   error is LastFmApiError.NetworkError ||
                                   error is LastFmApiError.ServiceUnavailable
                _progress.value = ImportProgress.Failed(errorMessage, isRecoverable)
                return@withContext Result.failure(error)
            }
            
            // Check for API-level errors in the response body
            val apiError = checkApiError(userInfo.error, userInfo.message)
            if (apiError != null) {
                val errorMessage = when (apiError) {
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
            val topTracksResult = executeWithRetry("getTopTracks") {
                lastFmApi.getTopTracks(user = username, apiKey = API_KEY, limit = 1, page = 1)
            }
            val topTracksCount = topTracksResult.getOrNull()?.toptracks?.attr?.getTotal() ?: 0
            
            // Fetch loved tracks count with error handling
            delay(RATE_LIMIT_MS)
            val lovedTracksResult = executeWithRetry("getLovedTracks") {
                lastFmApi.getLovedTracks(user = username, apiKey = API_KEY, limit = 1, page = 1)
            }
            val lovedTracksCount = lovedTracksResult.getOrNull()?.lovedtracks?.attr?.getTotal() ?: 0
            
            _progress.value = ImportProgress.Idle
            
            Result.success(
                DiscoveryResult(
                    username = user.name ?: username,
                    totalScrobbles = user.getPlayCountLong() ?: 0,
                    registeredDate = user.getRegisteredTimestampMs(),
                    latestScrobble = System.currentTimeMillis(), // Will be updated during import
                    profileImageUrl = user.getBestImageUrl(),
                    topTracksCount = topTracksCount,
                    lovedTracksCount = lovedTracksCount
                )
            )
        } catch (e: LastFmApiError) {
            Log.e(TAG, "Discovery failed with API error", e)
            val isRecoverable = e is LastFmApiError.RateLimited || 
                               e is LastFmApiError.NetworkError ||
                               e is LastFmApiError.ServiceUnavailable
            _progress.value = ImportProgress.Failed(e.message ?: "Discovery failed", isRecoverable)
            Result.failure(e)
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
        totalScrobbles: Long
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        val startTimeMs = System.currentTimeMillis()
        
        try {
            Log.i(TAG, "Starting Last.fm import for $username with tier ${tier.name}")
            
            // Create import metadata record
            val importMetadata = LastFmImportMetadata(
                lastfmUsername = username,
                importTier = tier.name,
                activeTrackThreshold = tier.topTracksCount,
                recentMonthsIncluded = tier.recentMonths,
                totalScrobblesFound = totalScrobbles,
                status = LastFmImportMetadata.STATUS_DISCOVERING
            )
            val importId = importMetadataDao.insert(importMetadata)
            
            // Phase 1: Build active set
            _progress.value = ImportProgress.Discovering("Building active set...")
            val activeSetBuilt = buildActiveSet(username, tier)
            
            if (!activeSetBuilt) {
                val errorMessage = "Failed to build active set - network or rate limit error"
                importMetadataDao.markFailed(
                    id = importId,
                    failedAt = System.currentTimeMillis(),
                    errorMessage = errorMessage
                )
                _progress.value = ImportProgress.Failed(errorMessage, true)
                return@withContext Result.failure(
                    LastFmApiError.NetworkError(errorMessage, null)
                )
            }
            
            // Phase 2: Import scrobbles
            importMetadataDao.updateStatus(importId, LastFmImportMetadata.STATUS_IN_PROGRESS)
            val result = importScrobbles(username, importId, totalScrobbles, tier, startTimeMs)
            
            // Phase 3: Mark complete
            if (result.success) {
                importMetadataDao.markCompleted(
                    id = importId,
                    completedAt = System.currentTimeMillis(),
                    syncCursor = System.currentTimeMillis() / 1000 // Unix timestamp for cursor
                )
                
                // Update user preferences
                val prefs = userPreferencesDao.getSync() ?: UserPreferences()
                userPreferencesDao.upsert(
                    prefs.copy(
                        lastfmUsername = username,
                        lastfmConnected = true
                    )
                )
                
                // Phase 6: Post-import optimization
                _progress.value = ImportProgress.Processing("Optimizing database...")
                runPostImportOptimization()
                
                // Phase 7: Schedule accelerated enrichment for imported tracks
                // This runs in background with larger batches to quickly enrich
                // imported tracks (album art, genres, etc.)
                // NOTE: Only active tracks (tracksCreated) are enriched.
                // Archived tracks use Last.fm album art and are never enriched.
                if (result.tracksCreated > 0) {
                    Log.i(TAG, "Scheduling post-import enrichment for ${result.tracksCreated} active tracks (${result.scrobblesArchived} archived - not enriched)")
                    me.avinas.tempo.worker.EnrichmentWorker.schedulePostImportEnrichment(
                        context = context,
                        tracksCreated = result.tracksCreated
                    )
                }
            } else {
                importMetadataDao.markFailed(
                    id = importId,
                    failedAt = System.currentTimeMillis(),
                    errorMessage = result.errorMessage ?: "Unknown error"
                )
            }
            
            _progress.value = ImportProgress.Completed(result)
            Result.success(result)
            
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
    suspend fun syncNewScrobbles(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Get the latest completed import
            val lastImport = importMetadataDao.getLatestCompleted()
            if (lastImport == null) {
                Log.w(TAG, "No completed import found, cannot sync")
                return@withContext Result.failure(IllegalStateException("No completed import found"))
            }
            
            val username = lastImport.lastfmUsername
            val syncCursor = lastImport.lastSyncCursor ?: (System.currentTimeMillis() / 1000)
            
            Log.i(TAG, "Syncing scrobbles for $username since ${java.util.Date(syncCursor * 1000)}")
            
            _progress.value = ImportProgress.Processing("Syncing recent scrobbles...")
            
            var newScrobbles = 0
            var page = 1
            var hasMore = true
            
            // Fetch recent scrobbles after the sync cursor
            while (hasMore && page <= 50) { // Safety limit: max 50 pages
                val apiResult = executeWithRetry("syncNewScrobbles page $page") {
                    lastFmApi.getRecentTracks(
                        user = username,
                        apiKey = API_KEY,
                        limit = PAGE_SIZE,
                        page = page,
                        from = syncCursor // Only get scrobbles after this timestamp
                    )
                }
                
                val response = apiResult.getOrElse { error ->
                    Log.e(TAG, "Failed to fetch sync page $page", error)
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
                    
                    // Find or create track
                    var existingTrack = trackRepository.findByTitleAndArtist(trackTitle, artistName)
                    
                    val trackId = existingTrack?.id ?: run {
                        // Create new track
                        val newTrack = Track(
                            title = trackTitle,
                            artist = artistName,
                            album = scrobble.album?.name,
                            duration = null,
                            albumArtUrl = scrobble.getBestImageUrl(),
                            spotifyId = null,
                            musicbrainzId = scrobble.mbid
                        )
                        trackRepository.insert(newTrack)
                    }
                    
                    // Check for replay (same track within 5 minutes)
                    val isReplay = listeningEventDao.wasRecentlyPlayed(
                        trackId = trackId,
                        sinceTimestamp = timestamp - REPLAY_THRESHOLD_MS
                    )
                    
                    eventsToInsert.add(ListeningEvent(
                        track_id = trackId,
                        timestamp = timestamp,
                        playDuration = DEFAULT_DURATION_MS,
                        completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                        source = IMPORT_SOURCE,
                        wasSkipped = false,
                        isReplay = isReplay
                    ))
                }
                
                // Batch insert with deduplication
                if (eventsToInsert.isNotEmpty()) {
                    val insertResult = listeningEventDao.insertAllBatchedWithDedup(eventsToInsert)
                    newScrobbles += insertResult.inserted
                    Log.d(TAG, "Sync page $page: inserted ${insertResult.inserted}, skipped ${insertResult.skipped}")
                }
                
                // Check if there are more pages
                val attr = recentTracks?.attr
                val totalPages = attr?.getTotalPages() ?: 1
                hasMore = page < totalPages
                page++
                
                delay(RATE_LIMIT_MS)
            }
            
            // Update sync cursor to now
            val now = System.currentTimeMillis()
            importMetadataDao.updateSyncCursor(
                id = lastImport.id,
                cursor = now / 1000,
                timestamp = now
            )
            
            // Invalidate cache if we added new scrobbles
            if (newScrobbles > 0) {
                statsRepository.invalidateCache()
            }
            
            Log.i(TAG, "Sync complete: $newScrobbles new scrobbles imported")
            _progress.value = ImportProgress.Completed(
                ImportResult(
                    success = true,
                    eventsImported = newScrobbles.toLong(),
                    tracksCreated = 0,
                    artistsCreated = 0,
                    scrobblesArchived = 0,
                    duplicatesSkipped = 0,
                    totalProcessed = newScrobbles.toLong()
                )
            )
            
            Result.success(newScrobbles)
            
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
    private suspend fun buildActiveSet(username: String, tier: TierConfig): Boolean {
        activeSetKeys.clear()
        lovedTrackKeys.clear()
        
        // Fetch loved tracks (always in active set)
        _progress.value = ImportProgress.Discovering("Fetching loved tracks...")
        var lovedPage = 1
        var lovedTracksProcessed = false
        
        do {
            val result = executeWithRetry("getLovedTracks page $lovedPage") {
                lastFmApi.getLovedTracks(
                    user = username,
                    apiKey = API_KEY,
                    limit = PAGE_SIZE,
                    page = lovedPage
                )
            }
            
            val response = result.getOrElse { error ->
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
        } while (lovedPage <= totalPages)
        
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
        var topTracksFromApi = 0  // Tracks received from API (may include duplicates with loved)
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
            
            val result = executeWithRetry("getTopTracks page $topPage") {
                lastFmApi.getTopTracks(
                    user = username,
                    apiKey = API_KEY,
                    limit = PAGE_SIZE,  // Always fetch full page, filter locally
                    page = topPage
                )
            }
            
            val response = result.getOrElse { error ->
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
        } while (topPage <= totalPages)
        
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
        startTimeMs: Long
    ): ImportResult {
        var eventsImported = 0L
        var tracksCreated = 0L
        var artistsCreated = 0L
        var scrobblesArchived = 0L
        var duplicatesSkipped = 0L
        var scrobblesProcessed = 0L
        
        // Track archived scrobbles for batch compression
        val archiveBatch = mutableMapOf<String, ArchivePendingTrack>()
        
        // Batch active events for efficient inserts
        val activeEventsBatch = mutableListOf<ListeningEvent>()
        
        // Calculate recent cutoff timestamp for tiered imports
        val recentCutoffMs = if (tier.recentMonths < Int.MAX_VALUE) {
            System.currentTimeMillis() - (tier.recentMonths.toLong() * 30 * 24 * 60 * 60 * 1000)
        } else {
            0L
        }
        
        var page = 1
        var hasMore = true
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 5
        
        while (hasMore) {
            // Fetch page with robust error handling
            val result = executeWithRetry("getRecentTracks page $page") {
                lastFmApi.getRecentTracks(
                    user = username,
                    apiKey = API_KEY,
                    limit = PAGE_SIZE,
                    page = page
                )
            }
            
            val response = result.getOrElse { error ->
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
                        errorMessage = "Import stopped after $consecutiveErrors consecutive errors: ${error.message}"
                    )
                }
                
                // Skip this page and continue with next
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
                    _progress.value = ImportProgress.RateLimited(RATE_LIMIT_BACKOFF_MS)
                    delay(RATE_LIMIT_BACKOFF_MS)
                    // Retry same page
                    continue
                }
                
                // Other API errors - skip page and continue
                page++
                continue
            }
            
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
                        is ScrobblePrepareResult.Duplicate -> duplicatesSkipped++
                        is ScrobblePrepareResult.Error -> Log.w(TAG, "Failed to process: ${result.message}")
                    }
                    
                    // Batch insert when we hit the threshold
                    if (activeEventsBatch.size >= EVENT_BATCH_SIZE) {
                        val batchResult = listeningEventDao.insertAllBatchedWithDedup(activeEventsBatch)
                        // Count actual insertions (not duplicates skipped at batch level)
                        eventsImported += batchResult.inserted
                        duplicatesSkipped += batchResult.skipped
                        activeEventsBatch.clear()
                    }
                } else {
                    // =====================================================
                    // ARCHIVE PATH: NO ENRICHMENT
                    // =====================================================
                    // Archived tracks are stored with Last.fm album art only.
                    // NO Track entity is created → NO EnrichedMetadata is created
                    // → EnrichmentWorker will NEVER see these tracks.
                    // This is intentional: archived tracks are rarely viewed,
                    // so enriching them would waste API quota.
                    // The album art from Last.fm (stored below) is sufficient.
                    // =====================================================
                    val pending = archiveBatch.getOrPut(normalizedKey) {
                        ArchivePendingTrack(
                            trackTitle = scrobble.name ?: "",
                            artistName = scrobble.artist?.getArtistName() ?: "",
                            albumName = scrobble.album?.name,
                            musicbrainzId = scrobble.mbid,
                            albumArtUrl = scrobble.getBestImageUrl(), // Last.fm art - NOT enriched later
                            wasLoved = lovedTrackKeys.contains(normalizedKey),
                            timestamps = mutableListOf()
                        )
                    }
                    pending.timestamps.add(timestampMs)
                    scrobblesArchived++
                }
                
                // Update progress periodically
                if (scrobblesProcessed % 100 == 0L) {
                    _progress.value = ImportProgress.Importing(
                        phase = "Importing scrobbles",
                        current = scrobblesProcessed,
                        total = totalScrobbles,
                        eventsCreated = eventsImported,
                        tracksCreated = tracksCreated,
                        archived = scrobblesArchived,
                        tierName = tier.name
                    )
                    
                    importMetadataDao.updateProgress(importId, page, scrobblesProcessed)
                }
            }
            
            // Compress and save archive batch periodically
            if (archiveBatch.size >= 500) {
                saveArchiveBatch(archiveBatch, importId)
                archiveBatch.clear()
            }
            
            hasMore = page < totalPages
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
        
        // Flush any remaining pending tracks (shouldn't be any at this point)
        flushPendingTracks()
        
        // Clear performance caches
        trackCache.clear()
        pendingTracks.clear()
        Log.d(TAG, "Import complete - caches cleared")
        
        // Calculate duration
        val durationSeconds = (System.currentTimeMillis() - startTimeMs) / 1000
        
        // Update final results
        importMetadataDao.updateResults(
            id = importId,
            eventsImported = eventsImported,
            tracksCreated = tracksCreated,
            artistsCreated = artistsCreated,
            scrobblesArchived = scrobblesArchived,
            duplicatesSkipped = duplicatesSkipped
        )
        
        return ImportResult(
            success = true,
            eventsImported = eventsImported,
            tracksCreated = tracksCreated,
            artistsCreated = artistsCreated,
            scrobblesArchived = scrobblesArchived,
            duplicatesSkipped = duplicatesSkipped,
            totalProcessed = scrobblesProcessed,
            durationSeconds = durationSeconds
        )
    }
    
    /**
     * Prepare a scrobble for batch insertion (doesn't insert yet).
     * Returns the event ready for batch or error status.
     */
    private sealed class ScrobblePrepareResult {
        data class Ready(val event: ListeningEvent, val newTrack: Boolean, val newArtist: Boolean) : ScrobblePrepareResult()
        object Duplicate : ScrobblePrepareResult()
        data class Error(val message: String) : ScrobblePrepareResult()
    }
    
    private suspend fun prepareScrobbleForActive(
        scrobble: LastFmScrobble,
        timestampMs: Long,
        pendingEvents: List<ListeningEvent>
    ): ScrobblePrepareResult {
        val trackTitle = scrobble.name ?: return ScrobblePrepareResult.Error("No track name")
        val artistName = scrobble.artist?.getArtistName() 
            ?: return ScrobblePrepareResult.Error("No artist name")
        
        // === PERFORMANCE OPTIMIZATION: Use in-memory cache ===
        val cacheKey = "${trackTitle.lowercase().trim()}|${artistName.lowercase().trim()}"
        var track = trackCache[cacheKey]
        var isNewTrack = false
        var isNewArtist = false
        var needsMetadata = false
        
        // Check if we've already looked this up (null means looked up but not found)
        if (!trackCache.containsKey(cacheKey)) {
            // First time seeing this track - check database
            track = trackRepository.findByTitleAndArtist(trackTitle, artistName)
            if (track == null) {
                // Try fuzzy match
                track = trackRepository.findByTitleAndArtistFuzzy(trackTitle, artistName)
            }
            // Cache the result (even null to avoid repeated lookups)
            trackCache[cacheKey] = track
        }
        
        if (track == null) {
            // Check if already in pending batch (not yet flushed to DB)
            val pendingTrack = pendingTracks[cacheKey]
            if (pendingTrack != null) {
                // Use track from pending batch (will get ID after flush)
                track = pendingTrack.track
                if (track.id == 0L) {
                    // Track not yet flushed - flush now to get IDs
                    flushPendingTracks()
                    // Re-fetch from cache after flush
                    track = trackCache[cacheKey]
                }
                isNewTrack = false // Already counted as new
            }
        }
        
        if (track == null) {
            // Create new track and queue for batch insert
            val newTrack = Track(
                title = trackTitle,
                artist = artistName,
                album = scrobble.album?.name,
                duration = null, // Will be enriched later
                albumArtUrl = scrobble.getBestImageUrl(),
                spotifyId = null,
                musicbrainzId = scrobble.mbid,
                primaryArtistId = null, // DEFERRED: Artist linking handled by background worker
                contentType = "MUSIC"
            )
            
            // Queue for batch insert
            pendingTracks[cacheKey] = PendingTrack(newTrack, cacheKey, scrobble)
            isNewTrack = true
            
            // Flush when batch reaches threshold
            if (pendingTracks.size >= EVENT_BATCH_SIZE) {
                flushPendingTracks()
                // Get the inserted track with ID
                track = trackCache[cacheKey]
            } else {
                // Track will be flushed later, insert immediately for now
                // (This ensures we have track ID for listening event)
                val trackId = trackRepository.insert(newTrack)
                track = newTrack.copy(id = trackId)
                trackCache[cacheKey] = track
                pendingTracks.remove(cacheKey) // Remove since we inserted directly
                
                // BATCHED: Queue metadata instead of immediate insert  
                queueEnrichedMetadata(track, scrobble)
            }
            
            // DEFERRED: Skip artist linking during import for performance
            // The existing ArtistLinkingService.processUnlinkedTracks() background worker
            // will handle this after import completes
            isNewArtist = true // Assume new artist for stats, will be deduplicated later
        }
        
        // Ensure we have a valid track with ID
        val finalTrack = track ?: return ScrobblePrepareResult.Error("Failed to create or find track")
        
        // OPTIMIZATION: Skip per-scrobble duplicate check during import.
        // The batch insert (insertAllBatchedWithDedup) handles deduplication efficiently
        // by fetching all timestamps for the batch in one query instead of N queries.
        
        // Calculate isReplay: Only check in-memory batch for replay detection during import.
        // This avoids N database queries per scrobble. The replay flag is not critical
        // for imported data - it's primarily for real-time listening feedback.
        val hasNearbyInBatch = pendingEvents.any { event ->
            event.track_id == finalTrack.id &&
            kotlin.math.abs(event.timestamp - timestampMs) <= REPLAY_THRESHOLD_MS &&
            event.timestamp != timestampMs // Not the same event
        }
        
        // Mark as replay only if there's a nearby play in the current batch
        // (DB check skipped for performance - imported data replay detection is best-effort)
        val isReplay = hasNearbyInBatch
        
        // Create listening event
        val event = ListeningEvent(
            track_id = finalTrack.id,
            timestamp = timestampMs,
            playDuration = DEFAULT_DURATION_MS,
            completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
            source = IMPORT_SOURCE,
            wasSkipped = false,
            isReplay = isReplay,
            estimatedDurationMs = DEFAULT_DURATION_MS,
            pauseCount = 0,
            sessionId = null,
            endTimestamp = timestampMs + DEFAULT_DURATION_MS
        )
        
        return ScrobblePrepareResult.Ready(event, isNewTrack, isNewArtist)
    }
    
    /**
     * Create enriched metadata for a new track (used for sync operations).
     */
    private suspend fun createEnrichedMetadata(track: Track, scrobble: LastFmScrobble) {
        try {
            val albumArtUrl = scrobble.getBestImageUrl()
            val metadata = EnrichedMetadata(
                trackId = track.id,
                musicbrainzRecordingId = scrobble.mbid,
                musicbrainzArtistId = scrobble.artist?.mbid,
                albumTitle = scrobble.album?.name,
                musicbrainzReleaseId = scrobble.album?.mbid,
                albumArtUrl = albumArtUrl,
                // Use DEEZER priority level for Last.fm art (reasonable quality)
                albumArtSource = if (albumArtUrl != null) AlbumArtSource.DEEZER else AlbumArtSource.NONE,
                artistName = scrobble.artist?.getArtistName(),
                enrichmentStatus = EnrichmentStatus.PENDING,
                cacheTimestamp = System.currentTimeMillis()
            )
            enrichedMetadataDao.upsert(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create enriched metadata for track ${track.id}", e)
        }
    }
    
    /**
     * Queue enriched metadata for batch insert (performance optimization).
     */
    private suspend fun queueEnrichedMetadata(track: Track, scrobble: LastFmScrobble) {
        val albumArtUrl = scrobble.getBestImageUrl()
        val metadata = EnrichedMetadata(
            trackId = track.id,
            musicbrainzRecordingId = scrobble.mbid,
            musicbrainzArtistId = scrobble.artist?.mbid,
            albumTitle = scrobble.album?.name,
            musicbrainzReleaseId = scrobble.album?.mbid,
            albumArtUrl = albumArtUrl,
            albumArtSource = if (albumArtUrl != null) AlbumArtSource.DEEZER else AlbumArtSource.NONE,
            artistName = scrobble.artist?.getArtistName(),
            enrichmentStatus = EnrichmentStatus.PENDING,
            cacheTimestamp = System.currentTimeMillis()
        )
        pendingMetadata.add(metadata)
        
        // Flush when batch is full
        if (pendingMetadata.size >= METADATA_BATCH_SIZE) {
            flushPendingMetadata()
        }
    }
    
    /**
     * Flush pending metadata to database in batch.
     */
    private suspend fun flushPendingMetadata() {
        if (pendingMetadata.isEmpty()) return
        try {
            enrichedMetadataDao.upsertAll(pendingMetadata)
            Log.d(TAG, "Flushed ${pendingMetadata.size} pending metadata records")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush ${pendingMetadata.size} metadata records", e)
        }
        pendingMetadata.clear()
    }
    
    /**
     * Flush pending tracks to database in batch.
     * After insert, updates the cache with the inserted tracks (with IDs).
     */
    private suspend fun flushPendingTracks() {
        if (pendingTracks.isEmpty()) return
        try {
            val tracksToInsert = pendingTracks.values.map { it.track }
            val insertedIds = trackRepository.insertAll(tracksToInsert)
            
            // Update cache with inserted tracks (now with IDs)
            pendingTracks.values.forEachIndexed { index, pending ->
                val insertedId = insertedIds.getOrNull(index) ?: 0L
                if (insertedId > 0) {
                    val trackWithId = pending.track.copy(id = insertedId)
                    trackCache[pending.cacheKey] = trackWithId
                    // Queue metadata for the track
                    queueEnrichedMetadata(trackWithId, pending.scrobble)
                }
            }
            Log.d(TAG, "Flushed ${pendingTracks.size} pending tracks")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush ${pendingTracks.size} pending tracks", e)
        }
        pendingTracks.clear()
    }
    
    /**
     * Compress and save archived scrobbles with proper merge handling.
     * Uses upsertWithMerge to correctly handle tracks that span multiple batches.
     */
    private suspend fun saveArchiveBatch(
        batch: Map<String, ArchivePendingTrack>,
        importId: Long
    ) {
        for ((_, pending) in batch) {
            val timestamps = pending.timestamps.sorted()
            if (timestamps.isEmpty()) continue
            
            val archive = ScrobbleArchive(
                trackHash = ScrobbleArchive.generateTrackHash(pending.artistName, pending.trackTitle),
                trackTitle = pending.trackTitle,
                artistName = pending.artistName,
                artistNameNormalized = pending.artistName.lowercase().trim(),
                albumName = pending.albumName,
                musicbrainzId = pending.musicbrainzId,
                timestampsBlob = compressTimestamps(timestamps),
                playCount = timestamps.size,
                firstScrobble = timestamps.first(),
                lastScrobble = timestamps.last(),
                albumArtUrl = pending.albumArtUrl,
                wasLoved = pending.wasLoved,
                importId = importId
            )
            
            // Use merge upsert to handle tracks spanning multiple batches
            scrobbleArchiveDao.upsertWithMerge(
                archive = archive,
                decompressTimestamps = ::decompressTimestamps,
                compressTimestamps = ::compressTimestamps
            )
        }
    }
    
    /**
     * Compress timestamps using delta encoding.
     */
    private fun compressTimestamps(timestamps: List<Long>): ByteArray {
        if (timestamps.isEmpty()) return ByteArray(0)
        
        val output = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(output)
        
        // Write first timestamp as base
        dataOut.writeLong(timestamps.first())
        
        // Write count
        dataOut.writeInt(timestamps.size)
        
        // Write deltas (in seconds to save space)
        var prev = timestamps.first()
        for (i in 1 until timestamps.size) {
            val delta = ((timestamps[i] - prev) / 1000).toInt()
            dataOut.writeInt(delta)
            prev = timestamps[i]
        }
        
        dataOut.flush()
        return output.toByteArray()
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
        val timestamps: MutableList<Long>
    )
    
    /**
     * Cancel current import.
     */
    fun cancelImport() {
        _progress.value = ImportProgress.Idle
        activeSetKeys.clear()
        lovedTrackKeys.clear()
        // Clear performance caches
        trackCache.clear()
        pendingTracks.clear()
        pendingMetadata.clear()
    }
    
    /**
     * Check if an import is currently in progress.
     */
    suspend fun hasActiveImport(): Boolean {
        return importMetadataDao.getActiveImport() != null
    }
    
    /**
     * Get the most recent completed import for a user.
     */
    suspend fun getLastImport(username: String): LastFmImportMetadata? {
        return importMetadataDao.getLatestCompletedForUsername(username)
    }
    
    // ==================== Archive Query Methods ====================
    
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
        val lastPlayed: Long? = null
    )
    
    /**
     * Search both active tracks and archive, returning unified results.
     * Archive results are marked with isFromArchive = true.
     * 
     * NOTE: This method has an N+1 query pattern for active tracks (3 queries per track).
     * For better performance with large result sets, consider adding a batch query to
     * ListeningEventDao that returns track stats in bulk.
     */
    suspend fun searchUnified(query: String, limit: Int = 50): List<UnifiedSearchResult> {
        val results = mutableListOf<UnifiedSearchResult>()
        
        // Search active tracks first
        val activeTracks = trackRepository.searchTracks(query)
        for (track in activeTracks.take(limit)) {
            // Get play count from listening events
            val playCount = listeningEventDao.countByTrackId(track.id)
            val firstPlayed = listeningEventDao.getFirstPlayTimestampForTrack(track.id)
            val lastPlayed = listeningEventDao.getLastPlayTimestampForTrack(track.id)
            
            results.add(UnifiedSearchResult(
                title = track.title,
                artist = track.artist,
                album = track.album,
                playCount = playCount,
                isFromArchive = false,
                trackId = track.id,
                albumArtUrl = track.albumArtUrl,
                firstPlayed = firstPlayed,
                lastPlayed = lastPlayed
            ))
        }
        
        // Search archive for additional results
        val archiveResults = scrobbleArchiveDao.search(query, limit)
        for (archive in archiveResults) {
            // Skip if we already have this track from active
            val alreadyHave = results.any { 
                it.title.equals(archive.trackTitle, ignoreCase = true) && 
                it.artist.equals(archive.artistName, ignoreCase = true)
            }
            
            if (!alreadyHave) {
                results.add(UnifiedSearchResult(
                    title = archive.trackTitle,
                    artist = archive.artistName,
                    album = archive.albumName,
                    playCount = archive.playCount,
                    isFromArchive = true,
                    archiveId = archive.id,
                    albumArtUrl = archive.albumArtUrl,
                    firstPlayed = archive.firstScrobble,
                    lastPlayed = archive.lastScrobble
                ))
            }
        }
        
        // Sort by play count descending
        return results.sortedByDescending { it.playCount }.take(limit)
    }
    
    /**
     * Promote an archived track to active set.
     * Creates full ListeningEvent records for all archived timestamps.
     */
    suspend fun promoteFromArchive(archiveId: Long): Result<PromotionResult> = withContext(Dispatchers.IO) {
        try {
            val archive = scrobbleArchiveDao.getById(archiveId)
                ?: return@withContext Result.failure(Exception("Archive entry not found"))
            
            // Find or create the track
            var track = trackRepository.findByTitleAndArtist(archive.trackTitle, archive.artistName)
            var isNewTrack = false
            
            if (track == null) {
                val newTrack = Track(
                    title = archive.trackTitle,
                    artist = archive.artistName,
                    album = archive.albumName,
                    duration = null,
                    albumArtUrl = archive.albumArtUrl,
                    spotifyId = null,
                    musicbrainzId = archive.musicbrainzId,
                    primaryArtistId = null,
                    contentType = "MUSIC"
                )
                val trackId = trackRepository.insert(newTrack)
                track = newTrack.copy(id = trackId)
                isNewTrack = true
                
                // Link artists
                try {
                    artistLinkingService.linkArtistsForTrack(track)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to link artists during promotion", e)
                }
            }
            
            // Decompress timestamps
            val timestamps = decompressTimestamps(archive.timestampsBlob).sorted()
            
            // Create listening events for each timestamp, calculating isReplay
            val events = mutableListOf<ListeningEvent>()
            var previousTimestamp: Long? = null
            
            for (timestampMs in timestamps) {
                // Check if this is a replay (same track within REPLAY_THRESHOLD_MS of previous)
                val isReplay = previousTimestamp != null && 
                    (timestampMs - previousTimestamp) <= REPLAY_THRESHOLD_MS
                
                events.add(ListeningEvent(
                    track_id = track.id,
                    timestamp = timestampMs,
                    playDuration = DEFAULT_DURATION_MS,
                    completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                    source = "$IMPORT_SOURCE.promoted",
                    wasSkipped = false,
                    isReplay = isReplay,
                    estimatedDurationMs = DEFAULT_DURATION_MS,
                    pauseCount = 0,
                    sessionId = null,
                    endTimestamp = timestampMs + DEFAULT_DURATION_MS
                ))
                
                previousTimestamp = timestampMs
            }
            
            // Batch insert with dedup
            val insertResult = listeningEventDao.insertAllBatchedWithDedup(events)
            
            // Delete from archive
            scrobbleArchiveDao.deleteById(archiveId)
            
            // Invalidate stats cache since play counts changed
            statsRepository.invalidateCache()
            
            Log.i(TAG, "Promoted ${archive.trackTitle} - ${archive.artistName}: " +
                "${insertResult.inserted} events created, ${insertResult.skipped} duplicates skipped")
            
            Result.success(PromotionResult(
                trackId = track.id,
                eventsCreated = insertResult.inserted,
                duplicatesSkipped = insertResult.skipped,
                isNewTrack = isNewTrack
            ))
            
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
        val isNewTrack: Boolean
    )
    
    /**
     * Decompress timestamps from archive blob.
     */
    private fun decompressTimestamps(blob: ByteArray): List<Long> {
        if (blob.isEmpty()) return emptyList()
        
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(blob))
        
        // Read base timestamp
        val baseTimestamp = input.readLong()
        
        // Read count
        val count = input.readInt()
        
        if (count <= 1) return listOf(baseTimestamp)
        
        // Read deltas and reconstruct timestamps
        val timestamps = mutableListOf(baseTimestamp)
        var current = baseTimestamp
        
        repeat(count - 1) {
            val deltaSec = input.readInt()
            current += deltaSec * 1000L
            timestamps.add(current)
        }
        
        return timestamps
    }
    
    /**
     * Get archive statistics for UI display.
     */
    suspend fun getArchiveStats(): ArchiveStats {
        return ArchiveStats(
            totalTracks = scrobbleArchiveDao.getTotalCount(),
            totalPlays = scrobbleArchiveDao.getTotalPlayCount(),
            storageBytes = scrobbleArchiveDao.getStorageSizeBytes(),
            uniqueArtists = scrobbleArchiveDao.getUniqueArtistCount()
        )
    }
    
    /**
     * Archive statistics for display.
     */
    data class ArchiveStats(
        val totalTracks: Int,
        val totalPlays: Long,
        val storageBytes: Long,
        val uniqueArtists: Int
    )
    
    // =====================
    // History Timeline Integration
    // =====================
    
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
        val isFromArchive: Boolean = true
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
        limit: Int = 100
    ): List<ArchiveHistoryItem> {
        // Get archives that overlap with the date range
        // Use a higher limit for archives since each archive can have multiple timestamps
        // and we'll filter/limit after expanding
        val archiveLimit = limit * 5  // Fetch more archives to account for filtering
        val archives = if (searchQuery.isNullOrBlank()) {
            scrobbleArchiveDao.getInDateRange(startTime, endTime, archiveLimit)
        } else {
            // Search within date range
            scrobbleArchiveDao.searchByTitle(searchQuery, limit).filter { archive ->
                // Check if any timestamps fall within range
                archive.firstScrobble <= endTime && archive.lastScrobble >= startTime
            }
        }
        
        // Expand compressed timestamps and filter to date range
        val expandedItems = mutableListOf<ArchiveHistoryItem>()
        
        for (archive in archives) {
            val timestamps = decompressTimestamps(archive.timestampsBlob)
            
            // Filter timestamps to those in range
            val inRangeTimestamps = timestamps.filter { ts ->
                ts in startTime..endTime
            }
            
            // Create history items for each timestamp
            for (ts in inRangeTimestamps) {
                expandedItems.add(ArchiveHistoryItem(
                    archiveId = archive.id,
                    trackTitle = archive.trackTitle,
                    artistName = archive.artistName,
                    albumName = archive.albumName,
                    timestamp = ts,
                    albumArtUrl = archive.albumArtUrl
                ))
            }
        }
        
        // Sort by timestamp descending and limit
        return expandedItems
            .sortedByDescending { it.timestamp }
            .take(limit)
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
    suspend fun getArchivePlayCountInRange(startTime: Long, endTime: Long): Int {
        // Use a very high limit to get all archives in range for accurate count
        // This is a stats method, so accuracy is more important than speed
        val archives = scrobbleArchiveDao.getInDateRange(startTime, endTime, limit = 10000)
        
        var totalCount = 0
        for (archive in archives) {
            val timestamps = decompressTimestamps(archive.timestampsBlob)
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
        limit: Int = 100
    ): List<ArchiveHistoryItem> {
        val archives = scrobbleArchiveDao.searchByArtist(artistName, limit * 2)
        
        val expandedItems = mutableListOf<ArchiveHistoryItem>()
        
        for (archive in archives) {
            val timestamps = decompressTimestamps(archive.timestampsBlob)
            
            // Filter timestamps to range if specified
            val filteredTimestamps = timestamps.filter { ts ->
                (startTime == null || ts >= startTime) && (endTime == null || ts <= endTime)
            }
            
            for (ts in filteredTimestamps) {
                expandedItems.add(ArchiveHistoryItem(
                    archiveId = archive.id,
                    trackTitle = archive.trackTitle,
                    artistName = archive.artistName,
                    albumName = archive.albumName,
                    timestamp = ts,
                    albumArtUrl = archive.albumArtUrl
                ))
            }
        }
        
        return expandedItems
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * Check if archive data exists for better UI hints.
     */
    suspend fun hasArchiveData(): Boolean {
        return scrobbleArchiveDao.getTotalCount() > 0
    }
    
    // =====================
    // Phase 6: Post-Import Optimization
    // =====================
    
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
                    val freeListCount = db.query("PRAGMA freelist_count").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    val pageCount = db.query("PRAGMA page_count").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 1
                    }
                    
                    val freeSpaceRatio = freeListCount.toFloat() / pageCount
                    Log.d(TAG, "Database free space: ${(freeSpaceRatio * 100).toInt()}% (freelist=$freeListCount, pages=$pageCount)")
                    
                    if (freeSpaceRatio > 0.10) {
                        Log.d(TAG, "Running incremental VACUUM (free space > 10%)...")
                        // Vacuum up to 1000 pages at a time to avoid blocking
                        db.execSQL("PRAGMA incremental_vacuum(1000)")
                    }
                    
                    // 3. Checkpoint WAL to merge changes into main database
                    Log.d(TAG, "Checkpointing WAL...")
                    db.execSQL("PRAGMA wal_checkpoint(PASSIVE)")
                }
                
                // 4. Invalidate stats cache
                Log.d(TAG, "Invalidating stats cache...")
                statsRepository.invalidateCache()
                
                Log.i(TAG, "Post-import optimization complete")
                
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
    suspend fun prunePromotedArchives(): Int {
        return withContext(Dispatchers.IO) {
            var pruned = 0
            
            try {
                // Process archives in batches to avoid OOM for large archives
                // Get candidates that might have been promoted (high play count suggests activity)
                val candidates = scrobbleArchiveDao.getCandidatesForPromotion(minPlayCount = 1, limit = 500)
                
                for (archive in candidates) {
                    // Check if this track now exists in active set
                    val existingTrack = trackRepository.findByTitleAndArtist(
                        title = archive.trackTitle,
                        artist = archive.artistName
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
                        // This ensures we don't delete archives until we've truly covered them.
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
                
            } catch (e: Exception) {
                Log.e(TAG, "Archive pruning failed", e)
            }
            
            pruned
        }
    }
}
