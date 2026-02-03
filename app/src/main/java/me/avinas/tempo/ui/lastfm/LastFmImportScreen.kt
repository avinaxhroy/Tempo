package me.avinas.tempo.ui.lastfm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import java.text.NumberFormat
import java.util.Locale

/**
 * Helper data class for destructuring in ImportProgressContent
 */
private data class Tuple6<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F
)

/**
 * Screen for Last.fm import flow.
 * Handles: username input → discovery → tier selection → import progress → completion
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastFmImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: LastFmViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()

    DeepOceanBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Import from Last.fm",
                                color = Color.White
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    // Show import completion
                    uiState.importResult != null -> {
                        ImportCompletionContent(
                            result = uiState.importResult!!,
                            onDone = {
                                viewModel.reset()
                                onNavigateBack()
                            }
                        )
                    }
                    
                    // Show import progress
                    uiState.isImporting -> {
                        ImportProgressContent(
                            progress = importProgress,
                            onCancel = viewModel::cancelImport
                        )
                    }
                    
                    // Show tier selection
                    uiState.showTierSelection && uiState.discoveryResult != null -> {
                        TierSelectionContent(
                            discovery = uiState.discoveryResult!!,
                            onSelectTier = viewModel::startImportDirect,
                            onBack = viewModel::reset
                        )
                    }
                    
                    // Show loading while discovering
                    uiState.isLoading -> {
                        LoadingContent()
                    }
                    
                    // Show username input
                    else -> {
                        UsernameInputContent(
                            error = uiState.error,
                            onSubmit = viewModel::discoverUser,
                            onClearError = viewModel::clearError
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsernameInputContent(
    error: String?,
    onSubmit: (String) -> Unit,
    onClearError: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Last.fm Logo/Title
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = me.avinas.tempo.R.drawable.ic_lastfm),
            contentDescription = null,
            tint = Color(0xFFBA0000),
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Connect Last.fm",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Import your scrobble history to unlock powerful listening insights",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Username input
        OutlinedTextField(
            value = username,
            onValueChange = { 
                username = it
                if (error != null) onClearError()
            },
            label = { Text("Last.fm Username") },
            placeholder = { Text("Enter your username") },
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (username.isNotBlank()) {
                        onSubmit(username.trim())
                    }
                }
            ),
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Connect button
        Button(
            onClick = {
                focusManager.clearFocus()
                if (username.isNotBlank()) {
                    onSubmit(username.trim())
                }
            },
            enabled = username.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBA0000),
                disabledContainerColor = Color(0xFFBA0000).copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Connect",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Info text
        Text(
            text = "We'll analyze your listening history and import recent scrobbles. Your data stays on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Color(0xFFBA0000),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Discovering your Last.fm account...",
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun TierSelectionContent(
    discovery: LastFmImportService.DiscoveryResult,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onBack: () -> Unit
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with account info
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Welcome, ${discovery.username}!",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(
                            label = "Total Scrobbles",
                            value = numberFormat.format(discovery.totalScrobbles)
                        )
                        StatItem(
                            label = "Top Tracks",
                            value = numberFormat.format(discovery.topTracksCount)
                        )
                    }
                    
                    // Recommend Standard for most users - it captures listening identity
                    // Quick is for testing, Deep for power users who want more history
                    val recommendedTier = "Standard"
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFBA0000),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recommended: $recommendedTier",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFBA0000)
                        )
                    }
                }
            }
        }
        
        // Tier selection title
        item {
            Text(
                text = "Choose Import Level",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        // Tier cards - Quick, Standard (recommended), Deep
        item {
            TierCard(
                tier = LastFmImportService.Companion.Tiers.QUICK,
                isRecommended = false,
                totalScrobbles = discovery.totalScrobbles,
                onClick = { onSelectTier(LastFmImportService.Companion.Tiers.QUICK) }
            )
        }
        
        item {
            TierCard(
                tier = LastFmImportService.Companion.Tiers.STANDARD,
                isRecommended = true, // Standard is recommended for most users
                totalScrobbles = discovery.totalScrobbles,
                onClick = { onSelectTier(LastFmImportService.Companion.Tiers.STANDARD) }
            )
        }
        
        item {
            TierCard(
                tier = LastFmImportService.Companion.Tiers.DEEP,
                isRecommended = false,
                totalScrobbles = discovery.totalScrobbles,
                onClick = { onSelectTier(LastFmImportService.Companion.Tiers.DEEP) }
            )
        }
        
        // User-friendly explanation
        item {
            Text(
                text = "💡 All your history is saved! This just affects how fast your charts load.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        // Back button
        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Use a different account",
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun TierCard(
    tier: LastFmImportService.TierConfig,
    isRecommended: Boolean,
    totalScrobbles: Long,
    onClick: () -> Unit
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }
    val description = when (tier.name) {
        "QUICK" -> 
            "Last ${tier.recentMonths} months + loved tracks. Fast and lightweight—perfect for getting started."
        "STANDARD" ->
            "Last ${tier.recentMonths} months + top ${numberFormat.format(tier.topTracksCount)} tracks + loved. Captures your listening identity. ~${tier.estimatedCoverage}% coverage."
        "DEEP" ->
            "Last ${tier.recentMonths} months + top ${numberFormat.format(tier.topTracksCount)} tracks + loved. For deep historical analysis. ~${tier.estimatedCoverage}% coverage."
        else -> "Import tier"
    }
    
    val icon = when (tier.name) {
        "QUICK" -> "⚡"
        "STANDARD" -> "⭐"
        "DEEP" -> "📊"
        else -> "📁"
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        isHighlighted = isRecommended
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = tier.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (isRecommended) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFBA0000).copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBA0000)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ImportProgressContent(
    progress: LastFmImportService.ImportProgress,
    onCancel: () -> Unit
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }
    
    // Extract values from the progress state
    val (progressPercent, phase, message, eventsCreated, archived, total) = when (progress) {
        is LastFmImportService.ImportProgress.Importing -> {
            val percent = if (progress.total > 0) ((progress.current * 100) / progress.total).toInt() else 0
            val msg = "Processing ${numberFormat.format(progress.current)} of ${numberFormat.format(progress.total)}"
            Tuple6(percent, progress.phase, msg, progress.eventsCreated, progress.archived, progress.total)
        }
        is LastFmImportService.ImportProgress.Discovering -> Tuple6(0, "Discovering", progress.message, 0L, 0L, 0L)
        is LastFmImportService.ImportProgress.Processing -> Tuple6(50, "Processing", progress.message, 0L, 0L, 0L)
        else -> Tuple6(0, "Preparing", "Starting import...", 0L, 0L, 0L)
    }
    
    val animatedProgress by animateFloatAsState(
        targetValue = progressPercent.toFloat() / 100f,
        label = "progress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress indicator
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFBA0000),
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 8.dp
            )
            
            Text(
                text = "$progressPercent%",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = phase,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Stats during import
        GlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Events",
                    value = numberFormat.format(eventsCreated)
                )
                StatItem(
                    label = "Archived",
                    value = numberFormat.format(archived)
                )
                StatItem(
                    label = "Total",
                    value = numberFormat.format(total)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Cancel button
        TextButton(onClick = onCancel) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Cancel Import",
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ImportCompletionContent(
    result: LastFmImportService.ImportResult,
    onDone: () -> Unit
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Success icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(Color(0xFF27AE60).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF27AE60),
                modifier = Modifier.size(40.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Import Complete!",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Your Last.fm history is now connected",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Results summary
        GlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                ResultRow(
                    label = "Active Events",
                    value = numberFormat.format(result.activeSetCount),
                    description = "Recent scrobbles for quick access"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                ResultRow(
                    label = "Archived",
                    value = numberFormat.format(result.archivedCount),
                    description = "Historical data stored efficiently"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                ResultRow(
                    label = "Duplicates Skipped",
                    value = numberFormat.format(result.duplicatesSkipped),
                    description = "Already in your library"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                ResultRow(
                    label = "Duration",
                    value = "${result.durationSeconds}s",
                    description = "Time to import"
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Done button
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBA0000)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Done",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF9B59B6),
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Glass card component matching the app's design.
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    val backgroundColor = if (isHighlighted) {
        Color(0xFF9B59B6).copy(alpha = 0.15f)
    } else {
        Color.White.copy(alpha = 0.1f)
    }
    
    val borderColor = if (isHighlighted) {
        Color(0xFF9B59B6).copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = 0.2f)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}
