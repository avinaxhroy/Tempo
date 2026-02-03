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
        
        // Scopes required for Tempo features:
        // - user-read-private: Market-specific searches and user product info
        // - user-read-recently-played: Import recent listening history for onboarding
        // - user-top-read: Access user's top tracks/artists (4 weeks, 6 months, all time)
        // - user-library-read: Access user's saved/liked tracks with exact timestamps
        // - playlist-read-private: Access user's playlists (including "Your Top Songs" yearly wraps)
        const val SCOPES = "user-read-private user-read-recently-played user-top-read user-library-read playlist-read-private"
        
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
    
    // Audio features endpoints removed as they are deprecated for third-party apps since Nov 2024.

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

    // =====================
    // Recently Played (Import Feature)
    // =====================

    /**
     * Get the current user's recently played tracks.
     * Returns up to 50 tracks from approximately the last 24 hours.
     * 
     * Used for onboarding import when user's Tempo data is empty,
     * allowing them to bootstrap their library with recent Spotify activity.
     * 
     * Pagination:
     * - Use 'after' cursor to get tracks played AFTER that timestamp (for incremental polling)
     * - Use 'before' cursor to get tracks played BEFORE that timestamp (for going back in time)
     * 
     * @param authorization Bearer token (format: "Bearer {access_token}")
     * @param limit Max tracks to return (1-50, default 50)
     * @param after Unix timestamp in milliseconds. Returns items after this position.
     * @param before Unix timestamp in milliseconds. Returns items before this position.
     *               Note: Only 'after' OR 'before' should be specified, not both.
     * @return Recently played tracks with timestamps and context
     */
    @GET("me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("after") after: Long? = null,
        @Query("before") before: Long? = null
    ): Response<SpotifyRecentlyPlayedResponse>

    // =====================
    // User's Top Items (Stats.fm style!)
    // =====================

    /**
     * Get the current user's top tracks based on affinity calculated by Spotify.
     * 
     * This is the key endpoint for getting user's listening stats over time!
     * Unlike recently-played (last 50), this provides TOP tracks based on Spotify's
     * internal affinity calculation across different time ranges.
     * 
     * Time ranges:
     * - short_term: ~4 weeks
     * - medium_term: ~6 months  
     * - long_term: Several years ("all time")
     * 
     * @param authorization Bearer token
     * @param timeRange Time range: "short_term", "medium_term", or "long_term"
     * @param limit Max tracks (1-50, default 20)
     * @param offset Pagination offset (0-based)
     * @return User's top tracks for the specified time range
     */
    @GET("me/top/tracks")
    suspend fun getUserTopTracks(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserTopTracksResponse>

    /**
     * Get the current user's top artists based on affinity calculated by Spotify.
     * 
     * Time ranges:
     * - short_term: ~4 weeks
     * - medium_term: ~6 months
     * - long_term: Several years ("all time")
     * 
     * @param authorization Bearer token
     * @param timeRange Time range: "short_term", "medium_term", or "long_term"
     * @param limit Max artists (1-50, default 20)
     * @param offset Pagination offset (0-based)
     * @return User's top artists for the specified time range
     */
    @GET("me/top/artists")
    suspend fun getUserTopArtists(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserTopArtistsResponse>

    // =====================
    // User's Saved Tracks (Time Machine - Exact Timestamps!)
    // =====================

    /**
     * Get the current user's saved (liked) tracks.
     * 
     * CRITICAL FOR HISTORY RECONSTRUCTION:
     * Unlike top tracks, this endpoint provides EXACT timestamps of when
     * each track was added/liked. This is the "Time Machine" data source.
     * 
     * If a user liked a song on June 12, 2023, we KNOW they were listening
     * to it around that time. We can generate accurate listening events
     * clustered around these dates.
     * 
     * @param authorization Bearer token
     * @param limit Max tracks (1-50, default 50)
     * @param offset Pagination offset (can fetch up to ~1000 tracks)
     * @param market ISO 3166-1 alpha-2 country code
     * @return User's saved tracks with added_at timestamps
     */
    @GET("me/tracks")
    suspend fun getUserSavedTracks(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifySavedTracksResponse>

    // =====================
    // User's Playlists (Artifact Hunter)
    // =====================

    /**
     * Get the current user's playlists.
     * 
     * CRITICAL FOR HISTORY RECONSTRUCTION:
     * We search for playlists with specific naming patterns:
     * - "Your Top Songs 2024", "Your Top Songs 2023", etc.
     * - "On Repeat", "Repeat Rewind"
     * - "Daylist", seasonal playlists
     * 
     * These are "historical artifacts" that reveal past listening patterns.
     * 
     * @param authorization Bearer token
     * @param limit Max playlists (1-50, default 50)
     * @param offset Pagination offset
     * @return User's playlists
     */
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserPlaylistsResponse>

    /**
     * Get tracks from a specific playlist.
     * Used to extract tracks from "Your Top Songs 20XX" playlists.
     * 
     * @param authorization Bearer token
     * @param playlistId Spotify playlist ID
     * @param limit Max tracks (1-100, default 100)
     * @param offset Pagination offset
     * @param market ISO 3166-1 alpha-2 country code
     * @return Playlist tracks with added_at timestamps
     */
    @GET("playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") authorization: String,
        @Path("playlist_id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null,
        @Query("fields") fields: String = "items(added_at,track(id,name,artists,album,duration_ms,popularity,explicit,external_ids,preview_url)),total,next"
    ): Response<SpotifyPlaylistTracksResponse>
}
