package me.avinas.tempo.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.FitToHeight
import me.avinas.tempo.ui.components.ShareCardLayoutStyle
import me.avinas.tempo.ui.components.ShareThemePalette
import me.avinas.tempo.ui.components.asShareArt
import me.avinas.tempo.ui.components.contrastingText
import me.avinas.tempo.ui.components.share.ShareCardSurface
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.ui.components.share.ShareStatDivider
import me.avinas.tempo.ui.components.share.ShareStorySafeTop
import me.avinas.tempo.ui.components.share.formatShareCount

enum class StatsShareLayout { LIST, PODIUM, GRID }

// Theme system (ShareTheme / ShareThemePalette) lives in
// ui/components/ShareTheme.kt — shared with the song/artist details share
// cards so every share surface offers the same themes.

/**
 * What the stats deck edits: how many entries, in what shape, with or without
 * the summary cell.
 *
 * Not the theme. [ShareCardTheme] is the single value every share card takes,
 * and the sheet owns it — folding a copy of it in here is how the two drift.
 */
data class StatsShareConfig(
    val count: ShareItemCount = ShareItemCount.TOP_5,
    val layout: StatsShareLayout = StatsShareLayout.LIST,
    val showSummary: Boolean = true,
)

data class StatsItemInfo(
    val title: String,
    /**
     * The line under the title, or null when the item has none of its own and
     * the card should print its play count instead.
     *
     * Null rather than a pre-formatted "12 plays": this string is baked into
     * the exported PNG, so it has to be resolved from resources at render time.
     * An English count on a German phone would stay in the file the user keeps.
     */
    val subtitle: String?,
    val imageUrl: String?,
    val plays: Int,
    val timeMs: Long,
)

/** The subtitle line the card draws: the item's own, or its play count. */
@Composable
fun statsItemSubtitle(info: StatsItemInfo): String =
    info.subtitle ?: stringResource(R.string.share_plays_format, info.plays)

/**
 * The stats screens' `formatListeningTime`, resolved from resources.
 *
 * Same reason as [statsItemSubtitle]: this card is captured into a PNG the user
 * keeps and sends, so its text cannot be hardcoded English. The screen-side
 * helper keeps its hardcoded form because it never leaves the app.
 */
@Composable
fun formatShareListeningTime(millis: Long): String {
    val totalMinutes = millis / 1000L / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        // Past a hundred hours the minutes are a rounding error in a column
        // that is a third of the card wide; the hours alone are the figure.
        hours >= 100L -> stringResource(R.string.share_time_hours_format, hours)
        hours > 0 -> stringResource(R.string.spotlight_time_hours, hours, minutes)
        minutes > 0 -> stringResource(R.string.spotlight_time_minutes, minutes)
        else -> stringResource(R.string.share_time_less_than_minute)
    }
}

/**
 * The items this card prints, ranked, with nothing it cannot print dropped.
 *
 * The card's rows and the sheet's palette extraction both want this list, and
 * they used to build it separately: the card ranked everything into `ranked`,
 * and the sheet ranked the same items into a parallel list of cover URLs. Two
 * lists that are *supposed* to be the same but are computed twice can disagree
 * for a frame — the card painting cover A while the scene was shaded from cover
 * B — so there is one ranking and both read it.
 */
fun rankedStatsItems(
    items: List<Any>,
    count: ShareItemCount,
): List<StatsItemInfo> =
    items
        .asSequence()
        .mapNotNull { statsItemInfo(it) }
        .take(count.count)
        .toList()

fun statsItemInfo(item: Any): StatsItemInfo? =
    when (item) {
        is TopTrack -> {
            StatsItemInfo(
                title = item.title,
                subtitle = item.artist,
                imageUrl = item.albumArtUrl,
                plays = item.playCount,
                timeMs = item.totalTimeMs,
            )
        }

        is TopArtist -> {
            StatsItemInfo(
                title = item.artist,
                // An artist has no second name; the card prints the play count
                // instead, localised at render time.
                subtitle = null,
                imageUrl = item.imageUrl,
                plays = item.playCount,
                timeMs = item.totalTimeMs,
            )
        }

        is TopAlbum -> {
            StatsItemInfo(
                title = item.album,
                subtitle = item.artist,
                imageUrl = item.albumArtUrl,
                plays = item.playCount,
                timeMs = item.totalTimeMs,
            )
        }

        else -> {
            null
        }
    }

