package me.avinas.tempo.ui.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.drive.*
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.TempoSnackbar
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.ui.components.TempoDropdownMenu
import me.avinas.tempo.ui.components.TempoDropdownMenuItem
import me.avinas.tempo.utils.FormatUtils.formatBytes
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R

/**
 * Dedicated Backup & Restore screen with Google Drive integration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    val importExportResult by viewModel.importExportResult.collectAsState()
    val conflictDialogUri by viewModel.showConflictDialog.collectAsState()
    
    val scope = rememberCoroutineScope()
    
    // Google Drive states
    val googleAccount by viewModel.googleAccount.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val driveBackups by viewModel.driveBackups.collectAsState()
    val backupSettings by viewModel.backupSettings.collectAsState()
    val driveOperation by viewModel.driveOperation.collectAsState()
    val driveRestoreDialog by viewModel.showDriveRestoreDialog.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Get Activity context for Credential Manager
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Track if we've shown the activity error to avoid spamming
    var activityErrorShown by remember { mutableStateOf(false) }

    // A backup/restore runs on an application scope and survives this screen,
    // but blocking back while one is active prevents the user from accidentally
    // leaving in the middle of a long operation with no visible progress.
    val isOperationActive = driveOperation is DriveOperationState.Downloading ||
        driveOperation is DriveOperationState.Restoring ||
        driveOperation is DriveOperationState.Uploading ||
        importExportProgress != null
    var confirmLeaveDuringOperation by remember { mutableStateOf(false) }
    BackHandler(enabled = isOperationActive) { confirmLeaveDuringOperation = true }
    // Handle sign-in request from ViewModel
    val signInRequested by viewModel.signInRequested.collectAsState()
    val sessionRestoreRequested by viewModel.sessionRestoreRequested.collectAsState()
    val consentRequested by viewModel.consentRequested.collectAsState()
    
    // Consent Flow Launcher
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentComplete(result.resultCode == Activity.RESULT_OK)
    }
    
    // Bridge Activity context for sign-in
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
    
    // Bridge Activity context for session restore on screen launch
    LaunchedEffect(sessionRestoreRequested) {
        if (sessionRestoreRequested && activity != null) {
            viewModel.onSessionRestoreReady(activity)
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

    
    // File picker for import (ZIP)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startImport(it) }
    }
    
    // File creator for export (ZIP)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportData(it) }
    }
    
    // Show result snackbar after successful import/restore
    LaunchedEffect(importExportResult) {
        importExportResult?.let { result ->
            when (result) {
                is ImportExportResult.Success -> {
                    val imageText = if (result.imagesCount > 0) " and ${result.imagesCount} images" else ""
                    snackbarHostState.showSnackbar(
                        "Successfully processed ${result.totalRecords} records$imageText"
                    )
                }
                is ImportExportResult.Error -> {
                    snackbarHostState.showSnackbar(result.message)
                }
            }
            viewModel.clearImportExportResult()
        }
    }
    
    // Show Drive operation results
    LaunchedEffect(driveOperation) {
        when (val op = driveOperation) {
            is DriveOperationState.Success -> {
                snackbarHostState.showSnackbar(op.message)
                viewModel.clearDriveOperation()
            }
            is DriveOperationState.Error -> {
                snackbarHostState.showSnackbar(op.message)
                viewModel.clearDriveOperation()
            }
            else -> {}
        }
    }
    
    if (confirmLeaveDuringOperation) {
        AlertDialog(
            onDismissRequest = { confirmLeaveDuringOperation = false },
            title = { Text(stringResource(R.string.backup_leave_title)) },
            text = { Text(stringResource(R.string.backup_leave_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeaveDuringOperation = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.backup_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveDuringOperation = false }) {
                    Text(stringResource(R.string.drive_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_restore), color = TextPrimary) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isOperationActive) confirmLeaveDuringOperation = true
                            else onNavigateBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { TempoSnackbar(it) } }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Data Overview Section
                DataOverviewSection(uiState)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Cross-device listening history is independent from device
                // backups and visible even before Google sign-in.
                DriveHistorySyncSection(
                    isGoogleSignedIn = isSignedIn,
                    accountEmail = googleAccount?.email,
                    onRequestGoogleSignIn = viewModel::requestSignIn
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Full-device Google Drive backup/restore.
                GoogleDriveSection(
                    isSignedIn = isSignedIn,
                    googleAccount = googleAccount,
                    driveBackups = driveBackups,
                    backupSettings = backupSettings,
                    driveOperation = driveOperation,
                    onSignIn = viewModel::requestSignIn,
                    onSignOut = viewModel::signOut,
                    onSetDriveBackupEnabled = viewModel::setDriveBackupEnabled,
                    onBackupNow = viewModel::backupToDrive,
                    onRestore = viewModel::startDriveRestore,
                    onDelete = viewModel::deleteDriveBackup,
                    onRefreshBackups = viewModel::loadDriveBackups
                )

                Spacer(modifier = Modifier.height(24.dp))

                AutomaticBackupSection(
                    backupSettings = backupSettings,
                    onSetInterval = viewModel::setBackupInterval,
                    onSetWifiOnly = viewModel::setWifiOnly
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Local Backup Section
                LocalBackupSection(
                    uiState = uiState,
                    onToggleLocalImages = viewModel::toggleIncludeLocalImages,
                    onChooseAutoBackupFolder = viewModel::chooseLocalBackupFolder,
                    onExport = {
                        exportLauncher.launch("tempo_backup_${System.currentTimeMillis()}.zip")
                    },
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Info Card
                BackupTipsCard()
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while in progress */ },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (progress.phase.contains("Import", ignoreCase = true)) "Importing..."
                    else "Exporting...",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = progress.phase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (progress.isIndeterminate || progress.total <= 0) {
                        CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${progress.current}/${progress.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            },
            confirmButton = { }
        )
    }
    
    // Local Conflict Resolution Dialog
    conflictDialogUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Import Options", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text("What should happen when importing data that already exists in the app?", color = TextSecondary)
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }
                ) {
                    Text(stringResource(R.string.settings_replace_existing), color = TempoPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("Cancel", color = TextTertiary)
                    }
                    TextButton(
                        onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }
                    ) {
                        Text(stringResource(R.string.settings_skip_duplicates), color = TextSecondary)
                    }
                }
            }
        )
    }
    
    // Drive Restore Confirmation Dialog
    driveRestoreDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDriveRestore() },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = TempoPrimary) },
            title = { Text(stringResource(R.string.drive_device_restore_title), color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(stringResource(R.string.drive_device_restore_question, formatDate(backup.createdAt)), color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Size: ${formatBytes(backup.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    if (backup.appVersion != null) {
                        Text(
                            text = "App version: ${backup.appVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.restore_handle_conflicts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.REPLACE) }
                ) {
                    Text(stringResource(R.string.settings_replace_existing), color = TempoPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelDriveRestore() }) {
                        Text("Cancel", color = TextTertiary)
                    }
                    TextButton(
                        onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.SKIP) }
                    ) {
                        Text(stringResource(R.string.settings_skip_duplicates), color = TextSecondary)
                    }
                }
            }
        )
    }
    
    // Drive Operation Progress Dialog
    when (val op = driveOperation) {
        is DriveOperationState.SigningIn,
        is DriveOperationState.Loading,
        is DriveOperationState.Restoring -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = TempoSurfaceDialog,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        when (op) {
                            DriveOperationState.SigningIn -> "Signing in..."
                            DriveOperationState.Loading -> "Loading..."
                            DriveOperationState.Restoring -> "Restoring..."
                            else -> ""
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                    }
                },
                confirmButton = { }
            )
        }
        is DriveOperationState.Uploading -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = TempoSurfaceDialog,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Uploading to Google Drive...", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { op.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(op.progress * 100).toInt()}%", color = TextTertiary)
                    }
                },
                confirmButton = { }
            )
        }
        is DriveOperationState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = TempoSurfaceDialog,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Downloading from Google Drive...", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { op.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(op.progress * 100).toInt()}%", color = TextTertiary)
                    }
                },
                confirmButton = { }
            )
        }
        else -> { }
    }
}

