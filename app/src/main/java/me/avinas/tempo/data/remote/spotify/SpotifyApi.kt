package me.avinas.tempo.data.remote.spotify

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Spotify Web API Retrofit interface.
 * 
 * API Documentation: https://developer.spotify.com/documentation/web-api
 * 
 * Authentication: OAuth 2.0 with Authorization Code + PKCE flow.
 * All endpoints require a valid access token in the Authorization header.
 * 
 * Base URL: https://api.spotify.com/v1/
 * 
 * Rate Limiting: Spotify uses dynamic rate limiting.
 * Standard apps typically have a rate limit that allows for reasonable use.
 * If rate limited, the API returns 429 with Retry-After header.
 */
interface SpotifyApi {

    companion object {
        const val BASE_URL = "https://api.spotify.com/v1/"
        const val AUTH_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        
        // Scopes required for audio features
        // Note: user-read-private is required for market-specific searches
        const val SCOPES = "user-read-private"
        
        // Redirect URI for OAuth callback
        const val REDIRECT_URI = "tempo://spotify-callback"
    }

    // =====================
    // Track Search
    // =====================

    /**
     * Search for tracks on Spotify.
     * 
     * @param authorization Bearer token (format: "Bearer {access_token}")
     * @param query Search query (supports field filters like artist:, track:, album:)
     * @param type Type of item to search for (always "track" for this use case)
     * @param limit Max results (1-50, default 20)
     * @param offset Pagination offset
     * @param market ISO 3166-1 alpha-2 country code for market-specific results
     * @return Search results with matching tracks
     */
    @GET("search")
    suspend fun searchTracks(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 5,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifySearchResponse>

    // =====================
    // Artist Search
    // =====================

    /**
     * Search for artists on Spotify.
     * This is more reliable than track search for getting artist images
     * because it directly searches for artists rather than inferring from tracks.
     * 
     * @param authorization Bearer token (format: "Bearer {access_token}")
     * @param query Search query (artist name)
     * @param type Type of item to search for (always "artist" for this use case)
     * @param limit Max results (1-50, default 5)
     * @param offset Pagination offset
     * @param market ISO 3166-1 alpha-2 country code for market-specific results
     * @return Search results with matching artists (includes images!)
     */
    @GET("search")
    suspend fun searchArtists(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 5,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifyArtistSearchResponse>

    // =====================
    // Audio Features (DEPRECATED BY SPOTIFY)
    // =====================
    
    /**
     * Get audio features for a single track.
     * 
     * ⚠️ IMPORTANT: Spotify deprecated the audio-features endpoint for third-party apps
     * in late 2024. New apps and existing apps without extended quota will receive
     * 403 Forbidden responses. The app gracefully handles this and caches the
     * unavailability to avoid redundant API calls.
     * 
     * Audio features include:
     * - danceability: 0.0-1.0, how suitable for dancing
     * - energy: 0.0-1.0, perceptual measure of intensity
     * - valence: 0.0-1.0, musical positiveness (happy/cheerful vs sad/angry)
     * - tempo: BPM of the track
     * - acousticness: 0.0-1.0, confidence the track is acoustic
     * - instrumentalness: 0.0-1.0, predicts if track has no vocals
     * - loudness: Overall loudness in dB
     * - speechiness: 0.0-1.0, presence of spoken words
     * - liveness: 0.0-1.0, presence of audience in recording
     * - key: Pitch class notation (0-11)
     * - mode: Modality (0 = minor, 1 = major)
     * - time_signature: Estimated time signature
     * 
     * @param authorization Bearer token
     * @param trackId Spotify track ID
     * @return Audio features for the track, or 403 if API access is restricted
     */
    @GET("audio-features/{id}")
    suspend fun getAudioFeatures(
        @Header("Authorization") authorization: String,
        @Path("id") trackId: String
    ): Response<SpotifyAudioFeatures>

    /**
     * Get audio features for multiple tracks.
     * 
     * ⚠️ IMPORTANT: This endpoint is also deprecated for third-party apps.
     * See getAudioFeatures() for details.
     * 
     * @param authorization Bearer token
     * @param ids Comma-separated list of track IDs (max 100)
     * @return Audio features for all tracks, or 403 if API access is restricted
     */
    @GET("audio-features")
    suspend fun getMultipleAudioFeatures(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String
    ): Response<SpotifyAudioFeaturesResponse>

    // =====================
    // Track Details
    // =====================

    /**
     * Get detailed information for a track.
     * 
     * @param authorization Bearer token
     * @param trackId Spotify track ID
     * @param market ISO 3166-1 alpha-2 country code
     * @return Detailed track information
     */
    @GET("tracks/{id}")
    suspend fun getTrack(
        @Header("Authorization") authorization: String,
        @Path("id") trackId: String,
        @Query("market") market: String? = null
    ): Response<SpotifyTrack>

    /**
     * Get detailed information for multiple tracks in one request.
     * Much more efficient than calling getTrack() multiple times.
     * 
     * @param authorization Bearer token
     * @param ids Comma-separated list of track IDs (max 50)
     * @param market ISO 3166-1 alpha-2 country code
     * @return Multiple track details
     */
    @GET("tracks")
    suspend fun getMultipleTracks(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String,
        @Query("market") market: String? = null
    ): Response<SpotifyMultipleTracksResponse>

    // =====================
    // User Profile (for validating connection)
    // =====================

    /**
     * Get current user's profile.
     * Used to verify authentication is working.
     * 
     * @param authorization Bearer token
     * @return User profile information
     */
    @GET("me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): Response<SpotifyUser>

    // =====================
    // Artist Details
    // =====================

    /**
     * Get full details for an artist including images.
     * 
     * @param authorization Bearer token
     * @param artistId Spotify artist ID
     * @return Full artist details including images
     */
    @GET("artists/{id}")
    suspend fun getArtist(
        @Header("Authorization") authorization: String,
        @Path("id") artistId: String
    ): Response<SpotifyFullArtist>
    
    /**
     * Get multiple artists in one request.
     * Much more efficient than calling getArtist() multiple times.
     * 
     * @param authorization Bearer token
     * @param ids Comma-separated list of artist IDs (max 50)
     * @return Multiple artist details including genres
     */
    @GET("artists")
    suspend fun getMultipleArtists(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String
    ): Response<SpotifyMultipleArtistsResponse>
    
    /**
     * Get an artist's top tracks.
     * Returns up to 10 top tracks for the artist.
     * 
     * This is useful for deriving approximate audio features when:
     * - Spotify's audio-features API is unavailable (deprecated)
     * - We can analyze the user's listening history for these tracks
     * 
     * @param authorization Bearer token
     * @param artistId Spotify artist ID
     * @param market ISO 3166-1 alpha-2 country code (required by API)
     * @return Artist's top tracks in the specified market
     */
    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") authorization: String,
        @Path("id") artistId: String,
        @Query("market") market: String = "US"
    ): Response<SpotifyArtistTopTracksResponse>
}
