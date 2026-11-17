package me.avinas.tempo.data.drive

/**
 * Data models for Google Drive backup operations.
 */

/**
 * Backup info retrieved from Google Drive.
 */
data class DriveBackupInfo(
    val fileId: String,
    val fileName: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val appVersion: String?,
    val deviceName: String?
)

/**
 * User's Google account information.
 */
data class GoogleAccount(
    val email: String,
    val displayName: String?,
    val photoUrl: String?
)

/**
 * Result of Google Sign-In operation.
 */
sealed class GoogleSignInResult {
    data class Success(val account: GoogleAccount) : GoogleSignInResult()
    data class Error(val message: String, val exception: Exception? = null) : GoogleSignInResult()
    data object Cancelled : GoogleSignInResult()
}

/**
 * Result of Drive backup upload operation.
 */
sealed class DriveBackupResult {
    data class Success(val fileId: String, val fileName: String) : DriveBackupResult()
    data class Error(val message: String, val exception: Exception? = null) : DriveBackupResult()
}

/**
 * Result of Drive restore operation.
 */
sealed class DriveRestoreResult {
    data class Success(val localFile: java.io.File) : DriveRestoreResult()
    data class Error(val message: String, val exception: Exception? = null) : DriveRestoreResult()
}

/**
 * Backup scheduling interval options.
 */
enum class BackupInterval(val hours: Long, val displayName: String) {
    MANUAL(0, "Manual"),
    DAILY(24, "Daily"),
    WEEKLY(168, "Weekly"),
    MONTHLY(720, "Monthly")
}

/**
 * Status of the last backup operation.
 */
enum class BackupStatus {
    NEVER,
    SUCCESS,
    FAILED,
    IN_PROGRESS
}

/**
 * Wording for a failed backup notification or snackbar. Each failure type gets its
 * own title, because "Backup failed" tells the user nothing about whether they must
 * sign in, free storage, wait, or reconnect.
 */
data class DriveFailureNotice(val title: String, val message: String)

/**
 * Maps a failure to notification/snackbar wording. [fallback] is used when the
 * exception carries no message of its own (or is not a Drive exception at all).
 */
fun Throwable?.toDriveFailureNotice(fallback: String): DriveFailureNotice {
    val title = when (this) {
        is DriveException.Auth -> "Drive Backup Needs Sign-In"
        is DriveException.Corrupt -> "Drive Backup Verification Failed"
        is DriveException.Network -> "Drive Backup Failed — Offline"
        else -> "Drive Backup Failed"
    }
    val message = this?.message?.takeIf { it.isNotBlank() } ?: fallback
    return DriveFailureNotice(title, message)
}
