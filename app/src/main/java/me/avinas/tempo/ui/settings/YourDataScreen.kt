package me.avinas.tempo.ui.settings

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsCatalog
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * The single "Data & diagnostics" surface: what Tempo sends on its own, and how it is
 * running on this device — one card each, both collapsed until opened.
 *
 * The card bodies answer the two questions a privacy screen gets asked. "What we collect"
 * renders [AnalyticsCatalog], which `AnalyticsCatalogTest` keeps matching the events the
 * app can actually send. The diagnostics card renders the user-initiated report, which
 * never leaves the device unless the user taps Share. Collapsed by default, the screen
 * reads as two plain summaries rather than a wall of data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourDataScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_data_diagnostics), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
        DeepOceanBackground {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.your_data_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )

                CollapsibleCard(
                    title = stringResource(R.string.what_we_collect_title),
                    description = stringResource(R.string.what_we_collect_intro),
                ) {
                    Text(
                        text = stringResource(R.string.what_we_collect_properties),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )

                    AnalyticsCatalog.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = GlassBorderSoft)
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(
                                text = entry.event,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TempoPrimary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.what,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = entry.properties.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextTertiary,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.what_we_collect_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                CollapsibleCard(
                    title = stringResource(R.string.diagnostics_title),
                    description = stringResource(R.string.diagnostics_intro),
                    onExpand = viewModel::refresh,
                ) {
                    val report = uiState.report
                    when {
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                            }
                        }

                        report == null -> {
                            Text(
                                text = stringResource(R.string.diagnostics_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }

                        else -> {
                            SelectionContainer {
                                Text(
                                    text = report,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = viewModel::refresh,
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isLoading,
                                ) {
                                    Text(stringResource(R.string.diagnostics_refresh))
                                }

                                Button(
                                    onClick = {
                                        val text = report ?: return@Button
                                        val intent =
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    context.getString(R.string.diagnostics_share_subject),
                                                )
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            }
                                        context.startActivity(Intent.createChooser(intent, null))
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor = TempoPrimary,
                                            contentColor = Color.White,
                                        ),
                                ) {
                                    Text(stringResource(R.string.diagnostics_share))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A [GlassCard] that shows a title and one-line description, and reveals its content only
 * when tapped. Expanded state survives configuration changes; [onExpand] fires each time
 * the card is opened so stale content can refresh itself.
 */
@Composable
private fun CollapsibleCard(
    title: String,
    description: String,
    onExpand: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    GlassCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
        contentPadding = PaddingValues(16.dp),
        variant = GlassCardVariant.LowProminence,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TempoPrimary,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )

            if (expanded) {
                onExpand()
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}
