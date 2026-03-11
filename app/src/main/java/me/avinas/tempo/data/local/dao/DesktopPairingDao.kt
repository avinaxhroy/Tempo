package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.avinas.tempo.data.local.entities.DesktopPairingSession

@Dao
interface DesktopPairingDao {

    /** Returns the one active pairing session, or null if the user has never paired. */
    @Query("SELECT * FROM desktop_pairing_sessions WHERE is_active = 1 ORDER BY paired_at_ms DESC LIMIT 1")
    suspend fun getActiveSession(): DesktopPairingSession?

    /** Upsert a session (used when creating or updating an existing record). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(session: DesktopPairingSession)

    /** Soft-deactivate all existing sessions before creating a new one. */
    @Query("UPDATE desktop_pairing_sessions SET is_active = 0")
    suspend fun deactivateAll()

    /** Validate: find an active session matching the given token. Returns null if invalid. */
    @Query("SELECT * FROM desktop_pairing_sessions WHERE auth_token = :token AND is_active = 1 LIMIT 1")
    suspend fun findByToken(token: String): DesktopPairingSession?

    /** Update the last-seen timestamp and device name after a successful scrobble ingestion. */
    @Query("UPDATE desktop_pairing_sessions SET last_seen_ms = :timestamp, device_name = :deviceName WHERE auth_token = :token")
    suspend fun updateLastSeen(token: String, timestamp: Long, deviceName: String)
}
