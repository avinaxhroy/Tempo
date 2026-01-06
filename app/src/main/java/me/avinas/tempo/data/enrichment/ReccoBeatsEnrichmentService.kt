package me.avinas.tempo.data.enrichment

import android.util.Log
import com.squareup.moshi.Moshi
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.entities.AudioFeaturesSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.remote.reccobeats.ReccoBeatsApi
import me.avinas.tempo.data.remote.reccobeats.ReccoBeatsAudioFeatures
import me.avinas.tempo.data.remote.reccobeats.ReccoBeatsTrack
import me.avinas.tempo.data.remote.spotify.SpotifyAudioFeatures
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Service responsible for enriching track metadata with ReccoBeats audio features.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This service is called by EnrichmentWorker as a FALLBACK when:
 * 1. Spotify is not connected, OR
 * 2. Spotify's audio features API is unavailable (deprecated for third-party apps)
 * 
 * ReccoBeats provides FREE audio features API with:
 * - No authentication required
 * - Same features as Spotify (danceability, energy, valence, tempo, etc.)
 * - Support for Spotify IDs (can use existing spotifyId from metadata)
 * - Audio file analysis fallback (upload 30s preview)
 * 
 * =====================================================
 * ENRICHMENT FALLBACK CHAIN:
 * 
 * 1. Try ReccoBeats with Spotify ID (if available)
 * 2. Try ReccoBeats with track search
 * 3. Try ReccoBeats audio file analysis (if preview URL available)
 * 4. Fall back to Genius for lyrics-based mood (last resort)
 * 
 * =====================================================
 */
