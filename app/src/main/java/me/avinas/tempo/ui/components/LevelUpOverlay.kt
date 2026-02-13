package me.avinas.tempo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * Full-screen overlay shown when the user levels up.
 * Displays confetti-style particles and a scale-in animation.
 */
@Composable
fun LevelUpOverlay(
    newLevel: Int,
    title: String,
    onDismiss: () -> Unit
) {
    // Entry animation  
    val scale = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f)
        )
        textAlpha.animateTo(1f, animationSpec = tween(400))
    }
    
    // Confetti particles
    val particles = remember {
        (0..30).map {
            ConfettiParticle(
                x = Random.nextFloat(),
                startY = -Random.nextFloat() * 0.3f,
                speed = 0.3f + Random.nextFloat() * 0.7f,
                size = 4f + Random.nextFloat() * 8f,
                color = listOf(
                    Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1),
                    Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6)
                ).random(),
                rotation = Random.nextFloat() * 360f
            )
        }
    }
    
    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val confettiProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confettiProgress"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // Confetti canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach { p ->
                val y = (p.startY + confettiProgress * p.speed * 2f) % 1.2f
                drawCircle(
                    color = p.color.copy(alpha = if (y > 0.9f) 1f - (y - 0.9f) / 0.3f else 0.8f),
                    radius = p.size,
                    center = Offset(p.x * size.width, y * size.height)
                )
            }
        }
        
        // Level Up Card
        Column(
            modifier = Modifier
                .scale(scale.value)
                .padding(32.dp)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFF1E1B4B).copy(alpha = 0.95f),
                            Color(0xFF0F172A).copy(alpha = 0.95f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "LEVEL UP!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color(0xFFF59E0B),
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Level $newLevel",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier.alpha(textAlpha.value)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFA855F7),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(textAlpha.value)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.alpha(textAlpha.value)
            ) {
                Text(
                    text = "Awesome!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

private data class ConfettiParticle(
    val x: Float,
    val startY: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    val rotation: Float
)
