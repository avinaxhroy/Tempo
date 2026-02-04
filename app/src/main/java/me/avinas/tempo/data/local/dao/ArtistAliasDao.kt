package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ArtistAlias
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing artist aliases.
 * 
 * Artist aliases are used to redirect plays from merged artist names
 * to their canonical artist.
 */
@Dao
interface ArtistAliasDao {

    // =====================
    // Insert Operations
    // =====================

    /**
     * Insert a new artist alias.
     * Will ignore if normalized name already exists (unique constraint).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: ArtistAlias): Long
    
    /**
     * Insert multiple artist aliases.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<ArtistAlias>)
    
    /**
     * Update the target artist for an alias atomically.
     * Used during chained merges (A->B, B->C results in A->C).
     */
    @Query("UPDATE artist_aliases SET target_artist_id = :newTargetId WHERE id = :aliasId")
    suspend fun updateTargetArtist(aliasId: Long, newTargetId: Long)

    // =====================
    // Query Operations
    // =====================

    /**
     * Find an alias by normalized artist name.
     * Returns the alias if one exists, or null.
     * 
     * IMPORTANT: Caller must normalize the name using Artist.normalizeName() before calling.
     */
    @Query("SELECT * FROM artist_aliases WHERE original_name_normalized = :normalizedName LIMIT 1")
    suspend fun findAlias(normalizedName: String): ArtistAlias?

    /**
     * Get all aliases that point to a specific target artist.
     */
    @Query("SELECT * FROM artist_aliases WHERE target_artist_id = :artistId ORDER BY created_at DESC")
    suspend fun getAliasesForArtist(artistId: Long): List<ArtistAlias>
    
    /**
     * Observe all aliases for an artist.
     */
    @Query("SELECT * FROM artist_aliases WHERE target_artist_id = :artistId ORDER BY created_at DESC")
    fun observeAliasesForArtist(artistId: Long): Flow<List<ArtistAlias>>
    
    /**
     * Get all aliases (for backup/export).
     */
    @Query("SELECT * FROM artist_aliases ORDER BY created_at DESC")
    suspend fun getAllSync(): List<ArtistAlias>
    
    /**
     * Check if an alias exists for a normalized name.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM artist_aliases WHERE original_name_normalized = :normalizedName)")
    suspend fun hasAlias(normalizedName: String): Boolean

    // =====================
    // Delete Operations
    // =====================

    /**
     * Delete an alias by ID.
     */
    @Query("DELETE FROM artist_aliases WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    /**
     * Delete all aliases for a target artist.
     */
    @Query("DELETE FROM artist_aliases WHERE target_artist_id = :artistId")
    suspend fun deleteAllForArtist(artistId: Long)
    
    /**
     * Delete an alias.
     */
    @Delete
    suspend fun delete(alias: ArtistAlias)
    
    /**
     * Delete all aliases.
     */
    @Query("DELETE FROM artist_aliases")
    suspend fun deleteAll()

    // =====================
    // Count Operations
    // =====================

    /**
     * Count total aliases.
     */
    @Query("SELECT COUNT(*) FROM artist_aliases")
    suspend fun countAll(): Int
    
    /**
     * Count aliases for a specific artist.
     */
    @Query("SELECT COUNT(*) FROM artist_aliases WHERE target_artist_id = :artistId")
    suspend fun countForArtist(artistId: Long): Int
}
