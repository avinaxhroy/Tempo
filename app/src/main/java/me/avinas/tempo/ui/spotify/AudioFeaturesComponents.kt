package me.avinas.tempo.ui.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.remote.spotify.SpotifyAudioFeatures
import kotlin.math.roundToInt

/**
 * UI components for displaying Spotify audio features.
 * 
 * These components are used throughout the app to show advanced
 * stats when the user has connected their Spotify account.
 */

/**
 * Card showing a summary of audio features for a track.
 */
@Composable
fun AudioFeaturesSummaryCard(
    audioFeatures: SpotifyAudioFeatures,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Audio Analysis",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Main features row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioFeatureCircle(
                    label = "Energy",
                    value = audioFeatures.energy,
                    color = Color(0xFFFF9800)
                )
                AudioFeatureCircle(
                    label = "Mood",
                    value = audioFeatures.valence,
                    color = Color(0xFFE91E63)
                )
                AudioFeatureCircle(
                    label = "Dance",
                    value = audioFeatures.danceability,
                    color = Color(0xFF9C27B0)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Additional features
            AudioFeatureRow("Tempo", "${audioFeatures.tempo.roundToInt()} BPM")
            Spacer(modifier = Modifier.height(8.dp))
            AudioFeatureRow("Key", "${audioFeatures.keyName} ${audioFeatures.modeName}")
            
            if (audioFeatures.isLikelyAcoustic) {
                Spacer(modifier = Modifier.height(8.dp))
                AudioFeatureTag("Acoustic")
            }
            
            if (audioFeatures.isLikelyInstrumental) {
                Spacer(modifier = Modifier.height(8.dp))
                AudioFeatureTag("Instrumental")
            }
            
            if (audioFeatures.isLikelyLive) {
                Spacer(modifier = Modifier.height(8.dp))
                AudioFeatureTag("Live Recording")
            }
        }
    }
}

/**
 * Circular progress indicator for audio features.
 */
@Composable
fun AudioFeatureCircle(
    label: String,
    value: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Row for displaying a labeled audio feature.
 */
@Composable
private fun AudioFeatureRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Tag for boolean audio features.
 */
@Composable
private fun AudioFeatureTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Aggregated audio features for a time period.
 * Used in stats screens to show overall mood, energy, etc.
 */
data class AggregatedAudioFeatures(
    val averageEnergy: Float,
    val averageValence: Float,
    val averageDanceability: Float,
    val averageTempo: Float,
    val averageAcousticness: Float,
    val averageInstrumentalness: Float,
    val trackCount: Int
) {
    val energyPercentage: Int get() = (averageEnergy * 100).roundToInt()
    val moodPercentage: Int get() = (averageValence * 100).roundToInt()
    val danceabilityPercentage: Int get() = (averageDanceability * 100).roundToInt()
    val acousticnessPercentage: Int get() = (averageAcousticness * 100).roundToInt()
    
    val moodDescription: String
        get() = when {
            averageValence >= 0.7 -> "Happy & Upbeat"
            averageValence >= 0.5 -> "Positive"
            averageValence >= 0.3 -> "Balanced"
            else -> "Mellow & Introspective"
        }
    
    val energyDescription: String
        get() = when {
            averageEnergy >= 0.7 -> "High Energy"
            averageEnergy >= 0.5 -> "Moderate Energy"
            averageEnergy >= 0.3 -> "Relaxed"
            else -> "Calm & Peaceful"
        }
}

/**
 * Card showing aggregated stats for a time period.
 */
@Composable
fun PeriodAudioStatsCard(
    stats: AggregatedAudioFeatures,
    periodLabel: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Your Music $periodLabel",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Based on ${stats.trackCount} tracks with audio analysis",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Energy stat
            StatWithBar(
                label = "Energy Level",
                description = stats.energyDescription,
                percentage = stats.energyPercentage,
                color = Color(0xFFFF9800)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Mood stat
            StatWithBar(
                label = "Overall Mood",
                description = stats.moodDescription,
                percentage = stats.moodPercentage,
                color = Color(0xFFE91E63)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Danceability stat
            StatWithBar(
                label = "Danceability",
                description = "${stats.danceabilityPercentage}% danceable",
                percentage = stats.danceabilityPercentage,
                color = Color(0xFF9C27B0)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Tempo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Average Tempo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "${stats.averageTempo.roundToInt()} BPM",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFF2196F3)
                )
            }
        }
    }
}

/**
 * Stat with a progress bar.
 */
@Composable
private fun StatWithBar(
    label: String,
    description: String,
    percentage: Int,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Insight card showing interesting patterns from audio analysis.
 */
@Composable
fun AudioInsightCard(
    title: String,
    insight: String,
    emoji: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = emoji,
                fontSize = 32.sp
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = insight,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
