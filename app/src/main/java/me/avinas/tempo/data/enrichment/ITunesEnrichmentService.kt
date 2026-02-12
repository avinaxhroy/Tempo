package me.avinas.tempo.data.enrichment

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.remote.itunes.iTunesApi
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
        
        // Regex for og:image extraction from Apple Music SSR HTML
        // Apple Music pages always include this meta tag server-side for social sharing
        private val OG_IMAGE_REGEX = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""""", RegexOption.IGNORE_CASE)
        private val OG_IMAGE_REGEX_ALT = Regex("""<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["']"""""", RegexOption.IGNORE_CASE)
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
     * @param artistName The raw artist string (may contain multiple artists)
     * @return The artist image URL or null if not found
     */
    suspend fun searchAndFetchArtistImage(artistName: String): String? {
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
                
                // Find all matching artists and rank them by popularity
                val matchingArtists = searchResponse.results.filter { result ->
                    result.artistName != null && ArtistParser.isSameArtist(result.artistName, cleanName)
                }
                
                if (matchingArtists.isEmpty()) {
                    Log.d(TAG, "No exact artist match found for: '$cleanName'")
                    continue
                }
                
                // Rank by: 1) Exact name match, 2) Artist score (track count, etc.)
                val bestArtist = matchingArtists.maxByOrNull { result ->
                    val exactMatch = result.isExactArtistMatch(cleanName)
                    val score = result.getArtistScore()
                    // Exact match gets huge bonus
                    (if (exactMatch) 10000 else 0) + score
                }
                
                if (bestArtist?.artistId != null) {
                    val score = bestArtist.getArtistScore()
                    val exactMatch = bestArtist.isExactArtistMatch(cleanName)
                    Log.d(TAG, "Found artist ID for '$cleanName': ${bestArtist.artistId} (score: $score, exact: $exactMatch, tracks: ${bestArtist.trackCount})")
                    
                    val imageUrl = fetchArtistImage(bestArtist.artistId)
                    
                    if (imageUrl != null) {
                        return imageUrl // Found one, return immediately
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error searching artist '$cleanName' on iTunes", e)
            }
        }
        
        Log.d(TAG, "No artist image found for any artist in: '$artistName'")
        return null
    }

    /**
     * Fetch artist image for the given artist ID.
     * 
     * Strategy:
     * 1. Lookup artist → get Apple Music page URL
     * 2. Fetch page via OkHttp → extract og:image (actual artist photo)
     * 3. Rewrite URL to 600x600 square
     * 4. Fallback to track/album artwork if og:image fails
     */
    suspend fun fetchArtistImage(artistId: Long, visitedIds: MutableSet<Long> = mutableSetOf()): String? {
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
            
            // Step 2: Fetch Apple Music page via OkHttp and extract og:image
            if (!artistUrl.isNullOrBlank()) {
                val ogImage = fetchOgImageViaOkHttp(artistUrl)
                if (ogImage != null) {
                    // Rewrite to 600x600 square (og:image is 1200x630 banner)
                    val squareUrl = rewriteToSquare(ogImage, 600)
                    Log.i(TAG, "Found artist photo for ID $artistId: $squareUrl")
                    return squareUrl
                }
            }
            
            // Step 3: Fallback to track artwork
            Log.d(TAG, "og:image failed for ID $artistId, trying track artwork")
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
            
            Log.d(TAG, "No artwork found for artist ID: $artistId")
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching artwork for artist $artistId", e)
        }
        return null
    }
    
    /**
     * Fetch Apple Music page via OkHttp and extract og:image URL.
     * OkHttp handles HTTP/2, gzip, redirects, cookies properly — unlike
     * raw HttpURLConnection which fails on Apple Music's responses.
     */
    private suspend fun fetchOgImageViaOkHttp(pageUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Apple Music page fetch failed: ${response.code}")
                    return@withContext null
                }
                
                val html = response.body?.string() ?: return@withContext null
                
                // Extract og:image from SSR HTML
                val match = OG_IMAGE_REGEX.find(html) ?: OG_IMAGE_REGEX_ALT.find(html)
                val url = match?.groupValues?.get(1)
                
                if (url != null && url.contains("mzstatic.com")) {
                    Log.d(TAG, "Extracted og:image: $url")
                    return@withContext url
                }
                
                Log.d(TAG, "No og:image in HTML (${html.length} chars)")
                null
            } catch (e: Exception) {
                Log.w(TAG, "OkHttp fetch error: ${e.message}")
                null
            }
        }
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
