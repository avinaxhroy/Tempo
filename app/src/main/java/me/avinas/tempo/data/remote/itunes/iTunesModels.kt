package me.avinas.tempo.data.remote.itunes

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * iTunes Search API response models.
 * 
 * API Documentation: https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
 * 
 * iTunes provides excellent coverage for mainstream commercial music with
 * high-quality album artwork and metadata.
 */

@JsonClass(generateAdapter = true)
data class iTunesSearchResponse(
    @Json(name = "resultCount") val resultCount: Int = 0,
    @Json(name = "results") val results: List<iTunesResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class iTunesResult(
    // Wrapper type - tells us what kind of result this is
    @Json(name = "wrapperType") val wrapperType: String? = null, // "track" or "collection"
    @Json(name = "kind") val kind: String? = null, // "song", "album", etc.
    
    // Track Info
    @Json(name = "trackId") val trackId: Long? = null,
    @Json(name = "trackName") val trackName: String? = null,
    @Json(name = "trackCensoredName") val trackCensoredName: String? = null,
    @Json(name = "trackTimeMillis") val trackTimeMillis: Long? = null,
    @Json(name = "trackNumber") val trackNumber: Int? = null,
    @Json(name = "trackCount") val trackCount: Int? = null,
    
    // Artist Info
    @Json(name = "artistId") val artistId: Long? = null,
    @Json(name = "artistName") val artistName: String? = null,
    
    // Collection/Album Info
    @Json(name = "collectionId") val collectionId: Long? = null,
    @Json(name = "collectionName") val collectionName: String? = null,
    @Json(name = "collectionCensoredName") val collectionCensoredName: String? = null,
    
    // Album Artwork URLs (multiple sizes available)
    @Json(name = "artworkUrl30") val artworkUrl30: String? = null,
    @Json(name = "artworkUrl60") val artworkUrl60: String? = null,
    @Json(name = "artworkUrl100") val artworkUrl100: String? = null,
    
    // Genre
    @Json(name = "primaryGenreName") val primaryGenreName: String? = null,
    @Json(name = "primaryGenreId") val primaryGenreId: Int? = null,
    
    // Release Date
    @Json(name = "releaseDate") val releaseDate: String? = null, // ISO 8601 format
    
    // Preview
    @Json(name = "previewUrl") val previewUrl: String? = null, // 30-second preview
    
    // Country/Region
    @Json(name = "country") val country: String? = null,
    @Json(name = "currency") val currency: String? = null,
    
    // Links
    @Json(name = "artistLinkUrl") val artistLinkUrl: String? = null,
    @Json(name = "trackViewUrl") val trackViewUrl: String? = null,
    @Json(name = "collectionViewUrl") val collectionViewUrl: String? = null,
    
    // Price (not very useful for our app)
    @Json(name = "trackPrice") val trackPrice: Double? = null,
    @Json(name = "collectionPrice") val collectionPrice: Double? = null
) {
    /**
     * Get the best available album artwork URL.
     * iTunes provides 100x100 by default, but we can scale it up.
     * Replace "100x100bb.jpg" with "600x600bb.jpg" for higher quality.
     */
    fun getBestArtworkUrl(): String? {
        return artworkUrl100?.replace("100x100bb.jpg", "600x600bb.jpg")
    }
    
    /**
     * Get medium size artwork (300x300).
     */
    fun getMediumArtworkUrl(): String? {
        return artworkUrl100?.replace("100x100bb.jpg", "300x300bb.jpg")
    }
    
    /**
     * Get small artwork for thumbnails (150x150).
     */
    fun getSmallArtworkUrl(): String? {
        return artworkUrl100?.replace("100x100bb.jpg", "150x150bb.jpg")
    }
    
    /**
     * Get release year from release date.
     */
    fun getReleaseYear(): Int? {
        return releaseDate?.take(4)?.toIntOrNull()
    }
    
    /**
     * Get duration in milliseconds.
     */
    fun getDurationMs(): Long? {
        return trackTimeMillis
    }
}
