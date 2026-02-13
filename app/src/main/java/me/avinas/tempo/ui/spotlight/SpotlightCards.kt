package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.ui.theme.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.random.Random

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.delay

@Composable
fun DashboardHeader(
    icon: ImageVector,
    title: String,
    iconColor: Color,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = iconColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = iconColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        
        if (showShareButton) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// 1. Vedic Astronomy (Samrat Yantra) Functional Clock
@Composable
fun DashboardCosmicClockCard(
    data: SpotlightCardData.CosmicClock,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    // Data-driven function: Point to Peak Activity Time
    val peakHour = remember(data.hourlyLevels) {
        data.hourlyLevels.indices.maxByOrNull { data.hourlyLevels[it] } ?: 12 // Default to noon if empty
    }
    
    // Animation state
    val animatedRotation = remember { Animatable(-180f) }
    val targetRotation = (peakHour - 18) * 15f
    
    LaunchedEffect(targetRotation) {
        delay(300) 
        animatedRotation.animateTo(
            targetValue = targetRotation,
            animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing)
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = TempoDarkSurface.copy(alpha = 0.9f),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DashboardHeader(
                icon = Icons.Outlined.Schedule,
                title = "Circadian Rhythm",
                iconColor = GoldenAmber,
                showShareButton = showShareButton,
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                // Main Instrument Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = center
                    val radius = size.minDimension / 2
                    
                    // 1. The Sky Cycle (Background Ring)
                    val skyGradient = Brush.sweepGradient(
                        0.0f to Color(0xFF0F172A), // Midnight (Top/00:00) - Slate 900
                        0.25f to Color(0xFFFB923C), // Sunrise (06:00) - Orange 400
                        0.5f to Color(0xFFFEF08A), // Noon (12:00) - Yellow 200
                        0.75f to Color(0xFFC084FC), // Sunset (18:00) - Purple 400
                        1.0f to Color(0xFF0F172A), // Midnight
                        center = center
                    )
                    
                    // Draw the Sky Track
                    drawCircle(
                        brush = skyGradient,
                        radius = radius,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    
                    // Fill subtle darkness in the center
                    drawCircle(
                        brush = Brush.radialGradient(
                           colors = listOf(Color(0xFF1E293B), Color(0xFF020617)),
                           center = center,
                           radius = radius
                        ),
                        radius = radius - 4.dp.toPx()
                    )

                    // 2. The Vedic Grid (Muhurtas & Hours)
                    for (i in 0 until 24) {
                        // 00:00 is at Top (-90 deg), but Index 0 in loop.
                        // Formula: (i * 15) - 90
                        val angleDeg = (i * 15f) - 90f
                        val angleRad = angleDeg * (PI / 180f).toFloat()
                        val isMajor = i % 6 == 0 
                        
                        val startR = if (isMajor) radius * 0.88f else radius * 0.92f
                        val endR = radius * 0.98f
                        
                        drawLine(
                            color = if (isMajor) GoldenAmber else Color.White.copy(alpha = 0.2f),
                            start = Offset(center.x + startR * cos(angleRad), center.y + startR * sin(angleRad)),
                            end = Offset(center.x + endR * cos(angleRad), center.y + endR * sin(angleRad)),
                            strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                        )
                        
                        // Numeric Labels for Major Hours
                        if (isMajor) {
                            val text = when(i) {
                                0 -> "0"
                                6 -> "6"
                                12 -> "12"
                                18 -> "18"
                                else -> ""
                            }
                            drawContext.canvas.nativeCanvas.apply {
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.parseColor("#94A3B8") // Slate 400
                                    textSize = 12.dp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                }
                                val labelR = radius * 0.82f
                                drawText(
                                    text, 
                                    center.x + labelR * cos(angleRad), 
                                    center.y + labelR * sin(angleRad) + (textPaint.textSize / 3), 
                                    textPaint
                                )
                            }
                        }
                    }
                    
                    // 30 Muhurta Ticks (Inner Ring)
                    for (j in 0 until 30) {
                         val angleDeg = (j * 12f) - 90f
                         val angleRad = angleDeg * (PI / 180f).toFloat()
                         val isMajor = j % 5 == 0 // Every 5 muhurtas (every 4 hours)
                         
                         // Visual: Small ticks floating inside
                         val markerStartR = radius * 0.65f
                         val markerEndR = radius * 0.70f 
                         
                         drawLine(
                            color = GoldenAmber.copy(alpha = if (isMajor) 0.6f else 0.3f),
                            start = Offset(center.x + markerStartR * cos(angleRad), center.y + markerStartR * sin(angleRad)),
                            end = Offset(center.x + markerEndR * cos(angleRad), center.y + markerEndR * sin(angleRad)),
                            strokeWidth = 1.dp.toPx()
                         )
                    }

                    // 3. The Galactic Rhythm (Data Waveform)
                    val path = Path()
                    val dataRadiusBase = radius * 0.45f // Base of the chart 
                    val dataRadiusMax = radius * 0.75f  // Top of the highest peak
                    
                    val points = mutableListOf<Offset>()
                    
                    data.hourlyLevels.forEachIndexed { index, level ->
                        val angleDeg = (index * 15f) - 90f
                        val angleRad = angleDeg * (PI / 180f).toFloat()
                        
                        // Smooth data a bit? No, raw fidelity is better for "truth".
                        // Normalize 0-100 to 0.0-1.0
                        val normalized = level / 100f
                        val r = dataRadiusBase + (normalized * (dataRadiusMax - dataRadiusBase))
                        
                        val x = center.x + r * cos(angleRad)
                        val y = center.y + r * sin(angleRad)
                        points.add(Offset(x, y))
                    }
                    
                    // Build smooth path (Catmull-Rom or simple LineTo)
                    // Simple LineTo for now to ensure robustness, but closed loop.
                    if (points.isNotEmpty()) {
                        path.moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) {
                            path.lineTo(points[i].x, points[i].y)
                        }
                        path.close() // Close the loop
                        
                        // Fill Area
                        drawPath(
                            path = path,
                            brush = Brush.radialGradient(
                                colors = listOf(GoldenAmber.copy(alpha = 0.5f), Color.Transparent),
                                center = center,
                                radius = radius * 0.8f
                            )
                        )
                        // Stroke Line
                        drawPath(
                            path = path,
                            color = GoldenAmber,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
                
                // 4. The Samrat Yantra Gnomon (Physical Structure)
                Canvas(modifier = Modifier.size(80.dp)) {
                    val gnomonPath = Path().apply {
                        // Top-down view of a triangular ramp/gnomon
                        // Center is (40, 40)
                        val cx = size.width / 2
                        val cy = size.height / 2
                        
                        moveTo(cx, cy - 30.dp.toPx()) // Tip pointing Top
                        lineTo(cx + 10.dp.toPx(), cy + 20.dp.toPx()) // Base Right
                        lineTo(cx, cy + 10.dp.toPx()) // Inner notch
                        lineTo(cx - 10.dp.toPx(), cy + 20.dp.toPx()) // Base Left
                        close()
                    }
                    
                    drawPath(
                        path = gnomonPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF475569), Color(0xFF1E293B))
                        )
                    )
                    drawPath(
                        path = gnomonPath,
                        color = Color.White.copy(alpha=0.1f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw shadow indicator pointing to peak listening hour
                Canvas(modifier = Modifier.fillMaxSize()) {
                     val currentRotation = animatedRotation.value
                     val angleRad = (currentRotation + 180f) * (PI / 180f).toFloat()
                     val shadowLen = size.minDimension / 2 * 0.85f
                     val endX = center.x + shadowLen * cos(angleRad)
                     val endY = center.y + shadowLen * sin(angleRad)
                     
                     drawLine(
                         brush = Brush.linearGradient(
                             colors = listOf(GoldenAmber.copy(alpha=0f), GoldenAmber),
                             start = center,
                             end = Offset(endX, endY)
                         ),
                         start = center,
                         end = Offset(endX, endY),
                         strokeWidth = 3.dp.toPx(),
                         cap = StrokeCap.Round
                     )
                     
                     // Tip
                     drawCircle(
                         color = GoldenAmber,
                         radius = 3.dp.toPx(),
                         center = Offset(endX, endY)
                     )
                }

                // Center Information Display
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(60.dp)) // Push down below Gnomon
                    
                    // Archetype
                    Text(
                        text = data.sunListenerType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFBAE6FD), 
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Peak Time Display
                    val amPm = if (peakHour < 12) "AM" else "PM"
                    val hour12 = if (peakHour % 12 == 0) 12 else peakHour % 12
                    
                    Text(
                        text = "$hour12 $amPm", 
                        style = MaterialTheme.typography.displaySmall.copy(
                            shadow = Shadow(color = GoldenAmber, blurRadius = 24f)
                        ),
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Insight Summary
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 // Day
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Box(modifier = Modifier.size(8.dp).background(GoldenAmber, CircleShape))
                     Spacer(modifier = Modifier.width(8.dp))
                     Column {
                         Text("DAY (${data.dayPercentage}%)", style = MaterialTheme.typography.labelSmall, color = Color.White)
                         Text("Solar Power", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                     }
                 }
                 
                 // Night
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Box(modifier = Modifier.size(8.dp).background(Color(0xFFC084FC), CircleShape))
                     Spacer(modifier = Modifier.width(8.dp))
                     Column(horizontalAlignment = Alignment.End) {
                         Text("NIGHT (${data.nightPercentage}%)", style = MaterialTheme.typography.labelSmall, color = Color.White)
                         Text("Lunar Calm", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                     }
                 }
            }
        }
    }
}




// 2. Weekend Warrior (3D Isometric Constructivism)
@Composable
fun DashboardWeekendWarriorCard(
    data: SpotlightCardData.WeekendWarrior,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = TempoDarkBackground.copy(alpha = 0.95f), // Premium Dark
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DashboardHeader(
                icon = Icons.Outlined.Architecture,
                title = "The Weekly Pulse",
                iconColor = TempoSecondary, // Teal
                showShareButton = showShareButton,
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // Blueprint Grid Background (Subtle)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().alpha(0.1f)) {
                    val gridSize = 20.dp.toPx()
                    for (x in 0 until size.width.toInt() step gridSize.toInt()) {
                        drawLine(
                            color = TempoSecondary, // Teal Grid
                            start = Offset(x.toFloat(), 0f),
                            end = Offset(x.toFloat(), size.height),
                            strokeWidth = 1f
                        )
                    }
                    for (y in 0 until size.height.toInt() step gridSize.toInt()) {
                        drawLine(
                            color = TempoSecondary, // Teal Grid
                            start = Offset(0f, y.toFloat()),
                            end = Offset(size.width, y.toFloat()),
                            strokeWidth = 1f
                        )
                    }
                }

                // 3D Isometric Visualization
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    val centerX = size.width / 2
                    val bottomY = size.height - 20.dp.toPx()
                    val maxBarHeight = size.height * 0.6f
                    val barWidth = 40.dp.toPx()
                    val depth = 20.dp.toPx() // Isometric depth
                    
                    val total = (data.weekdayAverage + data.weekendAverage).coerceAtLeast(1).toFloat()
                    val weekdayH = (data.weekdayAverage / total) * maxBarHeight
                    val weekendH = (data.weekendAverage / total) * maxBarHeight
                    
                    // Helper to draw isometric box
                    fun drawIsoBox(
                        x: Float, 
                        y: Float, 
                        w: Float, // width (front face)
                        h: Float, 
                        d: Float, // depth (side face width approx)
                        frontColor: Color,
                        topColor: Color,
                        sideColor: Color
                    ) {
                        // Math for "fake" isometric/perspective
                        // Front Face
                        drawRect(
                            color = frontColor,
                            topLeft = Offset(x, y - h),
                            size = Size(w, h)
                        )
                        
                        // Top Face (Rhombus-ish / slanted rect)
                        val topPath = Path().apply {
                            moveTo(x, y - h)
                            lineTo(x + d, y - h - (d * 0.5f))
                            lineTo(x + w + d, y - h - (d * 0.5f))
                            lineTo(x + w, y - h)
                            close()
                        }
                        drawPath(path = topPath, color = topColor)
                        
                        // Side Face
                        val sidePath = Path().apply {
                            moveTo(x + w, y - h)
                            lineTo(x + w + d, y - h - (d * 0.5f))
                            lineTo(x + w + d, y - (d * 0.5f)) 
                            lineTo(x + w, y)
                            close()
                        }
                         drawPath(path = sidePath, color = sideColor)
                    }

                    // 1. Weekday Structure (Left, Concrete/Teal)
                    drawIsoBox(
                        x = centerX - barWidth - 20.dp.toPx(),
                        y = bottomY,
                        w = barWidth,
                        h = weekdayH.coerceAtLeast(10f),
                        d = depth,
                        frontColor = Color(0xFF334155), // Slate 700
                        topColor = Color(0xFF475569), // Slate 600
                        sideColor = Color(0xFF1E293B) // Slate 800
                    )

                    // 2. Weekend Structure (Right, Vibrant/Rose)
                     drawIsoBox(
                        x = centerX + 20.dp.toPx(),
                        y = bottomY,
                        w = barWidth,
                        h = weekendH.coerceAtLeast(10f),
                        d = depth,
                        frontColor = TempoError, // Rose 500
                        topColor = Color(0xFFFB7185), // Rose 400
                        sideColor = Color(0xFFBE123C) // Rose 700
                    )
                }
                
                // Labels
                Row(
                    modifier = Modifier.fillMaxSize().padding(bottom = 0.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${data.weekdayAverage}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "WEEKDAY",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 2.sp
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                         Text(
                            text = "${data.weekendAverage}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "WEEKEND",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFDA4AF), // Rose 300
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }
    }
}

// 3. Kintsugi Style (Golden Repair)
@Composable
fun DashboardForgottenFavoriteCard(
    data: SpotlightCardData.ForgottenFavorite,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = TempoDarkSurface.copy(alpha = 0.8f), // Dark Stone
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DashboardHeader(
                icon = Icons.Outlined.History,
                title = "Forgotten Favorite",
                iconColor = Color(0xFFD6D3D1),
                showShareButton = showShareButton,
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Kintsugi Album Art (Broken Pottery)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(110.dp)
                ) {
                   if (data.albumArtUrl != null) {
                        // We use a custom shape to simulate fragments, but strictly clipping to a jagged path is complex in Compose without a custom Shape.
                        // Instead, we overlay the cracks firmly.
                        CachedAsyncImage(
                            imageUrl = data.albumArtUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(4.dp)) // Rough cut
                                .alpha(0.7f), // Faded memory
                            contentScale = ContentScale.Crop
                        )
                   }
                   
                   // Gold Cracks Overlay (Jagged / Randomness)
                   androidx.compose.foundation.Canvas(modifier = Modifier.size(110.dp)) {
                       val path = Path().apply {
                           // Jagged "Lightning" shape for realistic ceramic crack
                           moveTo(0f, size.height * 0.4f)
                           lineTo(size.width * 0.2f, size.height * 0.3f)
                           lineTo(size.width * 0.35f, size.height * 0.5f)
                           lineTo(size.width * 0.5f, size.height * 0.45f)
                           lineTo(size.width * 0.65f, size.height * 0.6f)
                           lineTo(size.width * 0.8f, size.height * 0.5f)
                           lineTo(size.width, size.height * 0.65f)
                       }
                       
                       // Glow (Luminescence)
                       drawPath(
                           path = path,
                           color = GoldenAmber.copy(alpha = 0.4f),
                           style = androidx.compose.ui.graphics.drawscope.Stroke(
                               width = 6.dp.toPx(),
                               cap = StrokeCap.Round,
                               join = StrokeJoin.Round
                           )
                       )
                       
                       // The Gold Fill
                       drawPath(
                           path = path,
                           brush = Brush.linearGradient(
                               colors = listOf(GoldenAmber, Color(0xFFB45309), GoldenAmber) // Shimmer
                           ),
                           style = androidx.compose.ui.graphics.drawscope.Stroke(
                               width = 2.dp.toPx(),
                               cap = StrokeCap.Butt,
                               join = StrokeJoin.Round
                           )
                       )
                       
                       // Secondary fine crack
                       val path2 = Path().apply {
                           moveTo(size.width * 0.35f, size.height * 0.5f)
                           lineTo(size.width * 0.4f, size.height)
                       }
                       drawPath(
                           path = path2,
                           color = GoldenAmber.copy(alpha = 0.8f),
                           style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                       )
                   }
                }
                
                Spacer(modifier = Modifier.width(20.dp))
                
                Column {
                    // "Days Lost" - Calligraphic Gold Centerpiece
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${data.daysSinceLastPlay}",
                            style = MaterialTheme.typography.displayMedium.copy(
                                brush = Brush.verticalGradient(
                                    colors = listOf(GoldenAmber, Color(0xFF92400E))
                                )
                            ),
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "days lost",
                            style = MaterialTheme.typography.labelMedium,
                            color = GoldenAmber.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = data.songTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "Restored from memory",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA8A29E)
                    )
                }
            }
        }
    }
}

