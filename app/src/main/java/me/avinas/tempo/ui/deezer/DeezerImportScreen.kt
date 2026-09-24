package me.avinas.tempo.ui.deezer

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.deezer.DeezerDataImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

private val DeezerPurple = Color(0xFFA238FF)

internal val DEEZER_IMPORT_MIME_TYPES =
    arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/octet-stream",
        "*/*",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeezerImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        selectedUri = uri
    }

    DeepOceanBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = DeezerPurple,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_import_deezer),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (
                                    uiState is DeezerImportUiState.Completed ||
                                    uiState is DeezerImportUiState.Error
                                ) {
                                    viewModel.resetState()
                                }
                                onNavigateBack()
                            },
                        ) {
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
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = uiState) {
                    DeezerImportUiState.Idle -> {
                        IdleContent(
                            hasSelection = selectedUri != null,
                            onSelect = {
                                picker.launch(DEEZER_IMPORT_MIME_TYPES)
                            },
                            onClear = { selectedUri = null },
                            onImport = {
                                selectedUri?.let { viewModel.importFile(context, it) }
                            },
                        )
                    }

                    DeezerImportUiState.Importing ->
                        ImportingContent(
                            state = importState,
                            onCancel = viewModel::cancelImport,
                        )

                    is DeezerImportUiState.Completed -> {
                        CompletedContent(
                            result = state.result,
                            onDone = {
                                viewModel.resetState()
                                onNavigateBack()
                            },
                            onImportAnother = {
                                selectedUri = null
                                viewModel.resetState()
                            },
                        )
                    }

                    is DeezerImportUiState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = {
                                selectedUri = null
                                viewModel.resetState()
                            },
                            onBack = {
                                viewModel.resetState()
                                onNavigateBack()
                            },
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
    hasSelection: Boolean,
    onSelect: () -> Unit,
    onClear: () -> Unit,
    onImport: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.FileOpen,
                contentDescription = null,
                tint = DeezerPurple,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.deezer_import_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.deezer_import_intro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.deezer_import_select_file))
            }

            if (hasSelection) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.deezer_import_file_selected),
                    color = DeezerPurple,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.deezer_import_clear))
                    }
                    Button(
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
                    ) {
                        Text(stringResource(R.string.deezer_import_start))
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Column {
            Text(
                text = stringResource(R.string.deezer_import_how_to_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                stringResource(R.string.deezer_import_step_1),
                stringResource(R.string.deezer_import_step_2),
                stringResource(R.string.deezer_import_step_3),
                stringResource(R.string.deezer_import_step_4),
                stringResource(R.string.deezer_import_step_5),
            ).forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.deezer_import_short_plays_note),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun ImportingContent(
    state: DeezerDataImportService.ImportState,
    onCancel: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = DeezerPurple, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.deezer_import_importing),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val (message, progress) = when (state) {
                is DeezerDataImportService.ImportState.Parsing ->
                    stringResource(R.string.deezer_import_reading, state.fileName) to 0.05f
                is DeezerDataImportService.ImportState.Importing ->
                    stringResource(
                        R.string.deezer_import_progress,
                        state.current,
                        state.total,
                        state.tracksImported,
                        state.eventsCreated,
                    ) to (state.current.toFloat() / state.total.coerceAtLeast(1))
                else -> stringResource(R.string.deezer_import_preparing) to 0f
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
                color = DeezerPurple,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.deezer_import_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.deezer_import_cancel))
            }
        }
    }
}

@Composable
private fun CompletedContent(
    result: DeezerDataImportService.ImportResult,
    onDone: () -> Unit,
    onImportAnother: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF27AE60),
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.deezer_import_complete),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            val summaryLines = mutableListOf(
                stringResource(R.string.deezer_import_events_imported, result.eventsCreated),
                stringResource(R.string.deezer_import_new_tracks, result.tracksImported),
                stringResource(R.string.deezer_import_duplicates_skipped, result.duplicatesSkipped),
                stringResource(R.string.deezer_import_short_skipped, result.shortPlaysSkipped),
            )
            if (result.malformedRows > 0) {
                summaryLines += stringResource(
                    R.string.deezer_import_malformed_skipped,
                    result.malformedRows,
                )
            }
            Text(
                text = summaryLines.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            if (result.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.deezer_import_warnings, result.errors.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                )

                val warningLines =
                    result.errors.take(5).map { warning ->
                        when {
                            warning.contains("Metadata for a Deezer track", ignoreCase = true) ->
                                stringResource(R.string.deezer_import_warning_metadata)
                            warning.contains("additional artist credit", ignoreCase = true) ->
                                stringResource(R.string.deezer_import_warning_artist_credit)
                            warning.contains("listening event", ignoreCase = true) ->
                                stringResource(R.string.deezer_import_warning_event)
                            warning.contains("listening-history row", ignoreCase = true) ->
                                stringResource(R.string.deezer_import_warning_row)
                            warning.contains("more errors", ignoreCase = true) ->
                                stringResource(R.string.deezer_import_warning_more)
                            else ->
                                stringResource(R.string.deezer_import_warning_unknown)
                        }
                    }.toMutableList()
                if (result.errors.size > 5) {
                    warningLines += stringResource(R.string.deezer_import_warning_more)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = warningLines.joinToString("\n") { "• $it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
            ) {
                Text(stringResource(R.string.deezer_import_done))
            }
            OutlinedButton(
                onClick = onImportAnother,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.deezer_import_another))
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.deezer_import_failed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val localizedMessage =
                when {
                    message.contains("does not contain Deezer listening history", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_missing_history)
                    message.contains("listening-history columns", ignoreCase = true) &&
                        message.contains("not supported", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_unsupported_columns)
                    message.contains("No Deezer listening-history entries", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_no_entries)
                    message.contains("too large", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_too_large)
                    message.contains("not a valid Deezer XLSX", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_invalid_xlsx)
                    message.contains("Not enough memory", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_out_of_memory)
                    message.contains("No file selected", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_no_file)
                    message.contains("Invalid file URI", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_invalid_uri)
                    message.contains("Deezer import failed", ignoreCase = true) ->
                        stringResource(R.string.deezer_import_error_generic)
                    else -> message
                }
            Text(
                text = localizedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
            ) {
                Text(stringResource(R.string.deezer_import_try_again))
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_back))
            }
        }
    }
}
