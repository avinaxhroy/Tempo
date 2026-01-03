package me.avinas.tempo.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.SettingsOption
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import me.avinas.tempo.ui.theme.TempoRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToBackup: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val spotifyAuthState by spotifyViewModel.authState.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }
    val scope = rememberCoroutineScope()
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Import/Export states
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    val importExportResult by viewModel.importExportResult.collectAsState()
    val conflictDialogUri by viewModel.showConflictDialog.collectAsState()
    
    // File picker for import (ZIP)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.startImport(it)
        }
    }
    
    // File creator for export (ZIP)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportData(it)
        }
    }
    
    // Show result snackbar
    LaunchedEffect(importExportResult) {
        importExportResult?.let { result ->
            when (result) {
                is ImportExportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        "Successfully processed ${result.totalRecords} records and ${result.imagesCount} images"
                    )
                }
                is ImportExportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        result.message
                    )
                }
            }
            viewModel.clearImportExportResult()
        }
    }
    
    // Name Dialog State
    var showNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                // Profile Section
                SettingsSectionHeader("Profile")
                GlassCard(
                    modifier = Modifier.fillMaxWidth().clickable { 
                        tempName = uiState.userName
                        showNameDialog = true 
                    },
                    contentPadding = PaddingValues(16.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(TempoRed, Color(0xFF991B1B))
                                    ),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = uiState.userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.userName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Tap to edit name",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Name",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                // Notifications
                SettingsSectionHeader("Notifications")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsSwitch(
                            title = "Daily Summary",
                            subtitle = "Get a summary of your listening each day at 8 PM",
                            checked = uiState.dailySummaryEnabled,
                            onCheckedChange = viewModel::toggleDailySummary
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = "Weekly Recap",
                            subtitle = "See your top stats every Sunday evening",
                            checked = uiState.weeklyRecapEnabled,
                            onCheckedChange = viewModel::toggleWeeklyRecap
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = "Achievements",
                            subtitle = "Get notified when you reach milestones",
                            checked = uiState.achievementsEnabled,
                            onCheckedChange = viewModel::toggleAchievements
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tracking
                SettingsSectionHeader("Music Tracking")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = "Manage Permissions",
                            subtitle = "Tempo uses notification access to detect what you're listening to",
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Spotify
                SettingsSectionHeader("Advanced Stats")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        // Use authState flow to ensure UI updates after successful auth
                        when (spotifyAuthState) {
                            is me.avinas.tempo.data.remote.spotify.SpotifyAuthManager.AuthState.Connected -> {
                                SettingsOption(
                                    title = "Connected as ${spotifyViewModel.getUserDisplayName() ?: "Spotify User"}",
                                    subtitle = "Tap to disconnect",
                                    onClick = { showDisconnectDialog = true }
                                )
                            }
                            else -> {
                                SettingsOption(
                                    title = "Connect Spotify",
                                    subtitle = "Unlock mood tracking and audio features",
                                    onClick = { 
                                        val intent = spotifyViewModel.startLogin()
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = "Extended Audio Analysis (Experimental)",
                            subtitle = "Download 30s previews for detailed mood analysis (uses mobile data)",
                            checked = uiState.extendedAudioAnalysisEnabled,
                            onCheckedChange = viewModel::toggleExtendedAudioAnalysis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Data Management
                SettingsSectionHeader("Your Data")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsSwitch(
                            title = "Smart Merge Versions",
                            subtitle = "Treat Live/Remix versions as the same song for cleaner history",
                            checked = uiState.mergeAlternateVersions,
                            onCheckedChange = viewModel::toggleMergeAlternateVersions
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = "Backup & Restore",
                            subtitle = "Export or import your data",
                            onClick = { onNavigateToBackup?.invoke() }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = "Clear All Data",
                            textColor = TempoRed,
                            onClick = { showClearDataDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Community
                SettingsSectionHeader("Community")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = "Reddit Community",
                            subtitle = "r/TempoStats",
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/TempoStats/"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = "Telegram Channel",
                            subtitle = "Confused Coconut",
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/confusedcoconut"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Privacy & About
                SettingsSectionHeader("About")
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = "Rate on Play Store",
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                                    context.startActivity(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
                                    context.startActivity(intent)
                                }
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = "Privacy Policy",
                            onClick = { 
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://tempo.avinas.me/privacy.html"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = "Version",
                            subtitle = versionName,
                            showArrow = false
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Name Edit Dialog
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Update Name") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                         focusedBorderColor = TempoRed,
                         focusedLabelColor = TempoRed,
                         cursorColor = TempoRed
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempName.isNotBlank()) {
                            viewModel.updateUserName(tempName.trim())
                            showNameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TempoRed)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while in progress */ },
            title = { Text("Processing...") },
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
                    if (progress.isIndeterminate) {
                        CircularProgressIndicator()
                    } else {
                        LinearProgressIndicator(
                            progress = { progress.percentage },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${progress.current}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { }
        )
    }
    
    // Conflict Resolution Dialog
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

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear All Data?") },
            text = { Text("This action cannot be undone. All your listening history and preferences will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDataDialog = false
                        onNavigateToOnboarding?.invoke()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect Spotify?") },
            text = { Text("This will remove your Spotify connection. Audio features and mood tracking will no longer be available.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        spotifyViewModel.disconnect()
                        showDisconnectDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
}
