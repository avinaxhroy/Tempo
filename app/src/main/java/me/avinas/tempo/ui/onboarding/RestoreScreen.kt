package me.avinas.tempo.ui.onboarding

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.applemusic.AppleMusicImportService
import me.avinas.tempo.data.drive.DriveBackupInfo
import me.avinas.tempo.data.drive.DriveRestoreResult
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import me.avinas.tempo.ui.applemusic.APPLE_MUSIC_IMPORT_MIME_TYPES
import me.avinas.tempo.ui.applemusic.AppleMusicGuideDialog
import me.avinas.tempo.ui.applemusic.AppleMusicImportUiState
import me.avinas.tempo.ui.applemusic.AppleMusicImportViewModel
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.TempoSnackbar
import me.avinas.tempo.ui.lastfm.LastFmUiState
import me.avinas.tempo.ui.lastfm.LastFmViewModel
import me.avinas.tempo.ui.onboarding.restore.ExpandableRestoreCard
import me.avinas.tempo.ui.onboarding.restore.RestoreHeader
import me.avinas.tempo.ui.onboarding.restore.RestoreSectionTitle
import me.avinas.tempo.ui.onboarding.restore.RestoreTopBar
import me.avinas.tempo.ui.settings.BackupRestoreViewModel
import me.avinas.tempo.ui.settings.DriveOperationState
import me.avinas.tempo.ui.spotify.SpotifyJsonImportUiState
import me.avinas.tempo.ui.spotify.SpotifyJsonImportViewModel
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.youtube.TakeoutGuideDialog
import me.avinas.tempo.ui.youtube.YouTubeMusicImportUiState
import me.avinas.tempo.ui.youtube.YouTubeMusicImportViewModel
import me.avinas.tempo.utils.FormatUtils.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
    lastFmViewModel: LastFmViewModel = hiltViewModel(),
    spotifyJsonImportViewModel: SpotifyJsonImportViewModel = hiltViewModel(),
    youTubeMusicImportViewModel: YouTubeMusicImportViewModel = hiltViewModel(),
    appleMusicImportViewModel: AppleMusicImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Track if we've shown the activity error to avoid spamming
    var activityErrorShown by remember { mutableStateOf(false) }

    // Guard against onFinish being called multiple times from different LaunchedEffect blocks.
    // Multiple paths (import success, Spotify success, Last.fm success, Start Fresh button) can
    // call onFinish concurrently; calling it more than once causes NavController to try to
    // transition a back-stack entry that no longer exists → IllegalStateException.
    var hasFinished by remember { mutableStateOf(false) }
    val safeOnFinish: () -> Unit = {
        if (!hasFinished) {
            hasFinished = true
            onFinish()
        }
    }

    // Single-expanded accordion — only one restore option open at a time,
    // keeps the step to ~1.5 screens of scroll instead of 4+.
    var expandedId by remember { mutableStateOf<String?>(null) }

    fun toggleExpanded(id: String) {
        expandedId = if (expandedId == id) null else id
    }

    // Handle system back press - block during active operations
    val driveOperation by viewModel.driveOperation.collectAsState()
    val importExportProgress by viewModel.importExportProgress.collectAsState()

    // Last.fm State
    val lastFmUiState by lastFmViewModel.uiState.collectAsState()
    val lastFmImportProgress by lastFmViewModel.importProgress.collectAsState()

    // Spotify JSON Import State
    val spotifyJsonImportUiState by spotifyJsonImportViewModel.uiState.collectAsState()
    val spotifyJsonImportState by spotifyJsonImportViewModel.importState.collectAsState()

    // YouTube Music Import State
    val youTubeMusicImportUiState by youTubeMusicImportViewModel.uiState.collectAsState()
    val youTubeMusicImportState by youTubeMusicImportViewModel.importState.collectAsState()

    // Apple Music Import State
    val appleMusicImportUiState by appleMusicImportViewModel.uiState.collectAsState()
    val appleMusicImportState by appleMusicImportViewModel.importState.collectAsState()

    val isOperationActive =
        driveOperation is DriveOperationState.Downloading ||
            driveOperation is DriveOperationState.Restoring ||
            driveOperation is DriveOperationState.Uploading ||
            importExportProgress != null ||
            lastFmUiState.isImporting ||
            lastFmUiState.isLoading ||
            spotifyJsonImportUiState is SpotifyJsonImportUiState.Importing ||
            youTubeMusicImportUiState is YouTubeMusicImportUiState.Importing ||
            appleMusicImportUiState is AppleMusicImportUiState.Importing

    androidx.activity.compose.BackHandler(enabled = !isOperationActive, onBack = onBack)

    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val driveBackups by viewModel.driveBackups.collectAsState()
    val importExportResult by viewModel.importExportResult.collectAsState()

    // Dialog States
    val conflictDialogUri by viewModel.showConflictDialog.collectAsState()
    val driveRestoreDialog by viewModel.showDriveRestoreDialog.collectAsState()

    // Error Handling State
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle Sign-In/Restore/Consent callbacks
    val signInRequested by viewModel.signInRequested.collectAsState()
    val consentRequested by viewModel.consentRequested.collectAsState()

    // Consent Flow Launcher
    val consentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            viewModel.onConsentComplete(result.resultCode == Activity.RESULT_OK)
        }

    LaunchedEffect(signInRequested) {
        if (signInRequested) {
            if (activity != null) {
                viewModel.onSignInReady(activity)
            } else {
                viewModel.cancelSignIn()
                if (!activityErrorShown) {
                    snackbarHostState.showSnackbar("Cannot sign in: Activity not available")
                    activityErrorShown = true
                }
            }
        }
    }

    // Handle Drive Consent Flow
    LaunchedEffect(consentRequested) {
        if (consentRequested) {
            val pendingIntent = viewModel.getDriveConsentPendingIntent()
            if (pendingIntent != null) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    consentLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to open consent screen: ${e.message}")
                    viewModel.onConsentComplete(false)
                }
            } else {
                // No pending intent means consent isn't actually needed
                viewModel.onConsentComplete(true)
            }
        }
    }

    // Success Handling - Finish onboarding on successful restore
    LaunchedEffect(importExportResult) {
        when (val result = importExportResult) {
            is ImportExportResult.Success -> {
                safeOnFinish() // Auto-finish on success
                viewModel.clearImportExportResult()
            }

            is ImportExportResult.Error -> {
                snackbarHostState.showSnackbar("Restore failed: ${result.message}")
                viewModel.clearImportExportResult()
            }

            else -> {}
        }
    }

    LaunchedEffect(driveOperation) {
        when (val op = driveOperation) {
            is DriveOperationState.Success -> {
                viewModel.clearDriveOperation()
            }

            is DriveOperationState.Error -> {
                snackbarHostState.showSnackbar("Drive error: ${op.message}")
                viewModel.clearDriveOperation()
            }

            else -> {}
        }
    }

    // Local Backup Picker
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { viewModel.startImport(it) }
        }

    // ponytail: no Scaffold — its content padding double-applies the system-bar
    // insets under edge-to-edge (every other onboarding screen skips Scaffold).
    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Fixed top bar — never scrolls away
            RestoreTopBar(
                onBack = onBack,
                onSkip = safeOnFinish,
                skipEnabled = !isOperationActive,
                backEnabled = !isOperationActive,
            )

            // Scrollable content
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                RestoreHeader(
                    title = "Bring your history",
                    subtitle = "Import from another service or restore a Tempo backup.",
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                RestoreSectionTitle(text = "Import from services")

                // Last.fm Import Card
                LastFmImportCard(
                    uiState = lastFmUiState,
                    importProgress = lastFmImportProgress,
                    onUsernameSubmit = lastFmViewModel::discoverUser,
                    onSelectTier = lastFmViewModel::startImportDirect,
                    onCancel = lastFmViewModel::reset,
                    onCancelImport = lastFmViewModel::cancelImport,
                    onClearError = lastFmViewModel::clearError,
                    onFinishImport = {
                        lastFmViewModel.reset()
                        safeOnFinish()
                    },
                    expanded = expandedId == "lastfm",
                    onExpandedChange = { open -> expandedId = if (open) "lastfm" else null },
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Spotify JSON Import Card
                SpotifyJsonImportCard(
                    uiState = spotifyJsonImportUiState,
                    importState = spotifyJsonImportState,
                    onImport = { uris ->
                        spotifyJsonImportViewModel.importFiles(context, uris)
                    },
                    onReset = {
                        spotifyJsonImportViewModel.resetState()
                    },
                    onFinish = {
                        spotifyJsonImportViewModel.resetState()
                        safeOnFinish()
                    },
                    expanded = expandedId == "spotify",
                    onExpandedChange = { open -> expandedId = if (open) "spotify" else null },
                )

                Spacer(modifier = Modifier.height(12.dp))

                // YouTube Music Import Card
                YouTubeMusicImportCard(
                    uiState = youTubeMusicImportUiState,
                    importState = youTubeMusicImportState,
                    onImport = { uris ->
                        youTubeMusicImportViewModel.importFiles(context, uris)
                    },
                    onReset = {
                        youTubeMusicImportViewModel.resetState()
                    },
                    onFinish = {
                        youTubeMusicImportViewModel.resetState()
                        safeOnFinish()
                    },
                    expanded = expandedId == "youtube",
                    onExpandedChange = { open -> expandedId = if (open) "youtube" else null },
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Apple Music Import Card
                AppleMusicImportCard(
                    uiState = appleMusicImportUiState,
                    importState = appleMusicImportState,
                    onImport = { uri ->
                        appleMusicImportViewModel.importFile(context, uri)
                    },
                    onReset = {
                        appleMusicImportViewModel.resetState()
                    },
                    onFinish = {
                        appleMusicImportViewModel.resetState()
                        safeOnFinish()
                    },
                    expanded = expandedId == "apple",
                    onExpandedChange = { open -> expandedId = if (open) "apple" else null },
                )

                Spacer(modifier = Modifier.height(24.dp))

                RestoreSectionTitle(text = "Restore backup")

                // Option 1: Google Drive (same header pattern as import cards)
                ExpandableRestoreCard(
                    title = "Google Drive",
                    subtitle =
                        if (isSignedIn && driveBackups.isNotEmpty()) {
                            "${driveBackups.size} backup${if (driveBackups.size == 1) "" else "s"} found"
                        } else {
                            "Restore from the cloud"
                        },
                    accent = Color(0xFF4285F4),
                    icon = Icons.Default.Cloud,
                    expanded = expandedId == "drive",
                    onToggle = { toggleExpanded("drive") },
                    interactionEnabled = !isOperationActive,
                ) {
                    if (!isSignedIn) {
                        Text(
                            text = "Sign in to list backups from your Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.requestSignIn() },
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4285F4),
                                    contentColor = Color.White,
                                ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Connect Google Account", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        if (driveOperation is DriveOperationState.Loading || driveOperation is DriveOperationState.SigningIn) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (driveBackups.isNotEmpty()) {
                            Column {
                                driveBackups.forEachIndexed { index, backup ->
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.startDriveRestore(backup) }
                                                .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text =
                                                    SimpleDateFormat(
                                                        "MMM dd, yyyy",
                                                        Locale.getDefault(),
                                                    ).format(Date(backup.createdAt)),
                                                color = Color.White,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp),
                                            )
                                            Text(
                                                text = formatBytes(backup.sizeBytes) + (backup.deviceName?.let { " • $it" } ?: ""),
                                                color = Color.White.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = adaptiveTextUnitByCategory(14.sp, 13.sp, 12.sp),
                                            )
                                        }
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = "Restore",
                                            tint = TempoPrimary,
                                        )
                                    }
                                    if (index < driveBackups.lastIndex) {
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No backups found on Drive.",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: Local Backup (same header pattern, explicit button)
                ExpandableRestoreCard(
                    title = "Restore from File",
                    subtitle = "Select a .zip backup file",
                    accent = Color.White,
                    icon = Icons.Default.FolderOpen,
                    expanded = expandedId == "local",
                    onToggle = { toggleExpanded("local") },
                    interactionEnabled = !isOperationActive,
                ) {
                    Text(
                        text = "Pick a .zip backup file previously exported from Tempo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                contentColor = Color.White,
                            ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select backup file", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Slim dismissible note — imports/restore stay available in Settings.
                var showLaterNote by remember { mutableStateOf(true) }
                if (showLaterNote) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "You can do this later from Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { showLaterNote = false },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.04f)))
            }

            // Pinned CTA — same rect as every other step.
            OnboardingFooter(
                text = "Start Tracking",
                onClick = safeOnFinish,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
            snackbar = { TempoSnackbar(it) },
        )
    }

    // Dialogs

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (progress.phase.contains("Import")) "Restoring..." else "Processing...",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(progress.phase, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (progress.isIndeterminate || progress.total <= 0) {
                        CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    // Drive Downloading Dialog
    if (driveOperation is DriveOperationState.Downloading || driveOperation is DriveOperationState.Restoring) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Restoring from Cloud...", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val downloadingState = driveOperation as? DriveOperationState.Downloading
                    if (downloadingState != null) {
                        val currentProgress = downloadingState.progress
                        LinearProgressIndicator(
                            progress = { currentProgress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft,
                        )
                    } else {
                        CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                    }
                }
            },
            confirmButton = {},
        )
    }

    // Local Conflict Resolution
    conflictDialogUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Restore Options", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = { Text("How should we handle data conflicts?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }) {
                    Text("Replace Everything", color = TempoPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("Cancel", color = TextTertiary)
                    }
                    TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }) {
                        Text("Skip Duplicates", color = TextSecondary)
                    }
                }
            },
        )
    }

    // Drive Restore Confirmation
    driveRestoreDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDriveRestore() },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = TempoPrimary) },
            title = { Text("Restore this backup?", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "Date: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backup.createdAt))}",
                        color = TextSecondary,
                    )
                    Text("Size: ${formatBytes(backup.sizeBytes)}", color = TextTertiary)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.REPLACE) }) {
                    Text("Restore", color = TempoPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDriveRestore() }) {
                    Text("Cancel", color = TextTertiary)
                }
            },
        )
    }
}