// 4. Op Art Style (Optical Illusion / Tunnel)
@Composable
fun DashboardDeepDiveCard(
    data: SpotlightCardData.DeepDive,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = TempoDarkBackground, // Deep Void
        contentPadding = PaddingValues(24.dp)
    ) {
        Column {
            DashboardHeader(
                icon = Icons.Outlined.PlayArrow,
                title = "Sonic Immersion", 
                iconColor = ElectricBlue,
                showShareButton = showShareButton,
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Op Art Tunnel Background (Animated)
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tunnel")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(20000, easing = androidx.compose.animation.core.LinearEasing)
                    ),
                    label = "rotation"
                )
                
                val pulse by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { 
                            rotationZ = rotation 
                            scaleX = pulse
                            scaleY = pulse
                        }
                ) {
                     val centerX = size.width / 2
                     val centerY = size.height / 2
                     val maxRadius = size.maxDimension / 2
                     
                     // Warped Concentric Rings (Bridget Riley style)
                     for (i in 0 until 12) {
                         val progress = i / 12f
                         val radius = maxRadius * (1f - progress)
                         
                         // Sine wave distortion
                         val path = Path()
                         val steps = 60
                         for (j in 0..steps) {
                             val theta = (j.toFloat() / steps) * 2 * PI
                             val distortion = sin(theta * 6) * (10.dp.toPx() * progress) // Wavy effect increases towards center? Or edge?
                             val r = radius + distortion.toFloat()
                             
                             val x = centerX + r * cos(theta).toFloat()
                             val y = centerY + r * sin(theta).toFloat()
                             
                             if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                         }
                         path.close()
                         
                         drawPath(
                             path = path,
                             color = if (i % 2 == 0) GlassWhite else ElectricBlue.copy(alpha=0.2f),
                             style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()) // Thin precise lines
                         )
                     }
                     
                     // Radial Vignette/Depth
                     drawCircle(
                         brush = Brush.radialGradient(
                             colors = listOf(
                                 Color.Transparent,
                                 Color.Black.copy(alpha = 0.9f)
                             ),
                             center = Offset(centerX, centerY),
                             radius = maxRadius
                         ),
                         radius = maxRadius,
                         center = Offset(centerX, centerY)
                     )
                }
                
                // Singularity Data (The Light at the End)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF38BDF8).copy(alpha=0.3f), Color.Transparent)
                            )
                        )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${data.durationMinutes}",
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Black
                        )
                         Text(
                            text = "MINUTES",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
             
             // Time Context (Curved or Bottom)
             Text(
                text = "${data.timeOfDay} • ${data.date}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBAE6FD),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
            )
        }
    }
}

