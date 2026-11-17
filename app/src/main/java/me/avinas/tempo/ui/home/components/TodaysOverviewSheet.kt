package me.avinas.tempo.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.PeriodComparison
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ArtAtmosphereLayer
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.FrostedIconButton
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SectionCatalogKicker
import me.avinas.tempo.ui.details.conditionedAccent
import me.avinas.tempo.ui.stats.StatsShareDialog
import me.avinas.tempo.ui.stats.StatsTab
import me.avinas.tempo.ui.theme.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* Rank medal accents — shared by podium rows on the overview. */
private val RankGold = Color(0xFFFFD479)
private val RankSilver = Color(0xFFC7CCD4)
private val RankBronze = Color(0xFFCD8B5A)

/**
 * Staggered content entrance: each section fades and lifts in smoothly once
 * the sheet has settled.
 */
@Composable
private fun EntranceReveal(index: Int, content: @Composable () -> Unit) {
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(startDelayMs(index))
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 26f
        }
    ) {
        content()
    }
}

private fun startDelayMs(index: Int): Long = (50L + index * 45L).coerceAtMost(320L)

/**
 * Fullscreen daily listening summary overlay.
 * Displays today's top track, listening time comparison, hourly rhythm chart,
 * and rankings for top tracks and artists.
 */
