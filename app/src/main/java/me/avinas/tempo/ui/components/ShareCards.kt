package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.TrackDetails
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq

/**
 * Common background for share cards to ensure brand consistency.
 */
@Composable
fun ShareCardBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Deep Ocean themed gradient
    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        TempoDarkBackground, // Slate 900
                        Color(0xFF1E1B4B), // Indigo 950
                        Color(0xFF312E81)  // Indigo 900
                    )
                )
            )
    ) {
        // Decorative ambient glow
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 50.dp, y = (-50).dp)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFA855F7).copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-50).dp, y = 50.dp)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFEC4899).copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        
        content()
        
        // Minimal Branding at Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp), // Moved upward (increased from 32dp)
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TEMPO",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 4.sp
            )
        }
    }
}

/**
 * 9:16 Optimized Share Card for Artists
 */
@Composable
fun ArtistShareCard(
    artistDetails: ArtistDetails,
    modifier: Modifier = Modifier
) {
    ShareCardBackground(modifier = modifier.aspectRatio(9f/16f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp), // Reduced padding to fit more content
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.5f))
            
            // Artist Image with Badge
            Box {
                Box(
                    modifier = Modifier
                        .size(160.dp) // Reduced size to make room for top songs
                        .clip(CircleShape)
                        .border(4.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                     // FIX: Use artist image instead of album art
                     val imageUrl = artistDetails.artist.imageUrl
                    
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(imageUrl ?: R.drawable.ic_launcher_foreground)
                                .allowHardware(false) // FIX: Disable hardware bitmaps for capture
                                .build()
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                // Top Fan Badge
                if (artistDetails.personalPlayCount > 50) {
                    GlassCard(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 12.dp),
                        backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.9f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "TOP 1% FAN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Artist Name
            Text(
                text = artistDetails.artist.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 32.sp), // Slightly smaller
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 36.sp,
                maxLines = 2
            )
            
            if (artistDetails.country != null) {
                Spacer(modifier = Modifier.height(8.dp))
                GlassCard(
                    shape = RoundedCornerShape(50),
                    backgroundColor = Color.White.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "📍 ${artistDetails.country}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "TOTAL TIME",
                    value = "${artistDetails.personalTotalTimeMinutes}m",
                    isLarge = false
                )
                StatItem(
                    label = "SONGS",
                    value = artistDetails.uniqueTracksPlayed.toString(),
                    isLarge = false
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Top Songs Section
            if (artistDetails.topSongs.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                    backgroundColor = Color.Black.copy(alpha = 0.4f), // Darker for more contrast
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "TOP SONGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f), // More visible
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        artistDetails.topSongs.take(3).forEachIndexed { index, song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${song.playCount} plays",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(40.dp)) // Reduced footer space, matched to logo
        }
    }
}

/**
 * 9:16 Optimized Share Card for Songs
 */
@Composable
fun SongShareCard(
    trackDetails: TrackDetails,
    modifier: Modifier = Modifier
) {
    ShareCardBackground(modifier = modifier.aspectRatio(9f/16f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))
            
            // Album Art with Glow
            Box(
                contentAlignment = Alignment.Center
            ) {
                // Glow effect
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .blur(32.dp)
                        .background(Color.White.copy(alpha = 0.3f), CircleShape)
                )
                
                GlassCard(
                    modifier = Modifier.size(280.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(trackDetails.track.albumArtUrl ?: R.drawable.ic_launcher_foreground)
                                .allowHardware(false) // FIX: Disable hardware bitmaps for capture
                                .build()
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(56.dp))
            
            // Track Info
            Text(
                text = trackDetails.track.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = trackDetails.track.artist,
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE9D5FF), // Purple 200 - Lighter contrast
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Detailed Stats Grid
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play Count
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "PLAYS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = trackDetails.playCount.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                    
                    // Total Time
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "MINUTES",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = trackDetails.totalTimeMinutes.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp)) // Space for footer
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    isLarge: Boolean = true
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = if (isLarge) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
