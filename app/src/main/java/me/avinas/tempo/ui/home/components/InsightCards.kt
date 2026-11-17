package me.avinas.tempo.ui.home.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.AudioTraitKey
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.domain.insights.MusicalPersonality
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.details.formatListeningTime
import me.avinas.tempo.ui.theme.CaptionSmall
import me.avinas.tempo.ui.theme.DisplayFontFamily
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.InsightBinge
import me.avinas.tempo.ui.theme.InsightDiscovery
import me.avinas.tempo.ui.theme.InsightEngagement
import me.avinas.tempo.ui.theme.InsightMood
import me.avinas.tempo.ui.theme.InsightPeakTime
import me.avinas.tempo.ui.theme.InsightStreak
import me.avinas.tempo.ui.theme.TempoPop
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.premiumClickable
import me.avinas.tempo.ui.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders one insight.
 *
 * Each payload owns exactly one composition, chosen so the shape of the card carries the shape of
 * the information: a personality is a tinted statement, a mood is a point in a field, a favourite
 * artist is artwork, a repeat count is a number, a streak is a run of days, listening hours are a
 * series, a measured profile is a table. Colour is taken from the subject being measured, never
 * from a palette rotation, and nothing is drawn that is not a value on screen.
 */
@Composable
fun InsightCard(
    insight: InsightCardData,
    modifier: Modifier = Modifier,
    onNavigateToArtist: ((String) -> Unit)? = null,
) {
    val cardModifier = modifier.fillMaxWidth()
    val accent = accentOf(insight.payload)
    when (val payload = insight.payload) {
        is InsightPayload.PersonalityData -> PersonalityStatement(payload, cardModifier)
        is InsightPayload.MoodData -> MoodField(insight, payload, accent, cardModifier)
        is InsightPayload.TopArtistData -> ArtistArtwork(payload, cardModifier, onNavigateToArtist)
        is InsightPayload.BingeData -> BingeCount(payload, accent, cardModifier, onNavigateToArtist)
        is InsightPayload.EngagementData -> ListeningSplit(payload, accent, cardModifier)
        is InsightPayload.StreakData -> StreakTimeline(payload, accent, cardModifier)
        is InsightPayload.PeakTimeData -> PeakHours(payload, accent, cardModifier)
        is InsightPayload.DiscoveryData -> DiscoveryLedger(insight, payload, accent, cardModifier)
        is InsightPayload.AudioProfileData -> AudioProfileTable(payload, accent, cardModifier)
        is InsightPayload.GamificationProgress -> Unit
        InsightPayload.TextOnly -> TextOnlyInsight(insight, cardModifier)
    }
}

/** The colour the card is about: its own subject, used for its accents and its dot in the web. */
internal fun accentOf(payload: InsightPayload): Color = when (payload) {
    is InsightPayload.PersonalityData -> TempoPop
    is InsightPayload.MoodData -> InsightMood
    is InsightPayload.BingeData -> InsightBinge
    is InsightPayload.EngagementData -> InsightEngagement
    is InsightPayload.StreakData -> InsightStreak
    is InsightPayload.PeakTimeData -> InsightPeakTime
    is InsightPayload.DiscoveryData -> InsightDiscovery
    is InsightPayload.AudioProfileData -> TempoPrimary
    is InsightPayload.TopArtistData -> InsightBinge
    else -> TextSecondary
}

/** What the card is about, short enough to sit on a star in the web. */
@Composable
internal fun labelOf(insight: InsightCardData): String = when (val payload = insight.payload) {
    is InsightPayload.PersonalityData -> insight.title
    is InsightPayload.MoodData -> stringResource(R.string.insight_node_mood)
    is InsightPayload.TopArtistData -> payload.artist
    is InsightPayload.BingeData -> "${payload.playCount}x"
    is InsightPayload.EngagementData -> "${payload.completionRate.roundToInt()}%"
    is InsightPayload.StreakData -> "${payload.days}d"
    is InsightPayload.PeakTimeData -> hourLabelOf(payload.peakHour)
    is InsightPayload.DiscoveryData -> stringResource(
        R.string.insight_node_new,
        payload.trends.firstOrNull()?.new_artists_count ?: 0,
    )
    is InsightPayload.AudioProfileData -> stringResource(R.string.insight_node_profile)
    else -> insight.title
}

/**
 * A bordered card in the insight's own colour. The border is a single flat accent rule: the glass
 * gradient is kept for the fill, so the card reads as one surface instead of two stacked ones.
 */
