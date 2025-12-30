package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    
    // =====================
    // Basic CRUD Operations
    // =====================
    
    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getById(id: Long): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun findBySpotifyId(spotifyId: String): Track?
    
    @Query("SELECT * FROM tracks WHERE musicbrainz_id = :musicbrainzId LIMIT 1")
    suspend fun findByMusicBrainzId(musicbrainzId: String): Track?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)
    
    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun all(): Flow<List<Track>>
    
    @Query("SELECT * FROM tracks ORDER BY title ASC")
    suspend fun getAllSync(): List<Track>
    
    // =====================
    // Find by Title and Artist
    // =====================
    
    /**
     * Find track by exact title and artist match.
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE LOWER(title) = LOWER(:title) 
        AND LOWER(artist) = LOWER(:artist) 
        LIMIT 1
    """)
    suspend fun findByTitleAndArtist(title: String, artist: String): Track?
    
    /**
     * Find track by title with fuzzy artist match.
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE LOWER(title) = LOWER(:title) 
        AND (
            LOWER(artist) = LOWER(:artist) 
            OR LOWER(artist) LIKE '%' || LOWER(:artist) || '%'
            OR LOWER(:artist) LIKE '%' || LOWER(artist) || '%'
        )
        LIMIT 1
    """)
    suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track?
    
    // =====================
    // Queries by Artist ID
    // =====================
    
    /**
     * Get tracks by primary artist ID.
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id = :artistId ORDER BY title ASC")
    suspend fun getTracksByPrimaryArtist(artistId: Long): List<Track>
    
    /**
     * Get tracks by primary artist ID as Flow.
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id = :artistId ORDER BY title ASC")
    fun observeTracksByPrimaryArtist(artistId: Long): Flow<List<Track>>
    
    /**
     * Get track count by primary artist.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE primary_artist_id = :artistId")
    suspend fun getTrackCountByPrimaryArtist(artistId: Long): Int
    
    // =====================
    // Queries for Linking
    // =====================
    
    /**
     * Get tracks without primary artist ID (need migration).
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id IS NULL LIMIT :limit")
    suspend fun getTracksWithoutPrimaryArtist(limit: Int = 100): List<Track>
    
    /**
     * Update primary artist ID for a track.
     */
    @Query("UPDATE tracks SET primary_artist_id = :artistId WHERE id = :trackId")
    suspend fun updatePrimaryArtistId(trackId: Long, artistId: Long?)
    
    /**
     * Get the primary artist for a track.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN tracks t ON t.primary_artist_id = a.id
        WHERE t.id = :trackId
    """)
    suspend fun getPrimaryArtistForTrack(trackId: Long): Artist?
    
    // =====================
    // Search Operations
    // =====================
    
    /**
     * Search tracks by title.
     */
    @Query("SELECT * FROM tracks WHERE LOWER(title) LIKE '%' || LOWER(:query) || '%' ORDER BY title ASC")
    suspend fun searchByTitle(query: String): List<Track>
    
    /**
     * Search tracks by artist name.
     */
    @Query("SELECT * FROM tracks WHERE LOWER(artist) LIKE '%' || LOWER(:query) || '%' ORDER BY title ASC")
    suspend fun searchByArtist(query: String): List<Track>
}

