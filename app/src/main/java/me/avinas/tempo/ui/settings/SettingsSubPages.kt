package me.avinas.tempo.ui.settings

import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsInsetDivider
import me.avinas.tempo.ui.components.SettingsOption
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.SettingsStatusBanner
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.InsightMood
import me.avinas.tempo.ui.theme.LastFmRed
import me.avinas.tempo.ui.theme.SpotifyGreen
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPop
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.YouTubeRed
import me.avinas.tempo.utils.OemBackgroundHelper

@Composable
internal fun TrackingSettingsPage(
    uiState: SettingsUiState,
    notificationListenerEnabled: Boolean,
    isXiaomiDevice: Boolean,
    autostartState: OemBackgroundHelper.AutostartState,
    batteryLevel: Int,
    selectedCoverSourceName: String,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenSupportedApps: () -> Unit,
    onOpenBackgroundProtection: () -> Unit,
    onTogglePauseLowBattery: (Boolean) -> Unit,
    onOpenCoverSourceDialog: () -> Unit,
    onToggleFilterPodcasts: (Boolean) -> Unit,
    onToggleFilterAudiobooks: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_section_status_access))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_manage_permissions),
                    subtitle = stringResource(R.string.settings_manage_permissions_desc),
                    icon = Icons.Rounded.NotificationsActive,
                    iconTint = if (notificationListenerEnabled) TempoSuccess else TempoWarning,
                    badgeText = if (notificationListenerEnabled) {
                        stringResource(R.string.settings_status_enabled)
                    } else {
                        stringResource(R.string.settings_status_required)
                    },
                    badgeColor = if (notificationListenerEnabled) TempoSuccess else TempoWarning,
                    onClick = onOpenNotificationListenerSettings,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_manage_apps),
                    subtitle = stringResource(R.string.settings_manage_apps_desc),
                    icon = Icons.Rounded.Apps,
                    iconTint = TempoPrimary,
                    onClick = onOpenSupportedApps,
                )
                if (isXiaomiDevice) {
                    SettingsInsetDivider(hasLeadingIcon = true)
                    val autostartDisabled = autostartState == OemBackgroundHelper.AutostartState.DISABLED
                    SettingsOption(
                        title = if (autostartDisabled) {
                            stringResource(R.string.settings_configure_required)
                        } else {
                            stringResource(R.string.settings_xiaomi_settings)
                        },
                        subtitle = stringResource(R.string.settings_prevent_killed),
                        icon = Icons.Rounded.Security,
                        iconTint = if (autostartDisabled) TempoWarning else TempoSuccess,
                        badgeText = if (autostartDisabled) {
                            stringResource(R.string.settings_status_action_needed)
                        } else {
                            null
                        },
                        badgeColor = TempoWarning,
                        onClick = onOpenBackgroundProtection,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_content_filtering))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsSwitch(
                    title = stringResource(R.string.settings_filter_podcasts),
                    subtitle = stringResource(R.string.settings_filter_podcasts_desc),
                    checked = uiState.filterPodcasts,
                    onCheckedChange = onToggleFilterPodcasts,
                    icon = Icons.Rounded.FilterAlt,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_filter_audiobooks),
                    subtitle = stringResource(R.string.settings_filter_audiobooks_desc),
                    checked = uiState.filterAudiobooks,
                    onCheckedChange = onToggleFilterAudiobooks,
                    icon = Icons.Rounded.FilterAlt,
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_artwork_power))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_cover_source_title),
                    subtitle = selectedCoverSourceName,
                    icon = Icons.Rounded.Album,
                    iconTint = TempoPrimary,
                    onClick = onOpenCoverSourceDialog,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_pause_tracking_low_battery),
                    subtitle = if (uiState.pauseTrackingOnLowBattery) {
                        stringResource(R.string.settings_pause_tracking_on_desc, batteryLevel)
                    } else {
                        stringResource(R.string.settings_pause_tracking_off_desc)
                    },
                    checked = uiState.pauseTrackingOnLowBattery,
                    onCheckedChange = onTogglePauseLowBattery,
                    icon = Icons.Rounded.BatterySaver,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun NotificationsSettingsPage(
    uiState: SettingsUiState,
    onToggleDailySummary: (Boolean) -> Unit,
    onToggleWeeklyRecap: (Boolean) -> Unit,
    onToggleDailyChallenges: (Boolean) -> Unit,
    onToggleAchievements: (Boolean) -> Unit,
    onOpenExperience: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_section_recaps))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsSwitch(
                    title = stringResource(R.string.settings_daily_summary),
                    subtitle = stringResource(R.string.settings_daily_summary_desc),
                    checked = uiState.dailySummaryEnabled,
                    onCheckedChange = onToggleDailySummary,
                    icon = Icons.Rounded.NotificationsNone,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_weekly_recap),
                    subtitle = stringResource(R.string.settings_weekly_recap_desc),
                    checked = uiState.weeklyRecapEnabled,
                    onCheckedChange = onToggleWeeklyRecap,
                    icon = Icons.Rounded.NotificationsNone,
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_milestones))
        if (uiState.isGamificationEnabled) {
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                Column {
                    SettingsSwitch(
                        title = stringResource(R.string.settings_daily_challenges),
                        subtitle = stringResource(R.string.settings_daily_challenges_desc),
                        checked = uiState.dailyChallengesEnabled,
                        onCheckedChange = onToggleDailyChallenges,
                        icon = Icons.Rounded.EmojiEvents,
                    )
                    SettingsInsetDivider(hasLeadingIcon = true)
                    SettingsSwitch(
                        title = stringResource(R.string.settings_achievements),
                        subtitle = stringResource(R.string.settings_achievements_desc),
                        checked = uiState.achievementsEnabled,
                        onCheckedChange = onToggleAchievements,
                        icon = Icons.Rounded.EmojiEvents,
                    )
                }
            }
        } else {
            SettingsStatusBanner(
                icon = Icons.Rounded.EmojiEvents,
                title = stringResource(R.string.settings_gamification_disabled_title),
                subtitle = stringResource(R.string.settings_gamification_disabled_desc),
                actionLabel = stringResource(R.string.settings_gamification_disabled_action),
                accentColor = TempoInfo,
                onClick = onOpenExperience,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun ExperienceSettingsPage(
    uiState: SettingsUiState,
    currentLanguageName: String,
    onOpenLanguageDialog: () -> Unit,
    onToggleExtendedAudio: (Boolean) -> Unit,
    onToggleGamification: (Boolean) -> Unit,
    onToggleHideStreaks: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_language))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            SettingsOption(
                title = stringResource(R.string.settings_language_title),
                subtitle = currentLanguageName,
                icon = Icons.Rounded.Language,
                iconTint = TempoPrimary,
                onClick = onOpenLanguageDialog,
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_insights_display))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsSwitch(
                    title = stringResource(R.string.settings_extended_audio),
                    subtitle = stringResource(R.string.settings_extended_audio_desc),
                    checked = uiState.extendedAudioAnalysisEnabled,
                    onCheckedChange = onToggleExtendedAudio,
                    icon = Icons.Rounded.AutoAwesome,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_enable_gamification),
                    subtitle = stringResource(R.string.settings_enable_gamification_desc),
                    checked = uiState.isGamificationEnabled,
                    onCheckedChange = onToggleGamification,
                    icon = Icons.Rounded.EmojiEvents,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_hide_streaks),
                    subtitle = stringResource(R.string.settings_hide_streaks_desc),
                    checked = uiState.hideStreaks,
                    onCheckedChange = onToggleHideStreaks,
                    icon = Icons.Rounded.VisibilityOff,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun ImportHistorySettingsPage(
    uiState: SettingsUiState,
    onOpenLastFmImport: () -> Unit,
    onOpenSpotifyJsonImport: () -> Unit,
    onOpenAppleMusicImport: () -> Unit,
    onOpenYouTubeMusicImport: () -> Unit,
    onOpenDeezerImport: () -> Unit,
    onOpenMusicoletImport: () -> Unit,
    onOpenExtensionImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_section_live_sync))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            if (uiState.isLastFmConnected) {
                SettingsOption(
                    title = stringResource(R.string.settings_lastfm_connected, uiState.lastFmUsername ?: "Connected"),
                    subtitle = when (uiState.lastFmSyncFrequency) {
                        "DAILY" -> stringResource(R.string.settings_lastfm_sync_daily)
                        "WEEKLY" -> stringResource(R.string.settings_lastfm_sync_weekly)
                        else -> stringResource(R.string.settings_lastfm_import_complete)
                    },
                    icon = Icons.Rounded.CloudDownload,
                    iconTint = TempoSuccess,
                    badgeText = stringResource(R.string.settings_status_connected),
                    badgeColor = TempoSuccess,
                    onClick = onOpenLastFmImport,
                )
            } else {
                SettingsOption(
                    title = stringResource(R.string.settings_import_lastfm),
                    subtitle = stringResource(R.string.settings_import_lastfm_desc),
                    icon = Icons.Rounded.CloudDownload,
                    iconTint = LastFmRed,
                    onClick = onOpenLastFmImport,
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_platform_exports))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_import_spotify_json),
                    subtitle = stringResource(R.string.settings_import_spotify_json_desc),
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = SpotifyGreen,
                    onClick = onOpenSpotifyJsonImport,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_import_apple_music),
                    subtitle = stringResource(R.string.settings_import_apple_music_desc),
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = TempoPop,
                    onClick = onOpenAppleMusicImport,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_import_youtube_music),
                    subtitle = stringResource(R.string.settings_import_youtube_music_desc),
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = YouTubeRed,
                    onClick = onOpenYouTubeMusicImport,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_import_deezer),
                    subtitle = stringResource(R.string.settings_import_deezer_desc),
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = InsightMood,
                    onClick = onOpenDeezerImport,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_import_musicolet),
                    subtitle = stringResource(R.string.settings_import_musicolet_desc),
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = TempoWarning,
                    onClick = onOpenMusicoletImport,
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_browser))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            SettingsOption(
                title = stringResource(R.string.settings_import_extension),
                subtitle = stringResource(R.string.settings_import_extension_desc),
                icon = Icons.Rounded.Extension,
                iconTint = TempoInfo,
                onClick = onOpenExtensionImport,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun DataPrivacySettingsPage(
    uiState: SettingsUiState,
    onOpenBackup: () -> Unit,
    onToggleSmartMerge: (Boolean) -> Unit,
    onOpenEnrichmentReport: () -> Unit,
    onOpenYourData: () -> Unit,
    onToggleAnalytics: (Boolean) -> Unit,
    onResetAnalyticsId: () -> Unit,
    onOpenClearDataDialog: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_section_backup_library))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_desc),
                    icon = Icons.Rounded.Backup,
                    iconTint = TempoPrimary,
                    onClick = onOpenBackup,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsSwitch(
                    title = stringResource(R.string.settings_smart_merge),
                    subtitle = stringResource(R.string.settings_smart_merge_desc),
                    checked = uiState.mergeAlternateVersions,
                    onCheckedChange = onToggleSmartMerge,
                    icon = Icons.Rounded.Merge,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.enrichment_report_settings_option),
                    subtitle = stringResource(R.string.enrichment_report_settings_option_desc),
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = onOpenEnrichmentReport,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_data_diagnostics),
                    subtitle = stringResource(R.string.settings_data_diagnostics_desc),
                    icon = Icons.Rounded.Analytics,
                    onClick = onOpenYourData,
                )
            }
        }

        if (uiState.analyticsConfigured) {
            Spacer(modifier = Modifier.height(22.dp))
            SettingsSectionHeader(stringResource(R.string.settings_section_privacy_telemetry))
            GlassCard(
                contentPadding = PaddingValues(0.dp),
                variant = GlassCardVariant.LowProminence,
            ) {
                Column {
                    SettingsSwitch(
                        title = stringResource(R.string.settings_analytics_toggle),
                        subtitle = stringResource(R.string.settings_analytics_toggle_desc),
                        checked = uiState.analyticsEnabled,
                        onCheckedChange = onToggleAnalytics,
                        icon = Icons.Rounded.PrivacyTip,
                    )
                    SettingsInsetDivider(hasLeadingIcon = true)
                    SettingsOption(
                        title = stringResource(R.string.settings_analytics_reset_id),
                        subtitle = stringResource(R.string.settings_analytics_reset_id_desc),
                        icon = Icons.Rounded.Refresh,
                        onClick = onResetAnalyticsId,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_danger_zone))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            accentColor = TempoError,
            accentStrength = 0.06f,
            borderColor = TempoError.copy(alpha = 0.28f),
            borderWidth = 1.dp,
        ) {
            SettingsOption(
                title = stringResource(R.string.settings_clear_all),
                subtitle = stringResource(R.string.settings_clear_all_desc),
                textColor = TempoError,
                icon = Icons.Rounded.DeleteForever,
                iconTint = TempoError,
                onClick = onOpenClearDataDialog,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun AboutCommunitySettingsPage(
    versionName: String,
    onOpenUrl: (String) -> Unit,
    onRateApp: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.settings_community))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_reddit),
                    subtitle = stringResource(R.string.settings_reddit_sub),
                    icon = Icons.Rounded.Forum,
                    iconTint = TempoPrimary,
                    onClick = { onOpenUrl("https://www.reddit.com/r/TempoStats/") },
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_github),
                    subtitle = stringResource(R.string.settings_github_desc),
                    icon = Icons.Rounded.Code,
                    onClick = { onOpenUrl("https://github.com/avinaxhroy/Tempo") },
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_contribute),
                    subtitle = stringResource(R.string.settings_contribute_desc),
                    icon = Icons.Rounded.VolunteerActivism,
                    onClick = { onOpenUrl("https://github.com/avinaxhroy/Tempo/blob/main/CONTRIBUTION.md") },
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_app_info))
        GlassCard(
            contentPadding = PaddingValues(0.dp),
            variant = GlassCardVariant.LowProminence,
        ) {
            Column {
                SettingsOption(
                    title = stringResource(R.string.settings_rate_play_store),
                    icon = Icons.Rounded.StarOutline,
                    iconTint = TempoWarning,
                    onClick = onRateApp,
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_privacy_policy),
                    icon = Icons.Rounded.Policy,
                    onClick = { onOpenUrl("https://tempo.avinash.im/privacy/") },
                )
                SettingsInsetDivider(hasLeadingIcon = true)
                SettingsOption(
                    title = stringResource(R.string.settings_version),
                    subtitle = versionName,
                    icon = Icons.Rounded.Info,
                    showArrow = false,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