@Composable
fun StatsShareCard(
    tab: StatsTab,
    timeRange: TimeRange,
    items: List<Any>,
    overview: ListeningOverview?,
    config: StatsShareConfig = StatsShareConfig(),
    // How the card is printed — theme, scene clock, cover palette. The same
    // value the song, artist, badge and milestone cards take.
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
) {
    val titleRes =
        when (tab) {
            StatsTab.TOP_SONGS -> R.string.stats_share_top_songs
            StatsTab.TOP_ARTISTS -> R.string.stats_share_top_artists
            StatsTab.TOP_ALBUMS -> R.string.stats_share_top_albums
        }
    val periodRes =
        when (timeRange) {
            TimeRange.TODAY -> R.string.stats_share_period_today
            TimeRange.THIS_WEEK -> R.string.stats_share_period_this_week
            TimeRange.THIS_MONTH -> R.string.stats_share_period_this_month
            TimeRange.THIS_YEAR -> R.string.stats_share_period_this_year
            TimeRange.ALL_TIME -> R.string.stats_share_period_all_time
        }
    val ranked =
        remember(items, config.count) {
            rankedStatsItems(items, config.count)
        }
    val bgImage = ranked.firstOrNull()?.imageUrl

    // The page — gradient, cover blur, scene, the veil that holds type over it
    // and the branding — is ShareCardSurface, the same one every other share
    // card prints on. Only the ranking inside belongs to this file. The surface
    // hands back the palette to ink the card with, because a moving theme
    // re-derives its accent from the scene it is shading.
    ShareCardSurface(
        card = card,
        art = bgImage.asShareArt(),
        modifier = modifier.fillMaxSize(),
    ) { palette ->
        // A scene sets its data block on a pane (see `frostedPane`); a still
        // sheet prints straight on the page. The pane is a card lying on the
        // stock, so it may run closer to the ruled edge than the page's own
        // text measure — which buys back the title width the pane's inset costs.
        // Both kinds of scene take it: Halftone's print is light and wants white
        // glass, Glyphs' glyph field is bright over a dark page and wants the
        // same pane in black — a hairline rule laid over that field is not a
        // rule at all.
        val pane =
            palette.layoutStyle == ShareCardLayoutStyle.INDEX &&
                palette.overScene
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // An INDEX page draws its own rule around the stock (see
                    // PAPER_PAGE in ShareTheme), and a text block set 20dp from
                    // the trim lands about a point inside that rule: the masthead,
                    // the period and the summary all read as touching the border
                    // rather than sitting in a margin. A ruled page needs a real
                    // gutter — the rule is the page edge, so the type has to clear
                    // it by the width of a printed margin. Poster themes draw no
                    // rule and keep the tighter trim, where the card edge itself
                    // is the only boundary to clear.
                    .padding(
                        horizontal =
                            when {
                                palette.layoutStyle != ShareCardLayoutStyle.INDEX -> 20.dp
                                pane -> 32.dp
                                else -> 40.dp
                            },
                    )
                    // Top safe-zone: clears the Instagram/story username + avatar
                    // overlay on the upper-left. It is the same inset every other
                    // share card uses (ShareStorySafeTop), because the overlay
                    // belongs to the host app rather than to this card. Bottom
                    // padding reserves the TEMPO watermark footer (16dp inset +
                    // ~20dp text) so the last list row never crowds it.
                    // FitToHeight below scales the whole column uniformly into
                    // the remaining height, so every layout (list/podium/grid) and
                    // count (3/5/10) keeps its structure.
                    .padding(top = ShareStorySafeTop, bottom = 56.dp),
        ) {
            // On an INDEX page the rules carry the layout, so rules stopping two
            // thirds down mean a setting error, not less content. `stretch` re-measures
            // the column against the sheet so the ranked block below can claim the
            // rest of the page. Every other theme keeps scale-down only: a poster is
            // a fixed composition, and blowing it up to fill would enlarge the type
            // instead of the air.
            FitToHeight(
                modifier = Modifier.fillMaxWidth(),
                // A grid is re-composed against the frame for the same reason a
                // ruled page is: its cells have to be told how much height they
                // have before they can divide it. Everything else keeps the
                // natural pass and is scaled down only if it overruns it.
                stretch = palette.layoutStyle == ShareCardLayoutStyle.INDEX ||
                    config.layout == StatsShareLayout.GRID,
            ) {
                // A scene is a printed page, and the quiet labels keep losing
                // their ground to it: a bold title survives a halftone mass or a
                // glyph field, an 8sp period or plays label does not. On a scene
                // the whole data block — masthead, summary, ranking — is set on
                // one frosted pane, so the print still reads through it while
                // the type sits on glass. A still sheet has no scene behind it
                // and keeps the open page.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (pane) {
                                    Modifier.frostedPane(dark = palette.isDark)
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (pane) {
                                    // The pane's own margin: type set on the rim
                                    // of a foreground object reads as a label
                                    // that fell off a card. Fourteen, not the
                                    // eighteen a card would want, because every
                                    // dp here comes straight out of the title
                                    // column on a ten-row page.
                                    Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header. Standard themes lead with an accent tick and a
                    // stacked title; the INDEX themes print a masthead instead,
                    // because a paper page has no chrome to hang a colour bar on.
                    if (palette.layoutStyle == ShareCardLayoutStyle.INDEX) {
                        IndexHeader(
                            title = stringResource(titleRes),
                            period = stringResource(periodRes),
                            palette = palette,
                        )
                    } else {
                        StandardHeader(
                            title = stringResource(titleRes),
                            period = stringResource(periodRes),
                            palette = palette,
                        )
                    }

                    if (config.showSummary && overview != null) {
                        if (palette.layoutStyle == ShareCardLayoutStyle.INDEX) {
                            IndexSummary(overview = overview, palette = palette)
                        } else {
                            SummaryCard(overview = overview, palette = palette)
                        }
                    }

                    if (ranked.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.stats_no_stats_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        // The theme decides *how* a layout is printed, not
                        // which one the deck picked: an INDEX page still offers
                        // list, podium and grid, it just draws each of them as
                        // ruled type instead of as cards.
                        val index = palette.layoutStyle == ShareCardLayoutStyle.INDEX
                        // On an INDEX page the ranking is the body of the
                        // sheet, so it takes the height the masthead and the
                        // summary leave behind — the same way a printed table
                        // runs to the foot of the page instead of stopping
                        // over a block of empty stock.
                        // A grid is a block of squares rather than a stack of
                        // rows, so on a poster it takes the remainder of the
                        // page too: sized on width alone its cells run off the
                        // foot of the sheet, and left unweighted it stops
                        // halfway down and leaves the rest as air. A list and a
                        // podium keep their own shape — they are compositions,
                        // and a composition does not stretch.
                        val fills = index || config.layout == StatsShareLayout.GRID
                        Box(
                            modifier =
                                if (fills) {
                                    Modifier.fillMaxWidth().weight(1f)
                                } else {
                                    Modifier
                                },
                        ) {
                            when (config.layout) {
                            StatsShareLayout.LIST ->
                                if (index) {
                                    IndexList(ranked, palette, config.count)
                                } else {
                                    ListLayout(ranked, palette, config.count)
                                }
                            StatsShareLayout.PODIUM ->
                                if (index) {
                                    IndexPodium(ranked, palette, config.count)
                                } else {
                                    PodiumLayout(ranked, palette, config.count)
                                }
                            StatsShareLayout.GRID ->
                                if (index) {
                                    IndexGrid(ranked, palette, config.count, tab)
                                } else {
                                    GridLayout(ranked, palette, config.count, tab)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    overview: ListeningOverview,
    palette: ShareThemePalette,
) {
    // Drawn from the palette rather than through GlassCard. GlassCard is the
    // app's own glass, and its brush ends on a fixed tint of the app's dark
    // theme; on a share card that is a foreign colour, and on DAYLIGHT it was
    // literally that — a grey-teal slab laid over a peach sky, with the
    // card's warm border cutting across its top edge. A card that carries its
    // own palette cannot borrow the app's glass.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(palette.cardShape)
                .background(palette.surface)
                .border(1.dp, palette.accent.copy(alpha = 0.25f), palette.cardShape)
                .padding(10.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_time),
                value = formatShareListeningTime(overview.totalListeningTimeMs),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
            ShareStatDivider(palette = palette, height = 30.dp)
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_plays),
                value = formatShareCount(overview.totalPlayCount),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
            ShareStatDivider(palette = palette, height = 30.dp)
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_unique),
                value = formatShareCount(overview.uniqueTracksCount),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = palette.labelWeight ?: FontWeight.Medium,
            color = palette.textSecondary,
            letterSpacing = palette.labelTracking ?: 1.5.sp,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = palette.headlineWeight ?: FontWeight.Black,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Rank-badge/pedestal text contrast is provided by
// ShareThemePalette.contrastingText() in ui/components/ShareTheme.kt.

@Composable
private fun RankBadge(
    rank: Int,
    size: Dp,
    palette: ShareThemePalette,
) {
    val colors =
        remember(rank, palette) {
            when (rank) {
                1 -> listOf(palette.rank1Tint, palette.rank1Tint)
                // Silver and bronze are theme data (rank2Tint / rank3Tint):
                // the same two metals read wrong on a paper page and wrong on
                // a midnight one, so the palette owns them now.
                2 -> listOf(palette.rank2Tint, palette.rank2Tint.copy(alpha = 0.72f))
                3 -> listOf(palette.rank3Tint, palette.rank3Tint.copy(alpha = 0.72f))
                else -> listOf(palette.accent.copy(alpha = 0.55f), palette.accent.copy(alpha = 0.25f))
            }
        }
    val brush = remember(colors) { Brush.linearGradient(colors) }
    val textColor = remember(colors, palette) { palette.contrastingText(colors.first()) }
    Box(
        modifier =
            Modifier
                .size(size)
                .background(brush = brush, palette.badgeShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

@Composable
private fun ItemThumbnail(
    size: Dp,
    imageUrl: String?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
) {
    val targetSize = remember(size) { (size.value * 2.5f).toInt().coerceAtLeast(64) }
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(shape)
                .border(borderWidth, borderColor, shape),
    ) {
        CachedAsyncImage(
            imageUrl = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            targetSizeDp = targetSize,
            // No `allowHardware = false` here: the capture wrapper already
            // forces software bitmaps through LocalInCaptureContext, and asking
            // for them unconditionally meant every cover in the on-screen
            // preview was decoded once more into the separate "_sw" memory-cache
            // entry, with no hardware bitmap path for the common case.
            placeholder = {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Gray),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                }
            },
        )
    }
}

@Composable
private fun ListLayout(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
) {
    val showHero = count != ShareItemCount.TOP_10
    val rowHeight =
        when (count) {
            ShareItemCount.TOP_3 -> 46.dp
            ShareItemCount.TOP_5 -> 44.dp
            ShareItemCount.TOP_10 -> 36.dp
        }
    val thumbSize =
        when (count) {
            ShareItemCount.TOP_3 -> 38.dp
            ShareItemCount.TOP_5 -> 36.dp
            ShareItemCount.TOP_10 -> 30.dp
        }
    val showSubtitle = count != ShareItemCount.TOP_10

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHero) {
            val hero = items.first()
            Row(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ItemThumbnail(
                    size = 56.dp,
                    imageUrl = hero.imageUrl,
                    shape = CircleShape,
                    borderColor = palette.rank1Tint,
                    borderWidth = 2.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stats_share_rank_1),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = palette.labelWeight ?: FontWeight.Bold,
                        color = palette.rank1Tint,
                        letterSpacing = palette.labelTracking ?: 1.sp,
                    )
                    Text(
                        text = hero.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = palette.headlineWeight ?: FontWeight.Black,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showSubtitle) {
                        Text(
                            text = statsItemSubtitle(hero),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = formatShareListeningTime(hero.timeMs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                )
            }
            val rest = items.drop(1)
            if (rest.isNotEmpty()) {
                HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            }
            rest.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 2,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = showSubtitle,
                    palette = palette,
                )
            }
        } else {
            items.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 1,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = showSubtitle,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun CompactRow(
    rank: Int,
    info: StatsItemInfo,
    rowHeight: Dp,
    thumbSize: Dp,
    showSubtitle: Boolean,
    palette: ShareThemePalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RankBadge(rank = rank, size = 20.dp, palette = palette)
        ItemThumbnail(size = thumbSize, imageUrl = info.imageUrl, shape = palette.thumbShape)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle) {
                Text(
                    text = statsItemSubtitle(info),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = formatShareListeningTime(info.timeMs),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = palette.accent,
        )
    }
}

@Composable
private fun PodiumLayout(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
) {
    val topThree = items.take(3)
    val rest = items.drop(3)
    val rowHeight =
        when (count) {
            ShareItemCount.TOP_3 -> 40.dp
            ShareItemCount.TOP_5 -> 38.dp
            ShareItemCount.TOP_10 -> 30.dp
        }
    val thumbSize =
        when (count) {
            ShareItemCount.TOP_3 -> 30.dp
            ShareItemCount.TOP_5 -> 28.dp
            ShareItemCount.TOP_10 -> 24.dp
        }
    // A podium is usually asked for three entries, and three entries is a
    // ranking with nothing under it. Drawn at the size it takes when seven
    // rows follow, the steps stop a third of the way down and the rest of the
    // sheet is blank stock: the card reads as a header with a diagram pinned
    // beneath it. Where the podium *is* the whole ranking it takes the height
    // instead - larger covers, taller steps - so the pedestals land near the
    // foot of the page, which is where the base of a podium belongs.
    val soloPodium = count == ShareItemCount.TOP_3
    val podiumHeight = if (soloPodium) 360.dp else 190.dp
    val winnerPedestal = if (soloPodium) 160.dp else 74.dp
    val runnerPedestal = if (soloPodium) 120.dp else 50.dp
    val thirdPedestal = if (soloPodium) 96.dp else 38.dp
    val winnerAvatar = if (soloPodium) 110.dp else 60.dp
    val runnerAvatar = if (soloPodium) 86.dp else 48.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (topThree.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(podiumHeight)) {
                // Glow behind the winner
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 4.dp)
                            .size(if (soloPodium) 240.dp else 180.dp)
                            .background(
                                brush =
                                    Brush.radialGradient(
                                        colors = listOf(palette.rank1Tint.copy(alpha = 0.3f), Color.Transparent),
                                    ),
                                shape = CircleShape,
                            ),
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val steps =
                        remember(topThree, palette, soloPodium) {
                            val width = if (soloPodium) 88.dp else 72.dp
                            val list = mutableListOf<PodiumStepData>()
                            if (topThree.size > 1) {
                                list.add(
                                    PodiumStepData(
                                        info = topThree[1],
                                        rank = 2,
                                        pedestalHeight = runnerPedestal,
                                        avatarSize = runnerAvatar,
                                        tint = palette.rank2Tint,
                                        pedestalWidth = width,
                                    ),
                                )
                            }
                            list.add(
                                PodiumStepData(
                                    info = topThree[0],
                                    rank = 1,
                                    pedestalHeight = winnerPedestal,
                                    avatarSize = winnerAvatar,
                                    tint = palette.rank1Tint,
                                    isWinner = true,
                                    pedestalWidth = width,
                                ),
                            )
                            if (topThree.size > 2) {
                                list.add(
                                    PodiumStepData(
                                        info = topThree[2],
                                        rank = 3,
                                        pedestalHeight = thirdPedestal,
                                        avatarSize = runnerAvatar,
                                        tint = palette.rank3Tint,
                                        pedestalWidth = width,
                                    ),
                                )
                            }
                            list
                        }
                    steps.forEach { PodiumStep(it, palette) }
                }
            }
        }
        if (rest.isNotEmpty()) {
            HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            rest.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 4,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = count != ShareItemCount.TOP_10,
                    palette = palette,
                )
            }
        }
    }
}

