package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    
    // Basic CRUD Operations
    @Query("SELECT * FROM artists WHERE id = :id")
    fun getById(id: Long): Flow<Artist?>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getArtistById(id: Long): Artist?
    
    @Query("SELECT * FROM artists")
    fun getAllArtists(): Flow<List<Artist>>
    
    @Query("SELECT * FROM artists")
    suspend fun getAllArtistsSync(): List<Artist>

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun getCount(): Int

    @Query("SELECT image_url FROM artists WHERE image_url LIKE 'file://%'")
    suspend fun getLocalImageUrls(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: Artist): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(artists: List<Artist>): List<Long>

    /**
     * Replace a whole artist row, but keep a portrait the user picked by hand.
     *
     * Most callers fetch an artist, change one metadata field and write the row
     * back. If the user chose artwork in between, writing the fetched row would
     * undo that choice, so the manual image is carried over from the stored row.
     */
    @Transaction
    suspend fun update(artist: Artist) {
        val stored = getArtistById(artist.id)
        val resolved = if (stored?.imageArtSource == AlbumArtSource.USER_SELECTED) {
            artist.copy(
                imageUrl = stored.imageUrl,
                imageArtSource = AlbumArtSource.USER_SELECTED,
            )
        } else {
            artist
        }
        updateRow(resolved)
    }

    @Update
    suspend fun updateRow(artist: Artist)
    
    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    // Lookup by Name
    @Query("SELECT * FROM artists WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getArtistByName(name: String): Artist?
    
    @Query("SELECT * FROM artists WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun getArtistByNormalizedName(normalizedName: String): Artist?
    
    @Query("SELECT * FROM artists WHERE normalized_name IN (:normalizedNames)")
    suspend fun getArtistsByNormalizedNames(normalizedNames: List<String>): List<Artist>
    
    @Query("SELECT * FROM artists WHERE LOWER(name) LIKE '%' || LOWER(:name) || '%' LIMIT 1")
    suspend fun getArtistByNamePartial(name: String): Artist?
    
    @Query("SELECT * FROM artists WHERE name LIKE :query ORDER BY name ASC")
    fun search(query: String): Flow<List<Artist>>
    
    @Query("""
        SELECT a.id, a.name, a.normalized_name,
               CASE
                   -- A portrait the user picked by hand wins over every fallback,
                   -- including the enrichment and track-artwork columns below.
                   WHEN a.image_art_source = 'USER_SELECTED' THEN NULLIF(a.image_url, '')
                   ELSE COALESCE(
                       NULLIF(a.image_url, ''),
                       (SELECT COALESCE(
                           NULLIF(em.spotify_artist_image_url, ''),
                           NULLIF(em.itunes_artist_image_url, ''),
                           NULLIF(em.lastfm_artist_image_url, ''),
                           NULLIF(em.deezer_artist_image_url, '')
                        )
                        FROM enriched_metadata em
                        INNER JOIN track_artists ta ON ta.track_id = em.track_id
                        WHERE ta.artist_id = a.id
                          AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                               OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
                        LIMIT 1),
                       (SELECT COALESCE(NULLIF(t.album_art_url, ''), NULLIF(em2.album_art_url, ''))
                        FROM tracks t
                        LEFT JOIN enriched_metadata em2 ON t.id = em2.track_id
                        WHERE (t.primary_artist_id = a.id OR t.artist = a.name)
                          AND (t.album_art_url IS NOT NULL OR em2.album_art_url IS NOT NULL)
                        LIMIT 1)
                   )
               END as image_url,
               a.genres, a.musicbrainz_id, a.spotify_id, a.country, a.artist_type,
               a.image_art_source
        FROM artists a
        WHERE LOWER(a.name) LIKE '%' || LOWER(:query) || '%'
        ORDER BY a.name ASC
        LIMIT 50
    """)
    suspend fun searchSync(query: String): List<Artist>
    
    // Lookup by External IDs
    @Query("SELECT * FROM artists WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun getArtistBySpotifyId(spotifyId: String): Artist?
    
    @Query("SELECT * FROM artists WHERE musicbrainz_id = :musicbrainzId LIMIT 1")
    suspend fun getArtistByMusicBrainzId(musicbrainzId: String): Artist?
    
    // Get or Create Operations
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
    
    // Batch Operations
    @Query("SELECT * FROM artists WHERE id IN (:ids)")
    suspend fun getArtistsByIds(ids: List<Long>): List<Artist>
    
    @Query("SELECT * FROM artists WHERE image_url IS NOT NULL AND image_url != '' ORDER BY name ASC")
    suspend fun getArtistsWithImages(): List<Artist>
    
    @Query("SELECT * FROM artists WHERE image_url IS NULL OR image_url = '' LIMIT :limit")
    suspend fun getArtistsWithoutImages(limit: Int = 50): List<Artist>
    
    // Update Operations
    
    /**
     * Update artist image URL from an automatic source.
     *
     * Artwork the user picked by hand is locked: when `image_art_source` is
     * `USER_SELECTED` the row is left untouched, so an enrichment pass or a
     * refresh can never replace a portrait the user chose on purpose.
     * Use [setUserSelectedImageUrl] to write an explicit choice.
     */
    @Query("UPDATE artists SET image_url = :imageUrl WHERE id = :artistId AND image_art_source != 'USER_SELECTED'")
    suspend fun updateImageUrl(artistId: Long, imageUrl: String?)

    /**
     * Current artwork source for an artist, used to decide whether the
     * automatic writers below are allowed to touch the row.
     */
    @Query("SELECT image_art_source FROM artists WHERE id = :artistId")
    suspend fun getImageArtSource(artistId: Long): AlbumArtSource?

    /**
     * Write artwork the user picked explicitly (from a provider or the gallery)
     * and mark it as manual so nothing automatic overwrites it.
     */
    @Query("UPDATE artists SET image_url = :imageUrl, image_art_source = 'USER_SELECTED' WHERE id = :artistId")
    suspend fun setUserSelectedImageUrl(artistId: Long, imageUrl: String)

    /**
     * Release the manual lock and clear the portrait so the next enrichment
     * pass picks artwork again.
     *
     * [AlbumArtSource.USER_RESET] is written instead of `NONE` on purpose: it is
     * a tombstone, so an in-flight enrichment that still holds the old manual
     * URL cannot put the portrait back.
     */
    @Query("UPDATE artists SET image_url = NULL, image_art_source = 'USER_RESET' WHERE id = :artistId")
    suspend fun resetImageToAutomatic(artistId: Long)

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
     * IDs of artist rows whose genres column still holds the legacy JSON-array
     * format (e.g. `["jazz", "cool jazz"]`) instead of the `|||`-delimited format.
     * Used by the one-time list column repair.
     */
    @Query("SELECT id FROM artists WHERE genres LIKE '[%' AND genres LIKE '%]'")
    suspend fun getArtistIdsWithLegacyGenreFormat(): List<Long>

    /**
     * Update artist image URL by Spotify ID.
     *
     * Same rule as [updateImageUrl]: a manually chosen portrait is never
     * replaced by an automatic source.
     */
    @Query("UPDATE artists SET image_url = :imageUrl WHERE spotify_id = :spotifyId AND image_art_source != 'USER_SELECTED'")
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