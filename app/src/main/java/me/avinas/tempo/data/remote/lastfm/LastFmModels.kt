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
    @field:Json(name = "image") val images: List<LastFmImage>? = null
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
    @field:Json(name = "#text") val url: String? = null,
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
    @field:Json(name = "@attr") val attr: LastFmAttr? = null
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

// =====================================================================
// USER HISTORY IMPORT MODELS
// =====================================================================
// These models support Last.fm scrobble history import functionality.

// =====================
// User Info Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmUserInfoResponse(
    val user: LastFmUser? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmUser(
    val name: String? = null,
    val realname: String? = null,
    val url: String? = null,
    val country: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val subscriber: String? = null,
    val playcount: String? = null, // Total scrobble count as string
    @field:Json(name = "artist_count") val artistCount: String? = null,
    @field:Json(name = "track_count") val trackCount: String? = null,
    @field:Json(name = "album_count") val albumCount: String? = null,
    val image: List<LastFmImage>? = null,
    val registered: LastFmRegistered? = null
) {
    /**
     * Get total play count as Long.
     */
    fun getPlayCountLong(): Long? = playcount?.toLongOrNull()
    
    /**
     * Get registration timestamp in milliseconds.
     */
    fun getRegisteredTimestampMs(): Long? = registered?.unixtime?.toLongOrNull()?.times(1000)
    
    /**
     * Get best quality profile image.
     */
    fun getBestImageUrl(): String? {
        val preferredOrder = listOf("extralarge", "large", "medium", "small")
        for (size in preferredOrder) {
            val img = image?.find { it.size == size }
            if (!img?.url.isNullOrBlank()) return img?.url
        }
        return image?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
}

@JsonClass(generateAdapter = true)
data class LastFmRegistered(
    val unixtime: String? = null, // Unix timestamp as string
    @field:Json(name = "#text") val text: String? = null // Formatted date string
)

// =====================
// Recent Tracks Response (Scrobble History)
// =====================

@JsonClass(generateAdapter = true)
data class LastFmRecentTracksResponse(
    val recenttracks: LastFmRecentTracks? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmRecentTracks(
    val track: List<LastFmScrobble>? = null,
    @field:Json(name = "@attr") val attr: LastFmPaginationAttr? = null
)

/**
 * A single scrobble from the user's history.
 */
@JsonClass(generateAdapter = true)
data class LastFmScrobble(
    val name: String? = null, // Track title
    val mbid: String? = null, // MusicBrainz recording ID
    val url: String? = null,
    val artist: LastFmScrobbleArtist? = null,
    val album: LastFmScrobbleAlbum? = null,
    val image: List<LastFmImage>? = null,
    val streamable: String? = null,
    val loved: String? = null, // "0" or "1"
    val date: LastFmScrobbleDate? = null, // Null if currently playing
    @field:Json(name = "@attr") val attr: LastFmNowPlayingAttr? = null
) {
    /**
     * Get timestamp in milliseconds.
     */
    fun getTimestampMs(): Long? = date?.uts?.toLongOrNull()?.times(1000)
    
    /**
     * Check if this track is currently playing.
     */
    fun isNowPlaying(): Boolean = attr?.nowplaying == "true"
    
    /**
     * Check if this track is loved.
     */
    fun isLoved(): Boolean = loved == "1"
    
    /**
     * Get best quality album art URL.
     */
    fun getBestImageUrl(): String? {
        val preferredOrder = listOf("extralarge", "large", "medium", "small")
        for (size in preferredOrder) {
            val img = image?.find { it.size == size }
            if (!img?.url.isNullOrBlank()) return img?.url
        }
        return image?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
    
    /**
     * Get a normalized track key for deduplication.
     * Format: lowercase(artist_name|track_name)
     */
    fun getNormalizedKey(): String {
        // Use getArtistName() to handle both 'name' and '#text' fields
        val artistName = artist?.getArtistName()?.lowercase()?.trim() ?: ""
        val trackName = name?.lowercase()?.trim() ?: ""
        return "$artistName|$trackName"
    }
}

@JsonClass(generateAdapter = true)
data class LastFmScrobbleArtist(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    @field:Json(name = "#text") val text: String? = null, // Alternative name field
    val image: List<LastFmImage>? = null // Artist images from API
) {
    /**
     * Get artist name from either field.
     */
    fun getArtistName(): String? = name ?: text
    
    /**
     * Get best quality artist image URL.
     */
    fun getBestImageUrl(): String? {
        val preferredOrder = listOf("extralarge", "large", "medium", "small")
        for (size in preferredOrder) {
            val img = image?.find { it.size == size }
            if (!img?.url.isNullOrBlank()) return img?.url
        }
        return image?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
}

@JsonClass(generateAdapter = true)
data class LastFmScrobbleAlbum(
    val mbid: String? = null,
    @field:Json(name = "#text") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmScrobbleDate(
    val uts: String? = null, // Unix timestamp as string
    @field:Json(name = "#text") val text: String? = null // Formatted date string
)

@JsonClass(generateAdapter = true)
data class LastFmNowPlayingAttr(
    val nowplaying: String? = null // "true" if currently playing
)

// =====================
// Pagination Attributes
// =====================

@JsonClass(generateAdapter = true)
data class LastFmPaginationAttr(
    val user: String? = null,
    val totalPages: String? = null,
    val page: String? = null,
    val perPage: String? = null,
    val total: String? = null
) {
    fun getTotalPages(): Int = totalPages?.toIntOrNull() ?: 0
    fun getCurrentPage(): Int = page?.toIntOrNull() ?: 0
    fun getPerPage(): Int = perPage?.toIntOrNull() ?: 0
    fun getTotal(): Int = total?.toIntOrNull() ?: 0
}

// =====================
// Top Tracks Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmTopTracksResponse(
    val toptracks: LastFmTopTracks? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTopTracks(
    val track: List<LastFmTopTrack>? = null,
    @field:Json(name = "@attr") val attr: LastFmPaginationAttr? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTopTrack(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val playcount: String? = null, // User's play count for this track
    val duration: String? = null, // Duration in seconds as string
    val artist: LastFmScrobbleArtist? = null,
    val image: List<LastFmImage>? = null,
    @field:Json(name = "@attr") val attr: LastFmRankAttr? = null
) {
    /**
     * Get play count as Int.
     */
    fun getPlayCountInt(): Int = playcount?.toIntOrNull() ?: 0
    
    /**
     * Get duration in milliseconds.
     */
    fun getDurationMs(): Long? = duration?.toLongOrNull()?.times(1000)
    
    /**
     * Get rank from attributes.
     */
    fun getRank(): Int = attr?.rank?.toIntOrNull() ?: 0
    
    /**
     * Get normalized track key for matching.
     */
    fun getNormalizedKey(): String {
        val artistName = artist?.getArtistName()?.lowercase()?.trim() ?: ""
        val trackName = name?.lowercase()?.trim() ?: ""
        return "$artistName|$trackName"
    }
}

@JsonClass(generateAdapter = true)
data class LastFmRankAttr(
    val rank: String? = null
)

// =====================
// Loved Tracks Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmLovedTracksResponse(
    val lovedtracks: LastFmLovedTracks? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmLovedTracks(
    val track: List<LastFmLovedTrack>? = null,
    @field:Json(name = "@attr") val attr: LastFmPaginationAttr? = null
)

@JsonClass(generateAdapter = true)
data class LastFmLovedTrack(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val artist: LastFmScrobbleArtist? = null,
    val image: List<LastFmImage>? = null,
    val date: LastFmScrobbleDate? = null // When the track was loved
) {
    /**
     * Get normalized track key for matching.
     */
    fun getNormalizedKey(): String {
        val artistName = artist?.getArtistName()?.lowercase()?.trim() ?: ""
        val trackName = name?.lowercase()?.trim() ?: ""
        return "$artistName|$trackName"
    }
}

// =====================
// Top Artists Response
// =====================

@JsonClass(generateAdapter = true)
data class LastFmTopArtistsResponse(
    val topartists: LastFmTopArtists? = null,
    val error: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTopArtists(
    val artist: List<LastFmTopArtist>? = null,
    @field:Json(name = "@attr") val attr: LastFmPaginationAttr? = null
)

@JsonClass(generateAdapter = true)
data class LastFmTopArtist(
    val name: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val playcount: String? = null,
    val image: List<LastFmImage>? = null,
    @field:Json(name = "@attr") val attr: LastFmRankAttr? = null
) {
    /**
     * Get play count as Int.
     */
    fun getPlayCountInt(): Int = playcount?.toIntOrNull() ?: 0
    
    /**
     * Get rank from attributes.
     */
    fun getRank(): Int = attr?.rank?.toIntOrNull() ?: 0
    
    /**
     * Get best quality artist image.
     */
    fun getBestImageUrl(): String? {
        val preferredOrder = listOf("extralarge", "large", "medium", "small")
        for (size in preferredOrder) {
            val img = image?.find { it.size == size }
            if (!img?.url.isNullOrBlank()) return img?.url
        }
        return image?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
    }
}
