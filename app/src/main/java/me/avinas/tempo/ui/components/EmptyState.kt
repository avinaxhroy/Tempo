package me.avinas.tempo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.ui.tooling.preview.Preview
import me.avinas.tempo.ui.theme.TempoTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.theme.TempoRed




@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    timeRange: TimeRange? = null,
    onCheckSupportedApps: () -> Unit
) {
    // Container that fills the available space
    Box(
         modifier = modifier.fillMaxSize(),
         contentAlignment = Alignment.Center
    ) {
        if (timeRange != null && timeRange != TimeRange.ALL_TIME && timeRange != TimeRange.TODAY) {
            TimeRangeEmptyState(
                timeRange = timeRange,
                onCheckSupportedApps = onCheckSupportedApps
            )
        } else {
            SetupGuideEmptyState(onCheckSupportedApps = onCheckSupportedApps)
        }
    }
}

@Composable
private fun TimeRangeEmptyState(
    timeRange: TimeRange,
    onCheckSupportedApps: () -> Unit
) {
    // Calculate precise date label for the "Context"
    val contextLabel = remember(timeRange) {
        val now = java.time.LocalDate.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d")
        
        when (timeRange) {
            TimeRange.THIS_WEEK -> {
                // Assuming Monday start for simplicity, or localized
                val startOfWeek = now.minusDays(now.dayOfWeek.value.toLong() - 1)
                val endOfWeek = startOfWeek.plusDays(6)
                "${startOfWeek.format(formatter)} - ${endOfWeek.format(formatter)}"
            }
            TimeRange.THIS_MONTH -> java.time.format.DateTimeFormatter.ofPattern("MMMM").format(now)
            TimeRange.THIS_YEAR -> java.time.format.DateTimeFormatter.ofPattern("yyyy").format(now)
            else -> ""
        }
    }

    // Distinct copy for "Reassurance" vs just "Empty"
    // The goal is to tell the user: "We are looking, just nothing found YET."
    val (icon, title, message) = when (timeRange) {
        TimeRange.THIS_WEEK -> Triple(
            Icons.Rounded.DateRange, 
            "Quiet week so far", 
            "We're listening, but haven't seen any plays yet. Start streaming to see your weekly pulse."
        )
        TimeRange.THIS_MONTH -> Triple(
            Icons.Rounded.DateRange, 
            "Fresh month, fresh start", 
            "Your history is safe. Play music to spark this month's stats."
        )
        TimeRange.THIS_YEAR -> Triple(
            Icons.Rounded.AutoAwesome, 
            "2026 has begun", 
            "Your year in review starts now. Keep listening to build your story."
        )
        else -> Triple(
            Icons.Rounded.DateRange, 
            "No Data Yet", 
            "Start listening to see your stats here."
        )
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp), // Standard outer margins
        backgroundColor = TempoRed.copy(alpha = 0.1f),
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // "System Status" Badge - Reassures the user the app is working
            Surface(
                color = Color(0xFF22C55E).copy(alpha = 0.15f), 
                shape = androidx.compose.foundation.shape.RoundedCornerShape(100),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.3f)),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing Dot effect (Radar Animation)
                    PulsingRadar(modifier = Modifier.size(24.dp), color = Color(0xFF22C55E))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Music Tracking Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF22C55E),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Title & Context
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            // Explicit Date Context
            // Helps user understand "Oh, this is why it's empty, it's a new week"
            if (contextLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = contextLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.5f), 
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Reassuring Body
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Secondary Action - Just in case they suspect a connection issue
            Button(
                onClick = onCheckSupportedApps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed, 
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
               Text("Check Supported Apps", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) 
            }
        }
    }
}

@Composable
private fun SetupGuideEmptyState(
    onCheckSupportedApps: () -> Unit
) {
    // Logic: Provide clear instruction in a premium container.
    // Differentiator: This card is "Louder" (HighProminence) and focused on ACTION.
    
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        backgroundColor = TempoRed.copy(alpha = 0.12f),
        variant = GlassCardVariant.HighProminence, // Stand out more
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header
            Text(
                text = "Getting started with your stats", // Updated title
                style = MaterialTheme.typography.headlineSmall, // Bigger than titleLarge
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Let's get your stats flowing",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Trust Signal
            Surface(
                color = Color(0xFF22C55E).copy(alpha = 0.1f), 
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.2f))
            ) {
                 Text(
                    text = "No account connection needed",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF22C55E),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Visual Steps - Compact & Clean
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StepItem(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic, 
                    text = "Open Spotify, YouTube Music, or others"
                )
                StepItem(
                    icon = Icons.Rounded.Headphones, 
                    text = "Play a few songs — stats appear shortly"
                )
                StepItem(
                    icon = Icons.Rounded.GraphicEq, 
                    text = "Return here to see your insights"
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Primary Action
            Button(
                onClick = onCheckSupportedApps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Check Supported Apps",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StepItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color.White.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = TempoRed, 
                modifier = Modifier.size(18.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = text, 
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun PulsingRadar(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF22C55E)
) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "Radar")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.9f, // Expand outward
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "RadarScale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f, // Fade out
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.FastOutLinearInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "RadarAlpha"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Ripple
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .background(color.copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape)
        )
        // Core Dot (Static)
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EmptyStatePreview() {
    TempoTheme {
        EmptyState(
            modifier = Modifier.fillMaxSize(),
            onCheckSupportedApps = {}
        )
    }
}
