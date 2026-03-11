package me.avinas.tempo.ui.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.DesktopPairingSession
import me.avinas.tempo.desktop.DesktopMdnsManager
import me.avinas.tempo.desktop.DesktopPairingManager
import me.avinas.tempo.desktop.DesktopQrData
import me.avinas.tempo.desktop.DesktopSatelliteServer
import javax.inject.Inject

/** Which step of the pairing lifecycle the screen is currently in. */
enum class PairingPhase {
    /** Checking Room for an existing active session (brief loading state). */
    CHECKING,
    /** No active session. Showing info card and the Scan button. */
    UNPAIRED,
    /** Camera is live and scanning for a desktop QR code. */
    SCANNING,
    /** A session is active; the NanoHTTPD receiver is running. */
    PAIRED
}

/**
 * Immutable UI state for [DesktopLinkScreen].
 *
 * @param phase        Current step in the pairing lifecycle.
 * @param isServerRunning Whether the NanoHTTPD receiver is currently accepting connections.
 * @param phoneIp      This phone's local IP (shown to user as the address desktop pushes to).
 * @param phonePort    Port the NanoHTTPD server listens on.
 * @param session      The active [DesktopPairingSession], or null if unpaired.
 * @param errorMessage Transient error to display below the cards; null when no error.
 */
data class DesktopLinkUiState(
    val phase: PairingPhase = PairingPhase.CHECKING,
    val isServerRunning: Boolean = false,
    val phoneIp: String = "",
    val phonePort: Int = DesktopPairingManager.SERVER_PORT,
    val session: DesktopPairingSession? = null,
    val errorMessage: String? = null,
    val desktopStats: DesktopStats? = null
)

/** Aggregated stats for scrobbles received from the desktop satellite. */
data class DesktopStats(
    val totalScrobbles: Int = 0,
    val totalListeningTimeMs: Long = 0,
    val last7DaysScrobbles: Int = 0,
    val last7DaysListeningTimeMs: Long = 0,
    val topArtist: String? = null,
    val topTrack: String? = null,
    val topTrackArtist: String? = null,
    val sourceBreakdown: List<ListeningEventDao.SourceCount> = emptyList()
)

@HiltViewModel
class DesktopLinkViewModel @Inject constructor(
    private val pairingManager: DesktopPairingManager,
    private val server: DesktopSatelliteServer,
    private val mdnsManager: DesktopMdnsManager,
    private val listeningEventDao: ListeningEventDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DesktopLinkUiState())
    val uiState: StateFlow<DesktopLinkUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = pairingManager.getActiveSession()
            val phoneIp = pairingManager.getLocalIpAddress()

            if (existing != null) {
                // Restore — restart the receiver if it isn't already running
                if (!server.isRunning) {
                    server.start(DesktopPairingManager.SERVER_PORT)
                }
                // Register mDNS so desktop can auto-discover this phone
                mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                _uiState.update {
                    it.copy(
                        phase = PairingPhase.PAIRED,
                        isServerRunning = server.isRunning,
                        phoneIp = phoneIp,
                        session = existing
                    )
                }
                // Load desktop stats
                loadDesktopStats()
            } else {
                _uiState.update {
                    it.copy(phase = PairingPhase.UNPAIRED, phoneIp = phoneIp)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    /** Activate the camera scanner. */
    fun startScanning() {
        _uiState.update { it.copy(phase = PairingPhase.SCANNING, errorMessage = null) }
    }

    /** Cancel scanning and return to the UNPAIRED view. */
    fun cancelScanning() {
        _uiState.update { it.copy(phase = PairingPhase.UNPAIRED) }
    }

    /**
     * Called by [QrScannerView] when a QR code's raw text is decoded.
     *
     * Parses the Tempo Desktop QR payload, stores the session, and starts the receiver.
     * If the QR is not a valid Tempo Desktop code the user is shown an error and the
     * scanner is re-activated so they can try again.
     */
    fun onQrScanned(rawText: String) {
        viewModelScope.launch {
            val qrData = pairingManager.parseDesktopQrPayload(rawText)
            if (qrData == null) {
                _uiState.update {
                    it.copy(
                        phase = PairingPhase.UNPAIRED,
                        errorMessage = "Not a valid Tempo Desktop QR code. Please scan the QR shown by the desktop app."
                    )
                }
                return@launch
            }
            completePairing(qrData)
        }
    }

    /**
     * Manual-entry alternative: pair using IP, port and token typed by the user.
     */
    fun connectManually(ip: String, port: String, token: String) {
        val portInt = port.trim().toIntOrNull()
        if (ip.isBlank() || portInt == null || portInt <= 0 || token.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please fill in all fields with valid values.") }
            return
        }
        viewModelScope.launch {
            completePairing(DesktopQrData(ip = ip.trim(), port = portInt, token = token.trim()))
        }
    }

    /** Disconnect from the desktop and stop the receiver. */
    fun unpair() {
        viewModelScope.launch {
            mdnsManager.unregister()
            server.stop()
            pairingManager.deactivate()
            _uiState.update {
                it.copy(
                    phase = PairingPhase.UNPAIRED,
                    isServerRunning = false,
                    session = null,
                    errorMessage = null
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------------

    private suspend fun completePairing(qrData: DesktopQrData) {
        try {
            val session = pairingManager.completePairingFromDesktopQr(qrData)
            server.start(DesktopPairingManager.SERVER_PORT)
            mdnsManager.register(DesktopPairingManager.SERVER_PORT)
            _uiState.update {
                it.copy(
                    phase = PairingPhase.PAIRED,
                    isServerRunning = server.isRunning,
                    session = session,
                    errorMessage = if (!server.isRunning)
                        "Paired, but receiver failed to start. Try navigating away and back."
                    else null
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    phase = PairingPhase.UNPAIRED,
                    errorMessage = "Pairing failed: ${e.localizedMessage}"
                )
            }
        }
    }

    private fun loadDesktopStats() {
        viewModelScope.launch {
            try {
                val totalScrobbles = listeningEventDao.getDesktopScrobbleCount()
                val totalTime = listeningEventDao.getDesktopListeningTimeMs()
                val now = System.currentTimeMillis()
                val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L
                val last7Days = listeningEventDao.getDesktopScrobbleCountInRange(sevenDaysAgo, now)
                val last7DaysTime = listeningEventDao.getDesktopListeningTimeMsInRange(sevenDaysAgo, now)
                val topArtist = listeningEventDao.getDesktopTopArtist()
                val topTrack = listeningEventDao.getDesktopTopTrack()
                val sourceBreakdown = listeningEventDao.getDesktopSourceBreakdown()

                _uiState.update {
                    it.copy(
                        desktopStats = DesktopStats(
                            totalScrobbles = totalScrobbles,
                            totalListeningTimeMs = totalTime,
                            last7DaysScrobbles = last7Days,
                            last7DaysListeningTimeMs = last7DaysTime,
                            topArtist = topArtist?.artist,
                            topTrack = topTrack?.title,
                            topTrackArtist = topTrack?.artist,
                            sourceBreakdown = sourceBreakdown
                        )
                    )
                }
            } catch (_: Exception) {
                // Stats are non-critical — show paired UI even if stats fail to load
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT stop the server when the screen is left — it should keep running in the
        // background so the desktop can push scrobbles even when the screen isn't open.
    }
}
