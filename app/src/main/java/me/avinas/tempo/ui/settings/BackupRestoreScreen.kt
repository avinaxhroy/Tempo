package me.avinas.tempo.ui.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.drive.*
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.utils.FormatUtils.formatBytes
import java.text.SimpleDateFormat
import java.util.*

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
    
    // Show result snackbar
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
    
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                
                // Google Drive Section
                GoogleDriveSection(
                    isSignedIn = isSignedIn,
                    googleAccount = googleAccount,
                    driveBackups = driveBackups,
                    backupSettings = backupSettings,
                    driveOperation = driveOperation,
                    onSignIn = viewModel::requestSignIn,
                    onSignOut = viewModel::signOut,
                    onBackupNow = viewModel::backupToDrive,
                    onRestore = viewModel::startDriveRestore,
                    onDelete = viewModel::deleteDriveBackup,
                    onRefreshBackups = viewModel::loadDriveBackups,
                    onSetInterval = viewModel::setBackupInterval,
                    onSetWifiOnly = viewModel::setWifiOnly
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Local Backup Section
                LocalBackupSection(
                    uiState = uiState,
                    onToggleLocalImages = viewModel::toggleIncludeLocalImages,
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
            title = { 
                Text(
                    if (progress.phase.contains("Import", ignoreCase = true)) "Importing..." 
                    else "Exporting..."
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
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (progress.isIndeterminate || progress.total <= 0) {
                        CircularProgressIndicator()
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${progress.current}/${progress.total}",
                            style = MaterialTheme.typography.bodySmall
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
            title = { Text("Import Options") },
            text = { 
                Text("What should happen when importing data that already exists in the app?") 
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }
                ) {
                    Text("Replace Existing")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }
                    ) {
                        Text("Skip Duplicates")
                    }
                }
            }
        )
    }
    
    // Drive Restore Confirmation Dialog
    driveRestoreDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDriveRestore() },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
            title = { Text("Restore from Cloud") },
            text = { 
                Column {
                    Text("Restore backup from ${formatDate(backup.createdAt)}?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Size: ${formatBytes(backup.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (backup.appVersion != null) {
                        Text(
                            text = "App version: ${backup.appVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "How should duplicates be handled?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.REPLACE) }
                ) {
                    Text("Replace Existing")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelDriveRestore() }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.SKIP) }
                    ) {
                        Text("Skip Duplicates")
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
                title = { 
                    Text(
                        when (op) {
                            DriveOperationState.SigningIn -> "Signing in..."
                            DriveOperationState.Loading -> "Loading..."
                            DriveOperationState.Restoring -> "Restoring..."
                            else -> ""
                        }
                    )
                },
                text = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                },
                confirmButton = { }
            )
        }
        is DriveOperationState.Uploading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Uploading to Google Drive...") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { op.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(op.progress * 100).toInt()}%")
                    }
                },
                confirmButton = { }
            )
        }
        is DriveOperationState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Downloading from Google Drive...") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { op.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(op.progress * 100).toInt()}%")
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
    Text(
        text = "YOUR DATA",
        style = MaterialTheme.typography.titleSmall,
        color = TempoRed,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
    
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
                DataStatItem(Icons.Default.MusicNote, uiState.trackCount, "Tracks")
                DataStatItem(Icons.Default.Person, uiState.artistCount, "Artists")
                DataStatItem(Icons.Default.History, uiState.eventCount, "Plays")
                DataStatItem(Icons.Default.Image, uiState.localImageCount, "Images")
            }
        }
        
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TempoRed, modifier = Modifier.size(32.dp))
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
    onBackupNow: () -> Unit,
    onRestore: (DriveBackupInfo) -> Unit,
    onDelete: (DriveBackupInfo) -> Unit,
    onRefreshBackups: () -> Unit,
    onSetInterval: (BackupInterval) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit
) {
    Text(
        text = "GOOGLE DRIVE",
        style = MaterialTheme.typography.titleSmall,
        color = TempoRed,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
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
                                color = Color(0xFF4285F4).copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connect Google Account",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Back up your data to Google Drive",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f)
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
                                .background(Color(0xFF4285F4), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = googleAccount?.email?.first()?.uppercase() ?: "G",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = googleAccount?.displayName ?: "Google Account",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Text(
                            text = googleAccount?.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    TextButton(onClick = onSignOut) {
                        Text("Sign Out", color = Color.White.copy(alpha = 0.7f))
                    }
                }
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                
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
                            color = Color.White
                        )
                        if (backupSettings.lastBackupTime != null) {
                            Text(
                                text = "Last backup: ${formatRelativeTime(backupSettings.lastBackupTime)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Button(
                        onClick = onBackupNow,
                        enabled = driveOperation == DriveOperationState.Idle,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup Now")
                    }
                }
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                
                // Schedule dropdown
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
                            color = Color.White
                        )
                        Text(
                            text = backupSettings.backupInterval.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    
                    Box {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                        DropdownMenu(
                            expanded = intervalExpanded,
                            onDismissRequest = { intervalExpanded = false }
                        ) {
                            BackupInterval.entries.forEach { interval ->
                                DropdownMenuItem(
                                    text = { Text(interval.displayName) },
                                    onClick = {
                                        onSetInterval(interval)
                                        intervalExpanded = false
                                    },
                                    leadingIcon = {
                                        if (backupSettings.backupInterval == interval) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Wi-Fi only toggle
                SettingsSwitch(
                    title = "Wi-Fi Only",
                    subtitle = "Only backup when connected to Wi-Fi",
                    checked = backupSettings.wifiOnly,
                    onCheckedChange = onSetWifiOnly
                )
                
                // Backup history
                if (driveBackups.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Backup History (${driveBackups.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        
                        IconButton(onClick = onRefreshBackups, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White.copy(alpha = 0.7f),
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
                color = Color.White
            )
            Row {
                Text(
                    text = formatBytes(backup.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                if (backup.deviceName != null) {
                    Text(
                        text = " • ${backup.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
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
                    tint = Color(0xFF4285F4)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun LocalBackupSection(
    uiState: BackupRestoreUiState,
    onToggleLocalImages: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Text(
        text = "LOCAL BACKUP",
        style = MaterialTheme.typography.titleSmall,
        color = TempoRed,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            // Local images toggle
            SettingsSwitch(
                title = "Include Local Images",
                subtitle = if (uiState.localImageCount > 0) {
                    "${uiState.localImageCount} images (${uiState.localImageSizeFormatted})"
                } else {
                    "No local images to include"
                },
                checked = uiState.includeLocalImages,
                onCheckedChange = onToggleLocalImages
            )
            
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            
            // Export button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export to Device",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Estimated size: ${uiState.estimatedExportSizeFormatted}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                
                Button(
                    onClick = onExport,
                    colors = ButtonDefaults.buttonColors(containerColor = TempoRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export")
                }
            }
            
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            
            // Import button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import from Device",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Restore from a local backup file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                
                OutlinedButton(
                    onClick = onImport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.3f))
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import")
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
        backgroundColor = TempoRed.copy(alpha = 0.1f)
    ) {
        Column {
            Text(
                text = "💡 Backup Tips",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Google Drive backups are automatic and encrypted\n" +
                       "• Only the 5 most recent backups are kept\n" +
                       "• Album art from streaming services will be re-downloaded\n" +
                       "• Local album art is only included if toggled on",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
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
                .background(color = TempoRed.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = TempoRed, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = formatCount(count),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

// Helper functions
private fun formatCount(count: Int): String {
    return when {
        count >= 1000 -> String.format("%.1fK", count / 1000.0)
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
