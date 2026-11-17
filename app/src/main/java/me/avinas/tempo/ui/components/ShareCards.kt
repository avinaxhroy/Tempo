package me.avinas.tempo.ui.components

import android.text.format.DateFormat as AndroidDateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
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
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.ui.components.share.ShareArtworkCardPadding
import me.avinas.tempo.ui.components.share.ShareCardBrandingInset
import me.avinas.tempo.ui.components.share.ShareCardSurface
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareIdentityPill
import me.avinas.tempo.ui.components.share.ShareTrackList
import me.avinas.tempo.ui.components.share.ShareTrackRow
import me.avinas.tempo.ui.components.share.formatShareCount
import me.avinas.tempo.ui.components.share.formatShareDuration
import me.avinas.tempo.ui.components.share.SHARE_META_SEPARATOR
import me.avinas.tempo.ui.components.share.ShareProceduralCardPadding
import me.avinas.tempo.ui.components.share.ShareStatBand
import me.avinas.tempo.ui.components.share.ShareStatCellData
import me.avinas.tempo.ui.components.share.ShareStatDivider
import me.avinas.tempo.ui.components.share.ShareStatusPill
import me.avinas.tempo.ui.profile.BadgeEmblem
import me.avinas.tempo.ui.profile.getRarityColor
import me.avinas.tempo.ui.profile.getUniqueBadgeColor
import me.avinas.tempo.ui.theme.DisplayFontFamily
import me.avinas.tempo.ui.theme.LevelTier
import me.avinas.tempo.ui.theme.getLevelTierAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 9:16 Optimized Share Card for Artists
 */