// 5. Newspaper Clipping Style (Editorial / Vintage Print)
@Composable
fun DashboardNewObsessionCard(
    data: SpotlightCardData.NewObsession,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    // Newsprint color palette
    val paperColor = Color(0xFFF5F0E6) // Aged paper
    val inkColor = Color(0xFF1A1A1A) // Deep ink black
    val accentRed = Color(0xFFB91C1C) // Classic newspaper red
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = paperColor,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row with masthead styling
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(inkColor)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "THE DAILY ROTATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = paperColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Est. ${data.daysKnown} days ago",
                        style = MaterialTheme.typography.labelSmall,
                        color = paperColor.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                }
                
                if (showShareButton) {
                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier
                            .size(32.dp)
                            .background(paperColor.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = paperColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            // Breaking News Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accentRed)
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = "★ BREAKING: NEW OBSESSION DETECTED ★",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Main Content Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Artist Image with halftone effect (SINGLE IMAGE LOAD)
                Box(
                    modifier = Modifier
                        .weight(0.45f)
                        .aspectRatio(0.85f)
                        .clip(RoundedCornerShape(4.dp))
                        .border(2.dp, inkColor, RoundedCornerShape(4.dp))
                ) {
                    if (data.artistImageUrl != null) {
                        // Single image with newsprint desaturation
                        CachedAsyncImage(
                            imageUrl = data.artistImageUrl,
                            contentDescription = data.artistName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(
                                ColorMatrix().apply { 
                                    setToSaturation(0.15f) // Low saturation for vintage look
                                }
                            )
                        )
                    }
                    
                    // Halftone Dot Overlay (newsprint texture)
                    Canvas(modifier = Modifier.fillMaxSize().alpha(0.15f)) {
                        val dotSpacing = 4.dp.toPx()
                        val maxDotSize = 2.dp.toPx()
                        
                        for (x in 0 until size.width.toInt() step dotSpacing.toInt()) {
                            for (y in 0 until size.height.toInt() step dotSpacing.toInt()) {
                                // Vary dot size slightly for authentic halftone
                                val variation = ((x + y) % 3) * 0.3f
                                drawCircle(
                                    color = inkColor,
                                    radius = maxDotSize * (0.5f + variation),
                                    center = Offset(x.toFloat(), y.toFloat())
                                )
                            }
                        }
                    }
                    
                    // Photo credit line
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(inkColor.copy(alpha = 0.7f))
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "Photo: Archive",
                            style = MaterialTheme.typography.labelSmall,
                            color = paperColor,
                            fontSize = 8.sp
                        )
                    }
                }
                
                // Article Content
                Column(
                    modifier = Modifier.weight(0.55f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Artist Name as Headline
                    Text(
                        text = data.artistName.uppercase(),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontWeight = FontWeight.Black,
                            lineHeight = 28.sp
                        ),
                        color = inkColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Divider line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(inkColor)
                    )
                    
                    // Stats as article excerpt
                    Text(
                        text = "Sources confirm this artist has captured ${data.percentOfListening}% of total listening time, marking an unprecedented rise in the charts.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            lineHeight = 16.sp
                        ),
                        color = inkColor.copy(alpha = 0.8f)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Play count as bold stat
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(inkColor)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLAYS",
                            style = MaterialTheme.typography.labelSmall,
                            color = paperColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${data.playCount}",
                            style = MaterialTheme.typography.titleLarge,
                            color = paperColor,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            
            // Footer with torn edge effect
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                // Torn paper edge (jagged line)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        var x = 0f
                        while (x < size.width) {
                            val peakHeight = (5..15).random().toFloat()
                            lineTo(x + 8f, size.height - peakHeight)
                            lineTo(x + 16f, size.height)
                            x += 16f
                        }
                        lineTo(size.width, 0f)
                        lineTo(0f, 0f)
                        close()
                    }
                    drawPath(path, color = paperColor)
                }
                
                // Page number / edition
                Text(
                    text = "Page A1 • Music Section",
                    style = MaterialTheme.typography.labelSmall,
                    color = inkColor.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

// 6. Listening Peak (Harmonic Resonance / Generative Line Art)
@Composable
fun DashboardListeningPeakCard(
    data: SpotlightCardData.ListeningPeak,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = TempoDarkBackground, // Deep Void
        contentPadding = PaddingValues(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            // 1. Background - Pure Void (removed grid for cleaner look)

            // 2. Harmonic Waves (High Fidelity)
            // Increased density and smoother curves for "Polished" look
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                val numLines = 45 // Increased density
                val centerY = size.height * 0.55f // Slightly higher to account for displacement
                val spacing = 3.5.dp.toPx() // Tighter spacing
                
                val peakIntensity = (data.totalMinutes / 120f).coerceIn(0.8f, 1.3f) 
                
                for (i in 0 until numLines) {
                    val lineY = centerY - (numLines / 2f * spacing) + (i * spacing)
                    val progress = i / numLines.toFloat()
                    
                    // Calculate verticalFactor (Center lines are the "peak" subject)
                    // Ease the factor for smoother drop-off
                    val verticalFactor = 1f - abs(progress - 0.5f) * 2f 
                    val smoothFactor = verticalFactor.pow(1.5f)

                    val path = Path()
                    path.moveTo(0f, lineY)
                    
                    val steps = 180 // Smoother curves
                    val stepX = size.width / steps
                    
                    for (j in 0..steps) {
                        val x = j * stepX
                        val normX = j / steps.toFloat()
                        
                        // Main Peak (Gaussian)
                        val centerMean = 0.5f
                        val stdDev = 0.12f // Tighter peak
                        val gaussian = exp(-0.5 * ((normX - centerMean) / stdDev).pow(2)).toFloat()
                        
                        // Harmonics (Secondary waves) for "Resonance" feel
                        val harmonic1 = sin(normX * PI.toFloat() * 4f + (i * 0.2f)) * 0.1f // Slow wave
                        val harmonic2 = sin(normX * 50f + i) * 0.02f // Fine noise
                        
                        // Combined displacement
                        val displacement = -1 * (gaussian + harmonic1 + harmonic2) * 70.dp.toPx() * peakIntensity * smoothFactor
                        
                        path.lineTo(x, lineY + displacement)
                    }
                    
                    // Polished Gradient Stroke
                    // Center lines are brighter/blue, edges darker/purple
                    val alpha = (0.3f + (smoothFactor * 0.7f)).coerceIn(0f, 1f)
                    
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF0F172A).copy(alpha = 0f), // Transparent edges
                                Color(0xFF38BDF8), // Sky Blue
                                Color(0xFF818CF8), // Indigo
                                Color(0xFFC084FC), // Purple
                                Color(0xFF0F172A).copy(alpha = 0f)
                            )
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = (1.2f + (smoothFactor * 0.5f)).dp.toPx(), // Varies slightly with depth
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        ),
                        alpha = alpha
                    )
                }
            }

            // 3. Foreground Content - Refined Typography
            Column(modifier = Modifier.fillMaxWidth()) {
                DashboardHeader(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Harmonic Resonance",
                    iconColor = Color(0xFF38BDF8),
                    showShareButton = showShareButton,
                    onShareClick = onShareClick
                )
                
                Spacer(modifier = Modifier.weight(1f))
            
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    // Refined Peak Time
                    Row(verticalAlignment = Alignment.Bottom) {
                         Text(
                            text = data.peakTimeRange,
                            style = MaterialTheme.typography.displaySmall.copy( // Smaller but refined
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                fontWeight = FontWeight.Light,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Minimalist Intensity Display
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "INTENSITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassWhite.copy(alpha=0.5f),
                            letterSpacing = 3.sp,
                            fontSize = 10.sp
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))

                        // Styled Value
                        Text(
                            text = "${data.totalMinutes} MIN",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// 7. Early Adopter -> "Interstellar Passport" (Sci-Fi / Space ID)

// 7. Early Adopter -> "Interstellar Passport" (Sci-Fi / Space ID)
@Composable
fun DashboardEarlyAdopterCard(
    data: SpotlightCardData.EarlyAdopter,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    var showQrDialog by remember { mutableStateOf(false) }

    if (showQrDialog) {
        // Safe encoding for generic search
        val encodedArtist = try {
            java.net.URLEncoder.encode(data.artistName, "UTF-8")
        } catch(e: Exception) { data.artistName }
        
        val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=500x500&color=38BDF8&bgcolor=020617&data=https%3A%2F%2Fopen.spotify.com%2Fsearch%2F$encodedArtist"
        
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            containerColor = Color(0xFF020617),
            textContentColor = Color(0xFFE2E8F0),
            titleContentColor = Color(0xFFE2E8F0),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(1.dp, Color(0xFF38BDF8).copy(alpha=0.3f), RoundedCornerShape(24.dp)),
            title = {
                Text(
                    "ACCESS VERIFICATION", 
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                         modifier = Modifier
                             .size(240.dp)
                             .clip(RoundedCornerShape(12.dp))
                             .border(1.dp, Color(0xFF38BDF8).copy(alpha=0.5f), RoundedCornerShape(12.dp))
                             .background(Color.Black), // Contrast for QR
                         contentAlignment = Alignment.Center
                    ) {
                        // Scanline effect background
                         androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().alpha(0.1f)) {
                            val lineH = 2.dp.toPx()
                            val gap = 2.dp.toPx()
                            for(i in 0 until (size.height / (lineH+gap)).toInt()) {
                                drawRect(Color(0xFF38BDF8), topLeft = Offset(0f, i*(lineH+gap)), size = Size(size.width, lineH))
                            }
                         }
                         
                        CachedAsyncImage(
                            imageUrl = qrUrl,
                            contentDescription = "QR Code",
                            modifier = Modifier.size(220.dp).padding(10.dp),
                            contentScale = ContentScale.Fit
                        )
                        
                        // Corner brackets
                        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                             // Top Left
                             Box(modifier = Modifier.align(Alignment.TopStart).size(20.dp, 2.dp).background(Color(0xFF38BDF8)))
                             Box(modifier = Modifier.align(Alignment.TopStart).size(2.dp, 20.dp).background(Color(0xFF38BDF8)))
                             // Bottom Right
                             Box(modifier = Modifier.align(Alignment.BottomEnd).size(20.dp, 2.dp).background(Color(0xFF38BDF8)))
                             Box(modifier = Modifier.align(Alignment.BottomEnd).size(2.dp, 20.dp).background(Color(0xFF38BDF8)))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "SCAN FOR ACCESS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 2.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("CLOSE", color = Color(0xFF38BDF8))
                }
            }
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // Smooth curve like a modern ID
        backgroundColor = Color(0xFF020617), // Deep Space Blue/Black
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            // 1. Background Atmosphere (Nebula)
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.8f, size.height * 0.2f)
                // Nebula Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF6366F1).copy(alpha = 0.3f), // Indigo
                            Color(0xFF8B5CF6).copy(alpha = 0.1f), // Violet
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.width * 0.6f
                    ),
                    center = center,
                    radius = size.width * 0.6f
                )
                
                // Starfield
                val randomSeed = Random(data.artistName.hashCode())
                for (i in 0..30) {
                    val x = randomSeed.nextFloat() * size.width
                    val y = randomSeed.nextFloat() * size.height
                    val starSize = randomSeed.nextFloat() * 2.dp.toPx()
                    val opacity = randomSeed.nextFloat() * 0.8f
                    drawCircle(
                        color = Color.White.copy(alpha=opacity),
                        radius = starSize / 2,
                        center = Offset(x, y)
                    )
                }
                
                // Tech Grid / Overlay Data
                val gridColor = Color(0xFF94A3B8).copy(alpha = 0.1f)
                val lineH = size.height * 0.75f
                drawLine(gridColor, Offset(0f, lineH), Offset(size.width, lineH), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(size.width * 0.35f, 0f), Offset(size.width * 0.35f, size.height), strokeWidth = 1.dp.toPx())
            }

            // 2. Content Layout
            Row(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                // Left Column: Photo & ID
                Column(
                    modifier = Modifier.weight(0.35f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Passport Photo Frame
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f) // 4:5 Photo ratio
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(4.dp)) // Silver Border
                            .background(Color(0xFF1E293B))
                    ) {
                         if (data.artistImageUrl != null) {
                             CachedAsyncImage(
                                 imageUrl = data.artistImageUrl,
                                 contentDescription = null,
                                 modifier = Modifier.fillMaxSize(),
                                 contentScale = ContentScale.Crop,
                                 colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) }) // Desaturated Sci-Fi look
                             )
                         }
                         // "Holo" Overlay on photo
                         Box(modifier = Modifier.fillMaxSize().background(
                             brush = Brush.linearGradient(
                                 colors = listOf(
                                     Color.Transparent,
                                     Color(0xFF38BDF8).copy(alpha=0.2f),
                                     Color.Transparent
                                 ),
                                 start = Offset(0f, 0f),
                                 end = Offset(100f, 100f) // Approximate diagonal
                             )
                         ))
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // ID Barcode (Fake visual) - CLICKABLE TO SHOW QR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clickable { showQrDialog = true }, // Interaction added
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        repeat(10) {
                             Box(modifier = Modifier.width(Random.nextInt(2, 6).dp).fillMaxHeight().background(Color(0xFF475569)))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(20.dp))
                
                // Right Column: Data Fields
                Column(
                    modifier = Modifier.weight(0.65f).fillMaxHeight()
                ) {
                    // Header
                    Text(
                        text = "TEMPO VANGUARD",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8), // Slate 400
                        letterSpacing = 2.sp,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "ACCESS PASS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF8FAFC), // Slate 50
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Field: SUBJECT
                    Text("SUBJECT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontSize = 8.sp)
                    Text(
                        text = data.artistName.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE2E8F0),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Field: CLASS
                    Text("CLASS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontSize = 8.sp)
                    Text(
                        text = "EARLY ADOPTER (FOUNDER)", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = Color(0xFFA7F3D0), // Soft Green/Teal
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Field: DISCOVERY / ISSUED
                    Text("FIRST CONTACT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontSize = 8.sp)
                    Text(
                        text = data.discoveryDate, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = Color(0xFFE2E8F0),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            
            // 3. Stamped Verification Seal (Overlaid)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .rotate(-15f)
            ) {
                // We draw a custom "Rubber Stamp" effect
                 androidx.compose.foundation.Canvas(modifier = Modifier.size(100.dp)) {
                     val color = Color(0xB3F43F5E) // Rose 500, 70% opacity
                     
                     // Outer Ring
                     drawCircle(color = color, style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)))
                     // Inner Ring
                     drawCircle(color = color, radius = size.minDimension/2 * 0.9f, style = Stroke(width = 1.dp.toPx()))
                     
                     // Text
                     drawContext.canvas.nativeCanvas.apply {
                         val paint = android.graphics.Paint().apply {
                             this.color = android.graphics.Color.parseColor("#F43F5E")
                             textSize = 12.dp.toPx()
                             textAlign = android.graphics.Paint.Align.CENTER
                             typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                             alpha = 180
                         }
                         drawText("APPROVED",center.x, center.y + 4.dp.toPx(), paint)
                     }
                 }
            }
            
            // Share Button (Top Right) -> Now acts as PRIMARY QR Trigger too if desired, or keep as Share
            // User asked: "when clicked on that icon" -> Top Right Icon is Icons.Outlined.QrCode2
            IconButton(
                 onClick = { showQrDialog = true }, // Trigger QR Dialog
                 modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
             ) {
                 Icon(
                     imageVector = Icons.Outlined.QrCode2, // Techy icon
                     contentDescription = "Show QR Code",
                     tint = Color.White.copy(alpha=0.5f),
                     modifier = Modifier.size(20.dp)
                 )
             }
        }
    }
}

