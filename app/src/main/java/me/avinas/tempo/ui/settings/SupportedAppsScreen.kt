package me.avinas.tempo.ui.settings

import android.graphics.drawable.Drawable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import me.avinas.tempo.data.preferences.TrackingRulesPreferences.ContentOverrideType
import me.avinas.tempo.data.preferences.TrackingRulesPreferences.DurationMode
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.components.TempoDialogShape
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoDarkSurfaceElevated
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessBright
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextQuaternary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * Semaphore that limits the number of concurrent PackageManager icon loads.
 * PackageManager.getApplicationIcon() internally acquires locks on AssetManager/ResourcesImpl.
 * Without this limit, loading many icons in parallel causes severe lock contention which
 * can starve the main thread and trigger an ANR (Input dispatching timed out).
 */
private val iconLoadingSemaphore = Semaphore(permits = 4)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SupportedAppsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppPreferenceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val trackingRules = remember(context) { TrackingRulesPreferences(context.applicationContext) }

    var showAddAppDialog by remember { mutableStateOf(false) }
    var showMinPlayDialog by remember { mutableStateOf(false) }
    var showDefaultMaxDialog by remember { mutableStateOf(false) }
    var showContentOverridesDialog by remember { mutableStateOf(false) }
    var durationApp by remember { mutableStateOf<AppPreference?>(null) }
    var rulesRevision by remember { mutableIntStateOf(0) }

    // Reading the duration SharedPreferences is cheap; revision makes summaries refresh
    // immediately after a duration dialog saves a new rule. Content overrides are observed
    // directly from Room through the ViewModel.
    val minimumPlayDurationMs = remember(rulesRevision) { trackingRules.minimumPlayDurationMs }
    val defaultMaxMusicDurationMs = remember(rulesRevision) { trackingRules.defaultMaxMusicDurationMs }
    val overrideCount = uiState.contentOverrides.size

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_manage_apps), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddAppDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add App", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    variant = GlassCardVariant.LowProminence,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    TextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent),
                        placeholder = {
                            Text(
                                "Search apps...", 
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = TextPrimary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TempoPrimary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item(key = "header_tracking_rules") {
                            SettingsSectionHeader(stringResource(R.string.tracking_rules_section))
                        }
                        item(key = "tracking_rules") {
                            TrackingRulesCard(
                                minimumPlayDurationMs = minimumPlayDurationMs,
                                defaultMaxMusicDurationMs = defaultMaxMusicDurationMs,
                                overrideCount = overrideCount,
                                onMinimumPlayClick = { showMinPlayDialog = true },
                                onDefaultMaxClick = { showDefaultMaxDialog = true },
                                onOverridesClick = { showContentOverridesDialog = true }
                            )
                        }

                        if (uiState.preinstalledApps.isNotEmpty()) {
                            item(key = "header_music_apps") {
                                SettingsSectionHeader("Music Apps")
                            }
                            item(key = "list_music_apps") {
                                GlassCard(
                                    contentPadding = PaddingValues(0.dp),
                                    variant = GlassCardVariant.LowProminence
                                ) {
                                    Column(modifier = Modifier.animateContentSize()) {
                                        uiState.preinstalledApps.forEachIndexed { index, app ->
                                            AppPreferenceItem(
                                                app = app,
                                                onToggle = { enabled ->
                                                    viewModel.toggleAppEnabled(app.packageName, enabled)
                                                },
                                                onBlock = { viewModel.blockApp(app.packageName) },
                                                onDurationClick = { durationApp = app },
                                                durationSummary = appDurationSummary(trackingRules, app.packageName),
                                                showDivider = index < uiState.preinstalledApps.size - 1,
                                                isInstalled = app.packageName in uiState.installedPackageNames
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.userAddedApps.isNotEmpty()) {
                            item(key = "header_user_apps") {
                                SettingsSectionHeader("Your Apps")
                            }
                            item(key = "list_user_apps") {
                                GlassCard(
                                    contentPadding = PaddingValues(0.dp),
                                    variant = GlassCardVariant.LowProminence
                                ) {
                                    Column(modifier = Modifier.animateContentSize()) {
                                        uiState.userAddedApps.forEachIndexed { index, app ->
                                            AppPreferenceItem(
                                                app = app,
                                                onToggle = { enabled ->
                                                    viewModel.toggleAppEnabled(app.packageName, enabled)
                                                },
                                                onRemove = { viewModel.removeApp(app.packageName) },
                                                onDurationClick = { durationApp = app },
                                                durationSummary = appDurationSummary(trackingRules, app.packageName),
                                                showDivider = index < uiState.userAddedApps.size - 1,
                                                isUserAdded = true,
                                                isInstalled = app.packageName in uiState.installedPackageNames
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.blockedApps.isNotEmpty()) {
                            item(key = "header_blocked_apps") {
                                SettingsSectionHeader("Blocked Apps")
                            }
                            item(key = "list_blocked_apps") {
                                GlassCard(
                                    contentPadding = PaddingValues(0.dp),
                                    variant = GlassCardVariant.LowProminence
                                ) {
                                    Column(modifier = Modifier.animateContentSize()) {
                                        uiState.blockedApps.forEachIndexed { index, app ->
                                            BlockedAppItem(
                                                app = app,
                                                onUnblock = { viewModel.unblockApp(app.packageName) },
                                                showDivider = index < uiState.blockedApps.size - 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (
                            uiState.preinstalledApps.isEmpty() &&
                            uiState.userAddedApps.isEmpty() &&
                            uiState.blockedApps.isEmpty()
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No apps found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextTertiary
                                    )
                                }
                            }
                        }

                        item(key = "help_text") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.tracking_rules_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddAppDialog) {
        AddAppDialog(
            onDismiss = { showAddAppDialog = false },
            onAddApp = { packageName, displayName ->
                viewModel.addCustomApp(packageName, displayName)
                showAddAppDialog = false
            }
        )
    }

    if (showMinPlayDialog) {
        MinimumPlayDurationDialog(
            initialMs = trackingRules.minimumPlayDurationMs,
            onDismiss = { showMinPlayDialog = false },
            onSave = { value ->
                trackingRules.minimumPlayDurationMs = value
                rulesRevision++
                showMinPlayDialog = false
            }
        )
    }

    if (showDefaultMaxDialog) {
        DefaultMaxDurationDialog(
            initialMs = trackingRules.defaultMaxMusicDurationMs,
            onDismiss = { showDefaultMaxDialog = false },
            onSave = { value ->
                trackingRules.defaultMaxMusicDurationMs = value
                rulesRevision++
                showDefaultMaxDialog = false
            }
        )
    }

    durationApp?.let { app ->
        AppDurationDialog(
            app = app,
            trackingRules = trackingRules,
            onDismiss = { durationApp = null },
            onSaved = {
                rulesRevision++
                durationApp = null
            }
        )
    }

    if (showContentOverridesDialog) {
        ContentOverridesDialog(
            rules = uiState.contentOverrides,
            onAdd = viewModel::addContentOverride,
            onDelete = viewModel::removeContentOverride,
            onDismiss = { showContentOverridesDialog = false }
        )
    }
}

@Composable
private fun TrackingRulesCard(
    minimumPlayDurationMs: Long,
    defaultMaxMusicDurationMs: Long?,
    overrideCount: Int,
    onMinimumPlayClick: () -> Unit,
    onDefaultMaxClick: () -> Unit,
    onOverridesClick: () -> Unit
) {
    GlassCard(
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            TrackingRuleRow(
                icon = Icons.Default.HourglassBottom,
                title = stringResource(R.string.tracking_count_listen_after),
                subtitle = formatDuration(minimumPlayDurationMs),
                onClick = onMinimumPlayClick
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            TrackingRuleRow(
                icon = Icons.Default.Timer,
                title = stringResource(R.string.tracking_default_max_duration),
                subtitle = defaultMaxMusicDurationMs?.let(::formatDuration)
                    ?: stringResource(R.string.tracking_no_limit),
                onClick = onDefaultMaxClick
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            TrackingRuleRow(
                icon = Icons.Default.FilterAlt,
                title = stringResource(R.string.tracking_content_exceptions),
                subtitle = if (overrideCount == 0) {
                    stringResource(R.string.tracking_content_exception_types)
                } else {
                    pluralStringResource(R.plurals.tracking_saved_rules, overrideCount, overrideCount)
                },
                onClick = onOverridesClick
            )
        }
    }
}

@Composable
private fun TrackingRuleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TempoPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun AppPreferenceItem(
    app: AppPreference,
    onToggle: (Boolean) -> Unit,
    onDurationClick: () -> Unit,
    durationSummary: String,
    onBlock: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    showDivider: Boolean = true,
    isUserAdded: Boolean = false,
    isInstalled: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = app.packageName,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = durationSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TempoPrimary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isInstalled && app.isEnabled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Installed • Start listening to track",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onDurationClick) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = stringResource(R.string.tracking_duration_limit),
                    tint = TempoPrimary.copy(alpha = 0.9f)
                )
            }

            if (isUserAdded && onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = TextQuaternary
                    )
                }
            }

            if (!isUserAdded && onBlock != null) {
                IconButton(onClick = onBlock) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = "Block",
                        tint = TextQuaternary
                    )
                }
            }

            Switch(
                checked = app.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextOnAccent,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = TempoDarkSurfaceElevated,
                    checkedBorderColor = Color.Transparent,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = GlassBorderSoft,
                modifier = Modifier.padding(start = 72.dp, end = 16.dp) // Indented divider
            )
        }
    }
}

@Composable
private fun BlockedAppItem(
    app: AppPreference,
    onUnblock: () -> Unit,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = app.packageName,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextQuaternary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(
                onClick = onUnblock,
                colors = ButtonDefaults.textButtonColors(contentColor = TempoSuccessBright)
            ) {
                Text("Unblock")
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = GlassBorderSoft,
                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
            )
        }
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                iconLoadingSemaphore.withPermit {
                    icon = context.packageManager.getApplicationIcon(packageName)
                }
            } catch (_: Exception) {
                // Keep null on error (e.g. package not found)
            }
        }
    }

    if (icon != null) {
        Image(
            painter = coil3.compose.rememberAsyncImagePainter(icon),
            contentDescription = null,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.background(GlassFrostSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
private fun MinimumPlayDurationDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var secondsText by remember { mutableStateOf((initialMs / 1000L).toString()) }
    val seconds = secondsText.toLongOrNull()
    val isValid = seconds != null && seconds in 1L..600L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracking_minimum_listening_time)) },
        text = {
            Column {
                Text(stringResource(R.string.tracking_minimum_listening_description))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.tracking_seconds)) },
                    supportingText = { Text(stringResource(R.string.tracking_minimum_range)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onSave(seconds!! * 1000L) }
            ) {
                Text(stringResource(R.string.tracking_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_cancel)) }
        }
    )
}

