package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table for many-to-many relationship between tracks and artists.
 * 
 * A track can have multiple artists (e.g., collaborations, features)
 * An artist can appear on multiple tracks
 * 
 * The role field distinguishes between:
 * - PRIMARY: The main artist(s) of the track
 * - FEATURED: Artists featured on the track (feat., ft., with, etc.)
 * - PRODUCER: Producer credits
 * - REMIXER: For remixes
 */
@Entity(
    tableName = "track_artists",
    primaryKeys = ["track_id", "artist_id"],
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Artist::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["artist_id"]),
        Index(value = ["track_id", "role"]),
        Index(value = ["artist_id", "role"])
    ]
)
data class TrackArtist(
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    
    /**
     * The role of the artist on this track.
     * See ArtistRole enum for possible values.
     */
    val role: ArtistRole = ArtistRole.PRIMARY,
    
    /**
     * Order of the artist in credits (0 = first credited artist)
     */
    @ColumnInfo(name = "credit_order")
    val creditOrder: Int = 0
)

/**
 * Enumeration of possible artist roles on a track.
 */
enum class ArtistRole {
    PRIMARY,    // Main artist(s)
    FEATURED,   // Featured artist (feat., ft., with)
    PRODUCER,   // Producer credit
    REMIXER,    // Remix credit
    COMPOSER,   // Songwriter/composer
    PERFORMER   // Additional performer
}
