package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.lastfm.LastFmApi
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for enriching track metadata with Last.fm ARTIST-LEVEL tags/genres.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This service is called ONLY by EnrichmentWorker in background.
 * It should NEVER be called directly from ViewModels or UI components.
 * 
 * IMPORTANT: Last.fm is used for ARTIST-BASED genre data only.
 * Track-level genre lookup is unreliable across most music APIs.
 * For track-level genre, use iTunes (the only reliable source).
 * For track-level genre with artist fallback, use MusicBrainz.
 * 
 * Last.fm is excellent for:
 * - Artist genre/tag data (community-curated, very accurate)
 * - Artist images (when other sources fail)
 * 
 * Last.fm is used as a SUPPLEMENT to iTunes/MusicBrainz, not a primary source.
 * It fills in artist-level genres/tags when:
 * - iTunes couldn't provide track-level genre
 * - MusicBrainz couldn't find the recording
 * 
 * =====================================================
 */
@Singleton
class LastFmEnrichmentService @Inject constructor(
    private val lastFmApi: LastFmApi,
    private val enrichedMetadataDao: EnrichedMetadataDao
) {
    companion object {
        private const val TAG = "LastFmEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 200L
        
        // Minimum tag count to consider a tag relevant
        private const val MIN_TAG_COUNT = 10
        
        // Maximum tags to store
        private const val MAX_TAGS = 10
    }

    /**
     * Result of Last.fm enrichment attempt.
     */
    sealed class LastFmResult {
        data class Success(
            val tags: List<String>,
            val genres: List<String>,
            val albumArtUrl: String? = null,
            val albumTitle: String? = null,
            val musicbrainzId: String? = null
        ) : LastFmResult()
        
        object NotConfigured : LastFmResult()
        object TrackNotFound : LastFmResult()
        object AlreadyHasData : LastFmResult()
        data class Error(val message: String) : LastFmResult()
    }

    /**
     * Check if Last.fm enrichment is available.
     * Returns true if API key is configured.
     */
    fun isAvailable(): Boolean {
        return getApiKey().isNotBlank()
    }
    
    private fun getApiKey(): String {
        return try {
            BuildConfig.LASTFM_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Supplement existing metadata with Last.fm ARTIST-LEVEL tag/genre data.
     * 
     * IMPORTANT: This method fetches ARTIST genres only - not track-level genres.
     * This is intentional because:
     * 1. Most music APIs don't reliably provide track-level genre data
     * 2. iTunes is the exception and handles track-level genres
     * 3. Artist genres are more reliable and consistent
     * 
     * @param track The track to enrich
     * @param existingMetadata The existing metadata to supplement
     * @return LastFmResult indicating what was added
     */
    suspend fun supplementMetadata(
        track: Track,
        existingMetadata: EnrichedMetadata
    ): LastFmResult {
        if (!isAvailable()) {
            Log.d(TAG, "Last.fm API key not configured")
            return LastFmResult.NotConfigured
        }

        // Skip if we already have good genre/tag data
        if (existingMetadata.genres.isNotEmpty() && existingMetadata.tags.isNotEmpty()) {
            Log.d(TAG, "Track ${track.id} already has genre/tag data")
            return LastFmResult.AlreadyHasData
        }

        Log.d(TAG, "Fetching ARTIST-LEVEL genres for track ${track.id}: '${track.title}' by '${track.artist}'")

        // Skip if artist is unknown
        if (ArtistParser.isUnknownArtist(track.artist)) {
            Log.d(TAG, "Skipping Last.fm for track ${track.id}: artist is unknown")
            return LastFmResult.Error("Artist unknown")
        }

        val apiKey = getApiKey()

        try {
            // Directly fetch artist tags - skip track-level lookup
            // Track-level genre data is unreliable; use iTunes for that instead
            val artistResult = fetchArtistTags(track.artist, apiKey)
            
            if (artistResult is LastFmResult.Success) {
                updateMetadataWithLastFm(track.id, existingMetadata, artistResult)
                return artistResult
            }

            return LastFmResult.TrackNotFound

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Last.fm artist data", e)
            return LastFmResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Fetch track info from Last.fm.
     */
    private suspend fun fetchTrackInfo(
        title: String,
        artist: String,
        apiKey: String
    ): LastFmResult {
        try {
            val response = lastFmApi.getTrackInfo(
                track = title,
                artist = artist,
                apiKey = apiKey
            )

            if (!response.isSuccessful) {
                Log.e(TAG, "Last.fm track info failed: ${response.code()}")
                return LastFmResult.Error("API error: ${response.code()}")
            }

            val trackResponse = response.body()
            
            // Check for error in response body
            if (trackResponse?.error != null) {
                Log.d(TAG, "Last.fm error: ${trackResponse.message}")
                return LastFmResult.TrackNotFound
            }

            val trackInfo = trackResponse?.track
            if (trackInfo == null) {
                Log.d(TAG, "No track info returned for '$title' by '$artist'")
                return LastFmResult.TrackNotFound
            }

            // Extract tags and classify into genres vs general tags
            val allTags = trackInfo.getTagNames()
            val (genres, tags) = classifyTags(allTags)

            // Get album art if available
            val albumArtUrl = trackInfo.album?.getBestImageUrl()
            val albumTitle = trackInfo.album?.title

            Log.d(TAG, "Found Last.fm data for '$title': ${genres.size} genres, ${tags.size} tags")

            return LastFmResult.Success(
                tags = tags.take(MAX_TAGS),
                genres = genres.take(5),
                albumArtUrl = albumArtUrl,
                albumTitle = albumTitle,
                musicbrainzId = trackInfo.mbid
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching track info", e)
            return LastFmResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Fetch artist tags from Last.fm as a fallback.
     * Iterates through all individual artists until one returns valid tags.
     */
    private suspend fun fetchArtistTags(
        artistString: String,
        apiKey: String
    ): LastFmResult {
        val allArtists = ArtistParser.getAllArtists(artistString)
        Log.d(TAG, "Fetching artist tags for artists: $allArtists")

        for (artist in allArtists) {
            val cleanArtist = ArtistParser.normalizeArtistName(artist)
            if (cleanArtist.isBlank()) continue

            try {
                // Add rate limit delay between attempts
                delay(RATE_LIMIT_DELAY_MS)
                
                Log.d(TAG, "Trying artist tags for: '$cleanArtist'")
                
                val response = lastFmApi.getArtistTopTags(
                    artist = cleanArtist,
                    apiKey = apiKey
                )

                if (!response.isSuccessful) {
                    Log.w(TAG, "Last.fm artist tags failed for '$cleanArtist': ${response.code()}")
                    continue
                }

                val tagsResponse = response.body()
                
                if (tagsResponse?.error != null) {
                    Log.d(TAG, "Last.fm artist error for '$cleanArtist': ${tagsResponse.message}")
                    continue
                }

                val tagList = tagsResponse?.toptags?.tag
                    ?.filter { (it.count ?: 0) >= MIN_TAG_COUNT }
                    ?.mapNotNull { it.name }
                    ?: emptyList()

                if (tagList.isNotEmpty()) {
                    val (genres, tags) = classifyTags(tagList)
                    Log.d(TAG, "Found Last.fm artist tags for '$cleanArtist': ${genres.size} genres, ${tags.size} tags")

                    return LastFmResult.Success(
                        tags = tags.take(MAX_TAGS),
                        genres = genres.take(5),
                        albumArtUrl = null,
                        albumTitle = null,
                        musicbrainzId = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching artist tags for '$cleanArtist'", e)
            }
        }
        
        return LastFmResult.TrackNotFound
    }

    /**
     * Classify tags into genres and general tags.
     * 
     * Last.fm tags include both proper genres (rock, hip-hop) and
     * descriptive tags (chill, female vocalist). This separates them.
     */
    private fun classifyTags(tags: List<String>): Pair<List<String>, List<String>> {
        val genreKeywords = setOf(
            // Main genres
            "rock", "pop", "hip-hop", "hip hop", "rap", "r&b", "rnb", "jazz", "blues",
            "country", "folk", "classical", "electronic", "edm", "house", "techno",
            "metal", "punk", "indie", "alternative", "soul", "funk", "reggae",
            "latin", "world", "ambient", "new age", "soundtrack", "gospel",
            
            // Sub-genres
            "indie rock", "indie pop", "alt-rock", "alternative rock", "hard rock",
            "classic rock", "progressive rock", "psychedelic rock", "grunge",
            "trap", "lo-fi", "lofi", "drill", "grime", "boom bap", "conscious hip-hop",
            "deep house", "progressive house", "trance", "dubstep", "drum and bass",
            "death metal", "black metal", "thrash metal", "heavy metal", "nu metal",
            "post-punk", "hardcore punk", "pop punk", "emo",
            "neo-soul", "contemporary r&b", "quiet storm",
            "bossa nova", "samba", "salsa", "reggaeton", "k-pop", "j-pop",
            "bollywood", "indian", "desi", "indian hip-hop", "desi hip-hop",
            
            // Era/style
            "80s", "90s", "2000s", "00s", "10s", "oldies", "retro"
        )

        val genres = mutableListOf<String>()
        val generalTags = mutableListOf<String>()

        for (tag in tags) {
            val normalizedTag = tag.lowercase().trim()
            if (genreKeywords.any { normalizedTag.contains(it) || it.contains(normalizedTag) }) {
                genres.add(tag)
            } else {
                generalTags.add(tag)
            }
        }

        return Pair(genres, generalTags)
    }

    /**
     * Update metadata with Last.fm data, preserving existing data from other sources.
     */
    private suspend fun updateMetadataWithLastFm(
        trackId: Long,
        existingMetadata: EnrichedMetadata,
        lastFmResult: LastFmResult.Success
    ) {
        val updatedMetadata = existingMetadata.copy(
            // Only update genres if we don't have any
            genres = if (existingMetadata.genres.isEmpty()) {
                lastFmResult.genres
            } else {
                existingMetadata.genres
            },
            
            // Only update tags if we don't have any
            tags = if (existingMetadata.tags.isEmpty()) {
                lastFmResult.tags
            } else {
                existingMetadata.tags
            },
            
            // Only update album art if we don't have any
            albumArtUrl = existingMetadata.albumArtUrl ?: lastFmResult.albumArtUrl,
            
            // Only update album title if we don't have any
            albumTitle = existingMetadata.albumTitle ?: lastFmResult.albumTitle,
            
            // MusicBrainz ID from Last.fm (only if we don't have one)
            musicbrainzRecordingId = existingMetadata.musicbrainzRecordingId ?: lastFmResult.musicbrainzId,
            
            cacheTimestamp = System.currentTimeMillis()
        )

        enrichedMetadataDao.upsert(updatedMetadata)
        
        // Only log as "Updated" if we actually added useful data
        if (lastFmResult.genres.isNotEmpty() || lastFmResult.tags.isNotEmpty()) {
            Log.i(TAG, "Updated track $trackId with Last.fm data: " +
                    "${lastFmResult.genres.size} genres, ${lastFmResult.tags.size} tags")
        } else {
            Log.d(TAG, "Processed track $trackId via Last.fm (no new genres/tags)")
        }
    }

    /**
     * Fetch artist image from Last.fm.
     * Used as a fallback when Spotify doesn't provide artist images.
     * Iterates through all individual artists until an image is found.
     * 
     * @param artistName The raw artist name
     * @return Artist image URL (extralarge size) or null if not found
     */
    suspend fun fetchArtistImage(artistName: String): String? {
        if (!isAvailable()) {
            Log.d(TAG, "Last.fm API key not configured")
            return null
        }

        if (ArtistParser.isUnknownArtist(artistName)) {
            Log.d(TAG, "Skipping Last.fm artist image: artist is unknown")
            return null
        }

        val apiKey = getApiKey()
        val allArtists = ArtistParser.getAllArtists(artistName)
        
        Log.d(TAG, "Fetching artist image for artists: $allArtists")

        for (individualArtist in allArtists) {
            val cleanArtist = ArtistParser.normalizeArtistName(individualArtist)
            if (cleanArtist.isBlank()) continue
            
            Log.d(TAG, "Trying to fetch Last.fm image for: '$cleanArtist'")

            try {
                delay(RATE_LIMIT_DELAY_MS)
                
                val response = lastFmApi.getArtistInfo(
                    artist = cleanArtist,
                    apiKey = apiKey
                )

                if (!response.isSuccessful) {
                    Log.w(TAG, "Last.fm artist info failed for '$cleanArtist': ${response.code()}")
                    continue
                }

                val artistResponse = response.body()
                
                if (artistResponse?.error != null) {
                    Log.d(TAG, "Last.fm artist error for '$cleanArtist': ${artistResponse.message}")
                    continue
                }

                val artistInfo = artistResponse?.artist
                if (artistInfo == null) {
                    Log.d(TAG, "No artist info returned for '$cleanArtist'")
                    continue
                }

                // Get the best quality image
                val imageUrl = artistInfo.getBestImageUrl()
                
                if (imageUrl != null) {
                    Log.i(TAG, "Found Last.fm artist image for '$cleanArtist': $imageUrl")
                    return imageUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching artist image from Last.fm for '$cleanArtist'", e)
            }
        }
        
        Log.d(TAG, "No artist image available for any artist in '$artistName' on Last.fm")
        return null
    }
}