@Composable
fun TodaysOverviewOverlay(
    overview: ListeningOverview?,
    topTracks: List<TopTrack>,
    topArtists: List<TopArtist>,
    hourlyDistribution: List<HourlyDistribution>,
    periodComparison: PeriodComparison?,
    onDismiss: () -> Unit,
    onViewAllStats: () -> Unit,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    // Accent color extracted from top track album art or top artist image
    val topArtUrl = remember(topTracks, topArtists) {
        topTracks.firstOrNull()?.albumArtUrl?.takeIf { it.isNotBlank() }
            ?: topArtists.firstOrNull()?.imageUrl?.takeIf { it.isNotBlank() }
    }
    val accent = rememberTodayAccentColor(topArtUrl)
    val scrollState = rememberScrollState()

    val dateLabel = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
    }
    val shortDateLabel = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d", Locale.US)).uppercase()
    }

    // Scroll-linked collapsing top bar header.
    // derivedStateOf keeps the per-pixel scroll read out of composition: the
    // boolean only invalidates this scope when it actually flips past 120.
    val showCollapsedTitle by remember {
        derivedStateOf { scrollState.value > 120 }
    }
    val headerScrimAlpha by animateFloatAsState(
        targetValue = if (showCollapsedTitle) 1f else 0f,
        animationSpec = tween(200),
        label = "overviewHeaderScrim"
    )

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(isClosing) {
        if (isClosing) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            )
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (!isClosing) isClosing = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // Backdrop scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value }
                    .background(TempoDarkBackground.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { if (!isClosing) isClosing = true }
            )

            // Sheet content container with smooth slide-up
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - progress.value) * size.height
                        alpha = progress.value
                    }
                    .background(TempoDarkBackground)
            ) {
                // Ambient album art background layer tinted with extracted accent
                ArtAtmosphereLayer(
                    artUrl = topArtUrl,
                    tint = accent
                )

                // Top gradient scrim for scroll readability
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(130.dp)
                        .graphicsLayer { alpha = headerScrimAlpha }
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    TempoDarkBackground.copy(alpha = 0.95f),
                                    TempoDarkBackground.copy(alpha = 0.65f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Bottom gradient scrim
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.20f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, TempoDarkBackground.copy(alpha = 0.88f))
                            )
                        )
                )

                // Main scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                        .padding(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp,
                            bottom = 32.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    var sectionIndex = 0

                    // 1. Daily Dispatch Header
                    EntranceReveal(sectionIndex++) {
                        TodaysEditorialHeader(
                            shortDateLabel = shortDateLabel,
                            accent = accent
                        )
                    }

                    // 2. Today's Anthem / Daily Spotlight Hero
                    val topTrack = topTracks.firstOrNull()
                    if (topTrack != null) {
                        EntranceReveal(sectionIndex++) {
                            TodaysAnthemHeroCard(
                                track = topTrack,
                                accent = accent,
                                onTrackClick = { onNavigateToTrack(topTrack.trackId) }
                            )
                        }
                    }

                    // 3. Master Stats Masthead
                    if (overview != null) {
                        EntranceReveal(sectionIndex++) {
                            HeroListeningCard(
                                overview = overview,
                                periodComparison = periodComparison,
                                accent = accent
                            )
                        }
                    }

                    // 4. Section 01: Listening Rhythm & Hourly Cadence
                    if (hourlyDistribution.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            PeakHoursCard(
                                hourlyDistribution = hourlyDistribution,
                                accent = accent
                            )
                        }
                    }

                    // 5. Section 02: Top Tracks
                    if (topTracks.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            TopTracksSection(
                                tracks = topTracks,
                                accent = accent,
                                onTrackClick = { track -> onNavigateToTrack(track.trackId) }
                            )
                        }
                    }

                    // 6. Section 03: Top Artists
                    if (topArtists.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            TopArtistsSection(
                                artists = topArtists,
                                accent = accent,
                                onArtistClick = { artist ->
                                    val id = artist.artistId
                                    if (id != null && id > 0) {
                                        onNavigateToArtist("id:$id")
                                    } else {
                                        onNavigateToArtist(artist.artist)
                                    }
                                }
                            )
                        }
                    }

                    // 7. Footer Actions & Editorial Signature
                    EntranceReveal(sectionIndex++) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            FooterActions(
                                accent = accent,
                                onClose = { if (!isClosing) isClosing = true },
                                onViewAllStats = { if (!isClosing) onViewAllStats() }
                            )
                            TodaysOverviewFooter()
                        }
                    }
                }

                // Fixed Frosted Top Bar Navigation with Collapsing Title
                TodaysTopBarNavigation(
                    showCollapsedTitle = showCollapsedTitle,
                    dateLabel = dateLabel,
                    accent = accent,
                    onClose = { if (!isClosing) isClosing = true },
                    onShare = { showShareDialog = true }
                )
            }
        }

        // Stats Share Dialog for Today's Overview
        if (showShareDialog) {
            StatsShareDialog(
                tab = if (topTracks.isNotEmpty()) StatsTab.TOP_SONGS else StatsTab.TOP_ARTISTS,
                timeRange = TimeRange.TODAY,
                items = if (topTracks.isNotEmpty()) topTracks else topArtists,
                overview = overview,
                onDismiss = { showShareDialog = false }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Top Bar Navigation with Collapsing Title & Frosted Controls
// ──────────────────────────────────────────────────────────────

@Composable
private fun TodaysTopBarNavigation(
    showCollapsedTitle: Boolean,
    dateLabel: String,
    accent: Color,
    onClose: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FrostedIconButton(
            icon = Icons.Default.Close,
            contentDescription = "Close today's overview",
            onClick = onClose,
            iconTint = TextPrimary
        )

        AnimatedVisibility(
            visible = showCollapsedTitle,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -10 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -10 },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TODAY · $dateLabel".uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.2.sp,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Daily Listening",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        FrostedIconButton(
            icon = Icons.Default.Share,
            contentDescription = "Share today's listening",
            onClick = onShare,
            iconTint = TextPrimary
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 1. Editorial Header (In-Scroll Hero Dispatch)
// ──────────────────────────────────────────────────────────────

@Composable
private fun TodaysEditorialHeader(
    shortDateLabel: String,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = "TODAY · $shortDateLabel",
                style = KickerSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = accent
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Daily Listening",
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.5).sp,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(0.8.dp)
                .background(GlassBorderMedium)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 2. Today's Anthem / Daily Spotlight Hero Stage
// ──────────────────────────────────────────────────────────────

@Composable
private fun TodaysAnthemHeroCard(
    track: TopTrack,
    accent: Color,
    onTrackClick: () -> Unit
) {
    val glowBrush = remember(accent) {
        Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.20f),
                Color.Transparent
            )
        )
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .premiumClickable(onClick = onTrackClick),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = accent,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Artwork with radial glow halo & multi-layer shadow
            Box(
                modifier = Modifier.size(86.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = glowBrush, shape = RoundedCornerShape(20.dp))
                )

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(16.dp),
                            ambientColor = GlassShadowTeal,
                            spotColor = accent.copy(alpha = 0.25f)
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(TempoDarkSurfaceSunken)
                        .border(1.dp, GlassBorderStrong, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CachedAsyncImage(
                        imageUrl = track.albumArtUrl,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetSizeDp = 150,
                        placeholder = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(accent.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = accent.copy(alpha = 0.7f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    )
                }
            }

            // Info column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Text(
                        text = "TODAY'S ANTHEM",
                        style = KickerSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = accent
                    )
                }

                Text(
                    text = track.title,
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.5.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(0.8.dp)
                        .background(GlassBorderMedium)
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${track.playCount} ${if (track.playCount == 1) "play" else "plays"} today · ${formatTrackDuration(track.totalTimeMs)}",
                    style = KickerSmall,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = TextTertiary,
                    maxLines = 1
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 3. Master Stats Masthead
// ──────────────────────────────────────────────────────────────

@Composable
private fun HeroListeningCard(
    overview: ListeningOverview,
    periodComparison: PeriodComparison?,
    accent: Color
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.Obsidian,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Hero block: accent dot kicker over display-size numeral
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Text(
                            text = "TOTAL LISTENING TIME",
                            style = KickerSmall,
                            color = TextTertiary
                        )
                    }

                    DayOverDayChip(periodComparison = periodComparison, accent = accent)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = formatHeroTime(overview.totalListeningTimeMs),
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    letterSpacing = (-1).sp,
                    color = TextPrimary,
                    maxLines = 1,
                    softWrap = false
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${overview.totalPlayCount} ${if (overview.totalPlayCount == 1) "play" else "plays"} recorded across today",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Hairline divider separating hero from secondary metrics
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(GlassBorderSoft)
            )

            // Secondary metrics — stat columns split by vertical hairlines
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MastheadSecondaryStat(
                    label = "TRACKS",
                    value = "${overview.uniqueTracksCount}",
                    subtext = "${overview.uniqueAlbumsCount} ${if (overview.uniqueAlbumsCount == 1) "album" else "albums"}",
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft)
                )

                MastheadSecondaryStat(
                    label = "ARTISTS",
                    value = "${overview.uniqueArtistsCount}",
                    subtext = "unique",
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft)
                )

                MastheadSecondaryStat(
                    label = "AVG SESSION",
                    value = formatShortDuration(overview.averageSessionDurationMs),
                    subtext = "per listen",
                    modifier = Modifier.weight(1f),
                    valueColor = accent
                )
            }

            if (periodComparison != null) {
                // Hairline divider separating metrics from the day-over-day bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.8.dp)
                        .background(GlassBorderSoft)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    TodayVsYesterdayBar(
                        periodComparison = periodComparison,
                        accent = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun MastheadSecondaryStat(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Column(modifier = modifier.padding(horizontal = 10.dp)) {
        Text(
            text = label,
            style = KickerSmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = buildAnnotatedString {
                append(value)
                append("  ")
                withStyle(
                    SpanStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextTertiary
                    )
                ) {
                    append(subtext)
                }
            },
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFontFamily),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Comparison bar showing today's listening time relative to yesterday. */
@Composable
private fun TodayVsYesterdayBar(
    periodComparison: PeriodComparison?,
    accent: Color
) {
    if (periodComparison == null) return
    val currentMs = periodComparison.currentPeriodTimeMs.coerceAtLeast(0L)
    val previousMs = periodComparison.previousPeriodTimeMs.coerceAtLeast(0L)
    val totalMs = currentMs + previousMs
    if (totalMs <= 0L) return

    val todayFraction = (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0.04f, 0.96f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(todayFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.75f), accent)
                        )
                    )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = "TODAY · ${formatShortDuration(currentMs)}",
                    style = KickerSmall,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    color = accent
                )
            }

            Text(
                text = "YESTERDAY · ${formatShortDuration(previousMs)}",
                style = KickerSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                color = TextQuaternary
            )
        }
    }
}

