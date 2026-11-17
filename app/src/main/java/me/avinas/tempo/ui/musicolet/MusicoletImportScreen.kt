package me.avinas.tempo.ui.musicolet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.avinas.tempo.R
import me.avinas.tempo.data.musicolet.MusicoletImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

/** Musicolet's own accent, so the screen reads as its importer rather than a generic one. */
private val MusicoletAccent = Color(0xFF6E56CF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicoletImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: MusicoletImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val includeUndated by viewModel.includeUndatedPlays.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState) {
        if (uiState is MusicoletImportUiState.Completed) {
            delay(2500)
            viewModel.resetState()
            onNavigateBack()
        }
    }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                viewModel.importFile(context, uri)
            }
        }

    DeepOceanBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = null,
                                tint = MusicoletAccent,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_import_musicolet),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                                tint = TextPrimary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = uiState) {
                    is MusicoletImportUiState.Idle -> {
                        IdleContent(
                            includeUndated = includeUndated,
                            onIncludeUndatedChange = viewModel::setIncludeUndatedPlays,
                            onSelectFile = {
                                filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip", "*/*"))
                            },
                        )
                    }

                    is MusicoletImportUiState.Importing -> {
                        ImportingContent(importState = importState)
                    }

                    is MusicoletImportUiState.Completed -> {
                        CompletedContent(result = state.result)
                    }

                    is MusicoletImportUiState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = { viewModel.resetState() },
                            onNavigateBack = onNavigateBack,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun IdleContent(
    includeUndated: Boolean,
    onIncludeUndatedChange: (Boolean) -> Unit,
    onSelectFile: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.FileOpen,
                contentDescription = null,
                tint = MusicoletAccent,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import from a Musicolet backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text =
                    "In Musicolet, open Settings → Backup and restore → Backup now. " +
                        "Musicolet writes a ZIP wherever you choose; select that ZIP here. " +
                        "You do not need to unzip it.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onSelectFile,
                colors = ButtonDefaults.buttonColors(containerColor = MusicoletAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select backup file")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Column {
            Text(
                text = "What Musicolet actually stores",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "Musicolet keeps a play count per song per month, not a timestamp per " +
                        "play, plus the time each song was last played. Tempo spreads each " +
                        "month's plays across that month, so monthly and yearly totals are " +
                        "right while the exact day and hour are an estimate. The last-played " +
                        "time is exact and is kept exactly.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "These plays are recorded as reconstructed history, so they can never " +
                        "overwrite or hide anything Tempo measured while you listened. " +
                        "Importing the same backup twice changes nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Include undated plays",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Musicolet counts plays from before it started keeping monthly files " +
                            "but has no date for them. Turn this on to place them at the start " +
                            "of each song's history so lifetime totals match Musicolet. Left " +
                            "off, they are reported and left out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = includeUndated, onCheckedChange = onIncludeUndatedChange)
        }
    }
}

@Composable
private fun ImportingContent(importState: MusicoletImportService.ImportState) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(color = MusicoletAccent, modifier = Modifier.size(48.dp))

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Importing...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            val (message, progress) =
                when (importState) {
                    is MusicoletImportService.ImportState.Parsing ->
                        "Reading ${importState.fileName.ifBlank { "backup" }}..." to
                            (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))

                    is MusicoletImportService.ImportState.Importing ->
                        "Importing ${importState.current} of ${importState.total} plays\n" +
                            "${importState.tracksImported} tracks, ${importState.eventsCreated} events" to
                            (importState.current.toFloat() / importState.total.coerceAtLeast(1))

                    else -> "Preparing..." to 0f
                }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MusicoletAccent,
                trackColor = GlassFrostSoft,
            )
        }
    }
}

@Composable
private fun CompletedContent(result: MusicoletImportService.ImportResult) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MusicoletAccent,
                modifier = Modifier.size(56.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import complete",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatRow("Songs with plays", result.songsImported)
            StatRow("Listening events", result.eventsCreated)
            StatRow("New tracks", result.tracksImported)
            StatRow("Already in Tempo", result.duplicatesSkipped)
            if (result.periodFilesRead > 0) {
                StatRow("Monthly files read", result.periodFilesRead)
            }
            if (result.lastPlayedAnchors > 0) {
                StatRow("Exact last-played times", result.lastPlayedAnchors.toInt())
            }

            val period = formatPeriod(result.earliestPlayMs, result.latestPlayMs)
            if (period != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Covers $period",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }

            if (result.undatedPlays > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text =
                        if (result.anchoredUndatedPlays > 0) {
                            "${result.anchoredUndatedPlays} plays had no date in Musicolet and were " +
                                "placed at the start of their song's history."
                        } else {
                            "${result.undatedPlays} plays had no date in Musicolet and were left out. " +
                                "Import again with \"Include undated plays\" to keep them."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (result.unmatchedPeriodPlays > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        "${result.unmatchedPeriodPlays} plays belong to songs that are no longer " +
                            "in the backup's song list, so they had no title to import.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            if (result.droppedPlays > 0 || result.truncated) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        "Some plays could not be given a distinct time and were skipped " +
                            "(${result.droppedPlays}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoWarningBright,
                    textAlign = TextAlign.Center,
                )
            }

            if (result.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = result.warnings.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Continuing automatically...",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = TempoError,
                modifier = Modifier.size(56.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import failed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onNavigateBack, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = MusicoletAccent),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Try again")
                }
            }
        }
    }
}

/** `Mar 2019 – Oct 2026`, or null when the import placed no dated plays. */
private fun formatPeriod(
    earliestMs: Long?,
    latestMs: Long?,
): String? {
    if (earliestMs == null || latestMs == null) return null
    val format = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
    val start = format.format(java.util.Date(earliestMs))
    val end = format.format(java.util.Date(latestMs))
    return if (start == end) start else "$start – $end"
}
