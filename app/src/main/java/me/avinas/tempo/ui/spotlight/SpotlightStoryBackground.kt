package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.animation.animateColorAsState
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
fun SpotlightStoryBackground(
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Animate colors for smooth transitions between pages
    val animatedPrimary by animateColorAsState(
        targetValue = primaryColor,
        animationSpec = tween(durationMillis = 800),
        label = "primaryColor"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 800),
        label = "secondaryColor"
    )
    val animatedTertiary by animateColorAsState(
        targetValue = tertiaryColor,
        animationSpec = tween(durationMillis = 800),
        label = "tertiaryColor"
    )

    // Infinite transitions for "Living" feel
    val infiniteTransition = rememberInfiniteTransition(label = "SpotlightBg")

    // Slow Drift Animations
    val driftP_X by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
             animation = tween(8000, easing = EaseInOutSine),
             repeatMode = RepeatMode.Reverse
        ), label = "DriftPX"
    )
    val driftP_Y by infiniteTransition.animateFloat(
        initialValue = -20f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
             animation = tween(11000, easing = EaseInOutSine),
             repeatMode = RepeatMode.Reverse
        ), label = "DriftPY"
    )

    val driftS_X by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = -40f,
        animationSpec = infiniteRepeatable(
             animation = tween(13000, easing = EaseInOutSine),
             repeatMode = RepeatMode.Reverse
        ), label = "DriftSX"
    )
    val driftS_Y by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = -20f,
        animationSpec = infiniteRepeatable(
             animation = tween(9000, easing = EaseInOutSine),
             repeatMode = RepeatMode.Reverse
        ), label = "DriftSY"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base background (Slate 900)
    ) {
        // Ambient Background Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Top-left blob (Primary) - Drifting
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedPrimary.copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(driftP_X, driftP_Y),
                    radius = width * 0.8f
                ),
                center = Offset(driftP_X, driftP_Y),
                radius = width * 0.8f
            )
            
            // Bottom-right blob (Secondary) - Drifting
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedSecondary.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(width + driftS_X, height + driftS_Y),
                    radius = width * 0.9f
                ),
                center = Offset(width + driftS_X, height + driftS_Y),
                radius = width * 0.9f
            )
            
            // Center blob (Tertiary) - Static Anchor
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedTertiary.copy(alpha = 0.15f), Color.Transparent),
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
