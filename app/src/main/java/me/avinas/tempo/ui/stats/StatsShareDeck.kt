package me.avinas.tempo.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.share.ShareDeckCell
import me.avinas.tempo.ui.components.share.ShareDeckLabel
import me.avinas.tempo.ui.components.share.ShareDeckShell
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.ui.components.share.ShareSegmentedTrack
import me.avinas.tempo.ui.components.share.ShareSummaryToggle
import me.avinas.tempo.ui.components.share.shareDeckCellTint

/**
 * The config deck under the carousel: layout, item count and the summary band.
 *
 * Same three controls, one hierarchy. Each now sits under its own all-caps label
 * and inside a segmented track with a thumb that *slides* between cells, which is
 * what the old row was missing: three cells of identical weight with a fill that
 * appeared and vanished gave no sense of where the selection went, and "3 / 5 /
 * 10" had no label at all, so the numbers could as easily have been pages. The
 * slide also carries the change with the card above — one gesture, one response.
 *
 * The deck stays a strip, not a settings page: every extra row here is height
 * taken from the carousel, which is the thing the user is looking at.
 */
@Composable
internal fun ShareDeck(
    config: StatsShareConfig,
    onConfigChange: (StatsShareConfig) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val change: (StatsShareConfig) -> Unit = { next ->
        if (enabled) {
            // One tick per committed choice. It is the sound of the deck, and it
            // makes a tap register even when the card's change is subtle (five to
            // ten entries, say).
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onConfigChange(next)
        }
    }
    ShareDeckShell(modifier = modifier) {
            Column(
                modifier = Modifier.weight(1.45f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShareDeckLabel(stringResource(R.string.stats_share_layout_label))
                ShareSegmentedTrack(
                    cellCount = StatsShareLayout.entries.size,
                    selectedIndex = StatsShareLayout.entries.indexOf(config.layout),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatsShareLayout.entries.forEach { layout ->
                        ShareDeckCell(
                            selected = config.layout == layout,
                            onClick = { change(config.copy(layout = layout)) },
                            spokenLabel = spokenLayoutLabel(layout),
                        ) {
                            Icon(
                                imageVector = layout.icon(),
                                // The cell carries the same words, so the icon does
                                // not repeat them on top of the announcement.
                                contentDescription = null,
                                tint = shareDeckCellTint(config.layout == layout),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(0.95f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShareDeckLabel(stringResource(R.string.stats_share_count_label))
                ShareSegmentedTrack(
                    cellCount = ShareItemCount.entries.size,
                    selectedIndex = ShareItemCount.entries.indexOf(config.count),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ShareItemCount.entries.forEach { count ->
                        ShareDeckCell(
                            selected = config.count == count,
                            onClick = { change(config.copy(count = count)) },
                            // The cell shows a bare number. Announced alone it is
                            // "3, selected", which names no setting; the column's
                            // label is a separate node and does not get read with it.
                            spokenLabel = stringResource(R.string.stats_share_count_spoken, count.count),
                        ) {
                            Text(
                                text = count.count.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = shareDeckCellTint(config.count == count),
                            )
                        }
                    }
                }
            }

            // The summary toggle is the single-subject sheets' only control, so it
            // lives in the shared strip rather than here: "summary" has to mean the
            // same numbers on every card, and a second hand-built switch would
            // eventually disagree with this one about which side is "on".
            ShareSummaryToggle(
                showSummary = config.showSummary,
                onShowSummaryChange = { change(config.copy(showSummary = it)) },
                modifier = Modifier.weight(1.05f),
                enabled = enabled,
            )
    }
}

/** "Layout: List" — the layout's name with the setting it belongs to. */
@Composable
private fun spokenLayoutLabel(layout: StatsShareLayout): String =
    stringResource(R.string.stats_share_layout_spoken, layout.label())

private fun StatsShareLayout.icon(): ImageVector =
    when (this) {
        StatsShareLayout.LIST -> Icons.AutoMirrored.Filled.ViewList
        StatsShareLayout.PODIUM -> Icons.Default.EmojiEvents
        StatsShareLayout.GRID -> Icons.Default.GridView
    }

@Composable
private fun StatsShareLayout.label(): String =
    when (this) {
        StatsShareLayout.LIST -> stringResource(R.string.stats_share_layout_list)
        StatsShareLayout.PODIUM -> stringResource(R.string.stats_share_layout_podium)
        StatsShareLayout.GRID -> stringResource(R.string.stats_share_layout_grid)
    }
