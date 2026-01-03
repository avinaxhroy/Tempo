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
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.WarmVioletAccent
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import me.avinas.tempo.ui.utils.isSmallScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TempoDarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Ambient Background Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Blue-tinted blob (top-left) for "techy" feel
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF3B82F6).copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = width * 0.8f
                ),
                center = Offset(0f, 0f),
                radius = width * 0.8f
            )

            // Warm Violet blob (bottom-right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(WarmVioletAccent.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(width, height),
                    radius = width * 0.9f
                ),
                center = Offset(width, height),
                radius = width * 0.9f
            )
        }

        // Skip button
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isSmall = isSmallScreen()
            // Header
            Text(
                text = "Here's How It Works",
                style = if (isSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnit(28.sp, 22.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(8.dp, 4.dp)))

            Text(
                text = "No login. No account. Just music.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnit(16.sp, 14.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 16.dp)))

            // Visual Flow: Three connected steps
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(adaptiveSize(16.dp, 8.dp))
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

            Spacer(modifier = Modifier.height(adaptiveSize(48.dp, 24.dp)))

            // Bottom info badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoBadge(emoji = "📱", text = "20+ apps")
                InfoBadge(emoji = "🔒", text = "100% local")
                InfoBadge(emoji = "⚡", text = "Auto-tracks")
            }

            Spacer(modifier = Modifier.height(adaptiveSize(48.dp, 24.dp)))

            // CTA Button
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(adaptiveSize(96.dp, 80.dp)), // Increased height
        backgroundColor = iconColor.copy(alpha = 0.08f), // Subtler background
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(adaptiveSize(56.dp, 40.dp)) // Larger icon container
                    .background(
                        color = iconColor.copy(alpha = 0.2f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(adaptiveSize(32.dp, 24.dp)), // Larger icon
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.width(20.dp)) // More breathing room

            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = if (isSmallScreen()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, // Larger title
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = adaptiveTextUnit(20.sp, 16.sp)
                )
                Spacer(modifier = Modifier.height(adaptiveSize(4.dp, 2.dp)))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnit(14.sp, 12.sp)
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
    Box(
        modifier = Modifier
            .height(adaptiveSize(24.dp, 12.dp))
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
            fontSize = 16.sp,
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
            fontSize = 32.sp // Larger emoji
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}
