package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import me.avinas.tempo.ui.utils.isSmallScreen

@Composable
fun SpotifyConnectionBottomSheet(
    onConnect: () -> Unit,
    onMaybeLater: () -> Unit
) {
    val isSmall = isSmallScreen()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1B24).copy(alpha = 0.95f)) // Charcoal Surface
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = adaptiveSize(16.dp, 12.dp)), // Reduced vertical padding
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 12.dp))) // Reduced

        // Key message: Works WITHOUT Spotify
        Box(
            modifier = Modifier
                .background(
                    color = Color(0xFF22C55E).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 6.dp) // Compact padding
        ) {
            Text(
                text = "✓ Tempo works great without Spotify!",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF22C55E)
            )
        }

        Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 12.dp))) // Reduced

        Text(
            text = "Want Extra Features?",
            style = if (isSmall) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, // Smaller title
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = adaptiveTextUnit(18.sp, 16.sp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Connecting Spotify unlocks:",
            style = MaterialTheme.typography.bodySmall, // Smaller body
            color = Color.White.copy(alpha = 0.7f),
            fontSize = adaptiveTextUnit(14.sp, 12.sp)
        )

        Spacer(modifier = Modifier.height(adaptiveSize(12.dp, 8.dp))) // Reduced

        // Benefits (Using compact version)
        BenefitItem(icon = Icons.Default.Mood, text = "Mood tracking for each song")
        BenefitItem(icon = Icons.Default.Bolt, text = "Energy & danceability analysis")
        BenefitItem(icon = Icons.Default.GraphicEq, text = "High-resolution album art")

        Spacer(modifier = Modifier.height(adaptiveSize(12.dp, 8.dp))) // Reduced
        
        // Clarification note
        Text(
            text = "Only used for metadata. We never access your playlists or account info.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
            lineHeight = 14.sp // Tighter line height
        )

        Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 8.dp))) // Significantly reduced

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp), // Slightly smaller button
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1DB954), // Spotify Green
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                text = "Connect Spotify",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onMaybeLater) {
            Text(
                text = "Skip — I don't need this",
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun BenefitItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), // Reduced padding
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.width(40.dp).size(24.dp),
            tint = Color(0xFFEF4444) // Red 500
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}
