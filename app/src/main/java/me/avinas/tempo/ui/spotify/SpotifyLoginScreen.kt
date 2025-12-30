package me.avinas.tempo.ui.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager

/**
 * Spotify connection screen that explains benefits and allows users to connect.
 * 
 * This screen is shown when users tap on features that require Spotify,
 * or from settings when they want to enable advanced stats.
 */
@Composable
fun SpotifyLoginScreen(
    authState: SpotifyAuthManager.AuthState,
    onConnectClick: () -> Unit,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null
) {
    // Spotify brand colors
    val spotifyGreen = Color(0xFF1DB954)
    val spotifyBlack = Color(0xFF191414)
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Spotify Logo placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(spotifyGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Title
            Text(
                text = "Connect Spotify for\nAdvanced Stats",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Subtitle
            Text(
                text = "Unlock detailed audio analysis for your music",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Benefits list
            BenefitItem(
                icon = Icons.Default.Favorite,
                title = "Mood Analysis",
                description = "See how happy or melancholic your music is",
                accentColor = Color(0xFFE91E63)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            BenefitItem(
                icon = Icons.Default.Star,
                title = "Energy Insights",
                description = "Track your music's intensity over time",
                accentColor = Color(0xFFFF9800)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            BenefitItem(
                icon = Icons.Default.Settings,
                title = "Danceability Score",
                description = "Find out how danceable your playlists are",
                accentColor = Color(0xFF9C27B0)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            BenefitItem(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                title = "Tempo Stats",
                description = "Discover your preferred BPM ranges",
                accentColor = Color(0xFF2196F3)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            BenefitItem(
                icon = Icons.Default.Face,
                title = "Acoustic vs Electronic",
                description = "See your balance of acoustic and electronic music",
                accentColor = Color(0xFF4CAF50)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Privacy note
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = spotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Your data stays on your device. We only fetch audio features for tracks you've already listened to.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Connect button
            Button(
                onClick = onConnectClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = spotifyGreen,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(28.dp),
                enabled = authState !is SpotifyAuthManager.AuthState.Connecting
            ) {
                if (authState is SpotifyAuthManager.AuthState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Connect with Spotify",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            
            // Error message
            if (authState is SpotifyAuthManager.AuthState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = authState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            
            // Skip button (optional)
            if (onSkip != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onSkip) {
                    Text(
                        text = "Maybe Later",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BenefitItem(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Compact Spotify connection prompt for use in stats screens.
 * Shows when a stat requires Spotify connection.
 */
@Composable
fun SpotifyUpgradePrompt(
    statName: String,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = spotifyGreen,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "$statName requires Spotify",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Connect your account to unlock this feature",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = onConnectClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = spotifyGreen
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect Spotify")
            }
        }
    }
}

/**
 * Badge indicating a stat requires Spotify.
 */
@Composable
fun SpotifyRequiredBadge(
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = spotifyGreen.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = spotifyGreen,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Spotify",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = spotifyGreen
            )
        }
    }
}