@Composable
private fun DayOverDayChip(
    periodComparison: PeriodComparison?,
    accent: Color
) {
    val change = periodComparison?.timeChangePercent ?: return
    when {
        change > 0.5 -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingUp,
            text = "+${change.roundToInt()}% vs yesterday",
            color = accent
        )
        change < -0.5 -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingDown,
            text = "-${abs(change.roundToInt())}% vs yesterday",
            color = TextSecondary
        )
        else -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingFlat,
            text = "Same as yesterday",
            color = TextSecondary
        )
    }
}

@Composable
private fun TrendChip(iconVector: ImageVector, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(100.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 4. Section 01: Listening Rhythm & Hourly Cadence
// ──────────────────────────────────────────────────────────────

@Composable
private fun PeakHoursCard(
    hourlyDistribution: List<HourlyDistribution>,
    accent: Color
) {
    val peak = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.playCount > 0 }.maxByOrNull { it.playCount }
    }
    val isDay = peak?.let { it.hour in 6..17 } ?: true

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.Obsidian,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                SectionCatalogKicker(
                    number = "01",
                    label = "LISTENING RHYTHM"
                )

                if (peak != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .border(0.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "${formatHourLabel(peak.hour)} · ${peak.playCount} ${if (peak.playCount == 1) "play" else "plays"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent
                        )
                    }
                }
            }

            TodaysHoursChart(
                hourlyDistribution = hourlyDistribution,
                accent = accent,
                peakHour = peak?.hour
            )

            // Day Quadrant Breakdown (Night, Morning, Afternoon, Evening)
            DayQuadrantRow(
                hourlyDistribution = hourlyDistribution,
                accent = accent
            )
        }
    }
}

