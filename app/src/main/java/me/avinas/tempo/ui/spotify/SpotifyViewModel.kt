package me.avinas.tempo.ui.spotify

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Spotify integration screens.
 * Handles authentication flow, connection status, and enrichment stats.
 * 
 * Data Flow Pattern: Enrichment → Database → UI
 * - This ViewModel ONLY reads from database via Repository (never makes API calls)
 * - Spotify API calls are made by EnrichmentWorker in background
 * - UI always displays cached data from database
 */
@HiltViewModel
class SpotifyViewModel @Inject constructor(
    private val authManager: SpotifyAuthManager,
    private val tokenStorage: SpotifyTokenStorage,
    private val enrichedMetadataRepository: EnrichedMetadataRepository
) : ViewModel() {

    // Authentication state from auth manager
    val authState = authManager.authState

    // Stats state
    private val _spotifyStats = MutableStateFlow(SpotifyStats())
    val spotifyStats: StateFlow<SpotifyStats> = _spotifyStats.asStateFlow()

    // Show disconnect dialog
    private val _showDisconnectDialog = MutableStateFlow(false)
    val showDisconnectDialog: StateFlow<Boolean> = _showDisconnectDialog.asStateFlow()

    // Success message
    private val _showConnectedMessage = MutableStateFlow(false)
    val showConnectedMessage: StateFlow<Boolean> = _showConnectedMessage.asStateFlow()

    init {
        loadStats()
    }

    /**
     * Check if Spotify is currently connected.
     */
    fun isConnected(): Boolean = authManager.isConnected()

    /**
     * Get the connected user's display name.
     */
    fun getUserDisplayName(): String? = tokenStorage.getUserDisplayName()

    /**
     * Start the Spotify OAuth login flow.
     * Returns an Intent to launch the browser for authentication.
     */
    fun startLogin(): Intent {
        return authManager.startLoginFlow()
    }

    /**
     * Handle the OAuth callback from Spotify.
     * Should be called when the app receives the redirect URI.
     */
    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            val success = authManager.handleCallback(uri)
            if (success) {
                // Queue all tracks for Spotify enrichment via Repository
                enrichedMetadataRepository.queueAllForSpotifyEnrichment()
                _showConnectedMessage.value = true
                loadStats()
            }
        }
    }

    /**
     * Show the disconnect confirmation dialog.
     */
    fun showDisconnectConfirmation() {
        _showDisconnectDialog.value = true
    }

    /**
     * Dismiss the disconnect dialog.
     */
    fun dismissDisconnectDialog() {
        _showDisconnectDialog.value = false
    }

    /**
     * Disconnect from Spotify and clear all data.
     */
    fun disconnect() {
        viewModelScope.launch {
            // Clear all Spotify data from database via Repository
            enrichedMetadataRepository.clearAllSpotifyData()
            
            // Disconnect from auth manager (clears tokens)
            authManager.disconnect()
            
            _showDisconnectDialog.value = false
            loadStats()
        }
    }

    /**
     * Dismiss the connected success message.
     */
    fun dismissConnectedMessage() {
        _showConnectedMessage.value = false
    }

    /**
     * Load Spotify enrichment stats from database (cached data).
     * No API calls are made - stats are computed from locally stored enrichment data.
     */
    fun loadStats() {
        viewModelScope.launch {
            val enrichedCount = enrichedMetadataRepository.countTracksWithSpotifyFeatures()
            val pendingCount = enrichedMetadataRepository.countTracksPendingSpotifyEnrichment()
            
            _spotifyStats.value = SpotifyStats(
                enrichedTracksCount = enrichedCount,
                pendingTracksCount = pendingCount,
                isLoading = false
            )
        }
    }

    data class SpotifyStats(
        val enrichedTracksCount: Int = 0,
        val pendingTracksCount: Int = 0,
        val isLoading: Boolean = true
    )
}
