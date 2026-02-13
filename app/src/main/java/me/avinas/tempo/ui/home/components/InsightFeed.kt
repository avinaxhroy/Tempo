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
    onLevelClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Generate colors based on Audio Features
    val energyColor = androidx.compose.ui.graphics.lerp(
        Color(0xFFdb2777), // Low Energy: Pink 600
        Color(0xFFef4444), // High Energy: Red
        energy
    )
    
    val valenceColor = androidx.compose.ui.graphics.lerp(
        Color(0xFFA21CAF), // Low Valence: Plum 700
        Color(0xFFF59E0B), // High Valence: Amber
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
        
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 120.dp) // Reduced end padding to 72dp
        ) {
            // Premium Opaque Card for Profile - Minimalist Pill Edition
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLevelClick)
                    .shadow(
                        elevation = 12.dp, // Softer, more diffuse shadow
                        shape = RoundedCornerShape(100.dp), // Fully rounded / Pill shape
                        spotColor = Color(0x14000000), // Very subtle shadow (8% opacity)
                        ambientColor = Color(0x08000000)
                    ),
                color = Color.White,
                shape = RoundedCornerShape(100.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left: Minimal Level Ring
                    val level = userLevel ?: 0
                    val progress = levelProgress
                    
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // The Ring
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 5.dp.toPx() // Slightly thicker for better visibility
                            val radius = (size.minDimension - strokeWidth) / 2
                            
                            // Track - Very subtle gray
                            drawCircle(
                                color = Color(0xFFF3F4F6), // Gray 100
                                radius = radius,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                            
                            // Progress
                            if (progress > 0f) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        // Refined Gold Gradient
                                        listOf(Color(0xFFFBBF24), Color(0xFFFFD700), Color(0xFFF59E0B))
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                                    size = Size(radius * 2, radius * 2),
                                    topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                                )
                            }
                        }
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.offset(y = (-1).dp) // Visual centering
                        ) {
                            Text(
                                text = "$level",
                                style = MaterialTheme.typography.titleLarge,
                                fontSize = 20.sp, // Fixed size for consistency in the ring
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF111827), // Gray 900
                                lineHeight = 20.sp
                            )
                            Text(
                                text = "LVL",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp, // Slightly larger than 8sp
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9CA3AF), // Gray 400
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Right: User Info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        ResponsiveText(
                            text = userName,
                            style = MaterialTheme.typography.titleLarge, 
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827), // Gray 900
                            maxLines = 1
                        )
                        
                        if (!levelTitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = levelTitle.uppercase(),
                                style = MaterialTheme.typography.labelMedium, // Upgraded from labelSmall
                                color = Color(0xFFEC4899), // Pink 500
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                maxLines = 2, // Allow wrapping
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
