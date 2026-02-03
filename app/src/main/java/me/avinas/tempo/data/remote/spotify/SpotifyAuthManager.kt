package me.avinas.tempo.data.remote.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Manages Spotify OAuth 2.0 authentication using Authorization Code with PKCE flow.
 * 
 * PKCE (Proof Key for Code Exchange) is required for mobile apps as they cannot
 * securely store client secrets. This implementation follows Spotify's auth guide:
 * https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 * 
 * Flow:
 * 1. Generate code verifier (random string)
 * 2. Generate code challenge (SHA256 hash of verifier, base64url encoded)
 * 3. Open Spotify login in browser with code challenge
 * 4. User grants permission, Spotify redirects back with authorization code
 * 5. Exchange code + verifier for access token
 * 6. Store tokens securely, refresh when expired
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: SpotifyTokenStorage,
    @Named("spotify") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SpotifyAuthManager"
        
        // Client ID loaded from BuildConfig (set in local.properties)
        val CLIENT_ID: String = me.avinas.tempo.BuildConfig.SPOTIFY_CLIENT_ID
        
        // PKCE constants
        private const val CODE_VERIFIER_LENGTH = 64
        private const val CODE_CHALLENGE_METHOD = "S256"
        
        // Buffer time before token expiry to refresh (5 minutes)
        private const val TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000L
    }

    // Current authentication state
    sealed class AuthState {
        object NotConnected : AuthState()
        object Connecting : AuthState()
        data class Connected(val user: SpotifyUser?) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    // Mutex to prevent race condition during token refresh
    private val tokenRefreshMutex = Mutex()

    init {
        // Check if we have stored tokens on startup
        checkStoredTokens()
    }

    private fun checkStoredTokens() {
        val accessToken = tokenStorage.getAccessToken()
        if (accessToken != null) {
            _authState.value = AuthState.Connected(null)
        }
    }

    /**
     * Check if Spotify is currently connected and authenticated.
     */
    fun isConnected(): Boolean {
        return tokenStorage.getAccessToken() != null
    }

    /**
     * Get a valid access token, refreshing if necessary.
     * Returns null if not authenticated or refresh fails.
     * Uses mutex to prevent race condition when multiple coroutines call this simultaneously.
     */
    suspend fun getValidAccessToken(): String? = tokenRefreshMutex.withLock {
        val accessToken = tokenStorage.getAccessToken()
        val expiresAt = tokenStorage.getTokenExpiresAt()
        
        if (accessToken == null) {
            Log.d(TAG, "No access token stored")
            return@withLock null
        }

        // Check if token is expired or about to expire
        if (expiresAt != null && System.currentTimeMillis() > expiresAt - TOKEN_REFRESH_BUFFER_MS) {
            Log.d(TAG, "Token expired or expiring soon, refreshing...")
            return@withLock refreshAccessToken()
        }

        accessToken
    }

    /**
     * Start the Spotify OAuth login flow.
     * Opens the Spotify authorization page in the user's browser.
     */
    fun startLoginFlow(): Intent {
        _authState.value = AuthState.Connecting

        // Generate PKCE code verifier and challenge
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        
        // Generate random state for CSRF protection
        val state = generateCodeVerifier() // Reuse same generation logic
        
        // Store verifier and state in encrypted storage (persists across process death)
        tokenStorage.savePendingAuth(codeVerifier, state)

        // Build authorization URL with state parameter
        val authUrl = buildAuthorizationUrl(codeChallenge, state)
        
        Log.d(TAG, "Starting login flow with URL: $authUrl")
        
        return Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
    }

    /**
     * Handle the OAuth callback from Spotify.
     * Called when the app receives the redirect URI with the authorization code.
     * 
     * @param uri The callback URI containing the authorization code and state
     * @return true if authentication was successful
     */
    suspend fun handleCallback(uri: Uri): Boolean {
        Log.d(TAG, "Handling callback: $uri")

        // Check for errors
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val errorMessage = when (error) {
                "access_denied" -> "You cancelled the Spotify login"
                "invalid_scope" -> "Permission error, please try again"
                "server_error" -> "Spotify server error, please try again later"
                else -> "Authentication failed: $error"
            }
            Log.e(TAG, "Auth error: $error")
            _authState.value = AuthState.Error(errorMessage)
            tokenStorage.clearPendingAuth()
            return false
        }

        // Get authorization code
        val code = uri.getQueryParameter("code")
        if (code == null) {
            Log.e(TAG, "No authorization code in callback")
            _authState.value = AuthState.Error("No authorization code received")
            tokenStorage.clearPendingAuth()
            return false
        }
        
        // Get and validate state parameter (CSRF protection)
        val state = uri.getQueryParameter("state")
        if (state == null) {
            Log.e(TAG, "No state parameter in callback")
            _authState.value = AuthState.Error("Invalid authentication response")
            tokenStorage.clearPendingAuth()
            return false
        }

        // Get stored code verifier and validate state
        val pendingAuth = tokenStorage.getPendingAuth(state)
        if (pendingAuth == null) {
            Log.e(TAG, "No matching pending auth found or expired")
            _authState.value = AuthState.Error("Authentication expired or invalid")
            return false
        }

        // Exchange code for tokens
        return exchangeCodeForTokens(code, pendingAuth.codeVerifier)
    }

    /**
     * Exchange authorization code for access and refresh tokens.
     */
    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", SpotifyApi.REDIRECT_URI)
                    .add("code_verifier", codeVerifier)
                    .build()

                val request = Request.Builder()
                    .url(SpotifyApi.TOKEN_URL)
                    .post(formBody)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token")
                    val expiresIn = json.getInt("expires_in")
                    val scope = json.optString("scope")

                    // Calculate expiry timestamp
                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                    // Store tokens securely
                    tokenStorage.saveTokens(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAt = expiresAt,
                        scope = scope
                    )
                    
                    // Clear pending auth data
                    tokenStorage.clearPendingAuth()

                    Log.i(TAG, "Successfully authenticated with Spotify")
                    _authState.value = AuthState.Connected(null)
                    true
                } else {
                    Log.e(TAG, "Token exchange failed: ${response.code} - $responseBody")
                    _authState.value = AuthState.Error("Failed to get access token")
                    tokenStorage.clearPendingAuth()
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange error", e)
                _authState.value = AuthState.Error("Authentication error: ${e.message}")
                tokenStorage.clearPendingAuth()
                false
            }
        }
    }

    /**
     * Refresh the access token using the refresh token.
     */
    private suspend fun refreshAccessToken(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val refreshToken = tokenStorage.getRefreshToken()
                if (refreshToken == null) {
                    Log.e(TAG, "No refresh token available")
                    disconnect()
                    return@withContext null
                }

                val formBody = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()

                val request = Request.Builder()
                    .url(SpotifyApi.TOKEN_URL)
                    .post(formBody)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val newAccessToken = json.getString("access_token")
                    val newRefreshToken = json.optString("refresh_token", refreshToken)
                    val expiresIn = json.getInt("expires_in")

                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                    tokenStorage.saveTokens(
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken,
                        expiresAt = expiresAt,
                        scope = tokenStorage.getScope()
                    )

                    Log.i(TAG, "Successfully refreshed access token")
                    newAccessToken
                } else {
                    Log.e(TAG, "Token refresh failed: ${response.code} - $responseBody")
                    // If refresh fails with 400/401, the refresh token is invalid
                    if (response.code in listOf(400, 401)) {
                        disconnect()
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh error", e)
                null
            }
        }
    }

    /**
     * Disconnect from Spotify and clear all stored tokens.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from Spotify")
        tokenStorage.clearTokens()
        _authState.value = AuthState.NotConnected
    }

    /**
     * Build the authorization URL with PKCE parameters and state.
     */
    private fun buildAuthorizationUrl(codeChallenge: String, state: String): String {
        return Uri.parse(SpotifyApi.AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyApi.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", CODE_CHALLENGE_METHOD)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SpotifyApi.SCOPES)
            .build()
            .toString()
    }

    /**
     * Generate a random code verifier for PKCE.
     * Must be between 43-128 characters, using unreserved URI characters.
     */
    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Generate code challenge from code verifier using SHA256.
     * challenge = BASE64URL(SHA256(verifier))
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
