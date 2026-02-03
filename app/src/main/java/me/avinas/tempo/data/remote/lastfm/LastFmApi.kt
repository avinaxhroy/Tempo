package me.avinas.tempo.data.remote.lastfm

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Last.fm API Retrofit interface.
 * 
 * API Documentation: https://www.last.fm/api
 * 
 * Last.fm provides excellent tag/genre data for tracks and artists.
 * The API is free for non-commercial use with reasonable rate limits.
 * 
 * Base URL: https://ws.audioscrobbler.com/2.0/
 * 
 * All requests require an API key passed as `api_key` parameter.
 */
interface LastFmApi {

    companion object {
        const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
        
        // Rate limit: ~5 requests per second for reasonable use
        const val RATE_LIMIT_MS = 200L
    }

    /**
     * Get track info including tags/genres.
     * 
     * @param method API method (track.getInfo)
     * @param track Track name
     * @param artist Artist name
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param autocorrect Whether to autocorrect misspellings (1 = yes)
     * @return Track info with tags
     */
    @GET(".")
    suspend fun getTrackInfo(
        @Query("method") method: String = "track.getInfo",
        @Query("track") track: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmTrackResponse>

    /**
     * Get artist info including tags/genres.
     * 
     * @param method API method (artist.getInfo)
     * @param artist Artist name
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param autocorrect Whether to autocorrect misspellings (1 = yes)
     * @return Artist info with tags and similar artists
     */
    @GET(".")
    suspend fun getArtistInfo(
        @Query("method") method: String = "artist.getInfo",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmArtistResponse>

    /**
     * Get top tags for a track.
     * 
     * @param method API method (track.getTopTags)
     * @param track Track name
     * @param artist Artist name
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param autocorrect Whether to autocorrect misspellings (1 = yes)
     * @return Top tags for the track
     */
    @GET(".")
    suspend fun getTrackTopTags(
        @Query("method") method: String = "track.getTopTags",
        @Query("track") track: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmTopTagsResponse>

    /**
     * Get top tags for an artist.
     * 
     * @param method API method (artist.getTopTags)
     * @param artist Artist name
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param autocorrect Whether to autocorrect misspellings (1 = yes)
     * @return Top tags for the artist
     */
    @GET(".")
    suspend fun getArtistTopTags(
        @Query("method") method: String = "artist.getTopTags",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmTopTagsResponse>

    // =====================================================================
    // USER HISTORY IMPORT ENDPOINTS
    // =====================================================================
    // These endpoints are used for Last.fm scrobble history import.
    // They require only an API key (no user authentication needed for public profiles).

    /**
     * Get user info including total scrobble count and registration date.
     * 
     * @param method API method (user.getInfo)
     * @param user Last.fm username
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @return User info with playcount, registered date, etc.
     */
    @GET(".")
    suspend fun getUserInfo(
        @Query("method") method: String = "user.getInfo",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json"
    ): Response<LastFmUserInfoResponse>

    /**
     * Get user's recent/all scrobbles (paginated).
     * 
     * Despite the name "getRecentTracks", this endpoint returns the COMPLETE
     * lifetime scrobble history when paginated properly.
     * 
     * @param method API method (user.getRecentTracks)
     * @param user Last.fm username
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param limit Items per page (max 200)
     * @param page Page number (1-based)
     * @param extended Include extended data (images, loved status) - 1 = yes
     * @param from Start timestamp (Unix timestamp) for filtering
     * @param to End timestamp (Unix timestamp) for filtering
     * @return Paginated list of scrobbles
     */
    @GET(".")
    suspend fun getRecentTracks(
        @Query("method") method: String = "user.getRecentTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 200,
        @Query("page") page: Int = 1,
        @Query("extended") extended: Int = 1,
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null
    ): Response<LastFmRecentTracksResponse>

    /**
     * Get user's top tracks (for active set calculation).
     * 
     * @param method API method (user.getTopTracks)
     * @param user Last.fm username
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param period Time period: overall, 7day, 1month, 3month, 6month, 12month
     * @param limit Items per page (max 1000)
     * @param page Page number (1-based)
     * @return User's top tracks with play counts
     */
    @GET(".")
    suspend fun getTopTracks(
        @Query("method") method: String = "user.getTopTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmTopTracksResponse>

    /**
     * Get user's loved tracks (always included in active set).
     * 
     * @param method API method (user.getLovedTracks)
     * @param user Last.fm username
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param limit Items per page (max 1000)
     * @param page Page number (1-based)
     * @return User's loved tracks
     */
    @GET(".")
    suspend fun getLovedTracks(
        @Query("method") method: String = "user.getLovedTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmLovedTracksResponse>

    /**
     * Get user's top artists (for priority enrichment).
     * 
     * @param method API method (user.getTopArtists)
     * @param user Last.fm username
     * @param apiKey Last.fm API key
     * @param format Response format (json)
     * @param period Time period: overall, 7day, 1month, 3month, 6month, 12month
     * @param limit Items per page (max 1000)
     * @param page Page number (1-based)
     * @return User's top artists with play counts
     */
    @GET(".")
    suspend fun getTopArtists(
        @Query("method") method: String = "user.getTopArtists",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmTopArtistsResponse>
}
