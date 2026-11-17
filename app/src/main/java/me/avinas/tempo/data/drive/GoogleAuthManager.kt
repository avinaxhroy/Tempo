package me.avinas.tempo.data.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
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
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google authentication via the Credential Manager API, which replaces the deprecated
 * GoogleSignInClient. Credential Manager needs an Activity for UI, so [signIn] and
 * [restoreSession] take one.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStorage: GoogleDriveTokenStorage
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        
        // Using drive.file scope - only accesses files created by this app
        // This avoids CASA security assessment requirements
        private val DRIVE_SCOPE = Scope(DriveScopes.DRIVE_FILE)

        internal fun failedTokenIsStillCurrent(
            failedAccessToken: String,
            currentAccessToken: String?
        ): Boolean = failedAccessToken.isNotBlank() && failedAccessToken == currentAccessToken
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
    
    // Cached authorization result for Drive API access.
    @Volatile
    private var authorizationResult: AuthorizationResult? = null

    // ApiException can carry a consent resolution even when no AuthorizationResult
    // is returned. Keep that PendingIntent separately so the UI can always launch
    // the required Drive consent flow.
    @Volatile
    private var authorizationResolution: PendingIntent? = null

    // Google Play Services authorization calls mutate the same account/token
    // state. Serialize interactive sign-in, silent restore, refresh, consent and
    // sign-out so a background worker cannot overwrite a newly selected account.
    private val authOperationMutex = Mutex()

    private fun configuredWebClientId(): String? =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }
    
    /** Shows the "Sign in with Google" account chooser. Needs an Activity for Credential Manager UI. */
    suspend fun signIn(activity: Activity): GoogleSignInResult =
        authOperationMutex.withLock { signInUnlocked(activity) }

    private suspend fun signInUnlocked(activity: Activity): GoogleSignInResult = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId()
            ?: return@withContext GoogleSignInResult.Error(
                "Google Sign-In is not configured in this build (missing GOOGLE_WEB_CLIENT_ID)."
            ).also {
                Log.e(TAG, "Cannot start Google Sign-In: GOOGLE_WEB_CLIENT_ID is blank")
            }

        try {
            Log.d(TAG, "Starting explicit Google Sign-In with Credential Manager")
            
            // Create CredentialManager with Activity context
            val credentialManager = CredentialManager.create(activity)
            
            // A user tapping a dedicated "Sign in with Google" button should use
            // GetSignInWithGoogleOption. GetGoogleIdOption is intended for the
            // general credential selector / session-restore path and can return
            // NoCredentialException even when a Google account exists on-device.
            val googleSignInOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleSignInOption)
                .build()
            
            // CRITICAL: Must use Activity context here, not Application context
            val response = credentialManager.getCredential(activity, request)
            
            handleSignInResponse(response)
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Sign-in cancelled by user")
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            // NoCredentialException does not mean there is no Google account on
            // the device. It means Credential Manager could not return a usable
            // credential for this request, so surface an actionable error instead
            // of the misleading "No Google accounts found" message.
            Log.w(TAG, "No Google credential available for explicit sign-in", e)
            GoogleSignInResult.Error(
                "Google Sign-In is unavailable. Check Google Play services and your Google account settings, then try again.",
                e
            )
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Sign-in failed", e)
            GoogleSignInResult.Error("Sign-in failed: ${e.message}", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sign-in", e)
            GoogleSignInResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    private suspend fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        
        return when (credential) {
            is CustomCredential -> {
                val isGoogleIdCredential =
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL

                if (isGoogleIdCredential) {
                    try {
                        val googleIdCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val email = googleIdCredential.email?.takeIf { it.isNotBlank() }
                            ?: return GoogleSignInResult.Error(
                                "Google Sign-In did not return an email address for the selected account."
                            )
                        
                        val account = GoogleAccount(
                            email = email,
                            displayName = googleIdCredential.displayName,
                            photoUrl = googleIdCredential.profilePictureUri?.toString()
                        )
                        
                        _currentAccount.value = account
                        _isSignedIn.value = true
                        
                        // Persist account info for background session restoration
                        tokenStorage.saveAccountInfo(
                            email = account.email,
                            displayName = account.displayName,
                            photoUrl = account.photoUrl
                        )
                        
                        Log.i(TAG, "Google identity sign-in succeeded")

                        // An explicit account selection starts a fresh Drive authorization
                        // lifecycle. Never allow a token from a previous account/session to
                        // survive if the new authorization later needs consent or fails.
                        authorizationResult = null
                        authorizationResolution = null
                        _needsDriveConsent.value = false
                        tokenStorage.clearToken()
                        
                        // Authorize for Drive access
                        requestDriveAuthorization()
                        
                        GoogleSignInResult.Success(account)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _currentAccount.value = null
                        _isSignedIn.value = false
                        runCatching { tokenStorage.clearAll() }
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
     * Request Drive access after sign-in. True when granted immediately, false when the user
     * still has to consent.
     */
    private suspend fun requestDriveAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting Drive authorization")
            
            authorizationResolution = null
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                Log.d(TAG, "Drive authorization requires user consent - pendingIntent available")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResolution = null
                val accessToken = authorizationResult?.accessToken
                val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
                val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
                Log.i(
                    TAG,
                    "Drive authorization granted, accessToken present: ${accessToken != null}, " +
                        "drive.file scope granted: $hasDriveScope, scopes: $grantedScopes"
                )
                _needsDriveConsent.value = false
                
                if (accessToken == null || !hasDriveScope) {
                    // This is the classic cause of "Permission denied. Check Drive access.":
                    // the token was issued but does not carry drive.file, so the Drive API
                    // answers 403. Do not persist a broken token.
                    Log.e(
                        TAG,
                        "Drive authorization did not grant the drive.file scope. " +
                            "Ensure the OAuth consent screen for client ${BuildConfig.GOOGLE_WEB_CLIENT_ID} " +
                            "includes https://www.googleapis.com/auth/drive.file"
                    )
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                    return@withContext false
                }
                
                // Persist the access token for background workers
                tokenStorage.saveAccessToken(accessToken)
                Log.d(TAG, "Access token persisted to secure storage")
                
                true
            }
        } catch (e: ApiException) {
            // GMS may throw ApiException carrying the consent pendingIntent
            val resolution = e.status.resolution
            if (resolution != null) {
                authorizationResult = null
                authorizationResolution = resolution
                Log.d(TAG, "Drive authorization requires user consent (ApiException) - pendingIntent available")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResult = null
                authorizationResolution = null
                _needsDriveConsent.value = false
                tokenStorage.clearToken()
                Log.e(TAG, "Failed to request Drive authorization (${e.status.statusCode}: ${e.status.statusMessage})", e)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to request Drive authorization", e)
            false
        }
    }
    
    /** Re-requests authorization and obtains the access token after the user consents. */
    suspend fun completeConsentFlow(): Boolean =
        authOperationMutex.withLock { completeConsentFlowUnlocked() }

    private suspend fun completeConsentFlowUnlocked(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Completing consent flow, re-requesting authorization")
            _needsDriveConsent.value = false
            authorizationResolution = null
            
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()

            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                _needsDriveConsent.value = true
                Log.w(TAG, "Drive consent still requires user resolution after consent flow")
                return@withContext false
            }
            
            val accessToken = authorizationResult?.accessToken
            val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
            val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
            val hasToken = accessToken != null && hasDriveScope
            Log.i(TAG, "Consent flow completed, accessToken present: $hasToken, drive.file scope: $hasDriveScope")
            
            // Persist the access token for background workers
            if (accessToken != null && hasDriveScope) {
                tokenStorage.saveAccessToken(accessToken)
                Log.d(TAG, "Access token persisted to secure storage")
            } else {
                authorizationResult = null
                authorizationResolution = null
                tokenStorage.clearToken()
            }
            
            hasToken
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete consent flow (${e.status.statusCode}: ${e.status.statusMessage})", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete consent flow", e)
            false
        }
    }
    
    /**
     * Build the Drive authorization request.
     *
     * Binds the token to the OAuth Web client (GOOGLE_WEB_CLIENT_ID) and to the
     * account the user signed in with, so Google Play services issues an access
     * token against the consent screen that lists the `drive.file` scope.
     *
     * A Drive authorization request must never fall back to an implicit OAuth
     * client: that can mint a token without the required `drive.file` scope and
     * later surface as a misleading Drive permission failure.
     */
    private fun buildDriveAuthRequest(): AuthorizationRequest {
        val webClientId = requireNotNull(configuredWebClientId()) {
            "GOOGLE_WEB_CLIENT_ID is required for Google Drive authorization"
        }

        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(DRIVE_SCOPE))

        // Pin the authorization to the exact account used for sign-in.
        _currentAccount.value?.let { account ->
            builder.setAccount(Account(account.email, "com.google"))
        }

        // Bind every Drive token request to the configured OAuth Web client.
        builder.requestOfflineAccess(webClientId)

        return builder.build()
    }
    
    /** Pending intent that resolves the Drive consent flow, or null when none is outstanding. */
    fun getDriveAuthorizationPendingIntent(): PendingIntent? =
        authorizationResolution ?: authorizationResult?.pendingIntent
    
    /**
     * Drive access token, or null when not signed in or not authorized. Falls back to the
     * persisted token, which is what background workers rely on.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            // First, try to get from in-memory authorization result
            val result = authorizationResult
            if (result != null && !result.hasResolution()) {
                val token = result.accessToken
                val hasDriveScope = result.grantedScopes.orEmpty()
                    .any { it.contains(DriveScopes.DRIVE_FILE) }
                // The in-memory result carries no issue time; storage records the
                // same token's age, so age the memory copy out with it instead of
                // handing Drive a token that is already an hour old.
                if (token != null && hasDriveScope && !tokenStorage.isTokenStale()) {
                    return@withContext token
                }
                if (token != null && !hasDriveScope) {
                    Log.w(TAG, "Discarding in-memory token without drive.file scope")
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                }
            }
            
            // Fallback to persisted token storage (for background workers)
            val persistedToken = tokenStorage.getAccessToken()
            if (persistedToken != null) {
                // Refresh before the usual one-hour OAuth lifetime is reached.
                // If Google says consent is required, or the token has crossed our
                // hard-expiry threshold, never hand the old token to Drive.
                if (tokenStorage.isTokenStale()) {
                    Log.w(TAG, "Persisted token is stale, attempting silent refresh")
                    if (refreshAccessToken()) {
                        val freshToken = tokenStorage.getAccessToken()
                        if (freshToken != null) {
                            Log.d(TAG, "Got fresh token via silent refresh")
                            return@withContext freshToken
                        }
                    }

                    if (_needsDriveConsent.value || tokenStorage.isTokenExpired()) {
                        Log.w(TAG, "Stale Drive token cannot be reused; authorization is required")
                        tokenStorage.clearToken()
                        return@withContext null
                    }

                    // A transient Play Services failure may happen slightly before
                    // the token's normal expiry. Keep that previously scope-validated
                    // token only while it remains below the hard-expiry threshold;
                    // Drive will still reject/refresh it on a real 401.
                    Log.w(TAG, "Silent refresh failed; temporarily using cached Drive token")
                    return@withContext persistedToken
                }
                Log.d(TAG, "Using persisted token from storage")
                return@withContext persistedToken
            }
            
            Log.w(TAG, "No access token available (memory or storage)")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            null
        }
    }

    /** Force a token refresh, for when Drive answers 401 Unauthorized. */
    suspend fun refreshAccessToken(): Boolean =
        authOperationMutex.withLock { refreshAccessTokenUnlocked() }

    /**
     * Refresh after a concrete Drive request was rejected with HTTP 401.
     *
     * Another concurrent request may already have rotated the token while the
     * failed HTTP response was in flight. In that case the replacement token is
     * kept and the caller can retry immediately. Only the exact rejected token is
     * cleared/refreshed, so a late 401 can never erase a newer authorization.
     */
    suspend fun refreshAccessTokenAfterFailure(failedAccessToken: String): Boolean =
        authOperationMutex.withLock {
            val currentAccessToken = currentScopedAccessToken()
            if (currentAccessToken != null &&
                !failedTokenIsStillCurrent(failedAccessToken, currentAccessToken)
            ) {
                Log.d(TAG, "Ignoring stale 401 because Drive authorization already rotated")
                return@withLock true
            }

            if (failedTokenIsStillCurrent(failedAccessToken, authorizationResult?.accessToken)) {
                authorizationResult = null
                authorizationResolution = null
                _needsDriveConsent.value = false
            }
            if (failedTokenIsStillCurrent(failedAccessToken, tokenStorage.getAccessToken())) {
                tokenStorage.clearToken()
            }

            // Play Services keeps its own access-token cache. Forgetting our copy is not
            // enough: the next authorize() hands the same rejected token straight back,
            // so every retry fails the same way and the user is told "session expired"
            // forever, even after signing out and in. clearToken() is the documented way
            // to drop it, forcing the next authorize() to go to the OAuth server.
            clearCachedAccessToken(failedAccessToken)

            refreshAccessTokenUnlocked()
        }

    /**
     * Invalidate a token rejected for a known missing-scope authorization error.
     * Returns false when the failure belongs to an older token that another
     * request has already replaced.
     */
    suspend fun invalidateAuthorizationForAccessToken(failedAccessToken: String): Boolean =
        authOperationMutex.withLock {
            val currentAccessToken = currentScopedAccessToken()
            if (!failedTokenIsStillCurrent(failedAccessToken, currentAccessToken)) {
                Log.d(TAG, "Ignoring stale Drive authorization failure for a replaced token")
                return@withLock false
            }

            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            if (failedTokenIsStillCurrent(failedAccessToken, tokenStorage.getAccessToken())) {
                tokenStorage.clearToken()
            }
            clearCachedAccessToken(failedAccessToken)
            true
        }

    /**
     * Drops a rejected access token from the Play Services authorization cache.
     * Clearing credential state (sign out) does not touch this cache, which is why a
     * rejected token otherwise survives a full sign-out / sign-in cycle.
     */
    private suspend fun clearCachedAccessToken(accessToken: String) {
        if (accessToken.isBlank()) return
        try {
            authorizationClient.clearToken(
                ClearTokenRequest.builder().setToken(accessToken).build()
            ).await()
            Log.i(TAG, "Cleared rejected access token from the Google authorization cache")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clear the rejected access token from the cache", e)
        }
    }

    /**
     * Marks the current session unusable after Drive rejected a freshly refreshed
     * token. Unlike a transient refresh failure this is definitive, so the UI must
     * stop claiming the account is connected and offer the sign-in instead of
     * showing a "Sign Out" button next to a dead session.
     */
    fun markSessionRejected() {
        Log.w(TAG, "Drive rejected the authorized token; marking the session as signed out")
        _isSignedIn.value = false
        authorizationResult = null
        authorizationResolution = null
    }

    private fun currentScopedAccessToken(): String? {
        val inMemory = authorizationResult
        val hasDriveScope = inMemory?.grantedScopes.orEmpty()
            .any { it.contains(DriveScopes.DRIVE_FILE) }
        return if (inMemory != null && !inMemory.hasResolution() && hasDriveScope) {
            inMemory.accessToken?.takeIf { it.isNotBlank() }
        } else {
            tokenStorage.getAccessToken()
        }
    }

    private suspend fun refreshAccessTokenUnlocked(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Refreshing access token")
            
            // Re-authorize to get a fresh token. Clear the old result before
            // awaiting so an exception cannot leave a stale token readable.
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                Log.d(TAG, "Refresh requires user consent")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResolution = null
                _needsDriveConsent.value = false
                val accessToken = authorizationResult?.accessToken
                val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
                val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
                val hasToken = accessToken != null && hasDriveScope
                Log.i(TAG, "Token refreshed successfully: $hasToken (drive.file scope: $hasDriveScope)")
                
                // Persist the refreshed token
                if (accessToken != null && hasDriveScope) {
                    tokenStorage.saveAccessToken(accessToken)
                    Log.d(TAG, "Refreshed token persisted to secure storage")
                } else {
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                }
                
                hasToken
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Refresh failed (${e.status.statusCode}: ${e.status.statusMessage})", e)
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.e(TAG, "Failed to refresh token", e)
            false
        }
    }
    
    suspend fun signOut() = withContext(Dispatchers.IO + NonCancellable) {
        authOperationMutex.withLock { signOutUnlocked() }
    }

    private suspend fun signOutUnlocked() = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "Signing out")

        // Credential Manager cleanup is best effort. It must not gate the local
        // credential/token cleanup below, otherwise one provider exception can
        // leave Tempo looking signed in with stale Drive credentials.
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager cleanup failed during sign out", e)
        }

        try {
            tokenStorage.clearAll()
            Log.d(TAG, "Cleared persisted tokens from secure storage")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted Google credentials", e)
        } finally {
            authorizationResult = null
            authorizationResolution = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
        }

        Log.i(TAG, "Finished Google sign out cleanup")
    }
    
    /** Silently restore the last authorized account at launch. Needs an Activity for Credential Manager UI. */
    suspend fun restoreSession(activity: Activity): Boolean =
        authOperationMutex.withLock { restoreSessionUnlocked(activity) }

    private suspend fun restoreSessionUnlocked(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId()
        if (webClientId == null) {
            Log.w(TAG, "Skipping session restore: GOOGLE_WEB_CLIENT_ID is blank")
            return@withContext false
        }

        try {
            Log.d(TAG, "Attempting to restore session")
            
            val credentialManager = CredentialManager.create(activity)
            
            // Session restore intentionally uses GetGoogleIdOption so Credential
            // Manager can restrict the request to previously authorized accounts.
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore session", e)
            false
        }
    }
    
    /**
     * Silent restore for background operations (WorkManager), with no UI: account info comes
     * from encrypted storage, then re-authorization is attempted with Play Services.
     */
    suspend fun restoreSessionSilently(): Boolean =
        authOperationMutex.withLock { restoreSessionSilentlyUnlocked() }

    private suspend fun restoreSessionSilentlyUnlocked(): Boolean = withContext(Dispatchers.IO) {
        val activeAuthorization = authorizationResult
        val activeHasDriveScope = activeAuthorization?.grantedScopes.orEmpty()
            .any { it.contains(DriveScopes.DRIVE_FILE) }
        if (_isSignedIn.value && activeAuthorization?.accessToken != null && activeHasDriveScope) {
            Log.d(TAG, "Session already active in memory")
            return@withContext true
        }
        _isSignedIn.value = false

        // Never attempt background authorization against an implicit OAuth client.
        // Builds without the configured Web client ID must require an interactive,
        // correctly configured build instead of silently minting the wrong token.
        if (configuredWebClientId() == null) {
            Log.w(TAG, "Skipping silent session restore: GOOGLE_WEB_CLIENT_ID is blank")
            return@withContext false
        }
        
        val storageStatus = tokenStorage.getStorageStatus()
        Log.d(TAG, "Token storage status: $storageStatus")
        
        if (!tokenStorage.hasAccountInfo()) {
            Log.d(TAG, "No stored account info - user needs to sign in via UI")
            return@withContext false
        }
        
        // Restore account info to state
        val storedAccount = tokenStorage.getStoredAccount()
        if (storedAccount != null) {
            _currentAccount.value = storedAccount
            Log.d(TAG, "Restored Google account info from secure storage")
        }
        
        // Silent re-authorization can work here because Play Services caches the authorization
        try {
            Log.d(TAG, "Attempting silent re-authorization with Google Play Services")
            
            authorizationResult = null
            authorizationResolution = null
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            // Check if we got a token without needing user consent
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                _needsDriveConsent.value = true
                Log.d(TAG, "Re-authorization requires user consent - cannot complete silently")
                return@withContext false
            }
            authorizationResolution = null
            _needsDriveConsent.value = false
            
            val accessToken = authorizationResult?.accessToken
            val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
            val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
            if (accessToken != null && hasDriveScope) {
                // Successfully got a fresh token
                tokenStorage.saveAccessToken(accessToken)
                _isSignedIn.value = true
                Log.i(TAG, "Successfully restored the Google session silently")
                return@withContext true
            }
            
            Log.w(TAG, "Re-authorization completed but no drive.file-scoped access token received (scopes: $grantedScopes)")
            authorizationResult = null
            authorizationResolution = null
            tokenStorage.clearToken()
            return@withContext false
            
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            Log.w(
                TAG,
                "Silent re-authorization failed (${e.status.statusCode}: ${e.status.statusMessage})",
                e
            )

            // A resolution is an explicit instruction from Google that the user
            // must consent again. Do not mask it with a cached token.
            if (authorizationResolution != null) {
                tokenStorage.clearToken()
                return@withContext false
            }

            // For a non-consent Play Services failure, a still-valid token that
            // Tempo previously persisted only after verifying drive.file scope may
            // remain usable; Drive itself will reject it if Google invalidated it.
            if (tokenStorage.hasToken() && !tokenStorage.isTokenExpired()) {
                Log.d(TAG, "Using previously validated cached Drive token")
                _isSignedIn.value = true
                return@withContext true
            }
            return@withContext false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.w(TAG, "Silent re-authorization failed: ${e.message}", e)

            // Non-Google failures (for example a transient local Play Services
            // issue) may still use a non-expired token that was previously saved
            // only after verifying drive.file scope.
            if (tokenStorage.hasToken() && !tokenStorage.isTokenExpired()) {
                Log.d(TAG, "Using previously validated cached Drive token")
                _isSignedIn.value = true
                return@withContext true
            }
            return@withContext false
        }
    }
}
