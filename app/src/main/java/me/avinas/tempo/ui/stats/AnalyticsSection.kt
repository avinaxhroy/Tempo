package me.avinas.tempo.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.ui.components.StatCard

@Composable
fun AnalyticsContent(
    analyticsData: AnalyticsUiData?
) {
    if (analyticsData == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Overview Section
        OverviewSection(analyticsData.overview)

        // 2. Hourly Distribution Chart
        if (analyticsData.hourlyDistribution.isNotEmpty()) {
            HourlyChart(analyticsData.hourlyDistribution)
        }

        // 3. Insight Cards
        analyticsData.insightCards.forEach { insight ->
            InsightItem(insight)
        }
    }
}

@Composable
fun OverviewSection(overview: ListeningOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Total Time Card
        StatCard(
            modifier = Modifier.weight(1f),
            gradientColors = listOf(Color(0xFF7F1D1D).copy(alpha = 0.6f), Color(0xFF7F1D1D).copy(alpha = 0.2f))
        ) {
            Column {
                Text(
                    text = "Total Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatListeningTime(overview.totalListeningTimeMs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Play Count Card
        StatCard(
            modifier = Modifier.weight(1f),
            gradientColors = listOf(Color(0xFF1E3A8A).copy(alpha = 0.6f), Color(0xFF1E3A8A).copy(alpha = 0.2f))
        ) {
            Column {
                Text(
                    text = "Plays",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${overview.totalPlayCount}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
    
    // Secondary Stats Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Unique Tracks
        StatCard(modifier = Modifier.weight(1f)) {
             Column {
                Text("Tracks", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("${overview.uniqueTracksCount}", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        // Unique Artists
        StatCard(modifier = Modifier.weight(1f)) {
            Column {
                Text("Artists", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("${overview.uniqueArtistsCount}", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        // Unique Albums
        StatCard(modifier = Modifier.weight(1f)) {
             Column {
                Text("Albums", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("${overview.uniqueAlbumsCount}", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
fun HourlyChart(hourlyData: List<HourlyDistribution>) {
    StatCard {
        Column {
            Text(
                text = "Listening Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            // Simple Bar Chart
            val maxPlays = hourlyData.maxOfOrNull { it.playCount } ?: 1
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // We show 24 bars. 
                // To keep it simple, we just iterate 0..23
                for (hour in 0..23) {
                    val data = hourlyData.find { it.hour == hour }
                    val count = data?.playCount ?: 0
                    val heightRatio = if (maxPlays > 0) count.toFloat() / maxPlays else 0f
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 1.dp)
                            .fillMaxHeight(heightRatio.coerceAtLeast(0.05f)) // Min height for visibility
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFFF42525), Color(0xFFF42525).copy(alpha = 0.3f))
                                )
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12 AM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                Text("6 AM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                Text("12 PM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                Text("6 PM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun InsightItem(insight: InsightCardData) {
    val gradient = when (insight.type) {
        InsightType.MOOD -> listOf(Color(0xFF7C3AED), Color(0xFF4C1D95)) // Violet
        InsightType.BINGE -> listOf(Color(0xFFBE123C), Color(0xFF881337)) // Rose
        InsightType.DISCOVERY -> listOf(Color(0xFF059669), Color(0xFF065F46)) // Emerald
        InsightType.PEAK_TIME -> listOf(Color(0xFFCA8A04), Color(0xFF854D0E)) // Yellow
        else -> listOf(Color(0xFF374151), Color(0xFF1F2937)) // Gray
    }

    StatCard(
        modifier = Modifier.fillMaxWidth(),
        gradientColors = gradient.map { it.copy(alpha = 0.7f) }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                 // You could add an icon here based on type
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
