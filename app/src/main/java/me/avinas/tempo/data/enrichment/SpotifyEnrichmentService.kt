package me.avinas.tempo.data.enrichment

import android.util.Log
import com.squareup.moshi.Moshi
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.spotify.SpotifyApi
import me.avinas.tempo.data.remote.spotify.SpotifyArtist
import me.avinas.tempo.data.remote.spotify.SpotifyAudioFeatures
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyFullArtist
import me.avinas.tempo.data.remote.spotify.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for enriching track metadata with Spotify audio features.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This service is called ONLY by EnrichmentWorker in background.
 * It should NEVER be called directly from ViewModels or UI components.
 * 
 * Flow:
 * 1. EnrichmentWorker calls this service to fetch data from Spotify API
 * 2. This service stores the fetched data in Room database via DAOs
 * 3. UI components read the cached data from database via Repositories
 * 
 * This pattern ensures:
 * - API calls are made in background, never blocking UI
 * - Rate limits are respected (Spotify has dynamic rate limiting)
 * - UI always has fast access to cached data
 * - App works offline with whatever is cached
 * - User never waits for API responses
 * 
 * =====================================================
 * 
 * This service is OPTIONAL - the app works fully without Spotify connection.
 * When connected, it adds advanced audio analysis (danceability, energy, mood, etc.)
 * that enables advanced statistics like "Your music was 65% energetic this month."
 * 
 * Features:
 * - Search Spotify by song title + artist to find matching track
 * - Fetch audio features (danceability, energy, valence, tempo, etc.)
 * - Store features as JSON in EnrichedMetadata
 * - Respects user's choice to not connect Spotify
 * - Graceful degradation when Spotify is unavailable
 */
