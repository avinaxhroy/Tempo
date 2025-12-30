package me.avinas.tempo.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Row of buttons for opening track in music streaming services.
 * 
 * Shows buttons for:
 * - Apple Music (if appleMusicUrl available)
 * - Spotify (if spotifyUrl available)
 */
@Composable
fun MusicLinksRow(
    appleMusicUrl: String?,
    spotifyUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Only show if at least one link is available
    if (appleMusicUrl == null && spotifyUrl == null) return
    
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "Listen On",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        
        GlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Apple Music Button
                if (appleMusicUrl != null) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(appleMusicUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFFC3C44).copy(alpha = 0.2f),
                            contentColor = Color(0xFFFC3C44)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Apple Music",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apple Music")
                    }
                }
                
                // Spotify Button
                if (spotifyUrl != null) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1DB954).copy(alpha = 0.2f),
                            contentColor = Color(0xFF1DB954)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Spotify",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Spotify")
                    }
                }
            }
        }
    }
}
