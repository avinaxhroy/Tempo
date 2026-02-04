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
    @field:Json(name = "resultCount") val resultCount: Int = 0,
    @field:Json(name = "results") val results: List<iTunesResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class iTunesResult(
    // Wrapper type - tells us what kind of result this is
    @field:Json(name = "wrapperType") val wrapperType: String? = null, // "track" or "collection"
    @field:Json(name = "kind") val kind: String? = null, // "song", "album", etc.
    
    // Track Info
    @field:Json(name = "trackId") val trackId: Long? = null,
    @field:Json(name = "trackName") val trackName: String? = null,
    @field:Json(name = "trackCensoredName") val trackCensoredName: String? = null,
    @field:Json(name = "trackTimeMillis") val trackTimeMillis: Long? = null,
    @field:Json(name = "trackNumber") val trackNumber: Int? = null,
    @field:Json(name = "trackCount") val trackCount: Int? = null,
    
    // Artist Info
    @field:Json(name = "artistId") val artistId: Long? = null,
    @field:Json(name = "artistName") val artistName: String? = null,
    
    // Collection/Album Info
    @field:Json(name = "collectionId") val collectionId: Long? = null,
    @field:Json(name = "collectionName") val collectionName: String? = null,
    @field:Json(name = "collectionCensoredName") val collectionCensoredName: String? = null,
    
    // Album Artwork URLs (multiple sizes available)
    @field:Json(name = "artworkUrl30") val artworkUrl30: String? = null,
    @field:Json(name = "artworkUrl60") val artworkUrl60: String? = null,
    @field:Json(name = "artworkUrl100") val artworkUrl100: String? = null,
    
    // Genre
    @field:Json(name = "primaryGenreName") val primaryGenreName: String? = null,
    @field:Json(name = "primaryGenreId") val primaryGenreId: Int? = null,
    
    // Release Date
    @field:Json(name = "releaseDate") val releaseDate: String? = null, // ISO 8601 format
    
    // Preview
    @field:Json(name = "previewUrl") val previewUrl: String? = null, // 30-second preview
    
    // Country/Region
    @field:Json(name = "country") val country: String? = null,
    @field:Json(name = "currency") val currency: String? = null,
    
    // Links
    @field:Json(name = "artistLinkUrl") val artistLinkUrl: String? = null,
    @field:Json(name = "trackViewUrl") val trackViewUrl: String? = null,
    @field:Json(name = "collectionViewUrl") val collectionViewUrl: String? = null,
    
    // Price (not very useful for our app)
    @field:Json(name = "trackPrice") val trackPrice: Double? = null,
    @field:Json(name = "collectionPrice") val collectionPrice: Double? = null
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
