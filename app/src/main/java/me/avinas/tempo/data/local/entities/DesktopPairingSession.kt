package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores the active desktop satellite pairing session.
 *
 * Pairing flow: the desktop Tempo app displays a QR code containing its IP address,
 * port, and auth token. The phone scans this QR code with its camera, extracts the
 * credentials, and persists them here. The desktop then pushes scrobbles to the phone's
 * built-in NanoHTTPD server, authenticating with the same token embedded in the QR.
 *
 * Only one active session is expected at a time; old sessions are soft-deactivated.
 */
@Entity(
    tableName = "desktop_pairing_sessions",
    indices = [
        Index(value = ["auth_token"]),
        Index(value = ["is_active"])
    ]
)
data class DesktopPairingSession(
    /** UUID for this pairing record. */
    @PrimaryKey val id: String,

    /**
     * Auth token extracted from the desktop QR code. The desktop app sends this same
     * token in every scrobble POST so the phone can validate the request.
     */
    @ColumnInfo(name = "auth_token") val authToken: String,

    /** Human-readable device name sent by the desktop app on first scrobble. */
    @ColumnInfo(name = "device_name", defaultValue = "") val deviceName: String = "",

    /** Epoch-ms when this pairing was created on the phone. */
    @ColumnInfo(name = "paired_at_ms") val pairedAtMs: Long,

    /** Epoch-ms of the last successful scrobble from this device; null if never synced. */
    @ColumnInfo(name = "last_seen_ms", defaultValue = "NULL") val lastSeenMs: Long? = null,

    /** Whether this pairing is still active. Only one active session is used at a time. */
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,

    /** Desktop app's local IP address (learned by scanning the desktop's QR code). */
    @ColumnInfo(name = "desktop_ip", defaultValue = "NULL") val desktopIp: String? = null,

    /** Port the desktop app's scrobble endpoint listens on (learned from the desktop's QR code). */
    @ColumnInfo(name = "desktop_port", defaultValue = "NULL") val desktopPort: Int? = null
)