@Composable
private fun DefaultMaxDurationDialog(
    initialMs: Long?,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit
) {
    var noLimit by remember { mutableStateOf(initialMs == null) }
    var minutesText by remember {
        mutableStateOf(((initialMs ?: TrackingRulesPreferences.DEFAULT_MAX_MUSIC_DURATION_MS) / 60_000L).toString())
    }
    var secondsText by remember {
        mutableStateOf((((initialMs ?: TrackingRulesPreferences.DEFAULT_MAX_MUSIC_DURATION_MS) / 1000L) % 60L).toString())
    }

    val customMs = durationFieldsToMs(minutesText, secondsText)
    val isValid = noLimit || (customMs != null && customMs in 1_000L..86_400_000L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracking_default_max_duration)) },
        text = {
            Column {
                Text(stringResource(R.string.tracking_default_max_description))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { noLimit = !noLimit }
                        .padding(vertical = 4.dp)
                ) {
                    Switch(checked = noLimit, onCheckedChange = { noLimit = it })
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.tracking_no_maximum_duration))
                }
                if (!noLimit) {
                    DurationFields(
                        minutesText = minutesText,
                        secondsText = secondsText,
                        onMinutesChange = { minutesText = it },
                        onSecondsChange = { secondsText = it }
                    )
                    Text(
                        stringResource(R.string.tracking_default_twenty_minutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onSave(if (noLimit) null else customMs) }
            ) {
                Text(stringResource(R.string.tracking_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_cancel)) }
        }
    )
}

