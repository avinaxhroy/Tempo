package me.avinas.tempo.ui.home.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.FrostedIconButton
import me.avinas.tempo.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * The insight feed: an orbit map, and one card at a time.
 *
 * The map is the index. Nothing is shown until a star is chosen, because six insights stacked at
 * once is six cards to scroll past before reaching the rest of the screen; picking a star opens
 * its card and picking another replaces it. Each star carries its card's own label and colour, so
 * the map is a legend for the card rather than a second taxonomy, and the composition of the card
 * that appears is shaped by its own payload.
 */
@Composable
fun InsightFeed(
    insights: List<InsightCardData>,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The level card is gamification state, not a listening insight, and it has its own surface
    // in VibeHeader.
    val cards = remember(insights) {
        insights.filter { it.payload !is InsightPayload.GamificationProgress }
    }
    var selected by remember(cards) { mutableStateOf<InsightType?>(null) }
    val selectedCard = cards.firstOrNull { it.type == selected }

    Column(modifier = modifier) {
        if (cards.size > 1) {
            ConstellationWeb(
                insights = cards,
                selectedType = selected,
                onTypeSelected = { type ->
                    // Re-tapping the open star closes it again, so the section can be dismissed.
                    selected = if (type == selected) null else type
                },
            )
        } else {
            // A single insight has no map to navigate: show it directly.
            cards.firstOrNull()?.let { InsightCard(it, onNavigateToArtist = onNavigateToArtist) }
        }

        Crossfade(
            targetState = selectedCard,
            animationSpec = tween(durationMillis = 260),
            label = "insight_card",
        ) { card ->
            if (card != null) {
                Box(modifier = Modifier.padding(top = 22.dp)) {
                    InsightCard(card, onNavigateToArtist = onNavigateToArtist)
                }
            }
        }
    }
}

/**
 * The orbit map.
 *
 * Every star is one card: its label is that card's own value ("Arya", "8 PM", "12x", "Pop Icon")
 * and its colour is that card's accent, so the map and the card share one vocabulary. Selecting a
 * star sends a packet down its spoke and opens that card underneath; with nothing selected the map
 * carries a one-line hint, because a map whose cards are all hidden has to say that it opens.
 */
