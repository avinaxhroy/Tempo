package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE id = :id")
    fun getById(id: Long): Flow<Album?>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: Long): Album?

    @Query("""
        SELECT a.* FROM albums a 
        INNER JOIN artists ar ON a.artist_id = ar.id 
        WHERE a.title = :title AND ar.name = :artistName 
        LIMIT 1
    """)
    suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): Album?
    
    @Query("SELECT * FROM albums WHERE musicbrainz_id = :mbid LIMIT 1")
    suspend fun getAlbumByMusicBrainzId(mbid: String): Album?

    @Query("SELECT * FROM albums WHERE artist_id = :artistId")
    fun albumsForArtist(artistId: Long): Flow<List<Album>>
    
    @Query("SELECT * FROM albums WHERE artist_id = :artistId ORDER BY release_year DESC")
    suspend fun getAlbumsForArtistSync(artistId: Long): List<Album>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: Album): Long

    @Update
    suspend fun update(album: Album)
    
    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumCount(): Int
    
    /**
     * Get count of actual albums (excluding singles).
     * Includes: Album, EP, Compilation, and items with unknown release_type.
     */
    @Query("SELECT COUNT(*) FROM albums WHERE release_type IS NULL OR LOWER(release_type) != 'single'")
    suspend fun getAlbumCountExcludingSingles(): Int
    
    /**
     * Get albums for an artist excluding singles.
     */
    @Query("SELECT * FROM albums WHERE artist_id = :artistId AND (release_type IS NULL OR LOWER(release_type) != 'single') ORDER BY release_year DESC")
    fun albumsForArtistExcludingSingles(artistId: Long): Flow<List<Album>>
    
    /**
     * Get all albums for export.
     */
    @Query("SELECT * FROM albums")
    suspend fun getAllSync(): List<Album>
}
