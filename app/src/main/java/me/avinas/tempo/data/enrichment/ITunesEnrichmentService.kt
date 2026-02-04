package me.avinas.tempo.data.enrichment

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.remote.itunes.iTunesApi
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
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
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "iTunesEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 500L // 500ms between requests (iTunes has relaxed rate limits)
        
        // Pre-compiled regex patterns to avoid repeated native memory allocation
        private val WEBP_SOURCE_PATTERN = Regex("""<source[^>]+srcset="([^"]+)"[^>]+type="image/webp""""", RegexOption.IGNORE_CASE)
        private val OG_IMAGE_PATTERN = Regex("""<meta\\s+property="og:image"\\s+content="([^"]+)""""", RegexOption.IGNORE_CASE)
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
                
                // Find strict match for artist name
                val artistMatch = searchResponse.results.find { result ->
                    result.artistName != null && ArtistParser.isSameArtist(result.artistName, cleanName)
                }
                
                if (artistMatch?.artistId != null) {
                    Log.d(TAG, "Found artist ID for '$cleanName': ${artistMatch.artistId}")
                    val imageUrl = fetchArtistImage(artistMatch.artistId)
                    
                    if (imageUrl != null) {
                        return imageUrl // Found one, return immediately
                    }
                } else {
                    Log.d(TAG, "No exact artist match found for: '$cleanName'")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error searching artist '$cleanName' on iTunes", e)
            }
        }
        
        Log.d(TAG, "No artist image found for any artist in: '$artistName'")
        return null
    }

    /**
     * Fetch artist image using proper web scraping.
     * 
     * iTunes Search API returns an 'artistLinkUrl' which points to the Apple Music artist page.
     * That page contains the high-quality artist profile image in its Open Graph tags.
     * We fetch that page and extract the <meta property="og:image"> content.
     */
    suspend fun fetchArtistImage(artistId: Long): String? {
        Log.d(TAG, "Fetching artist image for artist ID: $artistId")
        
        try {
            delay(RATE_LIMIT_DELAY_MS)
            
            // 1. Get the artist link URL from lookup API
            val response = iTunesApi.lookupArtist(artistId = artistId)
            
            if (!response.isSuccessful) {
                Log.e(TAG, "iTunes artist lookup failed: ${response.code()}")
                return null
            }
            
            val lookupResponse = response.body()
            if (lookupResponse == null || lookupResponse.results.isEmpty()) {
                Log.d(TAG, "No artist info found for ID: $artistId")
                return null
            }
            
            val artist = lookupResponse.results.firstOrNull()
            val artistLinkUrl = artist?.artistLinkUrl
            
            if (artistLinkUrl.isNullOrBlank()) {
                Log.d(TAG, "No Apple Music link found for artist ID: $artistId")
                return null
            }
            
            // 2. Fetch the Apple Music page HTML
            Log.d(TAG, "Fetching Apple Music page: $artistLinkUrl")
            val pageContent = fetchUrlContent(artistLinkUrl)
            
            if (pageContent == null) {
                Log.w(TAG, "Failed to fetch Apple Music page content")
                return null
            }
            
            // 3. Extract artist image
            // Priority 1: Try to find WebP image from <picture><source type="image/webp" ...></picture>
            // This usually provides square cropped images (cc) which are better for avatars than og:image (cw/landscape)
            // Example: .../380x380cc.webp
            var imageUrl: String? = null
            
            try {
                // Regex to find source tag with type="image/webp" and capture its srcset
                val webpMatch = WEBP_SOURCE_PATTERN.find(pageContent)
                
                if (webpMatch != null) {
                    val srcset = webpMatch.groupValues[1]
                    // srcset format: "url1 190w,url2 380w"
                    // We want the largest one. Split by comma.
                    val bestWebpUrl = srcset.split(",")
                        .map { it.trim() }
                        .mapNotNull { entry ->
                            // Entry looks like: "https://.../380x380cc.webp 380w"
                            val parts = entry.split(" ")
                            if (parts.isNotEmpty()) {
                                val url = parts[0]
                                // Try to parse width if available, otherwise assume 0
                                val width = if (parts.size > 1) parts[1].replace("w", "").toIntOrNull() ?: 0 else 0
                                Pair(url, width)
                            } else null
                        }
                        .maxByOrNull { it.second } // Get the one with max width
                        ?.first
                    
                    if (bestWebpUrl != null) {
                        imageUrl = bestWebpUrl
                        Log.i(TAG, "Found WebP artist image: $imageUrl")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse WebP source: ${e.message}")
            }
            
            // Priority 2: Fallback to og:image if WebP not found
            if (imageUrl == null) {
                // Look for: <meta property="og:image" content="...">
                val matchResult = OG_IMAGE_PATTERN.find(pageContent)
                imageUrl = matchResult?.groupValues?.get(1)
                
                if (imageUrl != null) {
                    Log.i(TAG, "Found og:image fallback: $imageUrl")
                }
            }

            if (imageUrl != null) {
                Log.i(TAG, "Final artist image for ID $artistId: $imageUrl")
                return imageUrl
            } else {
                Log.d(TAG, "No artist image found (checked WebP source and og:image) for ID: $artistId")
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching artist image from iTunes", e)
            return null
        }
    }

    /**
     * Helper to fetch URL content string.
     * Uses a simple OkHttp request if available in the project, or basic Java URL connection if not.
     * Since this project uses Retrofit/OkHttp, we should assume OkHttp is available via DI or we can create a simple client.
     * For simplicity and robustness here without adding DI, we'll use a basic URL connection 
     * (or better, reuse the OkHttp client if visible).
     * 
     * To avoid adding dependencies/DI changes, we'll use basic HttpURLConnection here 
     * as it's just one GET request.
     */
    private fun fetchUrlContent(urlString: String): String? {
        var connection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(urlString)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            // User agent is important to avoid being blocked
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36")
            
            if (connection.responseCode == 200) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            }
            Log.w(TAG, "Web scrape failed with code: ${connection.responseCode}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Web scrape error: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
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
