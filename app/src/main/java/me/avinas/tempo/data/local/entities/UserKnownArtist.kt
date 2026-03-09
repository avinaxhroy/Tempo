package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a user-defined known artist/band name that should never be split
 * by the ArtistParser.
 * 
 * When a user renames a split artist (e.g., "Tyler" → "Tyler, The Creator"),
 * the full name is stored here so future parsing preserves it as a single artist.
 * 
 * This supplements the hardcoded KNOWN_COMPLEX_BANDS list in ArtistParser
 * with user-specific artist names.
 */
@Entity(
    tableName = "user_known_artists",
    indices = [
        Index(value = ["normalized_name"], unique = true)
    ]
)
data class UserKnownArtist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The full artist/band name as entered by the user.
     * e.g., "Tyler, The Creator"
     */
    val name: String,

    /**
     * Lowercase normalized name for fast lookup.
     * e.g., "tyler, the creator"
     */
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String = name.trim().lowercase(),

    /**
     * Timestamp when this entry was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Create a UserKnownArtist from a display name.
         */
        fun create(name: String): UserKnownArtist {
            return UserKnownArtist(
                name = name.trim(),
                normalizedName = name.trim().lowercase()
            )
        }
    }
}