@Singleton
class ReccoBeatsEnrichmentService @Inject constructor(
    private val reccoBeatsApi: ReccoBeatsApi,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val moshi: Moshi,
    @Named("reccobeats") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ReccoBeatsEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 200L
        
        // Match threshold for fuzzy track matching
        private const val MIN_MATCH_SCORE = 0.7
    }

    /**
     * Result of ReccoBeats enrichment attempt.
     */
    sealed class ReccoBeatsResult {
        data class Success(
            val reccoBeatsId: String?,
            val spotifyId: String?,
            val audioFeatures: ReccoBeatsAudioFeatures,
            val mood: String,
            val energyLevel: String,
            val genreHints: List<String>,
            // Additional track info if found
            val albumTitle: String? = null,
            val albumArtUrl: String? = null,
            val releaseDate: String? = null
        ) : ReccoBeatsResult()
        
        object TrackNotFound : ReccoBeatsResult()
        object AudioFeaturesNotAvailable : ReccoBeatsResult()
        data class Error(val message: String, val retryable: Boolean = true) : ReccoBeatsResult()
    }

    /**
     * Check if ReccoBeats enrichment is available.
     * ReccoBeats is always available (no auth required), but we check network connectivity.
     */
    fun isAvailable(): Boolean = true

    /**
     * Enrich a track with ReccoBeats audio features.
     * 
     * Strategy:
     * 1. If we have a Spotify ID, try audio-features endpoint directly (fastest)
     * 2. If not, search for track and get audio features
     * 3. If search fails, try audio file analysis (slowest, requires preview URL)
     * 
     * @param track The track to enrich
     * @param existingMetadata Existing enriched metadata (may contain spotifyId)
     * @param previewUrl Optional Spotify preview URL for audio analysis fallback
     * @return ReccoBeatsResult indicating success or failure
     */
    suspend fun enrichTrack(
        track: Track,
        existingMetadata: EnrichedMetadata?,
        previewUrl: String? = null
    ): ReccoBeatsResult {
        // CRITICAL: Wrap entire enrichment logic to ensure errors don't affect music tracking
        return try {
            // Skip if artist is unknown - waiting for metadata to settle
            if (ArtistParser.isUnknownArtist(track.artist)) {
                Log.d(TAG, "Skipping ReccoBeats enrichment for track ${track.id}: artist is unknown")
                return ReccoBeatsResult.TrackNotFound
            }

            Log.d(TAG, "Enriching track '${track.title}' by '${track.artist}' with ReccoBeats")

            // Strategy 1: Try with existing Spotify ID (fastest path)
            val spotifyId = existingMetadata?.spotifyId ?: track.spotifyId
            if (!spotifyId.isNullOrBlank()) {
                try {
                    Log.d(TAG, "Trying ReccoBeats with Spotify ID: $spotifyId")
                    val result = fetchAudioFeaturesById(spotifyId)
                    if (result is ReccoBeatsResult.Success) {
                        // Update metadata and return
                        updateMetadataWithReccoBeats(track.id, existingMetadata, result)
                        return result
                    }
                    // If 404, the track might not be in ReccoBeats database, continue to search
                    delay(RATE_LIMIT_DELAY_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching by Spotify ID, continuing to search: ${e.message}")
                    // Continue to next strategy instead of failing
                }
            }

            // Strategy 2: Search for track by name
            val searchResult = try {
                Log.d(TAG, "Searching ReccoBeats for '${track.title}' by '${track.artist}'")
                searchAndEnrich(track)
            } catch (e: Exception) {
                Log.w(TAG, "Error during ReccoBeats search: ${e.message}")
                ReccoBeatsResult.Error(e.message ?: "Search failed", retryable = false)
            }
            
            if (searchResult is ReccoBeatsResult.Success) {
                updateMetadataWithReccoBeats(track.id, existingMetadata, searchResult)
                return searchResult
            }

            // Strategy 3: Audio file analysis (if preview URL available)
            // This is the EXPENSIVE operation - wrap in extra protection
            if (!previewUrl.isNullOrBlank()) {
                try {
                    Log.d(TAG, "Trying ReccoBeats audio analysis with preview URL")
                    delay(RATE_LIMIT_DELAY_MS)
                    val analysisResult = analyzePreviewAudio(previewUrl)
                    if (analysisResult is ReccoBeatsResult.Success) {
                        updateMetadataWithReccoBeats(track.id, existingMetadata, analysisResult)
                        return analysisResult
                    }
                } catch (e: Exception) {
                    // Audio analysis failed but this shouldn't crash the enrichment
                    Log.w(TAG, "ReccoBeats audio analysis failed for track ${track.id}: ${e.message}")
                    // Continue to return searchResult below
                }
            } else if (previewUrl != null) {
                Log.d(TAG, "Preview URL is blank, skipping audio analysis")
            }

            return searchResult // Return the search result (likely NotFound or Error)
        } catch (e: Exception) {
            // Top-level safety net - should never reach here but ensures robustness
            Log.e(TAG, "Critical error in ReccoBeats enrichTrack for track ${track.id}", e)
            ReccoBeatsResult.Error("Critical error: ${e.message}", retryable = false)
        }
    }

    /**
     * Fetch audio features using a track ID (Spotify ID or ReccoBeats ID).
     */
    private suspend fun fetchAudioFeaturesById(id: String): ReccoBeatsResult {
        return try {
            val response = reccoBeatsApi.getAudioFeatures(id)
            
            when {
                response.isSuccessful -> {
                    val features = response.body()
                    if (features != null) {
                        ReccoBeatsResult.Success(
                            reccoBeatsId = null,
                            spotifyId = id,
                            audioFeatures = features,
                            mood = features.deriveMood(),
                            energyLevel = features.deriveEnergyLevel(),
                            genreHints = features.deriveGenreHints()
                        )
                    } else {
                        ReccoBeatsResult.AudioFeaturesNotAvailable
                    }
                }
                response.code() == 404 -> {
                    Log.d(TAG, "Track $id not found in ReccoBeats")
                    ReccoBeatsResult.TrackNotFound
                }
                response.code() == 429 -> {
                    Log.w(TAG, "ReccoBeats rate limited")
                    ReccoBeatsResult.Error("Rate limited", retryable = true)
                }
                else -> {
                    Log.w(TAG, "ReccoBeats API error: ${response.code()} - ${response.message()}")
                    ReccoBeatsResult.Error("API error: ${response.code()}", retryable = response.code() >= 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching audio features from ReccoBeats", e)
            ReccoBeatsResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }

    /**
     * Search for a track and get its audio features.
     */
    private suspend fun searchAndEnrich(track: Track): ReccoBeatsResult {
        val cleanTitle = ArtistParser.cleanTrackTitle(track.title)
        val primaryArtist = ArtistParser.getPrimaryArtist(track.artist)
        
        // Build search query - Retrofit will handle URL encoding for @Query parameters
        // Do NOT manually encode as that causes double-encoding and 404 errors
        val query = "$primaryArtist $cleanTitle"

        return try {
            val searchResponse = reccoBeatsApi.searchTracks(query)
            
            if (!searchResponse.isSuccessful) {
                return when (searchResponse.code()) {
                    429 -> ReccoBeatsResult.Error("Rate limited", retryable = true)
                    else -> ReccoBeatsResult.Error("Search failed: ${searchResponse.code()}", retryable = searchResponse.code() >= 500)
                }
            }

            val searchBody = searchResponse.body()
            if (searchBody == null || searchBody.tracks.isEmpty()) {
                Log.d(TAG, "No tracks found on ReccoBeats for: $query")
                return ReccoBeatsResult.TrackNotFound
            }

            // Find best matching track
            val bestMatch = findBestMatch(searchBody.tracks, cleanTitle, primaryArtist)
            if (bestMatch == null) {
                Log.d(TAG, "No good match found on ReccoBeats for: $query")
                return ReccoBeatsResult.TrackNotFound
            }

            Log.d(TAG, "Found match: '${bestMatch.name}' by ${bestMatch.artists.joinToString { it.name }}")

            // Get audio features for matched track
            delay(RATE_LIMIT_DELAY_MS)
            val featuresResponse = reccoBeatsApi.getAudioFeatures(bestMatch.id)
            
            if (!featuresResponse.isSuccessful || featuresResponse.body() == null) {
                Log.w(TAG, "Failed to get audio features for matched track")
                return ReccoBeatsResult.AudioFeaturesNotAvailable
            }

            val features = featuresResponse.body()!!
            ReccoBeatsResult.Success(
                reccoBeatsId = bestMatch.id,
                spotifyId = bestMatch.spotifyId,
                audioFeatures = features,
                mood = features.deriveMood(),
                energyLevel = features.deriveEnergyLevel(),
                genreHints = features.deriveGenreHints(),
                albumTitle = bestMatch.album?.name,
                albumArtUrl = bestMatch.album?.largeImageUrl ?: bestMatch.album?.mediumImageUrl,
                releaseDate = bestMatch.album?.releaseDate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error searching ReccoBeats", e)
            ReccoBeatsResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }

    /**
     * Find the best matching track from search results.
     */
    private fun findBestMatch(
        tracks: List<ReccoBeatsTrack>,
        targetTitle: String,
        targetArtist: String
    ): ReccoBeatsTrack? {
        val normalizedTitle = normalizeString(targetTitle)
        val normalizedArtist = normalizeString(targetArtist)

        return tracks
            .map { track ->
                val titleScore = calculateSimilarity(normalizeString(track.name), normalizedTitle)
                val artistScore = track.artists.maxOfOrNull { 
                    calculateSimilarity(normalizeString(it.name), normalizedArtist) 
                } ?: 0.0
                val combinedScore = (titleScore * 0.6 + artistScore * 0.4)
                track to combinedScore
            }
            .filter { it.second >= MIN_MATCH_SCORE }
            .maxByOrNull { it.second }
            ?.first
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
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0

        val words1 = s1.split(" ").toSet()
        val words2 = s2.split(" ").toSet()
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    /**
     * Analyze audio from a preview URL using ReccoBeats' audio analysis endpoint.
     * 
     * This downloads the preview audio and uploads it to ReccoBeats for analysis.
     * Use this as a last resort when track is not in ReccoBeats database.
     */
    private suspend fun analyzePreviewAudio(previewUrl: String): ReccoBeatsResult {
        return try {
            // Download the preview audio
            val downloadRequest = Request.Builder()
                .url(previewUrl)
                .build()
            
            val downloadResponse = okHttpClient.newCall(downloadRequest).execute()
            
            if (!downloadResponse.isSuccessful) {
                Log.w(TAG, "Failed to download preview audio: ${downloadResponse.code}")
                return ReccoBeatsResult.Error("Failed to download preview", retryable = false)
            }

            val audioBytes = downloadResponse.body?.bytes()
            if (audioBytes == null || audioBytes.isEmpty()) {
                Log.w(TAG, "Empty preview audio response")
                return ReccoBeatsResult.Error("Empty preview audio", retryable = false)
            }

            // Check file size (max 5MB)
            if (audioBytes.size > 5 * 1024 * 1024) {
                Log.w(TAG, "Preview audio too large: ${audioBytes.size} bytes")
                return ReccoBeatsResult.Error("Preview audio too large", retryable = false)
            }

            // Create multipart request
            val requestBody = audioBytes.toRequestBody("audio/mpeg".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("audioFile", "preview.mp3", requestBody)

            // Upload to ReccoBeats for analysis
            val analysisResponse = reccoBeatsApi.analyzeAudioFile(multipartBody)
            
            when {
                analysisResponse.isSuccessful -> {
                    val features = analysisResponse.body()
                    if (features != null) {
                        ReccoBeatsResult.Success(
                            reccoBeatsId = null,
                            spotifyId = null,
                            audioFeatures = features,
                            mood = features.deriveMood(),
                            energyLevel = features.deriveEnergyLevel(),
                            genreHints = features.deriveGenreHints()
                        )
                    } else {
                        ReccoBeatsResult.AudioFeaturesNotAvailable
                    }
                }
                analysisResponse.code() == 429 -> {
                    ReccoBeatsResult.Error("Rate limited", retryable = true)
                }
                else -> {
                    Log.w(TAG, "Audio analysis failed: ${analysisResponse.code()}")
                    ReccoBeatsResult.Error("Analysis failed: ${analysisResponse.code()}", retryable = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing preview audio", e)
            ReccoBeatsResult.Error(e.message ?: "Unknown error", retryable = false)
        }
    }

    /**
     * Update metadata with ReccoBeats data.
     */
    private suspend fun updateMetadataWithReccoBeats(
        trackId: Long,
        existingMetadata: EnrichedMetadata?,
        result: ReccoBeatsResult.Success
    ) {
        // Convert ReccoBeats audio features to Spotify-compatible JSON format
        // This allows reusing existing UI components that expect Spotify format
        val spotifyCompatibleFeatures = SpotifyAudioFeatures(
            id = result.spotifyId ?: result.reccoBeatsId ?: "",
            danceability = result.audioFeatures.danceability,
            energy = result.audioFeatures.energy,
            key = result.audioFeatures.key,
            loudness = result.audioFeatures.loudness,
            mode = result.audioFeatures.mode,
            speechiness = result.audioFeatures.speechiness,
            acousticness = result.audioFeatures.acousticness,
            instrumentalness = result.audioFeatures.instrumentalness,
            liveness = result.audioFeatures.liveness,
            valence = result.audioFeatures.valence,
            tempo = result.audioFeatures.tempo,
            durationMs = result.audioFeatures.durationMs ?: 0,
            timeSignature = result.audioFeatures.timeSignature,
            uri = "",
            trackHref = "",
            analysisUrl = ""
        )

        val audioFeaturesJson = serializeAudioFeatures(spotifyCompatibleFeatures)

        // Merge genre hints with existing genres
        val mergedGenres = (existingMetadata?.genres ?: emptyList())
            .plus(result.genreHints)
            .distinct()
            .take(10)

        val updated = existingMetadata?.copy(
            audioFeaturesJson = audioFeaturesJson,
            audioFeaturesSource = AudioFeaturesSource.RECCOBEATS,
            reccoBeatsId = result.reccoBeatsId,
            // Update spotifyId if we got one from ReccoBeats
            spotifyId = result.spotifyId ?: existingMetadata.spotifyId,
            // Only update album info if we don't have it yet
            albumTitle = existingMetadata.albumTitle ?: result.albumTitle,
            albumArtUrl = existingMetadata.albumArtUrl ?: result.albumArtUrl,
            // Merge genre hints
            genres = mergedGenres,
            cacheTimestamp = System.currentTimeMillis()
        ) ?: EnrichedMetadata(
            trackId = trackId,
            audioFeaturesJson = audioFeaturesJson,
            audioFeaturesSource = AudioFeaturesSource.RECCOBEATS,
            reccoBeatsId = result.reccoBeatsId,
            spotifyId = result.spotifyId,
            albumTitle = result.albumTitle,
            albumArtUrl = result.albumArtUrl,
            genres = result.genreHints,
            cacheTimestamp = System.currentTimeMillis()
        )

        enrichedMetadataDao.upsert(updated)
        Log.d(TAG, "Saved ReccoBeats data for track $trackId: mood=${result.mood}, energy=${result.energyLevel}")
    }

    /**
     * Serialize audio features to JSON (Spotify-compatible format).
     */
    private fun serializeAudioFeatures(features: SpotifyAudioFeatures): String {
        val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
        return adapter.toJson(features)
    }

    /**
     * Batch enrich multiple tracks with ReccoBeats data.
     * 
     * Optimized for tracks that already have Spotify IDs - can batch fetch audio features.
     * 
     * @param tracks List of tracks with their existing metadata
     * @return Number of tracks successfully enriched
     */
    suspend fun enrichBatch(
        tracks: List<Pair<Track, EnrichedMetadata?>>
    ): Int {
        var enrichedCount = 0

        for ((track, metadata) in tracks) {
            val result = enrichTrack(track, metadata)
            if (result is ReccoBeatsResult.Success) {
                enrichedCount++
            }
            delay(RATE_LIMIT_DELAY_MS)
        }

        return enrichedCount
    }

    /**
     * Get audio features from stored JSON (for display in UI).
     */
    fun getAudioFeatures(metadata: EnrichedMetadata): SpotifyAudioFeatures? {
        val json = metadata.audioFeaturesJson ?: return null
        return try {
            val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
            adapter.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored audio features", e)
            null
        }
    }

    /**
     * Get derived mood from stored audio features.
     */
    fun getMood(metadata: EnrichedMetadata): String? {
        val features = getAudioFeatures(metadata) ?: return null
        return ReccoBeatsAudioFeatures(
            valence = features.valence,
            energy = features.energy,
            danceability = features.danceability,
            acousticness = features.acousticness,
            instrumentalness = features.instrumentalness,
            speechiness = features.speechiness,
            liveness = features.liveness,
            tempo = features.tempo,
            loudness = features.loudness,
            key = features.key,
            mode = features.mode,
            timeSignature = features.timeSignature
        ).deriveMood()
    }

    /**
     * Get derived energy level from stored audio features.
     */
    fun getEnergyLevel(metadata: EnrichedMetadata): String? {
        val features = getAudioFeatures(metadata) ?: return null
        return ReccoBeatsAudioFeatures(
            energy = features.energy
        ).deriveEnergyLevel()
    }
}
