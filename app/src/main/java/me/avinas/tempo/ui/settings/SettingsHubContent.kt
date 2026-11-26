package me.avinas.tempo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsHubRow
import me.avinas.tempo.ui.components.SettingsInsetDivider
import me.avinas.tempo.ui.components.SettingsOption
import me.avinas.tempo.ui.components.SettingsSearchBar
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.SettingsStatusBanner
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.utils.OemBackgroundHelper

private val HubCardShape = RoundedCornerShape(20.dp)
private val HubEditBadgeShape = RoundedCornerShape(10.dp)
private val HubAvatarGradientBrush = Brush.linearGradient(listOf(TempoPrimary, TempoPrimaryDeep))

@Immutable
private sealed interface SearchResultEntry {
    val key: String
    val category: String
    val title: String
    val subtitle: String?
    val keywords: String
    val icon: ImageVector

    data class Action(
        override val key: String,
        override val category: String,
        override val title: String,
        override val subtitle: String?,
        override val keywords: String,
        override val icon: ImageVector,
        val textColor: Color = TextPrimary,
        val badgeText: String? = null,
        val badgeColor: Color = TempoPrimary,
        val onClick: (() -> Unit)? = null,
    ) : SearchResultEntry

    data class Toggle(
        override val key: String,
        override val category: String,
        override val title: String,
        override val subtitle: String?,
        override val keywords: String,
        override val icon: ImageVector,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SearchResultEntry
}

@Composable
internal fun SettingsHubContent(
    uiState: SettingsUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    notificationListenerEnabled: Boolean,
    isXiaomiDevice: Boolean,
    autostartState: OemBackgroundHelper.AutostartState,
    batteryLevel: Int,
    isLowBattery: Boolean,
    isBatteryCritical: Boolean,
    currentLanguageName: String,
    selectedCoverSourceName: String,
    versionName: String,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenProfileDialog: () -> Unit,
    onPickProfilePhoto: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenSupportedApps: () -> Unit,
    onOpenBackgroundProtection: () -> Unit,
    onOpenLanguageDialog: () -> Unit,
    onOpenCoverSourceDialog: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenLastFmImport: () -> Unit,
    onOpenSpotifyJsonImport: () -> Unit,
    onOpenDeezerImport: () -> Unit,
    onOpenAppleMusicImport: () -> Unit,
    onOpenYouTubeMusicImport: () -> Unit,
    onOpenMusicoletImport: () -> Unit,
    onOpenExtensionImport: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenEnrichmentReport: () -> Unit,
    onOpenYourData: () -> Unit,
    onOpenClearDataDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRateApp: () -> Unit,
    onToggleDailySummary: (Boolean) -> Unit,
    onToggleWeeklyRecap: (Boolean) -> Unit,
    onToggleDailyChallenges: (Boolean) -> Unit,
    onToggleAchievements: (Boolean) -> Unit,
    onTogglePauseLowBattery: (Boolean) -> Unit,
    onToggleFilterPodcasts: (Boolean) -> Unit,
    onToggleFilterAudiobooks: (Boolean) -> Unit,
    onToggleExtendedAudio: (Boolean) -> Unit,
    onToggleGamification: (Boolean) -> Unit,
    onToggleHideStreaks: (Boolean) -> Unit,
    onToggleSmartMerge: (Boolean) -> Unit,
    onToggleAnalytics: (Boolean) -> Unit,
    onResetAnalyticsId: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = stringResource(R.string.settings_search_placeholder),
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (searchQuery.isNotBlank()) {
            SettingsSearchResults(
                query = searchQuery,
                uiState = uiState,
                notificationListenerEnabled = notificationListenerEnabled,
                isXiaomiDevice = isXiaomiDevice,
                autostartState = autostartState,
                batteryLevel = batteryLevel,
                isLowBattery = isLowBattery,
                isBatteryCritical = isBatteryCritical,
                currentLanguageName = currentLanguageName,
                selectedCoverSourceName = selectedCoverSourceName,
                versionName = versionName,
                onOpenProfileDialog = onOpenProfileDialog,
                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                onOpenSupportedApps = onOpenSupportedApps,
                onOpenBackgroundProtection = onOpenBackgroundProtection,
                onOpenLanguageDialog = onOpenLanguageDialog,
                onOpenCoverSourceDialog = onOpenCoverSourceDialog,
                onOpenDesktop = onOpenDesktop,
                onOpenLastFmImport = onOpenLastFmImport,
                onOpenSpotifyJsonImport = onOpenSpotifyJsonImport,
                onOpenDeezerImport = onOpenDeezerImport,
                onOpenAppleMusicImport = onOpenAppleMusicImport,
                onOpenYouTubeMusicImport = onOpenYouTubeMusicImport,
                onOpenMusicoletImport = onOpenMusicoletImport,
                onOpenExtensionImport = onOpenExtensionImport,
                onOpenBackup = onOpenBackup,
                onOpenEnrichmentReport = onOpenEnrichmentReport,
                onOpenYourData = onOpenYourData,
                onOpenClearDataDialog = onOpenClearDataDialog,
                onOpenUrl = onOpenUrl,
                onRateApp = onRateApp,
                onToggleDailySummary = onToggleDailySummary,
                onToggleWeeklyRecap = onToggleWeeklyRecap,
                onToggleDailyChallenges = onToggleDailyChallenges,
                onToggleAchievements = onToggleAchievements,
                onTogglePauseLowBattery = onTogglePauseLowBattery,
                onToggleFilterPodcasts = onToggleFilterPodcasts,
                onToggleFilterAudiobooks = onToggleFilterAudiobooks,
                onToggleExtendedAudio = onToggleExtendedAudio,
                onToggleGamification = onToggleGamification,
                onToggleHideStreaks = onToggleHideStreaks,
                onToggleSmartMerge = onToggleSmartMerge,
                onToggleAnalytics = onToggleAnalytics,
                onResetAnalyticsId = onResetAnalyticsId,
            )
        } else {
            ProfileHeroCard(
                userName = uiState.userName,
                profileImagePath = uiState.profileImagePath,
                onCardClick = onOpenProfileDialog,
                onAvatarClick = onPickProfilePhoto,
            )

            // Conditional attention banner — only surfaces when user action is needed
            if (!notificationListenerEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                SettingsStatusBanner(
                    icon = Icons.Rounded.WarningAmber,
                    title = stringResource(R.string.settings_banner_permission_title),
                    subtitle = stringResource(R.string.settings_banner_permission_desc),
                    actionLabel = stringResource(R.string.settings_banner_permission_action),
                    accentColor = TempoWarning,
                    onClick = onOpenNotificationListenerSettings,
                )
            } else if (isXiaomiDevice && autostartState == OemBackgroundHelper.AutostartState.DISABLED) {
                Spacer(modifier = Modifier.height(14.dp))
                SettingsStatusBanner(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.settings_banner_xiaomi_title),
                    subtitle = stringResource(R.string.settings_banner_xiaomi_desc),
                    actionLabel = stringResource(R.string.settings_banner_xiaomi_action),
                    accentColor = TempoWarning,
                    onClick = onOpenBackgroundProtection,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Group 1: Core Experience
            val trackingNeedsAttention = !notificationListenerEnabled ||
                (isXiaomiDevice && autostartState == OemBackgroundHelper.AutostartState.DISABLED)
            val trackingSubtitle = when {
                !notificationListenerEnabled -> stringResource(R.string.settings_hub_tracking_permission_needed)
                isXiaomiDevice && autostartState == OemBackgroundHelper.AutostartState.DISABLED ->
                    stringResource(R.string.settings_hub_tracking_xiaomi_needed)
                else -> {
                    val filterText = if (uiState.filterPodcasts || uiState.filterAudiobooks) {
                        stringResource(R.string.settings_hub_tracking_filters_on)
                    } else {
                        stringResource(R.string.settings_hub_tracking_filters_off)
                    }
                    "${stringResource(R.string.settings_hub_tracking_active)} · $filterText"
                }
            }

            val totalNotifCount = if (uiState.isGamificationEnabled) 4 else 2
            val enabledNotifCount = listOfNotNull(
                uiState.dailySummaryEnabled,
                uiState.weeklyRecapEnabled,
                uiState.dailyChallengesEnabled.takeIf { uiState.isGamificationEnabled },
                uiState.achievementsEnabled.takeIf { uiState.isGamificationEnabled },
            ).count { it }
            val notificationsSubtitle = if (enabledNotifCount == 0) {
                stringResource(R.string.settings_hub_notifications_all_off)
            } else {
                stringResource(R.string.settings_hub_notifications_summary, enabledNotifCount, totalNotifCount)
            }

            val gamificationLabel = if (uiState.isGamificationEnabled) {
                stringResource(R.string.settings_hub_experience_gamification_on)
            } else {
                stringResource(R.string.settings_hub_experience_gamification_off)
            }
            val experienceSubtitle = "$currentLanguageName · $gamificationLabel"

            SettingsSectionHeader(stringResource(R.string.settings_group_core))
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                Column {
                    SettingsHubRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = stringResource(R.string.settings_hub_tracking_title),
                        subtitle = trackingSubtitle,
                        iconTint = if (trackingNeedsAttention) TempoWarning else TempoPrimary,
                        statusDotColor = if (trackingNeedsAttention) TempoWarning else TempoSuccess,
                        onClick = { onOpenPage(SettingsPage.TRACKING) },
                    )
                    SettingsInsetDivider(hasLeadingIcon = true)
                    SettingsHubRow(
                        icon = Icons.Rounded.NotificationsNone,
                        title = stringResource(R.string.settings_notifications),
                        subtitle = notificationsSubtitle,
                        iconTint = TempoInfo,
                        onClick = { onOpenPage(SettingsPage.NOTIFICATIONS) },
                    )
                    SettingsInsetDivider(hasLeadingIcon = true)
                    SettingsHubRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.settings_hub_experience_title),
                        subtitle = experienceSubtitle,
                        iconTint = TempoPrimary,
                        onClick = { onOpenPage(SettingsPage.EXPERIENCE) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Group 2: Connections & Import
            val importSubtitle = if (uiState.isLastFmConnected && !uiState.lastFmUsername.isNullOrBlank()) {
                stringResource(R.string.settings_hub_import_connected, uiState.lastFmUsername)
            } else {
                stringResource(R.string.settings_hub_import_default)
            }
            val desktopBadgeText = when {
                isBatteryCritical -> stringResource(R.string.settings_status_critical_battery, batteryLevel)
                isLowBattery -> stringResource(R.string.settings_status_low_battery, batteryLevel)
                else -> null
            }
            val desktopBadgeColor = if (isBatteryCritical) TempoError else TempoWarning

            SettingsSectionHeader(stringResource(R.string.settings_group_connections))
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                Column {
                    SettingsHubRow(
                        icon = Icons.Rounded.CloudDownload,
                        title = stringResource(R.string.settings_hub_import_title),
                        subtitle = importSubtitle,
                        iconTint = TempoPrimary,
                        badgeText = if (uiState.isLastFmConnected) stringResource(R.string.settings_status_connected) else null,
                        badgeColor = TempoSuccess,
                        onClick = { onOpenPage(SettingsPage.IMPORT_HISTORY) },
                    )
                    SettingsInsetDivider(hasLeadingIcon = true)
                    SettingsHubRow(
                        icon = Icons.Rounded.Devices,
                        title = stringResource(R.string.desktop_link_settings_title),
                        subtitle = stringResource(R.string.desktop_link_settings_subtitle),
                        iconTint = TempoInfo,
                        badgeText = desktopBadgeText,
                        badgeColor = desktopBadgeColor,
                        onClick = onOpenDesktop,
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Group 3: Data & Privacy
            SettingsSectionHeader(stringResource(R.string.settings_group_data_privacy))
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                SettingsHubRow(
                    icon = Icons.Rounded.Shield,
                    title = stringResource(R.string.settings_hub_data_title),
                    subtitle = stringResource(R.string.settings_hub_data_subtitle),
                    iconTint = TempoSuccess,
                    onClick = { onOpenPage(SettingsPage.DATA_PRIVACY) },
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Group 4: Tempo
            SettingsSectionHeader(stringResource(R.string.settings_group_tempo))
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                SettingsHubRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_hub_about_title),
                    subtitle = stringResource(R.string.settings_hub_about_subtitle, versionName),
                    iconTint = TextSecondary,
                    onClick = { onOpenPage(SettingsPage.ABOUT) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_version_footer, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileHeroCard(
    userName: String,
    profileImagePath: String?,
    onCardClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HubCardShape)
            .clickable(onClick = onCardClick),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(onClick = onAvatarClick)
                        .background(
                            brush = HubAvatarGradientBrush,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (profileImagePath.isNullOrBlank()) {
                        Text(
                            text = userName.firstOrNull()?.uppercase() ?: "U",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                    } else {
                        CachedAsyncImage(
                            imageUrl = profileImagePath,
                            contentDescription = stringResource(R.string.settings_profile_photo),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = TempoDarkSurfaceSunken,
                    border = BorderStroke(1.dp, GlassBorderSoft),
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.settings_change_photo),
                            tint = TextPrimary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_profile_edit_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            Surface(
                shape = HubEditBadgeShape,
                color = TempoPrimary.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, TempoPrimary.copy(alpha = 0.22f)),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.settings_update_name),
                        tint = TempoPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResults(
    query: String,
    uiState: SettingsUiState,
    notificationListenerEnabled: Boolean,
    isXiaomiDevice: Boolean,
    autostartState: OemBackgroundHelper.AutostartState,
    batteryLevel: Int,
    isLowBattery: Boolean,
    isBatteryCritical: Boolean,
    currentLanguageName: String,
    selectedCoverSourceName: String,
    versionName: String,
    onOpenProfileDialog: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenSupportedApps: () -> Unit,
    onOpenBackgroundProtection: () -> Unit,
    onOpenLanguageDialog: () -> Unit,
    onOpenCoverSourceDialog: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenLastFmImport: () -> Unit,
    onOpenSpotifyJsonImport: () -> Unit,
    onOpenDeezerImport: () -> Unit,
    onOpenAppleMusicImport: () -> Unit,
    onOpenYouTubeMusicImport: () -> Unit,
    onOpenMusicoletImport: () -> Unit,
    onOpenExtensionImport: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenEnrichmentReport: () -> Unit,
    onOpenYourData: () -> Unit,
    onOpenClearDataDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRateApp: () -> Unit,
    onToggleDailySummary: (Boolean) -> Unit,
    onToggleWeeklyRecap: (Boolean) -> Unit,
    onToggleDailyChallenges: (Boolean) -> Unit,
    onToggleAchievements: (Boolean) -> Unit,
    onTogglePauseLowBattery: (Boolean) -> Unit,
    onToggleFilterPodcasts: (Boolean) -> Unit,
    onToggleFilterAudiobooks: (Boolean) -> Unit,
    onToggleExtendedAudio: (Boolean) -> Unit,
    onToggleGamification: (Boolean) -> Unit,
    onToggleHideStreaks: (Boolean) -> Unit,
    onToggleSmartMerge: (Boolean) -> Unit,
    onToggleAnalytics: (Boolean) -> Unit,
    onResetAnalyticsId: () -> Unit,
) {
    val entries = buildList<SearchResultEntry> {
        val catProfile = stringResource(R.string.settings_profile)
        val catTracking = stringResource(R.string.settings_hub_tracking_title)
        val catNotif = stringResource(R.string.settings_notifications)
        val catExperience = stringResource(R.string.settings_hub_experience_title)
        val catImport = stringResource(R.string.settings_import_history)
        val catData = stringResource(R.string.settings_hub_data_title)
        val catAbout = stringResource(R.string.settings_hub_about_title)

        add(
            SearchResultEntry.Action(
                key = "profile",
                category = catProfile,
                title = stringResource(R.string.settings_profile_dialog_title),
                subtitle = uiState.userName,
                keywords = "name photo avatar user display",
                icon = Icons.Rounded.Edit,
                onClick = onOpenProfileDialog,
            ),
        )

        add(
            SearchResultEntry.Action(
                key = "permissions",
                category = catTracking,
                title = stringResource(R.string.settings_manage_permissions),
                subtitle = stringResource(R.string.settings_manage_permissions_desc),
                keywords = "notification access permission listener tracking",
                icon = Icons.Rounded.NotificationsActive,
                badgeText = if (notificationListenerEnabled) {
                    stringResource(R.string.settings_status_enabled)
                } else {
                    stringResource(R.string.settings_status_required)
                },
                badgeColor = if (notificationListenerEnabled) TempoSuccess else TempoWarning,
                onClick = onOpenNotificationListenerSettings,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "supported_apps",
                category = catTracking,
                title = stringResource(R.string.settings_manage_apps),
                subtitle = stringResource(R.string.settings_manage_apps_desc),
                keywords = "spotify youtube apple music apps players whitelist block",
                icon = Icons.Rounded.Apps,
                onClick = onOpenSupportedApps,
            ),
        )
        if (isXiaomiDevice) {
            add(
                SearchResultEntry.Action(
                    key = "xiaomi_bg",
                    category = catTracking,
                    title = if (autostartState == OemBackgroundHelper.AutostartState.DISABLED) {
                        stringResource(R.string.settings_configure_required)
                    } else {
                        stringResource(R.string.settings_xiaomi_settings)
                    },
                    subtitle = stringResource(R.string.settings_prevent_killed),
                    keywords = "xiaomi miui autostart battery background killed",
                    icon = Icons.Rounded.Security,
                    onClick = onOpenBackgroundProtection,
                ),
            )
        }
        add(
            SearchResultEntry.Toggle(
                key = "filter_podcasts",
                category = catTracking,
                title = stringResource(R.string.settings_filter_podcasts),
                subtitle = stringResource(R.string.settings_filter_podcasts_desc),
                keywords = "podcast filter exclude",
                icon = Icons.Rounded.FilterAlt,
                checked = uiState.filterPodcasts,
                onCheckedChange = onToggleFilterPodcasts,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "filter_audiobooks",
                category = catTracking,
                title = stringResource(R.string.settings_filter_audiobooks),
                subtitle = stringResource(R.string.settings_filter_audiobooks_desc),
                keywords = "audiobook filter exclude",
                icon = Icons.Rounded.FilterAlt,
                checked = uiState.filterAudiobooks,
                onCheckedChange = onToggleFilterAudiobooks,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "cover_source",
                category = catTracking,
                title = stringResource(R.string.settings_cover_source_title),
                subtitle = selectedCoverSourceName,
                keywords = "album art cover artwork musicbrainz itunes deezer",
                icon = Icons.Rounded.Album,
                onClick = onOpenCoverSourceDialog,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "low_battery",
                category = catTracking,
                title = stringResource(R.string.settings_pause_tracking_low_battery),
                subtitle = if (uiState.pauseTrackingOnLowBattery) {
                    stringResource(R.string.settings_pause_tracking_on_desc, batteryLevel)
                } else {
                    stringResource(R.string.settings_pause_tracking_off_desc)
                },
                keywords = "battery saver low power pause",
                icon = Icons.Rounded.BatterySaver,
                checked = uiState.pauseTrackingOnLowBattery,
                onCheckedChange = onTogglePauseLowBattery,
            ),
        )

        add(
            SearchResultEntry.Toggle(
                key = "daily_summary",
                category = catNotif,
                title = stringResource(R.string.settings_daily_summary),
                subtitle = stringResource(R.string.settings_daily_summary_desc),
                keywords = "notification daily summary 8pm",
                icon = Icons.Rounded.NotificationsNone,
                checked = uiState.dailySummaryEnabled,
                onCheckedChange = onToggleDailySummary,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "weekly_recap",
                category = catNotif,
                title = stringResource(R.string.settings_weekly_recap),
                subtitle = stringResource(R.string.settings_weekly_recap_desc),
                keywords = "notification weekly recap sunday",
                icon = Icons.Rounded.NotificationsNone,
                checked = uiState.weeklyRecapEnabled,
                onCheckedChange = onToggleWeeklyRecap,
            ),
        )
        if (uiState.isGamificationEnabled) {
            add(
                SearchResultEntry.Toggle(
                    key = "daily_challenges",
                    category = catNotif,
                    title = stringResource(R.string.settings_daily_challenges),
                    subtitle = stringResource(R.string.settings_daily_challenges_desc),
                    keywords = "notification challenges reminders",
                    icon = Icons.Rounded.EmojiEvents,
                    checked = uiState.dailyChallengesEnabled,
                    onCheckedChange = onToggleDailyChallenges,
                ),
            )
            add(
                SearchResultEntry.Toggle(
                    key = "achievements",
                    category = catNotif,
                    title = stringResource(R.string.settings_achievements),
                    subtitle = stringResource(R.string.settings_achievements_desc),
                    keywords = "notification badges milestones achievements",
                    icon = Icons.Rounded.EmojiEvents,
                    checked = uiState.achievementsEnabled,
                    onCheckedChange = onToggleAchievements,
                ),
            )
        }

        add(
            SearchResultEntry.Action(
                key = "language",
                category = catExperience,
                title = stringResource(R.string.settings_language_title),
                subtitle = currentLanguageName,
                keywords = "locale language english french german hungarian portuguese russian",
                icon = Icons.Rounded.Language,
                onClick = onOpenLanguageDialog,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "extended_audio",
                category = catExperience,
                title = stringResource(R.string.settings_extended_audio),
                subtitle = stringResource(R.string.settings_extended_audio_desc),
                keywords = "mood preview audio analysis experimental",
                icon = Icons.Rounded.AutoAwesome,
                checked = uiState.extendedAudioAnalysisEnabled,
                onCheckedChange = onToggleExtendedAudio,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "gamification",
                category = catExperience,
                title = stringResource(R.string.settings_enable_gamification),
                subtitle = stringResource(R.string.settings_enable_gamification_desc),
                keywords = "xp levels badges challenges gamification",
                icon = Icons.Rounded.EmojiEvents,
                checked = uiState.isGamificationEnabled,
                onCheckedChange = onToggleGamification,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "hide_streaks",
                category = catExperience,
                title = stringResource(R.string.settings_hide_streaks),
                subtitle = stringResource(R.string.settings_hide_streaks_desc),
                keywords = "streaks hide counter share",
                icon = Icons.Rounded.VisibilityOff,
                checked = uiState.hideStreaks,
                onCheckedChange = onToggleHideStreaks,
            ),
        )

        add(
            SearchResultEntry.Action(
                key = "lastfm",
                category = catImport,
                title = if (uiState.isLastFmConnected) {
                    stringResource(R.string.settings_lastfm_connected, uiState.lastFmUsername ?: "Connected")
                } else {
                    stringResource(R.string.settings_import_lastfm)
                },
                subtitle = stringResource(R.string.settings_import_lastfm_desc),
                keywords = "lastfm scrobble import sync",
                icon = Icons.Rounded.CloudDownload,
                onClick = onOpenLastFmImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "spotify_json",
                category = catImport,
                title = stringResource(R.string.settings_import_spotify_json),
                subtitle = stringResource(R.string.settings_import_spotify_json_desc),
                keywords = "spotify json export import history",
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenSpotifyJsonImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "apple_music",
                category = catImport,
                title = stringResource(R.string.settings_import_apple_music),
                subtitle = stringResource(R.string.settings_import_apple_music_desc),
                keywords = "apple music zip privacy export import",
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenAppleMusicImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "youtube_music",
                category = catImport,
                title = stringResource(R.string.settings_import_youtube_music),
                subtitle = stringResource(R.string.settings_import_youtube_music_desc),
                keywords = "youtube takeout yt music import",
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenYouTubeMusicImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "deezer",
                category = catImport,
                title = stringResource(R.string.settings_import_deezer),
                subtitle = stringResource(R.string.settings_import_deezer_desc),
                keywords = "deezer xlsx import",
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenDeezerImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "musicolet",
                category = catImport,
                title = stringResource(R.string.settings_import_musicolet),
                subtitle = stringResource(R.string.settings_import_musicolet_desc),
                keywords = "musicolet backup zip import",
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenMusicoletImport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "extension_import",
                category = catImport,
                title = stringResource(R.string.settings_import_extension),
                subtitle = stringResource(R.string.settings_import_extension_desc),
                keywords = "browser extension chrome firefox import",
                icon = Icons.Rounded.Extension,
                onClick = onOpenExtensionImport,
            ),
        )
        val desktopBadgeText = when {
            isBatteryCritical -> stringResource(R.string.settings_status_critical_battery, batteryLevel)
            isLowBattery -> stringResource(R.string.settings_status_low_battery, batteryLevel)
            else -> null
        }
        val desktopBadgeColor = if (isBatteryCritical) TempoError else TempoWarning
        add(
            SearchResultEntry.Action(
                key = "desktop_sync",
                category = stringResource(R.string.settings_group_connections),
                title = stringResource(R.string.desktop_link_settings_title),
                subtitle = stringResource(R.string.desktop_link_settings_subtitle),
                keywords = "desktop mac pc wifi sync qr link",
                icon = Icons.Rounded.Devices,
                badgeText = desktopBadgeText,
                badgeColor = desktopBadgeColor,
                onClick = onOpenDesktop,
            ),
        )

        add(
            SearchResultEntry.Action(
                key = "backup_restore",
                category = catData,
                title = stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_restore_desc),
                keywords = "backup restore google drive export zip",
                icon = Icons.Rounded.Backup,
                onClick = onOpenBackup,
            ),
        )
        add(
            SearchResultEntry.Toggle(
                key = "smart_merge",
                category = catData,
                title = stringResource(R.string.settings_smart_merge),
                subtitle = stringResource(R.string.settings_smart_merge_desc),
                keywords = "merge remix live alternate versions duplicates",
                icon = Icons.Rounded.Merge,
                checked = uiState.mergeAlternateVersions,
                onCheckedChange = onToggleSmartMerge,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "enrichment_report",
                category = catData,
                title = stringResource(R.string.enrichment_report_settings_option),
                subtitle = stringResource(R.string.enrichment_report_settings_option_desc),
                keywords = "enrichment cover art genres metadata",
                icon = Icons.Rounded.AutoAwesome,
                onClick = onOpenEnrichmentReport,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "data_diagnostics",
                category = catData,
                title = stringResource(R.string.settings_data_diagnostics),
                subtitle = stringResource(R.string.settings_data_diagnostics_desc),
                keywords = "diagnostics health service status data",
                icon = Icons.Rounded.Analytics,
                onClick = onOpenYourData,
            ),
        )
        if (uiState.analyticsConfigured) {
            add(
                SearchResultEntry.Toggle(
                    key = "analytics_toggle",
                    category = catData,
                    title = stringResource(R.string.settings_analytics_toggle),
                    subtitle = stringResource(R.string.settings_analytics_toggle_desc),
                    keywords = "analytics telemetry privacy crash reporting",
                    icon = Icons.Rounded.PrivacyTip,
                    checked = uiState.analyticsEnabled,
                    onCheckedChange = onToggleAnalytics,
                ),
            )
            add(
                SearchResultEntry.Action(
                    key = "analytics_reset",
                    category = catData,
                    title = stringResource(R.string.settings_analytics_reset_id),
                    subtitle = stringResource(R.string.settings_analytics_reset_id_desc),
                    keywords = "analytics reset id identifier",
                    icon = Icons.Rounded.Refresh,
                    onClick = onResetAnalyticsId,
                ),
            )
        }
        add(
            SearchResultEntry.Action(
                key = "clear_all",
                category = catData,
                title = stringResource(R.string.settings_clear_all),
                subtitle = stringResource(R.string.settings_clear_all_desc),
                keywords = "delete reset wipe clear history data",
                icon = Icons.Rounded.DeleteForever,
                textColor = TempoError,
                onClick = onOpenClearDataDialog,
            ),
        )

        add(
            SearchResultEntry.Action(
                key = "reddit",
                category = catAbout,
                title = stringResource(R.string.settings_reddit),
                subtitle = stringResource(R.string.settings_reddit_sub),
                keywords = "reddit community support",
                icon = Icons.Rounded.Forum,
                onClick = { onOpenUrl("https://www.reddit.com/r/TempoStats/") },
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "github",
                category = catAbout,
                title = stringResource(R.string.settings_github),
                subtitle = stringResource(R.string.settings_github_desc),
                keywords = "github source code open source issues",
                icon = Icons.Rounded.Code,
                onClick = { onOpenUrl("https://github.com/avinaxhroy/Tempo") },
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "contribute",
                category = catAbout,
                title = stringResource(R.string.settings_contribute),
                subtitle = stringResource(R.string.settings_contribute_desc),
                keywords = "contribute help translate develop",
                icon = Icons.Rounded.VolunteerActivism,
                onClick = { onOpenUrl("https://github.com/avinaxhroy/Tempo/blob/main/CONTRIBUTION.md") },
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "rate",
                category = catAbout,
                title = stringResource(R.string.settings_rate_play_store),
                subtitle = null,
                keywords = "rate review play store",
                icon = Icons.Rounded.StarOutline,
                onClick = onRateApp,
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "privacy_policy",
                category = catAbout,
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = null,
                keywords = "privacy policy legal",
                icon = Icons.Rounded.Policy,
                onClick = { onOpenUrl("https://tempo.avinash.im/privacy/") },
            ),
        )
        add(
            SearchResultEntry.Action(
                key = "version",
                category = catAbout,
                title = stringResource(R.string.settings_version),
                subtitle = versionName,
                keywords = "version build about",
                icon = Icons.Rounded.Info,
                onClick = null,
            ),
        )
    }

    val normalized = query.trim()
    val grouped = remember(normalized, entries) {
        entries.filter { entry ->
            entry.title.contains(normalized, ignoreCase = true) ||
                (entry.subtitle?.contains(normalized, ignoreCase = true) == true) ||
                entry.category.contains(normalized, ignoreCase = true) ||
                entry.keywords.contains(normalized, ignoreCase = true)
        }.groupBy { it.category }
    }

    if (grouped.isEmpty()) {
        GlassCard(
            contentPadding = PaddingValues(28.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TempoPrimary.copy(alpha = 0.10f))
                        .border(1.dp, TempoPrimary.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = TempoPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_search_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_search_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        grouped.entries.forEachIndexed { groupIndex, (category, items) ->
            if (groupIndex > 0) {
                Spacer(modifier = Modifier.height(20.dp))
            }
            SettingsSectionHeader(category)
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                Column {
                    items.forEachIndexed { itemIndex, entry ->
                        if (itemIndex > 0) {
                            SettingsInsetDivider(hasLeadingIcon = true)
                        }
                        when (entry) {
                            is SearchResultEntry.Toggle -> {
                                SettingsSwitch(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    checked = entry.checked,
                                    onCheckedChange = entry.onCheckedChange,
                                    icon = entry.icon,
                                )
                            }

                            is SearchResultEntry.Action -> {
                                SettingsOption(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    onClick = entry.onClick,
                                    textColor = entry.textColor,
                                    showArrow = entry.onClick != null,
                                    icon = entry.icon,
                                    iconTint = if (entry.textColor != TextPrimary) entry.textColor else TextSecondary,
                                    badgeText = entry.badgeText,
                                    badgeColor = entry.badgeColor,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
