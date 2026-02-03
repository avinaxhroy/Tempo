package me.avinas.tempo.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.LastFmImportMetadata

/**
 * DAO for Last.fm import metadata operations.
 */
@Dao
interface LastFmImportMetadataDao {
    
    // =====================
    // Basic CRUD Operations
    // =====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: LastFmImportMetadata): Long
    
    @Update
    suspend fun update(metadata: LastFmImportMetadata)
    
    @Delete
    suspend fun delete(metadata: LastFmImportMetadata)
    
    @Query("DELETE FROM lastfm_import_metadata WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    // =====================
    // Query Operations
    // =====================
    
    @Query("SELECT * FROM lastfm_import_metadata WHERE id = :id")
    suspend fun getById(id: Long): LastFmImportMetadata?
    
    @Query("SELECT * FROM lastfm_import_metadata WHERE id = :id")
    fun observeById(id: Long): Flow<LastFmImportMetadata?>
    
    @Query("SELECT * FROM lastfm_import_metadata ORDER BY import_started_at DESC")
    suspend fun getAll(): List<LastFmImportMetadata>
    
    @Query("SELECT * FROM lastfm_import_metadata ORDER BY import_started_at DESC")
    fun observeAll(): Flow<List<LastFmImportMetadata>>
    
    /**
     * Get the most recent import for a username.
     */
    @Query("""
        SELECT * FROM lastfm_import_metadata 
        WHERE lastfm_username = :username 
        ORDER BY import_started_at DESC 
        LIMIT 1
    """)
    suspend fun getLatestForUsername(username: String): LastFmImportMetadata?
    
    /**
     * Get the most recent completed import for a username (for sync cursor).
     */
    @Query("""
        SELECT * FROM lastfm_import_metadata 
        WHERE lastfm_username = :username 
        AND status = 'COMPLETED'
        ORDER BY import_completed_at DESC 
        LIMIT 1
    """)
    suspend fun getLatestCompletedForUsername(username: String): LastFmImportMetadata?
    
    /**
     * Get the most recent completed import (any username, for incremental sync).
     */
    @Query("""
        SELECT * FROM lastfm_import_metadata 
        WHERE status = 'COMPLETED'
        ORDER BY import_completed_at DESC 
        LIMIT 1
    """)
    suspend fun getLatestCompleted(): LastFmImportMetadata?
    
    /**
     * Check if there's an in-progress import.
     */
    @Query("""
        SELECT * FROM lastfm_import_metadata 
        WHERE status IN ('PENDING', 'DISCOVERING', 'IN_PROGRESS', 'ARCHIVING')
        ORDER BY import_started_at DESC 
        LIMIT 1
    """)
    suspend fun getActiveImport(): LastFmImportMetadata?
    
    @Query("""
        SELECT * FROM lastfm_import_metadata 
        WHERE status IN ('PENDING', 'DISCOVERING', 'IN_PROGRESS', 'ARCHIVING')
        ORDER BY import_started_at DESC 
        LIMIT 1
    """)
    fun observeActiveImport(): Flow<LastFmImportMetadata?>
    
    /**
     * Check if any import has been completed for a username.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM lastfm_import_metadata 
            WHERE lastfm_username = :username 
            AND status = 'COMPLETED'
        )
    """)
    suspend fun hasCompletedImport(username: String): Boolean
    
    // =====================
    // Progress Updates
    // =====================
    
    /**
     * Update import status.
     */
    @Query("UPDATE lastfm_import_metadata SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
    
    /**
     * Update import progress.
     */
    @Query("""
        UPDATE lastfm_import_metadata 
        SET current_page = :currentPage, 
            scrobbles_processed = :scrobblesProcessed 
        WHERE id = :id
    """)
    suspend fun updateProgress(id: Long, currentPage: Int, scrobblesProcessed: Long)
    
    /**
     * Update import results.
     */
    @Query("""
        UPDATE lastfm_import_metadata 
        SET events_imported = :eventsImported,
            tracks_created = :tracksCreated,
            artists_created = :artistsCreated,
            scrobbles_archived = :scrobblesArchived,
            duplicates_skipped = :duplicatesSkipped
        WHERE id = :id
    """)
    suspend fun updateResults(
        id: Long, 
        eventsImported: Long, 
        tracksCreated: Long, 
        artistsCreated: Long,
        scrobblesArchived: Long,
        duplicatesSkipped: Long
    )
    
    /**
     * Mark import as completed.
     */
    @Query("""
        UPDATE lastfm_import_metadata 
        SET status = 'COMPLETED',
            import_completed_at = :completedAt,
            last_sync_cursor = :syncCursor,
            last_sync_timestamp = :completedAt
        WHERE id = :id
    """)
    suspend fun markCompleted(id: Long, completedAt: Long, syncCursor: Long?)
    
    /**
     * Mark import as failed.
     */
    @Query("""
        UPDATE lastfm_import_metadata 
        SET status = 'FAILED',
            import_completed_at = :failedAt,
            error_message = :errorMessage
        WHERE id = :id
    """)
    suspend fun markFailed(id: Long, failedAt: Long, errorMessage: String)
    
    /**
     * Update sync cursor for incremental imports.
     */
    @Query("""
        UPDATE lastfm_import_metadata 
        SET last_sync_cursor = :cursor,
            last_sync_timestamp = :timestamp
        WHERE id = :id
    """)
    suspend fun updateSyncCursor(id: Long, cursor: Long, timestamp: Long)
}
