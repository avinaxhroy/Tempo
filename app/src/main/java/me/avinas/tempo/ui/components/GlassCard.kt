package me.avinas.tempo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    shape: RoundedCornerShape = RoundedCornerShape(24.dp), // Increased for softer premium feel
    backgroundColor: Color = Color.White.copy(alpha = 0.05f),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    variant: GlassCardVariant = GlassCardVariant.HighProminence,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.CenterStart,
    content: @Composable () -> Unit
) {
    // Hero (HighProminence): light-catching top edge gradient for depth
    // Normal (LowProminence): single flat stroke, no gradient
    val borderBrush = remember(variant) {
        if (variant == GlassCardVariant.HighProminence) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.05f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.10f)
                )
            )
        }
    }

    val backgroundBrush = remember(variant, backgroundColor) {
        if (variant == GlassCardVariant.HighProminence) {
            Brush.verticalGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.lerp(backgroundColor, Color.White, 0.07f),
                    backgroundColor.copy(alpha = (backgroundColor.alpha * 0.85f).coerceAtLeast(0.02f))
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    backgroundColor,
                    backgroundColor
                )
            )
        }
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 0.dp,
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
