package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.AppPreferenceDao
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.DefaultMusicApps
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing user app preferences.
 * 
 * Provides methods for:
 * - Getting enabled/blocked app lists
 * - Toggling app tracking status
 * - Adding custom apps
 * - Blocking/unblocking apps
 */
@Singleton
class AppPreferenceRepository @Inject constructor(
    private val appPreferenceDao: AppPreferenceDao
) {
    
    /**
     * Get all app preferences as a flow.
     */
    fun getAllApps(): Flow<List<AppPreference>> = appPreferenceDao.getAllApps()
    
    /**
     * Get enabled apps (not blocked and isEnabled=true).
     */
    fun getEnabledApps(): Flow<List<AppPreference>> = appPreferenceDao.getEnabledApps()
    
    /**
     * Get blocked apps.
     */
    fun getBlockedApps(): Flow<List<AppPreference>> = appPreferenceDao.getBlockedApps()
    
    /**
     * Get user-added apps.
     */
    fun getUserAddedApps(): Flow<List<AppPreference>> = appPreferenceDao.getUserAddedApps()
    
    /**
     * Get pre-installed apps (from default list).
     */
    fun getPreinstalledApps(): Flow<List<AppPreference>> = appPreferenceDao.getPreinstalledApps()
    
    /**
     * Get package names of enabled apps for fast lookup.
     */
    suspend fun getEnabledPackageNames(): Set<String> = 
        appPreferenceDao.getEnabledPackageNames().toSet()
    
    /**
     * Get package names of blocked apps for fast lookup.
     */
    suspend fun getBlockedPackageNames(): Set<String> = 
        appPreferenceDao.getBlockedPackageNames().toSet()
    
    /**
     * Check if an app is enabled for tracking.
     * Returns true if app exists and is enabled, false otherwise.
     */
    suspend fun isAppEnabled(packageName: String): Boolean =
        appPreferenceDao.isAppEnabled(packageName) ?: false
    
    /**
     * Check if an app is blocked.
     */
    suspend fun isAppBlocked(packageName: String): Boolean =
        appPreferenceDao.isAppBlocked(packageName) ?: false
    
    /**
     * Toggle enabled status for an app.
     */
    suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        appPreferenceDao.setEnabled(packageName, enabled)
    }
    
    /**
     * Block or unblock an app.
     * When blocking, sets enabled to false.
     * When unblocking, restores enabled to true.
     */
    suspend fun setAppBlocked(packageName: String, blocked: Boolean) {
        appPreferenceDao.setBlocked(packageName, blocked)
        if (blocked) {
            appPreferenceDao.setEnabled(packageName, false)
        } else {
            // When unblocking, restore enabled so the app actually tracks
            appPreferenceDao.setEnabled(packageName, true)
        }
    }
    
    /**
     * Add a custom app for tracking.
     */
    suspend fun addCustomApp(packageName: String, displayName: String, category: String = "MUSIC") {
        appPreferenceDao.upsert(
            AppPreference(
                packageName = packageName,
                displayName = displayName,
                isEnabled = true,
                isUserAdded = true,
                isBlocked = false,
                category = category
            )
        )
    }
    
    /**
     * Remove a user-added app.
     */
    suspend fun removeApp(packageName: String) {
        appPreferenceDao.delete(packageName)
    }
    
    /**
     * Get preference for a specific app.
     */
    suspend fun getAppPreference(packageName: String): AppPreference? =
        appPreferenceDao.getAppPreference(packageName)
    
    /**
     * Seed database with default apps if empty.
     * Called on first app launch or fresh install.
     */
    suspend fun seedDefaultAppsIfNeeded() {
        if (appPreferenceDao.getCount() == 0) {
            // Add music apps (enabled by default)
            val musicPrefs = DefaultMusicApps.MUSIC_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = true,
                    isUserAdded = false,
                    isBlocked = false,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(musicPrefs)
            
            // Add blocked apps
            val blockedPrefs = DefaultMusicApps.BLOCKED_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = false,
                    isUserAdded = false,
                    isBlocked = true,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(blockedPrefs)
        }
    }
}
