package me.avinas.tempo.data.remote.deezer

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for Deezer API.
 * 
 * Documentation: https://developers.deezer.com/api
 * 
 * We primarily use this for searching tracks to get 30s audio previews (mp3),
 * which are available publicly without authentication.
 */
interface DeezerApi {

    /**
     * Search for tracks.
     * 
     * @param query The search query (e.g. "artist:'Eminem' track:'Lose Yourself'")
     * @param limit Maximum number of results (default 25)
     */
    @GET("search/track")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 5
    ): Response<DeezerSearchResponse>
}
