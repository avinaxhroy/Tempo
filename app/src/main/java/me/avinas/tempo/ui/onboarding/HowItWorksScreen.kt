package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.isCompactScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage

/**
 * Educational screen explaining how Tempo's notification-based tracking works.
 * Shows a visual flow: Music App → Notification → Tempo → Local Stats
 */
@Composable
fun HowItWorksScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    // Animation for the flow arrows
    val infiniteTransition = rememberInfiniteTransition(label = "flow")
    val arrowProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "arrowProgress"
    )

    // Staggered appearance for steps
    val step1Alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500, delayMillis = 200),
        label = "step1"
    )
    val step2Alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500, delayMillis = 600),
        label = "step2"
    )
    val step3Alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500, delayMillis = 1000),
        label = "step3"
    )

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top flexible spacing
            Spacer(modifier = Modifier.weight(0.08f))
            val isSmall = isSmallScreen()
            // Header
            Text(
                text = "Here's How It Works",
                style = if (isSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnitByCategory(30.sp, 26.sp, 22.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))

            Text(
                text = "No login. No account. Just music.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 13.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

            // Visual Flow: Three connected steps
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(rememberScreenHeightPercentage(0.018f))

            ) {
                // Step 1: Music App
                FlowStep(
                    modifier = Modifier.alpha(step1Alpha),
                    icon = Icons.Default.MusicNote,
                    iconColor = Color(0xFF1DB954), // Spotify green
                    title = "You play music",
                    subtitle = "Spotify, YouTube Music, or any app"
                )

                // Animated connector
                FlowConnector(progress = arrowProgress, alpha = step1Alpha)

                // Step 2: Notification
                FlowStep(
                    modifier = Modifier.alpha(step2Alpha),
                    icon = Icons.Default.Notifications,
                    iconColor = Color(0xFFF59E0B), // Amber
                    title = "Notification appears",
                    subtitle = "Tempo reads only music info"
                )

                // Animated connector
                FlowConnector(progress = arrowProgress, alpha = step2Alpha)

                // Step 3: Stats
                FlowStep(
                    modifier = Modifier.alpha(step3Alpha),
                    icon = Icons.Default.BarChart,
                    iconColor = TempoRed,
                    title = "Stats saved locally",
                    subtitle = "On your phone, nowhere else"
                )
            }

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.035f)))

            // Bottom info badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoBadge(emoji = "📱", text = "20+ apps")
                InfoBadge(emoji = "🔒", text = "100% local")
                InfoBadge(emoji = "⚡", text = "Auto-tracks")
            }

            // Flexible spacer before button
            Spacer(modifier = Modifier.weight(0.1f))

            // CTA Button
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledSize(54.dp, 0.85f, 1.1f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Text(
                    text = "Next",
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Bottom padding
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
        }

        // Skip button - rendered LAST to be on top of all content (z-ordering in Box)
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(
                text = "Skip",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun FlowStep(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String
) {
    // Clamped height ensures consistent appearance across all screen sizes
    val cardHeight = rememberClampedHeightPercentage(0.095f, 70.dp, 90.dp)
    
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight),
        backgroundColor = iconColor.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = adaptiveSizeByCategory(18.dp, 14.dp, 12.dp), vertical = adaptiveSizeByCategory(14.dp, 10.dp, 8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon container
            // Icon container with clamped sizing for consistency
            val iconContainerSize = rememberClampedHeightPercentage(0.058f, 40.dp, 52.dp)
            Box(
                modifier = Modifier
                    .size(iconContainerSize)
                    .background(
                        color = iconColor.copy(alpha = 0.2f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                val innerIconSize = rememberClampedHeightPercentage(0.033f, 22.dp, 30.dp)
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(innerIconSize),
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.width(adaptiveSizeByCategory(20.dp, 16.dp, 12.dp)))

            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = if (isSmallScreen()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(20.sp, 18.sp, 16.sp)
                )
                Spacer(modifier = Modifier.height(adaptiveSizeByCategory(4.dp, 2.dp, 0.dp)))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnitByCategory(15.sp, 13.sp, 12.sp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FlowConnector(
    progress: Float,
    alpha: Float
) {
    // Clamped connector height
    val connectorHeight = rememberClampedHeightPercentage(0.024f, 16.dp, 24.dp)
    Box(
        modifier = Modifier
            .height(connectorHeight)
            .alpha(alpha * 0.6f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val dashWidth = 4.dp.toPx()
            val gapWidth = 4.dp.toPx()
            
            // Draw animated dashed line
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(size.width / 2, 0f),
                end = Offset(size.width / 2, size.height),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dashWidth, gapWidth),
                    phase = progress * (dashWidth + gapWidth) * 2
                )
            )
        }
        
        // Arrow head
        Text(
            text = "↓",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = adaptiveTextUnitByCategory(16.sp, 14.sp, 12.sp),
            modifier = Modifier.scale(1f + (progress * 0.1f))
        )
    }
}

@Composable
private fun InfoBadge(
    emoji: String,
    text: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            fontSize = adaptiveTextUnitByCategory(34.sp, 30.sp, 26.sp) // Adaptive emoji
        )
        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(8.dp, 6.dp, 4.dp)))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = adaptiveTextUnitByCategory(13.sp, 11.sp, 10.sp)
        )
    }
}
