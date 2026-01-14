package me.avinas.tempo.ui.spotlight.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A draggable, editable text element on the canvas.
 * 
 * Uses the same robust gesture pattern as CanvasCard.
 */
@Composable
fun CanvasText(
    item: CanvasTextItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTransform: (offsetX: Float, offsetY: Float, scale: Float, rotation: Float) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    // Track if user is actively interacting
    var isTransforming by remember { mutableStateOf(false) }
    
    // Local transform state
    var offsetX by remember(item.id) { mutableFloatStateOf(item.offsetX) }
    var offsetY by remember(item.id) { mutableFloatStateOf(item.offsetY) }
    var scale by remember(item.id) { mutableFloatStateOf(item.scale) }
    var rotation by remember(item.id) { mutableFloatStateOf(item.rotation) }
    
    // Sync with external state only when NOT transforming
    LaunchedEffect(item.offsetX, item.offsetY, item.scale, item.rotation) {
        if (!isTransforming) {
            offsetX = item.offsetX
            offsetY = item.offsetY
            scale = item.scale
            rotation = item.rotation
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            }
            // Transform gestures
            .pointerInput(item.id) {
                detectTransformGesturesWithCallbacks(
                    onGestureStart = {
                        isTransforming = true
                        onSelect()
                    },
                    onGestureEnd = {
                        isTransforming = false
                    },
                    onGesture = { _, panDelta, zoomDelta, rotationDelta ->
                        offsetX += panDelta.x
                        offsetY += panDelta.y
                        scale = (scale * zoomDelta).coerceIn(0.5f, 3f)
                        rotation += rotationDelta
                        onTransform(offsetX, offsetY, scale, rotation)
                    }
                )
            }
            // Tap gestures
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onEdit() },
                    onLongPress = {
                        onSelect()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0xFF60A5FA),
                        shape = RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
            .padding(if (isSelected) 4.dp else 0.dp)
    ) {
        // Text with styling
        val style = item.style
        val textStyle = TextStyle(
            fontFamily = style.fontPreset.fontFamily,
            fontSize = style.fontSize.sp,
            fontWeight = style.fontWeight,
            fontStyle = style.fontStyle,
            color = style.color,
            textAlign = style.alignment,
            shadow = if (style.hasShadow) Shadow(
                color = Color.Black.copy(alpha = 0.5f),
                offset = Offset(2f, 2f),
                blurRadius = 4f
            ) else null
        )
        
        Box(
            modifier = Modifier
                .then(
                    if (style.hasBackground) {
                        Modifier
                            .background(
                                style.backgroundColor,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    } else Modifier
                )
        ) {
            if (style.hasOutline) {
                // Draw outline by rendering text multiple times with offset
                Box {
                    // Outline layer
                    Text(
                        text = item.text,
                        style = textStyle.copy(color = style.outlineColor),
                        modifier = Modifier.offset(1.dp, 0.dp)
                    )
                    Text(
                        text = item.text,
                        style = textStyle.copy(color = style.outlineColor),
                        modifier = Modifier.offset((-1).dp, 0.dp)
                    )
                    Text(
                        text = item.text,
                        style = textStyle.copy(color = style.outlineColor),
                        modifier = Modifier.offset(0.dp, 1.dp)
                    )
                    Text(
                        text = item.text,
                        style = textStyle.copy(color = style.outlineColor),
                        modifier = Modifier.offset(0.dp, (-1).dp)
                    )
                    // Main text on top
                    Text(
                        text = item.text,
                        style = textStyle
                    )
                }
            } else {
                Text(
                    text = item.text,
                    style = textStyle
                )
            }
        }
        
        // Control buttons (only when selected)
        if (isSelected) {
            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(28.dp)
                    .background(Color(0xFFEF4444), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove text",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
