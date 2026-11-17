package me.avinas.tempo.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.*

/**
 * Enrichment Report screen.
 *
 * Shows library-wide analytics: how many songs have metadata / cover art vs. how many don't,
 * and lets the user trigger a bulk "Enrich All" sweep that runs in the background (survives
 * leaving the screen) with a live progress bar and a cancel option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrichmentReportScreen(
    onNavigateBack: () -> Unit,
    viewModel: EnrichmentReportViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val progress by viewModel.bulkProgress.collectAsStateWithLifecycle()
    val blockedNotFound by viewModel.blockedNotFound.collectAsStateWithLifecycle()

    // Poll DB counts while the sweep is running so the breakdown stays current.
    LaunchedEffect(progress.isRunning) {
        if (progress.isRunning) {
            while (true) {
                delay(3000)
                viewModel.refresh()
            }
        }
    }

    // When a sweep finishes (or is cancelled), do one final refresh so the numbers
    // reflect the end state immediately instead of staying stale.
    LaunchedEffect(progress.isRunning, progress.isDone) {
        if (!progress.isRunning && (progress.isDone || progress.total > 0)) {
            viewModel.refresh()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enrichment_report_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Description (non-technical)
                GlassCard(variant = GlassCardVariant.LowProminence) {
                    Text(
                        text = stringResource(R.string.enrichment_report_description),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Coverage bars
                GlassCard(variant = GlassCardVariant.LowProminence) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CoverageBar(
                            label = stringResource(R.string.enrichment_report_art_coverage),
                            percent = stats.artCoveragePercent,
                            summary = "${stats.withAlbumArt} / ${stats.totalTracks}"
                        )
                        CoverageBar(
                            label = stringResource(R.string.enrichment_report_coverage),
                            percent = stats.coveragePercent,
                            summary = "${stats.enriched} / ${stats.totalTracks}"
                        )
                    }
                }

                // Breakdown
                GlassCard(variant = GlassCardVariant.LowProminence) {
                    Column {
                        BreakdownRow(stringResource(R.string.enrichment_report_total_tracks), stats.totalTracks)
                        Divider()
                        BreakdownRow(stringResource(R.string.enrichment_report_with_art), stats.withAlbumArt)
                        BreakdownRow(stringResource(R.string.enrichment_report_without_art), stats.withoutAlbumArt)
                        Divider()
                        BreakdownRow(stringResource(R.string.enrichment_report_enriched), stats.enriched)
                        BreakdownRow(stringResource(R.string.enrichment_report_pending), stats.pending)
                        BreakdownRow(stringResource(R.string.enrichment_report_failed), stats.failed)
                        BreakdownRow(stringResource(R.string.enrichment_report_skipped), stats.skipped)
                        BreakdownRow(stringResource(R.string.enrichment_report_not_found), stats.notFound)
                        if (stats.notQueued > 0) {
                            BreakdownRow(stringResource(R.string.enrichment_report_not_queued), stats.notQueued)
                        }
                    }
                }

                // Action / progress
                if (progress.isRunning) {
                    GlassCard(variant = GlassCardVariant.LowProminence) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = when {
                                    progress.total > 0 ->
                                        stringResource(R.string.enrichment_report_progress, progress.processed, progress.total)
                                    progress.isWaiting ->
                                        stringResource(R.string.enrichment_report_waiting)
                                    else -> stringResource(R.string.enrichment_report_starting)
                                },
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall
                            )
                            LinearProgressIndicator(
                                progress = { if (progress.total > 0) (progress.processed.toFloat() / progress.total).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "${progress.percent}%",
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(onClick = { viewModel.cancelEnrichAll() }) {
                                    Text(stringResource(R.string.enrichment_report_cancel))
                                }
                            }
                        }
                    }
                } else {
                    val nothingToDo = stats.unenriched == 0
                    GlassCard(variant = GlassCardVariant.LowProminence) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.enrichment_report_enrich_all),
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.enrichment_report_enrich_all_desc),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (nothingToDo) {
                                Text(
                                    text = stringResource(R.string.enrichment_report_nothing_to_do),
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                Button(
                                    onClick = { viewModel.startEnrichAll() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.enrichment_report_enrich_all_button, stats.unenriched))
                                }
                            }
                            // Songs that failed every lookup recently are skipped by the
                            // sweep for 7 days (rate-limit / abuse guard) — tell the user
                            // instead of leaving them wondering why the count doesn't move.
                            if (blockedNotFound > 0) {
                                Text(
                                    text = stringResource(R.string.enrichment_report_blocked, blockedNotFound),
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (progress.isDone && stats.totalTracks > 0) {
                        Text(
                            text = stringResource(R.string.enrichment_report_complete),
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageBar(label: String, percent: Int, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(summary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
    }
}

@Composable
private fun BreakdownRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextPrimary)
        Text("$value", color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = GlassBorderSoft)
}