private data class PodiumStepData(
    val info: StatsItemInfo,
    val rank: Int,
    val pedestalHeight: Dp,
    val avatarSize: Dp,
    val tint: Color,
    val isWinner: Boolean = false,
    /** Width of the step itself; a solo podium prints wider blocks. */
    val pedestalWidth: Dp = 72.dp,
)

@Composable
private fun PodiumStep(
    step: PodiumStepData,
    palette: ShareThemePalette,
) {
    Column(
        modifier = Modifier.width(maxOf(104.dp, step.pedestalWidth + 16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        // No trophy sticker over the winner. It sat above the cover as a
        // borrowed pictogram — the same medal every ranking graphic in the
        // world pins on first place — while the card already says it three
        // ways: the tallest step, the largest cover, and the heaviest ring.
        // On a moonlight theme the medal was saturated against everything else
        // on the sheet, so it pulled the eye to the icon instead of the winner.
        ItemThumbnail(
            size = step.avatarSize,
            imageUrl = step.info.imageUrl,
            shape = CircleShape,
            borderColor = step.tint,
            borderWidth = if (step.isWinner) 3.dp else 2.dp,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = step.info.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = formatShareListeningTime(step.info.timeMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            // Medal tints (esp. silver) are tuned for dark backdrops; fall back to
            // the theme's readable primary text on light themes.
            color = if (palette.isDark) step.tint else palette.textPrimary,
        )
        Spacer(modifier = Modifier.height(5.dp))
        // Pedestal
        Box(
            modifier =
                Modifier
                    .width(step.pedestalWidth)
                    .height(step.pedestalHeight)
                    .background(
                        // The step used to fade to a quarter alpha at the
                        // foot, which on a light page left the numeral
                        // printing white on bare paper. A podium step is a
                        // block of stock, not a gradient into the sheet, so
                        // it holds its ink all the way down and only lifts
                        // slightly at the base.
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(step.tint.copy(alpha = 0.92f), step.tint.copy(alpha = 0.58f)),
                            ),
                    ).border(1.dp, step.tint.copy(alpha = 0.5f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${step.rank}",
                // The numeral is the only thing on the step, so it is set
                // to the block: a tall solo step would otherwise carry a
                // caption-sized digit in the middle of a slab of colour.
                style =
                    if (step.pedestalHeight >= 120.dp) {
                        MaterialTheme.typography.displaySmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                color = palette.contrastingText(step.tint),
            )
        }
    }
}

/**
 * Splits [size] items into the fewest rows of at most [columns] cells, keeping
 * the rows as even as the count allows.
 *
 * Ten covers over three columns divide as 3·3·3·1, and an orphaned single
 * cover under three full rows reads as a layout bug. Evened out to 3·3·2·2
 * every line is a block, and the shortfall is paid twice instead of once.
 */
private fun balancedRowSizes(size: Int, columns: Int): List<Int> {
    if (size <= 0 || columns <= 0) return emptyList()
    val rows = (size + columns - 1) / columns
    val base = size / rows
    val extra = size % rows
    return List(rows) { index -> base + if (index < extra) 1 else 0 }
}

/**
 * The GRID composition: covers at their own shape, ranked by a badge.
 *
 * A song or an album prints as a SQUARE, because that is the shape its artwork
 * is; an artist prints as a CIRCLE, because that is how an artist is drawn
 * everywhere else in this app. The rectangular cell this grid used before was
 * neither: it took a square sleeve and cropped it into a landscape strip, which
 * at five entries read as a row of banners rather than as a shelf of covers.
 *
 * The column count follows from the shape. A square needs the height a banner
 * did not, and a circle needs a line of type beneath it as well, so the two
 * grids spend the same page differently: covers go two across at three and
 * five, three across at ten, and artists trade size for count sooner.
 */
@Composable
private fun GridLayout(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
    tab: StatsTab,
) {
    val artists = tab == StatsTab.TOP_ARTISTS
    // Three across is the size a five-item artist board wants: a disc at two
    // columns is a portrait shown small, and the page ends in a band of empty
    // stock beneath it. A square can afford two columns at five — it fills the
    // width it is given — but a circle has to be big to read as a face.
    //
    // Ten runs the same argument one step further, and the two shapes part
    // company there. Ten covers want three across, not two: two columns are
    // five rows, and five squares in a 9:16 sheet shrink to a narrow strip
    // with clipped names. Ten discs would pay the caption band on those five
    // rows and still leave the face small, so artists stay wide: four across
    // is three rows of big discs and the caption is only paid three times.
    val columns =
        if (artists) {
            when (count) {
                ShareItemCount.TOP_3 -> 2
                ShareItemCount.TOP_5 -> 3
                ShareItemCount.TOP_10 -> 4
            }
        } else {
            // Ten covers at two columns put five rows on the page, and five
            // squares alone in a 9:16 sheet have to shrink until the grid is a
            // narrow strip down the middle: barely half the width, covers at
            // ~70dp with every title clipped to six characters. Three across
            // spends the spare width on the covers instead (four rows at
            // ~90dp) and reads as a shelf rather than a column.
            when (count) {
                ShareItemCount.TOP_3, ShareItemCount.TOP_5 -> 2
                ShareItemCount.TOP_10 -> 3
            }
        }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 8.dp
        // Ten covers do not divide by three. Letting the last row take the
        // leftover prints a single orphaned cover under three full rows;
        // evening the rows (3·3·2·2) keeps every line a block.
        val rowSizes = balancedRowSizes(items.size, columns)
        val rows = rowSizes.size.coerceAtLeast(1)
        // A cell is a square, so its width and its height are one number and
        // the grid has to satisfy both. Sized on width alone it ran off the
        // foot of the sheet, and on a ruled page that put the last cover over
        // the frame and the colophon. Height is only there to be spent when
        // the page hands the block its remainder (an INDEX page weighs this
        // grid); on a poster the column is measured unconstrained and the
        // width is all the grid can know.
        val caption = if (artists) ARTIST_CAPTION_BAND else 0.dp
        val byWidth = (maxWidth - gap * (columns - 1)) / columns
        val byHeight =
            if (maxHeight == Dp.Infinity) {
                byWidth
            } else {
                (maxHeight - caption * rows - gap * (rows - 1)) / rows
            }
        val cell = minOf(byWidth, byHeight).coerceAtLeast(56.dp)
        // The cell is handed the width it actually measured rather than a fixed
        // request. A 320dp decode into a ~100dp cell is four times the pixels the
        // cell can show, and a ten-item board asks for that ten times over.
        val decodeSizeDp = cell.value.roundToInt().coerceIn(64, 320)
        // Whatever the height budget could not spend on cells is offered to the
        // gaps between rows, so the block spreads over the page instead of
        // stacking under the summary — but only to a point: past that the
        // caption drifts away from the picture it names.
        val rowGap =
            if (rows > 1 && maxHeight != Dp.Infinity) {
                val spare = maxHeight - (caption + cell) * rows - gap * (rows - 1)
                gap + (spare / (rows - 1)).coerceIn(0.dp, 10.dp)
            } else {
                gap
            }
        // Slack the grid cannot spend on bigger cells is split above and
        // below the block rather than left at the foot. Pinned to the top of
        // a half-empty page a grid looks like it ran out of items; centred,
        // the same grid looks placed — which is all an index page has to say.
        val fillsHeight = maxHeight != Dp.Infinity
        Column(
            modifier = if (fillsHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(rowGap, Alignment.CenterVertically),
        ) {
            var rowStart = 0
            rowSizes.forEach { rowSize ->
                val rowItems = items.subList(rowStart, rowStart + rowSize)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // A short last row — the odd cover of a five, the tail of
                    // a disc board — is centred rather than left ragged against
                    // one margin, so the grid reads as a block instead of as a
                    // block with a bite out of its last line.
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                ) {
                    rowItems.forEachIndexed { colIndex, info ->
                        val rank = rowStart + colIndex + 1
                        if (artists) {
                            ArtistGridCell(
                                info = info,
                                rank = rank,
                                palette = palette,
                                decodeSizeDp = decodeSizeDp,
                                modifier = Modifier.width(cell),
                            )
                        } else {
                            PosterCell(
                                info = info,
                                rank = rank,
                                palette = palette,
                                decodeSizeDp = decodeSizeDp,
                                modifier = Modifier.width(cell),
                            )
                        }
                    }
                }
                rowStart += rowSize
            }
        }
    }
}

/**
 * The height an artist cell spends under its disc: the gap, the name and the
 * time. Reserved by the grid when it divides the page between its rows, so a
 * disc is never sized as if the caption were free.
 */
private val ARTIST_CAPTION_BAND = 44.dp

/**
 * One square cover: the artwork full bleed, the rank badged into its corner,
 * the title set over its foot.
 *
 * Square rather than a fixed height, so a cell is the shape of the sleeve it
 * carries and grows with the column it is given, instead of cropping every
 * cover to whatever strip height the item count happened to ask for.
 */
@Composable
private fun PosterCell(
    info: StatsItemInfo,
    rank: Int,
    palette: ShareThemePalette,
    decodeSizeDp: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(palette.cardShape)
                .background(palette.cellBackground),
    ) {
        CachedAsyncImage(
            imageUrl = info.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            targetSizeDp = decodeSizeDp,
            // Capture forces software decoding through LocalInCaptureContext;
            // asking here as well cost the preview a second decode per cover.
            placeholder = {
                Box(
                    modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.textSecondary)
                }
            },
        )
        // Bottom gradient for readability. It starts below the middle so the
        // artwork keeps its top half, and it reaches near-black only at the
        // foot where the type sits — the old ramp began at the centre and
        // turned half of every cover into a black band.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        brush =
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.42f to Color.Transparent,
                                0.68f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            ),
                    ),
        )
        // Rank badge
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(palette.accent, palette.accent.copy(alpha = 0.7f))),
                        palette.badgeShape,
                    ).border(1.dp, Color.Black.copy(alpha = 0.15f), palette.badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = palette.contrastingText(palette.accent),
                fontSize = 10.sp,
            )
        }
        // Title + time. A ten-item board runs three covers across, so the cell
        // is ~90dp and a single line of bodySmall cut nearly every name to six
        // characters ("Daft W…", "Mujhse…"). Two lines at 11sp buy back the
        // rest of the title; the time stays one line under it.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(
                text = info.title,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 1.5f), 5f),
                    ),
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatShareListeningTime(info.timeMs),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 1f), 4f),
                    ),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * One artist: portrait in a circle, rank on the rim, name and time beneath.
 *
 * Not a poster cell with a round clip. A poster cell sets white
 * type over the foot of the artwork behind a black scrim, and a scrim over a
 * circle is a dark band across a face — it also has nowhere to put a caption,
 * because the bottom of a disc is the part of the picture a portrait uses. So
 * the caption sits under the disc on the card's own field, and only the badge
 * overlaps the artwork, where the empty corner of a circle can afford it.
 */
