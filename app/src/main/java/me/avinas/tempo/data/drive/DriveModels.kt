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
    data object NoCredentials : GoogleSignInResult()
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
