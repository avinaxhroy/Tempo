package me.avinas.tempo.data.drive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.driveHistorySyncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_history_sync_settings"
)

@Singleton
class DriveHistorySyncSettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private val ENABLED = booleanPreferencesKey("enabled")
        private val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        private val LAST_STATUS = stringPreferencesKey("last_status")
        private val LAST_MESSAGE = stringPreferencesKey("last_message")
        private val LAST_UPLOADED = intPreferencesKey("last_uploaded")
        private val LAST_IMPORTED = intPreferencesKey("last_imported")
    }

    val settings: Flow<DriveHistorySyncSettings> = context.driveHistorySyncDataStore.data.map { prefs ->
        DriveHistorySyncSettings(
            enabled = prefs[ENABLED] ?: false,
            lastSyncTime = prefs[LAST_SYNC_TIME],
            lastStatus = DriveHistorySyncStatus.entries.firstOrNull {
                it.name == prefs[LAST_STATUS]
            } ?: DriveHistorySyncStatus.NEVER,
            lastMessage = prefs[LAST_MESSAGE],
            lastUploaded = prefs[LAST_UPLOADED] ?: 0,
            lastImported = prefs[LAST_IMPORTED] ?: 0
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.driveHistorySyncDataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun markRunning() {
        context.driveHistorySyncDataStore.edit {
            it[LAST_STATUS] = DriveHistorySyncStatus.RUNNING.name
            it.remove(LAST_MESSAGE)
        }
    }

    suspend fun markSuccess(uploaded: Int, imported: Int, message: String? = null) {
        context.driveHistorySyncDataStore.edit {
            it[LAST_SYNC_TIME] = System.currentTimeMillis()
            it[LAST_STATUS] = DriveHistorySyncStatus.SUCCESS.name
            it[LAST_UPLOADED] = uploaded
            it[LAST_IMPORTED] = imported
            if (message.isNullOrBlank()) it.remove(LAST_MESSAGE) else it[LAST_MESSAGE] = message
        }
    }

    suspend fun markFailure(message: String) {
        context.driveHistorySyncDataStore.edit {
            it[LAST_STATUS] = DriveHistorySyncStatus.FAILED.name
            it[LAST_MESSAGE] = message
        }
    }

    suspend fun clearForSignOut() {
        context.driveHistorySyncDataStore.edit { it.clear() }
    }
}

data class DriveHistorySyncSettings(
    val enabled: Boolean = false,
    val lastSyncTime: Long? = null,
    val lastStatus: DriveHistorySyncStatus = DriveHistorySyncStatus.NEVER,
    val lastMessage: String? = null,
    val lastUploaded: Int = 0,
    val lastImported: Int = 0
)

enum class DriveHistorySyncStatus {
    NEVER,
    RUNNING,
    SUCCESS,
    FAILED
}
