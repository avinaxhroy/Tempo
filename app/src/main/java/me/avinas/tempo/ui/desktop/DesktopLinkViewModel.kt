package me.avinas.tempo.ui.desktop

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.DesktopPairingSession
import me.avinas.tempo.desktop.DesktopPairingCallbackClient
import me.avinas.tempo.desktop.DesktopMdnsManager
import me.avinas.tempo.desktop.DesktopPairingManager
import me.avinas.tempo.desktop.DesktopQrData
import me.avinas.tempo.desktop.DesktopSatelliteServer
import me.avinas.tempo.utils.BatteryUtils
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
 * @param batteryLevel Current device battery level (0-100), or -1 if unavailable.
 * @param isBatteryCritical True if battery is ≤ 20% (desktop sync disabled).
 */
data class DesktopLinkUiState(
    val phase: PairingPhase = PairingPhase.CHECKING,
    val isServerRunning: Boolean = false,
    val phoneIp: String = "",
    val phonePort: Int = DesktopPairingManager.SERVER_PORT,
    val session: DesktopPairingSession? = null,
    val errorMessage: String? = null,
    val desktopStats: DesktopStats? = null,
    val batteryLevel: Int = -1,
    val isBatteryCritical: Boolean = false
)

/** Aggregated stats for plays received from the desktop satellite. */
data class DesktopStats(
    val totalPlays: Int = 0,
    val totalListeningTimeMs: Long = 0,
    val last7DaysPlays: Int = 0,
    val last7DaysListeningTimeMs: Long = 0,
    val topArtist: String? = null,
    val topTrack: String? = null,
    val topTrackArtist: String? = null,
    val sourceBreakdown: List<ListeningEventDao.SourceCount> = emptyList()
)

@HiltViewModel
class DesktopLinkViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val pairingManager: DesktopPairingManager,
    private val server: DesktopSatelliteServer,
    private val mdnsManager: DesktopMdnsManager,
    private val listeningEventDao: ListeningEventDao,
    private val pairingCallbackClient: DesktopPairingCallbackClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(DesktopLinkUiState())
    val uiState: StateFlow<DesktopLinkUiState> = _uiState.asStateFlow()

    init {
        // Start battery monitoring in the background
        startBatteryMonitoring()
        
        viewModelScope.launch {
            val existing = pairingManager.getActiveSession()
            val phoneIp = pairingManager.getLocalIpAddress()

            if (existing != null) {
                // Restore — restart the receiver if it isn't already running
                val canRunServer = !BatteryUtils.isCriticalBattery(appContext, forceRefresh = true)
                if (canRunServer && !server.isRunning) {
                    server.start(DesktopPairingManager.SERVER_PORT)
                }
                // Register mDNS so desktop can auto-discover this phone
                if (canRunServer) {
                    mdnsManager.register(DesktopPairingManager.SERVER_PORT)
                }
                _uiState.update {
                    it.copy(
                        phase = PairingPhase.PAIRED,
                        isServerRunning = server.isRunning,
                        phoneIp = phoneIp,
                        session = existing,
                        errorMessage = if (!canRunServer)
                            "Desktop sync is paused while battery is 20% or below."
                        else null
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
                        errorMessage = "That QR code is not valid. Scan the QR code shown in Tempo Desktop."
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
            _uiState.update { it.copy(errorMessage = "Please enter a valid IP address, port, and token.") }
            return
        }
        viewModelScope.launch {
            completePairing(
                DesktopQrData(
                    token = token.trim(),
                    ip = ip.trim(),
                    port = portInt
                )
            )
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
            if (BatteryUtils.isCriticalBattery(appContext, forceRefresh = true)) {
                _uiState.update {
                    it.copy(
                        phase = PairingPhase.UNPAIRED,
                        errorMessage = "Desktop sync is unavailable while battery is 20% or below. Charge your phone and try again."
                    )
                }
                return
            }

            val session = pairingManager.completePairingFromDesktopQr(qrData)
            server.start(DesktopPairingManager.SERVER_PORT)
            mdnsManager.register(DesktopPairingManager.SERVER_PORT)
            val phoneIp = pairingManager.getLocalIpAddress()

            if (qrData.ip != null && qrData.port != null) {
                pairingCallbackClient.confirmDesktopPairing(
                    qrData = qrData,
                    phoneIp = phoneIp,
                    phonePort = DesktopPairingManager.SERVER_PORT
                )
            }

            _uiState.update {
                it.copy(
                    phase = PairingPhase.PAIRED,
                    isServerRunning = server.isRunning,
                    phoneIp = phoneIp,
                    session = session,
                    errorMessage = if (!server.isRunning)
                        "Connected, but the sync receiver could not start. Go back and open this screen again."
                    else null
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    phase = PairingPhase.UNPAIRED,
                    errorMessage = "Could not connect to desktop: ${e.localizedMessage}"
                )
            }
        }
    }

    private fun loadDesktopStats() {
        viewModelScope.launch {
            try {
                val totalPlays = listeningEventDao.getDesktopPlayCount()
                val totalTime = listeningEventDao.getDesktopListeningTimeMs()
                val now = System.currentTimeMillis()
                val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L
                val last7Days = listeningEventDao.getDesktopPlayCountInRange(sevenDaysAgo, now)
                val last7DaysTime = listeningEventDao.getDesktopListeningTimeMsInRange(sevenDaysAgo, now)
                val topArtist = listeningEventDao.getDesktopTopArtist()
                val topTrack = listeningEventDao.getDesktopTopTrack()
                val sourceBreakdown = listeningEventDao.getDesktopSourceBreakdown()

                _uiState.update {
                    it.copy(
                        desktopStats = DesktopStats(
                            totalPlays = totalPlays,
                            totalListeningTimeMs = totalTime,
                            last7DaysPlays = last7Days,
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
        // background so the desktop can push plays even when the screen isn't open.
    }

    // ---------------------------------------------------------------------------
    // Battery Monitoring
    // ---------------------------------------------------------------------------

    /**
     * Polls battery state every 30 seconds to keep the UI accurate.
     * Starting/stopping the server on battery events is handled by
     * [DesktopSatelliteServer.startBatteryWatchdog], which runs for the whole
     * app lifetime and is not tied to this screen being open.
     */
    private fun startBatteryMonitoring() {
        viewModelScope.launch {
            while (true) {
                try {
                    val isCritical = BatteryUtils.isCriticalBattery(appContext, forceRefresh = true)
                    val batteryLevel = BatteryUtils.getBatteryLevel(appContext)
                    _uiState.update {
                        it.copy(
                            batteryLevel = batteryLevel,
                            isBatteryCritical = isCritical,
                            isServerRunning = server.isRunning,
                            errorMessage = if (isCritical && it.phase == PairingPhase.PAIRED)
                                "Desktop sync is paused while battery is 20% or below."
                            else if (!isCritical && it.errorMessage?.contains("battery", ignoreCase = true) == true)
                                null
                            else it.errorMessage
                        )
                    }
                } catch (e: Exception) {
                    // Silently ignore battery check errors; continue monitoring
                }
                delay(30_000L)
            }
        }
    }
}
