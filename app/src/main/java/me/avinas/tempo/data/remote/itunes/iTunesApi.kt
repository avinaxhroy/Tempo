package me.avinas.tempo.data.remote.itunes

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search API Retrofit interface.
 * 
 * API Documentation: https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
 * 
 * iTunes Search API is completely free with no authentication required.
 * Provides excellent metadata for mainstream commercial music.
 * 
 * Base URL: https://itunes.apple.com/
 * 
 * Rate Limit: ~20 requests per minute
 * No API key or authentication required!
 */
interface iTunesApi {

    companion object {
        const val BASE_URL = "https://itunes.apple.com/"
        
        // Rate limit: ~20 requests per minute
        const val RATE_LIMIT_MS = 3000L // 3 seconds between requests
    }

    /**
     * Search for music in the iTunes Store.
     * 
     * @param term Search query (artist + album for best results)
     * @param entity Type of content to search for (album, song, etc.)
     * @param limit Maximum number of results (1-200)
     * @param country Country code (US, IN, GB, etc.)
     * @param media Media type (music, movie, podcast, etc.)
     * @return Search results with album artwork and metadata
     */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("entity") entity: String = "album",
        @Query("limit") limit: Int = 5,
        @Query("country") country: String = "in", // India store for regional artist coverage
        @Query("media") media: String = "music"
    ): Response<iTunesSearchResponse>
    
    /**
     * Lookup artist by iTunes artist ID.
     * Returns artist information including images.
     * 
     * @param artistId iTunes artist ID
     * @param entity Type of entity (artist)
     * @return Artist information with images
     */
    @GET("lookup")
    suspend fun lookupArtist(
        @Query("id") artistId: Long,
        @Query("entity") entity: String? = null
    ): Response<iTunesSearchResponse>
}
