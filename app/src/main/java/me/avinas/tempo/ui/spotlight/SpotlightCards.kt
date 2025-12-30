package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import me.avinas.tempo.ui.components.GlassCard

@Composable
fun DashboardHeader(
    icon: ImageVector,
    title: String,
    iconColor: Color,
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

@Composable
fun DashboardTimeDevotionCard(
    data: SpotlightCardData.TimeDevotion,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = Color(0xFFF42525).copy(alpha = 0.2f),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            DashboardHeader(
                icon = Icons.Outlined.Info,
                title = "Time Devotion",
                iconColor = Color(0xFFF42525),
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "You spent a solid chunk of your year with",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8), // Slate 400
                modifier = Modifier.width(220.dp)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = data.genre,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "${data.percentage}% of your listening time",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF42525),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // Pie Chart Graphic
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(80.dp)
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.05f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24f)
                        )
                        drawArc(
                            color = Color(0xFFF42525),
                            startAngle = -90f,
                            sweepAngle = (data.percentage / 100f) * 360f,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 24f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }
                    Text(
                        text = "${data.percentage}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardEarlyAdopterCard(
    data: SpotlightCardData.EarlyAdopter,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = Color(0xFF3B82F6).copy(alpha = 0.15f),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            DashboardHeader(
                icon = Icons.Outlined.Star,
                title = "Early Adopter",
                iconColor = Color(0xFF60A5FA), // Blue 400
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = "You discovered",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8)
            )
            
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (data.artistImageUrl != null) {
                    AsyncImage(
                        model = data.artistImageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Column {
                    Text(
                        text = data.artistName,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "on ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = data.discoveryDate,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF60A5FA)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardSeasonalAnthemCard(
    data: SpotlightCardData.SeasonalAnthem,
    onShareClick: () -> Unit = {}
) {
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.4f), // Top gloss
            Color.White.copy(alpha = 0.1f)  // Bottom subtle
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(248.dp) // Adjusted height
            .clip(RoundedCornerShape(32.dp))
            .border(
                width = 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(32.dp)
            )
    ) {
        // Background Image
        if (data.albumArtUrl != null) {
            AsyncImage(
                model = data.albumArtUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.6f // Slightly brighter
            )
        }
        
        // Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            TempoDarkBackground.copy(alpha = 0.3f),
                            TempoDarkBackground.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            DashboardHeader(
                icon = Icons.Outlined.DateRange,
                title = if (data.seasonName in setOf("All-Time Anthem", "Early Year", "Mid-Year", "Late Year", "Year-End")) "Your Anthem" else "Seasonal Anthem",
                iconColor = Color(0xFF2DD4BF), // Teal 400
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = "This song defined your",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8)
            )
            
            Text(
                text = data.seasonName,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                color = Color(0xFF2DD4BF)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = data.songTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "by ${data.artistName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
fun DashboardListeningPeakCard(
    data: SpotlightCardData.ListeningPeak,
    onShareClick: () -> Unit = {}
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        backgroundColor = Color(0xFFA855F7).copy(alpha = 0.15f),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            DashboardHeader(
                icon = Icons.Outlined.Star,
                title = "Listening Peak",
                iconColor = Color(0xFFC084FC), // Purple 400
                onShareClick = onShareClick
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Your peak was",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                    
                    Text(
                        text = "${data.peakDate}, ${data.peakTime}",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFC084FC)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Vibing to",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF94A3B8)
                    )
                    
                    Text(
                        text = data.topSongTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                }
                
                // Max Volume Graphic
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFA855F7).copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF581C87).copy(alpha = 0.5f))
                            .border(1.dp, Color(0xFFA855F7).copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = Color(0xFFE9D5FF),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
