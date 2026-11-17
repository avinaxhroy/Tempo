package me.avinas.tempo.ui.stats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.share.ShareCardSheet

/**
 * The statistics share sheet: a ranking card, in every theme, in three layouts,
 * at three lengths.
 *
 * Everything a sheet needs that is not the card — the carousel, the motion
 * preview, the hidden capture source, the video export, the gallery write, the
 * tour — lives in [ShareCardSheet]. What is left here is the statistics
 * decision: what the card counts, and the deck that changes it.
 */
@Composable
fun StatsShareDialog(
    tab: StatsTab,
    timeRange: TimeRange,
    items: List<Any>,
    overview: ListeningOverview?,
    onDismiss: () -> Unit,
    // Optional subtle share nudge context (shown quietly above the share button).
    nudgeCaption: String? = null,
    // Invoked instead of onDismiss after a successful share (lets the caller
    // distinguish "shared" from "closed without sharing").
    onShared: (() -> Unit)? = null,
) {
    // The list is snapshotted when the sheet opens. It used to be read live, and
    // the stats screen empties `items` on every reload — so a background refresh
    // while the sheet was open swapped a full ranking for "No stats yet".
    val shareItems = remember { items }
    var config by remember { mutableStateOf(StatsShareConfig()) }

    ShareCardSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.stats_share_title),
        nudgeCaption = nudgeCaption,
        onShared = onShared,
        // The #1 cover sets the scene's palette, so the card's colours come from
        // the ranking the user is actually sharing — the same ranking the card
        // prints, not a second one computed beside it.
        artworkUrls =
            remember(shareItems, config.count) {
                rankedStatsItems(shareItems, config.count).map { it.imageUrl }
            },
        controls = { enabled ->
            ShareDeck(
                config = config,
                onConfigChange = { if (enabled) config = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                enabled = enabled,
            )
        },
    ) { page ->
        StatsShareCard(
            tab = tab,
            timeRange = timeRange,
            items = shareItems,
            overview = overview,
            config = config,
            card = page.card,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