@Composable
private fun AccentSurface(
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.Obsidian,
        borderColor = accent.copy(alpha = 0.22f),
        borderWidth = 1.dp,
        contentPadding = contentPadding,
    ) {
        Column(content = content)
    }
}

/** A card whose whole plane is tinted by its subject — used once, for the loudest insight. */
@Composable
private fun TintedSurface(
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.TintedSolid,
        accentColor = accent,
        contentPadding = contentPadding,
    ) {
        Column(content = content)
    }
}

@Composable
private fun CardHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = DisplayFontFamily,
            fontSize = 22.sp,
            lineHeight = 27.sp,
        ),
        color = TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// 1. Personality — the type is the card, on a plane tinted by the finding.

@Composable
private fun PersonalityStatement(
    data: InsightPayload.PersonalityData,
    modifier: Modifier,
) {
    TintedSurface(accent = TempoPop, modifier = modifier) {
        Text(
            text = stringResource(personalityNameRes(data.personality)),
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = DisplayFontFamily,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.6).sp,
            ),
            color = TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(personalityDescriptionRes(data.personality)),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        val genre = data.genre
        val share = data.genreSharePercent
        if (genre != null && share != null && share > 0) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.insight_personality_anchor, genre, share),
                style = CaptionSmall,
                color = TempoPop,
            )
        }
    }
}

// 2. Mood — one point in a two-axis field, labelled where its axes end.

@Composable
private fun MoodField(
    insight: InsightCardData,
    data: InsightPayload.MoodData,
    accent: Color,
    modifier: Modifier,
) {
    val valence = data.valence.coerceIn(0f, 1f)
    val energy = data.energy.coerceIn(0f, 1f)

    // The one authored motion moment in the feed: the point settles into place on first frame.
    val reducedMotion = rememberReducedMotion()
    var settled by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(Unit) { settled = true }
    val plottedX by animateFloatAsState(
        targetValue = if (settled) valence else 0.5f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "mood_valence",
    )
    val plottedY by animateFloatAsState(
        targetValue = if (settled) energy else 0.5f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "mood_energy",
    )

    AccentSurface(accent = accent, modifier = modifier) {
        CardHeading(insight.title)
        if (insight.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.7f),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val axis = 1.dp.toPx()
                val line = accent.copy(alpha = 0.16f)
                drawLine(
                    color = line,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height),
                    strokeWidth = axis,
                )
                drawLine(
                    color = line,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = axis,
                )
                val point = Offset(size.width * plottedX, size.height * (1f - plottedY))
                // Halo: the point sits on a black field, and a bare 6dp dot is a speck.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                        center = point,
                        radius = 26.dp.toPx(),
                    ),
                    center = point,
                    radius = 26.dp.toPx(),
                )
                drawCircle(color = accent, radius = 6.dp.toPx(), center = point)
            }
            AxisLabel(
                text = stringResource(R.string.insight_mood_axis_energy, (energy * 100).roundToInt()),
                modifier = Modifier.align(Alignment.TopStart),
            )
            AxisLabel(
                text = stringResource(
                    R.string.insight_mood_axis_valence,
                    (valence * 100).roundToInt(),
                ),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = CaptionSmall,
        color = TextTertiary,
    )
}

// 3. Top artist — artwork is the card.

@Composable
private fun ArtistArtwork(
    data: InsightPayload.TopArtistData,
    modifier: Modifier,
    onNavigateToArtist: ((String) -> Unit)?,
) {
    val clickable = if (onNavigateToArtist != null) {
        Modifier.premiumClickable(onClick = { onNavigateToArtist(data.artist) })
    } else {
        Modifier
    }
    GlassCard(
        modifier = modifier
            .height(200.dp)
            .then(clickable),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CachedAsyncImage(
                imageUrl = data.imageUrl,
                contentDescription = data.artist,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetSizeDp = 360,
            )
            // Legibility scrim: the name sits on photography of unknown brightness.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.35f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = data.artist,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = DisplayFontFamily,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.insight_artist_plays,
                        data.playCount,
                        formatListeningTime(data.totalTimeMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// 4. Repeat count — the number is set inside the sentence.

@Composable
private fun BingeCount(
    data: InsightPayload.BingeData,
    accent: Color,
    modifier: Modifier,
    onNavigateToArtist: ((String) -> Unit)?,
) {
    val clickable = if (onNavigateToArtist != null) {
        Modifier.premiumClickable(onClick = { onNavigateToArtist(data.artist) })
    } else {
        Modifier
    }
    Column(modifier = modifier.then(clickable)) {
        Text(
            text = data.artist,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                text = data.playCount.toString(),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 64.sp,
                    lineHeight = 66.sp,
                    letterSpacing = (-1.5).sp,
                ),
                color = TextPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.insight_binge_phrase),
                modifier = Modifier
                    .alignByBaseline()
                    .padding(bottom = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.insight_binge_sitting,
                formatListeningTime(data.durationMs),
            ),
            style = CaptionSmall,
            color = TextTertiary,
        )
    }
}

// 5. Listening split — two counts compared, not one number inflated.

@Composable
private fun ListeningSplit(
    data: InsightPayload.EngagementData,
    accent: Color,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            SplitValue(data.fullListens.toString(), stringResource(R.string.insight_full_listens))
            SplitValue(data.skips.toString(), stringResource(R.string.insight_skips))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.insight_completion_share, data.completionRate.roundToInt()),
            style = CaptionSmall,
            color = accent,
        )
    }
}

