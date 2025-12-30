package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.Artist
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    
    // =====================
    // Basic CRUD Operations
    // =====================
    
    @Query("SELECT * FROM artists WHERE id = :id")
    fun getById(id: Long): Flow<Artist?>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getArtistById(id: Long): Artist?
    
    @Query("SELECT * FROM artists")
    fun getAllArtists(): Flow<List<Artist>>
    
    @Query("SELECT * FROM artists")
    suspend fun getAllArtistsSync(): List<Artist>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: Artist): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(artists: List<Artist>): List<Long>

    @Update
    suspend fun update(artist: Artist)
    
    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    // =====================
    // Lookup by Name
    // =====================
    
    /**
     * Find artist by exact name match (case-insensitive).
     */
    @Query("SELECT * FROM artists WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getArtistByName(name: String): Artist?
    
    /**
     * Find artist by normalized name (for deduplication).
     */
    @Query("SELECT * FROM artists WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun getArtistByNormalizedName(normalizedName: String): Artist?
    
    /**
     * Find artist by partial name match (case-insensitive).
     */
    @Query("SELECT * FROM artists WHERE LOWER(name) LIKE '%' || LOWER(:name) || '%' LIMIT 1")
    suspend fun getArtistByNamePartial(name: String): Artist?
    
    /**
     * Search artists by name pattern.
     */
    @Query("SELECT * FROM artists WHERE name LIKE :query ORDER BY name ASC")
    fun search(query: String): Flow<List<Artist>>
    
    /**
     * Search artists by name pattern (sync).
     */
    @Query("SELECT * FROM artists WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%' ORDER BY name ASC")
    suspend fun searchSync(query: String): List<Artist>
    
    // =====================
    // Lookup by External IDs
    // =====================
    
    @Query("SELECT * FROM artists WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun getArtistBySpotifyId(spotifyId: String): Artist?
    
    @Query("SELECT * FROM artists WHERE musicbrainz_id = :musicbrainzId LIMIT 1")
    suspend fun getArtistByMusicBrainzId(musicbrainzId: String): Artist?
    
    // =====================
    // Get or Create Operations
    // =====================
    
    /**
     * Insert artist if not exists, return existing or new ID.
     * Uses normalized name for deduplication.
     */
    @Transaction
    suspend fun getOrCreate(name: String, imageUrl: String? = null): Artist {
        val normalizedName = Artist.normalizeName(name)
        
        // First try to find by normalized name
        getArtistByNormalizedName(normalizedName)?.let { return it }
        
        // If not found, insert new artist
        val newArtist = Artist(
            name = name,
            normalizedName = normalizedName,
            imageUrl = imageUrl,
            genres = emptyList(),
            musicbrainzId = null,
            spotifyId = null
        )
        
        val id = insert(newArtist)
        return if (id > 0) {
            newArtist.copy(id = id)
        } else {
            // Insert failed (race condition), try to get again
            getArtistByNormalizedName(normalizedName) ?: newArtist.copy(id = id)
        }
    }
    
    // =====================
    // Batch Operations
    // =====================
    
    /**
     * Get multiple artists by IDs.
     */
    @Query("SELECT * FROM artists WHERE id IN (:ids)")
    suspend fun getArtistsByIds(ids: List<Long>): List<Artist>
    
    /**
     * Get artists with images.
     */
    @Query("SELECT * FROM artists WHERE image_url IS NOT NULL AND image_url != '' ORDER BY name ASC")
    suspend fun getArtistsWithImages(): List<Artist>
    
    /**
     * Get artists without images (for enrichment).
     */
    @Query("SELECT * FROM artists WHERE image_url IS NULL OR image_url = '' LIMIT :limit")
    suspend fun getArtistsWithoutImages(limit: Int = 50): List<Artist>
    
    // =====================
    // Update Operations
    // =====================
    
    /**
     * Update artist image URL.
     */
    @Query("UPDATE artists SET image_url = :imageUrl WHERE id = :artistId")
    suspend fun updateImageUrl(artistId: Long, imageUrl: String?)
    
    /**
     * Update artist Spotify ID.
     */
    @Query("UPDATE artists SET spotify_id = :spotifyId WHERE id = :artistId")
    suspend fun updateSpotifyId(artistId: Long, spotifyId: String?)
    
    /**
     * Update artist MusicBrainz ID.
     */
    @Query("UPDATE artists SET musicbrainz_id = :musicbrainzId WHERE id = :artistId")
    suspend fun updateMusicBrainzId(artistId: Long, musicbrainzId: String?)
    
    /**
     * Update artist country.
     */
    @Query("UPDATE artists SET country = :country WHERE id = :artistId")
    suspend fun updateCountry(artistId: Long, country: String?)
    
    /**
     * Update artist genres.
     */
    @Query("UPDATE artists SET genres = :genres WHERE id = :artistId")
    suspend fun updateGenres(artistId: Long, genres: List<String>)

    /**
     * Update artist image URL by Spotify ID.
     */
    @Query("UPDATE artists SET image_url = :imageUrl WHERE spotify_id = :spotifyId")
    suspend fun updateImageUrlBySpotifyId(spotifyId: String, imageUrl: String)

    /**
     * Update artist Spotify ID by name (case-insensitive).
     */
    @Query("UPDATE artists SET spotify_id = :spotifyId WHERE LOWER(name) = LOWER(:name) AND (spotify_id IS NULL OR spotify_id = '')")
    suspend fun updateSpotifyIdByName(name: String, spotifyId: String)
    
    /**
     * Clear image URLs for artists that might have mismatched images.
     * This can be used as a cleanup when migrating to fixed image fetching logic.
     * Artists will get new images the next time they are enriched.
     */
    @Query("UPDATE artists SET image_url = NULL WHERE spotify_id IS NULL OR spotify_id = ''")
    suspend fun clearImagesForArtistsWithoutSpotifyId()
    
    /**
     * Get artists that need image re-fetch (have image but no spotify_id).
     * These artists may have incorrect images from the old buggy logic.
     */
    @Query("SELECT * FROM artists WHERE image_url IS NOT NULL AND image_url != '' AND (spotify_id IS NULL OR spotify_id = '') LIMIT :limit")
    suspend fun getArtistsWithImagesButNoSpotifyId(limit: Int = 100): List<Artist>
}