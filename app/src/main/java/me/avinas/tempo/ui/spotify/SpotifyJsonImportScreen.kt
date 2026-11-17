package me.avinas.tempo.ui.spotify

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyJsonImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: SpotifyJsonImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedUris = uris
    }

    DeepOceanBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = SpotifyGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_import_spotify_json),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (uiState) {
                    is SpotifyJsonImportUiState.Idle -> {
                        IdleContent(
                            selectedUris = selectedUris,
                            onSelectFiles = {
                                filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            onImport = {
                                if (selectedUris.isNotEmpty()) {
                                    viewModel.importFiles(context, selectedUris)
                                }
                            },
                            onClearSelection = { selectedUris = emptyList() }
                        )
                    }

                    is SpotifyJsonImportUiState.Importing -> {
                        ImportingContent(importState = importState)
                    }

                    is SpotifyJsonImportUiState.Completed -> {
                        CompletedContent(
                            result = (uiState as SpotifyJsonImportUiState.Completed).result,
                            onDone = {
                                viewModel.resetState()
                                selectedUris = emptyList()
                            },
                            onNavigateBack = onNavigateBack
                        )
                    }

                    is SpotifyJsonImportUiState.Error -> {
                        ErrorContent(
                            message = (uiState as SpotifyJsonImportUiState.Error).message,
                            onRetry = {
                                viewModel.resetState()
                            },
                            onNavigateBack = onNavigateBack
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
    selectedUris: List<Uri>,
    onSelectFiles: () -> Unit,
    onImport: () -> Unit,
    onClearSelection: () -> Unit
) {
    GlassCard(
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.FileOpen,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import from Spotify Data Export",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = buildAnnotatedString {
                    append("Get your data from ")
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://spotify.com/account/privacy",
                            styles = TextLinkStyles(style = SpanStyle(color = SpotifyGreen, textDecoration = TextDecoration.Underline))
                        )
                    ) {
                        append("spotify.com/account/privacy")
                    }
                    append(", then select the StreamingHistory or endsong JSON files here.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onSelectFiles,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select JSON Files")
            }

            if (selectedUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                GlassCard(
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Column {
                        Text(
                            text = "${selectedUris.size} file(s) selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Ready to import",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onClearSelection,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }

                    Button(
                        onClick = onImport,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Import")
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(
        contentPadding = PaddingValues(16.dp)
    ) {
        Column {
            Text(
                text = "How to get your Spotify data",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val steps = listOf(
                buildAnnotatedString {
                    append("1. Go to ")
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://spotify.com/account/privacy",
                            styles = TextLinkStyles(style = SpanStyle(color = SpotifyGreen, textDecoration = TextDecoration.Underline))
                        )
                    ) {
                        append("spotify.com/account/privacy")
                    }
                },
                buildAnnotatedString { append("2. Tap \"Download your data\"") },
                buildAnnotatedString { append("3. Wait for the email (usually 24-48 hours)") },
                buildAnnotatedString { append("4. Download and extract the ZIP file") },
                buildAnnotatedString { append("5. Select StreamingHistory*.json or endsong_*.json files") }
            )

            steps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportingContent(importState: SpotifyJsonImportService.ImportState) {
    GlassCard(
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            CircularProgressIndicator(
                color = SpotifyGreen,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Importing...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val (message, progress) = when (importState) {
                is SpotifyJsonImportService.ImportState.Parsing -> {
                    "Parsing ${importState.fileName}..." to
                        (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                }
                is SpotifyJsonImportService.ImportState.Importing -> {
                    "Importing ${importState.current}/${importState.total} entries\n" +
                        "${importState.tracksImported} tracks, ${importState.eventsCreated} events" to
                        (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                }
                else -> "Preparing..." to 0f
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = SpotifyGreen,
                trackColor = GlassFrostSoft
            )
        }
    }
}

@Composable
private fun CompletedContent(
    result: SpotifyJsonImportService.ImportResult,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit
) {
    GlassCard(
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import Complete!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatRow("Tracks imported", result.tracksImported)
            StatRow("Listening events", result.eventsCreated)
            StatRow("Duplicates skipped", result.duplicatesSkipped)
            if (result.podcastsSkipped > 0) {
                StatRow("Podcasts skipped", result.podcastsSkipped)
            }
            if (result.lowQualitySkipped > 0) {
                StatRow("Low-quality plays skipped", result.lowQualitySkipped)
            }
            StatRow("Files processed", result.filesProcessed)

            if (result.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${result.errors.size} warnings",
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoWarningBright
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onDone()
                    onNavigateBack()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit
) {
    GlassCard(
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = TempoError,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import Failed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyGreen
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
