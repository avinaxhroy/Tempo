package me.avinas.tempo.data.remote.musicbrainz

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MusicBrainz API Retrofit interface.
 * 
 * API Documentation: https://musicbrainz.org/doc/MusicBrainz_API
 * 
 * Rate Limiting: MusicBrainz requires max 1 request per second for unauthenticated requests.
 * All requests must include a User-Agent header identifying the application.
 * 
 * Base URL: https://musicbrainz.org/ws/2/
 * 
 * The `inc` parameter specifies which related data to include in responses.
 * The `fmt=json` parameter ensures JSON responses (XML is default).
 */
interface MusicBrainzApi {

    companion object {
        const val BASE_URL = "https://musicbrainz.org/ws/2/"
        const val COVER_ART_BASE_URL = "https://coverartarchive.org/"
        
        // User-Agent loaded from BuildConfig
        // MusicBrainz requires a proper User-Agent with contact info
        // See: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
        val USER_AGENT: String = me.avinas.tempo.BuildConfig.MUSICBRAINZ_USER_AGENT
        
        // Rate limit: 1 request per second
        val RATE_LIMIT_MS: Long = me.avinas.tempo.BuildConfig.MUSICBRAINZ_RATE_LIMIT_MS
    }

    // =====================
    // Recording (Track) Search & Lookup
    // =====================

    /**
     * Search for recordings (tracks) by query.
     * 
     * Query syntax supports Lucene query syntax:
     * - recording:"song title" AND artist:"artist name"
     * - recording:"song title" AND artistname:"artist name"
     * 
     * @param query Lucene query string
     * @param limit Max results (1-100, default 25)
     * @param offset Pagination offset
     * @return Search results with matching recordings
     */
    @GET("recording")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("fmt") format: String = "json"
    ): Response<RecordingSearchResponse>

    /**
     * Lookup a specific recording by MusicBrainz ID.
     * 
     * @param mbid MusicBrainz Recording ID (UUID)
     * @param inc Related data to include (artists, releases, tags, genres, isrcs, url-rels)
     * @return Detailed recording information
     */
    @GET("recording/{mbid}")
    suspend fun lookupRecording(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "artists+releases+tags+genres+isrcs+url-rels+release-groups",
        @Query("fmt") format: String = "json"
    ): Response<RecordingLookupResponse>

    // =====================
    // Artist Search & Lookup
    // =====================

    /**
     * Lookup a specific artist by MusicBrainz ID.
     * 
     * @param mbid MusicBrainz Artist ID (UUID)
     * @param inc Related data to include
     * @return Detailed artist information
     */
    @GET("artist/{mbid}")
    suspend fun lookupArtist(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "tags+genres+aliases+url-rels+release-groups",
        @Query("fmt") format: String = "json"
    ): Response<ArtistLookupResponse>

    // =====================
    // Release (Album) Search & Lookup
    // =====================

    /**
     * Lookup a specific release by MusicBrainz ID.
     * 
     * @param mbid MusicBrainz Release ID (UUID)
     * @param inc Related data to include
     * @return Detailed release information
     */
    @GET("release/{mbid}")
    suspend fun lookupRelease(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "artists+labels+recordings+release-groups+tags+genres+url-rels",
        @Query("fmt") format: String = "json"
    ): Response<ReleaseLookupResponse>
}

/**
 * Cover Art Archive API interface.
 * 
 * This is a separate API from MusicBrainz that provides album artwork.
 * No rate limiting, but be respectful.
 * 
 * Base URL: https://coverartarchive.org/
 */
interface CoverArtArchiveApi {

    /**
     * Get cover art for a release.
     * 
     * @param mbid MusicBrainz Release ID
     * @return Cover art information including image URLs
     */
    @GET("release/{mbid}")
    suspend fun getReleaseCoverArt(
        @Path("mbid") mbid: String
    ): Response<CoverArtResponse>
    
    /**
     * Get cover art for a release group.
     * 
     * @param mbid MusicBrainz Release Group ID
     * @return Cover art information including image URLs
     */
    @GET("release-group/{mbid}")
    suspend fun getReleaseGroupCoverArt(
        @Path("mbid") mbid: String
    ): Response<CoverArtResponse>

    /**
     * Get front cover art directly (redirects to image).
     * Use this URL pattern for direct image access:
     * https://coverartarchive.org/release/{mbid}/front-250
     * https://coverartarchive.org/release/{mbid}/front-500
     * https://coverartarchive.org/release/{mbid}/front-1200
     */
    @GET("release/{mbid}/front")
    suspend fun getReleaseFrontCover(
        @Path("mbid") mbid: String
    ): Response<Unit>

    companion object {
        const val BASE_URL = "https://coverartarchive.org/"
        
        /**
         * Build direct URL to front cover at specific size.
         */
        fun getFrontCoverUrl(releaseMbid: String, size: Int = 500): String {
            return "${BASE_URL}release/$releaseMbid/front-$size"
        }
        
        /**
         * Build direct URL to release group front cover at specific size.
         */
        fun getReleaseGroupFrontCoverUrl(releaseGroupMbid: String, size: Int = 500): String {
            return "${BASE_URL}release-group/$releaseGroupMbid/front-$size"
        }
    }
}
