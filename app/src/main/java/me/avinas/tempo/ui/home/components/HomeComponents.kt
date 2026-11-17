package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.NeoSpotlightCta
import me.avinas.tempo.ui.components.NeoSpotlightSurface
import me.avinas.tempo.ui.components.TelemetryMiniCell
import me.avinas.tempo.ui.components.TrendLine
import me.avinas.tempo.ui.theme.*
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.premiumClickable
import androidx.compose.foundation.border
import java.util.Locale

@Composable
fun HeroCard(
    userName: String,
    listeningTime: String,
    periodLabel: String,
    timeChangePercent: Double,
    trendData: List<Float>,
    selectedRange: TimeRange,
    dailyLabels: List<String> = emptyList(),
    dailyAvgMinutes: Long = 0L,
    activeDays: Int = 0,
    totalDays: Int = 0,
    peakDayMinutes: Long = 0L,
    modifier: Modifier = Modifier
) {
    var scrubbingTime by remember { mutableStateOf<String?>(null) }
    var scrubbingLabel by remember { mutableStateOf<String?>(null) }

    // Reset scrubbing state when time range changes
    LaunchedEffect(selectedRange) {
        scrubbingTime = null
        scrubbingLabel = null
    }

    val greeting = remember(userName) {
        me.avinas.tempo.utils.TempoCopyEngine.getHeroGreeting(userName)
    }

    val dailyAvgFormatted = remember(dailyAvgMinutes) {
        val h = dailyAvgMinutes / 60
        val m = dailyAvgMinutes % 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    val peakFormatted = remember(peakDayMinutes) {
        val h = peakDayMinutes / 60
        val m = peakDayMinutes % 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.Obsidian,
        shape = RoundedCornerShape(24.dp),
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Masthead kicker row with accent dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(TempoPrimary)
                )
                Text(
                    text = periodLabel.uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    color = TextTertiary,
                    letterSpacing = 1.0.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Large Hero Metric with DisplayFontFamily
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = scrubbingTime ?: listeningTime,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp
                    ),
                    color = TextPrimary
                )

                if (scrubbingLabel != null) {
                    Text(
                        text = "• $scrubbingLabel",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Greeting & Delta pill context
            val isPositive = timeChangePercent >= 0
            val percentString = "${if (isPositive) "+" else ""}${timeChangePercent.toInt()}%"
            val comparisonColor = if (isPositive) TempoSuccessBright else TempoWarningBright
            val arrowIcon = if (isPositive) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown
            val comparisonText = when (selectedRange) {
                TimeRange.TODAY -> "vs yesterday"
                TimeRange.THIS_WEEK -> "vs last week"
                TimeRange.THIS_MONTH -> "vs last month"
                TimeRange.THIS_YEAR -> "vs last year"
                TimeRange.ALL_TIME -> ""
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(comparisonColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = arrowIcon,
                            contentDescription = null,
                            tint = comparisonColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = percentString,
                            style = MaterialTheme.typography.labelSmall,
                            color = comparisonColor,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                if (comparisonText.isNotBlank()) {
                    Text(
                        text = comparisonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Hairline separator
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(GlassBorderSoft)
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Secondary Telemetry Metrics Row
            if (trendData.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryMiniCell(
                        label = "DAILY AVG",
                        value = dailyAvgFormatted,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(0.8.dp)
                            .background(GlassBorderSoft)
                    )

                    TelemetryMiniCell(
                        label = "ACTIVE DAYS",
                        value = "$activeDays / $totalDays",
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(0.8.dp)
                            .background(GlassBorderSoft)
                    )

                    TelemetryMiniCell(
                        label = "PEAK DAY",
                        value = peakFormatted,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Chart Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                if (dailyLabels.isNotEmpty() && trendData.size == dailyLabels.size) {
                    InteractiveTrendLine(
                        dataPoints = trendData,
                        labels = dailyLabels,
                        modifier = Modifier.fillMaxSize(),
                        lineColor = me.avinas.tempo.ui.theme.TempoSecondary,
                        fillColor = me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f),
                        strokeWidth = 3.dp,
                        formatValue = { value ->
                            val minutes = value.toLong()
                            val hours = minutes / 60
                            val mins = minutes % 60
                            if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        },
                        onValueSelected = { value, label ->
                            val minutes = value.toLong()
                            val hours = minutes / 60
                            val mins = minutes % 60
                            scrubbingTime = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                            scrubbingLabel = label
                        },
                        onSelectionCleared = {
                            scrubbingTime = null
                            scrubbingLabel = null
                        }
                    )
                } else {
                    TrendLine(
                        dataPoints = trendData,
                        modifier = Modifier.fillMaxSize(),
                        lineColor = me.avinas.tempo.ui.theme.TempoSecondary,
                        fillColor = me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}


private val SpotlightStoryGradient = listOf(
    TempoPrimary,       // Electric Teal
    Color(0xFF06B6D4),  // Bright Cyan
    Color(0xFF8B5CF6),  // Vivid Violet
    Color(0xFFEC4899),  // Neon Rose
    Color(0xFFF59E0B),  // Warm Amber
    TempoPrimary        // Loop back to teal
)

@Composable
private fun SpotlightRing(
    albumArtUrl: String?,
    viewed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (viewed) {
        ViewedSpotlightRing(
            albumArtUrl = albumArtUrl,
            onClick = onClick,
            modifier = modifier
        )
    } else {
        ActiveSpotlightRing(
            albumArtUrl = albumArtUrl,
            onClick = onClick,
            modifier = modifier
        )
    }
}

@Composable
private fun ActiveSpotlightRing(
    albumArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val storyAccessibilityLabel = stringResource(R.string.spotlight_view_story)
    val infiniteTransition = rememberInfiniteTransition(label = "spotlightRingTransition")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )

    val sweepGradientBrush = remember {
        Brush.sweepGradient(SpotlightStoryGradient)
    }

    Box(
        modifier = modifier
            .size(62.dp)
            .semantics {
                contentDescription = storyAccessibilityLabel
                role = Role.Button
            }
            .premiumClickable(onClick = onClick, pressedScale = 0.94f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val outerStroke = 2.2.dp.toPx()
            val ringRadius = (size.minDimension - outerStroke) / 2f

            // Ambient radial aura/glow behind the ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        TempoPrimary.copy(alpha = pulseAlpha * 0.36f),
                        Color(0xFF8B5CF6).copy(alpha = pulseAlpha * 0.16f),
                        Color.Transparent
                    ),
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f,
                center = center
            )

            // Rotating multi-stop gradient story ring
            rotate(rotationAngle) {
                drawCircle(
                    brush = sweepGradientBrush,
                    style = Stroke(width = outerStroke),
                    radius = ringRadius,
                    center = center
                )
            }
        }

        // Inner circular avatar with intentional dark separator border
        SpotlightAvatarContent(
            albumArtUrl = albumArtUrl,
            modifier = Modifier.size(50.dp)
        )

        // Interactive "Play Story" badge
        val badgeGradient = remember {
            Brush.linearGradient(
                colors = listOf(
                    TempoPrimary,
                    Color(0xFF8B5CF6)
                )
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(TempoDarkSurfaceElevated)
                .padding(1.8.dp)
                .clip(CircleShape)
                .background(badgeGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(11.dp)
                    .offset(x = 0.5.dp) // Optical center compensation for play triangle
            )
        }
    }
}

@Composable
private fun ViewedSpotlightRing(
    albumArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val storyAccessibilityLabel = stringResource(R.string.spotlight_view_story)
    Box(
        modifier = modifier
            .size(62.dp)
            .semantics {
                contentDescription = storyAccessibilityLabel
                role = Role.Button
            }
            .premiumClickable(onClick = onClick, pressedScale = 0.94f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val outerStroke = 1.2.dp.toPx()
            val ringRadius = (size.minDimension - outerStroke) / 2f

            // Calm, elegant, thin muted ring
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                style = Stroke(width = outerStroke),
                radius = ringRadius,
                center = center
            )
        }

        // Inner circular avatar with intentional dark separator border
        SpotlightAvatarContent(
            albumArtUrl = albumArtUrl,
            modifier = Modifier.size(50.dp)
        )
    }
}

@Composable
private fun SpotlightAvatarContent(
    albumArtUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(TempoDarkSurfaceElevated)
            .border(2.dp, TempoDarkSurfaceElevated, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!albumArtUrl.isNullOrBlank()) {
            CachedAsyncImage(
                imageUrl = albumArtUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                targetSizeDp = 100
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                TempoPrimary.copy(alpha = 0.20f),
                                TempoDarkSurfaceElevated
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = TempoPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun SpotlightStoryCard(
    onClick: () -> Unit,
    onRingClick: () -> Unit,
    albumArtUrl: String? = null,
    storyAvailable: Boolean = true,
    storyViewed: Boolean = false,
    modifier: Modifier = Modifier
) {
    NeoSpotlightSurface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (storyAvailable) {
                SpotlightRing(albumArtUrl = albumArtUrl, viewed = storyViewed, onClick = onRingClick)
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.home_spotlight_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_spotlight_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    lineHeight = 16.sp
                )
            }

            NeoSpotlightCta(size = 38.dp, iconSize = 12.dp)
        }
    }
}

@Composable
fun WeekInReviewGrid(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    totalHours: String,
    newDiscoveries: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_week_review),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        // Grid layout using Rows and Columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Artist
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.12f), // Restored x-factor
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topArtistImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopArtistCopy(topArtistName),
                        style = MaterialTheme.typography.bodySmall, // Smaller for longer text
                        color = me.avinas.tempo.ui.theme.NeonRed,
                        maxLines = 1
                    )
                    Text(
                        text = topArtistName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
            
            // Top Track
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.ElectricBlue.copy(alpha = 0.12f), // Restored x-factor
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topTrackImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopTrackCopy(topTrackName),
                        style = MaterialTheme.typography.bodySmall,
                        color = me.avinas.tempo.ui.theme.ElectricBlue,
                        maxLines = 1
                    )
                    Text(
                        text = topTrackName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Hours
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = me.avinas.tempo.ui.theme.GoldenAmber,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_listen_time),
                        style = MaterialTheme.typography.labelMedium,
                        color = me.avinas.tempo.ui.theme.GoldenAmber
                    )
                    Text(
                        text = totalHours,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            // Discoveries
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = InsightDanceability.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(InsightDanceability.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = InsightDanceability,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_new_finds),
                        style = MaterialTheme.typography.labelMedium,
                        color = InsightDanceability
                    )
                    Text(
                        text = "$newDiscoveries",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoverySection(
    discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_ready_discover),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        )
        
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val newArtists = discoveryStats?.newArtistsCount ?: 0
            val newTracks = discoveryStats?.newTracksCount ?: 0
            val varietyScore = ((discoveryStats?.varietyScore ?: 0.0) * 10).toInt()
            
            DiscoveryCard(
                title = stringResource(R.string.home_found_artists, newArtists),
                subtitle = stringResource(R.string.home_expand_horizon),
                icon = Icons.Default.Explore,
                color = TempoSky,
                backgroundColor = TempoInfo.copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = stringResource(R.string.home_discovered_tracks, newTracks),
                subtitle = stringResource(R.string.home_fresh_beats),
                icon = Icons.Default.History,
                color = TempoError,
                backgroundColor = TempoErrorDeep.copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = stringResource(R.string.home_variety_score, varietyScore),
                subtitle = stringResource(R.string.home_how_unique),
                icon = Icons.Default.Fingerprint,
                color = InsightMood,
                backgroundColor = InsightMood.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
fun DiscoveryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    backgroundColor: Color
) {
    GlassCard(
        modifier = Modifier.width(200.dp),
        backgroundColor = backgroundColor,
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun HabitInsights(
    insights: List<HabitInsightData>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_habits_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            insights.forEach { insight ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = insight.gradient.first().copy(alpha = 0.2f),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(insight.iconBgColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = insight.icon,
                                contentDescription = null,
                                tint = insight.iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = insight.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = insight.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class HabitInsightData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color,
    val iconBgColor: Color,
    val gradient: List<Color>
)
