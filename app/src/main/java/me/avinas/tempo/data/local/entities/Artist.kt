package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an artist in the database.
 * 
 * Artists are linked to tracks via:
 * - Track.primary_artist_id (for the main artist)
 * - TrackArtist junction table (for all artists including features)
 * 
 * Artists are also linked to albums via Album.artist_id
 */
@Entity(
    tableName = "artists", 
    indices = [
        Index(value = ["musicbrainz_id"], unique = true),
        Index(value = ["spotify_id"]),
        Index(value = ["name"]), // For name lookups
        Index(value = ["normalized_name"], unique = true) // For deduplication
    ]
)
data class Artist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /**
     * Display name of the artist as it appears in credits.
     */
    val name: String,
    
    /**
     * Normalized name for deduplication (lowercase, trimmed, special chars removed).
     * This helps prevent duplicate entries like "KR$NA" vs "Krsna" vs "KRSNA".
     */
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String = name.lowercase().trim(),
    
    @ColumnInfo(name = "image_url") 
    val imageUrl: String?,
    
    val genres: List<String> = emptyList(),
    
    @ColumnInfo(name = "musicbrainz_id") 
    val musicbrainzId: String?,
    
    @ColumnInfo(name = "spotify_id") 
    val spotifyId: String?,
    
    /**
     * Country/region of the artist (e.g., "IN", "US", "GB")
     */
    val country: String? = null,
    
    /**
     * Type of artist: Person, Group, Orchestra, etc.
     */
    @ColumnInfo(name = "artist_type")
    val artistType: String? = null
) {
    companion object {
        // Pre-compiled regex patterns to avoid repeated native memory allocation
        private val SPECIAL_CHARS_PATTERN = Regex("[^a-z0-9\\s]")
        private val WHITESPACE_PATTERN = Regex("\\s+")
        
        /**
         * Normalize an artist name for comparison and deduplication.
         */
        fun normalizeName(name: String): String {
            return name
                .lowercase()
                .trim()
                .replace(SPECIAL_CHARS_PATTERN, "") // Remove special chars
                .replace(WHITESPACE_PATTERN, " ") // Normalize whitespace
        }
    }
}
