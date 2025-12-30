package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    foreignKeys = [ForeignKey(entity = Artist::class, parentColumns = ["id"], childColumns = ["artist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["artist_id"]),
        Index(value = ["musicbrainz_id"], unique = true)
    ]
)
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "release_year") val releaseYear: Int?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "musicbrainz_id") val musicbrainzId: String? = null,
    @ColumnInfo(name = "release_type") val releaseType: String? = null // Album, Single, EP, etc.
)