@Composable
private fun DataOverviewSection(uiState: BackupRestoreUiState) {
    SettingsSectionHeader(stringResource(R.string.backup_restore_data_header))
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        AnimatedVisibility(
            visible = !uiState.isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DataStatItem(Icons.Default.MusicNote, uiState.trackCount, stringResource(R.string.backup_restore_tracks_label))
                DataStatItem(Icons.Default.Person, uiState.artistCount, stringResource(R.string.backup_restore_artists_label))
                DataStatItem(Icons.Default.History, uiState.eventCount, stringResource(R.string.backup_restore_plays_label))
                DataStatItem(Icons.Default.Image, uiState.localImageCount, stringResource(R.string.backup_restore_images_label))
            }
        }
        
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TempoPrimary, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun GoogleDriveSection(
    isSignedIn: Boolean,
    googleAccount: GoogleAccount?,
    driveBackups: List<DriveBackupInfo>,
    backupSettings: BackupSettings,
    driveOperation: DriveOperationState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSetDriveBackupEnabled: (Boolean) -> Unit,
    onBackupNow: () -> Unit,
    onRestore: (DriveBackupInfo) -> Unit,
    onDelete: (DriveBackupInfo) -> Unit,
    onRefreshBackups: () -> Unit
) {
    SettingsSectionHeader(stringResource(R.string.drive_device_backup_heading))
    Text(
        text = stringResource(R.string.drive_device_backup_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            if (!isSignedIn) {
                // Sign in prompt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSignIn() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = TempoInfo.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = TempoInfo,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.restore_connect_google),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.drive_device_backup_action_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                }
            } else {
                // Signed in account info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Profile picture or placeholder
                    if (googleAccount?.photoUrl != null) {
                        CachedAsyncImage(
                            imageUrl = googleAccount.photoUrl.toString(),
                            contentDescription = "Profile",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(TempoInfo, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = googleAccount?.email?.first()?.uppercase() ?: "G",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = googleAccount?.displayName ?: "Google Account",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = googleAccount?.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    TextButton(onClick = onSignOut) {
                        Text("Sign Out", color = TextSecondary)
                    }
                }
                
                HorizontalDivider(color = GlassBorderSoft)
                SettingsSwitch(
                    title = stringResource(R.string.drive_device_backup_toggle),
                    subtitle = stringResource(R.string.drive_device_backup_toggle_hint),
                    checked = backupSettings.isGoogleDriveEnabled,
                    onCheckedChange = onSetDriveBackupEnabled
                )
                HorizontalDivider(color = GlassBorderSoft)

                // Backup Now button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Backup to Drive",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        if (backupSettings.lastBackupTime != null) {
                            Text(
                                text = "Last backup: ${formatRelativeTime(backupSettings.lastBackupTime)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    
                    Button(
                        onClick = onBackupNow,
                        enabled = driveOperation == DriveOperationState.Idle &&
                            backupSettings.isGoogleDriveEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = TempoInfo),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.drive_device_backup_now))
                    }
                }
                
                // Backup history
                if (driveBackups.isNotEmpty()) {
                    HorizontalDivider(color = GlassBorderSoft)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.backup_history, driveBackups.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextSecondary
                        )
                        
                        IconButton(onClick = onRefreshBackups, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    driveBackups.forEach { backup ->
                        DriveBackupItem(
                            backup = backup,
                            onRestore = { onRestore(backup) },
                            onDelete = { onDelete(backup) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomaticBackupSection(
    backupSettings: BackupSettings,
    onSetInterval: (BackupInterval) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit
) {
    SettingsSectionHeader(stringResource(R.string.backup_auto))

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            var intervalExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { intervalExpanded = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-backup",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = backupSettings.backupInterval.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Box {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                    TempoDropdownMenu(
                        expanded = intervalExpanded,
                        onDismissRequest = { intervalExpanded = false }
                    ) {
                        BackupInterval.entries.forEach { interval ->
                            TempoDropdownMenuItem(
                                title = interval.displayName,
                                onClick = {
                                    onSetInterval(interval)
                                    intervalExpanded = false
                                },
                                isSelected = backupSettings.backupInterval == interval
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = GlassBorderSoft)

            SettingsSwitch(
                title = "Drive on Wi-Fi only",
                subtitle = "Device backups still run offline; only the Google Drive upload waits for Wi-Fi",
                checked = backupSettings.wifiOnly,
                onCheckedChange = onSetWifiOnly
            )
        }
    }
}

@Composable
private fun DriveBackupItem(
    backup: DriveBackupInfo,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDate(backup.createdAt),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Row {
                Text(
                    text = formatBytes(backup.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                if (backup.deviceName != null) {
                    Text(
                        text = " • ${backup.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        
        Row {
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "Restore",
                    tint = TempoInfo
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun LocalBackupSection(
    uiState: BackupRestoreUiState,
    onToggleLocalImages: (Boolean) -> Unit,
    onChooseAutoBackupFolder: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    SettingsSectionHeader(stringResource(R.string.backup_restore_local_backup_header))
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            // Automatic local backup destination
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic backup folder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose or change where scheduled device backups are saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                OutlinedButton(onClick = onChooseAutoBackupFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose folder")
                }
            }

            HorizontalDivider(color = GlassBorderSoft)

            // Local images toggle
            SettingsSwitch(
                title = stringResource(R.string.backup_restore_include_local_images),
                subtitle = if (uiState.localImageCount > 0) {
                    stringResource(R.string.backup_restore_local_images_desc, uiState.localImageCount, uiState.localImageSizeFormatted)
                } else {
                    stringResource(R.string.backup_restore_no_local_images)
                },
                checked = uiState.includeLocalImages,
                onCheckedChange = onToggleLocalImages
            )
            
            HorizontalDivider(color = GlassBorderSoft)
            
            // Export button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.backup_restore_export_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.backup_restore_estimated_size, uiState.estimatedExportSizeFormatted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                
                Button(
                    onClick = onExport,
                    colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_restore_export_button))
                }
            }
            
            HorizontalDivider(color = GlassBorderSoft)
            
            // Import button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.backup_restore_import_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.backup_restore_import_file_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                
                OutlinedButton(
                    onClick = onImport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(GlassBorderMedium, GlassBorderMedium)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_restore_import_button))
                }
            }
        }
    }
}

@Composable
private fun BackupTipsCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence,
        backgroundColor = TempoPrimary.copy(alpha = 0.1f)
    ) {
        Column {
            Text(
                text = "💡 Backup Tips",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Google Drive backups are stored in your Drive account\n" +
                       "• Only the 5 most recent backups are kept\n" +
                       "• Album art from streaming services will be re-downloaded\n" +
                       "• Local album art is only included if toggled on",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3
            )
        }
    }
}

@Composable
private fun DataStatItem(
    icon: ImageVector,
    count: Int,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color = TempoPrimary.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = TempoPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = formatCount(count),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1000 -> String.format(java.util.Locale.US, "%.1fK", count / 1000.0)
        else -> count.toString()
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        diff < 604_800_000 -> "${diff / 86_400_000} days ago"
        else -> formatDate(timestamp)
    }
}
