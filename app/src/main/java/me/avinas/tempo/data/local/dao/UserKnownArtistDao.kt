package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.UserKnownArtist

/**
 * DAO for managing user-defined known artist names.
 * 
 * These are artist/band names that the user has confirmed should not be split
 * by the ArtistParser. They supplement the hardcoded KNOWN_COMPLEX_BANDS list.
 */
@Dao
interface UserKnownArtistDao {

    /**
     * Insert a new known artist. Uses IGNORE to silently skip duplicates
     * (based on unique normalized_name index).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(knownArtist: UserKnownArtist): Long

    /**
     * Check if a normalized name is already registered as a known artist.
     */
    @Query("SELECT * FROM user_known_artists WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): UserKnownArtist?

    /**
     * Get all user-known artists (for display in settings/management).
     */
    @Query("SELECT * FROM user_known_artists ORDER BY name ASC")
    suspend fun getAll(): List<UserKnownArtist>

    /**
     * Observe all user-known artists (reactive).
     */
    @Query("SELECT * FROM user_known_artists ORDER BY name ASC")
    fun observeAll(): Flow<List<UserKnownArtist>>

    /**
     * Get all normalized names for fast parser lookup.
     * Called once at startup to populate ArtistParser's user known bands set.
     */
    @Query("SELECT normalized_name FROM user_known_artists")
    suspend fun getAllNormalizedNames(): List<String>

    /**
     * Delete a known artist by ID.
     */
    @Query("DELETE FROM user_known_artists WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete a known artist by normalized name.
     */
    @Query("DELETE FROM user_known_artists WHERE normalized_name = :normalizedName")
    suspend fun deleteByNormalizedName(normalizedName: String)

    /**
     * Get the count of user-known artists.
     */
    @Query("SELECT COUNT(*) FROM user_known_artists")
    suspend fun getCount(): Int
}
