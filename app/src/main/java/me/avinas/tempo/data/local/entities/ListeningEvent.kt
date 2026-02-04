package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single listening event for a track.
 * 
 * Enhanced tracking fields:
 * - wasSkipped: True if user skipped before 30% completion
 * - isReplay: True if this was played within 5 minutes of the same track
 * - estimatedDurationMs: The track's estimated duration (for completion calculation)
 * - pauseCount: Number of times playback was paused
 * - sessionId: Groups events from the same listening session
 */
@Entity(
    tableName = "listening_events",
    foreignKeys = [ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["timestamp"]), 
        Index(value = ["track_id"]),
        Index(value = ["session_id"]),
        Index(value = ["was_skipped"]),  // For skip rate queries
        Index(value = ["is_replay"]),    // For replay count queries
        Index(value = ["source"]),       // For filtering by source (Last.fm vs live)
        Index(value = ["source", "timestamp"]),  // For source + time queries
        Index(name = "index_listening_events_timestamp_track_id", value = ["timestamp", "track_id"]),
        Index(name = "index_listening_events_track_id_timestamp", value = ["track_id", "timestamp"]),
        Index(name = "index_listening_events_stats", value = ["timestamp", "track_id", "playDuration", "completionPercentage"])
    ]
)
data class ListeningEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val track_id: Long,
    val timestamp: Long,
    val playDuration: Long,  // Keep original column name for backward compatibility
    val completionPercentage: Int,  // Keep original column name for backward compatibility
    val source: String, // e.g., "com.spotify.music", "com.google.android.apps.youtube.music"
    
    // Enhanced tracking fields (with defaults for backward compatibility)
    @ColumnInfo(name = "was_skipped", defaultValue = "0") val wasSkipped: Boolean = false,
    @ColumnInfo(name = "is_replay", defaultValue = "0") val isReplay: Boolean = false,
    @ColumnInfo(name = "estimated_duration_ms", defaultValue = "NULL") val estimatedDurationMs: Long? = null,
    @ColumnInfo(name = "pause_count", defaultValue = "0") val pauseCount: Int = 0,
    @ColumnInfo(name = "session_id", defaultValue = "NULL") val sessionId: String? = null,
    @ColumnInfo(name = "end_timestamp", defaultValue = "NULL") val endTimestamp: Long? = null,
    
    // NEW: Enhanced robustness tracking fields
    @ColumnInfo(name = "total_pause_duration_ms", defaultValue = "0") val totalPauseDurationMs: Long = 0,
    @ColumnInfo(name = "seek_count", defaultValue = "0") val seekCount: Int = 0,
    @ColumnInfo(name = "position_updates_count", defaultValue = "0") val positionUpdatesCount: Int = 0,
    @ColumnInfo(name = "was_interrupted", defaultValue = "0") val wasInterrupted: Boolean = false
) {
    /**
     * Check if this was a full play (>80% completion).
     */
    val isFullPlay: Boolean get() = completionPercentage >= 80
    
    /**
     * Check if this was a partial play (30-80% completion).
     */
    val isPartialPlay: Boolean get() = completionPercentage in 30..79
    
    /**
     * Get actual play duration in seconds for display.
     */
    val playDurationSeconds: Int get() = (playDuration / 1000).toInt()
    
    /**
     * Calculate listening quality score (0-100).
     * Higher score = more engaged listening.
     */
    val qualityScore: Int get() {
        var score = completionPercentage
        if (wasSkipped) score -= 20
        if (isReplay) score += 15
        if (pauseCount == 0 && completionPercentage >= 80) score += 10
        return score.coerceIn(0, 100)
    }
}
