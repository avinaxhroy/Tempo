package me.avinas.tempo.ui.components.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.ShareThemePalette

/** One number in a summary band: its icon, its name, its value. */
data class ShareStatCellData(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * Accent colours for the cells, by position.
 *
 * Positional rather than per-cell: the first number is always plays, the second
 * always time, and tinting them consistently is what makes two different cards
 * read as the same family. A caller never picks a colour.
 */
private val ShareStatTints =
    listOf(
        Color(0xFFF472B6),
        Color(0xFFC084FC),
        Color(0xFF38BDF8),
    )

/**
 * The summary band every single-subject card draws: a glass panel of aggregate
 * numbers separated by hairlines.
 *
 * It exists as one composable because "summary" is the thing the share sheets now
 * let the user switch off. If each card grew its own band, the toggle would mean
 * a slightly different block on each screen, and the word would stop being useful.
 */
@Composable
internal fun ShareStatBand(
    palette: ShareThemePalette,
    cells: List<ShareStatCellData>,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = palette.surface,
        shape = palette.cardShape,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) {
                    ShareStatDivider(
                        palette = palette,
                        height = 26.dp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                ShareStatCell(
                    cell = cell,
                    palette = palette,
                    tint = ShareStatTints[index % ShareStatTints.size],
                )
            }
        }
    }
}

/** One column of the band: icon + name on one line, the number under it. */
@Composable
private fun RowScope.ShareStatCell(
    cell: ShareStatCellData,
    palette: ShareThemePalette,
    tint: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = cell.icon,
                // The label beside it is the same words; announcing both would
                // say it twice.
                contentDescription = null,
                tint = if (palette.isDark) tint else palette.accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = cell.label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                letterSpacing = palette.labelTracking ?: 1.5.sp,
                fontSize = 9.sp,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = cell.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = palette.headlineWeight ?: FontWeight.Black,
            color = palette.textPrimary,
            // A third of the band is not much room; the value keeps one line,
            // and the number formatters keep it short enough to fit.
            maxLines = 1,
            fontSize = 20.sp,
        )
    }
}
