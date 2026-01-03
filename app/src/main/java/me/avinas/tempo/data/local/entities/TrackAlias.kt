package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a "Smart Alias" for track deduplication.
 * 
 * When a user manually merges "Track A (Live)" into "Track A",
 * we create an alias here. Future plays of "Track A (Live)" will
 * automatically be re-mapped to "Track A".
 */
@Entity(
    tableName = "track_aliases",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["target_track_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["original_title", "original_artist"], unique = true),
        Index(value = ["target_track_id"])
    ]
)
data class TrackAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    @ColumnInfo(name = "target_track_id")
    val targetTrackId: Long,
    
    @ColumnInfo(name = "original_title")
    val originalTitle: String,
    
    @ColumnInfo(name = "original_artist")
    val originalArtist: String,
    
    /**
     * Timestamp when this alias was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
