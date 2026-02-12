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

// Global cache for noise bitmap to avoid regeneration across all instances
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

    // Noise Texture (Use global cache)
    val noiseBitmap = NoiseCache.bitmap

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base Obsidian
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Mesh Gradient Layer: Subtle, structured blends
            // Top-Left: Neutral Dark (MeshGradient1)
            val center1 = Offset(w * 0.1f + p1Offset, h * 0.1f + p1Offset)
            val radius1 = w * 0.9f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MeshGradient1.copy(alpha = 0.2f), Color.Transparent),
                    center = center1,
                    radius = radius1
                ),
                center = center1,
                radius = radius1
            )

            // Center-Right: Deepest Charcoal (MeshGradient2) - texture anchor
            val center2 = Offset(w * 0.8f, h * 0.5f)
            val radius2 = w * 0.8f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MeshGradient2.copy(alpha = 0.15f), Color.Transparent),
                    center = center2,
                    radius = radius2
                ),
                center = center2,
                radius = radius2
            )

            // Bottom-Left: Soft Dark Overlay (MeshGradient3) - grounding
            val center3 = Offset(w * 0.2f + p2Offset, h * 0.9f)
            val radius3 = w * 1.0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MeshGradient3.copy(alpha = 0.2f), Color.Transparent),
                    center = center3,
                    radius = radius3
                ),
                center = center3,
                radius = radius3
            )

            // 2. Cinematic Noise Overlay (The "Premium" Texture)
            drawNoiseOverlay(noiseBitmap, alpha = 0.05f) // Increased to 5% for visible texture on black

            // 3. Cinematic Vignette (Bottom Fade)
            // darker grounding for the bottom navigation area
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    startY = h * 0.75f,
                    endY = h
                ),
                size = size
            )
        }

        content()
    }
}

// Helper to draw tiled noise
private fun DrawScope.drawNoiseOverlay(noise: ImageBitmap, alpha: Float) {
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
private fun generateNoiseBitmap(width: Int, height: Int): ImageBitmap {
    val size = width * height
    val pixels = IntArray(size)
    val random = Random(123) // Seeded for consistency

    for (i in 0 until size) {
        // Random grayscale value
        val gray = random.nextInt(256)
        // Set alpha to 255 (opaque), we control overall opacity in drawImage
        pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
