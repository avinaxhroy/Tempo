package me.avinas.tempo.data.drive

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google authentication using the modern Credential Manager API.
 * 
 * IMPORTANT: Credential Manager requires an Activity context for UI operations.
 * The signIn() and restoreSession() methods require an Activity to be passed.
 * 
 * This replaces the deprecated GoogleSignInClient approach and provides:
 * - Google Sign-In via Credential Manager bottom sheet
 * - Authorization for Google Drive API access
 * - Token management for API calls
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        
        // Using drive.file scope - only accesses files created by this app
        // This avoids CASA security assessment requirements
        private val DRIVE_SCOPE = Scope(DriveScopes.DRIVE_FILE)
    }
    
    // CredentialManager is created per-call with Activity context
    private val authorizationClient = Identity.getAuthorizationClient(context)
    
    private val _currentAccount = MutableStateFlow<GoogleAccount?>(null)
    val currentAccount: StateFlow<GoogleAccount?> = _currentAccount.asStateFlow()
    
    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()
    
    // Indicates that Drive authorization requires user consent via pendingIntent
    private val _needsDriveConsent = MutableStateFlow(false)
    val needsDriveConsent: StateFlow<Boolean> = _needsDriveConsent.asStateFlow()
    
    // Cached authorization result for Drive API access
    private var authorizationResult: AuthorizationResult? = null
    
    /**
     * Sign in with Google using Credential Manager.
     * Shows a bottom sheet with available Google accounts.
     * 
     * @param activity The Activity context REQUIRED for Credential Manager UI
     */
    suspend fun signIn(activity: Activity): GoogleSignInResult = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Starting Google Sign-In with Credential Manager")
            
            // Create CredentialManager with Activity context
            val credentialManager = CredentialManager.create(activity)
            
            // Build Google ID request
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false) // Show all accounts
                .setAutoSelectEnabled(false) // Let user choose
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            
            // CRITICAL: Must use Activity context here, not Application context
            val response = credentialManager.getCredential(activity, request)
            
            handleSignInResponse(response)
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Sign-in cancelled by user")
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No credentials available", e)
            GoogleSignInResult.NoCredentials
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Sign-in failed", e)
            GoogleSignInResult.Error("Sign-in failed: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sign-in", e)
            GoogleSignInResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    /**
     * Handle the credential response and extract Google account info.
     */
    private suspend fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        
        return when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        
                        val account = GoogleAccount(
                            email = googleIdCredential.id,
                            displayName = googleIdCredential.displayName,
                            photoUrl = googleIdCredential.profilePictureUri?.toString()
                        )
                        
                        _currentAccount.value = account
                        _isSignedIn.value = true
                        
                        Log.i(TAG, "Successfully signed in as ${account.email}")
                        
                        // Authorize for Drive access
                        requestDriveAuthorization()
                        
                        GoogleSignInResult.Success(account)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Google ID credential", e)
                        GoogleSignInResult.Error("Failed to parse credential: ${e.message}", e)
                    }
                } else {
                    GoogleSignInResult.Error("Unexpected credential type: ${credential.type}")
                }
            }
            else -> {
                GoogleSignInResult.Error("Unexpected credential class: ${credential::class.java.name}")
            }
        }
    }
    
    /**
     * Request authorization for Google Drive API access.
     * This is called after successful sign-in.
     * 
     * @return true if authorization was granted immediately, false if consent is needed
     */
    private suspend fun requestDriveAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting Drive authorization")
            
            val authRequest = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(DRIVE_SCOPE))
                .build()
            
            authorizationResult = authorizationClient.authorize(authRequest).await()
            
            if (authorizationResult?.hasResolution() == true) {
                Log.d(TAG, "Drive authorization requires user consent - pendingIntent available")
                _needsDriveConsent.value = true
                false
            } else {
                Log.i(TAG, "Drive authorization granted, accessToken present: ${authorizationResult?.accessToken != null}")
                _needsDriveConsent.value = false
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Drive authorization", e)
            false
        }
    }
    
    /**
     * Called after user completes the consent flow via pendingIntent.
     * Re-requests authorization to get the access token.
     */
    suspend fun completeConsentFlow(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Completing consent flow, re-requesting authorization")
            _needsDriveConsent.value = false
            
            val authRequest = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(DRIVE_SCOPE))
                .build()
            
            authorizationResult = authorizationClient.authorize(authRequest).await()
            
            val hasToken = authorizationResult?.accessToken != null
            Log.i(TAG, "Consent flow completed, accessToken present: $hasToken")
            hasToken
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete consent flow", e)
            false
        }
    }
    
    /**
     * Get the authorization result for Drive API access.
     * May return null if not authorized yet.
     */
    fun getAuthorizationResult(): AuthorizationResult? = authorizationResult
    
    /**
     * Check if Drive authorization requires user consent.
     * Returns the pending intent if consent is needed.
     */
    fun getDriveAuthorizationPendingIntent() = authorizationResult?.pendingIntent
    
    /**
     * Update authorization result after user consent.
     */
    fun updateAuthorizationResult(result: AuthorizationResult) {
        authorizationResult = result
        Log.i(TAG, "Authorization result updated, accessToken present: ${result.accessToken != null}")
    }
    
    /**
     * Get access token for Google Drive API calls.
     * Returns null if not signed in or not authorized.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val result = authorizationResult ?: run {
                Log.w(TAG, "No authorization result available")
                return@withContext null
            }
            
            // Check if we have full authorization
            if (result.hasResolution()) {
                Log.w(TAG, "Authorization requires resolution - no access token yet")
                return@withContext null
            }
            
            val token = result.accessToken
            if (token == null) {
                Log.w(TAG, "Authorization result has no access token")
            }
            token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            null
        }
    }

    /**
     * Force refresh of the access token.
     * Call this when a 401 Unauthorized error is encountered.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Refreshing access token")
            
            val authRequest = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(DRIVE_SCOPE))
                .build()
            
            // Re-authorize to get a fresh token
            authorizationResult = authorizationClient.authorize(authRequest).await()
            
            if (authorizationResult?.hasResolution() == true) {
                Log.d(TAG, "Refresh requires user consent")
                _needsDriveConsent.value = true
                false
            } else {
                val hasToken = authorizationResult?.accessToken != null
                Log.i(TAG, "Token refreshed successfully: $hasToken")
                hasToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh token", e)
            false
        }
    }
    
    /**
     * Sign out and clear all credentials.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Signing out")
            
            // Create CredentialManager with application context for cleanup
            val credentialManager = CredentialManager.create(context)
            
            // Clear Credential Manager state
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            
            // Clear local state
            authorizationResult = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
            
            Log.i(TAG, "Successfully signed out")
        } catch (e: Exception) {
            Log.e(TAG, "Error during sign out", e)
        }
    }
    
    /**
     * Restore session on app launch.
     * Attempts to silently sign in with previously authorized account.
     * 
     * @param activity The Activity context REQUIRED for Credential Manager UI
     */
    suspend fun restoreSession(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Attempting to restore session")
            
            val credentialManager = CredentialManager.create(activity)
            
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(true) // Only previously authorized accounts
                .setAutoSelectEnabled(true) // Auto-select if only one account
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            
            val response = credentialManager.getCredential(activity, request)
            val result = handleSignInResponse(response)
            
            result is GoogleSignInResult.Success
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No saved credentials to restore")
            false
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Session restore cancelled")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore session", e)
            false
        }
    }
    
    /**
     * Attempt silent session restore without showing UI.
     * Uses Application context - may not work in all cases.
     * For background operations like WorkManager.
     * 
     * IMPORTANT LIMITATION: This method can only succeed if the session is already
     * active in memory. The Credential Manager API does not support truly silent
     * token refresh without user interaction. This means scheduled backups
     * (daily/weekly/monthly) via WorkManager will only succeed if:
     * 1. The app is already running in memory, OR
     * 2. The user has recently opened the app and authenticated
     * 
     * If the app was killed and no active session exists, this returns false
     * and scheduled backups will fail with a notification asking the user to
     * sign in via the app.
     */
    suspend fun restoreSessionSilently(): Boolean = withContext(Dispatchers.IO) {
        // For background workers, we can only check if we have a cached session
        // We cannot show UI from a background context
        if (_isSignedIn.value && authorizationResult?.accessToken != null) {
            Log.d(TAG, "Session already active")
            return@withContext true
        }
        
        Log.d(TAG, "No active session for silent restore - UI sign-in required")
        false
    }
}