// 8. Artist Loyalty -> "The Deep Cut" (Vinyl Stack)
@Composable
fun DashboardArtistLoyaltyCard(
    data: SpotlightCardData.ArtistLoyalty,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), 
        backgroundColor = Color(0xFF1E1E1E), 
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
             // 1. Warm "Listening Room" Background
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                 drawRect(
                     brush = Brush.verticalGradient(
                         colors = listOf(Color(0xFF2C1E18), Color(0xFF0F0A08)) // Dark Amber/Wood tone
                     )
                 )
                 // "Dust" particles
                 val random = Random(123)
                 for(i in 0..50) {
                     drawCircle(
                         color = Color(0xFFFCD34D).copy(alpha = 0.1f),
                         radius = random.nextFloat() * 2.dp.toPx(),
                         center = Offset(random.nextFloat()*size.width, random.nextFloat()*size.height)
                     )
                 }
            }

            // 2. Vinyl Stack Visualization
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, bottom = 24.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                 // Stack of records
                 // We draw multiple "spines" of records
                 val stackCount = (data.uniqueTrackCount / 2).coerceIn(3, 15)
                 
                 Column(verticalArrangement = Arrangement.spacedBy((-12).dp)) {
                     repeat(stackCount) { index ->
                         val isTop = index == stackCount - 1
                         val width = 180.dp + (index * 2).dp // Slight variation
                         val color = if (isTop) Color(0xFF1DB954) else Color.White.copy(alpha = 0.1f + (index * 0.05f))
                         
                         Box(
                             modifier = Modifier
                                 .width(width)
                                 .height(24.dp)
                                 // Rotated slightly for chaotic stack look
                                 .rotate(if (index % 2 == 0) -1f else 1f)
                                 .clip(RoundedCornerShape(2.dp))
                                 .background(
                                     brush = Brush.horizontalGradient(
                                         0.0f to Color(0xFF121212),
                                         0.1f to color.copy(alpha=0.5f), // Spine highlight
                                         0.9f to Color(0xFF121212)
                                     )
                                 )
                                 .border(0.5.dp, Color.White.copy(alpha=0.2f), RoundedCornerShape(2.dp))
                         )
                     }
                 }
            }
            
            // 3. Info Content (Right Side)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(180.dp)
                    .padding(top = 24.dp, end = 20.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.End
            ) {
                DashboardHeader(
                    title = "THE DEEP CUT",
                    icon = Icons.Filled.Album,
                    iconColor = Color(0xFFF59E0B), // Amber 500
                    showShareButton = showShareButton,
                    onShareClick = onShareClick
                )
                
                Spacer(modifier = Modifier.weight(1f))
            
                Text(
                    text = "${data.uniqueTrackCount}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B)
                )
                Text(
                    text = "UNIQUE TRACKS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFCD34D).copy(alpha=0.7f),
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "You don't just play the hits. You explored ${data.artistName}'s entire catalog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha=0.8f),
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// 9. Discovery -> "The Sonic Horizon" (Event Horizon)
@Composable
fun DashboardDiscoveryCard(
    data: SpotlightCardData.Discovery,
    showShareButton: Boolean = true,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = Color.Black,
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            // 1. Event Horizon Visualization
             androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                 val centerX = size.width * 0.5f
                 val centerY = size.height * 0.6f
                 val maxRadius = size.width * 0.8f
                 
                 // Dynamic color based on type
                 val coreColor = when(data.discoveryType) {
                     "The Explorer" -> Color(0xFF8B5CF6) // Violet (New)
                     "The Time Traveler" -> Color(0xFFF59E0B) // Amber (Old/Gold)
                     else -> Color(0xFF06B6D4) // Cyan (Balanced)
                 }

                 // A. Accretion Disk (Swirling gradients)
                 withTransform({
                     translate(centerX, centerY)
                     scale(1f, 0.4f) // Flatten perspective
                     rotate(degrees = 20f, pivot = Offset.Zero) // Tilt
                 }) {
                     // Outer Glow
                     drawCircle(
                         brush = Brush.radialGradient(
                             colors = listOf(coreColor.copy(alpha=0.6f), Color.Transparent),
                             radius = maxRadius
                         ),
                         radius = maxRadius
                     )
                     
                     // The Ring System (Representation of Repeats vs New)
                     val ringWidth = 40.dp.toPx()
                     drawCircle(
                         brush = Brush.sweepGradient(
                             colors = listOf(
                                 coreColor,
                                 Color.White,
                                 coreColor.copy(alpha=0.5f),
                                 coreColor
                             )
                         ),
                         radius = maxRadius * 0.6f,
                         style = Stroke(width = ringWidth)
                     )
                     
                     // Gaps/Debris in ring based on Variety score
                     if (data.varietyScore > 50) {
                         drawCircle(
                            color = Color.Black.copy(alpha=0.3f),
                            radius = maxRadius * 0.6f,
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 20f), 0f))
                         )
                     }
                 }
                 
                 // B. The Singularity (Black Hole Center) - Pure Black
                 drawCircle(
                     color = Color.Black,
                     radius = maxRadius * 0.25f,
                     center = Offset(centerX, centerY)
                 )
                 
                 // C. Photon Ring (Thin bright border around black hole)
                 drawCircle(
                     color = Color.White.copy(alpha=0.8f),
                     radius = maxRadius * 0.255f,
                     center = Offset(centerX, centerY),
                     style = Stroke(width = 2.dp.toPx())
                 )
            }
            
            // 2. Info Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                 DashboardHeader(
                    title = "THE HORIZON",
                    icon = Icons.Filled.AllInclusive, // Infinity symbol
                    iconColor = Color.White,
                    showShareButton = showShareButton,
                    onShareClick = onShareClick
                )
                
                Spacer(modifier = Modifier.weight(1f))
            
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                         Text(
                            text = "${data.newContentPercentage}%",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "NEW DISCOVERIES",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha=0.7f),
                            letterSpacing = 1.sp
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = data.discoveryType.uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = when(data.discoveryType) {
                                 "The Explorer" -> Color(0xFFA78BFA)
                                 "The Time Traveler" -> Color(0xFFFBD38D)
                                 else -> Color(0xFF67E8F9)
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if(data.topNewArtist != null) "Top find: ${data.topNewArtist}" else "Expanding horizons",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha=0.5f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

