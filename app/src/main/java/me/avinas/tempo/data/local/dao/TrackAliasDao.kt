package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.avinas.tempo.data.local.entities.TrackAlias

@Dao
interface TrackAliasDao {
    @Query("SELECT * FROM track_aliases WHERE original_title = :title AND original_artist = :artist LIMIT 1")
    suspend fun findAlias(title: String, artist: String): TrackAlias?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: TrackAlias): Long
    
    @Query("SELECT * FROM track_aliases WHERE target_track_id = :trackId")
    suspend fun getAliasesForTrack(trackId: Long): List<TrackAlias>
    
    @Query("DELETE FROM track_aliases WHERE target_track_id = :trackId")
    suspend fun clearAliasesForTrack(trackId: Long)
    
    /**
     * Get all track aliases for export.
     */
    @Query("SELECT * FROM track_aliases")
    suspend fun getAllSync(): List<TrackAlias>
}
