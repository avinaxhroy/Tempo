package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.UserPreferences
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferencesDao {
    @Query("SELECT * FROM user_preferences WHERE id = 0 LIMIT 1")
    fun preferences(): Flow<UserPreferences?>
    
    @Query("SELECT * FROM user_preferences WHERE id = 0 LIMIT 1")
    suspend fun getSync(): UserPreferences?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prefs: UserPreferences)
}
