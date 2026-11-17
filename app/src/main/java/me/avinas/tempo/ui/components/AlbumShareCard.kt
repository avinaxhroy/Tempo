package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.ui.components.share.ShareArtworkCardPadding
import me.avinas.tempo.ui.components.share.ShareCardBrandingInset
import me.avinas.tempo.ui.components.share.ShareCardSurface
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareStatBand
import me.avinas.tempo.ui.components.share.ShareStatCellData
import me.avinas.tempo.ui.components.share.ShareTrackList
import me.avinas.tempo.ui.components.share.ShareStatusPill
import me.avinas.tempo.ui.components.share.ShareTrackRow
import me.avinas.tempo.ui.components.share.formatShareCount
import me.avinas.tempo.ui.components.share.formatShareDuration

/**
 * 9:16 Optimized Share Card for Albums
 *
 * The album card exists because the album screen had no share of its own: the
 * same record could be shared from a track but never as the record. It prints
 * what an album actually is — a cover, a year, a track list and how much of it
 * this listener finished — through the same surface, band and list as the artist
 * and song cards, so the three read as one family.
 */
@Composable
fun AlbumShareCard(
    albumDetails: AlbumDetails,
    // How the card is printed; see [ArtistShareCard].
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
    /** Whether to print the summary band; see [ArtistShareCard]. */
    showSummary: Boolean = true,
) {
    val shareLocale = LocalConfiguration.current.locales[0]
    ShareCardSurface(
        card = card,
        art = albumDetails.album.artworkUrl.asShareArt(),
        modifier = modifier,
        brandingBottomPadding = ShareCardBrandingInset,
    ) { palette ->
        FitToHeight(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(ShareArtworkCardPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Standing kicker: null unless this record did something worth
                // stating. An album with nothing special to its name opens with
                // its cover, not with a slogan.
                val standingKicker =
                    remember(albumDetails.totalPlayCount, albumDetails.completionRate) {
                        resolveAlbumStandingKicker(
                            playCount = albumDetails.totalPlayCount,
                            completionRate = albumDetails.completionRate,
                        )
                    }?.resolve()

                if (standingKicker != null) {
                    ShareStatusPill(text = standingKicker, palette = palette)
                }

                // Hero Album Cover with Glow
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier =
                            Modifier
                                .size(190.dp)
                                .background(
                                    brush =
                                        Brush.radialGradient(
                                            colors =
                                                listOf(
                                                    palette.heroGlow.copy(alpha = 0.85f),
                                                    Color.Transparent,
                                                ),
                                        ),
                                    shape = CircleShape,
                                ),
                    )

                    GlassCard(
                        modifier = Modifier.size(192.dp),
                        shape = palette.cardShape,
                    ) {
                        CachedAsyncImage(
                            imageUrl = albumDetails.album.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            allowHardware = false,
                            placeholder = {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.textSecondary)
                                }
                            },
                        )
                    }
                }

                // Title / Artist / Release line
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = albumDetails.album.title,
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                fontSize = 22.sp,
                                lineHeight = 26.sp,
                            ),
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    Text(
                        text = albumDetails.artistName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (palette.isDark) Color(0xFFE9D5FF) else palette.accent,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                    )

                    val releaseLabel: String? =
                        remember(albumDetails.album.releaseYear, albumDetails.album.releaseType) {
                            val year = albumDetails.album.releaseYear
                            val type = albumDetails.album.releaseType?.takeIf { it.isNotBlank() }
                            when {
                                year != null && type != null ->
                                    // The song card prints "album • year" with the same
                                    // separator; one format string, so the two cards
                                    // cannot drift apart on punctuation.
                                    ShareValue.Resource(
                                        ShareText(R.string.share_album_year_format, listOf(type, year)),
                                    )
                                year != null ->
                                    ShareValue.Resource(
                                        ShareText(R.string.share_released_year, listOf(year)),
                                    )
                                type != null -> ShareValue.Formatted(type)
                                else -> null
                            }
                        }?.resolve()
                    if (releaseLabel != null) {
                        Text(
                            text = releaseLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                // The summary band, same three columns and the same meaning as
                // the artist card: how much, how long, how many.
                if (showSummary) {
                    ShareStatBand(
                        palette = palette,
                        cells =
                            listOf(
                                ShareStatCellData(
                                    icon = Icons.Default.GraphicEq,
                                    label = stringResource(R.string.share_plays_label),
                                    value = formatShareCount(albumDetails.totalPlayCount),
                                ),
                                ShareStatCellData(
                                    icon = Icons.Default.AccessTime,
                                    label = stringResource(R.string.share_time).uppercase(shareLocale),
                                    value = formatShareDuration(albumDetails.totalTimeMs / 60_000),
                                ),
                                ShareStatCellData(
                                    icon = Icons.Default.MusicNote,
                                    label = stringResource(R.string.share_tracks_label),
                                    value = albumDetails.tracks.size.toString(),
                                ),
                            ),
                    )
                }

                // Completion is the one number that is about this album
                // specifically: how much of it this listener actually sat through.
                val completionHighlight: String? =
                    remember(albumDetails.completionRate, albumDetails.totalPlayCount) {
                        resolveAlbumFlexHighlight(
                            completionRate = albumDetails.completionRate,
                            playCount = albumDetails.totalPlayCount,
                        )
                    }?.resolve()
                if (completionHighlight != null) {
                    GlassCard(
                        shape = RoundedCornerShape(50),
                        backgroundColor = palette.surfaceStrong,
                        borderColor = palette.divider,
                        borderWidth = 1.dp,
                        fillMaxWidth = false,
                    ) {
                        Text(
                            text = completionHighlight,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                // The track list, ranked by plays: an album card without its
                // tracks is a cover with a date on it.
                val rankedTracks =
                    remember(albumDetails.tracks) {
                        albumDetails.tracks
                            .sortedByDescending { it.playCount }
                            .map { ShareTrackRow(title = it.track.title, plays = it.playCount) }
                    }
                ShareTrackList(
                    palette = palette,
                    header = stringResource(R.string.share_top_tracks),
                    rows = rankedTracks,
                )
            }
        }
    }
}

/**
 * What, if anything, is worth stating about this album above its cover.
 *
 * Null for the ordinary case, on purpose: the artist and song cards were told to
 * stop printing a generic kicker, and this card starts out without that habit.
 */
internal fun resolveAlbumStandingKicker(playCount: Int, completionRate: Double): ShareText? =
    when {
        completionRate >= 95.0 && playCount >= 10 -> ShareText(R.string.share_kicker_album_complete)
        playCount >= 100 -> ShareText(R.string.share_kicker_album_century)
        playCount >= 40 -> ShareText(R.string.share_kicker_album_rotation)
        else -> null
    }

/**
 * The album's flex line: how much of the record was actually finished.
 *
 * Below half the album is not a flex worth printing — "you skipped most of this"
 * belongs on the stats screen, not on the image the listener is about to post.
 */
internal fun resolveAlbumFlexHighlight(completionRate: Double, playCount: Int): ShareText? =
    when {
        playCount < 5 -> null
        completionRate >= 60.0 ->
            ShareText(R.string.share_highlight_album_completion, listOf(completionRate.toInt()))
        else -> null
    }
