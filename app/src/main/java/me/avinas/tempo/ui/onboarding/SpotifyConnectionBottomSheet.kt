package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun SpotifyConnectionBottomSheet(
    onConnect: () -> Unit,
    onMaybeLater: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1B24).copy(alpha = 0.95f)) // Charcoal Surface
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Want advanced stats?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect Spotify (Optional)",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Benefits
        BenefitItem(icon = Icons.Default.Mood, text = "Mood tracking")
        BenefitItem(icon = Icons.Default.Bolt, text = "Energy analysis")
        BenefitItem(icon = Icons.Default.GraphicEq, text = "Audio features")

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1DB954), // Spotify Green
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text(
                text = "Connect Spotify",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onMaybeLater) {
            Text(
                text = "Maybe Later",
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BenefitItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
