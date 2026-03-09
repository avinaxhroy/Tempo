package me.avinas.tempo.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.SettingsOption
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.utils.OemBackgroundHelper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToBackup: (() -> Unit)? = null,
    onNavigateToSupportedApps: (() -> Unit)? = null,
    onNavigateToBackgroundProtection: (() -> Unit)? = null,
    onNavigateToLastFmImport: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val spotifyAuthState by spotifyViewModel.authState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    
    // OEM detection for Background Protection
    val isXiaomiDevice = remember { OemBackgroundHelper.isXiaomiDevice() }
    var autostartState by remember { mutableStateOf(OemBackgroundHelper.getAutostartState(context)) }
    
    // Language selector state
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLocale = remember {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (appLocales.isEmpty) "en" else appLocales.get(0)?.language ?: "en"
    }
    
    // Refresh autostart state when returning from BackgroundProtectionScreen
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autostartState = OemBackgroundHelper.getAutostartState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
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
    val importSuccessMsg = stringResource(R.string.settings_import_success, 0, 0)
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
                title = { Text(stringResource(R.string.settings_title), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = Color.White)
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
                SettingsSectionHeader(stringResource(R.string.settings_profile))
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
                                text = stringResource(R.string.settings_tap_to_edit),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.settings_update_name),
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                // Language Section
                SettingsSectionHeader(stringResource(R.string.settings_language))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    SettingsOption(
                        title = stringResource(R.string.settings_language_title),
                        subtitle = if (currentLocale == "fr") 
                            stringResource(R.string.settings_language_french) 
                        else 
                            stringResource(R.string.settings_language_english),
                        onClick = { showLanguageDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Notifications
                SettingsSectionHeader(stringResource(R.string.settings_notifications))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_daily_summary),
                            subtitle = stringResource(R.string.settings_daily_summary_desc),
                            checked = uiState.dailySummaryEnabled,
                            onCheckedChange = viewModel::toggleDailySummary
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = stringResource(R.string.settings_weekly_recap),
                            subtitle = stringResource(R.string.settings_weekly_recap_desc),
                            checked = uiState.weeklyRecapEnabled,
                            onCheckedChange = viewModel::toggleWeeklyRecap
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = stringResource(R.string.settings_daily_challenges),
                            subtitle = stringResource(R.string.settings_daily_challenges_desc),
                            checked = uiState.dailyChallengesEnabled,
                            onCheckedChange = viewModel::toggleDailyChallenges
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = stringResource(R.string.settings_achievements),
                            subtitle = stringResource(R.string.settings_achievements_desc),
                            checked = uiState.achievementsEnabled,
                            onCheckedChange = viewModel::toggleAchievements
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tracking
                SettingsSectionHeader(stringResource(R.string.settings_music_tracking))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_manage_permissions),
                            subtitle = stringResource(R.string.settings_manage_permissions_desc),
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_manage_apps),
                            subtitle = stringResource(R.string.settings_manage_apps_desc),
                            onClick = { onNavigateToSupportedApps?.invoke() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Background Protection - Only for Xiaomi/MIUI devices
                if (isXiaomiDevice) {
                    SettingsSectionHeader(stringResource(R.string.settings_background_protection))
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                    ) {
                        SettingsOption(
                            title = if (autostartState == OemBackgroundHelper.AutostartState.DISABLED) 
                                stringResource(R.string.settings_configure_required)
                            else 
                                stringResource(R.string.settings_xiaomi_settings),
                            subtitle = stringResource(R.string.settings_prevent_killed),
                            onClick = { onNavigateToBackgroundProtection?.invoke() }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                // Content Filtering
                SettingsSectionHeader(stringResource(R.string.settings_content_filtering))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_filter_podcasts),
                            subtitle = stringResource(R.string.settings_filter_podcasts_desc),
                            checked = uiState.filterPodcasts,
                            onCheckedChange = viewModel::toggleFilterPodcasts
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = stringResource(R.string.settings_filter_audiobooks),
                            subtitle = stringResource(R.string.settings_filter_audiobooks_desc),
                            checked = uiState.filterAudiobooks,
                            onCheckedChange = viewModel::toggleFilterAudiobooks
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Spotify
                SettingsSectionHeader(stringResource(R.string.settings_advanced_stats))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        // Use authState flow to ensure UI updates after successful auth
                        when (spotifyAuthState) {
                            is me.avinas.tempo.data.remote.spotify.SpotifyAuthManager.AuthState.Connected -> {
                                SettingsOption(
                                    title = stringResource(R.string.settings_connected_as, spotifyViewModel.getUserDisplayName() ?: "Spotify User"),
                                    subtitle = stringResource(R.string.settings_tap_to_disconnect),
                                    onClick = { showDisconnectDialog = true }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                // Spotify-API-Only Mode toggle (only shown when connected)
                                SettingsSwitch(
                                    title = stringResource(R.string.settings_spotify_api_mode),
                                    subtitle = if (uiState.spotifyApiOnlyMode) 
                                        stringResource(R.string.settings_spotify_api_on)
                                    else 
                                        stringResource(R.string.settings_spotify_api_off),
                                    checked = uiState.spotifyApiOnlyMode,
                                    onCheckedChange = viewModel::toggleSpotifyApiOnlyMode
                                )
                            }
                            else -> {
                                SettingsOption(
                                    title = stringResource(R.string.settings_connect_spotify),
                                    subtitle = stringResource(R.string.settings_connect_spotify_desc),
                                    onClick = { 
                                        val intent = spotifyViewModel.startLogin()
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsSwitch(
                            title = stringResource(R.string.settings_extended_audio),
                            subtitle = stringResource(R.string.settings_extended_audio_desc),
                            checked = uiState.extendedAudioAnalysisEnabled,
                            onCheckedChange = viewModel::toggleExtendedAudioAnalysis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Last.fm Import
                SettingsSectionHeader(stringResource(R.string.settings_import_history))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        if (uiState.isLastFmConnected) {
                            SettingsOption(
                                title = stringResource(R.string.settings_lastfm_connected, uiState.lastFmUsername ?: "Connected"),
                                subtitle = when (uiState.lastFmSyncFrequency) {
                                    "DAILY" -> stringResource(R.string.settings_lastfm_sync_daily)
                                    "WEEKLY" -> stringResource(R.string.settings_lastfm_sync_weekly)
                                    else -> stringResource(R.string.settings_lastfm_import_complete)
                                },
                                onClick = { onNavigateToLastFmImport?.invoke() }
                            )
                        } else {
                            SettingsOption(
                                title = stringResource(R.string.settings_import_lastfm),
                                subtitle = stringResource(R.string.settings_import_lastfm_desc),
                                onClick = { onNavigateToLastFmImport?.invoke() }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Data Management
                SettingsSectionHeader(stringResource(R.string.settings_your_data))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_smart_merge),
                            subtitle = stringResource(R.string.settings_smart_merge_desc),
                            checked = uiState.mergeAlternateVersions,
                            onCheckedChange = viewModel::toggleMergeAlternateVersions
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_backup_restore),
                            subtitle = stringResource(R.string.settings_backup_restore_desc),
                            onClick = { onNavigateToBackup?.invoke() }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_clear_all),
                            textColor = TempoRed,
                            onClick = { showClearDataDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Community
                SettingsSectionHeader(stringResource(R.string.settings_community))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_reddit),
                            subtitle = stringResource(R.string.settings_reddit_sub),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/TempoStats/"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_telegram),
                            subtitle = stringResource(R.string.settings_telegram_name),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/confusedcoconut"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_github),
                            subtitle = stringResource(R.string.settings_github_desc),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avinaxhroy/Tempo"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_contribute),
                            subtitle = stringResource(R.string.settings_contribute_desc),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avinaxhroy/Tempo/blob/main/CONTRIBUTION.md"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Privacy & About
                SettingsSectionHeader(stringResource(R.string.settings_about))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_rate_play_store),
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
                            title = stringResource(R.string.settings_privacy_policy),
                            onClick = { 
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://tempo.avinas.me/privacy.html"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        SettingsOption(
                            title = stringResource(R.string.settings_version),
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
            title = { Text(stringResource(R.string.settings_update_name)) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text(stringResource(R.string.settings_display_name)) },
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
                    Text(stringResource(R.string.settings_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
    
    // Language Selector Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_select_language)) },
            text = {
                Column {
                    val languages = listOf(
                        "en" to stringResource(R.string.settings_language_english),
                        "fr" to stringResource(R.string.settings_language_french),
                        "de" to stringResource(R.string.settings_language_german),
                        "hu" to stringResource(R.string.settings_language_hungarian)
                    )
                    languages.forEach { (langTag, langName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(langTag)
                                    )
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLocale == langTag,
                                onClick = {
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(langTag)
                                    )
                                    showLanguageDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = TempoRed
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = langName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while in progress */ },
            title = { Text(stringResource(R.string.settings_processing)) },
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
            title = { Text(stringResource(R.string.settings_import_options)) },
            text = { 
                Text(stringResource(R.string.settings_import_conflict_msg)) 
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }
                ) {
                    Text(stringResource(R.string.settings_replace_existing))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    TextButton(
                        onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }
                    ) {
                        Text(stringResource(R.string.settings_skip_duplicates))
                    }
                }
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(stringResource(R.string.settings_clear_data_title)) },
            text = { Text(stringResource(R.string.settings_clear_data_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDataDialog = false
                        onNavigateToOnboarding?.invoke()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_clear_everything))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
    
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_disconnect_spotify_title)) },
            text = { Text(stringResource(R.string.settings_disconnect_spotify_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        spotifyViewModel.disconnect()
                        showDisconnectDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
    
}
