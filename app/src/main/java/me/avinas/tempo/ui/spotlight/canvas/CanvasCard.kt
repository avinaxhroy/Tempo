package me.avinas.tempo.ui.spotlight.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.spotlight.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A draggable, resizable card on the canvas.
 * Renders any SpotlightCardData type with transform controls.
 * 
 * Uses proven gesture patterns:
 * - Separate tap detection (for selection)
 * - Transform detection for pan/zoom/rotate 
 * - Proper state isolation during interaction
 */
@Composable
fun CanvasCard(
    item: CanvasCardItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTransform: (offsetX: Float, offsetY: Float, scale: Float, rotation: Float) -> Unit,
    onDelete: () -> Unit,
    canvasSize: androidx.compose.ui.unit.IntSize,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    // Selection animation
    val selectionScale = remember { Animatable(1f) }
    LaunchedEffect(isSelected) {
        if (isSelected) {
            selectionScale.animateTo(1.02f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            selectionScale.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
    }

    // Track if user is actively interacting (to prevent external state sync during gesture)
    var isTransforming by remember { mutableStateOf(false) }
    
    // Local transform state - source of truth during interaction
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

    // Snapping state
    var isSnappedX by remember { mutableStateOf(false) }
    var isSnappedY by remember { mutableStateOf(false) }
    var isSnappedRotation by remember { mutableStateOf(false) }
    
    // Card size for center calculation
    var cardSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { cardSize = it.size }
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .graphicsLayer {
                scaleX = scale * selectionScale.value
                scaleY = scale * selectionScale.value
                rotationZ = rotation
            }
            // Transform gestures (drag, pinch, rotate)
            .pointerInput(item.id) {
                detectTransformGesturesWithCallbacks(
                    onGestureStart = {
                        isTransforming = true
                        onSelect()
                    },
                    onGestureEnd = {
                        isTransforming = false
                        isSnappedX = false
                        isSnappedY = false
                        isSnappedRotation = false
                    },
                    onGesture = { _, panDelta, zoomDelta, rotationDelta ->
                        // Update local state
                        offsetX += panDelta.x
                        offsetY += panDelta.y
                        scale = (scale * zoomDelta).coerceIn(0.3f, 1.5f)
                        rotation += rotationDelta
                        
                        // Calculate snap targets
                        var targetX = offsetX
                        var targetY = offsetY
                        var targetRotation = rotation
                        
                        // Position snapping to canvas center
                        if (cardSize.width > 0 && canvasSize.width > 0) {
                            val cardCenterX = offsetX + (cardSize.width * scale / 2f)
                            val cardCenterY = offsetY + (cardSize.height * scale / 2f)
                            val canvasCenterX = canvasSize.width / 2f
                            val canvasCenterY = canvasSize.height / 2f
                            val snapThreshold = 24.dp.toPx()
                            
                            if (abs(cardCenterX - canvasCenterX) < snapThreshold) {
                                targetX = canvasCenterX - (cardSize.width * scale / 2f)
                                if (!isSnappedX) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isSnappedX = true
                                }
                            } else {
                                isSnappedX = false
                            }
                            
                            if (abs(cardCenterY - canvasCenterY) < snapThreshold) {
                                targetY = canvasCenterY - (cardSize.height * scale / 2f)
                                if (!isSnappedY) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isSnappedY = true
                                }
                            } else {
                                isSnappedY = false
                            }
                        }
                        
                        // Rotation snapping (0, 90, 180, 270)
                        val snapThresholdRot = 5f
                        val normalizedRot = (rotation % 360).let { if (it < 0) it + 360 else it }
                        val distTo90 = normalizedRot % 90
                        val nearest90 = if (distTo90 < 45) {
                            rotation - distTo90
                        } else {
                            rotation + (90 - distTo90)
                        }
                        
                        if (abs(rotation - nearest90) < snapThresholdRot) {
                            targetRotation = nearest90
                            if (!isSnappedRotation) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isSnappedRotation = true
                            }
                        } else {
                            isSnappedRotation = false
                        }
                        
                        // Propagate to parent
                        onTransform(targetX, targetY, scale, targetRotation)
                    }
                )
            }
            // Tap gesture (for selection when not transforming)
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onLongPress = {
                        onSelect()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }
            .then(
                if (isSelected) {
                    Modifier
                        .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                        .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(32.dp))
                } else Modifier
            )
    ) {
        // Card content
        Box(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight()
        ) {
            when (val cardData = item.cardData) {
                is SpotlightCardData.CosmicClock -> DashboardCosmicClockCard(data = cardData, showShareButton = false)
                is SpotlightCardData.WeekendWarrior -> DashboardWeekendWarriorCard(data = cardData, showShareButton = false)
                is SpotlightCardData.ForgottenFavorite -> DashboardForgottenFavoriteCard(data = cardData, showShareButton = false)
                is SpotlightCardData.DeepDive -> DashboardDeepDiveCard(data = cardData, showShareButton = false)
                is SpotlightCardData.NewObsession -> DashboardNewObsessionCard(data = cardData, showShareButton = false)
                is SpotlightCardData.EarlyAdopter -> DashboardEarlyAdopterCard(data = cardData, showShareButton = false)
                is SpotlightCardData.ListeningPeak -> DashboardListeningPeakCard(data = cardData, showShareButton = false)
                is SpotlightCardData.ArtistLoyalty -> DashboardArtistLoyaltyCard(data = cardData, showShareButton = false)
                is SpotlightCardData.Discovery -> DashboardDiscoveryCard(data = cardData, showShareButton = false)
            }
        }
        
        // Delete button (only when selected)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove card",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Preview card thumbnail for the picker
 */
@Composable
fun CardPickerThumbnail(
    cardData: SpotlightCardData,
    isOnCanvas: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .width(150.dp)
            .height(180.dp)
            .clickable(enabled = !isOnCanvas, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        variant = if (isOnCanvas) GlassCardVariant.LowProminence else GlassCardVariant.HighProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scaled down preview
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(0.4f)
                    .wrapContentSize(unbounded = true)
                    .graphicsLayer { alpha = if (isOnCanvas) 0.3f else 1f }
            ) {
                when (cardData) {
                    is SpotlightCardData.CosmicClock -> DashboardCosmicClockCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.WeekendWarrior -> DashboardWeekendWarriorCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.ForgottenFavorite -> DashboardForgottenFavoriteCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.DeepDive -> DashboardDeepDiveCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.NewObsession -> DashboardNewObsessionCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.EarlyAdopter -> DashboardEarlyAdopterCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.ListeningPeak -> DashboardListeningPeakCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.ArtistLoyalty -> DashboardArtistLoyaltyCard(data = cardData, showShareButton = false)
                    is SpotlightCardData.Discovery -> DashboardDiscoveryCard(data = cardData, showShareButton = false)
                }
            }
            
            // Checkmark for cards already on canvas
            if (isOnCanvas) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(Color(0xFF60A5FA), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Added",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            // Card name label
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = getCardDisplayName(cardData),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun getCardDisplayName(cardData: SpotlightCardData): String {
    return when (cardData) {
        is SpotlightCardData.CosmicClock -> "Circadian Rhythm"
        is SpotlightCardData.WeekendWarrior -> "Weekly Pulse"
        is SpotlightCardData.ForgottenFavorite -> "Forgotten Favorite"
        is SpotlightCardData.DeepDive -> "Sonic Immersion"
        is SpotlightCardData.NewObsession -> "New Obsession"
        is SpotlightCardData.EarlyAdopter -> "Early Access Pass"
        is SpotlightCardData.ListeningPeak -> "Harmonic Resonance"
        is SpotlightCardData.ArtistLoyalty -> "Deep Cut"
        is SpotlightCardData.Discovery -> "Sonic Horizon"
    }
}

/**
 * Robust transform gesture detector with start/end callbacks.
 * Based on Jetpack Compose's official detectTransformGestures implementation.
 * 
 * This is the proven pattern from the Android framework.
 */
suspend fun PointerInputScope.detectTransformGesturesWithCallbacks(
    panZoomLock: Boolean = false,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var lockedToPanZoom = false
        var gestureStarted = false

        awaitFirstDown(requireUnconsumed = false)
        
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) {
                if (gestureStarted) onGestureEnd()
                break
            }

            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange

                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1 - zoom) * centroidSize
                val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                val panMotion = pan.getDistance()

                if (zoomMotion > touchSlop || rotationMotion > touchSlop || panMotion > touchSlop) {
                    pastTouchSlop = true
                    lockedToPanZoom = panZoomLock && rotationMotion < touchSlop
                    
                    // Fire gesture start callback
                    if (!gestureStarted) {
                        gestureStarted = true
                        onGestureStart()
                    }
                }
            }

            if (pastTouchSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                val effectiveRotation = if (lockedToPanZoom) 0f else rotationChange
                
                if (effectiveRotation != 0f || zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(centroid, panChange, zoomChange, effectiveRotation)
                }
                
                event.changes.forEach { 
                    if (it.positionChanged()) {
                        it.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })
        
        if (gestureStarted) {
            onGestureEnd()
        }
    }
}

// Extension to check if position changed
private fun PointerInputChange.positionChanged(): Boolean {
    return previousPosition != position
}

// Extension to calculate centroid
private fun PointerEvent.calculateCentroid(useCurrent: Boolean): Offset {
    var centroid = Offset.Zero
    var centroidWeight = 0
    changes.forEach { change ->
        if (change.pressed && change.previousPressed) {
            val position = if (useCurrent) change.position else change.previousPosition
            centroid += position
            centroidWeight++
        }
    }
    return if (centroidWeight > 0) centroid / centroidWeight.toFloat() else Offset.Unspecified
}