@Composable
fun ArtistShareCard(
    artistDetails: ArtistDetails,
    percentile: Double? = null,
    // How the card is printed: the theme on screen, the scene's clock and the
    // artwork its colours are drawn from — one value, because a card cannot use
    // any of the three without the others.
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
    /**
     * Whether to print the card's stat copy: the summary band's totals and the
     * pills that claim a standing (the kicker at the top, the flex line under the
     * band).
     *
     * A card with it on says "this is how much of my listening this artist is";
     * with it off the card is the portrait, the fan badge and the songs — the
     * version you send to someone who does not care about the totals. Every
     * number the card argues with is in this one group, so there is no
     * half-summary to disagree about later.
     */
    showSummary: Boolean = true,
) {
    val shareLocale = LocalConfiguration.current.locales[0]
    val monthYearPattern =
        remember(shareLocale) { shareDatePattern(shareLocale, "MMMyyyy", SHARE_PATTERN_MONTH_YEAR) }
    val yearPattern =
        remember(shareLocale) { shareDatePattern(shareLocale, "yyyy", SHARE_PATTERN_YEAR) }
    ShareCardSurface(
        card = card,
        art = artistDetails.artist.imageUrl.asShareArt(),
        modifier = modifier,
        // The single-subject cards leave a little more room under their last row
        // than the ranking card: their footer is a subject line, not a table.
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
                // Standing / Fan Status Kicker
                val standingKicker =
                    remember(percentile, artistDetails.personalPlayCount) {
                        resolveArtistStandingKicker(percentile, artistDetails.personalPlayCount)
                    }?.resolve()

                // Hidden with the summary off, and when the artist has no standing
                // worth stating: the card already leads with the face and the name.
                if (showSummary && standingKicker != null) {
                    ShareStatusPill(text = standingKicker, palette = palette)
                }

                // Artist Image with Glow
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier =
                            Modifier
                                .size(144.dp)
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

                    Box(
                        modifier =
                            Modifier
                                .size(136.dp)
                                .clip(CircleShape)
                                .border(3.dp, palette.divider, CircleShape),
                    ) {
                        CachedAsyncImage(
                            imageUrl = artistDetails.artist.imageUrl,
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

                // Artist Name
                Text(
                    text = artistDetails.artist.name,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontWeight = palette.headlineWeight ?: FontWeight.Black,
                            fontSize = 24.sp,
                            lineHeight = 28.sp,
                        ),
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                // Badges Row (Country + Top 1% Fan)
                if (artistDetails.country != null || artistDetails.personalPlayCount > 50) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (artistDetails.country != null) {
                            GlassCard(
                                shape = RoundedCornerShape(50),
                                backgroundColor = palette.surface,
                                fillMaxWidth = false,
                            ) {
                                Text(
                                    text = stringResource(R.string.share_country_format, artistDetails.country),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        if (artistDetails.country != null && artistDetails.personalPlayCount > 50) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        if (artistDetails.personalPlayCount > 50) {
                            val (badgeText, badgeEmoji, badgeColor) =
                                if (percentile != null) {
                                    when {
                                        percentile == 0.0 ->
                                            Triple(
                                                stringResource(R.string.share_fan_status_number_one),
                                                "👑",
                                                Color(0xFFFFD700),
                                            )
                                        percentile <= 1.0 -> Triple(stringResource(R.string.fan_status_top_1), "👑", Color(0xFFFFD700))
                                        percentile <= 5.0 -> Triple(stringResource(R.string.fan_status_top_5), "🌟", Color(0xFFF59E0B))
                                        percentile <= 10.0 -> Triple(stringResource(R.string.fan_status_top_10), "🔥", Color(0xFFEF4444))
                                        percentile <= 25.0 -> Triple(stringResource(R.string.fan_status_top_25), "🎧", Color(0xFF3B82F6))
                                        percentile <= 50.0 -> Triple(stringResource(R.string.fan_status_top_50), "🎵", Color(0xFF8B5CF6))
                                        else -> Triple(stringResource(R.string.fan_status_listener), "🎵", Color(0xFF94A3B8))
                                    }
                                } else {
                                    when {
                                        artistDetails.personalPlayCount > 1000 -> {
                                            Triple(
                                                stringResource(R.string.fan_status_ultimate),
                                                "👑",
                                                Color(0xFFFFD700),
                                            )
                                        }

                                        artistDetails.personalPlayCount > 500 -> {
                                            Triple(
                                                stringResource(R.string.fan_status_super),
                                                "🌟",
                                                Color(0xFFF59E0B),
                                            )
                                        }

                                        artistDetails.personalPlayCount > 200 -> {
                                            Triple(
                                                stringResource(R.string.fan_status_big),
                                                "🔥",
                                                Color(0xFFEF4444),
                                            )
                                        }

                                        else -> {
                                            Triple(stringResource(R.string.fan_status_regular), "🎧", Color(0xFF3B82F6))
                                        }
                                    }
                                }
                            GlassCard(
                                shape = RoundedCornerShape(50),
                                backgroundColor = badgeColor.copy(alpha = 0.15f),
                                borderColor = badgeColor.copy(alpha = 0.4f),
                                borderWidth = 1.dp,
                                fillMaxWidth = false,
                            ) {
                                Text(
                                    text = "$badgeEmoji $badgeText",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (palette.isDark) badgeColor else palette.textStrong,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // Optional Artist Flex Highlight Pill (streak, discovery date, or genre).
                // A hidden streak is passed through as 0 so the pill falls back to the
                // discovery date the card already knows rather than dropping the pill.
                val hideStreaks = LocalHideStreaks.current
                val artistFlexPill =
                    remember(
                        artistDetails.listeningStreakDays,
                        artistDetails.firstListenedDate,
                        artistDetails.firstDiscovery,
                        artistDetails.topGenres,
                        hideStreaks,
                    ) {
                        resolveArtistFlexPill(
                            listeningStreakDays = if (hideStreaks) 0 else artistDetails.listeningStreakDays,
                            firstListenedDate = artistDetails.firstListenedDate,
                            firstDiscoveryTimestamp = artistDetails.firstDiscovery?.firstListenTimestamp,
                            topGenre = artistDetails.topGenres.firstOrNull(),
                            dateLocale = shareLocale,
                            monthYearPattern = monthYearPattern,
                            yearPattern = yearPattern,
                        )
                    }?.resolve()
                // The streak / discovery line is a number dressed as a sentence, so
                // it leaves with the band rather than staying on a "summary off" card.
                if (showSummary && artistFlexPill != null) {
                    GlassCard(
                        shape = RoundedCornerShape(50),
                        backgroundColor = palette.surfaceStrong,
                        borderColor = palette.divider,
                        borderWidth = 1.dp,
                        fillMaxWidth = false,
                    ) {
                        Text(
                            text = artistFlexPill,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                val formattedTime = formatShareDuration(artistDetails.personalTotalTimeMinutes)

                // The summary band: one group, so "off" means nothing here at all
                // rather than a partial band. The card keeps the portrait, the
                // fan badge and the top songs.
                if (showSummary) {
                    ShareStatBand(
                        palette = palette,
                        cells =
                            listOf(
                                ShareStatCellData(
                                    icon = Icons.Default.GraphicEq,
                                    label = stringResource(R.string.share_plays_label),
                                    value = formatShareCount(artistDetails.personalPlayCount),
                                ),
                                ShareStatCellData(
                                    icon = Icons.Default.AccessTime,
                                    label = stringResource(R.string.share_time).uppercase(shareLocale),
                                    value = formattedTime,
                                ),
                                ShareStatCellData(
                                    icon = Icons.Default.MusicNote,
                                    label = stringResource(R.string.share_songs_label),
                                    value = formatShareCount(artistDetails.uniqueTracksPlayed),
                                ),
                            ),
                    )
                }

                // Top Songs Section
                if (artistDetails.topSongs.isNotEmpty()) {
                    ShareTrackList(
                        palette = palette,
                        header = stringResource(R.string.share_top_songs),
                        rows = artistDetails.topSongs.map { song ->
                            ShareTrackRow(title = song.title, plays = song.playCount)
                        },
                    )
                }

                // No equalizer strip here: the same nine bars on every card said
                // nothing about this track, and the card already carries its own logo.
            }
        }
    }
}

/**
 * 9:16 Optimized Share Card for Songs
 */
@Composable
fun SongShareCard(
    trackDetails: TrackDetails,
    // How the card is printed; see [ArtistShareCard].
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
    genre: String? = null,
    mood: String? = null,
    habitualHour: String? = null,
    peakBingeDay: Pair<String, Int>? = null,
    completionRate: Float? = null,
    skipRate: Float? = null,
    releaseYear: Int? = null,
    /** Whether to print the card's stat copy; see [ArtistShareCard]. */
    showSummary: Boolean = true,
) {
    val shareLocale = LocalConfiguration.current.locales[0]
    // A skeleton, not an English pattern: the platform decides whether the month comes
    // before the year and how the two are joined in this language.
    val shortMonthYearPattern =
        remember(shareLocale) {
            shareDatePattern(shareLocale, "MMMyy", SHARE_PATTERN_SHORT_MONTH_YEAR)
        }
    ShareCardSurface(
        card = card,
        art = trackDetails.track.albumArtUrl.asShareArt(),
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
                // Standing / Flex Status Kicker
                val standingKicker =
                    remember(trackDetails.peakRank, trackDetails.playCount, completionRate, skipRate) {
                        resolveSongStandingKicker(
                            peakRank = trackDetails.peakRank,
                            playCount = trackDetails.playCount,
                            completionRate = completionRate,
                            skipRate = skipRate,
                        )
                    }?.resolve()

                // A track with no standing, or a card with its summary off, gets no
                // pill; the cover is the opener.
                if (showSummary && standingKicker != null) {
                    ShareStatusPill(text = standingKicker, palette = palette)
                }

                // Hero Album Art with Glow & Favorite Badge. Carries its own top gap
                // because the cover is the subject, not the card's first line: with
                // the summary off it is the opener, and it should not sit on the frame.
                Box(
                    modifier = Modifier.padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                            imageUrl = trackDetails.track.albumArtUrl,
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

                    if (trackDetails.isFavorite) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = (-6).dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE11D48))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // Track Info (Title, Artist, Album/Year)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = trackDetails.track.title,
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
                        text = trackDetails.track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (palette.isDark) Color(0xFFE9D5FF) else palette.accent,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                    )

                    val albumYearText: ShareValue? =
                        remember(trackDetails.track.album, releaseYear) {
                            val album = trackDetails.track.album?.takeIf { it.isNotBlank() }
                            when {
                                album != null && releaseYear != null ->
                                    ShareValue.Resource(
                                        ShareText(R.string.share_album_year_format, listOf(album, releaseYear)),
                                    )
                                album != null -> ShareValue.Formatted(album)
                                releaseYear != null ->
                                    ShareValue.Resource(ShareText(R.string.share_released_year, listOf(releaseYear)))
                                else -> null
                            }
                        }
                    val albumYearLabel = albumYearText?.resolve()
                    if (albumYearLabel != null) {
                        Text(
                            text = albumYearLabel,
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

                // Col 3 flex stat resolved before card
                val flexStat =
                    remember(
                        trackDetails.firstPlayed,
                        trackDetails.peakRank,
                        completionRate,
                        trackDetails.isFavorite,
                        shareLocale,
                        shortMonthYearPattern,
                    ) {
                        resolveSongFlexStat(
                            firstPlayed = trackDetails.firstPlayed,
                            peakRank = trackDetails.peakRank,
                            completionRate = completionRate,
                            isFavorite = trackDetails.isFavorite,
                            dateLocale = shareLocale,
                            datePattern = shortMonthYearPattern,
                        )
                    }

                val formattedTime = formatShareDuration(trackDetails.totalTimeMinutes)

                // Same band, same meaning as the artist card: the totals, switched
                // off together. See [ShareStatBand].
                if (showSummary) {
                    ShareStatBand(
                        palette = palette,
                        cells =
                            listOf(
                                ShareStatCellData(
                                    icon = Icons.Default.GraphicEq,
                                    label = stringResource(R.string.share_plays_label),
                                    value = formatShareCount(trackDetails.playCount),
                                ),
                                ShareStatCellData(
                                    icon = Icons.Default.AccessTime,
                                    label = stringResource(R.string.share_time).uppercase(shareLocale),
                                    value = formattedTime,
                                ),
                                ShareStatCellData(
                                    icon = flexStat.icon,
                                    label = stringResource(flexStat.label),
                                    value = flexStat.value.resolve(),
                                ),
                            ),
                    )
                }

                // Standout Flex Highlight Pill (Cherry-picked editorial flex). Part
                // of the summary group: signature and record lines are the numbers
                // the toggle promises to remove.
                val flexHighlight =
                    remember(peakBingeDay, habitualHour, skipRate, mood, genre) {
                        resolveSongFlexHighlight(
                            peakBingeDay = peakBingeDay,
                            habitualHour = habitualHour,
                            skipRate = skipRate,
                            playCount = trackDetails.playCount,
                            mood = mood,
                            genre = genre,
                        )
                    }?.resolve()

                if (showSummary && flexHighlight != null) {
                    GlassCard(
                        shape = RoundedCornerShape(50),
                        backgroundColor = palette.surfaceStrong,
                        borderColor = palette.divider,
                        borderWidth = 1.dp,
                        fillMaxWidth = false,
                    ) {
                        Text(
                            text = flexHighlight,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                // Same rule as the artist card: no nine-bar strip. The bottom of
                // this card is where a summary or a second stat belongs.
            }
        }
    }
}

/**
 * A user-visible string that is still a resource reference: its id plus the positional
 * arguments it interpolates. Card copy travels as one of these instead of as English
 * text, so a card rasterized in Russian cannot contain a leftover English word, and a
 * concept shared by two cards can only be spelled once.
 */
internal data class ShareText(
    @StringRes val id: Int,
    val args: List<Any> = emptyList(),
)

/** Resolves [ShareText] against the configuration of the card being drawn. */
@Composable
internal fun ShareText.resolve(): String =
    if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

/** A flex-stat figure: either a resource (with args) or an already-formatted value. */
internal sealed interface ShareValue {
    data class Resource(val text: ShareText) : ShareValue

    data class Formatted(val text: String) : ShareValue
}

@Composable
internal fun ShareValue.resolve(): String =
    when (this) {
        is ShareValue.Resource -> text.resolve()
        is ShareValue.Formatted -> text
    }

/** Fallback patterns, used only when the platform has no locale-specific suggestion. */
private const val SHARE_PATTERN_SHORT_MONTH_YEAR = "MMM ''yy"
private const val SHARE_PATTERN_MONTH_YEAR = "MMM yyyy"
private const val SHARE_PATTERN_YEAR = "yyyy"
private const val SHARE_PATTERN_LONG_MONTH_YEAR = "MMMM yyyy"

/**
 * The locale's own ordering and punctuation for [skeleton] (an ICU skeleton such as
 * "MMMyyyy"). The cards used to print English patterns like "MMM d, yyyy" in every
 * language, which reads as a machine translation next to translated labels.
 */
internal fun shareDatePattern(
    locale: Locale,
    skeleton: String,
    fallbackPattern: String,
): String {
    // A platform that has no ICU suggestion for this skeleton hands back null (so does the
    // JVM stub in unit tests); the caller's own pattern is the fallback in that case.
    val suggested = AndroidDateFormat.getBestDateTimePattern(locale, skeleton)
    return if (suggested.isNullOrBlank()) fallbackPattern else suggested
}

/**
 * Formats [timestamp] with an explicit [pattern] in [locale]. Kept pure — the pattern is
 * always passed in — so unit tests never depend on the framework formatter stub.
 */
internal fun formatShareDate(timestamp: Long, pattern: String, locale: Locale): String =
    SimpleDateFormat(pattern, locale).format(Date(timestamp))

/** The meta separator between two facts in a header pill — see [SHARE_META_SEPARATOR]. */

/**
 * Badge categories are persisted as stable uppercase ids ("MILESTONE", "TIME", …). The
 * in-app label comes from `ProfileScreen.getCategoryLabel`, which is English and also used
 * in list rows, so the card owns its own mapping instead of localizing that shared label.
 * Returns 0 (`Resources.ID_NULL`) for an unknown id; callers print the raw value.
 */
@StringRes
internal fun badgeCategoryLabel(category: String): Int =
    when (category) {
        "MILESTONE" -> R.string.share_category_milestones
        "TIME" -> R.string.share_category_time
        "STREAK" -> R.string.share_category_streaks
        "DISCOVERY" -> R.string.share_category_discovery
        "ENGAGEMENT" -> R.string.share_category_engagement
        "LEVEL" -> R.string.share_category_levels
        else -> 0
    }

/**
 * `GamificationEngine.BadgeRarity.label` is an English enum constant label shared with the
 * in-app badge screens; the card maps the enum to its own resources so the exported image
 * is localized without changing what the rest of the app prints.
 */
@StringRes
internal fun badgeRarityLabel(rarity: GamificationEngine.BadgeRarity): Int =
    when (rarity) {
        GamificationEngine.BadgeRarity.COMMON -> R.string.share_rarity_common
        GamificationEngine.BadgeRarity.RARE -> R.string.share_rarity_rare
        GamificationEngine.BadgeRarity.EPIC -> R.string.share_rarity_epic
        GamificationEngine.BadgeRarity.LEGENDARY -> R.string.share_rarity_legendary
        GamificationEngine.BadgeRarity.MYTHIC -> R.string.share_rarity_mythic
    }

/**
 * Level tier name shown on the milestone card.
 *
 * The thresholds behind it live in [LevelTier], together with the tier's colour,
 * so the milestone card and the level-up pill cannot disagree about which tier a
 * level is in or what it is called.
 */
@StringRes
internal fun milestoneTierLabel(level: Int): Int = LevelTier.of(level).label

internal data class SongFlexStat(
    @StringRes val label: Int,
    val value: ShareValue,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

internal fun resolveSongStandingKicker(
    peakRank: Int?,
    playCount: Int,
    completionRate: Float?,
    skipRate: Float?,
): ShareText? =
    when {
        peakRank == 1 -> ShareText(R.string.share_kicker_all_time_track)
        peakRank != null && peakRank <= 5 -> ShareText(R.string.share_kicker_top_track_library, listOf(peakRank))
        peakRank != null && peakRank <= 20 -> ShareText(R.string.share_kicker_top_track_rotation, listOf(peakRank))
        playCount >= 100 -> ShareText(R.string.share_kicker_century_club_track)
        playCount >= 50 -> ShareText(R.string.share_kicker_heavy_rotation)
        skipRate != null && skipRate < 0.05f && playCount >= 15 -> ShareText(R.string.share_kicker_zero_skips)
        // Nothing special to say, so no kicker. A generic "TEMPO • TRACK HIGHLIGHT"
        // would push what is unique further down the card.
        else -> null
    }

internal fun resolveSongFlexStat(
    firstPlayed: Long?,
    peakRank: Int?,
    completionRate: Float?,
    isFavorite: Boolean,
    dateLocale: Locale,
    datePattern: String,
): SongFlexStat =
    when {
        firstPlayed != null && firstPlayed > 0L -> {
            SongFlexStat(
                R.string.share_since_label,
                ShareValue.Formatted(formatShareDate(firstPlayed, datePattern, dateLocale)),
                Icons.Default.History,
            )
        }

        peakRank != null -> {
            SongFlexStat(
                R.string.share_peak_label,
                ShareValue.Resource(ShareText(R.string.share_peak_rank_format, listOf(peakRank))),
                Icons.Default.EmojiEvents,
            )
        }

        completionRate != null -> {
            SongFlexStat(
                R.string.share_finish_label,
                ShareValue.Resource(
                    ShareText(R.string.share_percent_format, listOf((completionRate * 100).toInt())),
                ),
                Icons.Default.CheckCircle,
            )
        }

        else -> {
            SongFlexStat(
                R.string.share_status_label,
                ShareValue.Resource(
                    ShareText(if (isFavorite) R.string.share_status_favorite else R.string.share_status_spun),
                ),
                Icons.Default.Star,
            )
        }
    }

internal fun resolveSongFlexHighlight(
    peakBingeDay: Pair<String, Int>?,
    habitualHour: String?,
    skipRate: Float?,
    playCount: Int,
    mood: String?,
    genre: String?,
): ShareText? =
    when {
        peakBingeDay != null && peakBingeDay.second >= 4 -> {
            ShareText(R.string.share_highlight_binge, listOf(peakBingeDay.second))
        }

        habitualHour != null -> {
            ShareText(R.string.share_highlight_habit, listOf(habitualHour.lowercase()))
        }

        skipRate != null && skipRate < 0.08f && playCount >= 10 -> {
            ShareText(R.string.share_highlight_loyalty)
        }

        mood != null -> {
            ShareText(R.string.share_highlight_vibe, listOf(mood))
        }

        genre != null -> {
            ShareText(R.string.share_highlight_genre, listOf(genre))
        }

        else -> {
            null
        }
    }

internal fun resolveArtistStandingKicker(
    percentile: Double?,
    playCount: Int,
): ShareText? =
    when {
        percentile != null && percentile == 0.0 && playCount >= 10 -> ShareText(R.string.share_kicker_all_time_artist)
        percentile != null && percentile <= 1.0 && playCount >= 25 -> ShareText(R.string.share_kicker_top_1_percent)
        percentile != null && percentile <= 5.0 && playCount >= 20 -> ShareText(R.string.share_kicker_top_5_percent)
        percentile != null && percentile <= 10.0 && playCount >= 15 -> ShareText(R.string.share_kicker_top_10_percent)
        playCount >= 500 -> ShareText(R.string.share_kicker_century_club_artist)
        playCount >= 200 -> ShareText(R.string.share_kicker_heavy_rotation_artist)
        playCount >= 50 -> ShareText(R.string.share_kicker_rotation_regular)
        // Same rule as the song card: an artist with no standing has no kicker.
        else -> null
    }

internal fun resolveArtistFlexPill(
    listeningStreakDays: Int,
    firstListenedDate: Long?,
    firstDiscoveryTimestamp: Long?,
    topGenre: String? = null,
    dateLocale: Locale,
    monthYearPattern: String,
    yearPattern: String,
): ShareText? =
    when {
        listeningStreakDays >= 3 -> {
            ShareText(R.string.share_artist_streak_days, listOf(listeningStreakDays))
        }

        firstListenedDate != null && firstListenedDate > 0L -> {
            ShareText(
                R.string.share_artist_listening_since,
                listOf(formatShareDate(firstListenedDate, monthYearPattern, dateLocale)),
            )
        }

        firstDiscoveryTimestamp != null && firstDiscoveryTimestamp > 0L -> {
            ShareText(
                R.string.share_artist_discovered_in,
                listOf(formatShareDate(firstDiscoveryTimestamp, yearPattern, dateLocale)),
            )
        }

        !topGenre.isNullOrBlank() -> {
            ShareText(R.string.share_artist_top_genre, listOf(topGenre))
        }

        else -> {
            null
        }
    }

/**
 * 9:16 Optimized Share Card for Badges / Trophy Achievements
 */
@Composable
fun BadgeShareCard(
    badge: Badge,
    userName: String,
    profileImagePath: String? = null,
    // How the card is printed; see [ArtistShareCard]. A badge has no artwork, so
    // a scene seeds its colours from the theme alone.
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
) {
    val shareLocale = LocalConfiguration.current.locales[0]
    // A skeleton, not an English pattern: the platform decides where the month sits
    // relative to the year in this language.
    val earnedMonthPattern =
        remember(shareLocale) {
            shareDatePattern(shareLocale, "MMMMyyyy", SHARE_PATTERN_LONG_MONTH_YEAR)
        }
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val rarityLabel = stringResource(badgeRarityLabel(rarity))
    val categoryLabel =
        (
            badgeCategoryLabel(badge.category).takeIf { it != 0 }?.let { stringResource(it) }
                ?: badge.category
        ).uppercase(shareLocale)
    val xpEarned = GamificationEngine.getBadgeXpContribution(badge.badgeId, badge.stars)
    val earnedDate =
        remember(badge.earnedAt, badge.isEarned, shareLocale, earnedMonthPattern) {
            if (badge.isEarned && badge.earnedAt > 0L) {
                ShareValue.Formatted(formatShareDate(badge.earnedAt, earnedMonthPattern, shareLocale))
            } else {
                ShareValue.Resource(ShareText(R.string.share_recent))
            }
        }

    // The badge plate the app draws *is* the artwork: handed in as a source so
    // every art theme prints it as moonlight, glass, glyphs or dots, instead of
    // each theme having to know whether the picture came from a URL or a canvas.
    val backdropBitmap =
        remember(badge.badgeId) {
            createBadgeBackdropBitmap(badge = badge)
        }

    ShareCardSurface(
        card = card,
        art = ShareArtSource.BitmapSource(bitmap = backdropBitmap, key = "badge:${badge.badgeId}"),
        modifier = modifier,
        brandingBottomPadding = ShareCardBrandingInset,
    ) { palette ->
        FitToHeight(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(ShareProceduralCardPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShareIdentityPill(
                    palette = palette,
                    userName = userName,
                    profileImagePath = profileImagePath,
                    accent = intrinsicColor,
                    label =
                        stringResource(
                            if (badge.isMaxed) {
                                R.string.share_trophy_mastered
                            } else {
                                R.string.share_trophy_unlocked
                            },
                        ),
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Hero Stage: Grand Silhouette Emblem with Layered Blooming Halo
                Box(contentAlignment = Alignment.Center) {
                    // Outer diffuse glow
                    Box(
                        modifier =
                            Modifier
                                .size(176.dp)
                                .background(
                                    brush =
                                        Brush.radialGradient(
                                            listOf(
                                                intrinsicColor.copy(alpha = if (palette.isDark) 0.42f else 0.20f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    shape = CircleShape,
                                ),
                    )

                    // Concentric accent ring
                    Box(
                        modifier =
                            Modifier
                                .size(152.dp)
                                .border(
                                    width = 1.dp,
                                    color = rarityColor.copy(alpha = if (palette.isDark) 0.35f else 0.18f),
                                    shape = CircleShape,
                                ),
                    )

                    BadgeEmblem(
                        badge = badge,
                        intrinsicColor = intrinsicColor,
                        modifier = Modifier.size(136.dp),
                    )
                }

                // Category Kicker, Title & Description
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.share_badge_discipline, categoryLabel),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                            ),
                        color = if (palette.isDark) intrinsicColor else palette.textStrong,
                    )
                    Text(
                        text = badge.name,
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = DisplayFontFamily,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                fontSize = 26.sp,
                                letterSpacing = (-0.5).sp,
                            ),
                        color = palette.textPrimary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 30.sp,
                        maxLines = 2,
                    )
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = palette.textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp,
                        maxLines = 2,
                    )
                }

                // Prestige Rarity Badge & 5-Star Constellation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.surfaceStrong)
                            .border(1.dp, rarityColor.copy(alpha = if (palette.isDark) 0.40f else 0.20f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(rarityColor.copy(alpha = if (palette.isDark) 0.22f else 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = rarityLabel.uppercase(shareLocale),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Black,
                            color = if (palette.isDark) rarityColor else palette.textStrong,
                            letterSpacing = 1.sp,
                        )
                    }
                    Text(
                        text = SHARE_META_SEPARATOR,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 1..5) {
                            val active = i <= badge.stars
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint =
                                    if (active) {
                                        (if (badge.isMaxed) intrinsicColor else Color(0xFFFBBF24))
                                    } else {
                                        palette.textSecondary.copy(alpha = 0.22f)
                                    },
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                // Micro Progress Bar if in-progress
                if (!badge.isMaxed && badge.maxProgress > 1) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.share_tier_progress),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.Bold,
                                color = palette.textSecondary,
                                letterSpacing = 1.2.sp,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.share_progress_format,
                                        badge.progress,
                                        badge.maxProgress,
                                    ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(palette.divider),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(badge.progressFraction)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            brush =
                                                Brush.horizontalGradient(
                                                    listOf(intrinsicColor, rarityColor),
                                                ),
                                        ),
                            )
                        }
                    }
                }

                // Structured Telemetry Dossier Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape,
                    borderColor = palette.divider,
                    borderWidth = 1.dp,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.share_rank_tier),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text =
                                    if (badge.isMaxed) {
                                        stringResource(R.string.share_tier_master)
                                    } else {
                                        stringResource(R.string.share_tier_number, badge.stars)
                                    },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (badge.isMaxed && palette.isDark) intrinsicColor else palette.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text =
                                    stringResource(
                                        R.string.share_stars_of_five,
                                        if (badge.isMaxed) 5 else badge.stars,
                                    ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                            )
                        }

                        ShareStatDivider(palette = palette, height = 32.dp)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.share_xp_value),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text =
                                    stringResource(
                                        R.string.share_xp_amount,
                                        if (xpEarned > 0) {
                                            xpEarned
                                        } else {
                                            rarity.xpPerStar * badge.stars.coerceAtLeast(1)
                                        },
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (palette.isDark) Color(0xFFFBBF24) else palette.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = stringResource(R.string.share_rarity_tier, rarityLabel),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                            )
                        }

                        ShareStatDivider(palette = palette, height = 32.dp)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.share_achieved),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = earnedDate.resolve(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = stringResource(R.string.share_archive_record),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 9:16 Optimized Share Card for Level Up Milestones
 */
@Composable
fun MilestoneShareCard(
    level: Int,
    title: String,
    userName: String,
    profileImagePath: String? = null,
    totalXp: Long = 0,
    streak: Int = 0,
    // How the card is printed; see [ArtistShareCard].
    card: ShareCardTheme = ShareCardTheme.Default,
    modifier: Modifier = Modifier,
) {
    val tierAccent = getLevelTierAccent(level)
    val displayTitle =
        title.ifBlank { stringResource(R.string.share_milestone_default_title) }

    // Same contract as the badge card: the drawn plate is the artwork, so every
    // art theme treats it like a cover.
    val backdropBitmap =
        remember(level) {
            createMilestoneBackdropBitmap(level = level)
        }

    ShareCardSurface(
        card = card,
        art = ShareArtSource.BitmapSource(bitmap = backdropBitmap, key = "milestone:$level"),
        modifier = modifier,
        brandingBottomPadding = ShareCardBrandingInset,
    ) { palette ->
        FitToHeight(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(ShareProceduralCardPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ShareIdentityPill(
                    palette = palette,
                    userName = userName,
                    profileImagePath = profileImagePath,
                    accent = tierAccent,
                    label = stringResource(R.string.share_level_promotion),
                    labelLetterSpacing = 1.2.sp,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Hero Level Crest
                Box(contentAlignment = Alignment.Center) {
                    // Outer radial aura
                    Box(
                        modifier =
                            Modifier
                                .size(180.dp)
                                .background(
                                    brush =
                                        Brush.radialGradient(
                                            listOf(
                                                tierAccent.copy(alpha = if (palette.isDark) 0.38f else 0.20f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    shape = CircleShape,
                                ),
                    )

                    // Concentric Ring
                    Box(
                        modifier =
                            Modifier
                                .size(136.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceStrong)
                                .border(3.dp, tierAccent.copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_level_label),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = tierAccent,
                                letterSpacing = 2.sp,
                            )
                            Text(
                                text = "$level",
                                style =
                                    MaterialTheme.typography.displayLarge.copy(
                                        fontFamily = DisplayFontFamily,
                                        fontSize = 52.sp,
                                        letterSpacing = (-1.5).sp,
                                    ),
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Listener Title & Description
                Text(
                    text = displayTitle,
                    style =
                        MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = palette.headlineWeight ?: FontWeight.Black,
                        ),
                    color = palette.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 32.sp,
                )

                Text(
                    text = stringResource(R.string.share_milestone_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                // Stats Grid
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_total_xp),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.5.sp,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(Locale.getDefault(), "%,d", totalXp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = palette.textPrimary,
                            )
                        }

                        if (!LocalHideStreaks.current) {
                            ShareStatDivider(palette = palette, height = 30.dp)

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.share_streak_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text =
                                        if (streak > 0) {
                                            stringResource(R.string.share_streak_days, streak)
                                        } else {
                                            stringResource(R.string.share_streak_active)
                                        },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                    color = palette.textPrimary,
                                )
                            }
                        }

                        ShareStatDivider(palette = palette, height = 30.dp)

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_rank_tier),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.5.sp,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(milestoneTierLabel(level)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (palette.isDark) tierAccent else palette.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