/** 24-hour play volume distribution chart with current hour highlight & peak elevation. */
@Composable
private fun TodaysHoursChart(
    hourlyDistribution: List<HourlyDistribution>,
    accent: Color,
    peakHour: Int?
) {
    val currentHour = remember { LocalTime.now().hour }
    val maxPlays = remember(hourlyDistribution) {
        hourlyDistribution.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            (0..23).forEach { hour ->
                val playCount = hourlyDistribution.find { it.hour == hour }?.playCount ?: 0
                val heightPercent = (playCount.toFloat() / maxPlays).coerceIn(0.06f, 1f)
                val isCurrentHour = hour == currentHour
                val isPeak = peakHour != null && hour == peakHour && playCount > 0

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (playCount > 0) Modifier.fillMaxHeight(heightPercent)
                            else Modifier.height(2.5.dp)
                        )
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            when {
                                isPeak -> accent
                                isCurrentHour && playCount > 0 -> TempoAccentBright
                                isCurrentHour -> accent.copy(alpha = 0.45f)
                                playCount > 0 -> {
                                    val alpha = 0.45f + (0.50f * (playCount.toFloat() / maxPlays))
                                    accent.copy(alpha = alpha)
                                }
                                else -> Color.White.copy(alpha = 0.07f)
                            }
                        )
                )
            }
        }

        // 6-hour axis ticks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = KickerSmall,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextQuaternary,
                    letterSpacing = 0.4.sp,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Compact 4-cell row showing listening volume distributed across the 4 day quadrants. */
