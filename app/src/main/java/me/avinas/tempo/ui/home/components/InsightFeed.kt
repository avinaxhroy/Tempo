package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import me.avinas.tempo.ui.profile.CompactLevelRing
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp


@Composable
fun InsightFeed(
    insights: List<InsightCardData>,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        insights
            .filter { it.payload !is InsightPayload.GamificationProgress }
            .forEach { insight ->
            InsightCard(
                insight = insight,
                onClick = {
                    // Logic to handle navigation based on insight type or data
                }
            )
        }
    }
}

@Composable
fun VibeHeader(
    energy: Float,
    valence: Float,
    userName: String,
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
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp) // Reduced height for balanced profile feel
    ) {
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
        }
        
        // --- Adaptive sizing based on screen width ---
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        // Available pill width = screenWidth - start(16) - end(120) - card internal padding
        val availableDp = (screenWidthDp - 16 - 120).coerceAtLeast(120)

        // Ring: scales between 40dp (tiny screens) and 56dp (large screens),
        // proportional to available space. Clamped for safety.
        val ringSize: Dp = ((availableDp * 0.18f).coerceIn(40f, 56f)).dp
        val strokeWidthDp: Dp = ((ringSize.value * 0.09f).coerceIn(3.5f, 5.5f)).dp

        // Row padding and spacing also scale with available width
        val rowPaddingH: Dp = ((availableDp * 0.04f).coerceIn(8f, 14f)).dp
        val rowPaddingV: Dp = ((availableDp * 0.03f).coerceIn(6f, 12f)).dp
        val itemSpacing: Dp = ((availableDp * 0.06f).coerceIn(8f, 16f)).dp

        // Text: use titleMedium on narrow, titleLarge on wide screens
        val nameTextStyle = if (availableDp < 200) MaterialTheme.typography.titleSmall
                            else if (availableDp < 260) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge
        val levelNumStyle = if (availableDp < 200) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.titleMedium
        val titleStyle = if (availableDp < 200) MaterialTheme.typography.labelSmall
                         else MaterialTheme.typography.labelMedium

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 120.dp)
        ) {
            // Premium Opaque Card for Profile - Minimalist Pill Edition
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = isGamificationEnabled,
                        onClick = onLevelClick
                    )
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(100.dp),
                        spotColor = Color(0x14000000),
                        ambientColor = Color(0x08000000)
                    ),
                color = Color.White,
                shape = RoundedCornerShape(100.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = rowPaddingH, vertical = rowPaddingV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Left: Adaptive Level Ring
                    if (isGamificationEnabled) {
                        val level = userLevel ?: 0
                        val progress = levelProgress

                        Box(
                            modifier = Modifier.size(ringSize),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sw = strokeWidthDp.toPx()
                                val radius = (size.minDimension - sw) / 2

                                drawCircle(
                                    color = Color(0xFFF3F4F6),
                                    radius = radius,
                                    style = Stroke(width = sw, cap = StrokeCap.Round)
                                )
                                if (progress > 0f) {
                                    drawArc(
                                        brush = Brush.sweepGradient(
                                            listOf(Color(0xFFFBBF24), Color(0xFFFFD700), Color(0xFFF59E0B))
                                        ),
                                        startAngle = -90f,
                                        sweepAngle = 360f * progress,
                                        useCenter = false,
                                        style = Stroke(width = sw, cap = StrokeCap.Round),
                                        size = Size(radius * 2, radius * 2),
                                        topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                                    )
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.offset(y = (-1).dp)
                            ) {
                                ResponsiveText(
                                    text = "$level",
                                    style = levelNumStyle.copy(lineHeight = levelNumStyle.fontSize * 1.1),
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF111827),
                                    maxLines = 1
                                )
                                ResponsiveText(
                                    text = stringResource(R.string.home_lvl),
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF9CA3AF),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Right: User Info - weight(1f) ensures it fills remaining space
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        ResponsiveText(
                            text = userName,
                            style = nameTextStyle,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827),
                            maxLines = 1
                        )
                        if (isGamificationEnabled && !levelTitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(1.dp))
                            ResponsiveText(
                                text = levelTitle.uppercase(),
                                style = titleStyle.copy(letterSpacing = 1.sp),
                                color = Color(0xFFEC4899),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InsightCard(
    insight: InsightCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = (color as androidx.compose.ui.graphics.Color).copy(0.15f),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        text = insight.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Payload Content
            when (val payload = insight.payload) {
                is InsightPayload.TextOnly -> {
                    Text(
                        text = insight.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
                    )
                }
                else -> {} // Gamification handled above
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
    maxLines: Int = 1
) {
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.graphicsLayer { 
            alpha = if (readyToDraw) 1f else 0f 
        },
        style = style.copy(fontSize = fontSize),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        softWrap = false,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.hasVisualOverflow && fontSize.value > 12f) {
                fontSize = (fontSize.value * 0.9f).sp
            } else {
                readyToDraw = true
            }
        }
    )
}
