package me.avinas.tempo.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.avinas.tempo.R
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.SettingsOption
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.components.TempoDialogBody
import me.avinas.tempo.ui.components.TempoDialogButtonRow
import me.avinas.tempo.ui.components.TempoDialogDangerButton
import me.avinas.tempo.ui.components.TempoDialogIcon
import me.avinas.tempo.ui.components.TempoDialogSecondaryButton
import me.avinas.tempo.ui.components.TempoDialogSurface
import me.avinas.tempo.ui.components.TempoDialogTextField
import me.avinas.tempo.ui.components.TempoDialogTitle
import me.avinas.tempo.ui.components.TempoIcons
import me.avinas.tempo.ui.components.TempoSnackbar
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.utils.BatteryUtils
import me.avinas.tempo.utils.OemBackgroundHelper
import me.avinas.tempo.utils.ReviewUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToBackup: (() -> Unit)? = null,
    onNavigateToSupportedApps: (() -> Unit)? = null,
    onNavigateToBackgroundProtection: (() -> Unit)? = null,
    onNavigateToLastFmImport: (() -> Unit)? = null,
    onNavigateToSpotifyJsonImport: (() -> Unit)? = null,
    onNavigateToDeezerImport: (() -> Unit)? = null,
    onNavigateToYouTubeMusicImport: (() -> Unit)? = null,
    onNavigateToDesktop: () -> Unit = {},
    onNavigateToEnrichmentReport: (() -> Unit)? = null,
    onNavigateToYourData: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val versionName =
        remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "Unknown"
            }
        }
    val scope = rememberCoroutineScope()
    var showClearDataDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Import/Export states
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    val importExportResult by viewModel.importExportResult.collectAsState()
    val conflictDialogUri by viewModel.showConflictDialog.collectAsState()
    val profileImageMessage by viewModel.profileImageMessage.collectAsState()

    // OEM detection for Background Protection
    val isXiaomiDevice = remember { OemBackgroundHelper.isXiaomiDevice() }
    var autostartState by remember { mutableStateOf(OemBackgroundHelper.getAutostartState(context)) }

    // Language selector state
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLocale = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"
    val languages =
        listOf(
            "en" to stringResource(R.string.settings_language_english),
            "fr" to stringResource(R.string.settings_language_french),
            "de" to stringResource(R.string.settings_language_german),
            "hu" to stringResource(R.string.settings_language_hungarian),
            "pt" to stringResource(R.string.settings_language_portuguese),
            "ru" to stringResource(R.string.settings_language_russian),
        )
    val currentLanguageSubtitle =
        languages.firstOrNull { it.first == currentLocale }?.second
            ?: stringResource(R.string.settings_language_english)

    // Battery status monitoring for Desktop Sync
    var batteryLevel by remember { mutableStateOf(BatteryUtils.getBatteryLevel(context)) }
    var isBatteryCritical by remember { mutableStateOf(BatteryUtils.isCriticalBattery(context)) }
    var isLowBattery by remember { mutableStateOf(BatteryUtils.isLowBattery(context)) }

    // Refresh battery level every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(30 * 1000L)
            batteryLevel = BatteryUtils.getBatteryLevel(context)
            isBatteryCritical = BatteryUtils.isCriticalBattery(context)
            isLowBattery = BatteryUtils.isLowBattery(context)
        }
    }

    // Refresh autostart state when returning from BackgroundProtectionScreen
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
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
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let {
                viewModel.startImport(it)
            }
        }

    val profileImageLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let(viewModel::updateProfileImage)
        }

    // File creator for export (ZIP)
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
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
                        context.getString(R.string.settings_import_success, result.totalRecords, result.imagesCount),
                    )
                }

                is ImportExportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        result.message,
                    )
                }
            }
            viewModel.clearImportExportResult()
        }
    }

    LaunchedEffect(profileImageMessage) {
        profileImageMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearProfileImageMessage()
        }
    }

    // Name Dialog State
    var showNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = TextPrimary,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { TempoSnackbar(it) } },
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                // Profile Section
                SettingsSectionHeader(stringResource(R.string.settings_profile))
                GlassCard(
                    modifier =
                        Modifier.fillMaxWidth().clickable {
                            tempName = uiState.userName
                            showNameDialog = true
                        },
                    contentPadding = PaddingValues(16.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(64.dp)
                                    .clickable {
                                        profileImageLauncher.launch("image/*")
                                    },
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(
                                            brush =
                                                androidx.compose.ui.graphics.Brush.linearGradient(
                                                    colors = listOf(TempoPrimary, TempoPrimaryDeep),
                                                ),
                                            shape = CircleShape,
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.profileImagePath.isNullOrBlank()) {
                                    Text(
                                        text =
                                            uiState.userName
                                                .firstOrNull()
                                                ?.toString()
                                                ?.uppercase() ?: "U",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = TextPrimary,
                                    )
                                } else {
                                    me.avinas.tempo.ui.components.CachedAsyncImage(
                                        imageUrl = uiState.profileImagePath,
                                        contentDescription = stringResource(R.string.settings_profile_photo),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = TempoDarkSurfaceSunken,
                                tonalElevation = 0.dp,
                                modifier = Modifier.size(22.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.settings_change_photo),
                                        tint = TextPrimary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.userName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = TextPrimary,
                            )
                            Text(
                                text = stringResource(R.string.settings_tap_to_edit),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                            Text(
                                text = stringResource(R.string.settings_tap_photo_to_change),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextQuaternary,
                            )
                        }

                        if (uiState.profileImagePath.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.settings_update_name),
                                tint = TextPrimary.copy(alpha = 0.6f),
                            )
                        } else {
                            TextButton(onClick = viewModel::removeProfileImage) {
                                Text(
                                    text = stringResource(R.string.settings_remove_photo),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Language Section
                SettingsSectionHeader(stringResource(R.string.settings_language))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    SettingsOption(
                        title = stringResource(R.string.settings_language_title),
                        subtitle = currentLanguageSubtitle,
                        onClick = { showLanguageDialog = true },
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Notifications
                SettingsSectionHeader(stringResource(R.string.settings_notifications))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_daily_summary),
                            subtitle = stringResource(R.string.settings_daily_summary_desc),
                            checked = uiState.dailySummaryEnabled,
                            onCheckedChange = viewModel::toggleDailySummary,
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsSwitch(
                            title = stringResource(R.string.settings_weekly_recap),
                            subtitle = stringResource(R.string.settings_weekly_recap_desc),
                            checked = uiState.weeklyRecapEnabled,
                            onCheckedChange = viewModel::toggleWeeklyRecap,
                        )
                        if (uiState.isGamificationEnabled) {
                            HorizontalDivider(color = GlassBorderSoft)
                            SettingsSwitch(
                                title = stringResource(R.string.settings_daily_challenges),
                                subtitle = stringResource(R.string.settings_daily_challenges_desc),
                                checked = uiState.dailyChallengesEnabled,
                                onCheckedChange = viewModel::toggleDailyChallenges,
                            )
                            HorizontalDivider(color = GlassBorderSoft)
                            SettingsSwitch(
                                title = stringResource(R.string.settings_achievements),
                                subtitle = stringResource(R.string.settings_achievements_desc),
                                checked = uiState.achievementsEnabled,
                                onCheckedChange = viewModel::toggleAchievements,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tracking
                SettingsSectionHeader(stringResource(R.string.settings_music_tracking))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_manage_permissions),
                            subtitle = stringResource(R.string.settings_manage_permissions_desc),
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.settings_notification_settings_error),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_manage_apps),
                            subtitle = stringResource(R.string.settings_manage_apps_desc),
                            onClick = { onNavigateToSupportedApps?.invoke() },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsSwitch(
                            title = stringResource(R.string.settings_pause_tracking_low_battery),
                            subtitle =
                                if (uiState.pauseTrackingOnLowBattery) {
                                    stringResource(R.string.settings_pause_tracking_on_desc, batteryLevel)
                                } else {
                                    stringResource(R.string.settings_pause_tracking_off_desc)
                                },
                            checked = uiState.pauseTrackingOnLowBattery,
                            onCheckedChange = viewModel::togglePauseTrackingOnLowBattery,
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
                        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                    ) {
                        SettingsOption(
                            title =
                                if (autostartState == OemBackgroundHelper.AutostartState.DISABLED) {
                                    stringResource(R.string.settings_configure_required)
                                } else {
                                    stringResource(R.string.settings_xiaomi_settings)
                                },
                            subtitle = stringResource(R.string.settings_prevent_killed),
                            onClick = { onNavigateToBackgroundProtection?.invoke() },
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Content Filtering
                SettingsSectionHeader(stringResource(R.string.settings_content_filtering))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_filter_podcasts),
                            subtitle = stringResource(R.string.settings_filter_podcasts_desc),
                            checked = uiState.filterPodcasts,
                            onCheckedChange = viewModel::toggleFilterPodcasts,
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsSwitch(
                            title = stringResource(R.string.settings_filter_audiobooks),
                            subtitle = stringResource(R.string.settings_filter_audiobooks_desc),
                            checked = uiState.filterAudiobooks,
                            onCheckedChange = viewModel::toggleFilterAudiobooks,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Advanced Settings
                SettingsSectionHeader(stringResource(R.string.settings_advanced_stats))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_extended_audio),
                            subtitle = stringResource(R.string.settings_extended_audio_desc),
                            checked = uiState.extendedAudioAnalysisEnabled,
                            onCheckedChange = viewModel::toggleExtendedAudioAnalysis,
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsSwitch(
                            title = stringResource(R.string.settings_enable_gamification),
                            subtitle = stringResource(R.string.settings_enable_gamification_desc),
                            checked = uiState.isGamificationEnabled,
                            onCheckedChange = viewModel::toggleGamificationEnabled,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Last.fm Import
                SettingsSectionHeader(stringResource(R.string.settings_import_history))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        if (uiState.isLastFmConnected) {
                            SettingsOption(
                                title = stringResource(R.string.settings_lastfm_connected, uiState.lastFmUsername ?: "Connected"),
                                subtitle =
                                    when (uiState.lastFmSyncFrequency) {
                                        "DAILY" -> stringResource(R.string.settings_lastfm_sync_daily)
                                        "WEEKLY" -> stringResource(R.string.settings_lastfm_sync_weekly)
                                        else -> stringResource(R.string.settings_lastfm_import_complete)
                                    },
                                onClick = { onNavigateToLastFmImport?.invoke() },
                            )
                        } else {
                            SettingsOption(
                                title = stringResource(R.string.settings_import_lastfm),
                                subtitle = stringResource(R.string.settings_import_lastfm_desc),
                                onClick = { onNavigateToLastFmImport?.invoke() },
                            )
                        }

                        HorizontalDivider(color = GlassBorderSoft)

                        SettingsOption(
                            title = stringResource(R.string.settings_import_spotify_json),
                            subtitle = stringResource(R.string.settings_import_spotify_json_desc),
                            onClick = { onNavigateToSpotifyJsonImport?.invoke() },
                        )

                        HorizontalDivider(color = GlassBorderSoft)

                        SettingsOption(
                            title = stringResource(R.string.settings_import_deezer),
                            subtitle = stringResource(R.string.settings_import_deezer_desc),
                            onClick = { onNavigateToDeezerImport?.invoke() },
                        )

                        HorizontalDivider(color = GlassBorderSoft)

                        SettingsOption(
                            title = stringResource(R.string.settings_import_youtube_music),
                            subtitle = stringResource(R.string.settings_import_youtube_music_desc),
                            onClick = { onNavigateToYouTubeMusicImport?.invoke() },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Desktop Sync
                SettingsSectionHeader(stringResource(R.string.desktop_link_section_header))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        // Battery status warning if critical or low
                        if (isBatteryCritical) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(TempoErrorDeep.copy(alpha = 0.25f))
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_desktop_battery_critical, batteryLevel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TempoErrorSoft,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider(color = GlassBorderSoft)
                        } else if (isLowBattery) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(TempoWarningDeep.copy(alpha = 0.2f))
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_desktop_battery_low, batteryLevel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TempoWarningBright,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider(color = GlassBorderSoft)
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(TempoSuccessDeep.copy(alpha = 0.15f))
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_desktop_battery_healthy, batteryLevel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TempoSuccessBright,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider(color = GlassBorderSoft)
                        }

                        SettingsOption(
                            title = stringResource(R.string.desktop_link_settings_title),
                            subtitle = stringResource(R.string.desktop_link_settings_subtitle),
                            onClick = { onNavigateToDesktop() },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Data Management
                SettingsSectionHeader(stringResource(R.string.settings_your_data))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        // The opt-out for anonymous app-health reporting lives here, at the
                        // top of "Your Data" next to backup/restore rather than buried in
                        // About. Hidden entirely in builds that cannot report (no Aptabase
                        // key, or debug), since a switch for collection that cannot happen
                        // would be misleading.
                        if (uiState.analyticsConfigured) {
                            SettingsSwitch(
                                title = stringResource(R.string.settings_analytics_toggle),
                                subtitle = stringResource(R.string.settings_analytics_toggle_desc),
                                checked = uiState.analyticsEnabled,
                                onCheckedChange = viewModel::setAnalyticsEnabled,
                            )
                            HorizontalDivider(color = GlassBorderSoft)
                        }
                        SettingsSwitch(
                            title = stringResource(R.string.settings_smart_merge),
                            subtitle = stringResource(R.string.settings_smart_merge_desc),
                            checked = uiState.mergeAlternateVersions,
                            onCheckedChange = viewModel::toggleMergeAlternateVersions,
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_backup_restore),
                            subtitle = stringResource(R.string.settings_backup_restore_desc),
                            onClick = { onNavigateToBackup?.invoke() },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.enrichment_report_settings_option),
                            subtitle = stringResource(R.string.enrichment_report_settings_option_desc),
                            onClick = { onNavigateToEnrichmentReport?.invoke() },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_clear_all),
                            textColor = TempoError,
                            onClick = { showClearDataDialog = true },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Community
                SettingsSectionHeader(stringResource(R.string.settings_community))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_reddit),
                            subtitle = stringResource(R.string.settings_reddit_sub),
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/TempoStats/"))
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        HorizontalDivider(color = GlassBorderSoft)

                        SettingsOption(
                            title = stringResource(R.string.settings_github),
                            subtitle = stringResource(R.string.settings_github_desc),
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avinaxhroy/Tempo"))
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_contribute),
                            subtitle = stringResource(R.string.settings_contribute_desc),
                            onClick = {
                                try {
                                    val intent =
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/avinaxhroy/Tempo/blob/main/CONTRIBUTION.md"),
                                        )
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Privacy & About
                SettingsSectionHeader(stringResource(R.string.settings_about))
                GlassCard(
                    contentPadding = PaddingValues(0.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
                ) {
                    Column {
                        SettingsOption(
                            title = stringResource(R.string.settings_rate_play_store),
                            onClick = {
                                ReviewUtils.openPlayStoreListing(context)
                            },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_privacy_policy),
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tempo.avinash.im/privacy/"))
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_data_diagnostics),
                            subtitle = stringResource(R.string.settings_data_diagnostics_desc),
                            onClick = { onNavigateToYourData?.invoke() },
                        )
                        HorizontalDivider(color = GlassBorderSoft)
                        SettingsOption(
                            title = stringResource(R.string.settings_version),
                            subtitle = versionName,
                            showArrow = false,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Name Edit Dialog
    if (showNameDialog) {
        Dialog(onDismissRequest = { showNameDialog = false }) {
            TempoDialogSurface {
                TempoDialogIcon(
                    icon = TempoIcons.Edit,
                    tint = TempoPrimary,
                    size = 48,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TempoDialogTitle(text = stringResource(R.string.settings_update_name))
                Spacer(modifier = Modifier.height(6.dp))
                TempoDialogBody(text = stringResource(R.string.settings_name_dialog_body))
                Spacer(modifier = Modifier.height(20.dp))
                TempoDialogTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = stringResource(R.string.settings_display_name),
                )
                Spacer(modifier = Modifier.height(24.dp))
                TempoDialogButtonRow(
                    primaryText = stringResource(R.string.settings_save),
                    onPrimary = {
                        if (tempName.isNotBlank()) {
                            viewModel.updateUserName(tempName.trim())
                            showNameDialog = false
                        }
                    },
                    secondaryText = stringResource(R.string.settings_cancel),
                    onSecondary = { showNameDialog = false },
                    primaryEnabled = tempName.isNotBlank(),
                )
            }
        }
    }

    // Language Selector Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_select_language), color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    languages.forEach { (langTag, langName) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val localeList = LocaleListCompat.forLanguageTags(langTag)
                                        AppCompatDelegate.setApplicationLocales(localeList)
                                        showLanguageDialog = false
                                        (context as? Activity)?.recreate()
                                    }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentLocale == langTag,
                                onClick = {
                                    val localeList = LocaleListCompat.forLanguageTags(langTag)
                                    AppCompatDelegate.setApplicationLocales(localeList)
                                    showLanguageDialog = false
                                    (context as? Activity)?.recreate()
                                },
                                colors =
                                    RadioButtonDefaults.colors(
                                        selectedColor = TempoPrimary,
                                    ),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = langName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.settings_cancel), color = TextTertiary)
                }
            },
        )
    }

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while in progress */ },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_processing), color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = progress.phase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (progress.isIndeterminate) {
                        CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                    } else {
                        LinearProgressIndicator(
                            progress = { progress.percentage.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = TempoPrimary,
                            trackColor = GlassFrostSoft,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${progress.current}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                }
            },
            confirmButton = { },
        )
    }

    // Conflict Resolution Dialog
    conflictDialogUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            containerColor = TempoSurfaceDialog,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_import_options), color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(stringResource(R.string.settings_import_conflict_msg), color = TextSecondary)
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) },
                ) {
                    Text(stringResource(R.string.settings_replace_existing), color = TempoPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text(stringResource(R.string.settings_cancel), color = TextTertiary)
                    }
                    TextButton(
                        onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) },
                    ) {
                        Text(stringResource(R.string.settings_skip_duplicates), color = TextSecondary)
                    }
                }
            },
        )
    }

    if (showClearDataDialog) {
        Dialog(onDismissRequest = { showClearDataDialog = false }) {
            TempoDialogSurface {
                TempoDialogIcon(
                    icon = TempoIcons.Trash,
                    tint = TempoError,
                    size = 48,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TempoDialogTitle(text = stringResource(R.string.settings_clear_data_title))
                Spacer(modifier = Modifier.height(8.dp))
                TempoDialogBody(text = stringResource(R.string.settings_clear_data_msg))
                Spacer(modifier = Modifier.height(24.dp))
                TempoDialogDangerButton(
                    text = stringResource(R.string.settings_clear_everything),
                    onClick = {
                        viewModel.clearAllData()
                        showClearDataDialog = false
                        onNavigateToOnboarding?.invoke()
                    },
                    icon = TempoIcons.Trash,
                )
                Spacer(modifier = Modifier.height(6.dp))
                TempoDialogSecondaryButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = { showClearDataDialog = false },
                )
            }
        }
    }
}
