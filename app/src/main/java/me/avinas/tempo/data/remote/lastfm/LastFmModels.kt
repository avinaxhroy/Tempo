package me.avinas.tempo.data.remote.lastfm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Last.fm API response models.
 * 
 * Last.fm returns data in a specific nested structure that differs
 * from most modern APIs. These models handle the quirks of the Last.fm API.
 */

// =====================
// Track Info Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmTrackResponse(
    val track: LastFmTrack? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTrack(
    val name: String? = null,
    val mbid: String? = null, // MusicBrainz ID
    val url: String? = null,
    val duration: String? = null, // Duration in milliseconds as string
    val listeners: String? = null,
    val playcount: String? = null,
    val artist: LastFmArtistBasic? = null,
    val album: LastFmAlbumBasic? = null,
    val toptags: LastFmTagContainer? = null,
    val wiki: LastFmWiki? = null
) {
    /**
     * Get duration as Long, handling Last.fm's string format.
     */
    fun getDurationMs(): Long? {
        return duration?.toLongOrNull()
    }
    
    /**
     * Get tags as a list of strings.
     */
    fun getTagNames(): List<String> {
        return toptags?.tag?.mapNotNull { it.name } ?: emptyList()
    }
}

@JsonClass(generateAdapter = true)
data class LastFmArtistBasic(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmAlbumBasic(
    val artist: String? = null,
    val title: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    @Json(name = "image") val images: List<LastFmImage>? = null
) {
    /**
     * Get the best quality album art URL.
     */
    fun getBestImageUrl(): String? {
        // Last.fm image sizes: small, medium, large, extralarge, mega
        val preferredOrder = listOf("extralarge", "large", "mega", "medium", "small")
        for (size in preferredOrder) {
            val image = images?.find { it.size == size }
            if (!image?.url.isNullOrBlank()) {
                return image?.url
            }
        }
        return images?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
    
    /**
     * Get small album art URL for thumbnails.
     */
    fun getSmallImageUrl(): String? {
        return images?.find { it.size == "medium" || it.size == "small" }?.url?.takeIf { it.isNotBlank() }
    }
    
    /**
     * Get large album art URL.
     */
    fun getLargeImageUrl(): String? {
        return images?.find { it.size == "mega" || it.size == "extralarge" }?.url?.takeIf { it.isNotBlank() }
    }
}

@JsonClass(generateAdapter = true)
data class LastFmImage(
    @Json(name = "#text") val url: String? = null,
    val size: String? = null // small, medium, large, extralarge, mega
)

@JsonClass(generateAdapter = true)
data class LastFmWiki(
    val published: String? = null,
    val summary: String? = null,
    val content: String? = null
)

// =====================
// Artist Info Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmArtistResponse(
    val artist: LastFmArtist? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmArtist(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val image: List<LastFmImage>? = null,
    val stats: LastFmArtistStats? = null,
    val similar: LastFmSimilarArtists? = null,
    val tags: LastFmTagContainer? = null,
    val bio: LastFmWiki? = null
) {
    /**
     * Get the best quality artist image URL.
     */
    fun getBestImageUrl(): String? {
        val preferredOrder = listOf("extralarge", "large", "mega", "medium", "small")
        for (size in preferredOrder) {
            val img = image?.find { it.size == size }
            if (!img?.url.isNullOrBlank()) {
                return img?.url
            }
        }
        return image?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
    
    /**
     * Get tags as a list of strings.
     */
    fun getTagNames(): List<String> {
        return tags?.tag?.mapNotNull { it.name } ?: emptyList()
    }
    
    /**
     * Get similar artist names.
     */
    fun getSimilarArtistNames(): List<String> {
        return similar?.artist?.mapNotNull { it.name } ?: emptyList()
    }
}

@JsonClass(generateAdapter = true)
data class LastFmArtistStats(
    val listeners: String? = null,
    val playcount: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmSimilarArtists(
    val artist: List<LastFmSimilarArtist>? = null
)

@JsonClass(generateAdapter = true)
data class LastFmSimilarArtist(
    val name: String? = null,
    val url: String? = null,
    val image: List<LastFmImage>? = null
)

// =====================
// Tags Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmTopTagsResponse(
    val toptags: LastFmTagContainer? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTagContainer(
    val tag: List<LastFmTag>? = null,
    @Json(name = "@attr") val attr: LastFmAttr? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTag(
    val name: String? = null,
    val url: String? = null,
    val count: Int? = null // Tag weight/count (higher = more relevant)
)

@JsonClass(generateAdapter = true)
data class LastFmAttr(
    val artist: String? = null,
    val track: String? = null
)

// =====================
// Error Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmError(
    val error: Int,
    val message: String
) {
    companion object {
        // Common Last.fm error codes
        const val INVALID_SERVICE = 2
        const val INVALID_METHOD = 3
        const val AUTHENTICATION_FAILED = 4
        const val INVALID_FORMAT = 5
        const val INVALID_PARAMETERS = 6
        const val INVALID_RESOURCE = 7
        const val OPERATION_FAILED = 8
        const val INVALID_SESSION_KEY = 9
        const val INVALID_API_KEY = 10
        const val SERVICE_OFFLINE = 11
        const val RATE_LIMIT_EXCEEDED = 29
        const val TRACK_NOT_FOUND = 6 // Returns as "Track not found"
        const val ARTIST_NOT_FOUND = 6 // Returns as "Artist not found"
    }
}
