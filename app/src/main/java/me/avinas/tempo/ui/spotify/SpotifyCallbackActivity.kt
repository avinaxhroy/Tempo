package me.avinas.tempo.ui.spotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import me.avinas.tempo.MainActivity
import me.avinas.tempo.data.remote.spotify.SpotifyApi
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity that handles the Spotify OAuth callback.
 * 
 * This activity is launched when Spotify redirects back to the app
 * after user authentication. It handles the authorization code
 * and redirects to the main activity.
 * 
 * Registered in AndroidManifest.xml with intent filter for:
 * tempo://spotify-callback
 */
@AndroidEntryPoint
class SpotifyCallbackActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SpotifyCallback"
        const val EXTRA_SPOTIFY_AUTH_SUCCESS = "spotify_auth_success"
    }

    @Inject
    lateinit var authManager: SpotifyAuthManager
    
    @Inject
    lateinit var enrichedMetadataRepository: EnrichedMetadataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "SpotifyCallbackActivity created")
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        
        if (uri != null && isSpotifyCallback(uri)) {
            Log.d(TAG, "Received Spotify callback: $uri")
            
            // Process the callback using lifecycleScope to prevent memory leaks
            lifecycleScope.launch {
                val success = authManager.handleCallback(uri)
                Log.d(TAG, "Callback handled, success: $success")
                
                if (success) {
                    // Queue all existing tracks for Spotify enrichment
                    enrichedMetadataRepository.queueAllForSpotifyEnrichment()
                    
                    // Note: History reconstruction is triggered from MainActivity
                    // because it has a longer-lived viewModelScope that won't get cancelled
                    // Uses the "honest data only" approach with liked tracks + yearly playlists
                    Log.i(TAG, "Auth successful, history reconstruction will be triggered from MainActivity")
                }
                
                // Navigate back to main activity (will trigger reconstruction there)
                navigateToMain(success)
            }
        } else {
            Log.w(TAG, "Invalid callback URI: $uri")
            navigateToMain(false)
        }
    }

    private fun isSpotifyCallback(uri: Uri): Boolean {
        // Check if this is our Spotify redirect URI
        val expectedUri = Uri.parse(SpotifyApi.REDIRECT_URI)
        return uri.scheme == expectedUri.scheme && uri.host == expectedUri.host
    }

    private fun navigateToMain(authSuccess: Boolean) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SPOTIFY_AUTH_SUCCESS, authSuccess)
        }
        startActivity(mainIntent)
        finish()
    }
}
