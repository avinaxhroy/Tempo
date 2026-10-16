package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.data.local.dao.AlbumDao
import me.avinas.tempo.data.local.dao.ArtistAliasDao
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.GenreSource
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.musicbrainz.*
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enriches track metadata and cover art via MusicBrainz and Cover Art Archive APIs.
 */
@Singleton
class MusicBrainzEnrichmentService @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val coverArtApi: CoverArtArchiveApi,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val artistAliasDao: ArtistAliasDao
) {
    companion object {
        private const val TAG = "MBEnrichmentService"
        private const val MIN_SEARCH_SCORE = 80 // Minimum match score to accept
        private const val MIN_TITLE_SIMILARITY = 0.85 // Minimum title similarity to accept a match
        private const val MIN_FUZZY_TITLE_SIMILARITY = 0.70 // For fuzzy searches, require slightly lower but still reasonable match
        private const val MAX_COVER_RELEASE_CANDIDATES = 8
        private const val MUSICBRAINZ_MIN_REQUEST_INTERVAL_NS = 1_000_000_000L
        
        // Pre-compiled regex pattern to avoid repeated native memory allocation
        private val CAA_INDEX_PATTERN = Regex("""^https?://coverartarchive\.org/(release|release-group)/[a-f0-9-]+/?$""")
        
        /**
         * Fix HTTP URLs to use HTTPS for better reliability.
         * Cover Art Archive and Internet Archive use HTTPS for actual images,
         * but the API sometimes returns HTTP URLs that need to redirect.
         * By using HTTPS directly, we avoid redirect issues and improve loading.
         */
        fun fixHttpUrl(url: String?): String? {
            if (url.isNullOrBlank()) return url
            // Convert http:// to https:// for coverartarchive.org and archive.org domains
            return when {
                url.startsWith("http://coverartarchive.org") -> 
                    url.replace("http://coverartarchive.org", "https://coverartarchive.org")
                url.startsWith("http://archive.org") -> 
                    url.replace("http://archive.org", "https://archive.org")
                url.startsWith("http://ia") && url.contains(".us.archive.org") -> 
                    url.replaceFirst("http://", "https://")
                else -> url
            }
        }
        
        /**
         * validate if the URL is a valid image URL and NOT a JSON endpoint.
         * Reject URLs that look like:
         * - https://coverartarchive.org/release/{mbid} (JSON index)
         * - Any URL ending in .json
         */
        private fun isValidAlbumArtUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            
            // Reject .json extension
            if (url.endsWith(".json", ignoreCase = true)) return false
            
            // Reject Cover Art Archive index endpoints (return JSON)
            // Pattern: .../release/{mbid} or .../release-group/{mbid} possibly with trailing slash
            // Valid image URLs usually have /front, /back, or .jpg/.png extension
            if (CAA_INDEX_PATTERN.matches(url)) return false
            
            return true
        }
    }

    private val musicBrainzRequestMutex = Mutex()
    private var lastMusicBrainzRequestStartedAtNanos = 0L

    /**
     * MusicBrainz asks clients to keep requests to one per second. Serialize only
     * the scheduling step so concurrent enrichment jobs still run independently
     * once their request has been assigned a safe start time.
     */
    private suspend fun <T> withMusicBrainzRateLimit(block: suspend () -> T): T {
        musicBrainzRequestMutex.lock()
        try {
            if (lastMusicBrainzRequestStartedAtNanos != 0L) {
                val elapsedNanos = System.nanoTime() - lastMusicBrainzRequestStartedAtNanos
                val remainingNanos = MUSICBRAINZ_MIN_REQUEST_INTERVAL_NS - elapsedNanos
                if (remainingNanos > 0L) {
                    val waitMillis = (remainingNanos + 999_999L) / 1_000_000L
                    delay(waitMillis)
                }
            }
            lastMusicBrainzRequestStartedAtNanos = System.nanoTime()
        } finally {
            musicBrainzRequestMutex.unlock()
        }

        return block()
    }

    /**
     * Result of an enrichment attempt.
     */
    sealed class EnrichmentResult {
        data class Success(val metadata: EnrichedMetadata) : EnrichmentResult()
        data class NotFound(val reason: String) : EnrichmentResult()
        data class Error(val error: String, val retryable: Boolean = true) : EnrichmentResult()
        object AlreadyEnriched : EnrichmentResult()
        object CacheHit : EnrichmentResult()
    }

    data class CoverArtLookupResult(
        val albumArtUrl: String,
        val albumArtUrlSmall: String? = null,
        val albumArtUrlLarge: String? = null,
        val albumTitle: String? = null,
    )

    sealed class CoverArtSearchResult {
        data class Success(val artwork: CoverArtLookupResult) : CoverArtSearchResult()
        object NotFound : CoverArtSearchResult()
        data class Error(val message: String) : CoverArtSearchResult()
    }

    /**
     * Finds Cover Art Archive artwork without mutating enrichment state.
     * Used by the user-driven cover picker so artwork can be compared before saving.
     *
     * Unlike normal background enrichment, this user-facing lookup keeps provider
     * failures distinct from a genuine "no artwork" result.
     */
    suspend fun searchCoverArt(
        track: Track,
        currentMetadata: EnrichedMetadata? = null,
    ): CoverArtSearchResult {
        if (ArtistParser.isUnknownArtist(track.artist)) return CoverArtSearchResult.NotFound

        return try {
            val knownRecordingId = currentMetadata?.musicbrainzRecordingId
            val knownIdentityMatches =
                if (!knownRecordingId.isNullOrBlank()) {
                    val knownRecording = fetchRecordingDetails(knownRecordingId, strict = true)
                    knownRecording != null &&
                        isSafeCoverIdentityMatch(
                            expectedTitle = track.title,
                            expectedArtist = track.artist,
                            candidateTitle = knownRecording.title.orEmpty(),
                            candidateArtists = knownRecording.artistCredit
                                .orEmpty()
                                .mapNotNull { it.name ?: it.artist?.name },
                        )
                } else {
                    false
                }

            val knownReleaseId = currentMetadata?.musicbrainzReleaseId
            if (knownIdentityMatches && !knownReleaseId.isNullOrBlank()) {
                val art = fetchCoverArt(knownReleaseId, strict = true)
                val best = art?.large ?: art?.medium ?: art?.small
                if (art != null && best != null) {
                    return CoverArtSearchResult.Success(
                        CoverArtLookupResult(
                            albumArtUrl = best,
                            albumArtUrlSmall = art.small,
                            albumArtUrlLarge = art.large,
                            albumTitle = currentMetadata?.albumTitle ?: track.album,
                        )
                    )
                }
            }

            val knownReleaseGroupId = currentMetadata?.musicbrainzReleaseGroupId
            if (knownIdentityMatches && !knownReleaseGroupId.isNullOrBlank()) {
                val art = fetchReleaseGroupCoverArt(knownReleaseGroupId, strict = true)
                val best = art?.large ?: art?.medium ?: art?.small
                if (art != null && best != null) {
                    return CoverArtSearchResult.Success(
                        CoverArtLookupResult(
                            albumArtUrl = best,
                            albumArtUrlSmall = art.small,
                            albumArtUrlLarge = art.large,
                            albumTitle = currentMetadata.albumTitle ?: track.album,
                        )
                    )
                }
            }

            val result = searchRecording(
                title = track.title,
                artist = track.artist,
                strictCoverMatching = true,
            )
            val recording = when (result) {
                is SearchResult.Found -> result.recording
                is SearchResult.NotFound -> return CoverArtSearchResult.NotFound
                is SearchResult.Error -> return CoverArtSearchResult.Error(result.message)
            }

            val detailed = fetchRecordingDetails(recording.id, strict = true)
            val releases = (detailed?.releases.orEmpty() + recording.releases.orEmpty())
                .distinctBy { it.id }
            if (releases.isEmpty()) return CoverArtSearchResult.NotFound

            val albumHint = track.album
                ?.takeIf { it.isNotBlank() }
                ?: currentMetadata?.albumTitle?.takeIf { it.isNotBlank() }
            val attemptedReleaseGroups = mutableSetOf<String>()

            for (release in rankMusicBrainzReleases(releases, albumHint).take(MAX_COVER_RELEASE_CANDIDATES)) {
                val releaseArt = fetchCoverArt(release.id, strict = true)
                val releaseGroupId = release.releaseGroup?.id
                val groupArt = if (
                    releaseArt == null &&
                    releaseGroupId != null &&
                    attemptedReleaseGroups.add(releaseGroupId)
                ) {
                    fetchReleaseGroupCoverArt(releaseGroupId, strict = true)
                } else {
                    null
                }
                val art = releaseArt ?: groupArt ?: continue
                val best = art.large ?: art.medium ?: art.small ?: continue

                return CoverArtSearchResult.Success(
                    CoverArtLookupResult(
                        albumArtUrl = best,
                        albumArtUrlSmall = art.small,
                        albumArtUrlLarge = art.large,
                        albumTitle = release.title.ifBlank {
                            currentMetadata?.albumTitle ?: track.album.orEmpty()
                        },
                    )
                )
            }

            CoverArtSearchResult.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoverArtSearchResult.Error(e.message ?: "MusicBrainz artwork lookup failed")
        }
    }

    /**
     * Enrich a track with MusicBrainz metadata.
     * 
     * @param track The track to enrich
     * @param forceRefresh If true, re-fetch even if cache is valid
     * @return EnrichmentResult indicating success, failure, or cache hit
     */
    suspend fun enrichTrack(track: Track, forceRefresh: Boolean = false): EnrichmentResult {
        Log.d(TAG, "Enriching track: '${track.title}' by '${track.artist}'")

        // Skip enrichment if artist is empty/unknown - MediaSession often sends 
        // metadata in multiple callbacks, artist may arrive later
        if (ArtistParser.isUnknownArtist(track.artist)) {
            Log.d(TAG, "Skipping enrichment for track ${track.id}: artist is empty/unknown, waiting for metadata update")
            return EnrichmentResult.NotFound("Artist metadata not yet available")
        }

        // Check existing metadata
        val existingMetadata = enrichedMetadataDao.forTrackSync(track.id)
        
        if (existingMetadata != null && !forceRefresh) {
            if (existingMetadata.isCacheValid()) {
                Log.d(TAG, "Cache hit for track ${track.id}")
                return EnrichmentResult.CacheHit
            }
            
            if (existingMetadata.enrichmentStatus == EnrichmentStatus.NOT_FOUND) {
                Log.d(TAG, "Track previously not found in MusicBrainz")
                return EnrichmentResult.AlreadyEnriched
            }
        }

        // Search MusicBrainz for the track
        val searchResult = searchRecording(track.title, track.artist)
        
        return when (searchResult) {
            is SearchResult.Found -> {
                // Check if we already have metadata for this MusicBrainz ID
                val existingByMbid = enrichedMetadataDao.findByMusicBrainzId(searchResult.recording.id)
                if (existingByMbid != null && existingByMbid.trackId != track.id && existingByMbid.isCacheValid()) {
                    // Reuse existing metadata for different track with same MB ID
                    Log.d(TAG, "Deduplication: reusing metadata from track ${existingByMbid.trackId}")
                    val reusable = existingByMbid.copy(
                        id = existingMetadata?.id ?: 0,
                        trackId = track.id,
                        cacheTimestamp = System.currentTimeMillis()
                    )
                    val copied = preserveUserSelectedArtwork(
                        current = existingMetadata,
                        replacement = reusable,
                    )
                    enrichedMetadataDao.upsertFromAutomaticEnrichment(copied)
                    val persisted = enrichedMetadataDao.forTrackSync(track.id) ?: copied
                    return EnrichmentResult.Success(persisted)
                }

                // Fetch detailed metadata
                fetchAndStoreMetadata(track, searchResult.recording, existingMetadata)
            }
            
            is SearchResult.NotFound -> {
                val metadata = createNotFoundMetadata(track, existingMetadata, searchResult.reason)
                enrichedMetadataDao.upsertFromAutomaticEnrichment(metadata)
                EnrichmentResult.NotFound(searchResult.reason)
            }
            
            is SearchResult.Error -> {
                if (existingMetadata != null) {
                    val updated = existingMetadata.copy(
                        enrichmentStatus = EnrichmentStatus.FAILED,
                        enrichmentError = searchResult.message,
                        retryCount = existingMetadata.retryCount + 1,
                        lastEnrichmentAttempt = System.currentTimeMillis()
                    )
                    enrichedMetadataDao.upsertFromAutomaticEnrichment(updated)
                }
                EnrichmentResult.Error(searchResult.message, searchResult.retryable)
            }
        }
    }

    /**
     * Search for a recording in MusicBrainz.
     * Uses multiple search strategies for better coverage.
     */
    private suspend fun searchRecording(
        title: String,
        artist: String,
        strictCoverMatching: Boolean = false,
    ): SearchResult {
        // Try multiple search strategies
        val searchStrategies = buildSearchStrategies(
            title = title,
            artist = artist,
            preserveExplicitVersion = strictCoverMatching,
        )
        
        for ((index, query) in searchStrategies.withIndex()) {
            Log.d(TAG, "Search query (strategy ${index + 1}/${searchStrategies.size}): $query")
            
            try {
                val response = withMusicBrainzRateLimit {
                    musicBrainzApi.searchRecordings(query = query, limit = 5)
                }
                
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Search failed: ${response.code()} - $errorBody")
                    return SearchResult.Error(
                        "API error: ${response.code()}",
                        retryable = response.code() in listOf(429, 500, 502, 503, 504)
                    )
                }

                val searchResponse = response.body()
                if (searchResponse != null && searchResponse.recordings.isNotEmpty()) {
                    // A provider query can return nearby recordings while our
                    // conservative identity filter rejects all of them. That is
                    // not terminal: continue to the next search strategy.
                    when (
                        val processed = processSearchResponse(
                            searchResponse = searchResponse,
                            title = title,
                            artist = artist,
                            strictCoverMatching = strictCoverMatching,
                        )
                    ) {
                        is SearchResult.Found -> return processed
                        is SearchResult.Error -> return processed
                        is SearchResult.NotFound -> Unit
                    }
                }
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Search exception with strategy ${index + 1}", e)
                if (index == searchStrategies.lastIndex) {
                    return SearchResult.Error(e.message ?: "Unknown error", retryable = true)
                }
            }
        }
        
        Log.d(TAG, "No recordings found for '$title' by '$artist' after trying all strategies")
        return SearchResult.NotFound("No matching recordings found")
    }
    
    /**
     * Build multiple search strategies for MusicBrainz.
     * Uses different query formats to maximize chance of finding a match.
     */
    private fun buildSearchStrategies(
        title: String,
        artist: String,
        preserveExplicitVersion: Boolean = false,
    ): List<String> {
        val strategies = mutableListOf<String>()
        
        val titleVariants =
            if (preserveExplicitVersion) coverSearchTitleVariants(title)
            else listOf(ArtistParser.cleanTrackTitle(title))
        val cleanTitle = titleVariants.last()
        val allArtists = ArtistParser.getAllArtists(artist)
        val primaryArtist = ArtistParser.getPrimaryArtist(artist)
        
        val escapedTitle = escapeLucene(cleanTitle)
        val escapedPrimaryArtist = escapeLucene(primaryArtist)

        // For the picker, ask for the explicit version first. The cleaned title
        // remains the fallback so providers with inconsistent naming still work.
        titleVariants.dropLast(1).forEach { versionedTitle ->
            strategies.add(
                "recording:\"${escapeLucene(versionedTitle)}\" AND artist:\"$escapedPrimaryArtist\""
            )
        }
        
        // Strategy 1: Exact cleaned title + primary artist.
        strategies.add("recording:\"$escapedTitle\" AND artist:\"$escapedPrimaryArtist\"")
        
        // Strategy 2: Try each artist if there are multiple
        for (artistName in allArtists.take(3)) {
            if (artistName != primaryArtist) {
                val escaped = escapeLucene(artistName)
                strategies.add("recording:\"$escapedTitle\" AND artist:\"$escaped\"")
            }
        }
        
        // Strategy 3: Fuzzy title match with primary artist
        if (cleanTitle.length > 3) {
            strategies.add("recording:($escapedTitle~) AND artist:\"$escapedPrimaryArtist\"")
        }
        
        // Strategy 4: Title only (for unique track names)
        if (cleanTitle.length > 5) {
            strategies.add("recording:\"$escapedTitle\"")
        }
        
        return strategies.distinct()
    }
    
    /**
     * Process MusicBrainz search response and find best match.
     * 
     * IMPORTANT: This validates BOTH artist AND title similarity to prevent wrong matches.
     * Previous bug: relaxed matching accepted any recording without title/artist validation,
     * causing "Paracetamol" by Yashraj to match "Paracetamol" by Dr. Bohna (completely wrong).
     */
    private fun processSearchResponse(
        searchResponse: RecordingSearchResponse,
        title: String,
        artist: String,
        strictCoverMatching: Boolean = false,
    ): SearchResult {
        val searchArtists = ArtistParser.getAllArtists(artist)
        val cleanedSearchTitle = ArtistParser.cleanTrackTitle(title)

        fun hasMatchingArtist(recordingArtists: List<String>): Boolean =
            if (strictCoverMatching) {
                recordingArtists.any { recordingArtist ->
                    isSafeCoverArtistMatch(artist, recordingArtist)
                }
            } else {
                recordingArtists.any { recArtist ->
                    searchArtists.any { searchArtist ->
                        ArtistParser.isSameArtist(recArtist, searchArtist)
                    }
                }
            }
        
        // Find best match with score above threshold
        // Validate BOTH artist AND title to prevent wrong matches
        val bestMatch = searchResponse.recordings
            .filter { recording -> 
                val score = recording.score ?: 0
                if (score < MIN_SEARCH_SCORE) return@filter false
                
                // Calculate title similarity. In picker mode the conservative cover
                // matcher is authoritative: it understands equivalent remaster/live
                // suffixes that the legacy Levenshtein score would otherwise reject.
                val recordingTitle = recording.title ?: return@filter false
                val titleSimilarity =
                    if (strictCoverMatching) {
                        if (!isSafeCoverTrackTitleMatch(title, recordingTitle)) {
                            return@filter false
                        }
                        1.0
                    } else {
                        calculateTitleSimilarity(cleanedSearchTitle, recordingTitle)
                    }
                
                // Title must be at least 85% similar
                if (titleSimilarity < MIN_TITLE_SIMILARITY) {
                    Log.v(TAG, "Rejected '${recordingTitle}': title similarity ${(titleSimilarity * 100).toInt()}% < ${(MIN_TITLE_SIMILARITY * 100).toInt()}%")
                    return@filter false
                }
                
                // Verify at least one artist matches
                val recordingArtists = recording.artistCredit?.mapNotNull { it.name ?: it.artist?.name } ?: emptyList()
                
                val hasArtistMatch = hasMatchingArtist(recordingArtists)
                
                if (!hasArtistMatch) {
                    Log.v(TAG, "Rejected '${recordingTitle}' by ${recordingArtists}: no matching artist in $searchArtists")
                }
                
                hasArtistMatch
            }
            .maxByOrNull { it.score ?: 0 }

        if (bestMatch != null) {
            Log.d(TAG, "Found match: '${bestMatch.title}' (score: ${bestMatch.score}, id: ${bestMatch.id})")
            return SearchResult.Found(bestMatch)
        }
        
        // Fallback: Try to find a match with slightly relaxed criteria but STILL validate title and artist
        // This helps when the MusicBrainz score is lower but the match is actually correct
        val relaxedMatch = searchResponse.recordings
            .filter { recording ->
                val score = recording.score ?: 0
                if (score < MIN_SEARCH_SCORE - 15) return@filter false // Allow scores down to 65
                
                val recordingTitle = recording.title ?: return@filter false
                val titleSimilarity =
                    if (strictCoverMatching) {
                        if (!isSafeCoverTrackTitleMatch(title, recordingTitle)) {
                            return@filter false
                        }
                        1.0
                    } else {
                        calculateTitleSimilarity(cleanedSearchTitle, recordingTitle)
                    }
                
                // For relaxed matching, still require reasonable title similarity
                if (titleSimilarity < MIN_FUZZY_TITLE_SIMILARITY) return@filter false
                
                // Must have at least one matching artist
                val recordingArtists = recording.artistCredit?.mapNotNull { it.name ?: it.artist?.name } ?: emptyList()
                hasMatchingArtist(recordingArtists)
            }
            .maxByOrNull { it.score ?: 0 }
        
        if (relaxedMatch != null) {
            val titleSim = calculateTitleSimilarity(cleanedSearchTitle, relaxedMatch.title ?: "")
            Log.d(TAG, "Found relaxed match: '${relaxedMatch.title}' (score: ${relaxedMatch.score}, titleSim: ${(titleSim * 100).toInt()}%, id: ${relaxedMatch.id})")
            return SearchResult.Found(relaxedMatch)
        }
        
        Log.d(TAG, "No recordings with score >= ${MIN_SEARCH_SCORE - 15}, sufficient title similarity, and matching artist for '$title' by '$artist'")
        return SearchResult.NotFound("No high-confidence matches found")
    }
    
    /**
     * Calculate similarity between two titles using Levenshtein distance.
     * Returns a value between 0.0 (completely different) and 1.0 (identical).
     */
    private fun calculateTitleSimilarity(title1: String, title2: String): Double {
        val norm1 = title1.lowercase().trim()
        val norm2 = title2.lowercase().trim()
        
        if (norm1 == norm2) return 1.0
        if (norm1.isEmpty() || norm2.isEmpty()) return 0.0
        
        // Check if one contains the other (e.g., "Paracetamol" vs "Paracetamol (Remix)")
        if (norm1.contains(norm2) || norm2.contains(norm1)) {
            val shorter = minOf(norm1.length, norm2.length)
            val longer = maxOf(norm1.length, norm2.length)
            return shorter.toDouble() / longer // Partial credit based on length difference
        }
        
        // Calculate Levenshtein distance-based similarity
        val distance = levenshteinDistance(norm1, norm2)
        val maxLen = maxOf(norm1.length, norm2.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }
    
    /**
     * Calculate Levenshtein (edit) distance between two strings.
     * This is the minimum number of single-character edits (insertions, deletions, substitutions)
     * required to change one string into the other.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        
        // Use two rows instead of full matrix for memory efficiency
        var prevRow = IntArray(n + 1) { it }
        var currRow = IntArray(n + 1)
        
        for (i in 1..m) {
            currRow[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1,      // insertion
                    prevRow[j] + 1,          // deletion
                    prevRow[j - 1] + cost    // substitution
                )
            }
            // Swap rows
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        
        return prevRow[n]
    }

    /**
     * Build Lucene search query for MusicBrainz.
     * Uses ArtistParser to get the primary artist for focused search.
     */
    private fun buildSearchQuery(title: String, artist: String): String {
        // Clean the title to remove embedded artist info
        val cleanTitle = ArtistParser.cleanTrackTitle(title)
        
        // Get primary artist for search - MusicBrainz works better with single artist
        val primaryArtist = ArtistParser.getPrimaryArtist(artist)
        
        // Escape special Lucene characters
        val escapedTitle = escapeLucene(cleanTitle)
        val escapedArtist = escapeLucene(primaryArtist)
        
        return "recording:\"$escapedTitle\" AND artist:\"$escapedArtist\""
    }

    private fun escapeLucene(s: String): String {
        val specialChars = listOf("+", "-", "&&", "||", "!", "(", ")", "{", "}", "[", "]", "^", "\"", "~", "*", "?", ":", "\\", "/")
        var result = s
        for (char in specialChars) {
            result = result.replace(char, "\\$char")
        }
        return result
    }

    /**
     * Fetch detailed metadata and store in database.
     */
    private suspend fun fetchAndStoreMetadata(
        track: Track,
        recording: MBRecording,
        existingMetadata: EnrichedMetadata?
    ): EnrichmentResult {
        try {
            // Fetch full recording details
            val recordingDetails = fetchRecordingDetails(recording.id)
            
            // Get primary release (album)
            val primaryRelease = recordingDetails?.releases?.firstOrNull()
                ?: recording.releases?.firstOrNull()
            
            // Get artist info - collect all artists for better metadata
            val artistCredits = recordingDetails?.artistCredit ?: recording.artistCredit
            val primaryArtist = artistCredits?.firstOrNull()?.artist
            
            // Build complete artist name with all credited artists
            val allArtistNames = artistCredits?.mapNotNull { credit ->
                val name = credit.name ?: credit.artist?.name
                val joinPhrase = credit.joinphrase ?: ""
                if (name != null) name + joinPhrase else null
            }?.joinToString("")?.trim() ?: primaryArtist?.name
            
            // Fetch album art if release exists - try multiple approaches
            var albumArtUrl: String? = null
            var albumArtUrlSmall: String? = null
            var albumArtUrlLarge: String? = null
            
            if (primaryRelease != null) {
                // Try to fetch cover art regardless of coverArtArchive flags
                // The flags may not always be accurate
                val coverArt = fetchCoverArt(primaryRelease.id)
                if (coverArt != null) {
                    albumArtUrl = coverArt.medium
                    albumArtUrlSmall = coverArt.small
                    albumArtUrlLarge = coverArt.large
                }
                
                // If still no cover art, try release group
                if (albumArtUrl == null && primaryRelease.releaseGroup?.id != null) {
                    val releaseGroupArt = fetchReleaseGroupCoverArt(primaryRelease.releaseGroup.id)
                    if (releaseGroupArt != null) {
                        albumArtUrl = releaseGroupArt.medium
                        albumArtUrlSmall = releaseGroupArt.small
                        albumArtUrlLarge = releaseGroupArt.large
                    }
                }
            }
            
            // Extract tags and genres
            val tags = (recordingDetails?.tags ?: recording.tags)
                ?.sortedByDescending { it.count ?: 0 }
                ?.take(10)
                ?.map { it.name }
                ?: emptyList()
            
            val genres = (recordingDetails?.genres ?: emptyList())
                .map { it.name }
            
            // Parse release year
            val releaseDate = primaryRelease?.date 
                ?: primaryRelease?.releaseGroup?.firstReleaseDate
                ?: recording.firstReleaseDate
            val releaseYear = releaseDate?.take(4)?.toIntOrNull()
            
            // Check if we should update album art based on source priority
            // MusicBrainz (priority 5) should not overwrite Spotify (priority 6)
            val currentSource = existingMetadata?.albumArtSource ?: AlbumArtSource.NONE
            val shouldUpdateArt = albumArtUrl != null && 
                currentSource.shouldBeReplacedBy(AlbumArtSource.MUSICBRAINZ)
            
            // Build metadata - use complete artist name that includes all credited artists
            // Respect existing album art if from higher priority source (e.g., Spotify)
            val metadata = EnrichedMetadata(
                id = existingMetadata?.id ?: 0,
                trackId = track.id,
                musicbrainzRecordingId = recording.id,
                musicbrainzArtistId = primaryArtist?.id,
                musicbrainzReleaseId = primaryRelease?.id,
                musicbrainzReleaseGroupId = primaryRelease?.releaseGroup?.id,
                albumTitle = primaryRelease?.title,
                releaseDate = releaseDate,
                releaseYear = releaseYear,
                releaseType = primaryRelease?.releaseGroup?.primaryType,
                albumArtUrl = if (shouldUpdateArt) albumArtUrl else (existingMetadata?.albumArtUrl ?: albumArtUrl),
                albumArtUrlSmall = if (shouldUpdateArt) albumArtUrlSmall else (existingMetadata?.albumArtUrlSmall ?: albumArtUrlSmall),
                albumArtUrlLarge = if (shouldUpdateArt) albumArtUrlLarge else (existingMetadata?.albumArtUrlLarge ?: albumArtUrlLarge),
                // Set source as MUSICBRAINZ only if we're actually updating the album art
                albumArtSource = if (shouldUpdateArt) AlbumArtSource.MUSICBRAINZ 
                                 else (existingMetadata?.albumArtSource ?: 
                                       if (albumArtUrl != null) AlbumArtSource.MUSICBRAINZ else AlbumArtSource.NONE),
                artistName = allArtistNames ?: primaryArtist?.name ?: recording.artistCredit?.firstOrNull()?.name,
                artistCountry = primaryArtist?.country,
                artistType = primaryArtist?.type,
                trackDurationMs = recording.length,
                isrc = recording.isrcs?.firstOrNull(),
                tags = tags,
                genres = genres,
                recordLabel = primaryRelease?.labelInfo?.firstOrNull()?.label?.name,
                enrichmentStatus = EnrichmentStatus.ENRICHED,
                enrichmentError = null,
                retryCount = 0,
                lastEnrichmentAttempt = System.currentTimeMillis(),
                cacheTimestamp = System.currentTimeMillis()
            )
            
            // Log album art source decision
            if (shouldUpdateArt && existingMetadata != null) {
                Log.d(TAG, "MusicBrainz: Replacing ${existingMetadata.albumArtSource} album art with MUSICBRAINZ source")
            } else if (!shouldUpdateArt && existingMetadata?.albumArtSource != null && albumArtUrl != null) {
                Log.d(TAG, "MusicBrainz: Preserving ${existingMetadata.albumArtSource} album art (higher priority than MUSICBRAINZ)")
            }
            
            enrichedMetadataDao.upsertFromAutomaticEnrichment(metadata)
            Log.i(TAG, "Successfully enriched track ${track.id}: '${track.title}'")
            
            // Smart album association: Create Artist and Album entities if album info is available
            // IMPORTANT: Use track.artist (from music player notification) instead of allArtistNames (from MusicBrainz)
            // This prevents MusicBrainz artist credit concatenation from creating malformed names like "REAL REAL BOSS"
            if (primaryRelease?.title != null && primaryArtist != null) {
                createOrUpdateAlbumAssociation(
                    artistName = track.artist,  // Use original from notification, not MusicBrainz
                    artistMbid = primaryArtist.id,
                    artistCountry = primaryArtist.country,
                    artistType = primaryArtist.type,
                    albumTitle = primaryRelease.title,
                    albumMbid = primaryRelease.id,
                    releaseYear = releaseYear,
                    releaseType = primaryRelease.releaseGroup?.primaryType,
                    albumArtUrl = albumArtUrl ?: albumArtUrlSmall ?: albumArtUrlLarge,
                    genres = genres
                )
            }
            
            // Update the Track entity with MusicBrainz ID and album art URL
            // Only update album art if we actually used MusicBrain art (not if we preserved Spotify art)
            val finalAlbumArtUrl = if (shouldUpdateArt) {
                albumArtUrl ?: albumArtUrlSmall ?: albumArtUrlLarge
            } else {
                // Preserve existing art or use MusicBrainz if no existing art
                track.albumArtUrl ?: (albumArtUrl ?: albumArtUrlSmall ?: albumArtUrlLarge)
            }
            
            val updatedTrack = track.copy(
                musicbrainzId = recording.id,
                album = if (track.album.isNullOrBlank()) primaryRelease?.title else track.album
            )
            trackDao.updatePreservingManualArtwork(updatedTrack)
            if (!finalAlbumArtUrl.isNullOrBlank()) {
                trackDao.updateAutomaticAlbumArtUrl(track.id, finalAlbumArtUrl)
            }
            
            return EnrichmentResult.Success(metadata)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching metadata details", e)
            return EnrichmentResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }
    
    /**
     * Resolves and links [Artist] and [Album] records for a MusicBrainz release,
     * associating artwork and MBIDs.
     */
    private suspend fun createOrUpdateAlbumAssociation(
        artistName: String,
        artistMbid: String,
        artistCountry: String?,
        artistType: String?,
        albumTitle: String,
        albumMbid: String,
        releaseYear: Int?,
        releaseType: String?,
        albumArtUrl: String?,
        genres: List<String>
    ) {
        try {
            // Step 1: Find or create Artist entity
            val primaryArtistName = ArtistParser.getPrimaryArtist(artistName)
            
            // Check alias table first to avoid recreating a merged artist
            val normalizedName = Artist.normalizeName(primaryArtistName)
            val alias = artistAliasDao.findAlias(normalizedName)
            var artist = if (alias != null) {
                artistDao.getArtistById(alias.targetArtistId)
            } else {
                artistDao.getArtistByName(primaryArtistName)
            }
            
            if (artist == null) {
                // Create new artist
                val newArtist = Artist(
                    name = primaryArtistName,
                    imageUrl = null, // Artist images come from Spotify or other sources
                    genres = genres,
                    musicbrainzId = artistMbid,
                    spotifyId = null
                )
                val artistId = artistDao.insert(newArtist)
                artist = newArtist.copy(id = artistId)
                Log.d(TAG, "Created new artist: '$primaryArtistName' (id=$artistId)")
            } else {
                // Update existing artist with MusicBrainz ID and genres if missing
                if (artist.musicbrainzId == null || artist.genres.isEmpty()) {
                    val updatedArtist = artist.copy(
                        musicbrainzId = artist.musicbrainzId ?: artistMbid,
                        genres = if (artist.genres.isEmpty()) genres else artist.genres
                    )
                    artistDao.update(updatedArtist)
                    artist = updatedArtist
                    Log.d(TAG, "Updated artist: '${artist.name}' with MusicBrainz data")
                }
            }
            
            // Step 2: Find or create Album entity
            // First try by MusicBrainz ID for better deduplication
            var album = albumDao.getAlbumByMusicBrainzId(albumMbid)
            
            if (album == null) {
                // Try by title and artist name
                album = albumDao.getAlbumByTitleAndArtist(albumTitle, primaryArtistName)
            }
            
            if (album == null) {
                // Create new album linked to the artist
                val newAlbum = Album(
                    title = albumTitle,
                    artistId = artist.id,
                    releaseYear = releaseYear,
                    artworkUrl = albumArtUrl,
                    musicbrainzId = albumMbid,
                    releaseType = releaseType
                )
                val albumId = albumDao.insert(newAlbum)
                album = newAlbum.copy(id = albumId)
                Log.i(TAG, "Created new album: '$albumTitle' by '$primaryArtistName' (id=$albumId, type=$releaseType)")
            } else {
                // Update album with missing data
                var needsUpdate = false
                var updatedAlbum = album
                
                if (album.artworkUrl == null && albumArtUrl != null) {
                    updatedAlbum = updatedAlbum.copy(artworkUrl = albumArtUrl)
                    needsUpdate = true
                }
                if (album.musicbrainzId == null) {
                    updatedAlbum = updatedAlbum.copy(musicbrainzId = albumMbid)
                    needsUpdate = true
                }
                if (album.releaseYear == null && releaseYear != null) {
                    updatedAlbum = updatedAlbum.copy(releaseYear = releaseYear)
                    needsUpdate = true
                }
                if (album.releaseType == null && releaseType != null) {
                    updatedAlbum = updatedAlbum.copy(releaseType = releaseType)
                    needsUpdate = true
                }
                
                if (needsUpdate) {
                    albumDao.update(updatedAlbum)
                    Log.d(TAG, "Updated album: '$albumTitle' with enriched metadata")
                }
            }
            
        } catch (e: Exception) {
            // Don't fail the enrichment if album association fails
            Log.e(TAG, "Failed to create album association for '$albumTitle'", e)
        }
    }

    /**
     * Fetch detailed recording info including relations.
     */
    private suspend fun fetchRecordingDetails(
        mbid: String,
        strict: Boolean = false,
    ): RecordingLookupResponse? {
        return try {
            val response = withMusicBrainzRateLimit {
                musicBrainzApi.lookupRecording(mbid)
            }
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.w(TAG, "Recording lookup failed: ${response.code()}")
                if (strict && response.code() != 404) {
                    throw IllegalStateException("MusicBrainz recording lookup error: ${response.code()}")
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            Log.e(TAG, "Recording lookup error", e)
            null
        }
    }

    /**
     * Fetch detailed artist info including tags and genres.
     * Used as fallback when recording doesn't have tag data.
     */
    private suspend fun fetchArtistDetails(mbid: String): ArtistLookupResponse? {
        return try {
            val response = withMusicBrainzRateLimit {
                musicBrainzApi.lookupArtist(mbid)
            }
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.w(TAG, "Artist lookup failed: ${response.code()}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Artist lookup error", e)
            null
        }
    }

    /**
     * Fetch cover art from Cover Art Archive for a release.
     */
    private suspend fun fetchCoverArt(
        releaseMbid: String,
        strict: Boolean = false,
    ): CoverArtUrls? {
        return try {
            delay(100) // Small delay for rate limiting
            
            val response = coverArtApi.getReleaseCoverArt(releaseMbid)
            if (response.isSuccessful) {
                val coverArt = response.body()
                val frontImage = coverArt?.images?.find { it.front == true }
                    ?: coverArt?.images?.firstOrNull()
                
                frontImage?.let { image ->
                    // Try to get the best available URL for each size
                    // Fix HTTP URLs to HTTPS for better reliability
                    val urls = CoverArtUrls(
                        small = fixHttpUrl(image.thumbnails?.small 
                            ?: image.thumbnails?.small2
                            ?: "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-250"),
                        medium = fixHttpUrl(image.thumbnails?.medium 
                            ?: image.image 
                            ?: "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-500"),
                        large = fixHttpUrl(image.thumbnails?.large 
                            ?: image.thumbnails?.large2
                            ?: "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-1200")
                    )
                    
                    // Validate album art URL scheme and format
                    if (!isValidAlbumArtUrl(urls.medium)) {
                         Log.w(TAG, "Ignored invalid album art URL: ${urls.medium}")
                         return null
                    }
                    return urls
                }
            } else if (response.code() == 404) {
                // No cover art available for this release
                Log.d(TAG, "No cover art found for release $releaseMbid")
                null
            } else {
                Log.w(TAG, "Cover art API error ${response.code()} for release $releaseMbid")
                if (strict) {
                    throw IllegalStateException("Cover Art Archive error: ${response.code()}")
                }
                // Background enrichment keeps the historical direct-URL fallback.
                CoverArtUrls(
                    small = "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-250",
                    medium = "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-500",
                    large = "${CoverArtArchiveApi.BASE_URL}release/$releaseMbid/front-1200"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            Log.w(TAG, "Cover art fetch error for release $releaseMbid: ${e.message}")
            null
        }
    }

    /**
     * Fetch cover art from Cover Art Archive for a release group.
     * This is a fallback when the specific release has no cover art.
     */
    private suspend fun fetchReleaseGroupCoverArt(
        releaseGroupMbid: String,
        strict: Boolean = false,
    ): CoverArtUrls? {
        return try {
            delay(100) // Small delay for rate limiting
            
            Log.d(TAG, "Trying release group cover art for: $releaseGroupMbid")
            
            // Actually check if the release group has cover art
            val response = coverArtApi.getReleaseGroupCoverArt(releaseGroupMbid)
            if (response.isSuccessful) {
                val coverArt = response.body()
                val frontImage = coverArt?.images?.find { it.front == true }
                    ?: coverArt?.images?.firstOrNull()
                
                frontImage?.let { image ->
                    Log.d(TAG, "Found release group cover art for: $releaseGroupMbid")
                    // Fix HTTP URLs to HTTPS for better reliability
                    val urls = CoverArtUrls(
                        small = fixHttpUrl(image.thumbnails?.small 
                            ?: image.thumbnails?.small2
                            ?: "${CoverArtArchiveApi.BASE_URL}release-group/$releaseGroupMbid/front-250"),
                        medium = fixHttpUrl(image.thumbnails?.medium 
                            ?: image.image 
                            ?: "${CoverArtArchiveApi.BASE_URL}release-group/$releaseGroupMbid/front-500"),
                        large = fixHttpUrl(image.thumbnails?.large 
                            ?: image.thumbnails?.large2
                            ?: "${CoverArtArchiveApi.BASE_URL}release-group/$releaseGroupMbid/front-1200")
                    )
                    
                    if (!isValidAlbumArtUrl(urls.medium)) {
                         Log.w(TAG, "Ignored invalid release group art URL: ${urls.medium}")
                         return null
                    }
                    return urls
                }
            } else if (response.code() == 404) {
                Log.d(TAG, "No cover art found for release group $releaseGroupMbid")
                null
            } else {
                Log.w(TAG, "Cover art API error ${response.code()} for release group $releaseGroupMbid")
                if (strict) {
                    throw IllegalStateException("Cover Art Archive release-group error: ${response.code()}")
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            Log.e(TAG, "Release group cover art fetch error for $releaseGroupMbid", e)
            null
        }
    }

    private fun createNotFoundMetadata(
        track: Track,
        existingMetadata: EnrichedMetadata?,
        reason: String
    ): EnrichedMetadata {
        // Preserve any data already gathered by other sources (iTunes art, genres,
        // audio features...). Building a fresh row here used to wipe that data when
        // MusicBrainz alone could not find the track.
        val base = existingMetadata ?: EnrichedMetadata(trackId = track.id)
        return base.copy(
            enrichmentStatus = EnrichmentStatus.NOT_FOUND,
            enrichmentError = reason,
            lastEnrichmentAttempt = System.currentTimeMillis(),
            cacheTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Create initial pending metadata entry for a track.
     */
    suspend fun createPendingMetadata(trackId: Long) {
        val existing = enrichedMetadataDao.forTrackSync(trackId)
        if (existing == null) {
            val pending = EnrichedMetadata(
                trackId = trackId,
                enrichmentStatus = EnrichmentStatus.PENDING,
                cacheTimestamp = System.currentTimeMillis()
            )
            enrichedMetadataDao.upsertFromAutomaticEnrichment(pending)
        }
    }

    /**
     * Mark a track for manual re-enrichment.
     */
    suspend fun requestReEnrichment(trackId: Long) {
        enrichedMetadataDao.markForReEnrichment(trackId)
    }

    /**
     * Get enrichment statistics.
     */
    suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int> {
        return enrichedMetadataDao.getEnrichmentStats()
            .associate { it.status to it.count }
    }

    /**
     * Supplement existing metadata with MusicBrainz data.
     * 
     * This method is called when Spotify has provided basic metadata (album, artist, artwork)
     * but could not provide additional data (e.g., audio features API is deprecated).
     * It fills in ONLY the missing fields from MusicBrainz without overwriting existing Spotify data.
     * 
     * Specifically, this fetches:
     * - Genres and tags (Spotify audio features are unavailable, but MB has genre data)
     * - MusicBrainz IDs (for cross-referencing)
     * - Record label info
     * - Artist country/type (if not already present)
     * 
     * @param track The track to supplement
     * @param existingMetadata The existing metadata with Spotify data
     * @return SupplementResult indicating what was added
     */
    suspend fun supplementMetadata(
        track: Track,
        existingMetadata: EnrichedMetadata
    ): SupplementResult {
        Log.d(TAG, "Supplementing metadata for track ${track.id}: '${track.title}' by '${track.artist}'")

        // Skip if artist is unknown
        if (ArtistParser.isUnknownArtist(track.artist)) {
            Log.d(TAG, "Skipping supplement for track ${track.id}: artist is empty/unknown")
            return SupplementResult.Skipped("Artist metadata not yet available")
        }

        // Check genre source priority - MusicBrainz can replace lower priority sources
        val currentGenreSource = existingMetadata.genreSource
        val canReplaceGenres = currentGenreSource.shouldBeReplacedBy(GenreSource.MUSICBRAINZ)
        
        // Skip if we already have high-priority genres/tags (MusicBrainz or Last.fm)
        if (existingMetadata.genres.isNotEmpty() && !canReplaceGenres) {
            Log.d(TAG, "Track ${track.id} already has ${currentGenreSource.name} genres (priority: ${currentGenreSource.priority}), skipping supplement")
            return SupplementResult.AlreadyHasData
        }
        
        // We'll try MusicBrainz if: no genres, or genres are from lower priority source (SPOTIFY_ARTIST)
        if (existingMetadata.genres.isNotEmpty() && canReplaceGenres) {
            Log.d(TAG, "Track ${track.id} has ${currentGenreSource.name} genres (priority: ${currentGenreSource.priority}), trying MusicBrainz for better track-specific genres")
        }

        // Search MusicBrainz for the track
        val searchResult = searchRecording(track.title, track.artist)

        return when (searchResult) {
            is SearchResult.Found -> {
                supplementFromRecording(track, existingMetadata, searchResult.recording)
            }
            is SearchResult.NotFound -> {
                Log.d(TAG, "Track ${track.id} not found on MusicBrainz for supplement")
                SupplementResult.NotFound(searchResult.reason)
            }
            is SearchResult.Error -> {
                Log.w(TAG, "Error searching MusicBrainz for supplement: ${searchResult.message}")
                SupplementResult.Error(searchResult.message)
            }
        }
    }

    /**
     * Supplement metadata from a MusicBrainz recording without overwriting Spotify data.
     */
    private suspend fun supplementFromRecording(
        track: Track,
        existingMetadata: EnrichedMetadata,
        recording: MBRecording
    ): SupplementResult {
        try {
            // Fetch full recording details for genres/tags
            val recordingDetails = fetchRecordingDetails(recording.id)
            
            // Extract tags and genres from recording
            var tags = (recordingDetails?.tags ?: recording.tags)
                ?.sortedByDescending { it.count ?: 0 }
                ?.take(10)
                ?.map { it.name }
                ?: emptyList()

            var genres = (recordingDetails?.genres ?: emptyList())
                .map { it.name }
            
            // If recording has no tags/genres, try to get them from the artist
            // (MusicBrainz often has better tag data at artist level)
            val artistCredits = recordingDetails?.artistCredit ?: recording.artistCredit
            val primaryArtist = artistCredits?.firstOrNull()?.artist
            
            if (tags.isEmpty() && genres.isEmpty() && primaryArtist?.id != null) {
                Log.d(TAG, "Recording has no tags/genres, trying artist-level data for '${primaryArtist.name}'")
                val artistDetails = fetchArtistDetails(primaryArtist.id)
                if (artistDetails != null) {
                    tags = artistDetails.tags
                        ?.sortedByDescending { it.count ?: 0 }
                        ?.take(10)
                        ?.map { it.name }
                        ?: emptyList()
                    genres = (artistDetails.genres ?: emptyList()).map { it.name }
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        Log.d(TAG, "Found artist-level data: ${tags.size} tags, ${genres.size} genres")
                    }
                }
            }

            // Get release info for additional data
            val primaryRelease = recordingDetails?.releases?.firstOrNull()
                ?: recording.releases?.firstOrNull()

            // Determine if MusicBrainz genres should replace existing
            // Replace if: (1) no existing genres, OR (2) existing are from lower priority source
            val canReplaceGenres = existingMetadata.genres.isEmpty() || 
                existingMetadata.genreSource.shouldBeReplacedBy(GenreSource.MUSICBRAINZ)
            val newGenres = if (canReplaceGenres && genres.isNotEmpty()) genres else existingMetadata.genres
            val newGenreSource = if (canReplaceGenres && genres.isNotEmpty()) {
                GenreSource.MUSICBRAINZ
            } else {
                existingMetadata.genreSource
            }
            
            // Log if we're replacing Spotify artist genres
            if (canReplaceGenres && genres.isNotEmpty() && existingMetadata.genres.isNotEmpty()) {
                Log.i(TAG, "Replacing ${existingMetadata.genreSource.name} genres with MusicBrainz track-specific genres for track ${track.id}")
            }

            // Update metadata - preserve Spotify data but replace/add MusicBrainz data
            val supplementedMetadata = existingMetadata.copy(
                // MusicBrainz IDs (if not already set)
                musicbrainzRecordingId = existingMetadata.musicbrainzRecordingId ?: recording.id,
                musicbrainzArtistId = existingMetadata.musicbrainzArtistId ?: primaryArtist?.id,
                musicbrainzReleaseId = existingMetadata.musicbrainzReleaseId ?: primaryRelease?.id,
                musicbrainzReleaseGroupId = existingMetadata.musicbrainzReleaseGroupId ?: primaryRelease?.releaseGroup?.id,
                
                // Tags and genres - use priority-based replacement
                tags = if (existingMetadata.tags.isEmpty()) tags else existingMetadata.tags,
                genres = newGenres,
                genreSource = newGenreSource,
                
                // Record label (if not already set)
                recordLabel = existingMetadata.recordLabel ?: primaryRelease?.labelInfo?.firstOrNull()?.label?.name,
                
                // Artist info (only if missing)
                artistCountry = existingMetadata.artistCountry ?: primaryArtist?.country,
                artistType = existingMetadata.artistType ?: primaryArtist?.type,
                
                // Release info (only if missing from Spotify)
                releaseType = existingMetadata.releaseType ?: primaryRelease?.releaseGroup?.primaryType,
                
                // Keep Spotify data intact:
                // - albumTitle, albumArtUrl*, releaseYear, isrc are preserved from Spotify
                // - spotifyId, spotifyArtistId*, spotifyVerifiedArtist are preserved
                // - audioFeaturesJson is preserved (even if null)
                // - enrichmentStatus, spotifyEnrichmentStatus are preserved
                
                cacheTimestamp = System.currentTimeMillis()
            )

            enrichedMetadataDao.upsertFromAutomaticEnrichment(supplementedMetadata)
            
            // Only log if we actually added something useful
            val tagsCount = tags.size
            val genresCount = genres.size
            val addedLabel = existingMetadata.recordLabel == null && supplementedMetadata.recordLabel != null
            
            if (tagsCount > 0 || genresCount > 0 || addedLabel) {
                val parts = mutableListOf<String>()
                if (tagsCount > 0) parts.add("$tagsCount tags")
                if (genresCount > 0) parts.add("$genresCount genres")
                if (addedLabel) parts.add("label")
                Log.i(TAG, "Supplemented track ${track.id} with MusicBrainz: ${parts.joinToString(", ")}")
            }
            // Don't log when no data found - EnrichmentWorker will handle this case
            
            return SupplementResult.Success(
                tagsAdded = tagsCount,
                genresAdded = genresCount,
                recordLabel = supplementedMetadata.recordLabel
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error supplementing metadata from MusicBrainz", e)
            return SupplementResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Result of a supplement attempt.
     */
    sealed class SupplementResult {
        data class Success(
            val tagsAdded: Int,
            val genresAdded: Int,
            val recordLabel: String?
        ) : SupplementResult()
        object AlreadyHasData : SupplementResult()
        data class Skipped(val reason: String) : SupplementResult()
        data class NotFound(val reason: String) : SupplementResult()
        data class Error(val message: String) : SupplementResult()
    }

    // Internal types
    private sealed class SearchResult {
        data class Found(val recording: MBRecording) : SearchResult()
        data class NotFound(val reason: String) : SearchResult()
        data class Error(val message: String, val retryable: Boolean) : SearchResult()
    }

    private data class CoverArtUrls(
        val small: String?,
        val medium: String?,
        val large: String?
    )
}


/**
 * Rank MusicBrainz releases for cover selection instead of trusting API order.
 *
 * An exact known album title is the strongest signal. Without an album hint we
 * prefer official releases with actual front artwork and avoid compilations/live
 * releases when an ordinary album/single release is available.
 */
internal fun rankMusicBrainzReleases(
    releases: List<MBRelease>,
    albumHint: String?,
): List<MBRelease> =
    releases.sortedWith(
        compareByDescending<MBRelease> { musicBrainzReleaseScore(it, albumHint) }
            .thenBy { it.date ?: it.releaseGroup?.firstReleaseDate ?: "9999-99-99" }
            .thenBy { it.id }
    )

internal fun musicBrainzReleaseScore(
    release: MBRelease,
    albumHint: String?,
): Int {
    val normalizedHint = albumHint
        ?.let(ArtistParser::normalizeForSearch)
        ?.takeIf { it.isNotBlank() }
    val normalizedRelease = ArtistParser.normalizeForSearch(release.title)
    val normalizedGroup = release.releaseGroup?.title
        ?.let(ArtistParser::normalizeForSearch)
        .orEmpty()

    var score = 0
    var exactAlbumMatch = false

    if (normalizedHint != null) {
        when {
            normalizedRelease == normalizedHint -> {
                score += 240
                exactAlbumMatch = true
            }
            normalizedGroup == normalizedHint -> {
                score += 220
                exactAlbumMatch = true
            }
            minOf(normalizedRelease.length, normalizedHint.length) >= 4 &&
                (normalizedRelease.contains(normalizedHint) || normalizedHint.contains(normalizedRelease)) ->
                score += 90
            minOf(normalizedGroup.length, normalizedHint.length) >= 4 &&
                (normalizedGroup.contains(normalizedHint) || normalizedHint.contains(normalizedGroup)) ->
                score += 80
        }
    }

    when {
        release.coverArtArchive?.front == true -> score += 70
        release.coverArtArchive?.artwork == true -> score += 35
    }

    if (release.status.equals("Official", ignoreCase = true)) score += 20

    when (release.releaseGroup?.primaryType?.lowercase()) {
        "album" -> score += 12
        "ep" -> score += 8
        "single" -> score += 6
    }

    if (!exactAlbumMatch) {
        val secondaryTypes = release.releaseGroup?.secondaryTypes.orEmpty()
        if (secondaryTypes.any { it.equals("Compilation", ignoreCase = true) }) score -= 60
        if (secondaryTypes.any { it.equals("Live", ignoreCase = true) }) score -= 30
    }

    return score
}


/**
 * Automatic metadata replacement must never discard artwork explicitly chosen by
 * the user. Used by MusicBrainz's cross-track metadata reuse path, which otherwise
 * replaces the entire enriched_metadata row in one operation.
 */
internal fun preserveUserSelectedArtwork(
    current: EnrichedMetadata?,
    replacement: EnrichedMetadata,
): EnrichedMetadata {
    val manual = current?.takeIf {
        it.albumArtSource == AlbumArtSource.USER_SELECTED &&
            !it.albumArtUrl.isNullOrBlank()
    }

    if (manual != null) {
        val selectedUrl = manual.albumArtUrl!!
        return replacement.copy(
            albumArtUrl = selectedUrl,
            albumArtUrlSmall = manual.albumArtUrlSmall ?: selectedUrl,
            albumArtUrlLarge = manual.albumArtUrlLarge ?: selectedUrl,
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
    }

    // A manual cover belongs only to the donor track. Cross-track metadata reuse
    // may copy ordinary automatic artwork, but must never transfer another track's
    // explicit user choice. Preserve the target's own automatic artwork when it
    // has one; otherwise leave artwork empty so the normal provider chain continues.
    if (replacement.albumArtSource == AlbumArtSource.USER_SELECTED ||
        replacement.albumArtSource == AlbumArtSource.USER_RESET
    ) {
        return replacement.copy(
            albumArtUrl = current?.albumArtUrl,
            albumArtUrlSmall = current?.albumArtUrlSmall,
            albumArtUrlLarge = current?.albumArtUrlLarge,
            albumArtSource = current?.albumArtSource ?: AlbumArtSource.NONE,
        )
    }

    return replacement
}
