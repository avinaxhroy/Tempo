package me.avinas.tempo.ui.onboarding

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
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
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import me.avinas.tempo.ui.spotify.SpotifyImportState
import me.avinas.tempo.ui.spotify.TopItemsImportState
import me.avinas.tempo.ui.spotify.HistoryReconstructionState
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.utils.FormatUtils.formatBytes
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.painterResource
import me.avinas.tempo.R
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.ui.lastfm.LastFmViewModel
import me.avinas.tempo.ui.lastfm.LastFmUiState
import me.avinas.tempo.data.lastfm.LastFmImportService
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel(),
    lastFmViewModel: LastFmViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Track if we've shown the activity error to avoid spamming
    var activityErrorShown by remember { mutableStateOf(false) }
    
    // Handle system back press - block during active operations
    val driveOperation by viewModel.driveOperation.collectAsState()
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    val spotifyImportState by spotifyViewModel.importState.collectAsState()
    val topItemsImportState by spotifyViewModel.topItemsImportState.collectAsState()
    val reconstructionState by spotifyViewModel.reconstructionState.collectAsState()
    val spotifyAuthState by spotifyViewModel.authState.collectAsState()
    val isPendingSpotifyAuth by spotifyViewModel.isPendingAuth.collectAsState()
    
    // Last.fm State
    val lastFmUiState by lastFmViewModel.uiState.collectAsState()
    val lastFmImportProgress by lastFmViewModel.importProgress.collectAsState()
    
    // Check for pending Spotify auth when screen resumes (user returning from browser)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                spotifyViewModel.checkPendingAuth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val isOperationActive = driveOperation is DriveOperationState.Downloading ||
        driveOperation is DriveOperationState.Restoring ||
        driveOperation is DriveOperationState.Uploading ||
        importExportProgress != null ||
        spotifyImportState is SpotifyImportState.Importing ||
        topItemsImportState is TopItemsImportState.Importing ||
        reconstructionState is HistoryReconstructionState.Reconstructing ||
        lastFmUiState.isImporting ||
        lastFmUiState.isLoading
    
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
    
    // Spotify Top Items Import - Auto-finish on success
    LaunchedEffect(topItemsImportState) {
        when (val state = topItemsImportState) {
            is TopItemsImportState.Success -> {
                onFinish() // Auto-finish on successful Spotify import
                spotifyViewModel.clearTopItemsImportState()
            }
            is TopItemsImportState.Error -> {
                snackbarHostState.showSnackbar("Spotify import failed: ${state.message}")
                spotifyViewModel.clearTopItemsImportState()
            }
            else -> {}
        }
    }
    
    // History Reconstruction - Auto-finish on success
    LaunchedEffect(reconstructionState) {
        when (val state = reconstructionState) {
            is HistoryReconstructionState.Success -> {
                onFinish() // Auto-finish on successful reconstruction
                spotifyViewModel.clearReconstructionState()
            }
            is HistoryReconstructionState.Error -> {
                snackbarHostState.showSnackbar("History reconstruction failed: ${state.message}")
                spotifyViewModel.clearReconstructionState()
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
        me.avinas.tempo.ui.components.DeepOceanBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
    
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
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))
                    
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
    
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))
                    
                    Text(
                        text = "Import & Restore",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Text(
                        text = "Import from other services or restore your backup.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = rememberScreenHeightPercentage(0.01f))
                    )
        
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                    // Section: Import
                    Text(
                        text = "Import",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Start
                    )

                    // Custom Beautiful Spotify Card
                    SpotifyImportCard(
                        onImport = {
                            // If not connected, start login first (callback will trigger import)
                            // If connected, use enhanced history reconstruction
                            if (spotifyAuthState is SpotifyAuthManager.AuthState.Connected) {
                                // Use the new history reconstruction for more accurate data
                                spotifyViewModel.importWithHistoryReconstruction()
                            } else {
                                val intent = spotifyViewModel.startLogin()
                                context.startActivity(intent)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

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
                            onFinish()
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Section: Restore
                    Text(
                        text = "Restore",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Start
                    )
        
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
                                    .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
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
                                                        fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp)
                                                     )
                                                     Text(
                                                        text = formatBytes(backup.sizeBytes) + (backup.deviceName?.let { " • $it" } ?: ""),
                                                        color = Color.White.copy(alpha = 0.6f),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontSize = adaptiveTextUnitByCategory(14.sp, 13.sp, 12.sp)
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
        
                    Spacer(modifier = Modifier.height(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)))
        
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
                                    .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
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
                    
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.04f)))
                }
    
                // Start Fresh Button (Pinned Footer)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp))
                        .padding(bottom = rememberScreenHeightPercentage(0.03f))
                ) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scaledSize(54.dp, 0.85f, 1.1f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Start Fresh",
                            fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp),
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
                    val downloadingState = driveOperation as? DriveOperationState.Downloading
                    if (downloadingState != null) {
                        val currentProgress = downloadingState.progress
                        LinearProgressIndicator(progress = { currentProgress })
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
    
    // Spotify Import Progress Dialog
    if (spotifyImportState is SpotifyImportState.Importing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing from Spotify...") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Fetching your recent plays",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // Spotify Top Items Import Progress Dialog
    if (topItemsImportState is TopItemsImportState.Importing) {
        val importingState = topItemsImportState as TopItemsImportState.Importing
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing from Spotify...") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        progress = { importingState.current.toFloat() / importingState.total.coerceAtLeast(1) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = importingState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // History Reconstruction Progress Dialog (Enhanced Import)
    if (reconstructionState is HistoryReconstructionState.Reconstructing) {
        val state = reconstructionState as HistoryReconstructionState.Reconstructing
        AlertDialog(
            onDismissRequest = { },
            title = { 
                Text(
                    text = when (state.phase) {
                        "Time Machine" -> "🕐 Analyzing Your Liked Songs..."
                        "Artifact Hunter" -> "📅 Finding Yearly Playlists..."
                        "Smart Mixer" -> "🎵 Collecting Top Tracks..."
                        "Generating History" -> "✨ Creating Your History..."
                        else -> "Importing from Spotify..."
                    }
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LinearProgressIndicator(
                        progress = { state.progress.toFloat() / state.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (state.phase) {
                            "Time Machine" -> "Using exact dates from your liked songs"
                            "Artifact Hunter" -> "Finding Your Top Songs playlists"
                            "Smart Mixer" -> "Building from your top tracks"
                            "Generating History" -> "Creating realistic listening patterns"
                            else -> "Building your music history"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // Spotify Import Success Dialog
    if (spotifyImportState is SpotifyImportState.Success) {
        val successState = spotifyImportState as SpotifyImportState.Success
        AlertDialog(
            onDismissRequest = { spotifyViewModel.clearImportState() },
            title = { Text("Import Complete!") },
            text = { 
                Column {
                    Text(
                        if (successState.tracksImported > 0)
                            "Imported ${successState.tracksImported} tracks from Spotify."
                        else
                            "No new tracks to import. Your history is already up to date!"
                    )
                    if (successState.tracksImported > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Note: Spotify's API only provides your last ~50 plays. " +
                                "Tempo will automatically track new plays going forward.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    spotifyViewModel.clearImportState()
                    if (successState.tracksImported > 0) {
                        onFinish() // Auto-finish on successful import with tracks
                    }
                }) {
                    Text(if (successState.tracksImported > 0) "Continue" else "OK")
                }
            }
        )
    }
    
    // Spotify Import Error Dialog
    if (spotifyImportState is SpotifyImportState.Error) {
        val errorState = spotifyImportState as SpotifyImportState.Error
        AlertDialog(
            onDismissRequest = { spotifyViewModel.clearImportState() },
            title = { Text("Import Failed") },
            text = { Text(errorState.message) },
            confirmButton = {
                TextButton(onClick = { spotifyViewModel.clearImportState() }) {
                    Text("OK")
                }
            }
        )
    }
    
    // Spotify Pending Auth Dialog - shown immediately when returning from browser
    // This provides instant feedback while token exchange happens in background
    if (isPendingSpotifyAuth && topItemsImportState !is TopItemsImportState.Importing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Connecting to Spotify...") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Completing authentication",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = { }
        )
    }
}
@Composable
fun SpotifyImportCard(
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    
    Button(
        onClick = onImport,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = spotifyGreen
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.spotify),
            contentDescription = "Spotify",
            modifier = Modifier.size(28.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = "Import from Spotify",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
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
    modifier: Modifier = Modifier
) {
    val lastFmPurple = Color(0xFFBA0000) // Last.fm red
    var username by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    
    // Reset username when state is reset (e.g., when going back from tier selection)
    LaunchedEffect(uiState.discoveryResult, uiState.showTierSelection) {
        if (uiState.discoveryResult == null && !uiState.showTierSelection && !uiState.isImporting) {
            username = ""
        }
    }
    
    // Auto-expand when there's discovery result or import in progress
    LaunchedEffect(uiState.discoveryResult, uiState.isImporting, uiState.importResult) {
        if (uiState.discoveryResult != null || uiState.isImporting || uiState.importResult != null) {
            isExpanded = true
        }
    }
    
    // Handle successful import - don't auto-finish, let user click Continue
    // This avoids race conditions with the button click
    
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        if (!uiState.isImporting && !uiState.isLoading) {
                            isExpanded = !isExpanded 
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                        .background(lastFmPurple.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lastfm),
                        contentDescription = "Last.fm",
                        tint = lastFmPurple,
                        modifier = Modifier
                            .size(24.dp)
                            .offset(y = (-1).dp) // Visual correction
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last.fm",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Import years of listening history",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                
                // Expand/collapse indicator
                if (!uiState.isImporting && !uiState.isLoading) {
                    Icon(
                        imageVector = if (isExpanded) 
                            Icons.Default.KeyboardArrowUp 
                        else 
                            Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Expandable content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    
                    when {
                        // Import complete
                        uiState.importResult != null -> {
                            LastFmImportComplete(
                                result = uiState.importResult!!,
                                onDone = onFinishImport
                            )
                        }
                        
                        // Import in progress
                        uiState.isImporting -> {
                            LastFmImportProgress(
                                progress = importProgress,
                                onCancel = onCancelImport
                            )
                        }
                        
                        // Tier selection
                        uiState.showTierSelection && uiState.discoveryResult != null -> {
                            LastFmTierSelection(
                                discovery = uiState.discoveryResult!!,
                                onSelectTier = onSelectTier,
                                onBack = onCancel
                            )
                        }
                        
                        // Loading/discovering
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = lastFmPurple,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Discovering your account...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f)
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
                                onSubmit = { onUsernameSubmit(username.trim()) }
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
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Last.fm Username") },
            placeholder = { Text("Enter your username") },
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFBA0000),
                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                focusedLabelColor = Color(0xFFBA0000),
                unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                cursorColor = Color(0xFFBA0000)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = onSubmit,
            enabled = username.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBA0000),
                disabledContainerColor = Color(0xFFBA0000).copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Connect", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LastFmTierSelection(
    discovery: LastFmImportService.DiscoveryResult,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onBack: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "Welcome, ${discovery.username}!",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "${numberFormat.format(discovery.totalScrobbles)} total scrobbles",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Your complete history is imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f)
        )
        Text(
            text = "Choose which tracks power your leaderboards:",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Tier options - using new Quick/Standard/Deep system
        LastFmTierOption(
            name = "Quick",
            description = "Recent 3 months in leaderboards",
            icon = "⚡",
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.QUICK) }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LastFmTierOption(
            name = "Standard", 
            description = "Last year + top tracks in leaderboards",
            icon = "⭐",
            isRecommended = true, // Standard is recommended for most users
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.STANDARD) }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LastFmTierOption(
            name = "Deep",
            description = "Last 2 years + more tracks in leaderboards",
            icon = "📊",
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.DEEP) }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Explanation text - user-friendly
        Text(
            text = "💡 All your tracks are saved. This just affects chart speed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = "Use different account",
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LastFmTierOption(
    name: String,
    description: String,
    icon: String,
    isRecommended: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isRecommended) Color(0xFFBA0000).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.titleMedium)
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        if (isRecommended) {
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFFBA0000).copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBA0000)
                )
            }
        }
    }
}

@Composable
private fun LastFmImportProgress(
    progress: LastFmImportService.ImportProgress,
    onCancel: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    val lastFmRed = Color(0xFFBA0000)
    
    // Extract progress details including tier info
    val progressData = when (progress) {
        is LastFmImportService.ImportProgress.Importing -> {
            val percent = if (progress.total > 0) ((progress.current * 100) / progress.total).toInt() else 0
            ProgressData(
                percent = percent, 
                phase = progress.phase, 
                current = progress.current, 
                total = progress.total, 
                eventsCreated = progress.eventsCreated, 
                archived = progress.archived,
                isEverythingTier = progress.tierName == "EVERYTHING"
            )
        }
        is LastFmImportService.ImportProgress.Discovering -> ProgressData(0, "Discovering", 0, 0, 0, 0, false)
        is LastFmImportService.ImportProgress.Processing -> ProgressData(0, "Processing", 0, 0, 0, 0, false)
        else -> ProgressData(0, "Preparing", 0, 0, 0, 0, false)
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular progress with percentage
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { (progressData.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                color = lastFmRed,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 6.dp
            )
            
            Text(
                text = "${progressData.percent}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Phase indicator
        Text(
            text = progressData.phase,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        
        // Progress text
        if (progressData.total > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${numberFormat.format(progressData.current)} of ${numberFormat.format(progressData.total)} scrobbles",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This may take a few minutes",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Live stats row - show different labels based on tier
        if (progressData.isEverythingTier) {
            // Full import: just show total imported
            ImportStatItem(
                label = "Imported",
                value = numberFormat.format(progressData.eventsCreated),
                color = Color(0xFF4CAF50)
            )
        } else {
            // Tiered import: show active vs archived breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ImportStatItem(
                    label = "Active",
                    value = numberFormat.format(progressData.eventsCreated),
                    color = Color(0xFF4CAF50) // Green for active tracks
                )
                ImportStatItem(
                    label = "Archived",
                    value = numberFormat.format(progressData.archived),
                    color = Color(0xFF9E9E9E) // Gray for archived
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cancel button
        TextButton(onClick = onCancel) {
            Text(
                text = "Cancel",
                color = Color.White.copy(alpha = 0.6f)
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
    val isEverythingTier: Boolean
)

@Composable
private fun ImportStatItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun LastFmImportComplete(
    result: LastFmImportService.ImportResult,
    onDone: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF27AE60),
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "Import Complete!",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "${numberFormat.format(result.activeSetCount + result.archivedCount)} scrobbles imported",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBA0000)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }
    }
}
