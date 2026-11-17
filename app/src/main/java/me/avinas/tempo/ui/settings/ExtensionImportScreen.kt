package me.avinas.tempo.ui.settings

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.desktop.ExtensionPlaysImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessBright
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary

/**
 * Screen for importing plays exported from the Tempo Stats browser extension.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExtensionImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = context.applicationContext

    val filePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                // Persist read permissions for selected documents across process restarts.
                uris.forEach { uri ->
                    try {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: SecurityException) {
                    }
                }
                viewModel.setSelectedUris(uris)
            }
        }

    DeepOceanBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = TempoPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.extension_import_title),
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
                            titleContentColor = TextPrimary,
                            navigationIconContentColor = TextPrimary,
                        ),
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = uiState) {
                    ExtensionImportUiState.Idle -> {
                        IdleContent(
                            selectedCount = selectedUris.size,
                            onChooseFiles = {
                                filePicker.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            onImport = { viewModel.startImport() },
                        )
                    }

                    ExtensionImportUiState.Importing -> {
                        ImportingContent(importState)
                    }

                    is ExtensionImportUiState.Completed -> {
                        CompletedContent(
                            result = state.result,
                            onDone = {
                                viewModel.resetState()
                                onNavigateBack()
                            },
                        )
                    }

                    is ExtensionImportUiState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = viewModel::resetState,
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
    selectedCount: Int,
    onChooseFiles: () -> Unit,
    onImport: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = TempoPrimary,
                modifier = Modifier.size(44.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.extension_import_headline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.extension_import_how_to),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
                onClick = onChooseFiles,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.extension_import_choose_file))
            }

            if (selectedCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.extension_import_selected_files, selectedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoSuccessBright,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onImport,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = TempoPrimary,
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.extension_import_button))
                }
            }
        }
    }
}

@Composable
private fun ImportingContent(importState: ExtensionPlaysImportService.ImportState) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(
                color = TempoPrimary,
                modifier = Modifier.size(44.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.extension_import_running),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (importState) {
                is ExtensionPlaysImportService.ImportState.Parsing -> {
                    Text(
                        text = stringResource(R.string.extension_import_parsing, importState.fileName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (importState.fileCount > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text =
                                stringResource(
                                    R.string.extension_import_file_counter,
                                    importState.fileIndex + 1,
                                    importState.fileCount,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }

                is ExtensionPlaysImportService.ImportState.Importing -> {
                    Text(
                        text = stringResource(R.string.extension_import_progress, importState.processed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.extension_import_progress_added, importState.imported),
                        style = MaterialTheme.typography.bodySmall,
                        color = TempoSuccessBright,
                    )
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(18.dp))

            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = TempoPrimary,
                trackColor = GlassFrostSoft,
            )
        }
    }
}

@Composable
private fun CompletedContent(
    result: ExtensionPlaysImportService.ImportResult,
    onDone: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TempoSuccessBright,
                modifier = Modifier.size(44.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.extension_import_result_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                StatRow(stringResource(R.string.extension_import_stat_imported), result.eventsImported)
                StatRow(stringResource(R.string.extension_import_stat_duplicates), result.duplicatesSkipped)
                StatRow(stringResource(R.string.extension_import_stat_tracks), result.tracksCreated)
                StatRow(stringResource(R.string.extension_import_stat_too_short), result.tooShortSkipped)
                StatRow(stringResource(R.string.extension_import_stat_unreadable), result.malformedSkipped)
                StatRow(stringResource(R.string.extension_import_stat_files), result.filesProcessed)
            }

            if (result.truncated) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.extension_import_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoWarningBright,
                )
            }

            if (result.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    result.errors.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = TempoWarningBright,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDone,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = TempoPrimary,
                        contentColor = Color.White,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.extension_import_done))
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = TempoError,
                modifier = Modifier.size(44.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.extension_import_failed_title),
                style = MaterialTheme.typography.titleMedium,
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

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }

                Button(
                    onClick = onRetry,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = TempoPrimary,
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.extension_import_retry))
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
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


