package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an alias mapping for artist deduplication.
 * 
 * When a user manually merges "Billie Eilish - Topic" into "Billie Eilish",
 * we create an alias here. Future plays of "Billie Eilish - Topic" will
 * automatically be re-mapped to "Billie Eilish".
 * 
 * This is similar to TrackAlias but for artists instead of tracks.
 */
@Entity(
    tableName = "artist_aliases",
    foreignKeys = [
        ForeignKey(
            entity = Artist::class,
            parentColumns = ["id"],
            childColumns = ["target_artist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["original_name_normalized"], unique = true),
        Index(value = ["target_artist_id"])
    ]
)
data class ArtistAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /**
     * The target (canonical) artist ID that this alias points to.
     * All plays for the original artist name will be attributed to this artist.
     */
    @ColumnInfo(name = "target_artist_id")
    val targetArtistId: Long,
    
    /**
     * The original artist name that was merged.
     * Kept for display purposes and debugging.
     */
    @ColumnInfo(name = "original_name")
    val originalName: String,
    
    /**
     * Normalized version of original name for lookup.
     * Uses Artist.normalizeName() for consistency.
     */
    @ColumnInfo(name = "original_name_normalized")
    val originalNameNormalized: String,
    
    /**
     * Timestamp when this alias was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Create an alias from an original artist name to a target artist.
         */
        fun create(originalName: String, targetArtistId: Long): ArtistAlias {
            return ArtistAlias(
                targetArtistId = targetArtistId,
                originalName = originalName,
                originalNameNormalized = Artist.normalizeName(originalName)
            )
        }
    }
}
