package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a music track in the database.
 * 
 * The track maintains both:
 * - A denormalized 'artist' string for display and backward compatibility
 * - A foreign key 'primary_artist_id' linking to the Artists table
 * 
 * For multi-artist tracks, the full artist relationships are stored
 * in the track_artists junction table.
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = Artist::class,
            parentColumns = ["id"],
            childColumns = ["primary_artist_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["spotify_id"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["primary_artist_id"]),
        Index(value = ["title", "artist"]) // For duplicate detection
    ]
)
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    
    /**
     * Raw artist string as received from music player.
     * Kept for display and backward compatibility.
     * May contain multiple artists like "Artist1, Artist2" or "Artist1 feat. Artist2"
     */
    val artist: String,
    
    val album: String?,
    val duration: Long?,
    
    @ColumnInfo(name = "album_art_url") 
    val albumArtUrl: String?,
    
    @ColumnInfo(name = "spotify_id") 
    val spotifyId: String?,
    
    @ColumnInfo(name = "musicbrainz_id") 
    val musicbrainzId: String?,
    
    /**
     * Foreign key to the primary artist in the Artists table.
     * This is the main/first artist for the track.
     * Can be null for legacy tracks not yet migrated.
     */
    @ColumnInfo(name = "primary_artist_id")
    val primaryArtistId: Long? = null
)
