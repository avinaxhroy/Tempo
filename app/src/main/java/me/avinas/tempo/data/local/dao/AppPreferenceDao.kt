package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.AppPreference
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPreferenceDao {
    
    /**
     * Get all app preferences, ordered by display name.
     */
    @Query("SELECT * FROM app_preferences ORDER BY displayName ASC")
    fun getAllApps(): Flow<List<AppPreference>>
    
    /**
     * Get all enabled apps (not blocked and isEnabled=true).
     * Used by MusicTrackingService to determine which apps to track.
     */
    @Query("SELECT * FROM app_preferences WHERE isEnabled = 1 AND isBlocked = 0")
    fun getEnabledApps(): Flow<List<AppPreference>>
    
    /**
     * Get just the package names of enabled apps for fast lookups.
     */
    @Query("SELECT packageName FROM app_preferences WHERE isEnabled = 1 AND isBlocked = 0")
    suspend fun getEnabledPackageNames(): List<String>
    
    /**
     * Get all blocked apps.
     */
    @Query("SELECT * FROM app_preferences WHERE isBlocked = 1")
    fun getBlockedApps(): Flow<List<AppPreference>>
    
    /**
     * Get just the package names of blocked apps for fast lookups.
     */
    @Query("SELECT packageName FROM app_preferences WHERE isBlocked = 1")
    suspend fun getBlockedPackageNames(): List<String>
    
    /**
     * Get all user-added apps (custom apps added by user, not blocked).
     * Excludes blocked apps to prevent duplicates with the blocked section.
     */
    @Query("SELECT * FROM app_preferences WHERE isUserAdded = 1 AND isBlocked = 0")
    fun getUserAddedApps(): Flow<List<AppPreference>>
    
    /**
     * Get all pre-installed apps (default apps, not user-added, not blocked).
     * Excludes blocked apps to prevent duplicates with the blocked section.
     */
    @Query("SELECT * FROM app_preferences WHERE isUserAdded = 0 AND isBlocked = 0 ORDER BY displayName ASC")
    fun getPreinstalledApps(): Flow<List<AppPreference>>
    
    /**
     * Check if a specific app is enabled for tracking.
     * Returns true if app exists, is enabled, and not blocked.
     * Returns null if app doesn't exist in preferences.
     */
    @Query("SELECT (isEnabled = 1 AND isBlocked = 0) FROM app_preferences WHERE packageName = :packageName")
    suspend fun isAppEnabled(packageName: String): Boolean?
    
    /**
     * Check if a specific app is blocked.
     */
    @Query("SELECT isBlocked FROM app_preferences WHERE packageName = :packageName")
    suspend fun isAppBlocked(packageName: String): Boolean?
    
    /**
     * Get preference for a specific app.
     */
    @Query("SELECT * FROM app_preferences WHERE packageName = :packageName")
    suspend fun getAppPreference(packageName: String): AppPreference?
    
    /**
     * Insert or update an app preference.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: AppPreference)
    
    /**
     * Insert multiple app preferences.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(prefs: List<AppPreference>)
    
    /**
     * Delete an app preference by package name.
     * Typically used for removing user-added apps.
     */
    @Query("DELETE FROM app_preferences WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
    
    /**
     * Update enabled status for an app.
     */
    @Query("UPDATE app_preferences SET isEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)
    
    /**
     * Update blocked status for an app.
     */
    @Query("UPDATE app_preferences SET isBlocked = :blocked WHERE packageName = :packageName")
    suspend fun setBlocked(packageName: String, blocked: Boolean)
    
    /**
     * Get count of all preferences (used to check if seeding is needed).
     */
    @Query("SELECT COUNT(*) FROM app_preferences")
    suspend fun getCount(): Int
    
    /**
     * Get all app preferences for export.
     */
    @Query("SELECT * FROM app_preferences")
    suspend fun getAllSync(): List<AppPreference>
}
