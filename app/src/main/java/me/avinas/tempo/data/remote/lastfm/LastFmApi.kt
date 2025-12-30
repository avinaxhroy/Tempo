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
}
