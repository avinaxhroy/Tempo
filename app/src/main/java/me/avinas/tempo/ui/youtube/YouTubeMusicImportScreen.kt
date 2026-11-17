package me.avinas.tempo.ui.youtube

import android.net.Uri
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
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import me.avinas.tempo.R
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeMusicImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: YouTubeMusicImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(uiState) {
        if (uiState is YouTubeMusicImportUiState.Completed) {
            delay(2000)
            viewModel.resetState()
            selectedUris = emptyList()
            onNavigateBack()
        }
    }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            selectedUris = uris
            if (uris.isNotEmpty()) {
                viewModel.importFiles(context, uris)
            }
        }

    DeepOceanBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = null,
                                tint = YouTubeRed,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_import_youtube_music),
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
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
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

                when (uiState) {
                    is YouTubeMusicImportUiState.Idle -> {
                        IdleContent(
                            onSelectFiles = {
                                filePickerLauncher.launch(
                                    arrayOf("application/zip", "application/x-zip", "application/json", "text/plain", "*/*"),
                                )
                            },
                        )
                    }

                    is YouTubeMusicImportUiState.Importing -> {
                        ImportingContent(importState = importState)
                    }

                    is YouTubeMusicImportUiState.Completed -> {
                        CompletedContent(
                            result = (uiState as YouTubeMusicImportUiState.Completed).result,
                        )
                    }

                    is YouTubeMusicImportUiState.Error -> {
                        ErrorContent(
                            message = (uiState as YouTubeMusicImportUiState.Error).message,
                            onRetry = {
                                viewModel.resetState()
                            },
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
private fun IdleContent(onSelectFiles: () -> Unit) {
    GlassCard(
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.FileOpen,
                contentDescription = null,
                tint = YouTubeRed,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import from YouTube Takeout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text =
                    buildAnnotatedString {
                        append("Export your data from ")
                        withLink(
                            LinkAnnotation.Url(
                                url = "https://takeout.google.com",
                                styles = TextLinkStyles(style = SpanStyle(color = YouTubeRed, textDecoration = TextDecoration.Underline)),
                            ),
                        ) {
                            append("takeout.google.com")
                        }
                        append(
                            " — the animated walkthrough below shows exactly what to click. Both JSON and HTML formats work, and you don't need to extract the ZIP.",
                        )
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onSelectFiles,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = YouTubeRed,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select File")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = "How to get your file from Google Takeout",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            TakeoutTutorial()

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Large exports are split into several ZIPs — download all of them and select them together.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )

            Text(
                text = "Only YouTube Music entries are imported; regular YouTube videos are filtered out.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )

            Text(
                text = "Takeout only includes plays recorded while Watch history was on, and exports sometimes cut off older data. If a play count looks too low, that's why.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )

            Text(
                text = "Albums, album art and genres aren't in the Takeout file. Tempo fills them in automatically in the background, which can take a few hours for large libraries.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ImportingContent(importState: YouTubeMusicImportService.ImportState) {
    GlassCard(
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(
                color = YouTubeRed,
                modifier = Modifier.size(48.dp),
            )

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
                    is YouTubeMusicImportService.ImportState.Parsing -> {
                        "Parsing ${importState.fileName}..." to
                            (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                    }

                    is YouTubeMusicImportService.ImportState.Importing -> {
                        "Importing ${importState.current}/${importState.total} entries\n" +
                            "${importState.tracksImported} tracks, ${importState.eventsCreated} events" to
                            (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                    }

                    else -> {
                        "Preparing..." to 0f
                    }
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
                color = YouTubeRed,
                trackColor = GlassFrostSoft,
            )
        }
    }
}

@Composable
private fun CompletedContent(result: YouTubeMusicImportService.ImportResult) {
    GlassCard(
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = YouTubeRed,
                modifier = Modifier.size(56.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import Complete!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatRow("Tracks imported", result.tracksImported)
            StatRow("Listening events", result.eventsCreated)
            StatRow("Duplicates skipped", result.duplicatesSkipped)
            if (result.podcastsSkipped > 0) {
                StatRow("Podcasts skipped", result.podcastsSkipped)
            }
            StatRow("Files processed", result.filesProcessed)

            if (result.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${result.errors.size} warnings",
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoWarningBright,
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
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
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
    GlassCard(
        contentPadding = PaddingValues(24.dp),
    ) {
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
                text = "Import Failed",
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
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = onRetry,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = YouTubeRed,
                        ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
