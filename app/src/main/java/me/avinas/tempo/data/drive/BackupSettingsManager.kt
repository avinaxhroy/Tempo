package me.avinas.tempo.data.drive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore for backup-related settings.
 */
private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup_settings")

/**
 * Manages backup configuration settings using DataStore.
 */
@Singleton
class BackupSettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        // Preference keys
        private val GOOGLE_DRIVE_ENABLED = booleanPreferencesKey("google_drive_enabled")
        private val BACKUP_INTERVAL = stringPreferencesKey("backup_interval")
        private val LAST_BACKUP_TIME = longPreferencesKey("last_backup_time")
        private val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")
        private val INCLUDE_LOCAL_IMAGES = booleanPreferencesKey("include_local_images")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
    }
    
    /**
     * Current backup settings as a Flow.
     */
    val settings: Flow<BackupSettings> = context.backupDataStore.data.map { preferences ->
        BackupSettings(
            isGoogleDriveEnabled = preferences[GOOGLE_DRIVE_ENABLED] ?: false,
            backupInterval = BackupInterval.entries.find { 
                it.name == preferences[BACKUP_INTERVAL] 
            } ?: BackupInterval.MANUAL,
            lastBackupTime = preferences[LAST_BACKUP_TIME],
            lastBackupStatus = BackupStatus.entries.find {
                it.name == preferences[LAST_BACKUP_STATUS]
            } ?: BackupStatus.NEVER,
            includeLocalImages = preferences[INCLUDE_LOCAL_IMAGES] ?: true,
            wifiOnly = preferences[WIFI_ONLY] ?: true,
            googleAccountEmail = preferences[GOOGLE_ACCOUNT_EMAIL]
        )
    }
    
    /**
     * Enable/disable Google Drive backup.
     */
    suspend fun setGoogleDriveEnabled(enabled: Boolean) {
        context.backupDataStore.edit { preferences ->
            preferences[GOOGLE_DRIVE_ENABLED] = enabled
        }
    }
    
    /**
     * Set backup interval.
     */
    suspend fun setBackupInterval(interval: BackupInterval) {
        context.backupDataStore.edit { preferences ->
            preferences[BACKUP_INTERVAL] = interval.name
        }
    }
    
    /**
     * Update last backup status and optionally timestamp.
     * Timestamp is only updated on SUCCESS to preserve the last successful backup time.
     */
    suspend fun updateLastBackup(status: BackupStatus, timestamp: Long = System.currentTimeMillis()) {
        context.backupDataStore.edit { preferences ->
            // Only update timestamp on successful backup to preserve last success time
            if (status == BackupStatus.SUCCESS) {
                preferences[LAST_BACKUP_TIME] = timestamp
            }
            preferences[LAST_BACKUP_STATUS] = status.name
        }
    }
    
    /**
     * Set whether to include local images in backup.
     */
    suspend fun setIncludeLocalImages(include: Boolean) {
        context.backupDataStore.edit { preferences ->
            preferences[INCLUDE_LOCAL_IMAGES] = include
        }
    }
    
    /**
     * Set whether to only backup on Wi-Fi.
     */
    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.backupDataStore.edit { preferences ->
            preferences[WIFI_ONLY] = wifiOnly
        }
    }
    
    /**
     * Update stored Google account email.
     */
    suspend fun setGoogleAccountEmail(email: String?) {
        context.backupDataStore.edit { preferences ->
            if (email != null) {
                preferences[GOOGLE_ACCOUNT_EMAIL] = email
            } else {
                preferences.remove(GOOGLE_ACCOUNT_EMAIL)
            }
        }
    }
    
    /**
     * Clear all backup settings (useful on sign out).
     */
    suspend fun clearSettings() {
        context.backupDataStore.edit { preferences ->
            preferences.clear()
        }
    }
}

/**
 * Complete backup settings data class.
 */
data class BackupSettings(
    val isGoogleDriveEnabled: Boolean = false,
    val backupInterval: BackupInterval = BackupInterval.MANUAL,
    val lastBackupTime: Long? = null,
    val lastBackupStatus: BackupStatus = BackupStatus.NEVER,
    val includeLocalImages: Boolean = true,
    val wifiOnly: Boolean = true,
    val googleAccountEmail: String? = null
)