/**
 * Last.fm Import Card for onboarding
 * Compact card that handles the full Last.fm import flow inline
 */
@Composable
fun LastFmImportCard(
    uiState: LastFmUiState,
    importProgress: LastFmImportService.ImportProgress,
    onUsernameSubmit: (String) -> Unit,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onCancel: () -> Unit,
    onCancelImport: () -> Unit,
    onClearError: () -> Unit,
    onFinishImport: () -> Unit,
    modifier: Modifier = Modifier,
    // ponytail: hoisted accordion state; null = self-managed (legacy callers)
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val lastFmPurple = Color(0xFF8B5CF6) // Last.fm red
    var username by remember { mutableStateOf("") }
    var internalExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded

    fun setExpanded(value: Boolean) {
        if (expanded == null) {
            internalExpanded = value
        } else {
            onExpandedChange?.invoke(value)
        }
    }

    // Reset username when state is reset (e.g., when going back from tier selection)
    LaunchedEffect(uiState.discoveryResult, uiState.showTierSelection) {
        if (uiState.discoveryResult == null && !uiState.showTierSelection && !uiState.isImporting) {
            username = ""
        }
    }

    // Auto-expand when there's discovery result or import in progress
    LaunchedEffect(uiState.discoveryResult, uiState.isImporting, uiState.importResult) {
        if (uiState.discoveryResult != null || uiState.isImporting || uiState.importResult != null) {
            setExpanded(true)
        }
    }

    // Handle successful import - don't auto-finish, let user click Continue
    // This avoids race conditions with the button click

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            // Header row - always visible
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!uiState.isImporting && !uiState.isLoading) {
                                setExpanded(!isExpanded)
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                            .background(lastFmPurple.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lastfm),
                        contentDescription = "Last.fm",
                        tint = lastFmPurple,
                        modifier =
                            Modifier
                                .size(24.dp)
                                .offset(y = (-1).dp), // Visual correction
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last.fm",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Import years of listening history",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }

                // Expand/collapse indicator
                if (!uiState.isImporting && !uiState.isLoading) {
                    Icon(
                        imageVector =
                            if (isExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            // Expandable content — clip-reveal in place, same as ExpandableRestoreCard
            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    expandVertically(expandFrom = Alignment.Top, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(220)),
                exit =
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(160)),
            ) {
                Column {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    when {
                        // Import complete
                        uiState.importResult != null -> {
                            LastFmImportComplete(
                                result = uiState.importResult!!,
                                onDone = onFinishImport,
                            )
                        }

                        // Import in progress
                        uiState.isImporting -> {
                            LastFmImportProgress(
                                progress = importProgress,
                                onCancel = onCancelImport,
                            )
                        }

                        // Tier selection
                        uiState.showTierSelection && uiState.discoveryResult != null -> {
                            LastFmTierSelection(
                                discovery = uiState.discoveryResult!!,
                                onSelectTier = onSelectTier,
                                onBack = onCancel,
                            )
                        }

                        // Loading/discovering
                        uiState.isLoading -> {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = lastFmPurple,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Discovering your account...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }

                        // Username input (default state)
                        else -> {
                            LastFmUsernameInput(
                                username = username,
                                onUsernameChange = { newValue ->
                                    username = newValue
                                    if (uiState.error != null) onClearError()
                                },
                                error = uiState.error,
                                onSubmit = { onUsernameSubmit(username.trim()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastFmUsernameInput(
    username: String,
    onUsernameChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Last.fm Username") },
            placeholder = { Text("Enter your username") },
            singleLine = true,
            isError = error != null,
            supportingText =
                if (error != null) {
                    { Text(error, color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    focusedLabelColor = Color(0xFF8B5CF6),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                    cursorColor = Color(0xFF8B5CF6),
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSubmit,
            enabled = username.isNotBlank(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6),
                    disabledContainerColor = Color(0xFF8B5CF6).copy(alpha = 0.5f),
                ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Connect", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LastFmTierSelection(
    discovery: LastFmImportService.DiscoveryResult,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onBack: () -> Unit,
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }

    Column(
        modifier = Modifier.padding(16.dp),
    ) {
        Text(
            text = "Welcome, ${discovery.username}!",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "${numberFormat.format(discovery.totalScrobbles)} total scrobbles",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your complete history is imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
        Text(
            text = "Choose which tracks power your leaderboards:",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tier options - using new Quick/Standard/Deep system
        LastFmTierOption(
            name = "Quick",
            description = "Recent 3 months in leaderboards",
            icon = Icons.Default.Bolt,
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.QUICK) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        LastFmTierOption(
            name = "Standard",
            description = "Last year + top tracks in leaderboards",
            icon = Icons.Default.Star,
            isRecommended = true, // Standard is recommended for most users
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.STANDARD) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        LastFmTierOption(
            name = "Deep",
            description = "Last 2 years + more tracks in leaderboards",
            icon = Icons.Default.BarChart,
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.DEEP) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Explanation text - user-friendly
        Text(
            text = "All your tracks are saved. This just affects chart speed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = "Use different account",
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun LastFmTierOption(
    name: String,
    description: String,
    icon: ImageVector,
    isRecommended: Boolean,
    onClick: () -> Unit,
) {
    // ponytail: vector icon in a tinted disc, not emoji — emoji is the cheap
    // tell. Haptic + press scale via premiumClickable idiom would need a
    // wrapper; Button-like scale here is enough with haptic on tap.
    val haptic = LocalHapticFeedback.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = if (isRecommended) Color(0xFF8B5CF6).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                ).clickable(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                })
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(16.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }

        if (isRecommended) {
            Box(
                modifier =
                    Modifier
                        .background(
                            color = Color(0xFF8B5CF6).copy(alpha = 0.3f),
                            shape = RoundedCornerShape(4.dp),
                        ).padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8B5CF6),
                )
            }
        }
    }
}

@Composable
private fun LastFmImportProgress(
    progress: LastFmImportService.ImportProgress,
    onCancel: () -> Unit,
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    val lastFmRed = Color(0xFF8B5CF6)

    // Extract progress details including tier info
    val progressData =
        when (progress) {
            is LastFmImportService.ImportProgress.Importing -> {
                val percent = if (progress.total > 0) ((progress.current * 100) / progress.total).toInt() else 0
                ProgressData(
                    percent = percent,
                    phase = progress.phase,
                    current = progress.current,
                    total = progress.total,
                    eventsCreated = progress.eventsCreated,
                    archived = progress.archived,
                    isEverythingTier = progress.tierName == "EVERYTHING",
                )
            }

            is LastFmImportService.ImportProgress.Discovering -> {
                ProgressData(0, "Discovering", 0, 0, 0, 0, false)
            }

            is LastFmImportService.ImportProgress.Processing -> {
                ProgressData(0, "Processing", 0, 0, 0, 0, false)
            }

            else -> {
                ProgressData(0, "Preparing", 0, 0, 0, 0, false)
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Circular progress with percentage
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { (progressData.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                color = lastFmRed,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 6.dp,
            )

            Text(
                text = "${progressData.percent}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Phase indicator
        Text(
            text = progressData.phase,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )

        // Progress text
        if (progressData.total > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${numberFormat.format(progressData.current)} of ${numberFormat.format(progressData.total)} scrobbles",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This may take a few minutes",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live stats row - show different labels based on tier
        if (progressData.isEverythingTier) {
            // Full import: just show total imported
            ImportStatItem(
                label = "Imported",
                value = numberFormat.format(progressData.eventsCreated),
                color = Color(0xFF4CAF50),
            )
        } else {
            // Tiered import: show active vs archived breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ImportStatItem(
                    label = "Active",
                    value = numberFormat.format(progressData.eventsCreated),
                    color = Color(0xFF4CAF50), // Green for active tracks
                )
                ImportStatItem(
                    label = "Archived",
                    value = numberFormat.format(progressData.archived),
                    color = Color(0xFF9E9E9E), // Gray for archived
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        TextButton(onClick = onCancel) {
            Text(
                text = "Cancel",
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

/** Data class for progress extraction */
private data class ProgressData(
    val percent: Int,
    val phase: String,
    val current: Long,
    val total: Long,
    val eventsCreated: Long,
    val archived: Long,
    val isEverythingTier: Boolean,
)

@Composable
private fun ImportStatItem(
    label: String,
    value: String,
    color: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun LastFmImportComplete(
    result: LastFmImportService.ImportResult,
    onDone: () -> Unit,
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF27AE60),
            modifier = Modifier.size(48.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Import Complete!",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )

        Text(
            // A re-run whose scrobbles are already in the library must not read as a failed
            // import of "0 scrobbles".
            text =
                when {
                    result.totalImported > 0 -> "${numberFormat.format(result.totalImported)} scrobbles imported"

                    result.alreadyImported -> "Nothing new — your history is already in Tempo"

                    else -> "No new scrobbles found this time"
                },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )

        if (result.skippedPages > 0) {
            // The import reported success but left holes; say so here too, otherwise this screen
            // tells the user their history is complete when pages were dropped.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "${result.skippedPages} page(s) of history could not be fetched and were " +
                        "skipped. Re-run the import to fill the gaps.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE74C3C),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDone,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6),
                ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SpotifyJsonImportCard(
    uiState: SpotifyJsonImportUiState,
    importState: SpotifyJsonImportService.ImportState,
    onImport: (List<Uri>) -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    // ponytail: hoisted accordion state; null = self-managed (legacy callers)
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val spotifyGreen = Color(0xFF1DB954)
    var internalExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded

    fun setExpanded(value: Boolean) {
        if (expanded == null) {
            internalExpanded = value
        } else {
            onExpandedChange?.invoke(value)
        }
    }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            selectedUris = uris
            if (uris.isNotEmpty()) {
                setExpanded(true)
            }
        }

    LaunchedEffect(uiState) {
        if (uiState is SpotifyJsonImportUiState.Importing || uiState is SpotifyJsonImportUiState.Completed) {
            setExpanded(true)
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (uiState !is SpotifyJsonImportUiState.Importing) {
                                setExpanded(!isExpanded)
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                            .background(spotifyGreen.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.spotify),
                        contentDescription = null,
                        modifier = Modifier.size(adaptiveSizeByCategory(28.dp, 26.dp, 24.dp)),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Spotify Data Export",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Import from JSON files",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }

                if (uiState !is SpotifyJsonImportUiState.Importing) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    expandVertically(expandFrom = Alignment.Top, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(220)),
                exit =
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(160)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (uiState) {
                        is SpotifyJsonImportUiState.Idle -> {
                            Text(
                                text =
                                    buildAnnotatedString {
                                        append("Get your data from ")
                                        withLink(
                                            LinkAnnotation.Url(
                                                url = "https://spotify.com/account/privacy",
                                                styles =
                                                    TextLinkStyles(
                                                        style =
                                                            SpanStyle(
                                                                color = Color(0xFF1DB954),
                                                                textDecoration = TextDecoration.Underline,
                                                            ),
                                                    ),
                                            ),
                                        ) {
                                            append("spotify.com/account/privacy")
                                        }
                                        append(", then select the JSON files. You can also do this later from Settings.")
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select JSON Files")
                            }

                            if (selectedUris.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${selectedUris.size} file(s) selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = spotifyGreen,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { onImport(selectedUris) },
                                    colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Start Import", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        is SpotifyJsonImportUiState.Importing -> {
                            val (message, progress) =
                                when (importState) {
                                    is SpotifyJsonImportService.ImportState.Parsing -> {
                                        "Parsing ${importState.fileName}..." to
                                            (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                                    }

                                    is SpotifyJsonImportService.ImportState.Importing -> {
                                        "Importing ${importState.current}/${importState.total}" to
                                            (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                                    }

                                    else -> {
                                        "Preparing..." to 0f
                                    }
                                }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = spotifyGreen,
                                trackColor = Color.White.copy(alpha = 0.1f),
                            )
                        }

                        is SpotifyJsonImportUiState.Completed -> {
                            val result = uiState.result
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF27AE60),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Import Complete!",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text = "${result.tracksImported} tracks, ${result.eventsCreated} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = onFinish,
                                colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Continue", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        is SpotifyJsonImportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFE74C3C),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Retry")
                                }
                                Button(
                                    onClick = {
                                        selectedUris = emptyList()
                                        onReset()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Select Files")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun YouTubeMusicImportCard(
    uiState: YouTubeMusicImportUiState,
    importState: YouTubeMusicImportService.ImportState,
    onImport: (List<Uri>) -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    // ponytail: hoisted accordion state; null = self-managed (legacy callers)
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val youTubeRed = Color(0xFFFF0000)
    var internalExpanded by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded

    fun setExpanded(value: Boolean) {
        if (expanded == null) {
            internalExpanded = value
        } else {
            onExpandedChange?.invoke(value)
        }
    }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            selectedUris = uris
            if (uris.isNotEmpty()) {
                setExpanded(true)
                onImport(uris)
            }
        }

    LaunchedEffect(uiState) {
        if (uiState is YouTubeMusicImportUiState.Importing || uiState is YouTubeMusicImportUiState.Completed) {
            setExpanded(true)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is YouTubeMusicImportUiState.Completed) {
            kotlinx.coroutines.delay(2000)
            onFinish()
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (uiState !is YouTubeMusicImportUiState.Importing) {
                                setExpanded(!isExpanded)
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                            .background(youTubeRed.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = youTubeRed,
                        modifier = Modifier.size(adaptiveSizeByCategory(28.dp, 26.dp, 24.dp)),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YouTube Music Takeout",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Import from YouTube Takeout (ZIP or JSON)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }

                if (uiState !is YouTubeMusicImportUiState.Importing) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    expandVertically(expandFrom = Alignment.Top, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(220)),
                exit =
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(160)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (uiState) {
                        is YouTubeMusicImportUiState.Idle -> {
                            Text(
                                text =
                                    buildAnnotatedString {
                                        append("Get your data from ")
                                        withLink(
                                            LinkAnnotation.Url(
                                                url = "https://takeout.google.com",
                                                styles =
                                                    TextLinkStyles(
                                                        style = SpanStyle(color = youTubeRed, textDecoration = TextDecoration.Underline),
                                                    ),
                                            ),
                                        ) {
                                            append("takeout.google.com")
                                        }
                                        append(
                                            ", deselect all → select only \"YouTube and YouTube Music\" → only \"history\" → JSON format. Download the ZIP and select it here directly (if Takeout split the export into several ZIPs, select all of them). You can also do this later from Settings.",
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            TextButton(
                                onClick = { showGuide = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = youTubeRed,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "See how to do it",
                                    color = youTubeRed,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip", "application/json", "text/plain", "*/*"),
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = youTubeRed),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select File")
                            }
                        }

                        is YouTubeMusicImportUiState.Importing -> {
                            val (message, progress) =
                                when (importState) {
                                    is YouTubeMusicImportService.ImportState.Parsing -> {
                                        "Parsing ${importState.fileName}..." to
                                            (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                                    }

                                    is YouTubeMusicImportService.ImportState.Importing -> {
                                        "Importing ${importState.current}/${importState.total}" to
                                            (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                                    }

                                    else -> {
                                        "Preparing..." to 0f
                                    }
                                }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = youTubeRed,
                                trackColor = Color.White.copy(alpha = 0.1f),
                            )
                        }

                        is YouTubeMusicImportUiState.Completed -> {
                            val result = uiState.result
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF27AE60),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Import Complete!",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text = "${result.tracksImported} tracks, ${result.eventsCreated} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Continuing automatically...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }

                        is YouTubeMusicImportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFE74C3C),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Retry")
                                }
                                Button(
                                    onClick = {
                                        selectedUris = emptyList()
                                        onReset()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = youTubeRed),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Select Files")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (showGuide) {
                TakeoutGuideDialog(onDismiss = { showGuide = false })
            }
        }
    }
}

@Composable
fun AppleMusicImportCard(
    uiState: AppleMusicImportUiState,
    importState: AppleMusicImportService.ImportState,
    onImport: (Uri) -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val applePink = Color(0xFFFA2D48)
    var internalExpanded by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded

    fun setExpanded(value: Boolean) {
        if (expanded == null) {
            internalExpanded = value
        } else {
            onExpandedChange?.invoke(value)
        }
    }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                setExpanded(true)
                onImport(uri)
            }
        }

    LaunchedEffect(uiState) {
        if (uiState is AppleMusicImportUiState.Importing || uiState is AppleMusicImportUiState.Completed) {
            setExpanded(true)
        }
    }

    // The view model persists its work id, so a leftover Completed from an import finished in
    // Settings can arrive here already terminal. Advancing onboarding on that would skip the
    // step, so only a run this card watched go through Importing is allowed to auto-continue.
    var sawImporting by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (uiState) {
            is AppleMusicImportUiState.Importing -> {
                sawImporting = true
            }

            is AppleMusicImportUiState.Completed -> {
                if (sawImporting) {
                    kotlinx.coroutines.delay(2000)
                    onFinish()
                }
            }

            else -> Unit
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (uiState !is AppleMusicImportUiState.Importing) {
                                setExpanded(!isExpanded)
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                            .background(applePink.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = applePink,
                        modifier = Modifier.size(adaptiveSizeByCategory(28.dp, 26.dp, 24.dp)),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Apple Music",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Import your listening history (ZIP)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }

                if (uiState !is AppleMusicImportUiState.Importing) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    expandVertically(expandFrom = Alignment.Top, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(220)),
                exit =
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(160)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (uiState) {
                        is AppleMusicImportUiState.Idle -> {
                            Text(
                                text =
                                    buildAnnotatedString {
                                        append("Request a copy of your data at ")
                                        withLink(
                                            LinkAnnotation.Url(
                                                url = "https://privacy.apple.com",
                                                styles =
                                                    TextLinkStyles(
                                                        style = SpanStyle(color = applePink, textDecoration = TextDecoration.Underline),
                                                    ),
                                            ),
                                        ) {
                                            append("privacy.apple.com")
                                        }
                                        append(
                                            ", tick only \"Apple Media Services information\" and download the ZIP when Apple emails you — up to 7 days. Select that ZIP here, don't unzip it. You can also do this later from Settings.",
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            TextButton(
                                onClick = { showGuide = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = applePink,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "See how to do it",
                                    color = applePink,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = { filePickerLauncher.launch(APPLE_MUSIC_IMPORT_MIME_TYPES) },
                                colors = ButtonDefaults.buttonColors(containerColor = applePink),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select File")
                            }
                        }

                        is AppleMusicImportUiState.Importing -> {
                            val (message, progress) =
                                when (importState) {
                                    is AppleMusicImportService.ImportState.Parsing -> {
                                        "Reading ${importState.fileName}..." to 0.05f
                                    }

                                    is AppleMusicImportService.ImportState.Importing -> {
                                        "Importing ${importState.current}/${importState.total}" to
                                            (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                                    }

                                    else -> {
                                        "Preparing..." to 0f
                                    }
                                }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = applePink,
                                trackColor = Color.White.copy(alpha = 0.1f),
                            )
                        }

                        is AppleMusicImportUiState.Completed -> {
                            val result = uiState.result
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF27AE60),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Import Complete!",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text = "${result.tracksImported} tracks, ${result.eventsCreated} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (sawImporting) {
                                Text(
                                    text = "Continuing automatically...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                )
                            } else {
                                // A leftover Completed from an earlier session: nothing will advance
                                // this screen on its own, so it needs a button instead of that line.
                                Button(
                                    onClick = onFinish,
                                    colors = ButtonDefaults.buttonColors(containerColor = applePink),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        text = "Continue",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        is AppleMusicImportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFE74C3C),
                                modifier = Modifier.size(40.dp),
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Retry")
                                }
                                Button(
                                    onClick = {
                                        onReset()
                                        filePickerLauncher.launch(APPLE_MUSIC_IMPORT_MIME_TYPES)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = applePink),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Select File")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (showGuide) {
                AppleMusicGuideDialog(onDismiss = { showGuide = false })
            }
        }
    }
}
