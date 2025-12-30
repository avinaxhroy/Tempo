package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.WarmVioletAccent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun DeepOceanBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Ambient Animation
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "DeepOcean")
    
    // Scale Animations (Breathing)
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob1Scale"
    )
    
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob2Scale"
    )

    // Drift Animations (Slow position shift)
    val drift1X by infiniteTransition.animateFloat(
        initialValue = -50f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob1DriftX"
    )

    val drift1Y by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 70f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob1DriftY"
    )

    val drift2X by infiniteTransition.animateFloat(
        initialValue = 30f,
        targetValue = -40f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob2DriftX"
    )
    
    val drift2Y by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(22000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Blob2DriftY"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base background
    ) {
        // Ambient Background Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Warm Rose Bloom (top-left) - Rule #2: Faint warm bloom to cancel blue
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFBE123C).copy(alpha = 0.05f), Color.Transparent), // Burgundy/Rose at very low opacity
                    center = Offset(drift1X, drift1Y),
                    radius = width * scale1
                ),
                center = Offset(drift1X, drift1Y),
                radius = width * scale1
            )
            
            // Orchid/Magenta blob (bottom-right) - Rule #1: Targeted Magenta, not Blue-Violet
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(WarmVioletAccent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(width + drift2X, height + drift2Y),
                    radius = width * scale2
                ),
                center = Offset(width + drift2X, height + drift2Y),
                radius = width * scale2
            )
            
            // Deep Plum blob (center) - Rule #1: Aubergine/Plum anchor
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF701A75).copy(alpha = 0.08f), Color.Transparent), // Fuchsia 900
                    center = Offset(width * 0.3f, height * 0.4f),
                    radius = width * 0.6f
                ),
                center = Offset(width * 0.3f, height * 0.4f),
                radius = width * 0.6f
            )
        }
        
        content()
    }
}