@Composable
private fun AppDurationDialog(
    app: AppPreference,
    trackingRules: TrackingRulesPreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var mode by remember(app.packageName) {
        mutableStateOf(trackingRules.getAppDurationMode(app.packageName))
    }
    val initialCustom = remember(app.packageName) {
        trackingRules.getAppCustomMaxMusicDurationMs(app.packageName)
            ?: trackingRules.defaultMaxMusicDurationMs
            ?: TrackingRulesPreferences.DEFAULT_MAX_MUSIC_DURATION_MS
    }
    var minutesText by remember(app.packageName) {
        mutableStateOf((initialCustom / 60_000L).toString())
    }
    var secondsText by remember(app.packageName) {
        mutableStateOf(((initialCustom / 1000L) % 60L).toString())
    }
    val customMs = durationFieldsToMs(minutesText, secondsText)
    val validCustom = customMs != null && customMs in 1_000L..86_400_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracking_app_duration_limit, app.displayName)) },
        text = {
            Column {
                DurationModeRow(
                    selected = mode == DurationMode.GLOBAL,
                    title = stringResource(R.string.tracking_use_global_limit),
                    subtitle = trackingRules.defaultMaxMusicDurationMs?.let(::formatDuration)
                        ?: stringResource(R.string.tracking_no_limit),
                    onClick = { mode = DurationMode.GLOBAL }
                )
                DurationModeRow(
                    selected = mode == DurationMode.NO_LIMIT,
                    title = stringResource(R.string.tracking_no_limit),
                    subtitle = stringResource(R.string.tracking_no_limit_app_description),
                    onClick = { mode = DurationMode.NO_LIMIT }
                )
                DurationModeRow(
                    selected = mode == DurationMode.CUSTOM,
                    title = stringResource(R.string.tracking_custom_limit),
                    subtitle = stringResource(R.string.tracking_custom_limit_description),
                    onClick = { mode = DurationMode.CUSTOM }
                )
                if (mode == DurationMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DurationFields(
                        minutesText = minutesText,
                        secondsText = secondsText,
                        onMinutesChange = { minutesText = it },
                        onSecondsChange = { secondsText = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != DurationMode.CUSTOM || validCustom,
                onClick = {
                    when (mode) {
                        DurationMode.GLOBAL -> trackingRules.setAppUseGlobal(app.packageName)
                        DurationMode.NO_LIMIT -> trackingRules.setAppNoLimit(app.packageName)
                        DurationMode.CUSTOM -> trackingRules.setAppCustomMaxMusicDurationMs(
                            app.packageName,
                            customMs!!
                        )
                    }
                    onSaved()
                }
            ) {
                Text(stringResource(R.string.tracking_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_cancel)) }
        }
    )
}

@Composable
private fun DurationModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun DurationFields(
    minutesText: String,
    secondsText: String,
    onMinutesChange: (String) -> Unit,
    onSecondsChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = minutesText,
            onValueChange = { onMinutesChange(it.filter(Char::isDigit).take(4)) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.tracking_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
        OutlinedTextField(
            value = secondsText,
            onValueChange = {
                val digits = it.filter(Char::isDigit).take(2)
                val parsed = digits.toIntOrNull()
                if (parsed == null || parsed <= 59) onSecondsChange(digits)
            },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.tracking_seconds)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
    }
}

@Composable
private fun ContentOverridesDialog(
    rules: List<ManualContentMark>,
    onAdd: (String, String, ContentOverrideType) -> Unit,
    onDelete: (ManualContentMark) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ContentOverrideType.MUSIC) }
    val canAdd = title.isNotBlank() || artist.isNotBlank()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TempoDialogShape.shape)
                .background(TempoSurfaceDialog)
                .border(1.dp, GlassBorderSoft, TempoDialogShape.shape)
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.tracking_content_exceptions),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.tracking_content_exceptions_description),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tracking_title_optional)) },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tracking_artist_optional)) },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            DurationModeRow(
                selected = type == ContentOverrideType.MUSIC,
                title = stringResource(R.string.tracking_always_music),
                subtitle = stringResource(R.string.tracking_always_music_description),
                onClick = { type = ContentOverrideType.MUSIC }
            )
            DurationModeRow(
                selected = type == ContentOverrideType.VIDEO,
                title = stringResource(R.string.tracking_video_non_music),
                subtitle = stringResource(R.string.tracking_video_non_music_description),
                onClick = { type = ContentOverrideType.VIDEO }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    enabled = canAdd,
                    onClick = {
                        onAdd(title, artist, type)
                        title = ""
                        artist = ""
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tracking_add_rule))
                }
            }

            if (rules.isNotEmpty()) {
                HorizontalDivider(color = GlassBorderSoft)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = rules,
                        key = { it.id }
                    ) { rule ->
                        ContentOverrideItem(
                            rule = rule,
                            onDelete = { onDelete(rule) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_done)) }
            }
        }
    }
}

