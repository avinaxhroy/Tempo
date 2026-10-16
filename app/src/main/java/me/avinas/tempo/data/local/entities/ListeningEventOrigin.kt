package me.avinas.tempo.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * Stable producer identity for one physical listening event.
 *
 * Several devices can report the same playback; one device can only claim a
 * single origin for that playback. The foreign key ensures an alias cannot
 * outlive the listening event it refers to (including authority replacements).
 */
@Entity(
    tableName = "listening_event_origins",
    primaryKeys = ["accountSubject", "originEventId"],
    foreignKeys = [
        ForeignKey(
            entity = ListeningEvent::class,
            parentColumns = ["id"],
            childColumns = ["listeningEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["listeningEventId"]),
        Index(value = ["listeningEventId", "sourceDeviceId"], unique = true),
    ],
)
data class ListeningEventOrigin(
    val originEventId: String,
    val listeningEventId: Long,
    val sourceDeviceId: String,
    /** Immutable Google owner; unknown LAN/legacy origins remain quarantined. */
    @ColumnInfo(defaultValue = "'legacy-unverified'")
    val accountSubject: String = "legacy-unverified",
)
