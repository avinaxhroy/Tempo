package me.avinas.tempo.data.drive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Google Drive OAuth tokens using EncryptedSharedPreferences.
 * 
 * Uses AES-256-GCM encryption for values and AES-256-SIV for keys,
 * backed by Android Keystore for key management. This ensures tokens
 * are stored securely and cannot be read by other apps.
 * 
 * This enables background workers (like DriveBackupWorker) to restore
 * user sessions without requiring UI interaction, making scheduled
 * backups reliable even when the app has been killed.
 */
@Singleton
class GoogleDriveTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GoogleDriveTokenStorage"
        private const val PREFS_NAME = "google_drive_auth_prefs"
        
        // Token keys
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TIMESTAMP = "token_timestamp"
        
        // Account info keys
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ACCOUNT_DISPLAY_NAME = "account_display_name"
        private const val KEY_ACCOUNT_PHOTO_URL = "account_photo_url"
        
        // Token validity - Access tokens typically expire in 1 hour
        // We'll consider them stale after 55 minutes to add buffer
        private const val TOKEN_STALE_THRESHOLD_MS = 55 * 60 * 1000L
        
        // Maximum token age before we consider it definitely expired (2 hours)
        private const val TOKEN_MAX_AGE_MS = 2 * 60 * 60 * 1000L
    }
    
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            // Fallback to regular SharedPreferences (less secure, but keeps app functional)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    // ==================== TOKEN OPERATIONS ====================
    
    /**
     * Save the access token with timestamp.
     * 
     * Note: Google's OAuth for Drive only provides access tokens via the
     * Authorization API. Refresh tokens are managed internally by Google Play Services.
     */
    fun saveAccessToken(accessToken: String) {
        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putLong(KEY_TOKEN_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Access token saved")
    }
    
    /**
     * Get the stored access token.
     * Returns null if no token is stored.
     */
    fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }
    
    /**
     * Get the timestamp when the token was saved.
     */
    fun getTokenTimestamp(): Long {
        return encryptedPrefs.getLong(KEY_TOKEN_TIMESTAMP, 0L)
    }
    
    /**
     * Check if a valid access token is stored.
     */
    fun hasToken(): Boolean {
        return getAccessToken() != null
    }
    
    /**
     * Check if the stored token is likely stale (close to or past expiry).
     * 
     * Google OAuth access tokens typically expire after 1 hour.
     * We consider them stale after 55 minutes to add buffer for API calls.
     */
    fun isTokenStale(): Boolean {
        val timestamp = getTokenTimestamp()
        if (timestamp == 0L) return true
        
        val age = System.currentTimeMillis() - timestamp
        return age > TOKEN_STALE_THRESHOLD_MS
    }
    
    /**
     * Check if the stored token is definitely expired (past max age).
     */
    fun isTokenExpired(): Boolean {
        val timestamp = getTokenTimestamp()
        if (timestamp == 0L) return true
        
        val age = System.currentTimeMillis() - timestamp
        return age > TOKEN_MAX_AGE_MS
    }
    
    /**
     * Clear the stored access token.
     */
    fun clearToken() {
        encryptedPrefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_TOKEN_TIMESTAMP)
            apply()
        }
        Log.d(TAG, "Access token cleared")
    }
    
    // ==================== ACCOUNT INFO OPERATIONS ====================
    
    /**
     * Save the Google account information.
     */
    fun saveAccountInfo(email: String, displayName: String?, photoUrl: String?) {
        encryptedPrefs.edit().apply {
            putString(KEY_ACCOUNT_EMAIL, email)
            displayName?.let { putString(KEY_ACCOUNT_DISPLAY_NAME, it) }
            photoUrl?.let { putString(KEY_ACCOUNT_PHOTO_URL, it) }
            apply()
        }
        Log.d(TAG, "Account info saved for: $email")
    }
    
    /**
     * Get the stored account email.
     */
    fun getAccountEmail(): String? {
        return encryptedPrefs.getString(KEY_ACCOUNT_EMAIL, null)
    }
    
    /**
     * Get the stored display name.
     */
    fun getAccountDisplayName(): String? {
        return encryptedPrefs.getString(KEY_ACCOUNT_DISPLAY_NAME, null)
    }
    
    /**
     * Get the stored photo URL.
     */
    fun getAccountPhotoUrl(): String? {
        return encryptedPrefs.getString(KEY_ACCOUNT_PHOTO_URL, null)
    }
    
    /**
     * Check if account info is stored.
     */
    fun hasAccountInfo(): Boolean {
        return getAccountEmail() != null
    }
    
    /**
     * Get the stored account as a GoogleAccount object.
     * Returns null if no account info is stored.
     */
    fun getStoredAccount(): GoogleAccount? {
        val email = getAccountEmail() ?: return null
        return GoogleAccount(
            email = email,
            displayName = getAccountDisplayName(),
            photoUrl = getAccountPhotoUrl()
        )
    }
    
    /**
     * Clear only account info (keeps tokens).
     */
    fun clearAccountInfo() {
        encryptedPrefs.edit().apply {
            remove(KEY_ACCOUNT_EMAIL)
            remove(KEY_ACCOUNT_DISPLAY_NAME)
            remove(KEY_ACCOUNT_PHOTO_URL)
            apply()
        }
        Log.d(TAG, "Account info cleared")
    }
    
    // ==================== COMPLETE CLEAR ====================
    
    /**
     * Clear all stored data (tokens and account info).
     * Call this on sign out.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        Log.d(TAG, "All stored data cleared")
    }
    
    // ==================== DEBUG/STATUS ====================
    
    /**
     * Get token storage status for debugging.
     */
    fun getStorageStatus(): StorageStatus {
        return StorageStatus(
            hasToken = hasToken(),
            hasAccountInfo = hasAccountInfo(),
            isTokenStale = isTokenStale(),
            isTokenExpired = isTokenExpired(),
            tokenAgeMs = if (getTokenTimestamp() > 0) {
                System.currentTimeMillis() - getTokenTimestamp()
            } else null,
            accountEmail = getAccountEmail()
        )
    }
    
    /**
     * Status of stored tokens and account info.
     */
    data class StorageStatus(
        val hasToken: Boolean,
        val hasAccountInfo: Boolean,
        val isTokenStale: Boolean,
        val isTokenExpired: Boolean,
        val tokenAgeMs: Long?,
        val accountEmail: String?
    ) {
        override fun toString(): String {
            val ageStr = tokenAgeMs?.let { "${it / 1000 / 60}min" } ?: "N/A"
            return "StorageStatus(token=$hasToken, stale=$isTokenStale, expired=$isTokenExpired, " +
                    "age=$ageStr, account=$accountEmail)"
        }
    }
}
