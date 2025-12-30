package me.avinas.tempo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    gradientColors: List<Color>? = null,
    content: @Composable () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.95f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (gradientColors == null) backgroundColor else Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.then(
                if (gradientColors != null) {
                    Modifier.background(Brush.linearGradient(gradientColors))
                } else {
                    Modifier
                }
            ).padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
fun TrendLine(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp
) {
    if (dataPoints.isEmpty()) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val maxVal = dataPoints.maxOrNull() ?: 1f
        val minVal = 0f // Always scale from 0 to show absolute magnitude
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val path = Path()
        val fillPath = Path()

        val stepX = if (dataPoints.size > 1) width / (dataPoints.size - 1) else width

        dataPoints.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - ((value - minVal) / range * height)

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (index - 1) * stepX
                val prevY = height - ((dataPoints[index - 1] - minVal) / range * height)
                
                val controlPoint1X = prevX + (x - prevX) / 2
                val controlPoint1Y = prevY
                val controlPoint2X = prevX + (x - prevX) / 2
                val controlPoint2Y = y

                path.cubicTo(controlPoint1X, controlPoint1Y, controlPoint2X, controlPoint2Y, x, y)
                fillPath.cubicTo(controlPoint1X, controlPoint1Y, controlPoint2X, controlPoint2Y, x, y)
            }
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor, Color.Transparent),
                startY = 0f,
                endY = height
            )
        )

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}
