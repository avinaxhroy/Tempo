package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores metadata about Last.fm import sessions.
 * 
 * This tracks:
 * - Import configuration (username, tier, thresholds)
 * - Progress state (for resume capability)
 * - Results (counts of imported/archived items)
 * - Sync cursor (for incremental updates)
 */
@Entity(tableName = "lastfm_import_metadata")
data class LastFmImportMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    // =====================
    // User Configuration
    // =====================
    
    /**
     * Last.fm username for the import.
     */
    @ColumnInfo(name = "lastfm_username")
    val lastfmUsername: String,
    
    /**
     * Import tier selected by user.
     * Values: LIGHTWEIGHT, BALANCED, COMPREHENSIVE, EVERYTHING
     */
    @ColumnInfo(name = "import_tier")
    val importTier: String,
    
    /**
     * Number of top tracks included in active set.
     */
    @ColumnInfo(name = "active_track_threshold")
    val activeTrackThreshold: Int,
    
    /**
     * Months of recent history to include (for tiered imports).
     */
    @ColumnInfo(name = "recent_months_included")
    val recentMonthsIncluded: Int,
    
    // =====================
    // Discovery Results
    // =====================
    
    /**
     * Total scrobbles found on the user's Last.fm account.
     */
    @ColumnInfo(name = "total_scrobbles_found")
    val totalScrobblesFound: Long,
    
    /**
     * Earliest scrobble timestamp (milliseconds).
     */
    @ColumnInfo(name = "earliest_scrobble")
    val earliestScrobble: Long? = null,
    
    /**
     * Latest scrobble timestamp (milliseconds).
     */
    @ColumnInfo(name = "latest_scrobble")
    val latestScrobble: Long? = null,
    
    // =====================
    // Import Progress
    // =====================
    
    /**
     * Import status.
     * Values: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
     */
    @ColumnInfo(name = "status")
    val status: String = "PENDING",
    
    /**
     * Current page being processed (for resume).
     */
    @ColumnInfo(name = "current_page")
    val currentPage: Int = 0,
    
    /**
     * Total pages to process.
     */
    @ColumnInfo(name = "total_pages")
    val totalPages: Int = 0,
    
    /**
     * Scrobbles processed so far.
     */
    @ColumnInfo(name = "scrobbles_processed")
    val scrobblesProcessed: Long = 0,
    
    // =====================
    // Import Results
    // =====================
    
    /**
     * Number of listening events created in active table.
     */
    @ColumnInfo(name = "events_imported")
    val eventsImported: Long = 0,
    
    /**
     * Number of unique tracks created.
     */
    @ColumnInfo(name = "tracks_created")
    val tracksCreated: Long = 0,
    
    /**
     * Number of unique artists created.
     */
    @ColumnInfo(name = "artists_created")
    val artistsCreated: Long = 0,
    
    /**
     * Number of scrobbles sent to archive.
     */
    @ColumnInfo(name = "scrobbles_archived")
    val scrobblesArchived: Long = 0,
    
    /**
     * Number of duplicates skipped (already in Tempo).
     */
    @ColumnInfo(name = "duplicates_skipped")
    val duplicatesSkipped: Long = 0,
    
    // =====================
    // Incremental Sync
    // =====================
    
    /**
     * Timestamp of last scrobble imported (for incremental sync).
     * Unix timestamp in seconds (Last.fm format).
     */
    @ColumnInfo(name = "last_sync_cursor")
    val lastSyncCursor: Long? = null,
    
    /**
     * Timestamp of last successful sync.
     */
    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Long? = null,
    
    // =====================
    // Timestamps
    // =====================
    
    /**
     * When the import was started.
     */
    @ColumnInfo(name = "import_started_at")
    val importStartedAt: Long = System.currentTimeMillis(),
    
    /**
     * When the import was completed (or failed).
     */
    @ColumnInfo(name = "import_completed_at")
    val importCompletedAt: Long? = null,
    
    /**
     * Error message if import failed.
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
) {
    companion object {
        // Import status values
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DISCOVERING = "DISCOVERING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_ARCHIVING = "ARCHIVING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        
        // Import tier values
        const val TIER_LIGHTWEIGHT = "LIGHTWEIGHT"
        const val TIER_BALANCED = "BALANCED"
        const val TIER_COMPREHENSIVE = "COMPREHENSIVE"
        const val TIER_EVERYTHING = "EVERYTHING"
    }
    
    /**
     * Check if import is in a resumable state.
     */
    val isResumable: Boolean 
        get() = status == STATUS_IN_PROGRESS || status == STATUS_ARCHIVING
    
    /**
     * Check if import is complete.
     */
    val isComplete: Boolean 
        get() = status == STATUS_COMPLETED
    
    /**
     * Get progress percentage (0-100).
     */
    val progressPercentage: Int
        get() = if (totalScrobblesFound > 0) {
            ((scrobblesProcessed * 100) / totalScrobblesFound).toInt().coerceIn(0, 100)
        } else 0
}
