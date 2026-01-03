package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star

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
        insights.forEach { insight ->
            InsightCard(
                insight = insight,
                onClick = {
                    // Logic to handle navigation based on insight type or data
                    // For now, we can expand later.
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
    modifier: Modifier = Modifier
) {
    // Generate colors based on Audio Features - RED SHIFT APPLIED
    // Energy (Y-axis): Low (Plum/Wine) -> High (Red/Rose)
    // Valence (X-axis): Sad (Deep Plum) -> Happy (Amber/Gold)
    
    val energyColor = androidx.compose.ui.graphics.lerp(
        Color(0xFFdb2777), // Low Energy: Pink 600 (Rose) - No Indigo
        Color(0xFFef4444), // High Energy: Red
        energy
    )
    
    val valenceColor = androidx.compose.ui.graphics.lerp(
        Color(0xFFA21CAF), // Low Valence: Plum 700 - No Slate
        Color(0xFFF59E0B), // High Valence: Amber
        valence
    )
    
    val blendedColor = androidx.compose.ui.graphics.lerp(energyColor, valenceColor, 0.5f)
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp) // Reduced height to save space
    ) {
        // Dynamic Nebula Gradient - No hard edges
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // 1. Base Warm Glow (Top-Left) - Softer, less "solid"
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(energyColor.copy(alpha = 0.25f), Color.Transparent), // Reduced opacity
                    center = Offset(width * 0.1f, height * 0.3f), // Moved slightly down
                    radius = width * 0.8f // Larger spread
                ),
                center = Offset(width * 0.1f, height * 0.3f),
                radius = width * 0.8f
            )
            
            // 2. Secondary Vibe Glow (Center-Right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(valenceColor.copy(alpha = 0.15f), Color.Transparent), // Very subtle
                    center = Offset(width * 0.8f, height * 0.6f),
                    radius = width * 0.7f
                ),
                center = Offset(width * 0.8f, height * 0.6f),
                radius = width * 0.7f
            )
            
            // Removed opaque bottom fade "drawRect" to avoid muddy overlay
        }
        
        Column(
            modifier = Modifier
                .statusBarsPadding() // Account for status bar
                .align(Alignment.TopStart)
                .padding(horizontal = 24.dp, vertical = 8.dp) // Aligned up
                .padding(end = 64.dp) // Leave space for settings icon
        ) {
            ResponsiveText(
                text = me.avinas.tempo.utils.TempoCopyEngine.getDynamicGreeting(userName),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            // Vibe Description
            val vibeText = me.avinas.tempo.utils.TempoCopyEngine.getVibeDescription(energy, valence)
            
            Text(
                text = vibeText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f), // Brighter text
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

@Composable
fun InsightCard(
    insight: InsightCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when(insight.type) {
        InsightType.MOOD -> Icons.Filled.Face to Color(0xFF8B5CF6)
        InsightType.PEAK_TIME -> Icons.Filled.DateRange to Color(0xFFF59E0B)
        InsightType.BINGE -> Icons.Filled.Bolt to Color(0xFFEC4899)
        InsightType.DISCOVERY -> Icons.Filled.Celebration to Color(0xFF10B981)
        InsightType.ENERGY -> Icons.Filled.Bolt to Color(0xFFEF4444)
        InsightType.DANCEABILITY -> Icons.Filled.Celebration to Color(0xFFA855F7)
        InsightType.TEMPO -> Icons.Filled.Speed to Color(0xFF06B6D4)
        InsightType.ACOUSTICNESS -> Icons.Filled.Piano to Color(0xFF22C55E)
        InsightType.STREAK -> Icons.Filled.LocalFireDepartment to Color(0xFFF97316) // Orange
        InsightType.GENRE -> Icons.AutoMirrored.Filled.QueueMusic to Color(0xFFE11D48) // Rose
        InsightType.ENGAGEMENT -> Icons.Filled.Favorite to Color(0xFFDB2777) // Pink
        InsightType.RATE_APP -> Icons.Filled.Star to Color(0xFFFFD700) // Gold
        else -> Icons.Filled.Settings to Color.Gray
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = (color as androidx.compose.ui.graphics.Color).copy(0.15f),
        variant = GlassCardVariant.HighProminence,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon & Title
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
            
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
            )
        }
    }
}