@Composable
fun ConstellationWeb(
    insights: List<InsightCardData>,
    selectedType: InsightType?,
    onTypeSelected: (InsightType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(insights) { insights.take(6) }
    if (nodes.isEmpty()) return

    val angles = remember(nodes.size) {
        nodes.indices.map { index -> (index * (2f * Math.PI) / nodes.size).toFloat() }
    }
    val dragged = remember(nodes) { mutableStateMapOf<InsightType, Offset>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val packetProgress by animateFloatAsState(
        targetValue = if (selectedType != null) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "packet",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cx = maxWidth / 2
            val cy = maxHeight / 2
            val radius = (minOf(maxWidth, maxHeight) / 2) * 0.66f

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cxPx = size.width / 2
                val cyPx = size.height / 2
                val radiusPx = (minOf(size.width, size.height) / 2) * 0.66f
                val centre = Offset(cxPx, cyPx)

                for (ring in listOf(0.45f, 0.75f, 1f)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.03f),
                        radius = radiusPx * ring,
                        center = centre,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                val positions = nodes.mapIndexed { index, insight ->
                    val drag = dragged[insight.type] ?: Offset.Zero
                    Offset(
                        x = cxPx + radiusPx * cos(angles[index]) + drag.x.dp.toPx(),
                        y = cyPx + radiusPx * sin(angles[index]) + drag.y.dp.toPx(),
                    )
                }

                positions.forEachIndexed { index, start ->
                    val end = positions[(index + 1) % positions.size]
                    drawLine(
                        color = Color.White.copy(alpha = 0.05f),
                        start = start,
                        end = end,
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                positions.forEachIndexed { index, position ->
                    val insight = nodes[index]
                    val accent = accentOf(insight.payload)
                    val isSelected = insight.type == selectedType
                    drawLine(
                        color = if (isSelected) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f),
                        start = centre,
                        end = position,
                        strokeWidth = if (isSelected) 2.dp.toPx() else 1.dp.toPx(),
                    )
                }

                // Core marker: the origin of every spoke.
                drawCircle(color = Color.White.copy(alpha = 0.55f), radius = 3.dp.toPx(), center = centre)

                val selectedIndex = nodes.indexOfFirst { it.type == selectedType }
                if (selectedIndex >= 0) {
                    val target = positions[selectedIndex]
                    val packet = Offset(
                        x = centre.x + (target.x - centre.x) * packetProgress,
                        y = centre.y + (target.y - centre.y) * packetProgress,
                    )
                    val accent = accentOf(nodes[selectedIndex].payload)
                    drawCircle(
                        color = accent.copy(alpha = 0.16f + 0.5f * packetProgress),
                        radius = 9.dp.toPx(),
                        center = packet,
                    )
                    drawCircle(color = accent, radius = 3.dp.toPx(), center = packet)
                }
            }

            nodes.forEachIndexed { index, insight ->
                val accent = accentOf(insight.payload)
                val isSelected = insight.type == selectedType
                val drag = dragged[insight.type] ?: Offset.Zero
                val nodeX = cx + radius * cos(angles[index]) + drag.x.dp
                val nodeY = cy + radius * sin(angles[index]) + drag.y.dp

                Column(
                    modifier = Modifier
                        // 34dp puts the disc's centre on the orbit point: label 13 + gap 4 + radius 17.
                        .offset(x = nodeX - 40.dp, y = nodeY - 34.dp)
                        .width(80.dp)
                        // The label is the target as much as the disc: it is the part people read.
                        .pointerInput(insight.type) {
                            detectDragGestures(
                                onDragStart = { onTypeSelected(insight.type) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val current = dragged[insight.type] ?: Offset.Zero
                                    dragged[insight.type] = Offset(
                                        x = current.x + with(density) { amount.x.toDp().value },
                                        y = current.y + with(density) { amount.y.toDp().value },
                                    )
                                },
                                onDragEnd = {
                                    scope.launch { release(dragged, insight.type) }
                                },
                                onDragCancel = {
                                    scope.launch { release(dragged, insight.type) }
                                },
                            )
                        }
                        .clickable {
                            onTypeSelected(if (isSelected) null else insight.type)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = labelOf(insight),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            letterSpacing = 0.sp,
                        ),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White.copy(alpha = 0.92f) else TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        accent.copy(alpha = if (isSelected) 0.42f else 0.14f),
                                        Color.Transparent,
                                    ),
                                ),
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accent else accent.copy(alpha = 0.45f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconOf(insight.payload),
                            contentDescription = null,
                            tint = if (isSelected) accent else accent.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            }
        }

        // Nothing is open yet, so say what the stars do; once a card is up the map needs no caption.
        if (selectedType == null) {
            Text(
                text = stringResource(R.string.constellation_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                style = CaptionSmall,
                fontWeight = FontWeight.Medium,
                color = TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private suspend fun release(dragged: MutableMap<InsightType, Offset>, type: InsightType) {
    val start = dragged[type] ?: return
    animate(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
    ) { fraction, _ ->
        dragged[type] = start * (1f - fraction)
    }
    dragged.remove(type)
}

private fun iconOf(payload: InsightPayload): ImageVector = when (payload) {
    is InsightPayload.PersonalityData -> Icons.Filled.AutoAwesome
    is InsightPayload.MoodData -> Icons.Filled.Person
    is InsightPayload.TopArtistData -> Icons.Filled.Star
    is InsightPayload.BingeData -> Icons.Filled.Bolt
    is InsightPayload.EngagementData -> Icons.Filled.Favorite
    is InsightPayload.StreakData -> Icons.Filled.LocalFireDepartment
    is InsightPayload.PeakTimeData -> Icons.Filled.AccessTime
    is InsightPayload.DiscoveryData -> Icons.Filled.Celebration
    is InsightPayload.AudioProfileData -> Icons.Filled.Tune
    else -> Icons.Filled.Star
}

@Composable
fun VibeHeader(
    energy: Float,
    valence: Float,
    userName: String,
    profileImagePath: String? = null,
    isNewUser: Boolean = false,
    userLevel: Int? = null,
    levelProgress: Float = 0f,
    levelTitle: String? = null,
    isGamificationEnabled: Boolean = true,
    onLevelClick: () -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // The avatar puck is the pill's floor, and it is the same 40dp circle as the settings
        // button beside it, so the pill is content (40dp) + 6dp top and bottom = 52dp and the
        // button centers against it. No min-height is needed: the puck already sets one, and the
        // old 72dp floor was what left the pill floating in empty space.
        val avatarSize = 40.dp
        val rowPaddingH = 10.dp
        val rowPaddingV = 6.dp
        val itemSpacing = 10.dp
        val nameTextStyle = MaterialTheme.typography.titleMedium
        val levelNumStyle = MaterialTheme.typography.titleSmall
        val titleStyle = MaterialTheme.typography.labelMedium

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Premium Opaque Card for Profile - Minimalist Pill Edition
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(
                        enabled = isGamificationEnabled,
                        onClick = onLevelClick
                    )
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(100.dp),
                        spotColor = TempoDarkSurfaceSunken,
                        ambientColor = TempoDarkSurfaceSunken.copy(alpha = 0.8f)
                    )
                    .border(width = 1.dp, color = PillBorder, shape = RoundedCornerShape(100.dp)),
                color = PillSurface,
                shape = RoundedCornerShape(100.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = rowPaddingH, vertical = rowPaddingV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Left: Adaptive Level Ring + Avatar
                    Box(
                        modifier = Modifier.size(avatarSize),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGamificationEnabled && userLevel != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 2.5.dp.toPx()
                                val radius = (size.minDimension - strokeWidth) / 2
                                val topLeft = Offset(
                                    (size.width - radius * 2) / 2,
                                    (size.height - radius * 2) / 2
                                )
                                val arcSize = Size(radius * 2, radius * 2)

                                drawArc(
                                    color = PillInnerSurface,
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            LevelRingSweepStart,
                                            LevelRingSweepMid,
                                            LevelRingSweepEnd,
                                            LevelRingSweepStart
                                        )
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = 360f * levelProgress,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Avatar image or text letter inside (with a small margin so it sits inside the ring)
                        val innerAvatarSize = if (isGamificationEnabled && userLevel != null) avatarSize - 8.dp else avatarSize
                        Box(
                            modifier = Modifier
                                .size(innerAvatarSize)
                                .clip(CircleShape)
                                .background(PillInnerSurface)
                                .border(1.dp, PillBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profileImagePath.isNullOrBlank()) {
                                Text(
                                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                    style = levelNumStyle.copy(lineHeight = levelNumStyle.fontSize * 1.1),
                                    fontWeight = FontWeight.Black,
                                    color = PillTextPrimary,
                                    maxLines = 1
                                )
                            } else {
                                CachedAsyncImage(
                                    imageUrl = profileImagePath,
                                    contentDescription = "Profile image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .widthIn(min = 100.dp, max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(if (levelTitle.isNullOrBlank()) 0.dp else 2.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ResponsiveText(
                                text = userName,
                                style = nameTextStyle.copy(lineHeight = nameTextStyle.fontSize * 1.1f),
                                modifier = Modifier,
                                fontWeight = FontWeight.Bold,
                                color = PillTextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isGamificationEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = TempoPop.copy(alpha = 0.08f),
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp
                                ) {
                                    Text(
                                        text = "LVL ${userLevel ?: 0}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Black,
                                        color = TempoPop,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (isGamificationEnabled && !levelTitle.isNullOrBlank()) {
                            ResponsiveText(
                                text = levelTitle.uppercase(),
                                style = titleStyle.copy(letterSpacing = 0.9.sp, lineHeight = titleStyle.fontSize * 1.15f),
                                color = InsightBinge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (onSettingsClick != null) {
                FrostedIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.home_settings),
                    onClick = onSettingsClick
                )
            }
        }
    }
}

/**
 * Shrinks type until it fits rather than clipping a name mid-word.
 *
 * Used only where the string is user-generated and its length is unbounded (display name, level
 * title); everywhere else the layout gives type a fixed size and lets it wrap.
 */
@Composable
private fun ResponsiveText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip
) {
    var fontSize by remember(text, style.fontSize, maxLines, softWrap) { mutableStateOf(style.fontSize) }
    var readyToDraw by remember(text, style.fontSize, maxLines, softWrap) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.graphicsLayer {
            alpha = if (readyToDraw) 1f else 0f
        },
        style = style.copy(fontSize = fontSize),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.hasVisualOverflow && fontSize.value > 12f) {
                fontSize = (fontSize.value * 0.9f).sp
            } else {
                readyToDraw = true
            }
        }
    )
}
