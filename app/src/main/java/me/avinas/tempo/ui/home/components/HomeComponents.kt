package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.GlassCard

import me.avinas.tempo.ui.components.TrendLine
import me.avinas.tempo.ui.theme.TempoRed

@Composable
fun HeroCard(
    userName: String,
    listeningTime: String,
    periodLabel: String,
    timeChangePercent: Double,
    trendData: List<Float>,
    selectedRange: TimeRange,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(24.dp) // Increased padding
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                   val greetingText = me.avinas.tempo.utils.TempoCopyEngine.getHeroGreeting(userName)
                   
                   // Dynamic font size based on length
                   val greetingStyle = when {
                       greetingText.length <= 20 -> MaterialTheme.typography.headlineSmall
                       greetingText.length <= 30 -> MaterialTheme.typography.titleLarge
                       else -> MaterialTheme.typography.titleMedium
                   }
                   
                   Text(
                        text = greetingText,
                        style = greetingStyle,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    

                }

                Spacer(modifier = Modifier.height(8.dp))

                // Hero Time Display
                Text(
                    text = listeningTime,
                    style = MaterialTheme.typography.displayMedium, // Updated to displayMedium
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                
                // Smart Copy Logic with Pulse
                val isPositive = timeChangePercent >= 0
                val percentString = "${if (isPositive) "+" else ""}${timeChangePercent.toInt()}%"
                val comparisonColor = if (isPositive) Color(0xFF4ADE80) else Color(0xFFFBBF24) // Green vs Amber
                
                // Pulse Animation for badge
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "BadgePulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "PulseAlpha"
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(comparisonColor.copy(alpha = pulseAlpha))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = percentString,
                            style = MaterialTheme.typography.labelLarge,
                            color = comparisonColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getHeroSubtitle(timeChangePercent, selectedRange),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Chart area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                TrendLine(
                    dataPoints = trendData,
                    modifier = Modifier.fillMaxSize(),
                    lineColor = me.avinas.tempo.ui.theme.TempoSecondary,
                    fillColor = me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f),
                    strokeWidth = 3.dp // Thicker line
                )
            }
        }
    }
}


@Composable
fun SpotlightStoryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.15f), // NeonRed
        contentPadding = PaddingValues(24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = me.avinas.tempo.ui.theme.NeonRed,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Tempo Spotlight",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "See your music visualised.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun WeekInReviewGrid(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    totalHours: String,
    newDiscoveries: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Your Week in Review",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        // Grid layout using Rows and Columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Artist
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topArtistImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopArtistCopy(topArtistName),
                        style = MaterialTheme.typography.bodySmall, // Smaller for longer text
                        color = me.avinas.tempo.ui.theme.NeonRed,
                        maxLines = 1
                    )
                    Text(
                        text = topArtistName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
            
            // Top Track
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.ElectricBlue.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topTrackImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopTrackCopy(topTrackName),
                        style = MaterialTheme.typography.bodySmall,
                        color = me.avinas.tempo.ui.theme.ElectricBlue,
                        maxLines = 1
                    )
                    Text(
                        text = topTrackName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Hours
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = me.avinas.tempo.ui.theme.GoldenAmber,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Listen Time",
                        style = MaterialTheme.typography.labelMedium,
                        color = me.avinas.tempo.ui.theme.GoldenAmber
                    )
                    Text(
                        text = totalHours,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            // Discoveries
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFFA855F7).copy(alpha = 0.1f), // Purple
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFA855F7).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = Color(0xFFA855F7), // Purple 400
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "New Finds",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA855F7)
                    )
                    Text(
                        text = "$newDiscoveries",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoverySection(
    discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Ready to discover?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        )
        
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val newArtists = discoveryStats?.newArtistsCount ?: 0
            val newTracks = discoveryStats?.newTracksCount ?: 0
            val varietyScore = ((discoveryStats?.varietyScore ?: 0.0) * 10).toInt()
            
            DiscoveryCard(
                title = "Found $newArtists new artists",
                subtitle = "Expand your musical horizon",
                icon = Icons.Default.Explore,
                color = Color(0xFF0EA5E9), // Sky 500
                backgroundColor = Color(0xFF0369A1).copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = "Discovered $newTracks new tracks",
                subtitle = "Fresh beats for your playlist",
                icon = Icons.Default.History,
                color = Color(0xFFF43F5E), // Rose 500
                backgroundColor = Color(0xFFBE123C).copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = "Variety Score: $varietyScore/10",
                subtitle = "How unique is your taste?",
                icon = Icons.Default.Fingerprint,
                color = Color(0xFF8B5CF6), // Violet 500
                backgroundColor = Color(0xFF6D28D9).copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
fun DiscoveryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    backgroundColor: Color
) {
    GlassCard(
        modifier = Modifier.width(200.dp),
        backgroundColor = backgroundColor,
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun HabitInsights(
    insights: List<HabitInsightData>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "A Look at Your Habits",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            insights.forEach { insight ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = insight.gradient.first().copy(alpha = 0.2f),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(insight.iconBgColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = insight.icon,
                                contentDescription = null,
                                tint = insight.iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = insight.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = insight.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class HabitInsightData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color,
    val iconBgColor: Color,
    val gradient: List<Color>
)
