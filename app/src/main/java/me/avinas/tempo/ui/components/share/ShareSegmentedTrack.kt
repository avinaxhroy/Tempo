package me.avinas.tempo.ui.components.share

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.GlassBorderMedium
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextOnAccent

/**
 * Track height inside a control deck, shared by every track in every sheet.
 *
 * One value rather than a per-sheet one: the stats deck and a single card's
 * summary toggle are the same strip of controls under the same carousel, and two
 * heights would read as two different components.
 */
internal val ShareDeckTrackHeight: Dp = 38.dp

/** The all-caps label above one control. */
@Composable
internal fun ShareDeckLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        lineHeight = 10.sp,
        color = Color.White.copy(alpha = 0.34f),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * A segmented track whose selection is a thumb sliding between cells.
 *
 * The thumb is a sibling of the cells, not a per-cell background, so the selection
 * has a position: moving from List to Grid travels through Podium instead of
 * blinking out and back. Offset and width come from the track's own constraints,
 * so tracks of different widths line up.
 */
@Composable
internal fun ShareSegmentedTrack(
    cellCount: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    // Padding lives outside the measured box so a cell is exactly trackWidth/count
    // wide and the thumb lands on the centre of the cell it belongs to.
    Box(
        modifier =
            modifier
                .height(ShareDeckTrackHeight)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(3.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellWidth = maxWidth / cellCount
            val thumbOffset by
                animateDpAsState(
                    targetValue = cellWidth * selectedIndex,
                    // A little spring, no bounce: the deck should feel mechanical,
                    // not playful.
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    label = "deck-thumb",
                )
            Box(
                modifier =
                    Modifier
                        .offset(x = thumbOffset)
                        .width(cellWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TempoPrimary),
            )
            Row(
                modifier = Modifier.fillMaxSize().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** One cell of a track: transparent, because the thumb behind it is the selection. */
@Composable
internal fun RowScope.ShareDeckCell(
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = 4.dp,
    spokenLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                // The label has to sit on the OUTER node. selectable() makes its own
                // semantics node, so a description applied after it ends up on a
                // child: the reader lands on "3, not checked" and never reaches the
                // words. Merging first pulls the cell's own name and its checked
                // state onto one node.
                .then(
                    if (spokenLabel == null) {
                        Modifier
                    } else {
                        Modifier.semantics(mergeDescendants = true) { contentDescription = spokenLabel }
                    },
                )
                // selectable gives the track one group of options instead of three
                // unrelated buttons.
                .selectable(selected = selected, onClick = onClick)
                .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Text/icon colour for a cell, following the thumb that is currently over it. */
@Composable
internal fun shareDeckCellTint(selected: Boolean): Color =
    animateColorAsState(
        targetValue = if (selected) TextOnAccent else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(160),
        label = "deck-cell-tint",
    ).value

/**
 * The summary on/off control: the stats deck's third column, and the only control
 * a single-subject card has.
 *
 * It is one composable rather than a copy per sheet because "summary" has to mean
 * the same thing everywhere: the numbers the card prints — the band, and on a
 * single-subject card the pills that claim a standing. A caller that hides its
 * band is offering the same choice with the same words, and two hand-built
 * switches would eventually disagree about which side is "on".
 */
@Composable
internal fun ShareSummaryToggle(
    showSummary: Boolean,
    onShowSummaryChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val change: (Boolean) -> Unit = { next ->
        if (enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onShowSummaryChange(next)
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShareDeckLabel(stringResource(R.string.stats_share_summary))
        ShareSegmentedTrack(
            // Two cells rather than a switch: at this size a bare toggle reads as
            // a fourth icon and its state has to be inferred.
            cellCount = 2,
            selectedIndex = if (showSummary) 1 else 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ShareDeckCell(
                selected = !showSummary,
                onClick = { change(false) },
                horizontalPadding = 0.dp,
                spokenLabel = stringResource(R.string.stats_share_summary_spoken_off),
            ) {
                Text(
                    text = stringResource(R.string.stats_share_off),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = shareDeckCellTint(!showSummary),
                )
            }
            ShareDeckCell(
                selected = showSummary,
                onClick = { change(true) },
                horizontalPadding = 0.dp,
                spokenLabel = stringResource(R.string.stats_share_summary_spoken_on),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = shareDeckCellTint(showSummary),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.stats_share_on),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = shareDeckCellTint(showSummary),
                    )
                }
            }
        }
    }
}

/**
 * The sheet shell a control strip sits in: a rounded surface with a drag handle,
 * on which a row of controls is laid out.
 *
 * Two sheets now put a strip under the carousel — the single-subject card with
 * its summary toggle, and the story's closing slide with its highlights count —
 * and they are the same piece of furniture. Kept apart they were the same twelve
 * lines twice, including the drag handle's 34x4dp and its 0.18 alpha, which is
 * exactly the sort of number that drifts by one dp between two copies.
 */
@Composable
internal fun ShareDeckShell(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val sheetShape = RoundedCornerShape(26.dp)
    Column(
        modifier =
            modifier
                .clip(sheetShape)
                .background(TempoSurfaceDialog)
                .border(1.dp, GlassBorderMedium, sheetShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Drag handle — the affordance that this is the bottom of a sheet.
        Box(
            modifier =
                Modifier
                    .width(34.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .align(Alignment.CenterHorizontally),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
            content = content,
        )
    }
}

/**
 * The strip a single-subject sheet puts under its carousel: just the summary
 * toggle, in the same sheet shell the stats deck uses, so the two sheets have the
 * same furniture and the same height.
 */
@Composable
internal fun ShareSingleCardDeck(
    showSummary: Boolean,
    onShowSummaryChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ShareDeckShell(modifier = modifier) {
        ShareSummaryToggle(
            showSummary = showSummary,
            onShowSummaryChange = onShowSummaryChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
    }
}

/**
 * How many entries a card prints, as a control strip.
 *
 * The story's closing slide lists artists and songs under headings; the number of
 * rows is the one thing about it a user can reasonably want to change, because ten
 * rows of small print is a different slide from three rows of large print. It is
 * the same [ShareItemCount] the ranking deck offers, so "ten" means ten in both
 * places.
 */
@Composable
internal fun ShareItemCountDeck(
    count: ShareItemCount,
    onCountChange: (ShareItemCount) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.stats_share_count_label),
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val change: (ShareItemCount) -> Unit = { next ->
        if (enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onCountChange(next)
        }
    }
    ShareDeckShell(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ShareDeckLabel(label)
            ShareSegmentedTrack(
                cellCount = ShareItemCount.entries.size,
                selectedIndex = ShareItemCount.entries.indexOf(count),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ShareItemCount.entries.forEach { option ->
                    ShareDeckCell(
                        selected = count == option,
                        onClick = { change(option) },
                        // The cell shows a bare number. Announced alone it is
                        // "3, selected", which names no setting; the column's
                        // label is a separate node and does not get read with it.
                        spokenLabel = stringResource(R.string.stats_share_count_spoken, option.count),
                    ) {
                        Text(
                            text = option.count.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = shareDeckCellTint(count == option),
                        )
                    }
                }
            }
        }
    }
}
