package me.avinas.tempo.data.enrichment

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.remote.itunes.iTunesApi
import me.avinas.tempo.data.remote.itunes.iTunesResult as AppleMusicResult
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Service for fetching metadata from iTunes Search API.
 * 
 * iTunes is excellent for mainstream commercial music with high-quality
 * album artwork. No authentication required!
 * 
 * Used as a fallback when MusicBrainz and Last.fm don't have cover art.
 */
@Singleton
class ITunesEnrichmentService @Inject constructor(
    private val iTunesApi: iTunesApi,
    @param:ApplicationContext private val context: Context,
    @param:Named("itunes") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "iTunesEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 500L // 500ms between requests (iTunes has relaxed rate limits)
        
        // === Multiple regex strategies for extracting artist images from Apple Music HTML ===
        
        // AMCArtistImages CDN URL - Apple's dedicated artist photo path (most reliable signal)
        // Note: Apple Music sometimes hosts actual artist images under /Features or /Music paths
        private val ARTIST_IMAGE_CDN_REGEX = Regex("""(https?://is\d+-ssl\.mzstatic\.com/image/thumb/(?:AMCArtistImages|Features|Music)[^"'\s)}<]+)""")
        
        // --background-image CSS variable in artist-detail-header (contains artist banner/photo)
        private val BACKGROUND_IMAGE_REGEX = Regex("""--background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        
        // Standard og:image meta tag
        private val OG_IMAGE_REGEX = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val OG_IMAGE_REGEX_ALT = Regex("""<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        
        // twitter:image meta tag
        private val TWITTER_IMAGE_REGEX = Regex("""<meta\s+(?:name|property)=["']twitter:image["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TWITTER_IMAGE_REGEX_ALT = Regex("""<meta[^>]*content=["']([^"']*mzstatic\.com[^"']+)["'][^>]*(?:name|property)=["']twitter:image["']""", RegexOption.IGNORE_CASE)
        
        // JSON-LD structured data with artist image
        private val JSON_LD_IMAGE_REGEX = Regex(""""image"\s*:\s*"(https?://[^"]*mzstatic\.com/image/thumb/[^"]+)"""")
        
        // Any mzstatic artist-related URL (Features/atv-catalog patterns, Music thumbnails)
        private val MZSTATIC_ARTIST_REGEX = Regex("""(https?://is\d+-ssl\.mzstatic\.com/image/thumb/(?:Features|Music)[^"'\s)}<]*?/source/\d+x\d+[^"'\s)}<]*)""")
        
        // Generic mzstatic URL (broadest fallback)
        private val MZSTATIC_GENERIC_REGEX = Regex("""(https?://is\d+-ssl\.mzstatic\.com/image/thumb/[^"'\s)}<]+)""")
        
        // Social media bot User-Agent - Apple Music returns SSR HTML with og:image for crawlers
        private const val BOT_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }
    
    /**
     * Get device country code from system locale.
     * Falls back to US if unavailable.
     */
    private fun getDeviceCountryCode(): String {
        return try {
            val country = context.resources.configuration.locales[0]?.country
                ?: Locale.getDefault().country
            if (country.isNotBlank()) country.lowercase() else "us"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device country, falling back to US", e)
            "us"
        }
    }

    /**
     * Result of iTunes search attempt.
     */
    sealed class iTunesResult {
        data class Success(
            val albumArtUrl: String,
            val albumArtUrlSmall: String? = null,
            val albumArtUrlLarge: String? = null,
            val albumTitle: String? = null,
            val genre: String? = null,
            val releaseYear: Int? = null,
            val releaseDateFull: String? = null, // ISO 8601 format
            val durationMs: Long? = null,
            val previewUrl: String? = null,
            val appleMusicUrl: String? = null, // Track view URL
            val artistId: Long? = null, // For fetching artist image
            val artistCountry: String? = null
        ) : iTunesResult()
        
        object NotFound : iTunesResult()
        data class Error(val message: String) : iTunesResult()
    }

    /**
     * Search for album artwork using iTunes Search API.
     * 
     * @param artist Artist name
     * @param album Album name (optional but recommended)
     * @param track Track name (optional, for better matching)
     * @return iTunesResult with artwork URLs and metadata
     */
    /**
     * Search for album artwork using iTunes Search API.
     * 
     * Uses multiple search strategies to maximize match rate:
     * 1. Primary Artist + Album (most precise)
     * 2. Primary Artist + Track
     * 3. Secondary Artists + Track (if applicable)
     * 4. Track Name Only (fallback, filtered by artist)
     * 
     * @param artist Artist name
     * @param album Album name (optional but recommended)
     * @param track Track name (optional, for better matching)
     * @return iTunesResult with artwork URLs and metadata
     */
    suspend fun searchAlbumArt(
        artist: String,
        album: String? = null,
        track: String? = null
    ): iTunesResult {
        if (ArtistParser.isUnknownArtist(artist)) {
            Log.d(TAG, "Skipping iTunes search: artist is unknown")
            return iTunesResult.NotFound
        }

        // Build search strategies
        val searchStrategies = buildSearchStrategies(artist, album, track)
        
        // Track unique queries to avoid duplicates
        val uniqueQueries = searchStrategies.distinct()
        
        for ((index, query) in uniqueQueries.withIndex()) {
            Log.d(TAG, "Searching iTunes (strategy ${index + 1}/${uniqueQueries.size}): $query")

            try {
                if (index > 0) {
                    delay(RATE_LIMIT_DELAY_MS)
                }
                
                // Determine entity type based on whether we are looking for a track
                val entity = if (track != null) "song" else "album"
                
                val response = iTunesApi.search(
                    term = query,
                    entity = entity,
                    limit = 5, // Keep limit low since we have precise queries
                    country = getDeviceCountryCode()
                )

                if (!response.isSuccessful) {
                    Log.e(TAG, "iTunes search failed for '$query': ${response.code()}")
                    continue // Try next strategy
                }

                val searchResponse = response.body()
                val results = searchResponse?.results ?: emptyList()
                
                if (results.isEmpty()) {
                    continue // Try next strategy
                }

                // Check for match using relaxed artist validation
                val cleanTrack = if (track != null) ArtistParser.cleanTrackTitle(track) else null
                
                val bestMatch = results.find { result ->
                    val resultArtist = result.artistName ?: ""
                    
                    // Crucial: Limit matches to correct artist using relaxed checking
                    // Check if ANY artist in the result matches ANY artist in our input
                    val isArtistMatch = ArtistParser.hasAnyMatchingArtist(resultArtist, artist)
                    
                    if (!isArtistMatch) return@find false

                    // If searching for a track, validate track title
                    if (track != null && cleanTrack != null) {
                        (result.trackName?.contains(cleanTrack, ignoreCase = true) == true || 
                         result.trackCensoredName?.contains(cleanTrack, ignoreCase = true) == true ||
                         cleanTrack.contains(result.trackName ?: "", ignoreCase = true) ||
                         cleanTrack.contains(result.trackCensoredName ?: "", ignoreCase = true))
                    } else if (album != null) {
                        // If searching for album, validate album title
                        (result.collectionName?.contains(album, ignoreCase = true) == true ||
                         result.collectionCensoredName?.contains(album, ignoreCase = true) == true)
                    } else {
                        // If just searching by artist (no album/track provided), take the first artist match
                        true
                    }
                }

                if (bestMatch != null) {
                    val artworkUrl = bestMatch.getBestArtworkUrl()
                    if (artworkUrl != null) {
                        Log.i(TAG, "Found iTunes match via strategy ${index + 1}: '${bestMatch.trackName ?: bestMatch.collectionName}' by '${bestMatch.artistName}'")
                        
                        return iTunesResult.Success(
                            albumArtUrl = artworkUrl,
                            albumArtUrlSmall = bestMatch.getSmallArtworkUrl(),
                            albumArtUrlLarge = bestMatch.getBestArtworkUrl(),
                            albumTitle = bestMatch.collectionName,
                            genre = bestMatch.primaryGenreName,
                            releaseYear = bestMatch.getReleaseYear(),
                            releaseDateFull = bestMatch.releaseDate?.take(10),
                            durationMs = bestMatch.getDurationMs(),
                            previewUrl = bestMatch.previewUrl,
                            appleMusicUrl = bestMatch.trackViewUrl ?: bestMatch.collectionViewUrl,
                            artistId = bestMatch.artistId,
                            artistCountry = bestMatch.country
                        )
                    }
                } else {
                    Log.d(TAG, "Results found for '$query' but none matched artist '$artist' or title")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error searching iTunes with query '$query'", e)
                // Continue to next strategy on error
            }
        }
        
        Log.d(TAG, "iTunes search exhausted all strategies for '$artist' - '$track'")
        return iTunesResult.NotFound
    }
    
    /**
     * Search for an artist by name and fetch their image.
     * Iterates through all individual artists found in the artist string until one matches.
     *
     * When multiple artists share the same name, [trackHint] and [albumHint] are used to
     * disambiguate by checking which artist has the matching track in their catalog.
     *
     * @param artistName The raw artist string (may contain multiple artists)
     * @param trackHint  Track title being enriched — used to pick the right artist when names clash
     * @param albumHint  Album name being enriched — secondary disambiguation signal
     * @return The artist image URL or null if not found
     */
    suspend fun searchAndFetchArtistImage(
        artistName: String,
        trackHint: String? = null,
        albumHint: String? = null
    ): String? {
        if (ArtistParser.isUnknownArtist(artistName)) return null
        
        // Split into individual artists to try each one
        val allArtists = ArtistParser.getAllArtists(artistName)
        
        Log.d(TAG, "Searching iTunes for artist image. Raw: '$artistName', Parsed: $allArtists")
        
        for (individualArtist in allArtists) {
            val cleanName = ArtistParser.normalizeArtistName(individualArtist)
            if (cleanName.isBlank()) continue
            
            Log.d(TAG, "Trying individual artist: '$cleanName'")
            
            try {
                delay(RATE_LIMIT_DELAY_MS)
                
                val response = iTunesApi.search(
                    term = cleanName,
                    entity = "musicArtist",
                    limit = 5,
                    country = getDeviceCountryCode()
                )
                
                if (!response.isSuccessful) {
                    Log.w(TAG, "iTunes artist search failed for '$cleanName': ${response.code()}")
                    continue // Try next artist
                }
                
                val searchResponse = response.body() ?: continue
                
                // Use STRICT matching to prevent wrong artist (e.g., Shreya Ghoshal → Atif Aslam)
                val matchingArtists = searchResponse.results.filter { result ->
                    result.artistName != null && ArtistParser.isStrictSameArtist(result.artistName, cleanName)
                }
                
                if (matchingArtists.isEmpty()) {
                    Log.d(TAG, "No exact artist match found for: '$cleanName'")
                    continue
                }
                
                // When multiple artists share the same name, use track/album context to disambiguate
                val bestArtist = if (matchingArtists.size > 1 && (trackHint != null || albumHint != null)) {
                    Log.d(TAG, "Found ${matchingArtists.size} artists named '$cleanName' — disambiguating via track context")
                    disambiguateArtistByTrack(matchingArtists, trackHint, albumHint)
                        ?: matchingArtists.maxByOrNull { (if (it.isExactArtistMatch(cleanName)) 10000 else 0) + it.getArtistScore() }
                } else {
                    matchingArtists.maxByOrNull { (if (it.isExactArtistMatch(cleanName)) 10000 else 0) + it.getArtistScore() }
                }
                
                if (bestArtist?.artistId != null) {
                    val score = bestArtist.getArtistScore()
                    val exactMatch = bestArtist.isExactArtistMatch(cleanName)
                    Log.d(TAG, "Found artist ID for '$cleanName': ${bestArtist.artistId} (score: $score, exact: $exactMatch, tracks: ${bestArtist.trackCount})")
                    
                    val imageUrl = fetchArtistImage(bestArtist.artistId, artistPhotoOnly = true)
                    if (imageUrl != null) return imageUrl
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error searching artist '$cleanName' on iTunes", e)
            }
        }
        
        Log.d(TAG, "No artist image found for any artist in: '$artistName'")
        return null
    }

    /**
     * Search for a SINGLE specific artist by name and fetch their image.
     * Unlike searchAndFetchArtistImage(), this does NOT split through getAllArtists()
     * — it treats the input as a single clean artist name.
     *
     * Used by refreshArtistImageDirectly() where the artist name is already
     * a clean single name from the artists table.
     *
     * @param artistName The clean, single artist name
     * @param trackHint  Optional track title — used to disambiguate same-name artists
     * @param albumHint  Optional album name — secondary disambiguation signal
     * @return The artist image URL or null if not found
     */
    suspend fun searchAndFetchSingleArtistImage(
        artistName: String,
        trackHint: String? = null,
        albumHint: String? = null
    ): String? {
        if (ArtistParser.isUnknownArtist(artistName)) return null

        val cleanName = ArtistParser.normalizeArtistName(artistName)
        if (cleanName.isBlank()) return null

        Log.d(TAG, "Searching iTunes for single artist image: '$cleanName'")

        try {
            delay(RATE_LIMIT_DELAY_MS)

            val response = iTunesApi.search(
                term = cleanName,
                entity = "musicArtist",
                limit = 5,
                country = getDeviceCountryCode()
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "iTunes artist search failed for '$cleanName': ${response.code()}")
                return null
            }

            val searchResponse = response.body() ?: return null

            // Use STRICT matching only — exact name match (no fuzzy/Jaccard)
            val matchingArtists = searchResponse.results.filter { result ->
                result.artistName != null && ArtistParser.isStrictSameArtist(result.artistName, cleanName)
            }

            if (matchingArtists.isEmpty()) {
                Log.d(TAG, "No strict artist match found for: '$cleanName'")
                return null
            }

            // When multiple artists share the same name, use track/album context to disambiguate
            val bestArtist = if (matchingArtists.size > 1 && (trackHint != null || albumHint != null)) {
                Log.d(TAG, "Found ${matchingArtists.size} artists named '$cleanName' — disambiguating via track context")
                disambiguateArtistByTrack(matchingArtists, trackHint, albumHint)
                    ?: matchingArtists.maxByOrNull { (if (it.isExactArtistMatch(cleanName)) 10000 else 0) + it.getArtistScore() }
            } else {
                matchingArtists.maxByOrNull { (if (it.isExactArtistMatch(cleanName)) 10000 else 0) + it.getArtistScore() }
            }

            if (bestArtist?.artistId != null) {
                val score = bestArtist.getArtistScore()
                val exactMatch = bestArtist.isExactArtistMatch(cleanName)
                Log.d(TAG, "Found artist ID for '$cleanName': ${bestArtist.artistId} (score: $score, exact: $exactMatch)")

                val imageUrl = fetchArtistImage(bestArtist.artistId, artistPhotoOnly = true)
                if (imageUrl != null) return imageUrl
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error searching single artist '$cleanName' on iTunes", e)
        }

        Log.d(TAG, "No artist image found for single artist: '$artistName'")
        return null
    }

    /**
     * Disambiguate between multiple artists sharing the same name by checking
     * which one has the given track/album in their catalog.
     *
     * For each candidate artist, fetches their top songs and scores by:
     * - Track title match (strong signal, +100)
     * - Album name match (medium signal, +50)
     *
     * Returns the best-matching artist, or null if no candidate has a matching
     * track/album (caller should fall back to popularity-based ranking).
     */
    private suspend fun disambiguateArtistByTrack(
        candidates: List<AppleMusicResult>,
        trackHint: String?,
        albumHint: String?
    ): AppleMusicResult? {
        data class ScoredCandidate(val artist: AppleMusicResult, val score: Int)

        val cleanTrack = trackHint?.let { ArtistParser.cleanTrackTitle(it).lowercase() }
        val cleanAlbum = albumHint?.lowercase()

        val scored = candidates.mapNotNull { candidate ->
            val artistId = candidate.artistId ?: return@mapNotNull null
            try {
                delay(RATE_LIMIT_DELAY_MS)
                val songsResponse = iTunesApi.lookupArtistWithMedia(
                    artistId = artistId,
                    entity = "song",
                    limit = 10
                )
                if (!songsResponse.isSuccessful) return@mapNotNull null

                val songs = songsResponse.body()?.results
                    ?.filter { it.wrapperType == "track" }
                    ?: return@mapNotNull null

                var score = 0

                // Score by track name match
                if (cleanTrack != null) {
                    val trackMatch = songs.any { song ->
                        val songTitle = song.trackName?.lowercase() ?: ""
                        songTitle.contains(cleanTrack) || cleanTrack.contains(songTitle)
                    }
                    if (trackMatch) {
                        score += 100
                        Log.d(TAG, "Disambiguate: artist '${candidate.artistName}' (${artistId}) HAS track '$cleanTrack' (+100)")
                    } else {
                        Log.d(TAG, "Disambiguate: artist '${candidate.artistName}' (${artistId}) does NOT have track '$cleanTrack'")
                    }
                }

                // Score by album name match
                if (cleanAlbum != null) {
                    val albumMatch = songs.any { song ->
                        val collectionName = song.collectionName?.lowercase() ?: ""
                        collectionName.contains(cleanAlbum) || cleanAlbum.contains(collectionName)
                    }
                    if (albumMatch) {
                        score += 50
                        Log.d(TAG, "Disambiguate: artist '${candidate.artistName}' (${artistId}) HAS album '$cleanAlbum' (+50)")
                    }
                }

                ScoredCandidate(candidate, score)
            } catch (e: Exception) {
                Log.w(TAG, "Disambiguate: error checking songs for artist ${candidate.artistId}: ${e.message}")
                null
            }
        }

        val best = scored.maxByOrNull { it.score }
        return if (best != null && best.score > 0) {
            Log.i(TAG, "Disambiguated '${best.artist.artistName}' (${best.artist.artistId}) with score ${best.score} via track/album context")
            best.artist
        } else {
            Log.d(TAG, "Disambiguation found no track/album match among ${candidates.size} candidates — falling back to popularity")
            null
        }
    }

    /**
     * Fetch artist image for the given artist ID.
     * 
     * Strategy:
     * 1. Lookup artist → get Apple Music page URL
     * 2. Fetch page via OkHttp → extract artist image using multiple strategies
     * 3. Rewrite URL to 600x600 square
     * 4. Fallback to track/album artwork if page extraction fails (unless artistPhotoOnly=true)
     *
     * @param artistPhotoOnly If true, skip track/album artwork fallback. Use for artist-image
     *   refresh where cross-contamination with album art would be wrong.
     */
    suspend fun fetchArtistImage(
        artistId: Long,
        visitedIds: MutableSet<Long> = mutableSetOf(),
        artistPhotoOnly: Boolean = false
    ): String? {
        if (artistId in visitedIds) {
            Log.w(TAG, "Already visited artist ID $artistId, skipping")
            return null
        }
        visitedIds.add(artistId)
        
        if (visitedIds.size > 3) {
            Log.w(TAG, "Reached maximum artist lookup limit (3), stopping")
            return null
        }
        
        Log.d(TAG, "Fetching artist image for artist ID: $artistId")
        
        try {
            // Step 1: Get Apple Music artist page URL
            delay(RATE_LIMIT_DELAY_MS)
            val lookupResponse = iTunesApi.lookupArtist(artistId = artistId)
            val artistUrl = lookupResponse.body()?.results?.firstOrNull()?.artistLinkUrl
                ?.split("?")?.get(0) // Remove query params like ?uo=4
            
            // Step 2: Fetch Apple Music page and extract artist image using multiple strategies
            if (!artistUrl.isNullOrBlank()) {
                val artistImage = extractArtistImageFromPage(artistUrl)
                if (artistImage != null) {
                    // Rewrite to 600x600 square
                    val squareUrl = rewriteToSquare(artistImage, 600)
                    Log.i(TAG, "Found artist photo for ID $artistId: $squareUrl")
                    return squareUrl
                }
            }
            
            // Step 3 & 4: Fallback to track/album artwork
            // Skip if artistPhotoOnly — these are album/track covers, not artist photos
            if (!artistPhotoOnly) {
                Log.d(TAG, "Page extraction failed for ID $artistId, trying track artwork")
                delay(RATE_LIMIT_DELAY_MS)
                val songResponse = iTunesApi.lookupArtistWithMedia(
                    artistId = artistId,
                    entity = "song",
                    limit = 3
                )
                if (songResponse.isSuccessful) {
                    val trackArt = songResponse.body()?.results
                        ?.firstOrNull { it.artworkUrl100 != null && it.wrapperType == "track" }
                        ?.getBestArtworkUrl()
                    if (trackArt != null) {
                        Log.i(TAG, "Found track artwork for artist ID $artistId: $trackArt")
                        return trackArt
                    }
                }
                
                // Step 4: Fallback to album artwork
                delay(RATE_LIMIT_DELAY_MS)
                val albumResponse = iTunesApi.lookupArtistWithMedia(
                    artistId = artistId,
                    entity = "album",
                    limit = 1
                )
                if (albumResponse.isSuccessful) {
                    val albumArt = albumResponse.body()?.results
                        ?.firstOrNull { it.artworkUrl100 != null && it.wrapperType == "collection" }
                        ?.getBestArtworkUrl()
                    if (albumArt != null) {
                        Log.i(TAG, "Found album artwork for artist ID $artistId: $albumArt")
                        return albumArt
                    }
                }
            } else {
                Log.d(TAG, "artistPhotoOnly=true, skipping track/album artwork fallback for ID $artistId")
            }
            
            Log.d(TAG, "No artwork found for artist ID: $artistId")
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching artwork for artist $artistId", e)
        }
        return null
    }
    
    /**
     * Extract artist image from Apple Music page using multiple strategies.
     * 
     * Two-phase approach:
     * Phase 1: Fetch with social media bot User-Agent (facebookexternalhit).
     *   Apple Music returns SSR HTML with og:image/twitter:image for crawlers,
     *   which contains the actual artist photo (the same image shown in
     *   WhatsApp/Twitter link previews).
     * Phase 2: Fallback to regular browser User-Agent for SPA HTML scraping.
     * 
     * Extraction priority (artist images only, not album art):
     * 1. AMCArtistImages CDN URLs (Apple's dedicated artist photo path - most reliable)
     * 2. --background-image CSS in artist-detail-header
     * 3. og:image / twitter:image containing AMCArtistImages
     * 4. og:image / twitter:image (any, from bot-fetched HTML)
     * 5. JSON-LD structured data
     * 6. srcset-based AMCArtistImages in artwork-component divs
     * 7. Generic mzstatic fallback (only AMCArtistImages paths)
     */
    private suspend fun extractArtistImageFromPage(pageUrl: String): String? {
        // Extract artist slug from URL for context-aware filtering
        // e.g. "shreya-ghoshal" from "https://music.apple.com/us/artist/shreya-ghoshal/19715559"
        val artistSlug = pageUrl.substringAfter("/artist/").substringBefore("/").takeIf { it.isNotBlank() }
        
        return withContext(Dispatchers.IO) {
            // Phase 1: Fetch with bot UA for reliable og:image (same as social media previews)
            val botResult = fetchAndExtractArtistImage(pageUrl, BOT_USER_AGENT, "bot", artistSlug)
            if (botResult != null) return@withContext botResult
            
            // Phase 2: Fetch with browser UA for full SPA HTML with srcset/CSS data
            val browserResult = fetchAndExtractArtistImage(
                pageUrl,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "browser",
                artistSlug
            )
            if (browserResult != null) return@withContext browserResult
            
            Log.d(TAG, "No artist image found from either bot or browser fetch")
            null
        }
    }
    
    /**
     * Fetch Apple Music page with the given User-Agent and extract artist image.
     */
    private fun fetchAndExtractArtistImage(pageUrl: String, userAgent: String, phase: String, artistSlug: String? = null): String? {
        try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Apple Music page fetch failed ($phase): ${response.code}")
                return null
            }
            
            val html = response.body?.string() ?: return null
            Log.d(TAG, "Fetched Apple Music page ($phase): ${html.length} chars")
            
            return extractArtistImageFromHtml(html, phase, artistSlug)
        } catch (e: Exception) {
            Log.w(TAG, "Page extraction error ($phase): ${e.message}")
            return null
        }
    }
    
    /**
     * Extract artist image URL from Apple Music HTML content.
     * Strongly prefers AMCArtistImages URLs (actual artist photos) over
     * generic artwork URLs (which are usually album covers).
     * 
     * @param artistSlug The artist slug from the Apple Music URL (e.g. "shreya-ghoshal")
     *   Used to filter out images from related/similar artist sections.
     */
    private fun extractArtistImageFromHtml(html: String, phase: String, artistSlug: String? = null): String? {
        // Strategy 1: Data-Testid header block extraction
        // The most robust way to get the actual header image is to isolate the `<div data-testid="artist-detail-header">`
        // block and extract the mzstatic URL from inside it. This bypasses the need to guess which CDN path Apple Music
        // is using today, and it intrinsically prevents cross-contamination from "Related Artists" sections.
        val headerIndex = html.indexOf("""data-testid="artist-detail-header"""")
        if (headerIndex != -1) {
            // Extract a reasonable chunk of HTML containing the header (usually ~2000-3000 chars)
            val headerHtml = html.substring(headerIndex, minOf(headerIndex + 5000, html.length))
            val headerUrls = MZSTATIC_GENERIC_REGEX.findAll(headerHtml)
                .map { it.groupValues[1] }
                .toList()
                .distinct()
                
            if (headerUrls.isNotEmpty()) {
                val bestHeaderUrl = headerUrls.firstOrNull { it.contains("cropped.png") || it.contains("cc.") }
                    ?: headerUrls.first()
                Log.d(TAG, "Strategy 1 ($phase): Found artist image in artist-detail-header: $bestHeaderUrl")
                return bestHeaderUrl
            }
        }
        
        // Strategy 2: CDN Regex (Fallback for when the header block isn't found, e.g., SSR bot phase might differ)
        // These are the most reliable. Apple uses this path exclusively for genuine
        // artist profile photos, never for album art.
        // IMPORTANT: Apple Music pages include related/similar artist images too,
        // so we must filter by position to avoid cross-contamination.
        val allArtistCdnMatches = ARTIST_IMAGE_CDN_REGEX.findAll(html)
            .map { it.groupValues[1] to it.range.first } // Keep URL + position in HTML
            .toList()
            .distinctBy { it.first }
        if (allArtistCdnMatches.isNotEmpty()) {
            // Find where related/similar artist sections start in the HTML
            val relatedSectionStart = findRelatedArtistsSectionStart(html)
            
            // Filter to only URLs that appear BEFORE the related artists section
            val mainArtistMatches = if (relatedSectionStart != null) {
                val filtered = allArtistCdnMatches.filter { (_, position) -> position < relatedSectionStart }
                if (filtered.isNotEmpty()) {
                    Log.d(TAG, "Strategy 1 ($phase): Filtered ${allArtistCdnMatches.size} CDN URLs to ${filtered.size} (before related section at pos $relatedSectionStart)")
                    filtered
                } else {
                    // All CDN URLs are in related sections — they belong to other artists!
                    // Do not fall back to them; it's better to return null than the wrong artist.
                    Log.w(TAG, "Strategy 1 ($phase): All ${allArtistCdnMatches.size} CDN URLs appear after related section marker. Ignoring them to prevent cross-contamination.")
                    emptyList()
                }
            } else {
                allArtistCdnMatches
            }
            
            if (mainArtistMatches.isNotEmpty()) {
                val candidateUrls = mainArtistMatches.map { it.first }
                // Prefer the largest/highest quality variant
                val bestCdnUrl = candidateUrls.firstOrNull { it.contains("cropped.png") || it.contains("cc.") }
                    ?: candidateUrls.first()
                Log.d(TAG, "Strategy 1 ($phase): Found CDN URL (from ${allArtistCdnMatches.size} total, ${mainArtistMatches.size} main): $bestCdnUrl")
                return bestCdnUrl
            }
        }
        
        // Strategy 3: --background-image CSS variable in artist-detail-header
        // Contains the artist banner photo URL directly in CSS
        val bgImageUrl = BACKGROUND_IMAGE_REGEX.find(html)?.groupValues?.get(1)
        if (bgImageUrl != null && bgImageUrl.contains("mzstatic.com")) {
            Log.d(TAG, "Strategy 2 ($phase): Found --background-image: $bgImageUrl")
            return bgImageUrl
        }
        
        // Strategy 4: og:image containing AMCArtistImages (verified artist photo)
        val ogImage = (OG_IMAGE_REGEX.find(html) ?: OG_IMAGE_REGEX_ALT.find(html))
            ?.groupValues?.get(1)
        if (ogImage != null && ogImage.contains("AMCArtistImages")) {
            Log.d(TAG, "Strategy 4 ($phase): Found og:image with AMCArtistImages: $ogImage")
            return ogImage
        }
        
        // Strategy 5: twitter:image containing AMCArtistImages
        val twitterImage = (TWITTER_IMAGE_REGEX.find(html) ?: TWITTER_IMAGE_REGEX_ALT.find(html))
            ?.groupValues?.get(1)
        if (twitterImage != null && twitterImage.contains("AMCArtistImages")) {
            Log.d(TAG, "Strategy 4 ($phase): Found twitter:image with AMCArtistImages: $twitterImage")
            return twitterImage
        }
        
        // Strategy 6: og:image / twitter:image from bot-fetched HTML (trusted even without AMCArtistImages)
        // Social media previews for artist pages always show the artist photo.
        // Only trust this from bot phase since browser HTML might have album art in og:image.
        if (phase == "bot") {
            if (ogImage != null && ogImage.contains("mzstatic.com")) {
                Log.d(TAG, "Strategy 6 ($phase): Found og:image (bot-trusted): $ogImage")
                return ogImage
            }
            if (twitterImage != null && twitterImage.contains("mzstatic.com")) {
                Log.d(TAG, "Strategy 6 ($phase): Found twitter:image (bot-trusted): $twitterImage")
                return twitterImage
            }
        }
        
        // Strategy 7: JSON-LD structured data
        val jsonLdImage = JSON_LD_IMAGE_REGEX.find(html)?.groupValues?.get(1)
        if (jsonLdImage != null && jsonLdImage.contains("AMCArtistImages")) {
            Log.d(TAG, "Strategy 7 ($phase): Found JSON-LD artist image: $jsonLdImage")
            return jsonLdImage
        }
        
        // Strategy 8: Broad fallback - only AMCArtistImages URLs from any mzstatic match
        // This ensures we NEVER return album art as an artist image.
        val allMzstaticUrls = MZSTATIC_GENERIC_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .filter { url ->
                url.contains("AMCArtistImages") &&
                !url.contains("/favicon") &&
                !url.contains("Badge") &&
                !url.contains("icon")
            }
            .toList()
            .distinct()
        
        val preferredUrl = allMzstaticUrls.firstOrNull()
        if (preferredUrl != null) {
            Log.d(TAG, "Strategy 7 ($phase): Found AMCArtistImages in generic search (from ${allMzstaticUrls.size} candidates): $preferredUrl")
            return preferredUrl
        }
        
        Log.d(TAG, "No artist image found in HTML ($phase, ${html.length} chars, tried 7 strategies)")
        return null
    }
    
    /**
     * Find the position in HTML where related/similar artist sections begin.
     * Apple Music pages show the main artist content first, then sections like
     * "Also Listened To", "Similar Artists", etc. with other artist images.
     * 
     * @return The character position of the first related section marker, or null if not found
     */
    private fun findRelatedArtistsSectionStart(html: String): Int? {
        val markers = listOf(
            "Also Listened To",
            "Similar Artists",
            "More to Explore",
            "You Might Also Like",
            "Listeners Also Listen To",
            "shelf-grid",            // Common CSS class for artist grid carousels
            "carousel-list"          // Another common carousel container
        )
        return markers.mapNotNull { marker ->
            html.indexOf(marker, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull()
    }
    
    /**
     * Rewrite mzstatic.com URL to square dimensions.
     * Apple CDN supports dynamic size via URL suffix:
     * /1200x630cw.png → /600x600cc.png (cc = center-crop)
     */
    private fun rewriteToSquare(url: String, size: Int): String {
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash == -1) return url
        val basePath = url.substring(0, lastSlash)
        val ext = when {
            url.endsWith(".png", true) -> "png"
            url.endsWith(".webp", true) -> "webp"
            else -> "jpg"
        }
        return "$basePath/${size}x${size}cc.$ext"
    }

    /**
     * Build search query from artist, album, and track.
     * iTunes search works best with "artist album" format.
     */
    /**
     * Build list of search strategies for iTunes.
     * 
     * Generates multiple search queries to try in order of precision:
     * 1. Primary Artist + Album (if album exists)
     * 2. Primary Artist + Track (if track exists)
     * 3. Secondary Artists + Track (iterates through other artists)
     * 4. Track Name Only (filtered by artist in the main loop)
     */
    private fun buildSearchStrategies(artist: String, album: String?, track: String?): List<String> {
        val strategies = mutableListOf<String>()
        val primaryArtist = ArtistParser.getPrimaryArtist(artist)
        
        // Strategy 1: Primary Artist + Album (Best for cover art)
        if (!album.isNullOrBlank()) {
             strategies.add("$primaryArtist $album")
        }
        
        // Strategy 2: Primary Artist + Track (Best for track metadata)
        if (!track.isNullOrBlank()) {
            val cleanTrack = ArtistParser.cleanTrackTitle(track)
            strategies.add("$primaryArtist $cleanTrack")
            
            // Strategy 3: Try other artists if available
            val allArtists = ArtistParser.getAllArtists(artist)
            if (allArtists.size > 1) {
                for (otherArtist in allArtists) {
                    // Skip primary artist as we already added it
                    if (!ArtistParser.isSameArtist(otherArtist, primaryArtist)) {
                        strategies.add("$otherArtist $cleanTrack")
                    }
                }
            }
            
            // Strategy 4: Track name only (Fallback)
            // Useful if artist name on iTunes is completely different (e.g. "feat. X" vs "with X")
            // The result validation loop will ensure we don't match wrong songs
            strategies.add(cleanTrack)
        }
        
        // Fallback: Just primary artist (least precise, but finds something)
        if (strategies.isEmpty()) {
            strategies.add(primaryArtist)
        }
        
        return strategies
    }

    /**
     * Check if iTunes service is available.
     * Always returns true since no auth is required.
     */
    fun isAvailable(): Boolean = true
}
