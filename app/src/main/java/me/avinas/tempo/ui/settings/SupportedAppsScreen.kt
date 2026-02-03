package me.avinas.tempo.ui.settings

import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSectionHeader
import me.avinas.tempo.ui.theme.TempoRed

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SupportedAppsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppPreferenceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddAppDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Manage Apps", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddAppDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add App", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
                // Search bar
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
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium
                            ) 
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Pre-installed Apps Section
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
                                                showDivider = index < uiState.preinstalledApps.size - 1,
                                                isInstalled = app.packageName in uiState.installedPackageNames
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // User-added Apps Section
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
                                                showDivider = index < uiState.userAddedApps.size - 1,
                                                isUserAdded = true,
                                                isInstalled = app.packageName in uiState.installedPackageNames
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Blocked Apps Section
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

                        // Empty State for search
                        if (uiState.preinstalledApps.isEmpty() && uiState.userAddedApps.isEmpty() && uiState.blockedApps.isEmpty()) {
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
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }

                        // Help text
                        item(key = "help_text") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Enable apps to track music listening. Block apps to exclude them completely. Tap + to add a custom app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Add App Dialog
    if (showAddAppDialog) {
        AddAppDialog(
            onDismiss = { showAddAppDialog = false },
            onAddApp = { packageName, displayName ->
                viewModel.addCustomApp(packageName, displayName)
                showAddAppDialog = false
            }
        )
    }
}

@Composable
private fun AppPreferenceItem(
    app: AppPreference,
    onToggle: (Boolean) -> Unit,
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
            // App Icon
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
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isInstalled && app.isEnabled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Installed • Start listening to track",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Remove button for user-added apps
            if (isUserAdded && onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = Color.White.copy(alpha = 0.4f)
                    )
                }
            }

            // Block button (for pre-installed apps)
            if (!isUserAdded && onBlock != null) {
                IconButton(onClick = onBlock) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = "Block",
                        tint = Color.White.copy(alpha = 0.4f)
                    )
                }
            }

            Switch(
                checked = app.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4CAF50),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                    checkedBorderColor = Color.Transparent,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.1f),
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
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(
                onClick = onUnblock,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF81C784))
            ) {
                Text("Unblock")
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.1f),
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
                icon = context.packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                // Keep null on error
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
            modifier = modifier.background(Color.White.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(4.dp)
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

    // Get installed apps
    // Get installed apps
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
            .distinctBy { it.first } // Remove duplicates if any
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
        GlassCard(
            variant = GlassCardVariant.HighProminence,
            backgroundColor = Color(0xFF1E1E1E).copy(alpha = 0.9f),
            contentPadding = PaddingValues(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                Text(
                    text = "Add App",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))

                GlassCard(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    variant = GlassCardVariant.LowProminence,
                    shape = RoundedCornerShape(12.dp)
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
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (filteredApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No apps found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.5f)
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
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        }
    }
}