@Composable
private fun ContentOverrideItem(
    rule: ManualContentMark,
    onDelete: () -> Unit
) {
    val isAlwaysMusic = rule.contentType == AppPreferenceViewModel.CONTENT_TYPE_ALWAYS_MUSIC
    val anyTitle = stringResource(R.string.tracking_any_title)
    val anyArtist = stringResource(R.string.tracking_any_artist)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GlassFrostSoft)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isAlwaysMusic) {
                    stringResource(R.string.tracking_always_music)
                } else {
                    stringResource(R.string.tracking_video_non_music)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isAlwaysMusic) TempoPrimary else TextSecondary
            )
            Text(
                buildString {
                    append(if (rule.originalTitle.isBlank()) anyTitle else rule.originalTitle)
                    append(" • ")
                    append(if (rule.originalArtist.isBlank()) anyArtist else rule.originalArtist)
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.tracking_delete_rule),
                tint = TextTertiary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    onDismiss: () -> Unit,
    onAddApp: (packageName: String, displayName: String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val installedApps = remember {
        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(mainIntent, 0)
            .map { resolveInfo ->
                Pair(
                    resolveInfo.activityInfo.packageName,
                    resolveInfo.loadLabel(pm).toString()
                )
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    val filteredApps = remember(searchQuery) {
        if (searchQuery.isBlank()) installedApps.take(30)
        else installedApps.filter {
            it.first.contains(searchQuery, ignoreCase = true) ||
                it.second.contains(searchQuery, ignoreCase = true)
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TempoDialogShape.shape)
                .background(TempoSurfaceDialog)
                .border(1.dp, GlassBorderSoft, TempoDialogShape.shape)
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                Text(
                    text = "Add App",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassFrostSoft)
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent),
                        placeholder = {
                            Text(
                                "Search installed apps...",
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = TempoPrimary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { (packageName, _) -> packageName }
                    ) { (packageName, displayName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAddApp(packageName, displayName) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                packageName = packageName,
                                modifier = Modifier.size(36.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add",
                                tint = TempoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (filteredApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No apps found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextTertiary)
                    }
                }
            }
        }
    }
}

private fun durationFieldsToMs(minutesText: String, secondsText: String): Long? {
    val minutes = if (minutesText.isBlank()) 0L else minutesText.toLongOrNull() ?: return null
    val seconds = if (secondsText.isBlank()) 0L else secondsText.toLongOrNull() ?: return null
    if (minutes !in 0L..1440L || seconds !in 0L..59L) return null
    val totalSeconds = minutes * 60L + seconds
    if (totalSeconds <= 0L) return null
    return totalSeconds * 1000L
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0L -> "%dm %02ds".format(minutes, seconds)
        else -> "${seconds}s"
    }
}

@Composable
private fun appDurationSummary(
    trackingRules: TrackingRulesPreferences,
    packageName: String
): String {
    return when (trackingRules.getAppDurationMode(packageName)) {
        DurationMode.GLOBAL -> {
            val global = trackingRules.defaultMaxMusicDurationMs
            stringResource(
                R.string.tracking_music_limit_global,
                global?.let(::formatDuration) ?: stringResource(R.string.tracking_no_limit)
            )
        }
        DurationMode.NO_LIMIT -> stringResource(R.string.tracking_music_limit_no_limit)
        DurationMode.CUSTOM -> {
            val custom = trackingRules.getAppCustomMaxMusicDurationMs(packageName)
            stringResource(
                R.string.tracking_music_limit_custom,
                custom?.let(::formatDuration) ?: stringResource(R.string.tracking_global)
            )
        }
    }
}
