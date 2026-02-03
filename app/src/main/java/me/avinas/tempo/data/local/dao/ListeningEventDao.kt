package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ListeningEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningEventDao {
    
    companion object {
        // SQLite variable limit is 999, ListeningEvent has ~12 columns
        const val BATCH_SIZE = 80
        
        // Timestamp tolerance for deduplication (5 seconds)
        // Two events within 5 seconds for the same track are considered duplicates
        const val DUPLICATE_TOLERANCE_MS = 5000L
    }
    
    @Query("SELECT * FROM listening_events WHERE id = :id")
    fun getById(id: Long): Flow<ListeningEvent?>

    @Query("SELECT * FROM listening_events WHERE track_id = :trackId ORDER BY timestamp DESC")
    fun eventsForTrack(trackId: Long): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE track_id = :trackId ORDER BY timestamp DESC")
    suspend fun getEventsForTrack(trackId: Long): List<ListeningEvent>

    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC")
    fun all(): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentEvents(limit: Int): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun eventsInRange(startTime: Long, endTime: Long): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent>

    /**
     * Get only timestamps and durations for session calculation (memory efficient).
     */
    @Query("SELECT timestamp, playDuration FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getSessionPointsInRange(startTime: Long, endTime: Long): List<me.avinas.tempo.data.stats.SessionPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ListeningEvent): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ListeningEvent>): List<Long>
    
    /**
     * Check if an event already exists for this track within the tolerance window.
     * Used for deduplication during imports.
     */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE track_id = :trackId 
        AND timestamp BETWEEN :timestampMin AND :timestampMax
    """)
    suspend fun countEventsNearTimestamp(trackId: Long, timestampMin: Long, timestampMax: Long): Int
    
    /**
     * Get all existing timestamps for a set of tracks (for batch deduplication).
     * Returns pairs of (track_id, timestamp) for efficient lookup.
     */
    @Query("""
        SELECT track_id, timestamp FROM listening_events 
        WHERE track_id IN (:trackIds)
        ORDER BY track_id, timestamp
    """)
    suspend fun getTimestampsForTracks(trackIds: List<Long>): List<TrackTimestamp>
    
    /**
     * Batch insert with deduplication.
     * Filters out events that would duplicate existing events (same track within tolerance window).
     */
    @Transaction
    suspend fun insertAllBatchedWithDedup(events: List<ListeningEvent>): InsertResult {
        if (events.isEmpty()) return InsertResult(0, 0)
        
        // Get all track IDs from events to insert
        val trackIds = events.map { it.track_id }.distinct()
        
        // Fetch existing timestamps for these tracks
        val existingTimestamps = getTimestampsForTracks(trackIds)
        
        // Build a lookup map: trackId -> set of existing timestamps
        val existingMap = mutableMapOf<Long, MutableSet<Long>>()
        existingTimestamps.forEach { tt ->
            existingMap.getOrPut(tt.track_id) { mutableSetOf() }.add(tt.timestamp)
        }
        
        // Filter out duplicates
        val eventsToInsert = events.filter { event ->
            val existingForTrack = existingMap[event.track_id] ?: return@filter true
            // Check if any existing timestamp is within tolerance
            val isDuplicate = existingForTrack.any { existingTs ->
                kotlin.math.abs(existingTs - event.timestamp) <= DUPLICATE_TOLERANCE_MS
            }
            !isDuplicate
        }
        
        val skipped = events.size - eventsToInsert.size
        
        // Insert non-duplicates
        val results = mutableListOf<Long>()
        eventsToInsert.chunked(BATCH_SIZE).forEach { batch ->
            results.addAll(insertAll(batch))
        }
        
        return InsertResult(inserted = results.size, skipped = skipped)
    }
    
    /**
     * Batch insert with chunking for large imports.
     */
    @Transaction
    suspend fun insertAllBatched(events: List<ListeningEvent>): List<Long> {
        val results = mutableListOf<Long>()
        events.chunked(BATCH_SIZE).forEach { batch ->
            results.addAll(insertAll(batch))
        }
        return results
    }
    
    /**
     * Result of a deduplicating insert operation.
     */
    data class InsertResult(
        val inserted: Int,
        val skipped: Int
    ) {
        val total: Int get() = inserted + skipped
    }
    
    /**
     * Simple data class for timestamp lookup.
     */
    data class TrackTimestamp(
        val track_id: Long,
        val timestamp: Long
    )

    @Delete
    suspend fun delete(event: ListeningEvent)
    
    @Query("DELETE FROM listening_events WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    @Query("DELETE FROM listening_events WHERE track_id = :trackId")
    suspend fun deleteByTrackId(trackId: Long)
    
    /**
     * Move events from one track to another (used during merge).
     */
    @Query("UPDATE listening_events SET track_id = :targetTrackId WHERE track_id = :sourceTrackId")
    suspend fun reattributeEvents(sourceTrackId: Long, targetTrackId: Long)
    
    // =====================
    // Enhanced Engagement Queries
    // =====================
    
    /**
     * Get total skip count for a track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND was_skipped = 1")
    suspend fun getSkipCountForTrack(trackId: Long): Int
    
    /**
     * Get total replay count for a track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND is_replay = 1")
    suspend fun getReplayCountForTrack(trackId: Long): Int
    
    /**
     * Get average completion percentage for a track.
     */
    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE track_id = :trackId")
    suspend fun getAverageCompletionForTrack(trackId: Long): Float?
    
    /**
     * Get full play count for a track (completion >= 80%).
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND completionPercentage >= 80")
    suspend fun getFullPlayCountForTrack(trackId: Long): Int
    
    /**
     * Get last play timestamp for a track.
     */
    @Query("SELECT MAX(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getLastPlayTimestampForTrack(trackId: Long): Long?
    
    /**
     * Get first play timestamp for a track.
     */
    @Query("SELECT MIN(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getFirstPlayTimestampForTrack(trackId: Long): Long?
    
    /**
     * Get total play count for a specific track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId")
    suspend fun countByTrackId(trackId: Long): Int
    
    /**
     * Check if a track was recently played (within specified milliseconds).
     * Used for replay detection.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM listening_events 
            WHERE track_id = :trackId 
            AND timestamp >= :sinceTimestamp
        )
    """)
    suspend fun wasRecentlyPlayed(trackId: Long, sinceTimestamp: Long): Boolean
    
    /**
     * Get events by session ID.
     */
    @Query("SELECT * FROM listening_events WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsBySessionId(sessionId: String): List<ListeningEvent>
    
    /**
     * Get total listening time in a time range.
     */
    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long
    
    /**
     * Get skip rate for a time range.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN was_skipped = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getSkipRate(startTime: Long, endTime: Long): Float?
    
    /**
     * Get average completion for a time range.
     */
    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getAverageCompletion(startTime: Long, endTime: Long): Float?
    
    /**
     * Get all listening events for export.
     */
    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC")
    suspend fun getAllEventsSync(): List<ListeningEvent>
    
    /**
     * Delete all listening events for tracks belonging to a specific artist.
     * Returns the number of deleted rows.
     */
    @Query("""
        DELETE FROM listening_events 
        WHERE track_id IN (SELECT id FROM tracks WHERE LOWER(artist) = LOWER(:artistName))
    """)
    suspend fun deleteByArtist(artistName: String): Int

    /**
     * Get the timestamp of the very first listening event (earliest data point).
     */
    @Query("SELECT MIN(timestamp) FROM listening_events")
    suspend fun getEarliestEventTimestamp(): Long?
    
    /**
     * Get total listening time excluding imported events.
     * Only counts events from actual app usage (real-time tracking).
     */
    @Query("""
        SELECT COALESCE(SUM(playDuration), 0) FROM listening_events 
        WHERE source NOT LIKE '%import%'
    """)
    suspend fun getRealListeningTimeMs(): Long
    
    /**
     * Get total play count excluding imported events.
     * Only counts events from actual app usage (real-time tracking).
     */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE source NOT LIKE '%import%'
    """)
    suspend fun getRealPlayCount(): Int
}
