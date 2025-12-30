package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.WarmVioletAccent

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base background
    ) {
        // Ambient Background Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Red blob (top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFEF4444).copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = width * 0.8f
                ),
                center = Offset(0f, 0f),
                radius = width * 0.8f
            )
            
            // Warm Violet blob (bottom-right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(WarmVioletAccent.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(width, height),
                    radius = width * 0.9f
                ),
                center = Offset(width, height),
                radius = width * 0.9f
            )
            
            // Purple blob (center-ish)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFA855F7).copy(alpha = 0.1f), Color.Transparent),
                    center = Offset(width * 0.3f, height * 0.4f),
                    radius = width * 0.6f
                ),
                center = Offset(width * 0.3f, height * 0.4f),
                radius = width * 0.6f
            )
        }

        // Skip button
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(
                text = "Skip",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Hero Illustration
            GlassCard(
                modifier = Modifier.size(160.dp),
                backgroundColor = TempoRed.copy(alpha = 0.1f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Inner glow
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(TempoRed.copy(alpha = 0.4f), Color.Transparent)
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Know Your Music,\nLove Your Stats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tempo automatically tracks what you listen to and shows you beautiful insights about your listening habits.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Text(
                    text = "Get Started",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
