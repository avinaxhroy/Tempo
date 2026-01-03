package me.avinas.tempo.ui.onboarding

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.drive.DriveBackupInfo
import me.avinas.tempo.data.drive.DriveRestoreResult
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.settings.BackupRestoreViewModel
import me.avinas.tempo.ui.settings.DriveOperationState
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.WarmVioletAccent
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.utils.FormatUtils.formatBytes
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Track if we've shown the activity error to avoid spamming
    var activityErrorShown by remember { mutableStateOf(false) }
    
    // Handle system back press - block during active operations
    val driveOperation by viewModel.driveOperation.collectAsState()
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    val isOperationActive = driveOperation is DriveOperationState.Downloading ||
        driveOperation is DriveOperationState.Restoring ||
        driveOperation is DriveOperationState.Uploading ||
        importExportProgress != null
    
    androidx.activity.compose.BackHandler(enabled = !isOperationActive, onBack = onBack)
    
    // ViewModel State
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
    val sessionRestoreRequested by viewModel.sessionRestoreRequested.collectAsState()
    val consentRequested by viewModel.consentRequested.collectAsState()
    
    // Consent Flow Launcher
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
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

    // Success Handling - Finish onboarding on successful restore
    LaunchedEffect(importExportResult) {
        when (val result = importExportResult) {
             is ImportExportResult.Success -> {
                 onFinish() // Auto-finish on success
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
            is DriveOperationState.Success -> viewModel.clearDriveOperation()
            is DriveOperationState.Error -> {
                snackbarHostState.showSnackbar("Drive error: ${op.message}")
                viewModel.clearDriveOperation()
            }
            else -> {}
        }
    }

    // Local Backup Picker
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startImport(it) }
    }


    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TempoDarkBackground)
                .padding(paddingValues)
        ) {
             // Ambient Background Blobs (Reused from WelcomeScreen style)
             androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFEF4444).copy(alpha = 0.1f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(width, 0f),
                        radius = width * 0.8f
                    ),
                    center = androidx.compose.ui.geometry.Offset(width, 0f),
                    radius = width * 0.8f
                )
                 drawCircle(
                     brush = Brush.radialGradient(
                         colors = listOf(WarmVioletAccent.copy(alpha = 0.1f), Color.Transparent),
                         center = androidx.compose.ui.geometry.Offset(0f, height),
                         radius = width * 0.9f
                     ),
                     center = androidx.compose.ui.geometry.Offset(0f, height),
                     radius = width * 0.9f
                 )
            }
    
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Top Bar with Back Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Restore Data",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Text(
                        text = "Start fresh or restore your history from a previous backup.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
        
                    Spacer(modifier = Modifier.height(32.dp))
        
                    // Option 1: Google Drive
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(adaptiveSize(48.dp, 40.dp))
                                        .background(Color(0xFF4285F4).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = Color(0xFF4285F4)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Text(
                                    text = "Google Drive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
        
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        
                            if (!isSignedIn) {
                                Box(
                                    modifier = Modifier
                                        .clickable { viewModel.requestSignIn() }
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Connect Google Account",
                                        color = Color(0xFF4285F4),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            } else {
                                 if (driveOperation is DriveOperationState.Loading || driveOperation is DriveOperationState.SigningIn) {
                                     Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                         CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                     }
                                 } else if (driveBackups.isNotEmpty()) {
                                     Column(
                                         modifier = Modifier.padding(bottom = 16.dp)
                                     ) {
                                         driveBackups.forEachIndexed { index, backup ->
                                             Row(
                                                 modifier = Modifier
                                                     .fillMaxWidth()
                                                     .clickable { viewModel.startDriveRestore(backup) }
                                                     .padding(horizontal = 16.dp, vertical = 12.dp),
                                                 verticalAlignment = Alignment.CenterVertically
                                             ) {
                                                 Column(modifier = Modifier.weight(1f)) {
                                                     Text(
                                                         text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backup.createdAt)),
                                                         color = Color.White,
                                                         style = MaterialTheme.typography.bodyMedium,
                                                         fontSize = adaptiveTextUnit(16.sp, 14.sp)
                                                     )
                                                     Text(
                                                         text = formatBytes(backup.sizeBytes) + (backup.deviceName?.let { " • $it" } ?: ""),
                                                         color = Color.White.copy(alpha = 0.6f),
                                                         style = MaterialTheme.typography.bodySmall,
                                                         fontSize = adaptiveTextUnit(14.sp, 12.sp)
                                                     )
                                                 }
                                                 Icon(
                                                     Icons.Default.CloudDownload,
                                                     contentDescription = "Restore",
                                                     tint = TempoRed
                                                 )
                                             }
                                             if (index < driveBackups.lastIndex) {
                                                 HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                             }
                                         }
                                     }
                                 } else {
                                     Text(
                                         text = "No backups found",
                                         color = Color.White.copy(alpha = 0.5f),
                                         modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                                         style = MaterialTheme.typography.bodyMedium
                                     )
                                 }
                            }
                        }
                    }
        
                    Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 12.dp)))
        
                    // Option 2: Local Backup
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(adaptiveSize(48.dp, 40.dp))
                                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Column {
                                Text(
                                    text = "Restore from File",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Select a .zip backup file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                }
    
                // Start Fresh Button (Pinned Footer)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(adaptiveSize(56.dp, 48.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Start Fresh",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
    
    // --- Dialogs ---

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (progress.phase.contains("Import")) "Restoring..." else "Processing...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(progress.phase)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (progress.isIndeterminate || progress.total <= 0) {
                        CircularProgressIndicator()
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Drive Downloading Dialog
    if (driveOperation is DriveOperationState.Downloading || driveOperation is DriveOperationState.Restoring) {
         AlertDialog(
            onDismissRequest = { },
            title = { Text("Restoring from Cloud...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (driveOperation is DriveOperationState.Downloading) {
                        LinearProgressIndicator(progress = { (driveOperation as DriveOperationState.Downloading).progress })
                    } else {
                         CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {}
        )
    }
    
    // Local Conflict Resolution
    conflictDialogUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Restore Options") },
            text = { Text("How should we handle data conflicts?") },
            confirmButton = {
                TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }) {
                    Text("Replace Everything")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }) {
                        Text("Skip Duplicates")
                    }
                }
            }
        )
    }


    // Drive Restore Confirmation
    driveRestoreDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDriveRestore() },
            title = { Text("Restore this backup?") },
            text = { 
                Column {
                    Text("Date: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backup.createdAt))}")
                    Text("Size: ${formatBytes(backup.sizeBytes)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.REPLACE) }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDriveRestore() }) {
                    Text("Cancel")
                }
            }
        )
    }
}
