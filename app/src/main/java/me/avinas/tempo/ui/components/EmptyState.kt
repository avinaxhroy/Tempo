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
import androidx.compose.material.icons.rounded.QueueMusic
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


enum class GhostScreenType {
    HOME,
    STATS
}

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    timeRange: TimeRange? = null,
    type: GhostScreenType = GhostScreenType.HOME,
    onCheckSupportedApps: () -> Unit
) {
    // Container that fills the available space
    Box(
        modifier = modifier
    ) {
        // 1. Ghost Background Layer (The "Promise")
        // Sit explicitly behind everything
        GhostFutureBackground(type = type)

        // 2. Foreground Content Layer
        // Centered as before
        Box(
             modifier = Modifier.fillMaxSize(),
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
        backgroundColor = Color.White.copy(alpha = 0.05f),
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
                    icon = Icons.Rounded.QueueMusic, 
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

@Composable
fun GhostFutureBackground(type: GhostScreenType) {
    // A blurred "Skeleton" of the best version of the UI
    // It sits behind the glass cards to give depth and "promise"
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Remove hard padding to allow blur to bleed
            // .padding(16.dp) 
            
            // Add a vertical fade mask to blend the top and bottom edges seamlessly
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithCache {
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.Transparent,
                    0.15f to androidx.compose.ui.graphics.Color.Black,
                    0.85f to androidx.compose.ui.graphics.Color.Black,
                    1f to androidx.compose.ui.graphics.Color.Transparent
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
            }
            .blur(radius = 16.dp) 
    ) {
        when (type) {
            GhostScreenType.HOME -> HomeGhostLayout()
            GhostScreenType.STATS -> StatsGhostLayout()
        }
    }
}

@Composable
fun HomeGhostLayout() {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // 1. High-Fidelity Ghost Hero Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header (Greeting)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Fake Greeting Text
                    Box(modifier = Modifier.width(140.dp).height(24.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(4.dp)))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Hero Time Display (Big Numbers)
                Box(modifier = Modifier.width(180.dp).height(48.dp).background(Color.White.copy(alpha=0.2f), RoundedCornerShape(8.dp)))
                
                Spacer(modifier = Modifier.height(8.dp))

                // Badge Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Fake Badge
                    Box(modifier = Modifier.size(width = 60.dp, height = 24.dp).background(Color(0xFF4ADE80).copy(alpha=0.2f), RoundedCornerShape(8.dp)))
                    Spacer(modifier = Modifier.width(8.dp))
                    // Fake Subtitle
                    Box(modifier = Modifier.width(100.dp).height(16.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(4.dp)))
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Chart Area - Fake TrendLine
                Row(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val heights = listOf(0.4f, 0.6f, 0.3f, 0.8f, 0.5f, 0.9f, 0.6f)
                    heights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(h)
                                .padding(horizontal = 4.dp)
                                .background(me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f), CircleShape)
                        )
                    }
                }
            }
        }

        // 2. High-Fidelity Ghost Spotlight Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.15f),
            contentPadding = PaddingValues(24.dp)
        ) {
             Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon Box
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.2f), CircleShape)
                )
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Title
                    Box(modifier = Modifier.width(160.dp).height(24.dp).background(Color.White.copy(alpha=0.2f), RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.height(4.dp))
                    // Subtitle
                    Box(modifier = Modifier.width(100.dp).height(16.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(4.dp)))
                }
                
                // Arrow
                Box(modifier = Modifier.size(24.dp).background(Color.White.copy(alpha=0.1f), CircleShape))
            }
        }

        // 3. Quick Stats Row (Ghost)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(2) {
                GlassCard(
                    modifier = Modifier.weight(1f).height(120.dp),
                    backgroundColor = Color.White.copy(alpha = 0.05f)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                         Box(modifier = Modifier.size(32.dp).background(Color.White.copy(alpha=0.1f), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
fun StatsGhostLayout() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // 1. Tab Selector Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(100.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Hero Item (#1 Rank) - High Fidelity
        GlassCard(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentPadding = PaddingValues(16.dp),
             // Mimic Gold Gradient
            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.15f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                 // Rank #1
                 Text(
                    text = "1",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 16.dp)
                 )
                 
                 // Art
                 Box(
                     modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFFF59E0B).copy(alpha=0.3f), CircleShape)
                 )
                 
                 Spacer(modifier = Modifier.width(16.dp))
                 
                 Column {
                     Box(modifier = Modifier.width(120.dp).height(20.dp).background(Color.White.copy(alpha=0.2f), RoundedCornerShape(4.dp)))
                     Spacer(modifier = Modifier.height(8.dp))
                     Box(modifier = Modifier.width(80.dp).height(14.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(4.dp)))
                 }
            }
        }

        // 3. List Items (#2, #3, ...) - High Fidelity
        repeat(5) { i ->
            GlassCard(
                 modifier = Modifier.fillMaxWidth().height(72.dp),
                 contentPadding = PaddingValues(12.dp),
                 backgroundColor = Color.White.copy(alpha = 0.05f)
            ) {
                 Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "${i + 2}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.width(24.dp)
                    )
                    
                    Box(modifier = Modifier.size(48.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(8.dp)))
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Box(modifier = Modifier.width(100.dp).height(16.dp).background(Color.White.copy(alpha=0.2f), RoundedCornerShape(4.dp)))
                         Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.width(60.dp).height(12.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun GhostFutureBackgroundPreview() {
    TempoTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                 GhostFutureBackground(GhostScreenType.HOME)
            }
            Box(modifier = Modifier.weight(1f)) {
                 GhostFutureBackground(GhostScreenType.STATS)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EmptyStatePreview() {
    TempoTheme {
        EmptyState(
            modifier = Modifier.fillMaxSize(),
            onCheckSupportedApps = {},
            type = GhostScreenType.HOME
        )
    }
}
