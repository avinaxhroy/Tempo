package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.MeshGradient1
import me.avinas.tempo.ui.theme.MeshGradient2
import me.avinas.tempo.ui.theme.MeshGradient3

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random

// Global cache for noise bitmap to keep glassmorphism texture perfect and performant
private object NoiseCache {
    val bitmap: ImageBitmap by lazy { 
        generateNoiseBitmap(128, 128) 
    }
}

@Composable
fun DeepOceanBackground(
    modifier: Modifier = Modifier,
    enableAnimations: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    // Animation: Cinematic Drift (Very slow, subtle movement)
    // Only create animations if enabled to reduce recompositions
    val p1Offset = if (enableAnimations) {
        val infiniteTransition = rememberInfiniteTransition(label = "CinematicMesh")
        val offset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 100f,
            animationSpec = infiniteRepeatable(
                animation = tween(45000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MeshP1"
        )
        offset
    } else {
        0f
    }

    // Point 2: Deep Earth (Bottom Left Drift)
    val p2Offset = if (enableAnimations) {
        val infiniteTransition = rememberInfiniteTransition(label = "CinematicMesh2")
        val offset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -80f,
            animationSpec = infiniteRepeatable(
                animation = tween(55000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "MeshP2"
        )
        offset
    } else {
        0f
    }

    // Fetch noise texture purely for the glassmorphism feel
    val noiseBitmap = NoiseCache.bitmap

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base Obsidian (Permanent Dark)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. OLED Abyss Glow: A single, very subtle, wide ambient glow at the edges.
            // This prevents the "70% purple" linear look and provides a radical 95% pitch-black aesthetic.
            
            // Bottom "Abyss" Ambient Edge Glow
            val bottomCenter = Offset(w * 0.5f + p1Offset, h * 1.05f)
            val bottomRadius = w * 1.2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MeshGradient3.copy(alpha = 0.35f), Color.Transparent),
                    center = bottomCenter,
                    radius = bottomRadius
                ),
                center = bottomCenter,
                radius = bottomRadius
            )

            // Top-Right "Cosmic Dust" Glow
            val topRight = Offset(w * 0.9f, h * 0.0f)
            val topRadius = w * 0.8f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MeshGradient1.copy(alpha = 0.25f), Color.Transparent),
                    center = topRight,
                    radius = topRadius
                ),
                center = topRight,
                radius = topRadius
            )

            // The perfect texture for glassmorphism: extremely subtle.
            drawNoiseOverlay(noiseBitmap, alpha = 0.025f) // Lowered slightly to preserve pure black
        }

        content()
    }
}

// Helper to draw tiled noise
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNoiseOverlay(noise: androidx.compose.ui.graphics.ImageBitmap, alpha: Float) {
    val noiseW = noise.width
    val noiseH = noise.height
    val cols = (size.width / noiseW).toInt() + 1
    val rows = (size.height / noiseH).toInt() + 1

    for (x in 0 until cols) {
        for (y in 0 until rows) {
            drawImage(
                image = noise,
                dstOffset = androidx.compose.ui.unit.IntOffset(x * noiseW, y * noiseH),
                alpha = alpha
            )
        }
    }
}

// Generate a small static noise bitmap
private fun generateNoiseBitmap(width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap {
    val size = width * height
    val pixels = IntArray(size)
    val random = kotlin.random.Random(123) // Seeded for consistency

    for (i in 0 until size) {
        val gray = random.nextInt(256)
        pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
    
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