@Singleton
class SpotifyEnrichmentService @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val authManager: SpotifyAuthManager,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val artistDao: me.avinas.tempo.data.local.dao.ArtistDao,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "SpotifyEnrichment"
        private const val MIN_MATCH_SCORE = 0.7 // Minimum similarity score to accept match
        private const val RATE_LIMIT_DELAY_MS = 100L // Small delay between requests
    }

    /**
     * Result of Spotify enrichment attempt.
     */
    sealed class SpotifyEnrichmentResult {
        data class Success(
            val spotifyId: String,
            val audioFeatures: SpotifyAudioFeatures
        ) : SpotifyEnrichmentResult()
        
        object NotConnected : SpotifyEnrichmentResult()
        object TrackNotFound : SpotifyEnrichmentResult()
        object AudioFeaturesNotAvailable : SpotifyEnrichmentResult()
        data class Error(val message: String, val retryable: Boolean = true) : SpotifyEnrichmentResult()
    }

    /**
     * Check if Spotify enrichment is available.
     * Returns true only if user has connected their Spotify account.
     */
    fun isAvailable(): Boolean {
        return authManager.isConnected()
    }

    /**
     * Enrich a track with Spotify audio features.
     * 
     * @param track The track to enrich
     * @param existingMetadata Existing enriched metadata (if any)
     * @return SpotifyEnrichmentResult indicating success or failure
     */
    suspend fun enrichTrack(
        track: Track,
        existingMetadata: EnrichedMetadata?
    ): SpotifyEnrichmentResult {
        // Check if Spotify is connected
        if (!isAvailable()) {
            return SpotifyEnrichmentResult.NotConnected
        }

        // Check if we already have Spotify data with audio features
        if (existingMetadata?.spotifyId != null && existingMetadata.audioFeaturesJson != null) {
            Log.d(TAG, "Track ${track.id} already has Spotify data")
            return parseExistingAudioFeatures(existingMetadata)
        }

        // Skip enrichment if artist is unknown - waiting for metadata to settle
        // MediaSession often sends metadata in multiple callbacks, artist may arrive later
        if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
            Log.d(TAG, "Skipping Spotify enrichment for track ${track.id}: artist is unknown, waiting for metadata update")
            return SpotifyEnrichmentResult.TrackNotFound
        }

        Log.d(TAG, "Fetching metadata from Spotify: '${track.title}' by '${track.artist}'")

        // Get valid access token
        val accessToken = authManager.getValidAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Failed to get valid access token")
            return SpotifyEnrichmentResult.NotConnected
        }

        val authHeader = "Bearer $accessToken"

        // Check if we already have a Spotify ID (from basic metadata fetch)
        val spotifyId = existingMetadata?.spotifyId
        
        if (spotifyId != null) {
            // We already have the Spotify ID, just fetch audio features
            Log.d(TAG, "Using existing Spotify ID: $spotifyId for track ${track.id}")
            val audioFeatures = fetchAudioFeatures(spotifyId, authHeader)
                ?: return SpotifyEnrichmentResult.AudioFeaturesNotAvailable
            
            // Update metadata with audio features
            val audioFeaturesJson = serializeAudioFeatures(audioFeatures)
            val updated = existingMetadata.copy(
                audioFeaturesJson = audioFeaturesJson,
                cacheTimestamp = System.currentTimeMillis()
            )
            enrichedMetadataDao.upsert(updated)
            Log.d(TAG, "Saved audio features for track ${track.id}")
            
            return SpotifyEnrichmentResult.Success(spotifyId, audioFeatures)
        }

        // Step 1: Search for the track on Spotify
        val spotifyTrack = searchTrack(track.title, track.artist, authHeader)
            ?: return SpotifyEnrichmentResult.TrackNotFound

        // Small delay to be nice to the API
        delay(RATE_LIMIT_DELAY_MS)

        // Step 2: Fetch audio features
        val audioFeatures = fetchAudioFeatures(spotifyTrack.id, authHeader)
            ?: return SpotifyEnrichmentResult.AudioFeaturesNotAvailable

        // Step 3: Update metadata with Spotify data
        updateMetadataWithSpotifyData(track.id, existingMetadata, spotifyTrack, audioFeatures)

        return SpotifyEnrichmentResult.Success(spotifyTrack.id, audioFeatures)
    }

    /**
     * Search for a track on Spotify with fallback strategies.
     * Strategy 1: Exact title + artist search
     * Strategy 2: Title-only search (when artist search fails)
     */
    private suspend fun searchTrack(
        title: String,
        artist: String,
        authHeader: String
    ): SpotifyTrack? {
        try {
            // Strategy 1: Search with both title and artist
            val query = buildSearchQuery(title, artist)
            Log.d(TAG, "Spotify search query: $query")

            val response = spotifyApi.searchTracks(
                authorization = authHeader,
                query = query,
                limit = 5
            )

            if (!response.isSuccessful) {
                Log.e(TAG, "Search failed: ${response.code()} - ${response.errorBody()?.string()}")
                return null
            }

            val searchResponse = response.body()
            val tracks = searchResponse?.tracks?.items
            
            if (!tracks.isNullOrEmpty()) {
                // Find best match
                val bestMatch = findBestMatch(title, artist, tracks)
                if (bestMatch != null) {
                    Log.d(TAG, "Found match: '${bestMatch.name}' by '${bestMatch.primaryArtistName}' (${bestMatch.id})")
                    return bestMatch
                }
            }
            
            // Strategy 2: Try title-only search as fallback
            // This helps when artist name is slightly different (e.g., "The Beatles" vs "Beatles")
            Log.d(TAG, "No match with artist filter, trying title-only search for '$title'")
            val titleOnlyResult = searchByTitleOnly(title, artist, authHeader)
            if (titleOnlyResult != null) {
                Log.d(TAG, "Found match via title-only search: '${titleOnlyResult.name}' by '${titleOnlyResult.primaryArtistName}'")
                return titleOnlyResult
            }
            
            Log.d(TAG, "No tracks found for '$title' by '$artist'")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            return null
        }
    }
    
    /**
     * Search Spotify by title only, then verify artist matches.
     * This helps when the exact artist search fails due to name variations.
     */
    private suspend fun searchByTitleOnly(
        title: String,
        artist: String,
        authHeader: String
    ): SpotifyTrack? {
        try {
            val cleanTitle = cleanTrackTitle(title)
            val query = "track:\"$cleanTitle\""
            
            delay(RATE_LIMIT_DELAY_MS) // Be nice to the API
            
            val response = spotifyApi.searchTracks(
                authorization = authHeader,
                query = query,
                limit = 10 // Get more results for better matching
            )
            
            if (!response.isSuccessful) return null
            
            val tracks = response.body()?.tracks?.items ?: return null
            
            // Find best match - title should be very similar, artist should have some overlap
            return findBestMatchWithRelaxedArtist(title, artist, tracks)
        } catch (e: Exception) {
            Log.w(TAG, "Title-only search failed", e)
            return null
        }
    }
    
    /**
     * Find best match with relaxed artist matching.
     * Requires high title similarity but accepts partial artist matches.
     */
    private fun findBestMatchWithRelaxedArtist(
        searchTitle: String,
        searchArtist: String,
        tracks: List<SpotifyTrack>
    ): SpotifyTrack? {
        val normalizedTitle = normalizeString(searchTitle)
        val searchArtists = me.avinas.tempo.utils.ArtistParser.getAllArtists(searchArtist)
            .map { normalizeString(it) }

        return tracks
            .map { track ->
                val titleSimilarity = calculateSimilarity(normalizedTitle, normalizeString(track.name))
                
                // For relaxed matching, we need high title similarity
                if (titleSimilarity < 0.85) {
                    return@map track to 0.0
                }
                
                // Check for any artist word overlap
                val spotifyArtists = track.allArtistNames.split(", ").map { normalizeString(it) }
                val artistSimilarity = calculateBestArtistMatch(searchArtists, spotifyArtists)
                
                // Lower threshold for artist in relaxed mode
                if (artistSimilarity < 0.3) {
                    return@map track to 0.0
                }
                
                // Weight title more heavily for title-only search fallback
                val combinedScore = (titleSimilarity * 0.75) + (artistSimilarity * 0.25)
                track to combinedScore
            }
            .filter { (_, score) -> score >= 0.7 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /**
     * Build optimized search query for Spotify.
     */
    private fun buildSearchQuery(title: String, artist: String): String {
        // Clean up title (remove featuring, remixes, etc. for better matching)
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(artist)
        
        return "track:\"$cleanTitle\" artist:\"$cleanArtist\""
    }

    /**
     * Clean track title for better search matching.
     * Uses ArtistParser utility for robust cleaning.
     */
    private fun cleanTrackTitle(title: String): String {
        return me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(title)
    }

    /**
     * Clean artist name for better search matching.
     * Uses ArtistParser to get the primary artist for more focused search.
     */
    private fun cleanArtistName(artist: String): String {
        return me.avinas.tempo.utils.ArtistParser.getPrimaryArtist(artist)
    }

    /**
     * Find the best matching track from search results.
     * Uses enhanced matching that considers all artists.
     */
    private fun findBestMatch(
        searchTitle: String,
        searchArtist: String,
        tracks: List<SpotifyTrack>
    ): SpotifyTrack? {
        val normalizedTitle = normalizeString(searchTitle)
        val searchArtists = me.avinas.tempo.utils.ArtistParser.getAllArtists(searchArtist)
            .map { normalizeString(it) }

        return tracks
            .map { track ->
                val titleSimilarity = calculateSimilarity(normalizedTitle, normalizeString(track.name))
                
                // Check similarity against all artists in the search string
                // and all artists on the Spotify track
                val spotifyArtists = track.allArtistNames.split(", ").map { normalizeString(it) }
                val artistSimilarity = calculateBestArtistMatch(searchArtists, spotifyArtists)
                
                val combinedScore = (titleSimilarity * 0.6) + (artistSimilarity * 0.4)
                track to combinedScore
            }
            .filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /**
     * Calculate the best match score between two sets of artists.
     */
    private fun calculateBestArtistMatch(
        searchArtists: List<String>,
        trackArtists: List<String>
    ): Double {
        if (searchArtists.isEmpty() || trackArtists.isEmpty()) return 0.0
        
        // Find the best matching pair
        var bestScore = 0.0
        for (searchArtist in searchArtists) {
            for (trackArtist in trackArtists) {
                val score = calculateSimilarity(searchArtist, trackArtist)
                if (score > bestScore) bestScore = score
            }
        }
        
        // Bonus if multiple artists match
        var matchCount = 0
        for (searchArtist in searchArtists) {
            for (trackArtist in trackArtists) {
                if (calculateSimilarity(searchArtist, trackArtist) >= 0.7) {
                    matchCount++
                    break
                }
            }
        }
        
        // Add small bonus for matching multiple artists
        val multiMatchBonus = if (matchCount > 1) 0.1 else 0.0
        return (bestScore + multiMatchBonus).coerceAtMost(1.0)
    }

    /**
     * Normalize string for comparison.
     */
    private fun normalizeString(str: String): String {
        return str.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Calculate similarity between two strings (simple Jaccard-like similarity).
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        // Check if one contains the other
        if (s1.contains(s2) || s2.contains(s1)) return 0.9

        // Word-based similarity
        val words1 = s1.split(" ").toSet()
        val words2 = s2.split(" ").toSet()
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    // Track if audio features API is available (Spotify deprecated this endpoint for third-party apps in late 2024)
    @Volatile
    private var audioFeaturesAvailable = true
    private var lastAudioFeaturesCheck = 0L
    private val AUDIO_FEATURES_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // Re-check once per day

    /**
     * Fetch audio features for a track.
     * 
     * Note: Spotify deprecated the audio-features endpoint for third-party apps in late 2024.
     * This method gracefully handles 403 responses and caches the unavailability state
     * to avoid unnecessary API calls.
     */
    private var audioFeaturesSkipLoggedOnce = false // Log "skipping" message only once per session
    
    private suspend fun fetchAudioFeatures(
        spotifyId: String,
        authHeader: String
    ): SpotifyAudioFeatures? {
        // Check if we already know audio features are unavailable
        val now = System.currentTimeMillis()
        if (!audioFeaturesAvailable && (now - lastAudioFeaturesCheck) < AUDIO_FEATURES_CHECK_INTERVAL_MS) {
            // Only log once per session to reduce spam
            if (!audioFeaturesSkipLoggedOnce) {
                Log.d(TAG, "Skipping audio features fetch - API deprecated by Spotify (Nov 2024)")
                audioFeaturesSkipLoggedOnce = true
            }
            return null
        }
        
        try {
            val response = spotifyApi.getAudioFeatures(
                authorization = authHeader,
                trackId = spotifyId
            )

            if (!response.isSuccessful) {
                val errorCode = response.code()
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Audio features failed: $errorCode - $errorBody")
                
                // 403 means the API is not available for this app (deprecated by Spotify)
                if (errorCode == 403) {
                    Log.w(TAG, "Audio features API unavailable (403) - Spotify deprecated this endpoint for third-party apps")
                    audioFeaturesAvailable = false
                    lastAudioFeaturesCheck = now
                    return null
                }
                
                // 404 means audio features not available for this specific track
                if (errorCode == 404) {
                    return null
                }
                return null
            }

            // API is working - reset availability flag
            audioFeaturesAvailable = true
            return response.body()
        } catch (e: Exception) {
            Log.e(TAG, "Audio features error", e)
            return null
        }
    }
    
    /**
     * Check if audio features API is currently available.
     * Returns false if we've received a 403 recently.
     */
    fun isAudioFeaturesAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (!audioFeaturesAvailable && (now - lastAudioFeaturesCheck) >= AUDIO_FEATURES_CHECK_INTERVAL_MS) {
            // Reset to allow re-checking
            audioFeaturesAvailable = true
        }
        return audioFeaturesAvailable
    }
    
    /**
     * Fetch genres for an artist from Spotify.
     * 
     * Spotify's track search doesn't include genres - they're only available on artist profiles.
     * This makes an additional API call to get the artist's genres.
     * 
     * @param spotifyArtistId The Spotify artist ID
     * @param authHeader The authorization header with bearer token
     * @return List of genres for this artist, or empty list if unavailable
     */
    private suspend fun fetchArtistGenres(
        spotifyArtistId: String,
        authHeader: String
    ): List<String> {
        return try {
            val response = spotifyApi.getArtist(authHeader, spotifyArtistId)
            if (response.isSuccessful) {
                val artist = response.body()
                val genres = artist?.genres ?: emptyList()
                
                // Also update the Artist table with genres if we can find it
                if (genres.isNotEmpty()) {
                    try {
                        val existingArtist = artistDao.getArtistBySpotifyId(spotifyArtistId)
                        if (existingArtist != null && existingArtist.genres.isEmpty()) {
                            artistDao.updateGenres(existingArtist.id, genres)
                            Log.d(TAG, "Updated artist '${existingArtist.name}' with ${genres.size} genres")
                        }
                    } catch (e: Exception) {
                        // Non-critical, just log
                        Log.w(TAG, "Could not update artist genres in database", e)
                    }
                }
                
                genres
            } else {
                Log.w(TAG, "Failed to fetch artist genres: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching artist genres", e)
            emptyList()
        }
    }
    
    /**
     * Fetch genres for multiple artists in ONE API call.
     * 
     * Much more efficient than calling fetchArtistGenres() multiple times.
     * Useful for collab tracks with multiple artists.
     * 
     * @param artistIds List of Spotify artist IDs (max 50)
     * @param authHeader The authorization header with bearer token
     * @return Map of artist ID to their genres
     */
    suspend fun fetchMultipleArtistGenres(
        artistIds: List<String>,
        authHeader: String
    ): Map<String, List<String>> {
        if (artistIds.isEmpty()) return emptyMap()
        
        return try {
            // Batch into chunks of 50 (API limit)
            val results = mutableMapOf<String, List<String>>()
            
            artistIds.chunked(50).forEach { chunk ->
                val idsParam = chunk.joinToString(",")
                val response = spotifyApi.getMultipleArtists(authHeader, idsParam)
                
                if (response.isSuccessful) {
                    response.body()?.artists?.forEach { artist ->
                        if (artist != null) {
                            results[artist.id] = artist.genres
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to fetch multiple artists: ${response.code()}")
                }
            }
            
            Log.d(TAG, "Batch fetched genres for ${results.size}/${artistIds.size} artists")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching multiple artist genres", e)
            emptyMap()
        }
    }
    
    /**
     * Get combined genres from multiple artists.
     * 
     * For collab tracks, this merges genres from all artists,
     * prioritizing the primary artist's genres.
     * 
     * @param artistIds List of Spotify artist IDs (primary first)
     * @param authHeader The authorization header with bearer token
     * @return Combined list of unique genres (primary artist's first)
     */
    suspend fun fetchCombinedArtistGenres(
        artistIds: List<String>,
        authHeader: String
    ): List<String> {
        if (artistIds.isEmpty()) return emptyList()
        
        val genresMap = fetchMultipleArtistGenres(artistIds, authHeader)
        
        // Combine genres, keeping primary artist's genres first
        val combinedGenres = mutableListOf<String>()
        artistIds.forEach { artistId ->
            genresMap[artistId]?.forEach { genre ->
                if (genre !in combinedGenres) {
                    combinedGenres.add(genre)
                }
            }
        }
        
        return combinedGenres
    }

    /**
     * Update enriched metadata with Spotify data.
     */
    private suspend fun updateMetadataWithSpotifyData(
        trackId: Long,
        existingMetadata: EnrichedMetadata?,
        spotifyTrack: SpotifyTrack,
        audioFeatures: SpotifyAudioFeatures
    ) {
        val audioFeaturesJson = serializeAudioFeatures(audioFeatures)
        val primaryArtistId = spotifyTrack.artists.firstOrNull()?.id
        
        // Check if we should update album art based on source priority
        // Spotify (priority 6) can replace any lower priority source
        val currentSource = existingMetadata?.albumArtSource ?: me.avinas.tempo.data.local.entities.AlbumArtSource.NONE
        val hasSpotifyArt = spotifyTrack.album.mediumImageUrl != null
        val shouldUpdateArt = hasSpotifyArt && (existingMetadata?.albumArtUrl.isNullOrBlank() || 
            currentSource.shouldBeReplacedBy(me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY))

        val updated = existingMetadata?.copy(
            spotifyId = spotifyTrack.id,
            spotifyArtistId = primaryArtistId,
            spotifyTrackUrl = spotifyTrack.externalUrls.spotify,
            audioFeaturesJson = audioFeaturesJson,
            spotifyPreviewUrl = spotifyTrack.previewUrl,
            // Use Spotify preview if we don't have one yet
            previewUrl = existingMetadata.previewUrl ?: spotifyTrack.previewUrl,
            // Update album art if Spotify has higher priority
            albumArtUrl = if (shouldUpdateArt) spotifyTrack.album.mediumImageUrl else existingMetadata.albumArtUrl,
            albumArtUrlSmall = if (shouldUpdateArt) spotifyTrack.album.smallImageUrl ?: existingMetadata.albumArtUrlSmall else existingMetadata.albumArtUrlSmall,
            albumArtUrlLarge = if (shouldUpdateArt) spotifyTrack.album.largeImageUrl ?: existingMetadata.albumArtUrlLarge else existingMetadata.albumArtUrlLarge,
            albumArtSource = if (shouldUpdateArt) me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY else existingMetadata.albumArtSource,
            albumTitle = existingMetadata.albumTitle ?: spotifyTrack.album.name,
            cacheTimestamp = System.currentTimeMillis()
        ) ?: EnrichedMetadata(
            trackId = trackId,
            spotifyId = spotifyTrack.id,
            spotifyArtistId = primaryArtistId,
            spotifyTrackUrl = spotifyTrack.externalUrls.spotify,
            audioFeaturesJson = audioFeaturesJson,
            spotifyPreviewUrl = spotifyTrack.previewUrl,
            previewUrl = spotifyTrack.previewUrl,
            albumArtUrl = spotifyTrack.album.mediumImageUrl,
            albumArtUrlSmall = spotifyTrack.album.smallImageUrl,
            albumArtUrlLarge = spotifyTrack.album.largeImageUrl,
            albumArtSource = if (hasSpotifyArt) me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY else me.avinas.tempo.data.local.entities.AlbumArtSource.NONE,
            albumTitle = spotifyTrack.album.name,
            cacheTimestamp = System.currentTimeMillis()
        )
        
        if (shouldUpdateArt && existingMetadata != null) {
            Log.d(TAG, "Spotify: Replacing ${existingMetadata.albumArtSource} album art with SPOTIFY source (with audio features)")
        }

        enrichedMetadataDao.upsert(updated)
        Log.d(TAG, "Saved Spotify data for track $trackId (artistId: $primaryArtistId, url: ${spotifyTrack.externalUrls.spotify})")
        
        // Update Artist table if we have artist info
        if (primaryArtistId != null) {
            // 1. Try to link by name if not already linked
            val artistName = spotifyTrack.artists.firstOrNull()?.name ?: "Unknown"
            artistDao.updateSpotifyIdByName(artistName.toString(), primaryArtistId)
            
            // Note: We cannot update the image here because SpotifyTrack.artists (simplified object)
            // does not contain images. Images are fetched separately via fetchMissingArtistImages.
        }
    }

    /**
     * Serialize audio features to JSON.
     */
    private fun serializeAudioFeatures(features: SpotifyAudioFeatures): String {
        val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
        return adapter.toJson(features)
    }

    /**
     * Parse existing audio features from stored JSON.
     */
    private fun parseExistingAudioFeatures(metadata: EnrichedMetadata): SpotifyEnrichmentResult {
        val json = metadata.audioFeaturesJson ?: return SpotifyEnrichmentResult.AudioFeaturesNotAvailable
        val spotifyId = metadata.spotifyId ?: return SpotifyEnrichmentResult.TrackNotFound

        return try {
            val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
            val features = adapter.fromJson(json)
            if (features != null) {
                SpotifyEnrichmentResult.Success(spotifyId, features)
            } else {
                SpotifyEnrichmentResult.AudioFeaturesNotAvailable
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored audio features", e)
            SpotifyEnrichmentResult.AudioFeaturesNotAvailable
        }
    }

    /**
     * Get parsed audio features from an enriched metadata record.
     */
    fun getAudioFeatures(metadata: EnrichedMetadata): SpotifyAudioFeatures? {
        val json = metadata.audioFeaturesJson ?: return null
        return try {
            val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
            adapter.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse audio features", e)
            null
        }
    }

    /**
     * Batch enrich multiple tracks with Spotify data - OPTIMIZED.
     * 
     * Optimization strategy:
     * 1. Phase 1: Search for all tracks (1 API call per track - unavoidable)
     * 2. Phase 2: Batch fetch audio features (1 API call per 100 tracks!)
     * 
     * This reduces API calls from 2N to N+ceil(N/100) for N tracks.
     * For 50 tracks: 100 calls → 51 calls (49% reduction)
     * For 100 tracks: 200 calls → 101 calls (50% reduction)
     */
    suspend fun enrichBatch(
        tracks: List<Pair<Track, EnrichedMetadata?>>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): BatchEnrichmentResult {
        if (!isAvailable()) {
            return BatchEnrichmentResult(
                total = tracks.size,
                successful = 0,
                failed = 0,
                skipped = tracks.size,
                reason = "Spotify not connected"
            )
        }

        val accessToken = authManager.getValidAccessToken()
        if (accessToken == null) {
            return BatchEnrichmentResult(
                total = tracks.size,
                successful = 0,
                failed = 0,
                skipped = tracks.size,
                reason = "Failed to get access token"
            )
        }

        val authHeader = "Bearer $accessToken"
        
        var successful = 0
        var failed = 0
        var skipped = 0

        // Phase 1: Search for tracks and collect Spotify IDs
        // Store: (original track, metadata, spotifyTrack)
        val searchResults = mutableListOf<Triple<Track, EnrichedMetadata?, SpotifyTrack>>()
        
        tracks.forEachIndexed { index, (track, metadata) ->
            onProgress?.invoke(index + 1, tracks.size * 2) // First half of progress
            
            // Skip if already has complete Spotify data
            if (metadata?.spotifyId != null && metadata.audioFeaturesJson != null) {
                skipped++
                return@forEachIndexed
            }

            val spotifyTrack = searchTrack(track.title, track.artist, authHeader)
            if (spotifyTrack != null) {
                searchResults.add(Triple(track, metadata, spotifyTrack))
            } else {
                failed++
            }
            
            // Small delay between searches
            delay(RATE_LIMIT_DELAY_MS)
        }
        
        if (searchResults.isEmpty()) {
            return BatchEnrichmentResult(
                total = tracks.size,
                successful = 0,
                failed = failed,
                skipped = skipped,
                reason = null
            )
        }

        // Phase 2: Batch fetch audio features (up to 100 per request)
        val spotifyIds = searchResults.map { it.third.id }
        val audioFeaturesMap = fetchBatchAudioFeatures(spotifyIds, authHeader)
        
        // Phase 3: Update metadata for all tracks
        searchResults.forEachIndexed { index, (track, existingMetadata, spotifyTrack) ->
            onProgress?.invoke(tracks.size + index + 1, tracks.size * 2) // Second half
            
            val audioFeatures = audioFeaturesMap[spotifyTrack.id]
            if (audioFeatures != null) {
                updateMetadataWithSpotifyData(track.id, existingMetadata, spotifyTrack, audioFeatures)
                successful++
            } else {
                // Still save basic metadata even without audio features
                updateMetadataWithBasicSpotifyData(track.id, existingMetadata, spotifyTrack)
                successful++ // Count as partial success
            }
        }

        return BatchEnrichmentResult(
            total = tracks.size,
            successful = successful,
            failed = failed,
            skipped = skipped,
            reason = null
        )
    }
    
    /**
     * Fetch audio features for multiple tracks in batches of 100.
     * Returns a map of spotifyId -> audioFeatures
     * 
     * Note: This endpoint is also deprecated by Spotify for third-party apps.
     * Returns empty map if audio features API is unavailable (403).
     */
    private suspend fun fetchBatchAudioFeatures(
        spotifyIds: List<String>,
        authHeader: String
    ): Map<String, SpotifyAudioFeatures> {
        // Check if audio features API is available before making requests
        if (!audioFeaturesAvailable) {
            val now = System.currentTimeMillis()
            if ((now - lastAudioFeaturesCheck) < AUDIO_FEATURES_CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping batch audio features - API unavailable (cached)")
                return emptyMap()
            }
        }
        
        val result = mutableMapOf<String, SpotifyAudioFeatures>()
        
        // Spotify allows up to 100 IDs per request
        spotifyIds.chunked(100).forEach { chunk ->
            try {
                val idsParam = chunk.joinToString(",")
                val response = spotifyApi.getMultipleAudioFeatures(
                    authorization = authHeader,
                    ids = idsParam
                )
                
                if (response.isSuccessful) {
                    // API is working - reset availability flag
                    audioFeaturesAvailable = true
                    response.body()?.audioFeatures?.forEachIndexed { index, features ->
                        if (features != null && index < chunk.size) {
                            result[chunk[index]] = features
                        }
                    }
                } else {
                    val errorCode = response.code()
                    Log.w(TAG, "Batch audio features failed: $errorCode")
                    
                    // 403 means API is not available for this app
                    if (errorCode == 403) {
                        Log.w(TAG, "Batch audio features API unavailable (403) - Spotify deprecated this endpoint")
                        audioFeaturesAvailable = false
                        lastAudioFeaturesCheck = System.currentTimeMillis()
                        return result // Return whatever we have so far
                    }
                }
                
                // Small delay between batch requests
                delay(RATE_LIMIT_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching batch audio features", e)
            }
        }
        
        Log.d(TAG, "Fetched audio features for ${result.size}/${spotifyIds.size} tracks in ${(spotifyIds.size + 99) / 100} API calls")
        return result
    }
    
    /**
     * Update metadata with basic Spotify data (without audio features).
     * Used when audio features are not available for a track.
     */
    private suspend fun updateMetadataWithBasicSpotifyData(
        trackId: Long,
        existingMetadata: EnrichedMetadata?,
        spotifyTrack: SpotifyTrack
    ) {
        // Check if we should update album art based on source priority
        // Spotify (priority 6) can replace any lower priority source
        val currentSource = existingMetadata?.albumArtSource ?: me.avinas.tempo.data.local.entities.AlbumArtSource.NONE
        val shouldUpdateArt = existingMetadata?.albumArtUrl.isNullOrBlank() || 
            currentSource.shouldBeReplacedBy(me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY)
        
        val updated = existingMetadata?.copy(
            spotifyId = spotifyTrack.id,
            albumTitle = existingMetadata.albumTitle ?: spotifyTrack.album.name,
            albumArtUrl = if (shouldUpdateArt) spotifyTrack.album.mediumImageUrl ?: existingMetadata.albumArtUrl else existingMetadata.albumArtUrl,
            albumArtUrlSmall = if (shouldUpdateArt) spotifyTrack.album.smallImageUrl ?: existingMetadata.albumArtUrlSmall else existingMetadata.albumArtUrlSmall,
            albumArtUrlLarge = if (shouldUpdateArt) spotifyTrack.album.largeImageUrl ?: existingMetadata.albumArtUrlLarge else existingMetadata.albumArtUrlLarge,
            albumArtSource = if (shouldUpdateArt && spotifyTrack.album.mediumImageUrl != null) me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY else existingMetadata.albumArtSource,
            trackDurationMs = spotifyTrack.durationMs,
            isrc = spotifyTrack.externalIds?.isrc,
            spotifyTrackUrl = spotifyTrack.externalUrls.spotify,
            cacheTimestamp = System.currentTimeMillis()
        ) ?: EnrichedMetadata(
            trackId = trackId,
            spotifyId = spotifyTrack.id,
            albumTitle = spotifyTrack.album.name,
            albumArtUrl = spotifyTrack.album.mediumImageUrl,
            albumArtUrlSmall = spotifyTrack.album.smallImageUrl,
            albumArtUrlLarge = spotifyTrack.album.largeImageUrl,
            albumArtSource = if (spotifyTrack.album.mediumImageUrl != null) me.avinas.tempo.data.local.entities.AlbumArtSource.SPOTIFY else me.avinas.tempo.data.local.entities.AlbumArtSource.NONE,
            trackDurationMs = spotifyTrack.durationMs,
            isrc = spotifyTrack.externalIds?.isrc,
            spotifyTrackUrl = spotifyTrack.externalUrls.spotify,
            cacheTimestamp = System.currentTimeMillis()
        )
        
        if (shouldUpdateArt && spotifyTrack.album.mediumImageUrl != null && existingMetadata != null) {
            android.util.Log.d(TAG, "Spotify: Replacing ${existingMetadata.albumArtSource} album art with SPOTIFY source")
        }

        enrichedMetadataDao.upsert(updated)
    }

    data class BatchEnrichmentResult(
        val total: Int,
        val successful: Int,
        val failed: Int,
        val skipped: Int,
        val reason: String?
    )

    /**
     * Result of basic metadata enrichment from Spotify.
     * Used as fallback when MusicBrainz doesn't have the track.
     */
    sealed class BasicMetadataResult {
        data class Success(
            val spotifyId: String,
            val spotifyArtistId: String?,
            val spotifyArtistIds: List<String>, // All artist IDs for collab tracks
            val verifiedArtistName: String, // The correct artist name from Spotify
            val allArtistNames: String, // All artists formatted (e.g., "Artist1, Artist2")
            val albumTitle: String?,
            val albumArtUrl: String?,
            val albumArtUrlSmall: String?,
            val albumArtUrlLarge: String?,
            val releaseYear: Int?,
            val genres: List<String>,
            val durationMs: Long?,
            val isrc: String?,
            val audioFeaturesJson: String?, // Serialized audio features JSON (ready to store in DB)
            val previewUrl: String?, // Spotify 30s preview URL for ReccoBeats audio analysis fallback
            val spotifyTrackUrl: String? // Direct link to track on Spotify
        ) : BasicMetadataResult()
        
        object NotConnected : BasicMetadataResult()
        object TrackNotFound : BasicMetadataResult()
        data class Error(val message: String) : BasicMetadataResult()
    }

    /**
     * Fetch basic track metadata AND audio features from Spotify in optimized single flow.
     * This combines search + audio features fetch to minimize API calls.
     * 
     * Also verifies and corrects artist names from Spotify data.
     * 
     * @param track The track to look up
     * @return BasicMetadataResult with album art, verified artist name, and audio features
     */
    suspend fun fetchBasicMetadata(track: Track): BasicMetadataResult {
        // Check if Spotify is connected
        if (!isAvailable()) {
            return BasicMetadataResult.NotConnected
        }

        Log.d(TAG, "Fetching metadata from Spotify: '${track.title}' by '${track.artist}'")

        // Get valid access token
        val accessToken = authManager.getValidAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Failed to get valid access token for basic metadata")
            return BasicMetadataResult.NotConnected
        }

        val authHeader = "Bearer $accessToken"

        // Search for the track on Spotify
        val spotifyTrack = searchTrack(track.title, track.artist, authHeader)
            ?: return BasicMetadataResult.TrackNotFound

        // Extract metadata from the search result
        val album = spotifyTrack.album
        val releaseYear = try {
            album.releaseDate.take(4).toIntOrNull()
        } catch (e: Exception) {
            null
        }
        
        // Get verified artist name(s) from Spotify
        val primaryArtist = spotifyTrack.artists.firstOrNull()
        val allArtistIds = spotifyTrack.artists.map { it.id }
        val verifiedArtistName = primaryArtist?.name ?: track.artist
        val allArtistNames = spotifyTrack.artists.joinToString(", ") { it.name }
        
        Log.d(TAG, "Verified artist: '$verifiedArtistName' (all: '$allArtistNames')")
        
        // OPTIMIZATION: Fetch genres and audio features in parallel
        // This reduces total API wait time from ~200ms to ~100ms
        val (genres, audioFeatures) = coroutineScope {
            val genresDeferred = async {
                if (primaryArtist != null) {
                    fetchArtistGenres(primaryArtist.id, authHeader)
                } else {
                    emptyList()
                }
            }
            
            val audioFeaturesDeferred = async {
                fetchAudioFeatures(spotifyTrack.id, authHeader)
            }
            
            Pair(genresDeferred.await(), audioFeaturesDeferred.await())
        }
        
        if (genres.isNotEmpty()) {
            Log.d(TAG, "Found ${genres.size} genres for '${track.title}': ${genres.take(3).joinToString(", ")}")
        }
        
        // Serialize audio features to JSON if available
        val audioFeaturesJson = audioFeatures?.let { serializeAudioFeatures(it) }
        
        // Only log at debug level - avoid spam since audio features API is deprecated
        if (audioFeatures == null && !audioFeaturesAvailable) {
            // Already logged at warn level when we discovered it was unavailable
        } else if (audioFeatures == null) {
            Log.d(TAG, "Audio features not available for '${track.title}'")
        }

        return BasicMetadataResult.Success(
            spotifyId = spotifyTrack.id,
            spotifyArtistId = primaryArtist?.id,
            spotifyArtistIds = allArtistIds,
            verifiedArtistName = verifiedArtistName,
            allArtistNames = allArtistNames,
            albumTitle = album.name,
            albumArtUrl = album.mediumImageUrl ?: album.largeImageUrl,
            albumArtUrlSmall = album.smallImageUrl,
            albumArtUrlLarge = album.largeImageUrl,
            releaseYear = releaseYear,
            genres = genres, // Genres fetched from Spotify artist endpoint
            durationMs = spotifyTrack.durationMs,
            isrc = spotifyTrack.externalIds?.isrc,
            audioFeaturesJson = audioFeaturesJson,
            previewUrl = spotifyTrack.previewUrl,
            spotifyTrackUrl = spotifyTrack.externalUrls.spotify
        )
    }

    /**
     * Super-efficient bulk enrichment for tracks that already have Spotify IDs.
     * 
     * Use this when you have tracks with spotifyId but missing audio features.
     * Makes only 1 API call per 100 tracks!
     * 
     * @param metadataList List of EnrichedMetadata with spotifyId set
     * @return Number of tracks successfully enriched with audio features
     */
    suspend fun enrichAudioFeaturesForKnownTracks(
        metadataList: List<EnrichedMetadata>
    ): Int {
        if (!isAvailable()) {
            Log.d(TAG, "Spotify not connected, skipping bulk audio features enrichment")
            return 0
        }

        // Filter to tracks with Spotify ID but missing audio features
        val needsFeatures = metadataList.filter { 
            it.spotifyId != null && it.audioFeaturesJson == null 
        }
        
        if (needsFeatures.isEmpty()) {
            Log.d(TAG, "No tracks need audio features enrichment")
            return 0
        }

        val accessToken = authManager.getValidAccessToken() ?: return 0
        val authHeader = "Bearer $accessToken"
        
        val spotifyIds = needsFeatures.mapNotNull { it.spotifyId }
        val audioFeaturesMap = fetchBatchAudioFeatures(spotifyIds, authHeader)
        
        var enrichedCount = 0
        needsFeatures.forEach { metadata ->
            val features = audioFeaturesMap[metadata.spotifyId]
            if (features != null) {
                val audioFeaturesJson = serializeAudioFeatures(features)
                val updated = metadata.copy(
                    audioFeaturesJson = audioFeaturesJson,
                    spotifyEnrichmentStatus = me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus.ENRICHED,
                    cacheTimestamp = System.currentTimeMillis()
                )
                enrichedMetadataDao.upsert(updated)
                enrichedCount++
            }
        }
        
        Log.i(TAG, "Bulk enriched audio features for $enrichedCount/${needsFeatures.size} tracks")
        return enrichedCount
    }

    /**
     * Fetch artist image URL from Spotify.
     * 
     * @param spotifyArtistId The Spotify artist ID
     * @return The artist image URL, or null if not available
     */
    suspend fun fetchArtistImage(spotifyArtistId: String): String? {
        if (!isAvailable()) {
            return null
        }

        val accessToken = authManager.getValidAccessToken() ?: return null
        val authHeader = "Bearer $accessToken"

        return try {
            val response = spotifyApi.getArtist(authHeader, spotifyArtistId)
            if (response.isSuccessful) {
                val artist = response.body()
                artist?.mediumImageUrl ?: artist?.largeImageUrl
            } else {
                Log.e(TAG, "Failed to fetch artist: ${response.code()} - ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching artist image", e)
            null
        }
    }
    
    /**
     * Search for an artist by name and fetch their image.
     * This is useful when we don't have a Spotify artist ID.
     * 
     * Approach (in priority order):
     * 1. DIRECT ARTIST SEARCH: Search for artists directly (most reliable, returns images)
     * 2. TRACK SEARCH FALLBACK: Search for tracks by this artist and extract artist info
     * 
     * FIX: Previously this would take the first artist from the first track, which could
     * be wrong if the searched artist was a featured artist. Now we use direct artist 
     * search first, and validate name matches before caching.
     * 
     * @param artistName The artist name to search for
     * @return The artist image URL, or null if not found
     */
    suspend fun searchAndFetchArtistImage(artistName: String): String? {
        if (!isAvailable()) {
            return null
        }
        
        val accessToken = authManager.getValidAccessToken() ?: return null
        val authHeader = "Bearer $accessToken"
        val normalizedSearchName = normalizeArtistNameForComparison(artistName)
        
        return try {
            // PRIORITY 1: Direct artist search (most reliable!)
            // This searches Spotify's artist catalog directly and returns full artist info with images
            val artistSearchResult = searchArtistsDirectly(authHeader, artistName, normalizedSearchName)
            if (artistSearchResult != null) {
                return artistSearchResult
            }
            
            // PRIORITY 2: Fallback to track search
            // Search for tracks by this artist and find the correct artist in results
            Log.d(TAG, "Direct artist search failed for '$artistName', trying track search fallback")
            searchArtistViaTrackSearch(authHeader, artistName, normalizedSearchName)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for artist image", e)
            null
        }
    }
    
    /**
     * Search for artists directly using Spotify's artist search.
     * Returns the image URL of the best matching artist.
     */
    private suspend fun searchArtistsDirectly(
        authHeader: String,
        artistName: String,
        normalizedSearchName: String
    ): String? {
        return try {
            val response = spotifyApi.searchArtists(authHeader, artistName, "artist", limit = 5)
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Artist search failed: ${response.code()}")
                return null
            }
            
            val artists = response.body()?.artists?.items ?: emptyList()
            if (artists.isEmpty()) {
                Log.d(TAG, "No artists found for: $artistName")
                return null
            }
            
            // Find the best matching artist by name
            var bestMatch: SpotifyFullArtist? = null
            
            // First pass: exact match
            for (artist in artists) {
                val normalizedArtistName = normalizeArtistNameForComparison(artist.name)
                if (normalizedArtistName == normalizedSearchName) {
                    bestMatch = artist
                    Log.d(TAG, "Found exact artist match via direct search: ${artist.name} (${artist.id})")
                    break
                }
            }
            
            // Second pass: contains match
            if (bestMatch == null) {
                for (artist in artists) {
                    val normalizedArtistName = normalizeArtistNameForComparison(artist.name)
                    if (normalizedArtistName.contains(normalizedSearchName) ||
                        normalizedSearchName.contains(normalizedArtistName)) {
                        bestMatch = artist
                        Log.d(TAG, "Found partial artist match via direct search: ${artist.name} (${artist.id})")
                        break
                    }
                }
            }
            
            // Third pass: use first result only if it's reasonably close
            if (bestMatch == null && artists.isNotEmpty()) {
                val firstArtist = artists.first()
                val normalizedFirstName = normalizeArtistNameForComparison(firstArtist.name)
                // Only use first result if names share significant overlap
                val similarity = calculateNameSimilarity(normalizedSearchName, normalizedFirstName)
                if (similarity >= 0.5) {
                    bestMatch = firstArtist
                    Log.d(TAG, "Using first artist result (similarity=$similarity): ${firstArtist.name}")
                } else {
                    Log.w(TAG, "First artist result '${firstArtist.name}' doesn't match '$artistName' well enough (similarity=$similarity)")
                }
            }
            
            if (bestMatch == null) {
                return null
            }
            
            val imageUrl = bestMatch.mediumImageUrl ?: bestMatch.largeImageUrl
            
            if (imageUrl != null) {
                // Cache the result
                cacheArtistImageResult(artistName, bestMatch.id, imageUrl, bestMatch.name)
            }
            
            imageUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct artist search", e)
            null
        }
    }
    
    /**
     * Fallback: Search for tracks by the artist and extract artist info.
     */
    private suspend fun searchArtistViaTrackSearch(
        authHeader: String,
        artistName: String,
        normalizedSearchName: String
    ): String? {
        val searchQuery = "artist:$artistName"
        val response = spotifyApi.searchTracks(authHeader, searchQuery, "track", limit = 10)
        
        if (!response.isSuccessful) {
            Log.e(TAG, "Track search failed: ${response.code()} - ${response.errorBody()?.string()}")
            return null
        }
        
        val tracks = response.body()?.tracks?.items ?: emptyList()
        if (tracks.isEmpty()) {
            Log.d(TAG, "No tracks found for artist: $artistName")
            return null
        }
        
        // Find the correct artist by matching names across all tracks
        var matchedArtist: SpotifyArtist? = null
        
        // First pass: exact match
        for (track in tracks) {
            for (artist in track.artists) {
                val normalizedArtistName = normalizeArtistNameForComparison(artist.name)
                if (normalizedArtistName == normalizedSearchName) {
                    matchedArtist = artist
                    Log.d(TAG, "Found exact artist match via track search: ${artist.name} (${artist.id})")
                    break
                }
            }
            if (matchedArtist != null) break
        }
        
        // Second pass: partial match
        if (matchedArtist == null) {
            for (track in tracks) {
                for (artist in track.artists) {
                    val normalizedArtistName = normalizeArtistNameForComparison(artist.name)
                    if (normalizedArtistName.contains(normalizedSearchName) ||
                        normalizedSearchName.contains(normalizedArtistName)) {
                        matchedArtist = artist
                        Log.d(TAG, "Found partial artist match via track search: ${artist.name} (${artist.id})")
                        break
                    }
                }
                if (matchedArtist != null) break
            }
        }
        
        // DON'T use first artist as fallback - this causes mismatches
        if (matchedArtist == null) {
            Log.w(TAG, "No matching artist found in track search results for: $artistName")
            return null
        }
        
        // Fetch full artist details with images
        val artistResponse = spotifyApi.getArtist(authHeader, matchedArtist.id)
        
        if (!artistResponse.isSuccessful) {
            Log.e(TAG, "Failed to fetch artist details: ${artistResponse.code()}")
            return null
        }
        
        val fullArtist = artistResponse.body() ?: return null
        val imageUrl = fullArtist.mediumImageUrl ?: fullArtist.largeImageUrl
        
        if (imageUrl != null) {
            // Verify the fetched artist name still matches before caching
            val normalizedFetchedName = normalizeArtistNameForComparison(fullArtist.name)
            val isValidMatch = normalizedFetchedName == normalizedSearchName ||
                normalizedFetchedName.contains(normalizedSearchName) ||
                normalizedSearchName.contains(normalizedFetchedName)
            
            if (isValidMatch) {
                cacheArtistImageResult(artistName, matchedArtist.id, imageUrl, fullArtist.name)
            } else {
                Log.w(TAG, "Skipping cache for '$artistName' - name mismatch with '${fullArtist.name}'")
                return null
            }
        }
        
        return imageUrl
    }
    
    /**
     * Cache the artist image result in the database.
     */
    private suspend fun cacheArtistImageResult(
        searchedArtistName: String,
        spotifyArtistId: String,
        imageUrl: String,
        spotifyArtistName: String
    ) {
        val existingArtist = artistDao.getArtistByName(searchedArtistName)
        if (existingArtist != null) {
            artistDao.updateImageUrl(existingArtist.id, imageUrl)
            if (existingArtist.spotifyId.isNullOrBlank()) {
                artistDao.updateSpotifyId(existingArtist.id, spotifyArtistId)
            }
            Log.d(TAG, "Cached artist image for '$searchedArtistName' (matched: $spotifyArtistName): $imageUrl")
        }
    }
    
    /**
     * Calculate similarity between two normalized artist names.
     * Uses Jaccard similarity on words.
     */
    private fun calculateNameSimilarity(name1: String, name2: String): Double {
        val words1 = name1.split(" ").filter { it.isNotEmpty() }.toSet()
        val words2 = name2.split(" ").filter { it.isNotEmpty() }.toSet()
        
        if (words1.isEmpty() && words2.isEmpty()) return 1.0
        if (words1.isEmpty() || words2.isEmpty()) return 0.0
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return intersection.toDouble() / union
    }
    
    /**
     * Normalize artist name for comparison purposes.
     * Removes special characters, extra whitespace, and converts to lowercase.
     */
    private fun normalizeArtistNameForComparison(name: String): String {
        return name
            .lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove special chars
            .replace(Regex("\\s+"), " ") // Normalize whitespace
    }

    /**
     * Fetch and cache artist image for a given Spotify artist ID.
     * Updates both enriched_metadata and artists tables.
     * 
     * IMPORTANT: Updates artists table by both spotify_id AND artist name to handle
     * artists that were created from MediaSession metadata (without spotify_id).
     * 
     * @param spotifyArtistId The Spotify artist ID
     * @return The artist image URL if successfully fetched, null otherwise
     */
    suspend fun fetchAndCacheArtistImage(spotifyArtistId: String): String? {
        if (!isAvailable()) return null
        
        val accessToken = authManager.getValidAccessToken() ?: return null
        val authHeader = "Bearer $accessToken"
        
        // Fetch full artist details (includes name and image)
        val artist = try {
            val response = spotifyApi.getArtist(authHeader, spotifyArtistId)
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.e(TAG, "Failed to fetch artist: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching artist", e)
            null
        }
        
        if (artist == null) return null
        
        val imageUrl = artist.mediumImageUrl ?: artist.largeImageUrl ?: return null
        val artistName = artist.name ?: run {
            Log.w(TAG, "Artist $spotifyArtistId has no name")
            return null
        }
        
        // Update enriched_metadata table
        enrichedMetadataDao.updateArtistImageUrl(spotifyArtistId, imageUrl)
        
        // Update artists table by Spotify ID (for artists with spotify_id)
        artistDao.updateImageUrlBySpotifyId(spotifyArtistId, imageUrl)
        
        // CRITICAL FIX: Also update by artist name (for artists without spotify_id)
        // This handles artists created from MediaSession metadata
        val existingArtist = artistDao.getArtistByName(artistName)
        if (existingArtist != null) {
            artistDao.updateImageUrl(existingArtist.id, imageUrl)
            // Link the Spotify ID if not already set
            if (existingArtist.spotifyId.isNullOrBlank()) {
                artistDao.updateSpotifyId(existingArtist.id, spotifyArtistId)
                Log.d(TAG, "Linked Spotify ID for artist '$artistName' (ID=${existingArtist.id})")
            }
        }
        
        Log.d(TAG, "Cached artist image for '$artistName' ($spotifyArtistId): $imageUrl")
        
        return imageUrl
    }

    /**
     * Fetch and cache artist images for tracks that have Spotify artist IDs
     * but no cached artist images.
     * 
     * @param limit Maximum number of artists to process
     * @return Number of artist images fetched
     */
    suspend fun fetchMissingArtistImages(limit: Int = 10): Int {
        if (!isAvailable()) return 0
        
        val tracksNeedingImages = enrichedMetadataDao.getTracksNeedingArtistImage(limit)
        if (tracksNeedingImages.isEmpty()) return 0
        
        var fetchedCount = 0
        val processedArtistIds = mutableSetOf<String>()
        
        for (metadata in tracksNeedingImages) {
            val artistId = metadata.spotifyArtistId ?: continue
            if (artistId in processedArtistIds) continue
            
            val imageUrl = fetchAndCacheArtistImage(artistId)
            if (imageUrl != null) {
                fetchedCount++
                processedArtistIds.add(artistId)
            }
            
            // Small delay to be nice to the API
            delay(RATE_LIMIT_DELAY_MS)
        }
        
        Log.i(TAG, "Fetched $fetchedCount artist images")
        return fetchedCount
    }

    /**
     * Get Spotify artist ID from a track's Spotify data.
     * 
     * @param trackId The local track ID
     * @return The Spotify artist ID, or null if not available
     */
    suspend fun getSpotifyArtistIdForTrack(trackId: Long): String? {
        val metadata = enrichedMetadataDao.forTrackSync(trackId) ?: return null
        return metadata.spotifyArtistId
    }
    
    // =====================
    // Artist-Derived Audio Features (Fallback)
    // =====================
    
    /**
     * Result of deriving audio features from an artist's tracks.
     */
    sealed class ArtistDerivedFeaturesResult {
        data class Success(
            val audioFeaturesJson: String,
            val tracksUsed: Int
        ) : ArtistDerivedFeaturesResult()
        
        object NotConnected : ArtistDerivedFeaturesResult()
        object ArtistNotFound : ArtistDerivedFeaturesResult()
        object NoTracksWithFeatures : ArtistDerivedFeaturesResult()
        data class Error(val message: String) : ArtistDerivedFeaturesResult()
    }
    
    /**
     * Derive approximate audio features from an artist's top tracks.
     * 
     * This is a last-resort fallback when:
     * - Spotify's audio-features API is unavailable (deprecated for indie devs)
     * - Track not found in ReccoBeats database
     * - No preview URL available for audio analysis
     * 
     * The approach:
     * 1. Fetch artist's top tracks from Spotify API
     * 2. Look up which of those tracks exist in user's listening history (our DB)
     * 3. Get cached audio features for those tracks (from previous enrichment)
     * 4. Average the features to create an approximate "artist profile"
     * 
     * This gives a rough approximation of the artist's typical sound characteristics.
     * Obviously not as accurate as per-track features, but better than nothing.
     * 
     * @param spotifyArtistId The Spotify artist ID
     * @return Derived audio features result
     */
    suspend fun deriveAudioFeaturesFromArtist(spotifyArtistId: String): ArtistDerivedFeaturesResult {
        if (!isAvailable()) {
            return ArtistDerivedFeaturesResult.NotConnected
        }
        
        val accessToken = authManager.getValidAccessToken() 
            ?: return ArtistDerivedFeaturesResult.NotConnected
        val authHeader = "Bearer $accessToken"
        
        try {
            // Step 1: Fetch artist's top tracks from Spotify
            val response = spotifyApi.getArtistTopTracks(authHeader, spotifyArtistId)
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch artist top tracks: ${response.code()}")
                return when (response.code()) {
                    404 -> ArtistDerivedFeaturesResult.ArtistNotFound
                    else -> ArtistDerivedFeaturesResult.Error("API error: ${response.code()}")
                }
            }
            
            val topTracks = response.body()?.tracks ?: return ArtistDerivedFeaturesResult.ArtistNotFound
            if (topTracks.isEmpty()) {
                Log.d(TAG, "Artist $spotifyArtistId has no top tracks")
                return ArtistDerivedFeaturesResult.NoTracksWithFeatures
            }
            
            Log.d(TAG, "Found ${topTracks.size} top tracks for artist $spotifyArtistId")
            
            // Step 2: Get Spotify IDs for the top tracks
            val topTrackIds = topTracks.map { it.id }
            
            // Step 3: Look up which tracks exist in our database with audio features
            val featuresFromDb = mutableListOf<SpotifyAudioFeatures>()
            
            for (trackId in topTrackIds) {
                val metadata = enrichedMetadataDao.findBySpotifyId(trackId)
                if (metadata != null) {
                    val features = getAudioFeatures(metadata)
                    if (features != null) {
                        featuresFromDb.add(features)
                    }
                }
            }
            
            Log.d(TAG, "Found ${featuresFromDb.size}/${topTracks.size} top tracks with cached audio features")
            
            // Step 4: If we don't have enough tracks in our DB, return failure
            // We need at least 3 tracks to make a reasonable average
            if (featuresFromDb.size < 3) {
                Log.d(TAG, "Not enough tracks with audio features (need at least 3, have ${featuresFromDb.size})")
                return ArtistDerivedFeaturesResult.NoTracksWithFeatures
            }
            
            // Step 5: Average the features
            val averagedFeatures = averageAudioFeatures(featuresFromDb)
            val featuresJson = serializeAverageFeatures(averagedFeatures)
            
            Log.i(TAG, "Derived audio features from ${featuresFromDb.size} tracks for artist $spotifyArtistId")
            
            return ArtistDerivedFeaturesResult.Success(
                audioFeaturesJson = featuresJson,
                tracksUsed = featuresFromDb.size
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving artist features", e)
            return ArtistDerivedFeaturesResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Average multiple audio features into a single approximation.
     */
    private fun averageAudioFeatures(features: List<SpotifyAudioFeatures>): AveragedAudioFeatures {
        if (features.isEmpty()) {
            throw IllegalArgumentException("Cannot average empty list of features")
        }
        
        val size = features.size.toFloat()
        
        return AveragedAudioFeatures(
            danceability = features.sumOf { it.danceability.toDouble() }.toFloat() / size,
            energy = features.sumOf { it.energy.toDouble() }.toFloat() / size,
            valence = features.sumOf { it.valence.toDouble() }.toFloat() / size,
            tempo = features.sumOf { it.tempo.toDouble() }.toFloat() / size,
            acousticness = features.sumOf { it.acousticness.toDouble() }.toFloat() / size,
            instrumentalness = features.sumOf { it.instrumentalness.toDouble() }.toFloat() / size,
            loudness = features.sumOf { it.loudness.toDouble() }.toFloat() / size,
            speechiness = features.sumOf { it.speechiness.toDouble() }.toFloat() / size,
            liveness = features.sumOf { it.liveness.toDouble() }.toFloat() / size,
            // For mode, take the majority (0 = minor, 1 = major)
            mode = if (features.count { it.mode == 1 } > features.size / 2) 1 else 0,
            // For key, take the most common key
            key = features.groupingBy { it.key }.eachCount().maxByOrNull { it.value }?.key ?: 0,
            // For time signature, take the most common
            timeSignature = features.groupingBy { it.timeSignature }.eachCount().maxByOrNull { it.value }?.key ?: 4
        )
    }
    
    /**
     * Averaged audio features (derived from multiple tracks).
     */
    private data class AveragedAudioFeatures(
        val danceability: Float,
        val energy: Float,
        val valence: Float,
        val tempo: Float,
        val acousticness: Float,
        val instrumentalness: Float,
        val loudness: Float,
        val speechiness: Float,
        val liveness: Float,
        val mode: Int,
        val key: Int,
        val timeSignature: Int
    )
    
    /**
     * Serialize averaged features to JSON format compatible with our storage.
     */
    private fun serializeAverageFeatures(features: AveragedAudioFeatures): String {
        return """
            {
                "danceability": ${features.danceability},
                "energy": ${features.energy},
                "valence": ${features.valence},
                "tempo": ${features.tempo},
                "acousticness": ${features.acousticness},
                "instrumentalness": ${features.instrumentalness},
                "loudness": ${features.loudness},
                "speechiness": ${features.speechiness},
                "liveness": ${features.liveness},
                "mode": ${features.mode},
                "key": ${features.key},
                "time_signature": ${features.timeSignature},
                "derived_from_artist": true
            }
        """.trimIndent()
    }
}