@Composable
private fun ArtistGridCell(
    info: StatsItemInfo,
    rank: Int,
    palette: ShareThemePalette,
    decodeSizeDp: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            CachedAsyncImage(
                imageUrl = info.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .border(1.dp, palette.accent.copy(alpha = 0.30f), CircleShape),
                targetSizeDp = decodeSizeDp,
                placeholder = {
                    Box(
                        modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.textSecondary)
                    }
                },
            )
            // The badge rides the rim rather than the corner: it may sit on a
            // portrait, and on the rim it reads as a seed at position, not a
            // label laid over the face.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .size(22.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(palette.accent, palette.accent.copy(alpha = 0.7f))),
                            palette.badgeShape,
                        ).border(1.dp, Color.Black.copy(alpha = 0.15f), palette.badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = palette.contrastingText(palette.accent),
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = info.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = formatShareListeningTime(info.timeMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            color = palette.textSecondary,
            maxLines = 1,
        )
    }
}

// INDEX layout: the same data in the same order, drawn as a table with hairlines
// and numerals — no badges, pedestals or glass. It lets MINIMUM show the page
// itself instead of objects floating on a gradient.

@Composable
private fun StandardHeader(
    title: String,
    period: String,
    palette: ShareThemePalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(palette.accent, RoundedCornerShape(2.dp)),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                color = palette.textPrimary,
            )
            Text(
                text = period,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = palette.labelWeight ?: FontWeight.Bold,
                letterSpacing = palette.labelTracking ?: 0.sp,
                color = palette.accent,
            )
        }
    }
}

