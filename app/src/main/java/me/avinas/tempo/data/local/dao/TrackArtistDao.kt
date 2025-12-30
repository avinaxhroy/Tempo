package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistRole
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackArtist
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing track-artist relationships.
 * 
 * Optimized for batch operations with SQLite's 999 variable limit in mind.
 */
@Dao
interface TrackArtistDao {
    
    companion object {
        // SQLite has a limit of 999 variables per query
        // TrackArtist has 4 columns, so max batch size is ~249
        const val BATCH_SIZE = 200
    }
    
    // =====================
    // Insert Operations
    // =====================
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(trackArtist: TrackArtist): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(trackArtists: List<TrackArtist>)
    
    /**
     * Batch insert with chunking for large lists.
     * SQLite has a limit of 999 variables per statement.
     */
    @Transaction
    suspend fun insertAllBatched(trackArtists: List<TrackArtist>) {
        trackArtists.chunked(BATCH_SIZE).forEach { batch ->
            insertAll(batch)
        }
    }
    
    @Transaction
    suspend fun linkTrackToArtist(trackId: Long, artistId: Long, role: ArtistRole, order: Int = 0) {
        insert(TrackArtist(trackId, artistId, role, order))
    }
    
    @Transaction
    suspend fun linkTrackToArtists(trackId: Long, artistIds: List<Long>, role: ArtistRole) {
        val links = artistIds.mapIndexed { index, artistId ->
            TrackArtist(trackId, artistId, role, index)
        }
        insertAllBatched(links)
    }
    
    // =====================
    // Delete Operations
    // =====================
    
    @Delete
    suspend fun delete(trackArtist: TrackArtist)
    
    @Query("DELETE FROM track_artists WHERE track_id = :trackId")
    suspend fun deleteAllForTrack(trackId: Long)
    
    @Query("DELETE FROM track_artists WHERE track_id = :trackId AND role = :role")
    suspend fun deleteByTrackAndRole(trackId: Long, role: ArtistRole)
    
    // =====================
    // Query Operations - Get Artists for Track
    // =====================
    
    /**
     * Get all artists for a track, ordered by credit order.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN track_artists ta ON a.id = ta.artist_id
        WHERE ta.track_id = :trackId
        ORDER BY ta.credit_order ASC
    """)
    suspend fun getArtistsForTrack(trackId: Long): List<Artist>
    
    /**
     * Get all artists for a track as a Flow.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN track_artists ta ON a.id = ta.artist_id
        WHERE ta.track_id = :trackId
        ORDER BY ta.credit_order ASC
    """)
    fun observeArtistsForTrack(trackId: Long): Flow<List<Artist>>
    
    /**
     * Get primary artists for a track.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN track_artists ta ON a.id = ta.artist_id
        WHERE ta.track_id = :trackId AND ta.role = 'PRIMARY'
        ORDER BY ta.credit_order ASC
    """)
    suspend fun getPrimaryArtistsForTrack(trackId: Long): List<Artist>
    
    /**
     * Get featured artists for a track.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN track_artists ta ON a.id = ta.artist_id
        WHERE ta.track_id = :trackId AND ta.role = 'FEATURED'
        ORDER BY ta.credit_order ASC
    """)
    suspend fun getFeaturedArtistsForTrack(trackId: Long): List<Artist>
    
    /**
     * Get the first primary artist for a track.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN track_artists ta ON a.id = ta.artist_id
        WHERE ta.track_id = :trackId AND ta.role = 'PRIMARY'
        ORDER BY ta.credit_order ASC
        LIMIT 1
    """)
    suspend fun getMainArtistForTrack(trackId: Long): Artist?
    
    // =====================
    // Query Operations - Get Tracks for Artist
    // =====================
    
    /**
     * Get all tracks for an artist.
     */
    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN track_artists ta ON t.id = ta.track_id
        WHERE ta.artist_id = :artistId
        ORDER BY t.title ASC
    """)
    suspend fun getTracksForArtist(artistId: Long): List<Track>
    
    /**
     * Get all tracks for an artist as a Flow.
     */
    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN track_artists ta ON t.id = ta.track_id
        WHERE ta.artist_id = :artistId
        ORDER BY t.title ASC
    """)
    fun observeTracksForArtist(artistId: Long): Flow<List<Track>>
    
    /**
     * Get tracks where artist is primary.
     */
    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN track_artists ta ON t.id = ta.track_id
        WHERE ta.artist_id = :artistId AND ta.role = 'PRIMARY'
        ORDER BY t.title ASC
    """)
    suspend fun getTracksWhereArtistIsPrimary(artistId: Long): List<Track>
    
    /**
     * Get tracks where artist is featured.
     */
    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN track_artists ta ON t.id = ta.track_id
        WHERE ta.artist_id = :artistId AND ta.role = 'FEATURED'
        ORDER BY t.title ASC
    """)
    suspend fun getTracksWhereArtistIsFeatured(artistId: Long): List<Track>
    
    // =====================
    // Query Operations - Statistics
    // =====================
    
    /**
     * Get track count for an artist.
     */
    @Query("""
        SELECT COUNT(DISTINCT ta.track_id) 
        FROM track_artists ta 
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getTrackCountForArtist(artistId: Long): Int
    
    /**
     * Get track count where artist is primary.
     */
    @Query("""
        SELECT COUNT(DISTINCT ta.track_id) 
        FROM track_artists ta 
        WHERE ta.artist_id = :artistId AND ta.role = 'PRIMARY'
    """)
    suspend fun getPrimaryTrackCountForArtist(artistId: Long): Int
    
    /**
     * Check if track-artist relationship exists.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM track_artists 
            WHERE track_id = :trackId AND artist_id = :artistId
        )
    """)
    suspend fun hasRelationship(trackId: Long, artistId: Long): Boolean
    
    /**
     * Get all track-artist relationships for a track.
     */
    @Query("SELECT * FROM track_artists WHERE track_id = :trackId ORDER BY credit_order ASC")
    suspend fun getRelationshipsForTrack(trackId: Long): List<TrackArtist>
    
    /**
     * Get all track-artist relationships for an artist.
     */
    @Query("SELECT * FROM track_artists WHERE artist_id = :artistId")
    suspend fun getRelationshipsForArtist(artistId: Long): List<TrackArtist>
    
    /**
     * Get all track-artist relationships for export.
     */
    @Query("SELECT * FROM track_artists")
    suspend fun getAllSync(): List<TrackArtist>
}
