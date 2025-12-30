package me.avinas.tempo.data.remote.reccobeats

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * ReccoBeats API Retrofit interface.
 * 
 * ReccoBeats is a FREE API that provides audio features similar to Spotify's deprecated endpoint.
 * No authentication required.
 * 
 * API Documentation: https://reccobeats.com/docs/documentation/introduction
 * Base URL: https://api.reccobeats.com
 * 
 * Key features:
 * - Search tracks by artist/song name
 * - Get audio features (danceability, energy, valence, tempo, etc.)
 * - Supports both ReccoBeats UUIDs and Spotify IDs for audio features
 * - Can analyze uploaded audio files (max 30s, 5MB)
 * 
 * Rate Limiting: HTTP 429 if exceeded. Handle with retry + backoff.
 * 
 * ID Formats:
 * - ReccoBeats ID: UUID format (e.g., "878dadea-33c5-4c08-bdb9-e2b117475a99")
 * - Spotify ID: Base-62 format (e.g., "06HL4z0CvFAxyc27GXpf02")
 * 
 * The /audio-features endpoint accepts BOTH formats!
 */
interface ReccoBeatsApi {

    companion object {
        const val BASE_URL = "https://api.reccobeats.com/"
    }

    // =====================
    // Track Search
    // =====================

    /**
     * Search for tracks by artist/song name.
     * 
     * @param query Search query string (e.g., "Seedhe Maut Namastute")
     * @return Search results with matching tracks
     * 
     * Example: GET /v1/track/search?q=Seedhe+Maut+Namastute
     */
    @GET("v1/track/search")
    suspend fun searchTracks(
        @Query("q") query: String
    ): Response<ReccoBeatsSearchResponse>

    // =====================
    // Track Details
    // =====================

    /**
     * Get complete track information by ReccoBeats ID.
     * 
     * ⚠️ NOTE: This endpoint only accepts ReccoBeats UUID, NOT Spotify ID.
     * Use searchTracks() to find the ReccoBeats ID first.
     * 
     * @param id ReccoBeats UUID (e.g., "878dadea-33c5-4c08-bdb9-e2b117475a99")
     * @return Track details
     * 
     * Example: GET /v1/track/878dadea-33c5-4c08-bdb9-e2b117475a99
     */
    @GET("v1/track/{id}")
    suspend fun getTrack(
        @Path("id") id: String
    ): Response<ReccoBeatsTrack>

    // =====================
    // Audio Features (MOST IMPORTANT!)
    // =====================

    /**
     * Get audio features for a track.
     * 
     * ⭐ This is the main endpoint we need for mood/energy analysis.
     * ✅ Accepts BOTH ReccoBeats UUID AND Spotify ID!
     * 
     * If you already have a Spotify ID from the track, you can use it directly
     * without searching ReccoBeats first.
     * 
     * @param id ReccoBeats UUID or Spotify ID
     * @return Audio features (danceability, energy, valence, tempo, etc.)
     * 
     * Example with Spotify ID: GET /v1/track/06HL4z0CvFAxyc27GXpf02/audio-features
     * Example with ReccoBeats ID: GET /v1/track/878dadea-33c5-4c08-bdb9-e2b117475a99/audio-features
     */
    @GET("v1/track/{id}/audio-features")
    suspend fun getAudioFeatures(
        @Path("id") id: String
    ): Response<ReccoBeatsAudioFeatures>

    // =====================
    // Multiple Tracks
    // =====================

    /**
     * Get multiple tracks at once.
     * 
     * ✅ Accepts both ReccoBeats UUIDs and Spotify IDs.
     * 
     * @param ids List of track IDs (ReccoBeats UUID or Spotify ID)
     * @return Multiple tracks
     * 
     * Example: GET /v1/track?ids=06HL4z0CvFAxyc27GXpf02&ids=3n3Ppam7vgaVa1iaRUc9Lp
     */
    @GET("v1/track")
    suspend fun getMultipleTracks(
        @Query("ids") ids: List<String>
    ): Response<ReccoBeatsMultipleTracksResponse>

    // =====================
    // Recommendations
    // =====================

    /**
     * Get track recommendations based on seed tracks.
     * 
     * @param seeds Comma-separated track IDs (ReccoBeats UUID or Spotify ID)
     * @param size Number of recommendations (default: 10)
     * @return List of recommended tracks
     * 
     * Example: GET /v1/track/recommendation?seeds=06HL4z0CvFAxyc27GXpf02&size=20
     */
    @GET("v1/track/recommendation")
    suspend fun getRecommendations(
        @Query("seeds") seeds: String,
        @Query("size") size: Int = 10
    ): Response<ReccoBeatsRecommendationResponse>

    // =====================
    // Artist Search
    // =====================

    /**
     * Search for artists by name.
     * 
     * @param query Artist name
     * @return Matching artists with genres and images
     * 
     * Example: GET /v1/artist/search?q=Emiway+Bantai
     */
    @GET("v1/artist/search")
    suspend fun searchArtists(
        @Query("q") query: String
    ): Response<ReccoBeatsArtistSearchResponse>

    // =====================
    // Album Search
    // =====================

    /**
     * Search for albums by name.
     * 
     * @param query Album name
     * @return Matching albums
     * 
     * Example: GET /v1/album/search?q=King
     */
    @GET("v1/album/search")
    suspend fun searchAlbums(
        @Query("q") query: String
    ): Response<ReccoBeatsAlbumSearchResponse>

    // =====================
    // Audio Analysis (File Upload)
    // =====================

    /**
     * Extract audio features from an uploaded audio file.
     * 
     * Use this as a last resort when:
     * 1. Track not found in ReccoBeats database
     * 2. Spotify ID not available
     * 3. Have access to Spotify's 30s preview URL
     * 
     * ⚠️ Limitations:
     * - Max file size: 5MB
     * - Max duration: 30 seconds
     * - Supported formats: MP3, WAV, etc.
     * 
     * @param audioFile The audio file to analyze
     * @return Extracted audio features
     * 
     * Example:
     * curl -X POST https://api.reccobeats.com/v1/analysis/audio-features \
     *   -F "audioFile=@preview.mp3"
     */
    @Multipart
    @POST("v1/analysis/audio-features")
    suspend fun analyzeAudioFile(
        @Part audioFile: MultipartBody.Part
    ): Response<ReccoBeatsAnalysisResponse>
}