/**
 * The frosted pane a scene sets its data block on.
 *
 * Glass over a print is the honest fix for the quiet end of the type scale: a
 * scrim is a flat wash over the picture, while a pane keeps the picture — the
 * halftone still moves behind the block, at a third of its contrast, and the
 * small labels get a ground that does not drift out from under them. The rim
 * and the drop shadow are what make it read as a pane lying on the page rather
 * than as a white rectangle painted over it.
 *
 * [dark] is that pane for a scene printed black: Glyphs sets white type on a
 * bright glyph field, so the glass has to darken the field instead of washing it
 * out — and dense, because a glyph stroke is a hard edge where a halftone dot is
 * a soft one, and at a light shade of black the strokes printed straight through
 * the labels. The rim drops to a faint edge for the same reason: a white border
 * on a black page reads as a frame, not as a sliver of glass catching the light.
 */
private fun Modifier.frostedPane(dark: Boolean): Modifier =
    this
        // ponytail: a tinted pane, not a true backdrop blur. Sampling the scene
        // behind the pane means a second shader pass per frame, and the export
        // shades the scene itself, so the copy inside the pane would be blank.
        // Swap in a real blur if the frosting ever reads flat at phone size.
        .shadow(
            elevation = 16.dp,
            shape = FrostedPaneShape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.22f),
            spotColor = Color.Black.copy(alpha = 0.16f),
        )
        .clip(FrostedPaneShape)
        .background(
            Brush.verticalGradient(
                0f to paneGlass(dark, if (dark) 0.88f else 0.62f),
                1f to paneGlass(dark, if (dark) 0.82f else 0.50f),
            ),
        )
        .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.6f), FrostedPaneShape)

