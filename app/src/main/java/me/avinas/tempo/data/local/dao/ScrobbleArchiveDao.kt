package me.avinas.tempo.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.ScrobbleArchive

/**
 * DAO for scrobble archive operations.
 * 
 * The archive stores compressed scrobble data for long-tail tracks
 * that don't affect leaderboard rankings but preserve complete history.
 */
@Dao
interface ScrobbleArchiveDao {
    
    companion object {
        // Batch size for bulk operations
        const val BATCH_SIZE = 100
    }
    
    // =====================
    // Basic CRUD Operations
    // =====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(archive: ScrobbleArchive): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(archives: List<ScrobbleArchive>): List<Long>
    
    @Update
    suspend fun update(archive: ScrobbleArchive)
    
    @Delete
    suspend fun delete(archive: ScrobbleArchive)
    
    @Query("DELETE FROM scrobbles_archive WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    @Query("DELETE FROM scrobbles_archive WHERE import_id = :importId")
    suspend fun deleteByImportId(importId: Long): Int
    
    // =====================
    // Query Operations
    // =====================
    
    @Query("SELECT * FROM scrobbles_archive WHERE id = :id")
    suspend fun getById(id: Long): ScrobbleArchive?
    
    @Query("SELECT * FROM scrobbles_archive WHERE track_hash = :trackHash LIMIT 1")
    suspend fun getByTrackHash(trackHash: String): ScrobbleArchive?
    
    @Query("SELECT * FROM scrobbles_archive ORDER BY play_count DESC")
    suspend fun getAll(): List<ScrobbleArchive>
    
    @Query("SELECT * FROM scrobbles_archive ORDER BY play_count DESC")
    fun observeAll(): Flow<List<ScrobbleArchive>>
    
    /**
     * Get total count of archived tracks.
     */
    @Query("SELECT COUNT(*) FROM scrobbles_archive")
    suspend fun getTotalCount(): Int
    
    /**
     * Get total play count across all archived tracks.
     */
    @Query("SELECT COALESCE(SUM(play_count), 0) FROM scrobbles_archive")
    suspend fun getTotalPlayCount(): Long
    
    // =====================
    // Search Operations
    // =====================
    
    /**
     * Search archives by track title.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE track_title LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun searchByTitle(query: String, limit: Int = 50): List<ScrobbleArchive>
    
    /**
     * Search archives by artist name.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE artist_name LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun searchByArtist(query: String, limit: Int = 50): List<ScrobbleArchive>
    
    /**
     * Search archives by title and artist.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE (track_title LIKE '%' || :query || '%' COLLATE NOCASE
               OR artist_name LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 50): List<ScrobbleArchive>
    
    /**
     * Find archive by exact artist and title match.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE LOWER(artist_name) = LOWER(:artist) 
        AND LOWER(track_title) = LOWER(:title)
        LIMIT 1
    """)
    suspend fun findByArtistAndTitle(artist: String, title: String): ScrobbleArchive?
    
    // =====================
    // Date Range Queries
    // =====================
    
    /**
     * Get archives with scrobbles in a date range.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE first_scrobble <= :endTime AND last_scrobble >= :startTime
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getInDateRange(startTime: Long, endTime: Long, limit: Int = 100): List<ScrobbleArchive>
    
    /**
     * Get archives by artist in a date range.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE artist_name_normalized = :artistNormalized
        AND first_scrobble <= :endTime AND last_scrobble >= :startTime
        ORDER BY play_count DESC
    """)
    suspend fun getByArtistInDateRange(
        artistNormalized: String, 
        startTime: Long, 
        endTime: Long
    ): List<ScrobbleArchive>
    
    // =====================
    // Paginated Queries (for History Screen)
    // =====================
    
    /**
     * Get archived tracks paginated by last scrobble time (most recent first).
     * Used for showing archive in chronological order in History screen.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE 
            (:startTime IS NULL OR last_scrobble >= :startTime) AND
            (:endTime IS NULL OR first_scrobble <= :endTime) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                track_title LIKE '%' || :searchQuery || '%' OR 
                artist_name LIKE '%' || :searchQuery || '%' OR 
                album_name LIKE '%' || :searchQuery || '%')
        ORDER BY last_scrobble DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getArchivePaginated(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int,
        offset: Int
    ): List<ScrobbleArchive>
    
    /**
     * Count total archive items matching filters (for pagination info).
     */
    @Query("""
        SELECT COUNT(*) FROM scrobbles_archive 
        WHERE 
            (:startTime IS NULL OR last_scrobble >= :startTime) AND
            (:endTime IS NULL OR first_scrobble <= :endTime) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                track_title LIKE '%' || :searchQuery || '%' OR 
                artist_name LIKE '%' || :searchQuery || '%' OR 
                album_name LIKE '%' || :searchQuery || '%')
    """)
    suspend fun countArchive(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null
    ): Int
    
    // =====================
    // Top Tracks Queries
    // =====================
    
    /**
     * Get top archived tracks by play count.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopByPlayCount(limit: Int = 100): List<ScrobbleArchive>
    
    /**
     * Get top archived tracks by a specific artist.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE artist_name_normalized = :artistNormalized
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopByArtist(artistNormalized: String, limit: Int = 50): List<ScrobbleArchive>
    
    // =====================
    // Statistics Queries
    // =====================
    
    /**
     * Get total scrobbles in date range from archive.
     */
    @Query("""
        SELECT COALESCE(SUM(play_count), 0) FROM scrobbles_archive 
        WHERE first_scrobble <= :endTime AND last_scrobble >= :startTime
    """)
    suspend fun getTotalScrobblesInRange(startTime: Long, endTime: Long): Long
    
    /**
     * Get unique track count in date range from archive.
     */
    @Query("""
        SELECT COUNT(*) FROM scrobbles_archive 
        WHERE first_scrobble <= :endTime AND last_scrobble >= :startTime
    """)
    suspend fun getUniqueTracksInRange(startTime: Long, endTime: Long): Int
    
    /**
     * Get unique artist count in archive.
     */
    @Query("SELECT COUNT(DISTINCT artist_name_normalized) FROM scrobbles_archive")
    suspend fun getUniqueArtistCount(): Int
    
    /**
     * Get storage size estimate (bytes).
     * Note: This is an approximation based on blob sizes.
     */
    @Query("SELECT COALESCE(SUM(LENGTH(timestamps_blob)), 0) FROM scrobbles_archive")
    suspend fun getStorageSizeBytes(): Long
    
    // =====================
    // Promotion Operations
    // =====================
    
    /**
     * Check if a track hash exists in the archive.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM scrobbles_archive WHERE track_hash = :trackHash)")
    suspend fun exists(trackHash: String): Boolean
    
    /**
     * Get archives that could be promoted (high play count in archive).
     * These are tracks that were initially archived but got many plays.
     */
    @Query("""
        SELECT * FROM scrobbles_archive 
        WHERE play_count >= :minPlayCount
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getCandidatesForPromotion(minPlayCount: Int = 10, limit: Int = 50): List<ScrobbleArchive>
    
    // =====================
    // Batch Operations
    // =====================
    
    /**
     * Batch insert with chunking for large imports.
     */
    @Transaction
    suspend fun insertAllBatched(archives: List<ScrobbleArchive>): List<Long> {
        val results = mutableListOf<Long>()
        archives.chunked(BATCH_SIZE).forEach { batch ->
            results.addAll(insertAll(batch))
        }
        return results
    }
    
    /**
     * Upsert operation: update if exists, insert if not.
     * WARNING: This replaces timestamps entirely. For merging, use upsertWithMerge.
     */
    @Transaction
    suspend fun upsert(archive: ScrobbleArchive): Long {
        val existing = getByTrackHash(archive.trackHash)
        return if (existing != null) {
            update(archive.copy(id = existing.id))
            existing.id
        } else {
            insert(archive)
        }
    }
    
    /**
     * Upsert with timestamp merge: if archive exists, merge new timestamps with existing.
     * This is necessary when the same track appears in multiple import batches.
     * 
     * @param archive The archive to upsert
     * @param decompressTimestamps Function to decompress existing timestamp blob
     * @param compressTimestamps Function to compress merged timestamps
     * @return Pair of (archive ID, number of new timestamps added)
     */
    @Transaction
    suspend fun upsertWithMerge(
        archive: ScrobbleArchive,
        decompressTimestamps: (ByteArray) -> List<Long>,
        compressTimestamps: (List<Long>) -> ByteArray
    ): Pair<Long, Int> {
        val existing = getByTrackHash(archive.trackHash)
        
        return if (existing != null) {
            // Merge timestamps
            val existingTimestamps = decompressTimestamps(existing.timestampsBlob).toMutableSet()
            val newTimestamps = decompressTimestamps(archive.timestampsBlob)
            
            val sizeBefore = existingTimestamps.size
            existingTimestamps.addAll(newTimestamps)
            val newCount = existingTimestamps.size - sizeBefore
            
            // Recompress merged timestamps
            val sortedTimestamps = existingTimestamps.sorted()
            val mergedBlob = compressTimestamps(sortedTimestamps)
            
            // Update with merged data
            val merged = existing.copy(
                timestampsBlob = mergedBlob,
                playCount = sortedTimestamps.size,
                firstScrobble = sortedTimestamps.first(),
                lastScrobble = sortedTimestamps.last(),
                updatedAt = System.currentTimeMillis()
            )
            update(merged)
            
            Pair(existing.id, newCount)
        } else {
            val id = insert(archive)
            val newTimestamps = decompressTimestamps(archive.timestampsBlob)
            Pair(id, newTimestamps.size)
        }
    }
}
