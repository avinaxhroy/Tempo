package me.avinas.tempo.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import me.avinas.tempo.R
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.TempoSnackbar
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.utils.BatteryUtils
import me.avinas.tempo.utils.OemBackgroundHelper
import me.avinas.tempo.utils.PermissionUtils
import me.avinas.tempo.utils.ReviewUtils

internal enum class SettingsPage {
    HUB,
    TRACKING,
    NOTIFICATIONS,
    EXPERIENCE,
    IMPORT_HISTORY,
    DATA_PRIVACY,
    ABOUT,
}

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
    onNavigateToAppleMusicImport: (() -> Unit)? = null,
    onNavigateToYouTubeMusicImport: (() -> Unit)? = null,
    onNavigateToMusicoletImport: (() -> Unit)? = null,
    onNavigateToExtensionImport: (() -> Unit)? = null,
    onNavigateToDesktop: () -> Unit = {},
    onNavigateToEnrichmentReport: (() -> Unit)? = null,
    onNavigateToYourData: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.HUB) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isContentScrolled by remember { mutableStateOf(false) }

    var showProfileDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showCoverSourceDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val importExportProgress by viewModel.importExportProgress.collectAsStateWithLifecycle()
    val importExportResult by viewModel.importExportResult.collectAsStateWithLifecycle()
    val conflictDialogUri by viewModel.showConflictDialog.collectAsStateWithLifecycle()
    val profileImageMessage by viewModel.profileImageMessage.collectAsStateWithLifecycle()
    val analyticsIdResetMessage = stringResource(R.string.settings_analytics_reset_id_done)

    val isXiaomiDevice = remember { OemBackgroundHelper.isXiaomiDevice() }
    var autostartState by remember { mutableStateOf(OemBackgroundHelper.getAutostartState(context)) }
    var notificationListenerEnabled by remember {
        mutableStateOf(PermissionUtils.isNotificationListenerEnabled(context))
    }

    val automaticCoverLabel = stringResource(R.string.settings_cover_source_automatic)
    val coverSourceOptions: List<Pair<AlbumArtSource?, String>> = remember(automaticCoverLabel) {
        listOf(
            null to automaticCoverLabel,
            AlbumArtSource.MUSICBRAINZ to "MusicBrainz",
            AlbumArtSource.ITUNES to "Apple Music / iTunes",
            AlbumArtSource.DEEZER to "Deezer",
        )
    }
    val selectedCoverSourceName =
        coverSourceOptions.firstOrNull { it.first == uiState.preferredCoverSource }?.second
            ?: automaticCoverLabel

    val currentLocale = remember { AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en" }
    val langEnglish = stringResource(R.string.settings_language_english)
    val langFrench = stringResource(R.string.settings_language_french)
    val langGerman = stringResource(R.string.settings_language_german)
    val langHungarian = stringResource(R.string.settings_language_hungarian)
    val langPortuguese = stringResource(R.string.settings_language_portuguese)
    val langRussian = stringResource(R.string.settings_language_russian)
    val languages = remember(langEnglish, langFrench, langGerman, langHungarian, langPortuguese, langRussian) {
        listOf(
            "en" to langEnglish,
            "fr" to langFrench,
            "de" to langGerman,
            "hu" to langHungarian,
            "pt" to langPortuguese,
            "ru" to langRussian,
        )
    }
    val currentLanguageName =
        languages.firstOrNull { it.first == currentLocale }?.second
            ?: langEnglish

    var batteryLevel by remember { mutableIntStateOf(BatteryUtils.getBatteryLevel(context)) }
    val isBatteryCritical = batteryLevel <= BatteryUtils.CRITICAL_BATTERY_LEVEL
    val isLowBattery = batteryLevel <= BatteryUtils.LOW_BATTERY_LEVEL

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(2 * 60 * 1000L)
                batteryLevel = BatteryUtils.getBatteryLevel(context)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autostartState = OemBackgroundHelper.getAutostartState(context)
                notificationListenerEnabled = PermissionUtils.isNotificationListenerEnabled(context)
                batteryLevel = BatteryUtils.getBatteryLevel(context, forceRefresh = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val profileImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let(viewModel::updateProfileImage)
    }

    LaunchedEffect(importExportResult) {
        importExportResult?.let { result ->
            when (result) {
                is ImportExportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.settings_import_success, result.totalRecords, result.imagesCount),
                    )
                }

                is ImportExportResult.Error -> {
                    snackbarHostState.showSnackbar(result.message)
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

    LaunchedEffect(uiState.analyticsIdResetAt) {
        if (uiState.analyticsIdResetAt != null) {
            snackbarHostState.showSnackbar(analyticsIdResetMessage)
            viewModel.clearAnalyticsIdReset()
        }
    }

    BackHandler(enabled = currentPage != SettingsPage.HUB || searchQuery.isNotEmpty()) {
        if (currentPage != SettingsPage.HUB) {
            currentPage = SettingsPage.HUB
        } else {
            searchQuery = ""
        }
    }

    val topBarTitle = when (currentPage) {
        SettingsPage.HUB -> stringResource(R.string.settings_title)
        SettingsPage.TRACKING -> stringResource(R.string.settings_hub_tracking_title)
        SettingsPage.NOTIFICATIONS -> stringResource(R.string.settings_notifications)
        SettingsPage.EXPERIENCE -> stringResource(R.string.settings_hub_experience_title)
        SettingsPage.IMPORT_HISTORY -> stringResource(R.string.settings_import_history)
        SettingsPage.DATA_PRIVACY -> stringResource(R.string.settings_hub_data_title)
        SettingsPage.ABOUT -> stringResource(R.string.settings_hub_about_title)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = topBarTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    currentPage != SettingsPage.HUB -> currentPage = SettingsPage.HUB
                                    searchQuery.isNotEmpty() -> searchQuery = ""
                                    else -> onNavigateBack()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                                tint = TextPrimary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary,
                    ),
                )
                HorizontalDivider(
                    color = if (isContentScrolled) GlassBorderSoft else Color.Transparent,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { TempoSnackbar(it) } },
    ) { padding ->
        DeepOceanBackground {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    val forward = targetState != SettingsPage.HUB
                    val slideInOffset = { width: Int -> if (forward) width / 5 else -width / 5 }
                    val slideOutOffset = { width: Int -> if (forward) -width / 6 else width / 6 }
                    (slideInHorizontally(animationSpec = tween(200), initialOffsetX = slideInOffset) +
                        fadeIn(animationSpec = tween(180)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(180), targetOffsetX = slideOutOffset) +
                                fadeOut(animationSpec = tween(140)),
                        )
                },
                label = "SettingsPageTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { page ->
                val scrollState = rememberScrollState()
                val canScrollBackward by remember(scrollState) {
                    derivedStateOf { scrollState.canScrollBackward }
                }
                LaunchedEffect(page, currentPage, canScrollBackward) {
                    if (page == currentPage) {
                        isContentScrolled = canScrollBackward
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    when (page) {
                        SettingsPage.HUB -> {
                            SettingsHubContent(
                                uiState = uiState,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                notificationListenerEnabled = notificationListenerEnabled,
                                isXiaomiDevice = isXiaomiDevice,
                                autostartState = autostartState,
                                batteryLevel = batteryLevel,
                                isLowBattery = isLowBattery,
                                isBatteryCritical = isBatteryCritical,
                                currentLanguageName = currentLanguageName,
                                selectedCoverSourceName = selectedCoverSourceName,
                                versionName = versionName,
                                onOpenPage = { currentPage = it },
                                onOpenProfileDialog = { showProfileDialog = true },
                                onPickProfilePhoto = { profileImageLauncher.launch("image/*") },
                                onOpenNotificationListenerSettings = { openNotificationAccess(context) },
                                onOpenSupportedApps = { onNavigateToSupportedApps?.invoke() },
                                onOpenBackgroundProtection = { onNavigateToBackgroundProtection?.invoke() },
                                onOpenLanguageDialog = { showLanguageDialog = true },
                                onOpenCoverSourceDialog = { showCoverSourceDialog = true },
                                onOpenDesktop = onNavigateToDesktop,
                                onOpenLastFmImport = { onNavigateToLastFmImport?.invoke() },
                                onOpenSpotifyJsonImport = { onNavigateToSpotifyJsonImport?.invoke() },
                                onOpenDeezerImport = { onNavigateToDeezerImport?.invoke() },
                                onOpenAppleMusicImport = { onNavigateToAppleMusicImport?.invoke() },
                                onOpenYouTubeMusicImport = { onNavigateToYouTubeMusicImport?.invoke() },
                                onOpenMusicoletImport = { onNavigateToMusicoletImport?.invoke() },
                                onOpenExtensionImport = { onNavigateToExtensionImport?.invoke() },
                                onOpenBackup = { onNavigateToBackup?.invoke() },
                                onOpenEnrichmentReport = { onNavigateToEnrichmentReport?.invoke() },
                                onOpenYourData = { onNavigateToYourData?.invoke() },
                                onOpenClearDataDialog = { showClearDataDialog = true },
                                onOpenUrl = { url -> openExternalUrl(context, url) },
                                onRateApp = { ReviewUtils.openPlayStoreListing(context) },
                                onToggleDailySummary = viewModel::toggleDailySummary,
                                onToggleWeeklyRecap = viewModel::toggleWeeklyRecap,
                                onToggleDailyChallenges = viewModel::toggleDailyChallenges,
                                onToggleAchievements = viewModel::toggleAchievements,
                                onTogglePauseLowBattery = viewModel::togglePauseTrackingOnLowBattery,
                                onToggleFilterPodcasts = viewModel::toggleFilterPodcasts,
                                onToggleFilterAudiobooks = viewModel::toggleFilterAudiobooks,
                                onToggleExtendedAudio = viewModel::toggleExtendedAudioAnalysis,
                                onToggleGamification = viewModel::toggleGamificationEnabled,
                                onToggleHideStreaks = viewModel::toggleHideStreaks,
                                onToggleSmartMerge = viewModel::toggleMergeAlternateVersions,
                                onToggleAnalytics = viewModel::setAnalyticsEnabled,
                                onResetAnalyticsId = viewModel::resetAnalyticsId,
                            )
                        }

                        SettingsPage.TRACKING -> {
                            TrackingSettingsPage(
                                uiState = uiState,
                                notificationListenerEnabled = notificationListenerEnabled,
                                isXiaomiDevice = isXiaomiDevice,
                                autostartState = autostartState,
                                batteryLevel = batteryLevel,
                                selectedCoverSourceName = selectedCoverSourceName,
                                onOpenNotificationListenerSettings = { openNotificationAccess(context) },
                                onOpenSupportedApps = { onNavigateToSupportedApps?.invoke() },
                                onOpenBackgroundProtection = { onNavigateToBackgroundProtection?.invoke() },
                                onTogglePauseLowBattery = viewModel::togglePauseTrackingOnLowBattery,
                                onOpenCoverSourceDialog = { showCoverSourceDialog = true },
                                onToggleFilterPodcasts = viewModel::toggleFilterPodcasts,
                                onToggleFilterAudiobooks = viewModel::toggleFilterAudiobooks,
                            )
                        }

                        SettingsPage.NOTIFICATIONS -> {
                            NotificationsSettingsPage(
                                uiState = uiState,
                                onToggleDailySummary = viewModel::toggleDailySummary,
                                onToggleWeeklyRecap = viewModel::toggleWeeklyRecap,
                                onToggleDailyChallenges = viewModel::toggleDailyChallenges,
                                onToggleAchievements = viewModel::toggleAchievements,
                                onOpenExperience = { currentPage = SettingsPage.EXPERIENCE },
                            )
                        }

                        SettingsPage.EXPERIENCE -> {
                            ExperienceSettingsPage(
                                uiState = uiState,
                                currentLanguageName = currentLanguageName,
                                onOpenLanguageDialog = { showLanguageDialog = true },
                                onToggleExtendedAudio = viewModel::toggleExtendedAudioAnalysis,
                                onToggleGamification = viewModel::toggleGamificationEnabled,
                                onToggleHideStreaks = viewModel::toggleHideStreaks,
                            )
                        }

                        SettingsPage.IMPORT_HISTORY -> {
                            ImportHistorySettingsPage(
                                uiState = uiState,
                                onOpenLastFmImport = { onNavigateToLastFmImport?.invoke() },
                                onOpenSpotifyJsonImport = { onNavigateToSpotifyJsonImport?.invoke() },
                                onOpenAppleMusicImport = { onNavigateToAppleMusicImport?.invoke() },
                                onOpenYouTubeMusicImport = { onNavigateToYouTubeMusicImport?.invoke() },
                                onOpenDeezerImport = { onNavigateToDeezerImport?.invoke() },
                                onOpenMusicoletImport = { onNavigateToMusicoletImport?.invoke() },
                                onOpenExtensionImport = { onNavigateToExtensionImport?.invoke() },
                            )
                        }

                        SettingsPage.DATA_PRIVACY -> {
                            DataPrivacySettingsPage(
                                uiState = uiState,
                                onOpenBackup = { onNavigateToBackup?.invoke() },
                                onToggleSmartMerge = viewModel::toggleMergeAlternateVersions,
                                onOpenEnrichmentReport = { onNavigateToEnrichmentReport?.invoke() },
                                onOpenYourData = { onNavigateToYourData?.invoke() },
                                onToggleAnalytics = viewModel::setAnalyticsEnabled,
                                onResetAnalyticsId = viewModel::resetAnalyticsId,
                                onOpenClearDataDialog = { showClearDataDialog = true },
                            )
                        }

                        SettingsPage.ABOUT -> {
                            AboutCommunitySettingsPage(
                                versionName = versionName,
                                onOpenUrl = { url -> openExternalUrl(context, url) },
                                onRateApp = { ReviewUtils.openPlayStoreListing(context) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProfileDialog) {
        ProfileEditDialog(
            initialName = uiState.userName,
            profileImagePath = uiState.profileImagePath,
            onDismiss = { showProfileDialog = false },
            onSaveName = { newName ->
                viewModel.updateUserName(newName)
                showProfileDialog = false
            },
            onChangePhoto = { profileImageLauncher.launch("image/*") },
            onRemovePhoto = viewModel::removeProfileImage,
        )
    }

    if (showLanguageDialog) {
        LanguageSelectorDialog(
            languages = languages,
            currentLocale = currentLocale,
            onSelectLanguage = { langTag ->
                val localeList = LocaleListCompat.forLanguageTags(langTag)
                AppCompatDelegate.setApplicationLocales(localeList)
                showLanguageDialog = false
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showCoverSourceDialog) {
        CoverSourceSelectorDialog(
            options = coverSourceOptions,
            selectedSource = uiState.preferredCoverSource,
            onSelectSource = { source ->
                viewModel.setPreferredCoverSource(source)
                showCoverSourceDialog = false
            },
            onDismiss = { showCoverSourceDialog = false },
        )
    }

    importExportProgress?.let { progress ->
        ImportProgressDialog(progress = progress)
    }

    conflictDialogUri?.let { uri ->
        ImportConflictDialog(
            onReplace = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) },
            onSkip = { viewModel.importData(uri, ImportConflictStrategy.SKIP) },
            onCancel = viewModel::cancelImport,
        )
    }

    if (showClearDataDialog) {
        ClearDataConfirmDialog(
            onConfirm = {
                viewModel.clearAllData()
                showClearDataDialog = false
                onNavigateToOnboarding?.invoke()
            },
            onDismiss = { showClearDataDialog = false },
        )
    }
}

private fun openNotificationAccess(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.settings_notification_settings_error),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.settings_no_browser),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
