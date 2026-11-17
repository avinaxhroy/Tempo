package me.avinas.tempo.ui.components.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.ShareThemePalette
import me.avinas.tempo.ui.components.contrastingText

/**
 * One row of a card's track list: what it is called and how often it was played.
 *
 * A plain pair rather than a `TopTrack` or a `TrackWithStats`, so the artist card
 * (ranked listening) and the album card (a track sheet) draw the same furniture
 * without either model leaking into the other's card.
 */
data class ShareTrackRow(
    val title: String,
    val plays: Int,
)

/**
 * The ranked track list printed near the bottom of a single-subject card.
 *
 * Gold, silver, bronze on the first three ranks: the order is the point of the
 * list, so the medal does the ranking work that a second column of numbers would.
 * Rows past [maxRows] are dropped rather than scrolled — a story image has one
 * fixed frame, and a list that overflows it is a list the reader cannot finish.
 */
@Composable
fun ShareTrackList(
    palette: ShareThemePalette,
    header: String,
    rows: List<ShareTrackRow>,
    modifier: Modifier = Modifier,
    maxRows: Int = 3,
) {
    val visible = rows.take(maxRows)
    if (visible.isEmpty()) return

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, palette.divider, palette.cardShape),
        backgroundColor = palette.surfaceStrong,
        shape = palette.cardShape,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPrimary,
                fontWeight = palette.labelWeight ?: FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            visible.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        color = palette.divider,
                        thickness = 0.5.dp,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    val badgeColors = when (index) {
                        0 -> listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
                        1 -> listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8))
                        else -> listOf(Color(0xFFCD7F32), Color(0xFFB45309))
                    }
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(
                                brush = Brush.linearGradient(colors = badgeColors),
                                shape = palette.badgeShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.contrastingText(badgeColors.first()),
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.share_plays_format, row.plays),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}