/** The pane's tint: white glass on a light print, black glass on a dark one. */
private fun paneGlass(
    dark: Boolean,
    alpha: Float,
): Color = if (dark) Color.Black.copy(alpha = alpha) else Color.White.copy(alpha = alpha)

private val FrostedPaneShape = RoundedCornerShape(22.dp)

@Composable
private fun IndexHeader(
    title: String,
    period: String,
    palette: ShareThemePalette,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = palette.headlineWeight ?: FontWeight.Light,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = period,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = palette.labelWeight ?: FontWeight.Normal,
                letterSpacing = palette.labelTracking ?: 1.8.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(9.dp))
        // The masthead rule. Every block on an INDEX page opens with one, so
        // the page reads as ruled stock rather than a stack of loose groups.
        HorizontalDivider(color = palette.divider, thickness = 0.6.dp)
    }
}

@Composable
private fun IndexSummary(
    overview: ListeningOverview,
    palette: ShareThemePalette,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IndexStat(
                label = stringResource(R.string.stats_share_stat_time),
                value = formatShareListeningTime(overview.totalListeningTimeMs),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
            IndexRule(palette)
            IndexStat(
                label = stringResource(R.string.stats_share_stat_plays),
                value = formatShareCount(overview.totalPlayCount),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
            IndexRule(palette)
            IndexStat(
                label = stringResource(R.string.stats_share_stat_unique),
                value = formatShareCount(overview.uniqueTracksCount),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = palette.divider, thickness = 0.6.dp)
    }
}

@Composable
private fun IndexStat(
    label: String,
    value: String,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = palette.headlineWeight ?: FontWeight.Light,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = palette.labelWeight ?: FontWeight.Normal,
            color = palette.textSecondary,
            letterSpacing = palette.labelTracking ?: 1.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IndexRule(palette: ShareThemePalette) {
    Box(modifier = Modifier.width(1.dp).height(24.dp).background(palette.divider))
}

@Composable
private fun IndexList(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
) {
    IndexRows(
        items = items,
        startRank = 1,
        rowHeight = indexRowHeight(count),
        thumbSize = indexThumbSize(count),
        palette = palette,
        modifier = Modifier.fillMaxSize(),
        distribute = true,
    )
}

@Composable
private fun IndexRows(
    items: List<StatsItemInfo>,
    startRank: Int,
    rowHeight: Dp,
    thumbSize: Dp,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
    /** Let every entry take an equal share of [modifier]'s height instead of stacking. */
    distribute: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, info ->
            // No rule between entries: at five rows a hairline under every song
            // reads as a table, at ten it is a comb of them, and the row's own
            // art, numerals and spacing already carry the rhythm. The page keeps
            // its rules where they mean something — under the masthead and under
            // the summary — so it still reads as ruled stock.
            IndexRow(
                rank = startRank + index,
                info = info,
                rowHeight = rowHeight,
                thumbSize = thumbSize,
                palette = palette,
                modifier = if (distribute) Modifier.weight(1f) else Modifier,
            )
        }
    }
}