@Composable
private fun SplitValue(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                lineHeight = 38.sp,
            ),
            color = TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = CaptionSmall, color = TextTertiary)
    }
}

// 6. Streak — the days themselves.

@Composable
private fun StreakTimeline(
    data: InsightPayload.StreakData,
    accent: Color,
    modifier: Modifier,
) {
    AccentSurface(accent = accent, modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.days.toString(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    lineHeight = 46.sp,
                ),
                color = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.insight_streak_unit),
                modifier = Modifier
                    .alignByBaseline()
                    .padding(bottom = 5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        if (data.recentDays.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                data.recentDays.forEach { played ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (played) accent else GlassBorderSoft),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (data.recentDays.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.insight_streak_window),
                    style = CaptionSmall,
                    color = TextTertiary,
                )
            }
            if (data.longestDays > 0) {
                Text(
                    text = stringResource(R.string.insight_streak_longest, data.longestDays),
                    style = CaptionSmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

// 7. Peak hours — the listening day as a series, in one hue with the peak at full strength.

@Composable
private fun PeakHours(
    data: InsightPayload.PeakTimeData,
    accent: Color,
    modifier: Modifier,
) {
    val counts = remember(data.hourlyDistribution) {
        val byHour = IntArray(24)
        data.hourlyDistribution.forEach { entry ->
            if (entry.hour in 0..23) byHour[entry.hour] = entry.playCount
        }
        byHour
    }
    val peakCount = counts.maxOrNull() ?: 0
    val peakLabel = remember(data.peakHour) { hourLabelOf(data.peakHour) }

    AccentSurface(accent = accent, modifier = modifier) {
        CardHeading(stringResource(R.string.insight_peak_heading, peakLabel))
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            counts.forEachIndexed { hour, count ->
                val fraction = if (peakCount > 0) count.toFloat() / peakCount else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (hour == data.peakHour) accent else accent.copy(alpha = 0.22f),
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM").forEach { label ->
                Text(text = label, style = CaptionSmall, color = TextTertiary)
            }
        }
    }
}

// 8. Discovery — new artists per month, read as figures rather than charted.

@Composable
private fun DiscoveryLedger(
    insight: InsightCardData,
    data: InsightPayload.DiscoveryData,
    accent: Color,
    modifier: Modifier,
) {
    val months = remember(data.trends) { data.trends.take(6).reversed() }

    Column(modifier = modifier) {
        CardHeading(insight.title)
        if (insight.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            months.forEachIndexed { index, trend ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trend.new_artists_count.toString(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (index == months.lastIndex) accent else TextSecondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = monthLabelOf(trend.month),
                        style = CaptionSmall,
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}

// 9. Sound profile — measured values as a bar table.

@Composable
private fun AudioProfileTable(
    data: InsightPayload.AudioProfileData,
    accent: Color,
    modifier: Modifier,
) {
    AccentSurface(accent = accent, modifier = modifier) {
        CardHeading(stringResource(R.string.insight_sound_profile))
        Spacer(Modifier.height(16.dp))
        data.traits.forEachIndexed { index, trait ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            MeasurementRow(
                label = stringResource(traitLabelRes(trait.key)),
                value = "${(trait.value.coerceIn(0f, 1f) * 100).roundToInt()}%",
                fraction = trait.value.coerceIn(0f, 1f),
                accent = accent,
            )
        }
        val bpm = data.tempoBpm
        if (bpm != null) {
            Spacer(Modifier.height(14.dp))
            MeasurementRow(
                label = stringResource(R.string.insight_trait_tempo),
                value = "${bpm.roundToInt()} BPM",
                fraction = null,
                accent = accent,
            )
        }
        if (data.estimated) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.insight_profile_estimated),
                style = CaptionSmall,
                color = TextTertiary,
            )
        }
    }
}

/**
 * A row with the value stated and, when the value is a proportion, filled to it. Tempo has no
 * proportion to show, so it gets no track rather than an invented one.
 */
@Composable
private fun MeasurementRow(
    label: String,
    value: String,
    fraction: Float?,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
    }
    if (fraction != null) {
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GlassBorderSoft),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}

// Fallback for a payload with no measurements behind it: state the copy, draw nothing.

@Composable
private fun TextOnlyInsight(insight: InsightCardData, modifier: Modifier) {
    Column(modifier = modifier) {
        CardHeading(insight.title)
        if (insight.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

internal fun hourLabelOf(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

private fun monthLabelOf(month: String): String = runCatching {
    java.time.YearMonth.parse(month)
        .atDay(1)
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.getDefault()))
}.getOrDefault(month)

@StringRes
private fun traitLabelRes(key: AudioTraitKey): Int = when (key) {
    AudioTraitKey.ENERGY -> R.string.insight_trait_energy
    AudioTraitKey.DANCEABILITY -> R.string.insight_trait_danceability
    AudioTraitKey.ACOUSTICNESS -> R.string.insight_trait_acousticness
}

@StringRes
private fun personalityNameRes(personality: MusicalPersonality): Int = when (personality) {
    MusicalPersonality.HIP_HOP_HEAD -> R.string.personality_hip_hop_head_name
    MusicalPersonality.POP_ICON -> R.string.personality_pop_icon_name
    MusicalPersonality.ROCK_STAR -> R.string.personality_rock_star_name
    MusicalPersonality.METALHEAD -> R.string.personality_metalhead_name
    MusicalPersonality.RNB_SOUL -> R.string.personality_rb_soul_name
    MusicalPersonality.JAZZ_CAT -> R.string.personality_jazz_cat_name
    MusicalPersonality.ELECTRONIC_VOYAGER -> R.string.personality_electronic_voyager_name
    MusicalPersonality.MAESTRO -> R.string.personality_maestro_name
    MusicalPersonality.INDIE_SPIRIT -> R.string.personality_indie_spirit_name
    MusicalPersonality.PARTY_STARTER -> R.string.personality_party_starter_name
    MusicalPersonality.INTENSE_SOUL -> R.string.personality_intense_soul_name
    MusicalPersonality.PEACEFUL_OPTIMIST -> R.string.personality_peaceful_optimist_name
    MusicalPersonality.DEEP_THINKER -> R.string.personality_deep_thinker_name
    MusicalPersonality.DANCE_FLOOR_REGULAR -> R.string.personality_dance_floor_regular_name
    MusicalPersonality.BALANCED_ENTHUSIAST -> R.string.personality_balanced_enthusiast_name
    MusicalPersonality.THE_EXPLORER -> R.string.personality_the_explorer_name
    MusicalPersonality.THE_MELOPHILE -> R.string.personality_the_melophile_name
}

@StringRes
private fun personalityDescriptionRes(personality: MusicalPersonality): Int = when (personality) {
    MusicalPersonality.HIP_HOP_HEAD -> R.string.pers_desc_hip_hop
    MusicalPersonality.POP_ICON -> R.string.pers_desc_pop
    MusicalPersonality.ROCK_STAR -> R.string.pers_desc_rock
    MusicalPersonality.METALHEAD -> R.string.pers_desc_metal
    MusicalPersonality.RNB_SOUL -> R.string.pers_desc_rb
    MusicalPersonality.JAZZ_CAT -> R.string.pers_desc_jazz
    MusicalPersonality.ELECTRONIC_VOYAGER -> R.string.pers_desc_electronic
    MusicalPersonality.MAESTRO -> R.string.pers_desc_maestro
    MusicalPersonality.INDIE_SPIRIT -> R.string.pers_desc_indie
    MusicalPersonality.PARTY_STARTER -> R.string.pers_desc_party
    MusicalPersonality.INTENSE_SOUL -> R.string.pers_desc_intense
    MusicalPersonality.PEACEFUL_OPTIMIST -> R.string.pers_desc_peaceful
    MusicalPersonality.DEEP_THINKER -> R.string.pers_desc_deep_thinker
    MusicalPersonality.DANCE_FLOOR_REGULAR -> R.string.pers_desc_dance
    MusicalPersonality.BALANCED_ENTHUSIAST -> R.string.pers_desc_balanced
    MusicalPersonality.THE_EXPLORER -> R.string.pers_desc_explorer
    MusicalPersonality.THE_MELOPHILE -> R.string.pers_desc_melophile
}
