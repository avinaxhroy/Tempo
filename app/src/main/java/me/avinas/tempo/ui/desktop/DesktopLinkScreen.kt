package me.avinas.tempo.ui.desktop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.TempoPrimary
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLinkScreen(
    onNavigateBack: () -> Unit,
    viewModel: DesktopLinkViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // ── Camera permission state ──────────────────────────────────────────────
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) viewModel.startScanning()
    }

    // ── Full-screen scanning mode — no Scaffold chrome ───────────────────────
    if (uiState.phase == PairingPhase.SCANNING) {
        DesktopScanningScreen(
            onQrDetected = viewModel::onQrScanned,
            onCancel = viewModel::cancelScanning
        )
        return
    }

    // ── Normal scrollable view ────────────────────────────────────────────────
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.desktop_link_title), color = Color.White)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (uiState.phase) {
                    PairingPhase.CHECKING -> {
                        Spacer(modifier = Modifier.height(64.dp))
                        CircularProgressIndicator(color = TempoPrimary)
                    }

                    PairingPhase.UNPAIRED -> {
                        UnpairedContent(
                            uiState = uiState,
                            onScanClick = {
                                if (cameraPermissionGranted) {
                                    viewModel.startScanning()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            onManualConnect = viewModel::connectManually
                        )
                    }

                    PairingPhase.PAIRED -> {
                        PairedContent(
                            uiState = uiState,
                            context = context,
                            onDisconnect = viewModel::unpair
                        )
                        // Desktop stats below the paired content
                        uiState.desktopStats?.let { stats ->
                            if (stats.totalScrobbles > 0) {
                                DesktopStatsSection(stats = stats)
                            }
                        }
                    }

                    PairingPhase.SCANNING -> Unit // handled above as an early return
                }

                uiState.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        color = TempoRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full-screen scanner overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DesktopScanningScreen(
    onQrDetected: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        QrScannerView(
            modifier = Modifier.fillMaxSize(),
            onQrDetected = onQrDetected
        )

        // Dimmed border outside the scanning frame
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // Scanning frame indicator
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.desktop_scan_instruction),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                ) {
                    Text(stringResource(R.string.desktop_cancel_scan))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UNPAIRED state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UnpairedContent(
    uiState: DesktopLinkUiState,
    onScanClick: () -> Unit,
    onManualConnect: (ip: String, port: String, token: String) -> Unit
) {
    // Info card
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = TempoPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.desktop_link_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.desktop_link_description),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = 18.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Scan button card
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = TempoPrimary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.desktop_scan_qr_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.desktop_scan_qr_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onScanClick,
                colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary),
                modifier = Modifier.fillMaxWidth(0.75f)
            ) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.desktop_scan_button))
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Manual entry (collapsible)
    ManualEntrySection(onConnect = onManualConnect)
}

@Composable
private fun ManualEntrySection(
    onConnect: (ip: String, port: String, token: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.desktop_manual_entry),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                DesktopTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = stringResource(R.string.desktop_ip_hint),
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(8.dp))
                DesktopTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = stringResource(R.string.desktop_port_hint),
                    keyboardType = KeyboardType.Number
                )
                Spacer(modifier = Modifier.height(8.dp))
                DesktopTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.desktop_token_hint),
                    monospace = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onConnect(ip, port, token) },
                    colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.desktop_connect))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PAIRED state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PairedContent(
    uiState: DesktopLinkUiState,
    context: Context,
    onDisconnect: () -> Unit
) {
    val session = uiState.session ?: return

    // Connected card
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (uiState.isServerRunning) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (uiState.isServerRunning)
                        stringResource(R.string.desktop_receiver_running, uiState.phonePort)
                    else
                        stringResource(R.string.desktop_receiver_stopped),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoRow(
                label = stringResource(R.string.desktop_connected_to),
                value = session.desktopIp?.let { "${it}:${session.desktopPort ?: "?"}" }
                    ?: stringResource(R.string.desktop_manual_entry)
            )
            Spacer(modifier = Modifier.height(6.dp))
            InfoRow(
                label = stringResource(R.string.desktop_phone_address),
                value = "${uiState.phoneIp}:${uiState.phonePort}",
                monospace = true
            )
            if (session.deviceName.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                InfoRow(
                    label = stringResource(R.string.desktop_device_name),
                    value = session.deviceName
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Token card (copy-able, desktop needs this to authenticate)
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            Text(
                text = stringResource(R.string.desktop_auth_token),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = session.authToken,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Tempo Token", session.authToken))
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = TempoPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.desktop_copy_code),
                    color = TempoPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    // Last sync card
    session.lastSeenMs?.let { lastSeen ->
        if (lastSeen > 0L) {
            Spacer(modifier = Modifier.height(16.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                variant = GlassCardVariant.LowProminence
            ) {
                InfoRow(
                    label = stringResource(R.string.desktop_last_sync),
                    value = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSeen))
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Disconnect button
    OutlinedButton(
        onClick = onDisconnect,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TempoRed),
        border = BorderStroke(1.dp, TempoRed.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Filled.LinkOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.desktop_disconnect))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Desktop Stats section (shown when paired and has data)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DesktopStatsSection(stats: DesktopStats) {
    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.desktop_stats_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Stats overview row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DesktopStatCard(
            label = stringResource(R.string.desktop_stats_total_scrobbles),
            value = stats.totalScrobbles.toString(),
            icon = Icons.Filled.MusicNote,
            modifier = Modifier.weight(1f)
        )
        DesktopStatCard(
            label = stringResource(R.string.desktop_stats_listening_time),
            value = formatListeningTime(stats.totalListeningTimeMs),
            icon = Icons.Filled.Schedule,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Last 7 days row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DesktopStatCard(
            label = stringResource(R.string.desktop_stats_7day_scrobbles),
            value = stats.last7DaysScrobbles.toString(),
            icon = Icons.Filled.DateRange,
            modifier = Modifier.weight(1f)
        )
        DesktopStatCard(
            label = stringResource(R.string.desktop_stats_7day_time),
            value = formatListeningTime(stats.last7DaysListeningTimeMs),
            icon = Icons.Filled.Timer,
            modifier = Modifier.weight(1f)
        )
    }

    // Top artist / track
    if (stats.topArtist != null || stats.topTrack != null) {
        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            variant = GlassCardVariant.LowProminence
        ) {
            Column {
                Text(
                    text = stringResource(R.string.desktop_stats_top_listened),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                stats.topArtist?.let { artist ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.desktop_stats_top_artist),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
                stats.topTrack?.let { track ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.desktop_stats_top_track),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = if (stats.topTrackArtist != null) "$track — ${stats.topTrackArtist}" else track,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    // Source breakdown
    if (stats.sourceBreakdown.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            variant = GlassCardVariant.LowProminence
        ) {
            Column {
                Text(
                    text = stringResource(R.string.desktop_stats_sources),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                stats.sourceBreakdown.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = source.source.removePrefix("desktop:"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${source.cnt}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TempoPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopStatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = TempoPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

private fun formatListeningTime(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helper composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    truncate: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(72.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (truncate) 1 else Int.MAX_VALUE,
            overflow = if (truncate) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DesktopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White.copy(alpha = 0.8f),
            focusedBorderColor = TempoPrimary,
            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            focusedLabelColor = TempoPrimary,
            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
            cursorColor = TempoPrimary
        ),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    )
}