@Composable
private fun IndexRow(
    rank: Int,
    info: StatsItemInfo,
    rowHeight: Dp,
    thumbSize: Dp,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
) {
    Row(
        // A minimum, not a fixed height: a row that is handed extra height
        // keeps its content vertically centred inside it instead of pinning it
        // to the top and leaving a gap under the rule.
        modifier = modifier.fillMaxWidth().heightIn(min = rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Zero-padded numerals give the column a true edge to align on; a
        // right-aligned 1..10 would wobble by a digit's width. No tracking:
        // the theme's label spacing is for words, and it opened "0 1" into two
        // glyphs at the one size this column can afford.
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = palette.labelWeight ?: FontWeight.Normal,
            color = palette.textSecondary,
            letterSpacing = 0.sp,
            modifier = Modifier.width(16.dp),
        )
        ItemThumbnail(size = thumbSize, imageUrl = info.imageUrl, shape = palette.thumbShape)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = palette.labelWeight ?: FontWeight.Normal,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The meta line takes the title's whole width and puts the time on
            // the sheet's right edge instead of in a column of its own. On a
            // ten-row page that column was wide enough to truncate the titles
            // ("Maharani (feat. Arpit Bala …") while the time itself was set at
            // the quietest size on the card; here the title is the only thing
            // that competes for the width, and the value keeps the margin.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statsItemSubtitle(info),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = formatShareListeningTime(info.timeMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = palette.labelWeight ?: FontWeight.Normal,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun indexRowHeight(count: ShareItemCount): Dp =
    when (count) {
        ShareItemCount.TOP_3 -> 52.dp
        ShareItemCount.TOP_5 -> 46.dp
        ShareItemCount.TOP_10 -> 34.dp
    }

private fun indexThumbSize(count: ShareItemCount): Dp =
    when (count) {
        ShareItemCount.TOP_3 -> 32.dp
        ShareItemCount.TOP_5 -> 32.dp
        ShareItemCount.TOP_10 -> 30.dp
    }

/** Cover size for the runner-up steps, kept inside the step column width. */
private fun indexPodiumAvatarSize(count: ShareItemCount): Dp =
    when (count) {
        ShareItemCount.TOP_3 -> 84.dp
        ShareItemCount.TOP_5 -> 74.dp
        ShareItemCount.TOP_10 -> 60.dp
    }

/** The winner is printed a step larger: size is the rank it can afford. */
private fun indexPodiumWinnerAvatarSize(count: ShareItemCount): Dp =
    when (count) {
        ShareItemCount.TOP_3 -> 100.dp
        ShareItemCount.TOP_5 -> 88.dp
        ShareItemCount.TOP_10 -> 72.dp
    }

@Composable
private fun IndexPodium(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
) {
    val topThree = items.take(3)
    val rest = items.drop(3)
    Column(modifier = Modifier.fillMaxSize()) {
        if (topThree.isNotEmpty()) {
            // The steps are printed at the size the sheet can carry: three
            // postage stamps under a heading left the page empty, and the
            // cover is the only image a ranking card has.
            val avatar = indexPodiumAvatarSize(count)
            val winnerAvatar = indexPodiumWinnerAvatarSize(count)
            val steps =
                remember(topThree, avatar, winnerAvatar) {
                    buildList {
                        if (topThree.size > 1) add(IndexStep(topThree[1], 2, 46.dp, avatar))
                        add(IndexStep(topThree[0], 1, 70.dp, winnerAvatar))
                        if (topThree.size > 2) add(IndexStep(topThree[2], 3, 34.dp, avatar))
                    }
                }
            // The three steps stand on one rule, and the only thing that
            // makes them a podium is how high each numeral floats above it.
            // Drawn as boxes they were three grey rectangles with numbers in
            // them, which on a page this quiet read as a missing image rather
            // than as a ranking. A rule and three numerals is the same
            // information with nothing drawn that is not information.
            // The steps take the height the page has left instead of a fixed
            // band at the top of it: on a three-item card a fixed band left
            // the lower two thirds of the sheet as blank paper. A rule near
            // the foot with the three covers standing above it is the whole
            // composition, and it holds at any page height.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 176.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                steps.forEach { IndexPodiumStep(it, palette) }
            }
            HorizontalDivider(
                color = palette.divider,
                thickness = 0.6.dp,
            )
        }
        if (rest.isNotEmpty()) {
            IndexRows(
                items = rest,
                startRank = 4,
                rowHeight = indexRowHeight(count),
                thumbSize = indexThumbSize(count),
                palette = palette,
            )
        }
    }
}

private data class IndexStep(
    val info: StatsItemInfo,
    val rank: Int,
    val pedestalHeight: Dp,
    val avatarSize: Dp,
)

@Composable
private fun IndexPodiumStep(
    step: IndexStep,
    palette: ShareThemePalette,
) {
    Column(
        modifier = Modifier.width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        ItemThumbnail(size = step.avatarSize, imageUrl = step.info.imageUrl, shape = palette.thumbShape)
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = step.info.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = formatShareListeningTime(step.info.timeMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            color = palette.textSecondary,
        )
        Spacer(modifier = Modifier.height(7.dp))
        // The numeral sits at its rank's height above the shared rule, with
        // nothing drawn under it — the gap stands in for a pedestal. Height
        // alone marks the winner; no extra decoration is needed.
        Box(
            modifier =
                Modifier
                    .width(60.dp)
                    .height(step.pedestalHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = step.rank.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = palette.headlineWeight ?: FontWeight.Light,
                color = if (step.rank == 1) palette.textPrimary else palette.textSecondary,
            )
        }
    }
}

@Composable
private fun IndexGrid(
    items: List<StatsItemInfo>,
    palette: ShareThemePalette,
    count: ShareItemCount,
    tab: StatsTab,
) {
    val columns = if (count == ShareItemCount.TOP_10) 3 else 2
    // The plate is a square, so its width and its height are one number and
    // the grid has to satisfy both. Sized on width alone it overran the sheet
    // — on a five-item page the last cover printed across the frame and the
    // colophon. The page measures this block (it is weighted, see the caller),
    // so the height is known here and the cells can divide it.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gap = 10.dp
        val rows = (items.size + columns - 1) / columns
        val byWidth = (maxWidth - gap * (columns - 1)) / columns
        val byHeight =
            if (maxHeight == Dp.Infinity) {
                byWidth
            } else {
                (maxHeight - INDEX_CAPTION_BAND * rows - gap * (rows - 1)) / rows
            }
        val plate = minOf(byWidth, byHeight).coerceAtLeast(56.dp)
        // The rows are spread to the foot of the page instead of stacking under
        // the summary — but only as far as a printed table would: past that the
        // caption drifts away from the plate it names.
        val rowGap =
            if (rows > 1 && maxHeight != Dp.Infinity) {
                val spare = maxHeight - (INDEX_CAPTION_BAND + plate) * rows - gap * (rows - 1)
                gap + (spare / (rows - 1)).coerceIn(0.dp, 12.dp)
            } else {
                gap
            }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                ) {
                    rowItems.forEachIndexed { colIndex, info ->
                        IndexCell(
                            rank = rowIndex * columns + colIndex + 1,
                            info = info,
                            palette = palette,
                            // An artist is a portrait, so it prints round on
                            // this page too - the same rule as the poster grid,
                            // and it costs nothing here because the caption
                            // already sits under the plate rather than over it.
                            round = tab == StatsTab.TOP_ARTISTS,
                            modifier = Modifier.width(plate),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The height an INDEX cell spends under its plate: the gap, the name and the
 * rank line. Reserved when the grid divides the page between its rows, so a
 * plate is never sized as if its caption were free.
 */
private val INDEX_CAPTION_BAND = 40.dp

@Composable
private fun IndexCell(
    rank: Int,
    info: StatsItemInfo,
    palette: ShareThemePalette,
    round: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val plateShape = if (round) CircleShape else palette.thumbShape
    val captionAlign = if (round) TextAlign.Center else null
    // A disc has no left edge to hang a caption on — it is a circle set in the
    // middle of its column — so on a round plate the two lines are centred
    // under it instead of starting from the column margin.
    Column(
        modifier = modifier,
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // The cover is printed as it is: no scrim, no chip, no overlaid type.
        // Captioning underneath is what makes the scrim unnecessary, and it
        // is also what keeps the artwork legible on a white page.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(plateShape)
                    .background(palette.cellPlaceholder),
        ) {
            CachedAsyncImage(
                imageUrl = info.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                targetSizeDp = 220,
                placeholder = {
                    Box(modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder))
                },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = info.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = captionAlign,
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = rank.toString().padStart(2, '0') + " · " + formatShareListeningTime(info.timeMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            color = palette.textSecondary,
            letterSpacing = palette.labelTracking ?: 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

