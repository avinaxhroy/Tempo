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
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
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
 * Manages Google identity and the Drive scopes used by Tempo.
 *
 * Two least-privilege Drive scopes are requested together:
 * - drive.file: existing user-visible .tempo backups created by Tempo
 * - drive.appdata: hidden cross-device listening-history transport
 *
 * Keeping them in one authorization request is important because Google access
 * tokens are refreshed as a unit by Play services. A background refresh must not
 * silently drop one feature's permission while keeping the other.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStorage: GoogleDriveTokenStorage,
    private val historySyncSettings: DriveHistorySyncSettingsManager
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        // Keep these names aligned with DriveHistorySyncWorker. They live here as
        // strings deliberately so the authentication/data layer does not depend
        // on the worker package just to clean up scheduled work at sign-out.
        private const val HISTORY_SYNC_WORK_NAME = "drive_history_sync"
        private const val HISTORY_SYNC_MANUAL_WORK_NAME = "drive_history_sync_manual"

        private val REQUIRED_DRIVE_SCOPES = listOf(
            Scope(DriveScopes.DRIVE_FILE),
            Scope(DriveScopes.DRIVE_APPDATA)
        )
        private val REQUIRED_DRIVE_SCOPE_URIS = setOf(
            DriveScopes.DRIVE_FILE,
            DriveScopes.DRIVE_APPDATA
        )

        internal fun failedTokenIsStillCurrent(
            failedAccessToken: String,
            currentAccessToken: String?
        ): Boolean = failedAccessToken.isNotBlank() && failedAccessToken == currentAccessToken
    }

    private val authorizationClient = Identity.getAuthorizationClient(context)

    private val _currentAccount = MutableStateFlow<GoogleAccount?>(null)
    val currentAccount: StateFlow<GoogleAccount?> = _currentAccount.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _needsDriveConsent = MutableStateFlow(false)
    val needsDriveConsent: StateFlow<Boolean> = _needsDriveConsent.asStateFlow()

    @Volatile
    private var authorizationResult: AuthorizationResult? = null

    @Volatile
    private var authorizationResolution: PendingIntent? = null

    // Every authorization path mutates the same account/token state. Serializing
    // them prevents a background refresh or late HTTP failure from overwriting a
    // newer interactive sign-in, consent result, account switch, or sign-out.
    private val authOperationMutex = Mutex()

    private fun configuredWebClientId(): String? =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }

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
            val credentialManager = CredentialManager.create(activity)
            val googleSignInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleSignInOption)
                .build()
            handleSignInResponse(credentialManager.getCredential(activity, request))
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credential available for explicit sign-in", e)
            GoogleSignInResult.Error(
                "Google Sign-In is unavailable. Check Google Play services and your Google account settings, then try again.",
                e
            )
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google sign-in failed", e)
            GoogleSignInResult.Error("Sign-in failed: ${e.message}", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected Google sign-in error", e)
            GoogleSignInResult.Error("Unexpected error: ${e.message}", e)
        }
    }

    private suspend fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        if (credential !is CustomCredential) {
            return GoogleSignInResult.Error("Unexpected credential class: ${credential::class.java.name}")
        }

        val isGoogleIdCredential =
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
        if (!isGoogleIdCredential) {
            return GoogleSignInResult.Error("Unexpected credential type: ${credential.type}")
        }

        return try {
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
            // A newly-selected identity starts a new authorization boundary.
            // Clear the previous account's token before any consent/failure path
            // can return control to the UI.
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()

            tokenStorage.saveAccountInfo(account.email, account.displayName, account.photoUrl)
            _currentAccount.value = account
            _isSignedIn.value = true

            // Identity and Drive authorization are intentionally separate. Sign-in
            // succeeds even if the subsequent Drive consent still needs UI.
            requestDriveAuthorization()
            GoogleSignInResult.Success(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
            runCatching { tokenStorage.clearAll() }
            Log.e(TAG, "Failed to parse Google ID credential", e)
            GoogleSignInResult.Error("Failed to parse credential: ${e.message}", e)
        }
    }

    private suspend fun requestDriveAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            val result = authorizationClient.authorize(buildDriveAuthRequest()).await()
            authorizationResult = result
            if (result.hasResolution()) {
                authorizationResolution = result.pendingIntent
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(result)
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            if (authorizationResolution == null) tokenStorage.clearToken()
            Log.e(TAG, "Drive authorization failed (${e.status.statusCode})", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Drive authorization failed", e)
            false
        }
    }

    suspend fun completeConsentFlow(): Boolean =
        authOperationMutex.withLock { completeConsentFlowUnlocked() }

    private suspend fun completeConsentFlowUnlocked(): Boolean = withContext(Dispatchers.IO) {
        try {
            _needsDriveConsent.value = false
            authorizationResult = null
            authorizationResolution = null
            val result = authorizationClient.authorize(buildDriveAuthRequest()).await()
            authorizationResult = result
            if (result.hasResolution()) {
                authorizationResolution = result.pendingIntent
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(result)
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete Drive consent (${e.status.statusCode})", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete Drive consent", e)
            false
        }
    }

    private fun persistAuthorizedToken(result: AuthorizationResult?): Boolean {
        val accessToken = result?.accessToken
        val grantedScopes = result?.grantedScopes.orEmpty()
        val hasScopes = hasAllRequiredDriveScopes(grantedScopes)
        val valid = accessToken != null && hasScopes

        if (accessToken != null && hasScopes) {
            tokenStorage.saveAccessToken(accessToken, grantedScopes)
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.i(TAG, "Drive authorization granted for backup + appData sync")
        } else {
            authorizationResult = null
            authorizationResolution = null
            tokenStorage.clearToken()
            if (!hasScopes) {
                Log.w(
                    TAG,
                    "Drive authorization is missing one or more required least-privilege scopes. Granted: $grantedScopes"
                )
            }
        }
        return valid
    }

    private fun hasAllRequiredDriveScopes(grantedScopes: Collection<String>): Boolean =
        REQUIRED_DRIVE_SCOPE_URIS.all(grantedScopes::contains)

    fun getAuthorizationResult(): AuthorizationResult? = authorizationResult

    private fun buildDriveAuthRequest(): AuthorizationRequest {
        val webClientId = requireNotNull(configuredWebClientId()) {
            "GOOGLE_WEB_CLIENT_ID is required for Google Drive authorization"
        }
        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(REQUIRED_DRIVE_SCOPES)

        _currentAccount.value?.let { account ->
            builder.setAccount(Account(account.email, "com.google"))
        }
        builder.requestOfflineAccess(webClientId)
        return builder.build()
    }

    fun getDriveAuthorizationPendingIntent(): PendingIntent? =
        authorizationResolution ?: authorizationResult?.pendingIntent

    fun updateAuthorizationResult(result: AuthorizationResult) {
        authorizationResult = result
        authorizationResolution = result.pendingIntent.takeIf { result.hasResolution() }
        _needsDriveConsent.value = result.hasResolution()
        if (!result.hasResolution()) {
            persistAuthorizedToken(result)
        }
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val current = authorizationResult
            if (current != null && !current.hasResolution() &&
                hasAllRequiredDriveScopes(current.grantedScopes.orEmpty())
            ) {
                current.accessToken?.takeIf { it.isNotBlank() }?.let {
                    return@withContext it
                }
            }

            val persisted = tokenStorage.getAccessToken()
            if (persisted != null && tokenStorage.hasGrantedScopes(REQUIRED_DRIVE_SCOPE_URIS)) {
                if (tokenStorage.isTokenStale()) {
                    if (refreshAccessToken()) {
                        return@withContext tokenStorage.getAccessToken()
                    }
                    if (_needsDriveConsent.value || tokenStorage.isTokenExpired()) {
                        tokenStorage.clearToken()
                        return@withContext null
                    }
                }
                return@withContext persisted
            }
            if (persisted != null) tokenStorage.clearToken()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain Drive access token", e)
            null
        }
    }

    suspend fun refreshAccessToken(): Boolean =
        authOperationMutex.withLock { refreshAccessTokenUnlocked() }

    /**
     * Refresh after a concrete Drive request was rejected with HTTP 401.
     *
     * The HTTP response can arrive after another request has already rotated the
     * token. In that case the newer token is preserved and the caller can retry
     * immediately. Only the exact rejected token may be cleared.
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

            refreshAccessTokenUnlocked()
        }

    /**
     * Invalidate an authorization rejected for missing Drive scopes. Returns
     * false when the response belongs to an older token that has already been
     * replaced, so the caller can retry without damaging the new session.
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
            true
        }

    private fun currentScopedAccessToken(): String? {
        val inMemory = authorizationResult
        return if (inMemory != null && !inMemory.hasResolution() &&
            hasAllRequiredDriveScopes(inMemory.grantedScopes.orEmpty())
        ) {
            inMemory.accessToken?.takeIf { it.isNotBlank() }
        } else {
            tokenStorage.getAccessToken()
        }
    }

    private suspend fun refreshAccessTokenUnlocked(): Boolean = withContext(Dispatchers.IO) {
        try {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            val result = authorizationClient.authorize(buildDriveAuthRequest()).await()
            authorizationResult = result
            if (result.hasResolution()) {
                authorizationResolution = result.pendingIntent
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(result)
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            Log.w(TAG, "Drive token refresh failed (${e.status.statusCode})", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.e(TAG, "Drive token refresh failed", e)
            false
        }
    }

    fun invalidateAuthorization() {
        authorizationResult = null
        authorizationResolution = null
        _needsDriveConsent.value = false
    }

    fun clearPersistedAccessToken() {
        tokenStorage.clearToken()
    }

    suspend fun signOut() = withContext(Dispatchers.IO + NonCancellable) {
        authOperationMutex.withLock { signOutUnlocked() }
    }

    private suspend fun signOutUnlocked() = withContext(Dispatchers.IO + NonCancellable) {
        try {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager cleanup failed during sign-out", e)
        } finally {
            // Google sign-out is also a privacy boundary for the optional history
            // transport. Clear its opt-in/status and remove periodic/manual work
            // immediately so no background task keeps trying to use a signed-out
            // account. Local listening history and the stable device id remain.
            try {
                historySyncSettings.clearForSignOut()
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(HISTORY_SYNC_WORK_NAME)
                workManager.cancelUniqueWork(HISTORY_SYNC_MANUAL_WORK_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear Drive history sync state during sign-out", e)
            }

            tokenStorage.clearAll()
            authorizationResult = null
            authorizationResolution = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
        }
    }

    suspend fun restoreSession(activity: Activity): Boolean =
        authOperationMutex.withLock { restoreSessionUnlocked(activity) }

    private suspend fun restoreSessionUnlocked(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId() ?: return@withContext false
        try {
            val credentialManager = CredentialManager.create(activity)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            handleSignInResponse(credentialManager.getCredential(activity, request)) is GoogleSignInResult.Success
        } catch (e: NoCredentialException) {
            false
        } catch (e: GetCredentialCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore Google session", e)
            false
        }
    }

    /**
     * Background-safe restoration used by WorkManager. No UI is shown; if Google
     * needs fresh consent for drive.appdata after upgrading from an older Tempo
     * version, this returns false and the settings screen can request it later.
     */
    suspend fun restoreSessionSilently(): Boolean =
        authOperationMutex.withLock { restoreSessionSilentlyUnlocked() }

    private suspend fun restoreSessionSilentlyUnlocked(): Boolean = withContext(Dispatchers.IO) {
        val active = authorizationResult
        if (_isSignedIn.value && active?.accessToken != null &&
            hasAllRequiredDriveScopes(active.grantedScopes.orEmpty())
        ) return@withContext true
        _isSignedIn.value = false
        if (configuredWebClientId() == null || !tokenStorage.hasAccountInfo()) return@withContext false

        val storedAccount = tokenStorage.getStoredAccount() ?: return@withContext false
        _currentAccount.value = storedAccount

        try {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            val result = authorizationClient.authorize(buildDriveAuthRequest()).await()
            authorizationResult = result
            if (result.hasResolution()) {
                authorizationResolution = result.pendingIntent
                _needsDriveConsent.value = true
                return@withContext false
            }
            if (persistAuthorizedToken(result)) {
                _isSignedIn.value = true
                return@withContext true
            }
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            Log.w(TAG, "Silent Drive authorization failed (${e.status.statusCode})", e)
            if (authorizationResolution != null) {
                tokenStorage.clearToken()
                return@withContext false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.w(TAG, "Silent Drive authorization failed", e)
        }

        // A non-expired cached token can keep existing features alive, but after
        // this release tokens are normally refreshed with both required scopes.
        if (tokenStorage.hasToken() &&
            tokenStorage.hasGrantedScopes(REQUIRED_DRIVE_SCOPE_URIS) &&
            !tokenStorage.isTokenExpired()
        ) {
            _isSignedIn.value = true
            return@withContext true
        }
        false
    }
}
