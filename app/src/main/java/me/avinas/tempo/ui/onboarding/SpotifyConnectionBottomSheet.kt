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
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.scaledSize

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
            .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 18.dp))
            .verticalScroll(rememberScrollState())
            .padding(vertical = adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)))

        // Key message: Works WITHOUT Spotify
        Box(
            modifier = Modifier
                .background(
                    color = Color(0xFF22C55E).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 6.dp) 
        ) {
            Text(
                text = "✓ Tempo works with any music app!", // Simple & Direct
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF22C55E)
            )
        }

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)))

        Text(
            text = "Get High-Quality Art", // Focus on the visual benefit, not "Features"
            style = if (isSmall) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp)
        )

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(4.dp, 3.dp, 2.dp)))

        Text(
            text = "Connect to use Spotify nicely reliable database for metadata:", // "Nicely reliable" might be too casual? "Use Spotify's reliable database"
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = adaptiveTextUnitByCategory(14.sp, 13.sp, 12.sp)
        )

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(12.dp, 10.dp, 8.dp)))

        // Benefits (Using compact version)
        BenefitItem(icon = Icons.Default.GraphicEq, text = "Reliable album artwork")
        BenefitItem(icon = Icons.Default.Mood, text = "Accurate genre info")
        BenefitItem(icon = Icons.Default.Bolt, text = "Mood & energy stats")

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(12.dp, 10.dp, 8.dp)))
        
        // Clarification note
        Text(
            text = "This is optional. We never access your playlists or personal data.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
            lineHeight = 14.sp
        )

        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(16.dp, 12.dp, 8.dp)))

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(scaledSize(48.dp, 0.9f, 1.05f)),
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
                text = "Skip for now", // Neutral
                color = Color.White.copy(alpha = 0.6f), // Reduced prominence
                fontWeight = FontWeight.Normal
            )
        }
        
        // Add spacing for the navigation bar
        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(32.dp, 24.dp, 16.dp)))
        
        // Add spacing for the navigation bar specifically
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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
