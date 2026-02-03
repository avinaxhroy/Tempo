package me.avinas.tempo.ui.spotify

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage
import me.avinas.tempo.data.spotify.SpotifyImportService
import me.avinas.tempo.data.spotify.SpotifyTopItemsService
import me.avinas.tempo.data.spotify.SpotifyHistoryReconstructionService
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
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val spotifyImportService: SpotifyImportService,
    private val spotifyTopItemsService: SpotifyTopItemsService,
    private val historyReconstructionService: SpotifyHistoryReconstructionService
) : ViewModel() {

    // Authentication state from auth manager
    val authState = authManager.authState
    
    // Pending auth state - true when user is in the middle of auth flow (went to browser)
    private val _isPendingAuth = MutableStateFlow(false)
    val isPendingAuth: StateFlow<Boolean> = _isPendingAuth.asStateFlow()
    
    // Import state for tracking progress
    private val _importState = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val importState: StateFlow<SpotifyImportState> = _importState.asStateFlow()

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
        _isPendingAuth.value = true
        return authManager.startLoginFlow()
    }
    
    /**
     * Check if there's a pending auth from a previous session.
     * Should be called when app resumes to detect returning from browser.
     */
    fun checkPendingAuth() {
        _isPendingAuth.value = tokenStorage.hasPendingAuth()
    }
    
    /**
     * Clear pending auth state (called when auth completes or fails).
     */
    fun clearPendingAuth() {
        _isPendingAuth.value = false
    }

    /**
     * Handle the OAuth callback from Spotify.
     * Should be called when the app receives the redirect URI.
     * 
     * Note: This method is not currently used in the normal auth flow.
     * SpotifyCallbackActivity handles the auth and passes success to MainActivity,
     * which then calls reconstructHistory() directly.
     * This method is kept for potential direct usage or testing.
     */
    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            val success = authManager.handleCallback(uri)
            Log.d("SpotifyViewModel", "handleAuthCallback: success=$success")
            if (success) {
                // Queue all tracks for Spotify enrichment via Repository
                enrichedMetadataRepository.queueAllForSpotifyEnrichment()
                _showConnectedMessage.value = true
                loadStats()
                
                // Note: reconstructHistory() is triggered from MainActivity after auth callback
                // to avoid duplicate calls and ensure proper lifecycle management
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
    
    /**
     * Import recent plays from Spotify.
     * This fetches the last ~50 tracks from Spotify's recently played endpoint.
     */
    fun importRecentPlays() {
        if (_importState.value is SpotifyImportState.Importing) return
        
        viewModelScope.launch {
            _importState.value = SpotifyImportState.Importing(0)
            
            try {
                val result = spotifyImportService.importRecentlyPlayed()
                if (result.isSuccess) {
                    _importState.value = SpotifyImportState.Success(result.eventsCreated)
                } else {
                    _importState.value = SpotifyImportState.Error(
                        result.errors.firstOrNull() ?: "Import failed"
                    )
                }
            } catch (e: Exception) {
                _importState.value = SpotifyImportState.Error(e.message ?: "Import failed")
            }
        }
    }
    
    /**
     * Clear the import state (after showing result to user).
     */
    fun clearImportState() {
        _importState.value = SpotifyImportState.Idle
    }
    
    // ==================== SPOTIFY TOP ITEMS (Library Population) ====================
    
    // Top items import state
    private val _topItemsImportState = MutableStateFlow<TopItemsImportState>(TopItemsImportState.Idle)
    val topItemsImportState: StateFlow<TopItemsImportState> = _topItemsImportState.asStateFlow()
    
    /**
     * Import user's top tracks and artists from Spotify.
     * This populates the library with high-quality metadata and genre information.
     * 
     * Note: Spotify is a DATA SOURCE. The imported data becomes part of the regular
     * library - no separate "Spotify Tops" section is shown.
     */
    fun importTopItems() {
        if (_topItemsImportState.value is TopItemsImportState.Importing) {
            Log.d("SpotifyViewModel", "importTopItems already in progress, skipping")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting importTopItems()")
        
        viewModelScope.launch {
            _topItemsImportState.value = TopItemsImportState.Importing(0, 100, "Starting...")
            
            val result = spotifyTopItemsService.importTopItems { current, total, message ->
                _topItemsImportState.value = TopItemsImportState.Importing(current, total, message)
            }
            
            if (result.isSuccess) {
                Log.i("SpotifyViewModel", "Top items import successful: ${result.tracksCreated} tracks, ${result.artistsCreated} artists, ${result.genresCollected} genres, ${result.listeningEventsCreated} listening events")
                _topItemsImportState.value = TopItemsImportState.Success(
                    tracksCreated = result.tracksCreated,
                    artistsCreated = result.artistsCreated,
                    genresCollected = result.genresCollected,
                    listeningEventsCreated = result.listeningEventsCreated
                )
            } else {
                Log.e("SpotifyViewModel", "Top items import failed: ${result.errors}")
                _topItemsImportState.value = TopItemsImportState.Error(
                    result.errors.firstOrNull() ?: "Import failed"
                )
            }
        }
    }
    
    /**
     * Clear the top items import state.
     */
    fun clearTopItemsImportState() {
        _topItemsImportState.value = TopItemsImportState.Idle
    }
    
    // ==================== HISTORY RECONSTRUCTION (Enhanced Import) ====================
    
    // History reconstruction state
    private val _reconstructionState = MutableStateFlow<HistoryReconstructionState>(HistoryReconstructionState.Idle)
    val reconstructionState: StateFlow<HistoryReconstructionState> = _reconstructionState.asStateFlow()
    
    // Track if background processing is running to prevent duplicate launches
    private var isBackgroundProcessingRunning = false
    
    // Track if import has completed this session to prevent re-running
    private var hasCompletedImportThisSession = false
    
    /**
     * Reconstruct listening history using the Hybrid "Real + Smart" approach.
     * 
     * This is the enhanced import that combines:
     * 1. Time Machine: Liked tracks with exact timestamps
     * 2. Artifact Hunter: "Your Top Songs 20XX" playlists
     * 3. Smart Mixer: Algorithmic distribution with circadian rhythm bias
     * 
     * Results in highly accurate historical data for stats.
     */
    fun reconstructHistory() {
        if (_reconstructionState.value is HistoryReconstructionState.Reconstructing) {
            Log.d("SpotifyViewModel", "History reconstruction already in progress, skipping")
            return
        }
        
        // Prevent re-running if already completed this session
        if (hasCompletedImportThisSession) {
            Log.d("SpotifyViewModel", "History reconstruction already completed this session, skipping")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting history reconstruction")
        
        viewModelScope.launch {
            _reconstructionState.value = HistoryReconstructionState.Reconstructing(
                progress = 0,
                total = 100,
                phase = "Initializing",
                message = "Starting history reconstruction..."
            )
            
            val result = historyReconstructionService.reconstructHistory { current, total, phase, message ->
                _reconstructionState.value = HistoryReconstructionState.Reconstructing(
                    progress = current,
                    total = total,
                    phase = phase,
                    message = message
                )
            }
            
            if (result.isSuccess) {
                Log.i("SpotifyViewModel", """
                    History reconstruction successful:
                    - Tracks: ${result.tracksCreated}
                    - Artists: ${result.artistsCreated}
                    - Events: ${result.listeningEventsCreated}
                    - Liked tracks used: ${result.likedTracksFound}
                    - Yearly playlists: ${result.yearlyPlaylistsFound}
                    - Top tracks: ${result.topTracksUsed}
                    - Pending playlists: ${result.pendingYearlyPlaylists.size}
                """.trimIndent())
                
                _reconstructionState.value = HistoryReconstructionState.Success(
                    tracksCreated = result.tracksCreated,
                    artistsCreated = result.artistsCreated,
                    listeningEventsCreated = result.listeningEventsCreated,
                    likedTracksFound = result.likedTracksFound,
                    yearlyPlaylistsFound = result.yearlyPlaylistsFound,
                    topTracksUsed = result.topTracksUsed
                )
                
                // Mark import as completed to prevent re-running
                hasCompletedImportThisSession = true
                
                // Process remaining yearly playlists in background if any
                if (result.hasPendingHistory && !isBackgroundProcessingRunning) {
                    Log.i("SpotifyViewModel", "Starting background processing of ${result.pendingYearlyPlaylists.size} remaining yearly playlists")
                    processRemainingYearlyPlaylists(result.pendingYearlyPlaylists)
                }
            } else {
                Log.e("SpotifyViewModel", "History reconstruction failed: ${result.errors}")
                _reconstructionState.value = HistoryReconstructionState.Error(
                    result.errors.firstOrNull() ?: "Reconstruction failed"
                )
            }
        }
    }
    
    /**
     * Clear the history reconstruction state.
     */
    fun clearReconstructionState() {
        _reconstructionState.value = HistoryReconstructionState.Idle
    }
    
    /**
     * Import with enhanced history reconstruction (recommended for new users).
     * This replaces importTopItems() when user wants the best accuracy.
     */
    fun importWithHistoryReconstruction() {
        if (_topItemsImportState.value is TopItemsImportState.Importing ||
            _reconstructionState.value is HistoryReconstructionState.Reconstructing) {
            Log.d("SpotifyViewModel", "Import already in progress")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting enhanced import with history reconstruction")
        
        viewModelScope.launch {
            // Use the enhanced reconstruction service
            reconstructHistory()
        }
    }
    
    /**
     * Background processing for remaining yearly playlists.
     * Called after initial import completes with the most recent playlists.
     * This processes older yearly playlists without blocking the user.
     */
    private fun processRemainingYearlyPlaylists(pendingPlaylists: List<SpotifyHistoryReconstructionService.PendingPlaylist>) {
        // Guard against duplicate launches
        if (isBackgroundProcessingRunning) {
            Log.d("SpotifyViewModel", "Background processing already running, skipping")
            return
        }
        
        isBackgroundProcessingRunning = true
        
        viewModelScope.launch {
            try {
                Log.i("SpotifyViewModel", "Background: Processing ${pendingPlaylists.size} pending yearly playlists")
                
                val backgroundResult = historyReconstructionService.processPendingYearlyPlaylists(pendingPlaylists)
                
                Log.i("SpotifyViewModel", """
                    Background processing completed:
                    - Tracks: ${backgroundResult.tracksCreated}
                    - Events: ${backgroundResult.eventsCreated}
                    - Playlists processed: ${pendingPlaylists.size}
                """.trimIndent())
                
                // Update the UI state with enriched totals if we're still showing success
                val currentState = _reconstructionState.value
                if (currentState is HistoryReconstructionState.Success && backgroundResult.eventsCreated > 0) {
                    _reconstructionState.value = currentState.copy(
                        tracksCreated = currentState.tracksCreated + backgroundResult.tracksCreated,
                        listeningEventsCreated = currentState.listeningEventsCreated + backgroundResult.eventsCreated,
                        yearlyPlaylistsFound = currentState.yearlyPlaylistsFound + pendingPlaylists.size
                    )
                }
                
            } catch (e: Exception) {
                Log.e("SpotifyViewModel", "Background playlist processing failed", e)
                // Don't update state to error - initial import was successful
            } finally {
                isBackgroundProcessingRunning = false
                Log.d("SpotifyViewModel", "Background processing finished")
            }
        }
    }
}

/**
 * Represents the state of a Spotify import operation.
 */
sealed class SpotifyImportState {
    object Idle : SpotifyImportState()
    data class Importing(val progress: Int) : SpotifyImportState()
    data class Success(val tracksImported: Int) : SpotifyImportState()
    data class Error(val message: String) : SpotifyImportState()
}

/**
 * State for Spotify top items import (library population).
 */
sealed class TopItemsImportState {
    object Idle : TopItemsImportState()
    data class Importing(val current: Int, val total: Int, val message: String) : TopItemsImportState()
    data class Success(
        val tracksCreated: Int, 
        val artistsCreated: Int, 
        val genresCollected: Int,
        val listeningEventsCreated: Int
    ) : TopItemsImportState()
    data class Error(val message: String) : TopItemsImportState()
}

/**
 * State for history reconstruction (enhanced import).
 * 
 * This is the Hybrid "Real + Smart" approach that combines:
 * - Time Machine: Liked tracks with exact timestamps
 * - Artifact Hunter: Yearly playlists for historical data
 * - Smart Mixer: Algorithmic distribution for realism
 */
sealed class HistoryReconstructionState {
    object Idle : HistoryReconstructionState()
    
    data class Reconstructing(
        val progress: Int,
        val total: Int,
        val phase: String,
        val message: String
    ) : HistoryReconstructionState()
    
    data class Success(
        val tracksCreated: Int,
        val artistsCreated: Int,
        val listeningEventsCreated: Int,
        val likedTracksFound: Int,
        val yearlyPlaylistsFound: Int,
        val topTracksUsed: Int
    ) : HistoryReconstructionState() {
        // Number of tracks with REAL timing data
        val tracksWithRealData: Int get() = likedTracksFound + yearlyPlaylistsFound
        
        val summary: String
            get() = buildString {
                if (listeningEventsCreated > 0) {
                    append("Created $listeningEventsCreated events from real data:\n")
                    if (likedTracksFound > 0) append("• $likedTracksFound liked songs (exact dates)\n")
                    if (yearlyPlaylistsFound > 0) append("• $yearlyPlaylistsFound tracks from yearly playlists\n")
                } else {
                    append("No listening history created.\n")
                }
                if (topTracksUsed > 0) {
                    append("\nLibrary populated with $topTracksUsed top tracks.")
                }
            }
        
        val hasListeningData: Boolean get() = listeningEventsCreated > 0
    }
    
    data class Error(val message: String) : HistoryReconstructionState()
}