@Composable
private fun DayQuadrantRow(
    hourlyDistribution: List<HourlyDistribution>,
    accent: Color
) {
    val nightPlays = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.hour in 0..5 }.sumOf { it.playCount }
    }
    val morningPlays = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.hour in 6..11 }.sumOf { it.playCount }
    }
    val afternoonPlays = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.hour in 12..17 }.sumOf { it.playCount }
    }
    val eveningPlays = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.hour in 18..23 }.sumOf { it.playCount }
    }

    val quadrants = listOf(
        QuadrantData("NIGHT", "12A-6A", nightPlays),
        QuadrantData("MORNING", "6A-12P", morningPlays),
        QuadrantData("AFTERNOON", "12P-6P", afternoonPlays),
        QuadrantData("EVENING", "6P-12A", eveningPlays)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        quadrants.forEach { q ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = q.label,
                    style = KickerSmall,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextQuaternary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${q.plays}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = DisplayFontFamily),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (q.plays > 0) TextPrimary else TextQuaternary
                )
            }
        }
    }
}

private data class QuadrantData(val label: String, val window: String, val plays: Int)

// ──────────────────────────────────────────────────────────────
// 5. Section 02: Top Tracks (TopSongsPanel Layout)
// ──────────────────────────────────────────────────────────────

