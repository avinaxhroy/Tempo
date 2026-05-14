package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.avinas.tempo.data.local.entities.DesktopPairingSession

@Dao
interface DesktopPairingDao {

    @Query("SELECT * FROM desktop_pairing_sessions WHERE is_active = 1 ORDER BY paired_at_ms DESC LIMIT 1")
    suspend fun getActiveSession(): DesktopPairingSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(session: DesktopPairingSession)

    @Query("UPDATE desktop_pairing_sessions SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE desktop_pairing_sessions SET last_seen_ms = :timestamp, device_name = :deviceName WHERE id = :sessionId")
    suspend fun updateLastSeen(sessionId: String, timestamp: Long, deviceName: String)

    @Query("UPDATE desktop_pairing_sessions SET auth_token = :newEncryptedToken, token_version = token_version + 1 WHERE id = :sessionId")
    suspend fun updateToken(sessionId: String, newEncryptedToken: String)

    @Transaction
    suspend fun deactivateAndInsert(session: DesktopPairingSession) {
        deactivateAll()
        insertOrUpdate(session)
    }
}