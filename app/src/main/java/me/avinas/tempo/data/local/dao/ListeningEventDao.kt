package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ListeningEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningEventDao {
    
    companion object {
        // SQLite variable limit is 999, ListeningEvent has ~12 columns
        const val BATCH_SIZE = 80
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ListeningEvent): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ListeningEvent>): List<Long>
    
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

    @Delete
    suspend fun delete(event: ListeningEvent)
    
    @Query("DELETE FROM listening_events WHERE id = :id")
    suspend fun deleteById(id: Long)
    
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
}
