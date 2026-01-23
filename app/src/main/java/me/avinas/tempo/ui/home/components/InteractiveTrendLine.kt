package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun InteractiveTrendLine(
    dataPoints: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp,
    formatValue: (Float) -> String = { "${it.toInt()}m" },
    onValueSelected: ((Float, String) -> Unit)? = null,
    onSelectionCleared: (() -> Unit)? = null
) {
    if (dataPoints.isEmpty()) return
    
    require(labels.size == dataPoints.size) { 
        "Labels size (${labels.size}) must match dataPoints size (${dataPoints.size})" 
    }

    // Use labels as key to reset state when data changes (e.g., switching time ranges)
    // This ensures state resets even if dataPoints.size stays the same
    val dataKey = remember(labels) { labels.hashCode() }
    
    var selectedIndex by remember(dataKey) { mutableStateOf<Int?>(null) }
    var isDragging by remember(dataKey) { mutableStateOf(false) }
    var dragPosition by remember(dataKey) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val animatedX = remember(dataKey) { Animatable(0f) }
    
    // Reset scrubbing state when data changes (e.g., switching time ranges)
    LaunchedEffect(dataKey) {
        onSelectionCleared?.invoke()
    }

    // Helper function to calculate nearest index from X position
    fun calculateNearestIndex(xPos: Float, width: Float): Int {
        if (dataPoints.size <= 1) return 0
        
        // Calculate the exact fractional position
        val fraction = (xPos / width).coerceIn(0f, 1f)
        
        // Map fraction to index range [0, dataPoints.size - 1]
        val exactIndex = fraction * (dataPoints.size - 1)
        
        // Round to nearest integer index
        return exactIndex.toInt().coerceIn(0, dataPoints.size - 1)
    }
    
    // Helper function to calculate X position from index
    fun calculateXFromIndex(index: Int, width: Float): Float {
        if (dataPoints.size <= 1) return width / 2f
        return (index.toFloat() / (dataPoints.size - 1)) * width
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(dataKey) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragPosition = offset
                            
                            val width = size.width.toFloat()
                            val nearestIndex = calculateNearestIndex(offset.x, width)
                            
                            selectedIndex = nearestIndex
                            scope.launch {
                                animatedX.snapTo(calculateXFromIndex(nearestIndex, width))
                            }
                            onValueSelected?.invoke(
                                dataPoints[nearestIndex],
                                labels[nearestIndex]
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val offset = change.position
                            dragPosition = offset
                            
                            val width = size.width.toFloat()
                            val clampedX = offset.x.coerceIn(0f, width)
                            val nearestIndex = calculateNearestIndex(clampedX, width)
                            
                            if (nearestIndex != selectedIndex) {
                                selectedIndex = nearestIndex
                                scope.launch {
                                    animatedX.snapTo(calculateXFromIndex(nearestIndex, width))
                                }
                                onValueSelected?.invoke(
                                    dataPoints[nearestIndex],
                                    labels[nearestIndex]
                                )
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                kotlinx.coroutines.delay(300)
                                isDragging = false
                                selectedIndex = null
                                dragPosition = null
                                onSelectionCleared?.invoke()
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            selectedIndex = null
                            dragPosition = null
                            onSelectionCleared?.invoke()
                        }
                    )
                }
                .pointerInput(dataKey) {
                    detectTapGestures { offset ->
                        isDragging = true
                        dragPosition = offset
                        
                        val width = size.width.toFloat()
                        val nearestIndex = calculateNearestIndex(offset.x, width)
                        
                        selectedIndex = nearestIndex
                        scope.launch {
                            animatedX.snapTo(calculateXFromIndex(nearestIndex, width))
                            onValueSelected?.invoke(
                                dataPoints[nearestIndex],
                                labels[nearestIndex]
                            )
                            kotlinx.coroutines.delay(1500)
                            isDragging = false
                            selectedIndex = null
                            dragPosition = null
                            onSelectionCleared?.invoke()
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val maxVal = dataPoints.maxOrNull() ?: 1f
            val minVal = 0f
            val range = (maxVal - minVal).coerceAtLeast(1f)

            val path = Path()
            val fillPath = Path()
            
            // Use the same calculation as in helper function for consistency
            dataPoints.forEachIndexed { index, value ->
                val x = if (dataPoints.size > 1) {
                    (index.toFloat() / (dataPoints.size - 1)) * width
                } else {
                    width / 2f
                }
                val y = height - ((value - minVal) / range * height)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    val prevIndex = index - 1
                    val prevX = if (dataPoints.size > 1) {
                        (prevIndex.toFloat() / (dataPoints.size - 1)) * width
                    } else {
                        width / 2f
                    }
                    val prevY = height - ((dataPoints[prevIndex] - minVal) / range * height)
                    
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

            if (isDragging && selectedIndex != null) {
                val x = animatedX.value
                drawLine(
                    color = lineColor.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 2.dp.toPx()
                )
            }

            selectedIndex?.let { index ->
                val x = animatedX.value
                val y = height - ((dataPoints[index] - minVal) / range * height)
                
                drawCircle(
                    color = lineColor.copy(alpha = 0.3f),
                    radius = 14.dp.toPx(),
                    center = Offset(x, y)
                )
                
                drawCircle(
                    color = lineColor.copy(alpha = 0.6f),
                    radius = 10.dp.toPx(),
                    center = Offset(x, y)
                )
                
                drawCircle(
                    color = lineColor,
                    radius = 6.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}
