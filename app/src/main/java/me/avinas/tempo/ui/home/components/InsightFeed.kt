package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.data.stats.DiscoveryTrend
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import me.avinas.tempo.ui.components.CachedAsyncImage


@Composable
fun ConstellationWeb(
    insights: List<InsightCardData>,
    selectedType: InsightType?,
    onTypeSelected: (InsightType?) -> Unit,
    tempoBpm: Float = 100f,
    modifier: Modifier = Modifier
) {
    val categories = remember(insights) {
        insights.map { it.type }.distinct().take(6)
    }
    
    if (categories.isEmpty()) return

    // Gentle rotation of the outer nodes
    val infiniteTransition = rememberInfiniteTransition(label = "constellation_orbit")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )

    // Pulsing central Vibe Core throb cycle matching average tempo (BPM)
    val cycleBpmMs = (60_000 / tempoBpm).toInt().coerceIn(300, 2000)
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleBpmMs / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Packet pulse animation along connection
    val pulsePacketTransition = rememberInfiniteTransition(label = "pulse_packet")
    val packetFraction by pulsePacketTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "packet_fraction"
    )

    // Snap offsets for interactive drag snapping
    val nodeOffsets = remember(categories) {
        categories.associateWith { Animatable(Offset.Zero, Offset.VectorConverter) }
    }

    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        val angles = remember(categories.size) {
            categories.mapIndexed { index, _ ->
                (index * (2f * Math.PI) / categories.size)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            val cx = w / 2
            val cy = h / 2
            val radius = (minOf(w, h) / 2) * 0.65f
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val nodeRadiusPx = remember(localDensity) { with(localDensity) { 24.dp.toPx() } }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cwPx = size.width
                val chPx = size.height
                val cxPx = cwPx / 2
                val cyPx = chPx / 2
                val radiusPx = (minOf(cwPx, chPx) / 2) * 0.65f

                // Web lines
                for (r in listOf(0.4f, 0.75f, 1.0f)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.02f),
                        radius = radiusPx * r,
                        center = Offset(cxPx, cyPx),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw central throb Vibe Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF7C5CFF).copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(cxPx, cyPx),
                        radius = 32.dp.toPx() * pulseAnim
                    ),
                    center = Offset(cxPx, cyPx),
                    radius = 32.dp.toPx() * pulseAnim
                )
                drawCircle(
                    color = Color.White,
                    radius = 8.dp.toPx(),
                    center = Offset(cxPx, cyPx)
                )
                drawCircle(
                    color = Color(0xFF7C5CFF),
                    radius = 5.dp.toPx(),
                    center = Offset(cxPx, cyPx)
                )

                // Render dynamic elastic connectors
                val resolvedPositions = categories.mapIndexed { index, type ->
                    val angle = angles[index] + orbitAngle.toDouble()
                    val baseOffset = Offset(
                        x = (cxPx + radiusPx * kotlin.math.cos(angle)).toFloat(),
                        y = (cyPx + radiusPx * kotlin.math.sin(angle)).toFloat()
                    )
                    val dragOffsetPx = Offset(
                        x = nodeOffsets[type]?.value?.x?.dp?.toPx() ?: 0f,
                        y = nodeOffsets[type]?.value?.y?.dp?.toPx() ?: 0f
                    )
                    baseOffset + dragOffsetPx
                }

                for (i in resolvedPositions.indices) {
                    val p1 = resolvedPositions[i]
                    val p2 = resolvedPositions[(i + 1) % resolvedPositions.size]
                    
                    drawLine(
                        color = Color(0xFF7C5CFF).copy(alpha = 0.18f),
                        start = p1,
                        end = p2,
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Draw radial spokes from center Vibe Core to nodes
                    val isSelectedNode = selectedType == categories[i]
                    val connectorColor = if (isSelectedNode) {
                        val nodeColor = when(categories[i]) {
                            InsightType.MOOD -> Color(0xFF8B5CF6)
                            InsightType.PEAK_TIME -> Color(0xFFF59E0B)
                            InsightType.BINGE -> Color(0xFFEC4899)
                            InsightType.DISCOVERY -> Color(0xFF10B981)
                            InsightType.ENERGY -> Color(0xFFEF4444)
                            InsightType.DANCEABILITY -> Color(0xFFA855F7)
                            InsightType.TEMPO -> Color(0xFF06B6D4)
                            InsightType.ACOUSTICNESS -> Color(0xFF22C55E)
                            InsightType.STREAK -> Color(0xFFF97316)
                            InsightType.GENRE -> Color(0xFFE11D48)
                            InsightType.ENGAGEMENT -> Color(0xFFDB2777)
                            else -> Color.Gray
                        }
                        nodeColor.copy(alpha = 0.4f)
                    } else {
                        Color.White.copy(alpha = 0.04f)
                    }
                    val spokeWidth = if (isSelectedNode) 2.dp.toPx() else 1.dp.toPx()

                    drawLine(
                        color = connectorColor,
                        start = Offset(cxPx, cyPx),
                        end = p1,
                        strokeWidth = spokeWidth
                    )
                }

                // If a node is selected, render traveling packet
                selectedType?.let { type ->
                    val selIndex = categories.indexOf(type)
                    if (selIndex != -1 && selIndex < resolvedPositions.size) {
                        val targetPos = resolvedPositions[selIndex]
                        val startPos = Offset(cxPx, cyPx)
                        val packetX = startPos.x + (targetPos.x - startPos.x) * packetFraction
                        val packetY = startPos.y + (targetPos.y - startPos.y) * packetFraction
                        val packetPos = Offset(packetX, packetY)

                        val nodeColor = when(type) {
                            InsightType.MOOD -> Color(0xFF8B5CF6)
                            InsightType.PEAK_TIME -> Color(0xFFF59E0B)
                            InsightType.BINGE -> Color(0xFFEC4899)
                            InsightType.DISCOVERY -> Color(0xFF10B981)
                            InsightType.ENERGY -> Color(0xFFEF4444)
                            InsightType.DANCEABILITY -> Color(0xFFA855F7)
                            InsightType.TEMPO -> Color(0xFF06B6D4)
                            InsightType.ACOUSTICNESS -> Color(0xFF22C55E)
                            InsightType.STREAK -> Color(0xFFF97316)
                            InsightType.GENRE -> Color(0xFFE11D48)
                            InsightType.ENGAGEMENT -> Color(0xFFDB2777)
                            else -> Color.Gray
                        }

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(nodeColor.copy(alpha = 0.8f), Color.Transparent),
                                center = packetPos,
                                radius = 14.dp.toPx()
                            ),
                            center = packetPos,
                            radius = 14.dp.toPx()
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.5.dp.toPx(),
                            center = packetPos
                        )
                    }
                }
            }

            // Interactive node targets placed at matching coordinates
            categories.forEachIndexed { index, type ->
                val angle = angles[index] + orbitAngle.toDouble()
                val nodeCenterX = cx + radius * kotlin.math.cos(angle).toFloat()
                val nodeCenterY = cy + radius * kotlin.math.sin(angle).toFloat()

                val dragOffset = nodeOffsets[type]?.value ?: Offset.Zero
                val isSelected = selectedType == type

                val nodeColor = when(type) {
                    InsightType.MOOD -> Color(0xFF8B5CF6)
                    InsightType.PEAK_TIME -> Color(0xFFF59E0B)
                    InsightType.BINGE -> Color(0xFFEC4899)
                    InsightType.DISCOVERY -> Color(0xFF10B981)
                    InsightType.ENERGY -> Color(0xFFEF4444)
                    InsightType.DANCEABILITY -> Color(0xFFA855F7)
                    InsightType.TEMPO -> Color(0xFF06B6D4)
                    InsightType.ACOUSTICNESS -> Color(0xFF22C55E)
                    InsightType.STREAK -> Color(0xFFF97316)
                    InsightType.GENRE -> Color(0xFFE11D48)
                    InsightType.ENGAGEMENT -> Color(0xFFDB2777)
                    else -> Color.Gray
                }

                Column(
                    modifier = Modifier
                        .offset(
                            x = nodeCenterX - 40.dp + dragOffset.x.dp,
                            y = nodeCenterY - 36.dp + dragOffset.y.dp
                        )
                        .width(80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = type.name.replace("_", " "),
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = if (isSelected) Color.White else nodeColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(nodeColor.copy(alpha = if (isSelected) 0.4f else 0.15f), Color.Transparent),
                                    radius = nodeRadiusPx
                                )
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color.White else nodeColor.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .pointerInput(type) {
                                detectDragGestures(
                                    onDragStart = {
                                        onTypeSelected(type)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        coroutineScope.launch {
                                            val newX = dragOffset.x + dragAmount.x.toDp().value
                                            val newY = dragOffset.y + dragAmount.y.toDp().value
                                            nodeOffsets[type]?.snapTo(Offset(newX, newY))
                                        }
                                    },
                                    onDragEnd = {
                                        coroutineScope.launch {
                                            nodeOffsets[type]?.animateTo(
                                                targetValue = Offset.Zero,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessLow
                                                )
                                            )
                                        }
                                    },
                                    onDragCancel = {
                                        coroutineScope.launch {
                                            nodeOffsets[type]?.animateTo(
                                                targetValue = Offset.Zero,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessLow
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                            .clickable {
                                if (selectedType == type) onTypeSelected(null) else onTypeSelected(type)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when(type) {
                            InsightType.MOOD -> Icons.Filled.Face
                            InsightType.PEAK_TIME -> Icons.Filled.DateRange
                            InsightType.BINGE -> Icons.Filled.Bolt
                            InsightType.DISCOVERY -> Icons.Filled.Celebration
                            InsightType.ENERGY -> Icons.Filled.Bolt
                            InsightType.DANCEABILITY -> Icons.Filled.Celebration
                            InsightType.TEMPO -> Icons.Filled.Speed
                            InsightType.ACOUSTICNESS -> Icons.Filled.Piano
                            InsightType.STREAK -> Icons.Filled.LocalFireDepartment
                            InsightType.GENRE -> Icons.AutoMirrored.Filled.QueueMusic
                            InsightType.ENGAGEMENT -> Icons.Filled.Favorite
                            else -> Icons.Filled.Settings
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = type.name,
                            tint = if (isSelected) Color.White else nodeColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = if (selectedType == null) "TAP OR DRAG STARS TO ORBIT & DETAIL" else "ACTIVE DYNAMICS // ${selectedType.name.replace("_", " ")}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun VibeOverviewCard(insights: List<InsightCardData>) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White.copy(0.04f),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Vibe Constellation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Your music signals are mapped as stars in the constellation. Drag them to stretch the connections, or tap a star to reveal details about your listening patterns.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.25
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                insights.take(3).forEach { insight ->
                    val color = when(insight.type) {
                        InsightType.MOOD -> Color(0xFF8B5CF6)
                        InsightType.PEAK_TIME -> Color(0xFFF59E0B)
                        InsightType.BINGE -> Color(0xFFEC4899)
                        InsightType.DISCOVERY -> Color(0xFF10B981)
                        InsightType.ENERGY -> Color(0xFFEF4444)
                        InsightType.DANCEABILITY -> Color(0xFFA855F7)
                        InsightType.TEMPO -> Color(0xFF06B6D4)
                        InsightType.ACOUSTICNESS -> Color(0xFF22C55E)
                        InsightType.STREAK -> Color(0xFFF97316)
                        InsightType.GENRE -> Color(0xFFE11D48)
                        InsightType.ENGAGEMENT -> Color(0xFFDB2777)
                        else -> Color.Gray
                    }
                    
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(color, CircleShape)
                        )
                        Text(
                            text = insight.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InsightFeed(
    insights: List<InsightCardData>,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedType by remember { mutableStateOf<InsightType?>(null) }
    
    val nonGamification = remember(insights) {
        insights.filter { it.payload !is InsightPayload.GamificationProgress }
    }

    val selectedInsight = remember(nonGamification, selectedType) {
        nonGamification.find { it.type == selectedType }
    }

    val avgTempo = remember(nonGamification) {
        val tempoInsight = nonGamification.find { it.type == InsightType.TEMPO }
        val payload = tempoInsight?.payload
        if (payload is InsightPayload.TempoValue) payload.bpm else 100f
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConstellationWeb(
            insights = nonGamification,
            selectedType = selectedType,
            onTypeSelected = { selectedType = it },
            tempoBpm = avgTempo
        )

        Spacer(modifier = Modifier.height(4.dp))

        Crossfade(
            targetState = selectedInsight,
            animationSpec = tween(500),
            label = "deck_transition"
        ) { insight ->
            if (insight != null) {
                InsightCard(
                    insight = insight,
                    selectedType = selectedType,
                    onCloseClick = { selectedType = null },
                    onClick = {}
                )
            } else {
                VibeOverviewCard(insights = nonGamification)
            }
        }
    }
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
    modifier: Modifier = Modifier
) {
    val energyColor = androidx.compose.ui.graphics.lerp(
        Color(0xFF1E103C), // Low Energy: Very Dark Violet
        Color(0xFF5B21B6), // High Energy: Rich Purple
        energy
    )
    
    val valenceColor = androidx.compose.ui.graphics.lerp(
        Color(0xFF0F172A), // Low Valence: Very Dark Indigo
        Color(0xFF4C1D95), // High Valence: Deep Violet
        valence
    )
    
    // Breathing Animation
    val infiniteTransition = rememberInfiniteTransition(label = "nebula_breathing")
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, // Increased intensity
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
    ) {
        val outerHorizontalPadding = 16.dp
        val trailingActionInset = if (maxWidth < 360.dp) 72.dp else 88.dp
        val availableHeaderWidth = (maxWidth - outerHorizontalPadding - trailingActionInset).coerceAtLeast(180.dp)
        val compactLayout = availableHeaderWidth < 240.dp
        val ultraCompactLayout = availableHeaderWidth < 205.dp

        val avatarSize: Dp = when {
            ultraCompactLayout -> 44.dp
            compactLayout -> 48.dp
            else -> 56.dp
        }
        val rowPaddingH = when {
            ultraCompactLayout -> 10.dp
            compactLayout -> 12.dp
            else -> 14.dp
        }
        val rowPaddingV = when {
            ultraCompactLayout -> 8.dp
            else -> 10.dp
        }
        val itemSpacing = when {
            ultraCompactLayout -> 10.dp
            compactLayout -> 12.dp
            else -> 16.dp
        }
        val nameTextStyle = when {
            ultraCompactLayout -> MaterialTheme.typography.titleSmall
            compactLayout -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleLarge
        }
        val levelNumStyle = when {
            ultraCompactLayout -> MaterialTheme.typography.bodyMedium
            compactLayout -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.titleMedium
        }
        val titleStyle = if (compactLayout) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelMedium
        }

        // Dynamic Nebula Gradient (Enhanced)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // 1. Base Warm Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(energyColor.copy(alpha = breatheAlpha * 0.8f), Color.Transparent),
                    center = Offset(width * 0.2f, height * 0.4f),
                    radius = width * 0.9f * scaleAnim
                ),
                center = Offset(width * 0.2f, height * 0.4f),
                radius = width * 0.9f * scaleAnim
            )
            
            // 2. Secondary Vibe Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(valenceColor.copy(alpha = breatheAlpha * 0.6f), Color.Transparent),
                    center = Offset(width * 0.8f, height * 0.6f),
                    radius = width * 0.8f * scaleAnim
                ),
                center = Offset(width * 0.8f, height * 0.6f),
                radius = width * 0.8f * scaleAnim
            )

            // 3. Flowing Vibe Wave at the bottom (Creative Ambient Visualizer Wave)
            val wavePath = Path()
            val waveHeight = height * 0.15f
            val baseHeight = height * 0.85f
            
            wavePath.moveTo(0f, height)
            for (x in 0..width.toInt() step 15) {
                val relativeX = x.toFloat() / width
                val y = baseHeight + kotlin.math.sin(relativeX * 2f * Math.PI.toFloat() + wavePhase) * waveHeight * kotlin.math.sin(wavePhase * 0.5f)
                wavePath.lineTo(x.toFloat(), y)
            }
            wavePath.lineTo(width, height)
            wavePath.close()
            
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        valenceColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    startY = baseHeight - waveHeight,
                    endY = height
                )
            )
        }
        
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = outerHorizontalPadding, top = 8.dp, bottom = 12.dp, end = trailingActionInset)
        ) {
            // Premium Opaque Card for Profile - Minimalist Pill Edition
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = isGamificationEnabled,
                        onClick = onLevelClick
                    )
                    .defaultMinSize(minHeight = if (!levelTitle.isNullOrBlank()) 72.dp else 64.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(100.dp),
                        spotColor = Color(0x2B000000),
                        ambientColor = Color(0x1A000000)
                    )
                    .border(width = 1.dp, color = Color(0xFFE2E8F0), shape = RoundedCornerShape(100.dp)),
                color = Color.White,
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
                                val strokeWidth = 3.dp.toPx()
                                val radius = (size.minDimension - strokeWidth) / 2
                                val topLeft = Offset(
                                    (size.width - radius * 2) / 2,
                                    (size.height - radius * 2) / 2
                                )
                                val arcSize = Size(radius * 2, radius * 2)
                                
                                // Background ring (subtle gray)
                                drawArc(
                                    color = Color(0xFFF1F5F9),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                                
                                // Progress arc (vibrant sweep gradient)
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1), Color(0xFFEC4899))
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
                                .background(Color(0xFFF1F5F9))
                                .border(1.dp, Color(0xFFE2E8F0), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profileImagePath.isNullOrBlank()) {
                                Text(
                                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                    style = levelNumStyle.copy(lineHeight = levelNumStyle.fontSize * 1.1),
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF111827),
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

                    // Right: User Info - weight(1f) ensures it fills remaining space
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp),
                        verticalArrangement = Arrangement.spacedBy(if (levelTitle.isNullOrBlank()) 0.dp else 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ResponsiveText(
                                text = userName,
                                style = nameTextStyle.copy(
                                    lineHeight = if (ultraCompactLayout) {
                                        nameTextStyle.fontSize * 1.05f
                                    } else {
                                        nameTextStyle.fontSize * 1.1f
                                    }
                                ),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827),
                                maxLines = if (ultraCompactLayout) 2 else 1,
                                softWrap = ultraCompactLayout,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (isGamificationEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = Color(0xFF8B5CF6).copy(alpha = 0.08f), // Soft violet tint for premium branding
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp
                                ) {
                                    Text(
                                        text = "LVL ${userLevel ?: 0}",
                                        modifier = Modifier.padding(
                                            horizontal = if (compactLayout) 8.dp else 10.dp,
                                            vertical = 5.dp
                                        ),
                                        style = if (compactLayout) {
                                            MaterialTheme.typography.labelSmall
                                        } else {
                                            MaterialTheme.typography.labelMedium
                                        },
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF8B5CF6), // Primary Purple
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (isGamificationEnabled && !levelTitle.isNullOrBlank()) {
                            ResponsiveText(
                                text = levelTitle.uppercase(),
                                style = titleStyle.copy(
                                    letterSpacing = if (compactLayout) 0.6.sp else 0.9.sp,
                                    lineHeight = titleStyle.fontSize * 1.15f
                                ),
                                color = Color(0xFFEC4899),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodVisualizer(valence: Float, energy: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 4.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "mood_glow")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 6f,
            targetValue = 14f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2
            val cy = h / 2

            // Draw axis lines
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(0f, cy),
                end = Offset(w, cy),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(cx, 0f),
                end = Offset(cx, h),
                strokeWidth = 1.dp.toPx()
            )

            val posX = valence * w
            val posY = (1f - energy) * h
            val targetOffset = Offset(posX, posY)

            // Dynamic glow ring
            drawCircle(
                color = color,
                radius = pulseScale.dp.toPx(),
                center = targetOffset,
                alpha = pulseAlpha
            )

            // Main point
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = targetOffset
            )
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = targetOffset
            )
        }

        // Corner Labels
        Text(
            text = "Calm",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomStart)
        )
        Text(
            text = "Energetic",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.TopEnd)
        )
        Text(
            text = "Melancholic",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomEnd)
        )
        Text(
            text = "Joyful",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun PeakTimeVisualizer(peakHour: Int, hourlyDistribution: List<HourlyDistribution>, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pointsCount = hourlyDistribution.size
            if (pointsCount < 2) return@Canvas

            val maxPlays = hourlyDistribution.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
            val stepX = w / (pointsCount - 1)

            val path = Path()
            val fillPath = Path()

            hourlyDistribution.forEachIndexed { index, dist ->
                val x = index * stepX
                val ratio = dist.playCount.toFloat() / maxPlays
                val y = h - (ratio * h * 0.75f) - (h * 0.05f)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (index - 1) * stepX
                    val prevRatio = hourlyDistribution[index - 1].playCount.toFloat() / maxPlays
                    val prevY = h - (prevRatio * h * 0.75f) - (h * 0.05f)
                    
                    val controlX1 = prevX + (stepX / 2f)
                    val controlY1 = prevY
                    val controlX2 = prevX + (stepX / 2f)
                    val controlY2 = y

                    path.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                    fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                }

                if (index == pointsCount - 1) {
                    fillPath.lineTo(x, h)
                    fillPath.close()
                }
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            val peakIndex = hourlyDistribution.indexOfFirst { it.hour == peakHour }
            if (peakIndex != -1) {
                val peakX = peakIndex * stepX
                val peakRatio = hourlyDistribution[peakIndex].playCount.toFloat() / maxPlays
                val peakY = h - (peakRatio * h * 0.75f) - (h * 0.05f)

                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(peakX, h),
                    end = Offset(peakX, peakY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )

                drawCircle(
                    color = color,
                    radius = 6.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
            }
        }

        Text(
            text = "12 AM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomStart)
        )
        Text(
            text = "12 PM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        Text(
            text = "11 PM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun BingeVisualizer(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val barCount = 12
        val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
        val animations = (0 until barCount).map { index ->
            val duration = 400 + (index * 80) % 350
            infiniteTransition.animateFloat(
                initialValue = 0.1f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
        }

        Row(
            modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            animations.forEach { anim ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(anim.value)
                        .background(
                            brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.3f))),
                            shape = RoundedCornerShape(100.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun DiscoveryVisualizer(trends: List<DiscoveryTrend>, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pointsCount = trends.size
            if (pointsCount < 2) return@Canvas

            val maxDiscoveries = trends.maxOfOrNull { it.new_artists_count }?.coerceAtLeast(1) ?: 1
            val stepX = w / (pointsCount - 1)

            val path = Path()
            val fillPath = Path()

            trends.forEachIndexed { index, trend ->
                val x = index * stepX
                val ratio = trend.new_artists_count.toFloat() / maxDiscoveries
                val y = h - (ratio * h * 0.7f) - (h * 0.05f)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (index - 1) * stepX
                    val prevRatio = trends[index - 1].new_artists_count.toFloat() / maxDiscoveries
                    val prevY = h - (prevRatio * h * 0.7f) - (h * 0.05f)

                    val controlX1 = prevX + (stepX / 2f)
                    val controlY1 = prevY
                    val controlX2 = prevX + (stepX / 2f)
                    val controlY2 = y

                    path.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                    fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                }

                if (index == pointsCount - 1) {
                    fillPath.lineTo(x, h)
                    fillPath.close()
                }
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.2f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            trends.forEachIndexed { index, trend ->
                val x = index * stepX
                val ratio = trend.new_artists_count.toFloat() / maxDiscoveries
                val y = h - (ratio * h * 0.7f) - (h * 0.05f)

                drawCircle(
                    color = color,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            trends.forEach { trend ->
                val displayMonth = try {
                    val parts = trend.month.split("-")
                    val monthInt = parts[1].toInt()
                    java.time.Month.of(monthInt).getDisplayName(
                        java.time.format.TextStyle.SHORT,
                        java.util.Locale.ENGLISH
                    )
                } catch (e: Exception) {
                    trend.month
                }
                Text(
                    text = displayMonth,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun FeatureGaugeVisualizer(value: Float, color: Color, label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        val animValue = remember { Animatable(0f) }
        LaunchedEffect(value) {
            animValue.animateTo(
                targetValue = value.coerceIn(0f, 1f),
                animationSpec = tween(1200, easing = FastOutSlowInEasing)
            )
        }

        Canvas(modifier = Modifier.size(100.dp)) {
            val w = size.width
            val h = size.height
            val strokeWidthPx = 8.dp.toPx()
            
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(strokeWidthPx/2, strokeWidthPx/2),
                size = Size(w - strokeWidthPx, h - strokeWidthPx),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            drawArc(
                brush = Brush.sweepGradient(
                    listOf(color.copy(alpha = 0.6f), color, color)
                ),
                startAngle = 140f,
                sweepAngle = animValue.value * 260f,
                useCenter = false,
                topLeft = Offset(strokeWidthPx/2, strokeWidthPx/2),
                size = Size(w - strokeWidthPx, h - strokeWidthPx),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun TempoBPMVisualizer(bpm: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        contentAlignment = Alignment.Center
    ) {
        val cycleMs = (60_000 / bpm).toInt().coerceIn(200, 2000)
        val infiniteTransition = rememberInfiniteTransition(label = "metronome")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleMs / 2, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleMs / 2, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .background(color, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, color, CircleShape)
                )
            }
            Column {
                Text(
                    text = "${bpm.toInt()} BPM",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Pulsing in sync with average tempo",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun StreakVisualizer(days: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "flame")
        val flameScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "flameScale"
        )

        val fireColor1 = Color(0xFFF97316)
        val fireColor2 = Color(0xFFEF4444)
        val fireColor3 = Color(0xFFFBBF24)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = flameScale
                        scaleY = flameScale
                    }
            ) {
                val w = size.width
                val h = size.height

                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.05f)
                    cubicTo(w * 0.7f, h * 0.3f, w * 0.95f, h * 0.55f, w * 0.8f, h * 0.8f)
                    cubicTo(w * 0.7f, h * 0.95f, w * 0.3f, h * 0.95f, w * 0.2f, h * 0.8f)
                    cubicTo(w * 0.05f, h * 0.55f, w * 0.3f, h * 0.3f, w * 0.5f, h * 0.05f)
                    close()
                }

                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(fireColor3, fireColor1, fireColor2),
                        startY = 0f,
                        endY = h
                    )
                )
            }

            Column {
                Text(
                    text = "$days DAY STREAK",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = fireColor1
                )
                Text(
                    text = "Keep listening daily to grow the fire!",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun GenreVisualizer(genre: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(color.copy(alpha = 0.08f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(100.dp),
                    ambientColor = color.copy(alpha = 0.2f),
                    spotColor = color.copy(alpha = 0.3f)
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
                Text(
                    text = genre.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun EngagementVisualizer(value: Float, color: Color) {
    val isSkipRate = value < 0f
    val absValue = kotlin.math.abs(value)
    val percentageLabel = if (isSkipRate) "Skip Rate" else "Completion"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        val animValue = remember { Animatable(0f) }
        LaunchedEffect(value) {
            animValue.animateTo(
                targetValue = absValue.coerceIn(0f, 1f),
                animationSpec = tween(1000, easing = FastOutSlowInEasing)
            )
        }

        Canvas(modifier = Modifier.size(72.dp)) {
            val w = size.width
            val h = size.height
            val strokeWidthPx = 6.dp.toPx()

            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = (w - strokeWidthPx) / 2f,
                style = Stroke(width = strokeWidthPx)
            )

            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = animValue.value * 360f,
                useCenter = false,
                topLeft = Offset(strokeWidthPx/2, strokeWidthPx/2),
                size = Size(w - strokeWidthPx, h - strokeWidthPx),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(absValue * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = percentageLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun InsightCard(
    insight: InsightCardData,
    onCloseClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedType: InsightType? = null
) {
    // Determine color based on type
    val (icon, color) = when(insight.type) {
        InsightType.MOOD -> Icons.Filled.Face to Color(0xFF8B5CF6)
        InsightType.PEAK_TIME -> Icons.Filled.DateRange to Color(0xFFF59E0B)
        InsightType.BINGE -> Icons.Filled.Bolt to Color(0xFFEC4899)
        InsightType.DISCOVERY -> Icons.Filled.Celebration to Color(0xFF10B981)
        InsightType.ENERGY -> Icons.Filled.Bolt to Color(0xFFEF4444)
        InsightType.DANCEABILITY -> Icons.Filled.Celebration to Color(0xFFA855F7)
        InsightType.TEMPO -> Icons.Filled.Speed to Color(0xFF06B6D4)
        InsightType.ACOUSTICNESS -> Icons.Filled.Piano to Color(0xFF22C55E)
        InsightType.STREAK -> Icons.Filled.LocalFireDepartment to Color(0xFFF97316)
        InsightType.GENRE -> Icons.AutoMirrored.Filled.QueueMusic to Color(0xFFE11D48)
        InsightType.ENGAGEMENT -> Icons.Filled.Favorite to Color(0xFFDB2777)
        InsightType.RATE_APP -> Icons.Filled.Star to Color(0xFFFFD700)
        else -> Icons.Filled.Settings to Color.Gray
    }

    // Gamification progress removed as per user request (moved to Vibe Header)

    val isHighlighted = selectedType == insight.type

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = Color.White.copy(alpha = 0.04f),
        borderColor = if (isHighlighted) color else null,
        borderWidth = if (isHighlighted) 2.dp else null,
        shadowElevation = 0.dp,
        shadowSpotColor = Color.Transparent,
        variant = GlassCardVariant.HighProminence,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val iconBgColor = (color as androidx.compose.ui.graphics.Color).copy(alpha = 0.2f)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(iconBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        val categoryPrefix = when(insight.type) {
                            InsightType.MOOD -> "LISTENING DYNAMICS // MOOD"
                            InsightType.PEAK_TIME -> "LISTENING DYNAMICS // PEAK TIME"
                            InsightType.BINGE -> "LISTENING DYNAMICS // BINGE SESSION"
                            InsightType.DISCOVERY -> "LISTENING DYNAMICS // DISCOVERY"
                            InsightType.ENERGY -> "AUDIO ATTRIBUTES // ENERGY"
                            InsightType.DANCEABILITY -> "AUDIO ATTRIBUTES // DANCEABILITY"
                            InsightType.TEMPO -> "AUDIO ATTRIBUTES // TEMPO"
                            InsightType.ACOUSTICNESS -> "AUDIO ATTRIBUTES // ACOUSTICNESS"
                            InsightType.STREAK -> "LISTENING HABITS // STREAK"
                            InsightType.GENRE -> "LISTENING HABITS // GENRE"
                            InsightType.ENGAGEMENT -> "LISTENING HABITS // ENGAGEMENT"
                            else -> "INSIGHT // DETAIL"
                        }
                        Text(
                            text = categoryPrefix,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = color.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = insight.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Description is always shown for non-gamification cards
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
            )

            // Custom visual representation below text if data is present
            when (val payload = insight.payload) {
                is InsightPayload.MoodData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    MoodVisualizer(valence = payload.valence, energy = payload.energy, color = color)
                }
                is InsightPayload.PeakTimeData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    PeakTimeVisualizer(peakHour = payload.peakHour, hourlyDistribution = payload.hourlyDistribution, color = color)
                }
                is InsightPayload.BingeData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    BingeVisualizer(color = color)
                }
                is InsightPayload.DiscoveryData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    DiscoveryVisualizer(trends = payload.trends, color = color)
                }
                is InsightPayload.StreakData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    StreakVisualizer(days = payload.days)
                }
                is InsightPayload.GenreData -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    GenreVisualizer(genre = payload.genre, color = color)
                }
                is InsightPayload.FeatureValue -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (payload.type == InsightType.ENGAGEMENT) {
                        EngagementVisualizer(value = payload.value, color = color)
                    } else {
                        val label = when(payload.type) {
                            InsightType.ENERGY -> "Energy"
                            InsightType.DANCEABILITY -> "Danceability"
                            InsightType.ACOUSTICNESS -> "Acousticness"
                            else -> "Value"
                        }
                        FeatureGaugeVisualizer(value = payload.value, color = color, label = label)
                    }
                }
                is InsightPayload.TempoValue -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    TempoBPMVisualizer(bpm = payload.bpm, color = color)
                }
                else -> {}
            }
        }
    }
}

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

