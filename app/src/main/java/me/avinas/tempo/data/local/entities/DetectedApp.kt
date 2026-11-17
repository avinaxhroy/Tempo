package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An app that produced media Tempo refused to track because it is not on any allowlist.
 *
 * Tempo tracks only apps it knows to be music sources (curated defaults plus anything the
 * user added). Previously the rule was inverted — everything was tracked unless it matched a
 * blocklist — which meant any app that posted a media-style notification (Viber voice notes,
 * Signal voice messages, voicemail) was journaled as a song and pinned to the foreground
 * notification.
 *
 * Default-deny alone would leave those users with no way to discover why their player went
 * silent, so every rejected media session is recorded here. The Manage Apps screen lists these
 * as "Detected — not tracked" with a one-tap Allow, which turns the allowlist from a dead end
 * into a review queue.
 *
 * Display names are not stored: the package label is resolved from PackageManager
 * at render time so it stays correct after an app is renamed or updated.
 */
@Entity(
    tableName = "detected_apps",
    indices = [Index(value = ["lastSeenAt"])],
)
data class DetectedApp(
    /** Android package name (e.g., "com.viber.voip"). */
    @PrimaryKey val packageName: String,

    /** Title of the most recent rejected item — shown as context ("Voice message"). */
    @ColumnInfo(defaultValue = "NULL") val lastTitle: String? = null,

    /** Artist/creator of the most recent rejected item, when the source exposed one. */
    @ColumnInfo(defaultValue = "NULL") val lastArtist: String? = null,

    /** How many times this app was rejected. A high count means it is used often. */
    @ColumnInfo(defaultValue = "1") val detectionCount: Int = 1,

    @ColumnInfo(defaultValue = "0") val firstSeenAt: Long = System.currentTimeMillis(),

    /** Drives inbox ordering — most recently detected first. */
    @ColumnInfo(defaultValue = "0") val lastSeenAt: Long = System.currentTimeMillis(),
)