@Composable
private fun TopTracksSection(
    tracks: List<TopTrack>,
    accent: Color,
    onTrackClick: (TopTrack) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = accent,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                SectionCatalogKicker(
                    number = "02",
                    label = "TOP TRACKS"
                )

                Text(
                    text = "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }

            tracks.forEachIndexed { index, track ->
                TrackRankRow(
                    rank = index + 1,
                    track = track,
                    accent = accent,
                    onClick = { onTrackClick(track) }
                )
                if (index < tracks.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 74.dp)
                            .fillMaxWidth()
                            .height(0.6.dp)
                            .background(GlassBorderSoft)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TrackRankRow(
    rank: Int,
    track: TopTrack,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .premiumClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Rank podium medal or clean numeral
        RankMedalOrNumeral(rank = rank, accent = accent)

        // Rounded artwork
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.10f))
        ) {
            CachedAsyncImage(
                imageUrl = track.albumArtUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetSizeDp = 100,
                placeholder = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(accent.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = accent.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }

        // Title and artist
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = CaptionSmall,
                fontSize = 11.5.sp,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right-aligned play count & duration column
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "${track.playCount}",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DisplayFontFamily),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (rank == 1) accent else TextPrimary
            )
            Text(
                text = formatTrackDuration(track.totalTimeMs),
                style = CaptionSmall,
                fontSize = 10.sp,
                color = TextTertiary
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = TextQuaternary,
            modifier = Modifier.size(11.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 6. Section 03: Top Artists (Distinct Circular Avatar Styling)
// ──────────────────────────────────────────────────────────────

@Composable
private fun TopArtistsSection(
    artists: List<TopArtist>,
    accent: Color,
    onArtistClick: (TopArtist) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = accent,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                SectionCatalogKicker(
                    number = "03",
                    label = "TOP ARTISTS"
                )

                Text(
                    text = "${artists.size} ${if (artists.size == 1) "artist" else "artists"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }

            artists.forEachIndexed { index, artist ->
                ArtistRankRow(
                    rank = index + 1,
                    artist = artist,
                    accent = accent,
                    onClick = { onArtistClick(artist) }
                )
                if (index < artists.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 74.dp)
                            .fillMaxWidth()
                            .height(0.6.dp)
                            .background(GlassBorderSoft)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ArtistRankRow(
    rank: Int,
    artist: TopArtist,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .premiumClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Rank medal or quiet numeral
        RankMedalOrNumeral(rank = rank, accent = accent)

        // Circular artist portrait
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(TempoDarkSurfaceSunken)
                .border(1.dp, GlassBorderSoft, CircleShape)
        ) {
            CachedAsyncImage(
                imageUrl = artist.imageUrl,
                contentDescription = artist.artist,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetSizeDp = 100,
                placeholder = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = artist.artist.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFontFamily),
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            )
        }

        // Artist name and unique tracks count
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = artist.artist,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${artist.uniqueTracks} ${if (artist.uniqueTracks == 1) "track" else "tracks"} today",
                style = CaptionSmall,
                fontSize = 11.5.sp,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right-aligned listening duration & play count
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = formatLongListeningTime(artist.totalTimeMs),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DisplayFontFamily),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (rank == 1) accent else TextPrimary
            )
            Text(
                text = "${artist.playCount} PLAYS",
                style = CaptionSmall,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                color = TextTertiary
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = TextQuaternary,
            modifier = Modifier.size(11.dp)
        )
    }
}

/** Medal badge for ranks 1 to 3, or a plain rank number for 4 and above. */
@Composable
private fun RankMedalOrNumeral(rank: Int, accent: Color) {
    if (rank <= 3) {
        val medalColor = when (rank) {
            1 -> RankGold
            2 -> RankSilver
            else -> RankBronze
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(medalColor.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, medalColor.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$rank",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = medalColor
            )
        }
    } else {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = DisplayFontFamily),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextQuaternary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 7. Footer Actions & Editorial Signature
// ──────────────────────────────────────────────────────────────

@Composable
private fun FooterActions(
    accent: Color,
    onClose: () -> Unit,
    onViewAllStats: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1.6f)
                .height(50.dp)
                .shadow(10.dp, RoundedCornerShape(25.dp), ambientColor = Color.Black.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(25.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(accent, TempoPrimary)
                    )
                )
                .premiumClickable(onClick = onViewAllStats)
        ) {
            Text(
                text = "Full stats hub",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextOnAccent
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextOnAccent.copy(alpha = 0.85f),
                modifier = Modifier.size(11.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(GlassFrostSoft)
                .border(0.8.dp, GlassBorderSoft, RoundedCornerShape(25.dp))
                .premiumClickable(onClick = onClose)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Close",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun TodaysOverviewFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(0.8.dp)
                .background(GlassBorderSoft)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "TEMPO · TODAY'S OVERVIEW",
            style = KickerSmall,
            color = TextTertiary,
            letterSpacing = 2.sp
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Accent Extraction via Coil & Android Palette
// ──────────────────────────────────────────────────────────────

@Composable
private fun rememberTodayAccentColor(imageUrl: String?): Color {
    var accent by remember { mutableStateOf(TempoPrimary) }
    val context = LocalContext.current

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl)
                .size(64, 64)
                .build()
        )
        var bitmap = (result.image as? BitmapImage)?.bitmap ?: return@LaunchedEffect
        if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        }
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val color = palette.vibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
        accent = color?.let { conditionedAccent(Color(it)) } ?: TempoPrimary
    }
    return accent
}

// ──────────────────────────────────────────────────────────────
// Formatters
// ──────────────────────────────────────────────────────────────

/** Hero numeral form: "2h 41m", "41m", "38s". */
private fun formatHeroTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        ms <= 0L -> "0m"
        hours == 0L && mins == 0L -> "${ms / 1000}s"
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

/** Compact form for tiles and per-artist totals ("38m", "2.4h"). */
private fun formatShortDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0L -> "0m"
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        else -> String.format(Locale.US, "%.1fh", minutes / 60.0)
    }
}

/** Hours-and-minutes form ("2h 41m", "38m") for per-artist time totals. */
private fun formatLongListeningTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        ms <= 0L -> "0m"
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0L -> "${seconds}s"
        else -> String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatHourLabel(hour: Int): String {
    val h = when (val mod = hour % 12) {
        0 -> 12
        else -> mod
    }
    return "$h ${if (hour >= 12) "PM" else "AM"}"
}
