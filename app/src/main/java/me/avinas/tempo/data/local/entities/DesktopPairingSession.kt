package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores the active desktop satellite pairing session.
 *
 * Pairing flow (v3 - ECDH):
 * The desktop Tempo app displays a QR code containing its ECDH public key,
 * IP address, and port. The phone scans this QR code, generates its own ECDH
 * key pair, derives a shared secret via ECDH, and uses HKDF to derive the
 * auth token. The auth token is never transmitted over the network or displayed
 * in the QR code. The phone sends its public key to the desktop's confirm
 * endpoint, and both sides derive the same auth token independently.
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
    @PrimaryKey val id: String,

    /**
     * Auth token derived via HKDF from ECDH shared secret.
     * Stored encrypted via [TokenEncryptor] at rest.
     * The desktop app sends this same token (or its rotation) in every
     * scrobble POST so the phone can validate the request, accompanied by
     * an HMAC-SHA256 signature.
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

    /** Desktop app's local IP address. */
    @ColumnInfo(name = "desktop_ip", defaultValue = "NULL") val desktopIp: String? = null,

    /** Port the desktop app's scrobble endpoint listens on. */
    @ColumnInfo(name = "desktop_port", defaultValue = "NULL") val desktopPort: Int? = null,

    /** ECDH public key of this phone (base64url-no-pad SEC1 uncompressed point), for re-derivation. */
    @ColumnInfo(name = "phone_public_key", defaultValue = "NULL") val phonePublicKey: String? = null,

    /** ECDH public key of the desktop (base64url-no-pad SEC1 uncompressed point), received from QR. */
    @ColumnInfo(name = "desktop_public_key", defaultValue = "NULL") val desktopPublicKey: String? = null,

    /** Token rotation version counter; incremented after each successful sync. */
    @ColumnInfo(name = "token_version", defaultValue = "0") val tokenVersion: Int = 0
)