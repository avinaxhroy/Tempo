package me.avinas.tempo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GlassCardVariant {
    HighProminence,
    LowProminence
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    backgroundColor: Color = Color.White.copy(alpha = 0.05f),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    variant: GlassCardVariant = GlassCardVariant.HighProminence,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.CenterStart,
    content: @Composable () -> Unit
) {
    // Softened borders - barely there, just catching light
    val borderBrush = if (variant == GlassCardVariant.HighProminence) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.30f), // Crisp top edge (Structural contrast)
                Color.White.copy(alpha = 0.05f) 
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.15f), // Defined top edge
                Color(0x00FFFFFF),
                backgroundColor.copy(alpha = 0.2f) // Faint ambient bottom glow
            )
        )
    }

    // Adjusted Gradient for "Suspended" feel
    val backgroundBrush = if (variant == GlassCardVariant.HighProminence) {
         Brush.verticalGradient(
            colors = listOf(
                // Add Luminosity: Mix White with BackgroundColor to ensure it pops from dark bg
                androidx.compose.ui.graphics.lerp(backgroundColor, Color.White, 0.05f),
                backgroundColor.copy(alpha = (backgroundColor.alpha * 0.9f).coerceAtLeast(0.02f))
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                 // Add subtle brightness to LowProminence too
                 androidx.compose.ui.graphics.lerp(backgroundColor, Color.White, 0.03f).copy(alpha = (backgroundColor.alpha * 0.8f)), 
                 backgroundColor.copy(alpha = (backgroundColor.alpha * 0.9f))
            )
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 0.dp, // Avoid black box artifact
                shape = shape,
                ambientColor = Color.Transparent, 
                spotColor = Color.Transparent
            )
            .background(backgroundBrush, shape)
            .border(
                BorderStroke(
                    width = if (variant == GlassCardVariant.HighProminence) 1.dp else 0.5.dp,
                    brush = borderBrush
                ),
                shape = shape
            )
            .clip(shape),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth() // Maintain horizontal fill
                .padding(contentPadding)
        ) {
            content()
        }
    }
}
