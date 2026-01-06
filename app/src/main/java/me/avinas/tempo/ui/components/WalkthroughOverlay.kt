package me.avinas.tempo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.ui.theme.TempoRed
import javax.inject.Inject
import javax.inject.Singleton

enum class WalkthroughStep {
    NONE,
    HOME_SPOTLIGHT,      // "Tap here to view your daily music story"
    STATS_SORT,          // "Tap here to change how your stats are sorted"
    STATS_ITEM_CLICK,    // "Tap an item to see more details"
    HISTORY_FILTER       // "Long press to filter specific content"
}

// CompositionLocal to allow screens to register targets
val LocalWalkthroughController = staticCompositionLocalOf<WalkthroughController> {
    error("No WalkthroughController provided")
}

@Singleton
class WalkthroughController @Inject constructor(
    private val userPreferencesDao: UserPreferencesDao
) {
    private val _currentStep = MutableStateFlow(WalkthroughStep.NONE)
    val currentStep: StateFlow<WalkthroughStep> = _currentStep.asStateFlow()

    private val _targetRect = MutableStateFlow<Rect?>(null)
    val targetRect: StateFlow<Rect?> = _targetRect.asStateFlow()

    // Cache preferences to avoid constant DB reads
    private var cachedPrefs: UserPreferences? = null

    // Register a target's position. Called by onGloballyPositioned.
    // Screens call this constantly when composed, so we only update if needed.
    fun registerTarget(step: WalkthroughStep, coordinates: LayoutCoordinates) {
        if (_currentStep.value == step) {
            val position = coordinates.positionInRoot()
            val size = coordinates.size
            _targetRect.value = Rect(
                offset = position,
                size = Size(size.width.toFloat(), size.height.toFloat())
            )
        }
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    // Attempt to start a tutorial. Checks internal conditions.
    suspend fun checkAndTrigger(step: WalkthroughStep) {
        mutex.withLock {
            // Don't interrupt existing
            if (_currentStep.value != WalkthroughStep.NONE) return@withLock
            
            if (cachedPrefs == null) {
                cachedPrefs = userPreferencesDao.getSync() ?: UserPreferences()
            }
            
            val shouldShow = when (step) {
                WalkthroughStep.HOME_SPOTLIGHT -> !(cachedPrefs?.hasSeenSpotlightTutorial ?: false)
                WalkthroughStep.STATS_SORT -> !(cachedPrefs?.hasSeenStatsSortTutorial ?: false)
                WalkthroughStep.STATS_ITEM_CLICK -> {
                    // Dependency: Only show Item Click if Sort is done (optional logic, keeping simple for now)
                    !(cachedPrefs?.hasSeenStatsItemClickTutorial ?: false)
                }
                WalkthroughStep.HISTORY_FILTER -> !(cachedPrefs?.hasSeenHistoryCoachMark ?: false)
                WalkthroughStep.NONE -> false
            }

            if (shouldShow) {
                _currentStep.value = step
            }
        }
    }
    // Scope for background tasks (fire-and-forget UI updates)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    // ... (existing code)

    // Non-suspend helper for UI callbacks
    fun dismiss() {
        scope.launch {
            dismissCurrent()
        }
    }

    // Dismiss logic.
    // If 'completed' is true, we mark it as seen in DB.
    // If 'completed' is false (skipped), we ALSO mark it as seen (per "Dismiss forever" rule).
    suspend fun dismissCurrent() {
        val finishingStep = _currentStep.value
        if (finishingStep == WalkthroughStep.NONE) return

        // Mark as seen regardless of how it ended (Interaction or Dismiss)
        // This ensures "Not Annoying" - users won't see it again.
        val current = userPreferencesDao.getSync() ?: UserPreferences()
        val updated = when (finishingStep) {
            WalkthroughStep.HOME_SPOTLIGHT -> current.copy(hasSeenSpotlightTutorial = true)
            WalkthroughStep.STATS_SORT -> current.copy(hasSeenStatsSortTutorial = true)
            WalkthroughStep.STATS_ITEM_CLICK -> current.copy(hasSeenStatsItemClickTutorial = true)
            WalkthroughStep.HISTORY_FILTER -> current.copy(hasSeenHistoryCoachMark = true)
            WalkthroughStep.NONE -> current
        }
        userPreferencesDao.upsert(updated)
        cachedPrefs = updated
        
        // Reset state
        _currentStep.value = WalkthroughStep.NONE
        _targetRect.value = null
    }
}

@Composable
fun WalkthroughOverlay(
    controller: WalkthroughController,
    content: @Composable () -> Unit
) {
    val currentStep by controller.currentStep.collectAsState()
    val targetRect by controller.targetRect.collectAsState()
    val scope = rememberCoroutineScope()
    
    // Provide the controller to children
    CompositionLocalProvider(LocalWalkthroughController provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            
            // The Overlay
            if (currentStep != WalkthroughStep.NONE && targetRect != null) {
                SpotlightLayer(
                    rect = targetRect!!,
                    step = currentStep,
                    onBackgroundClick = {
                        // User tapped outside -> Dismiss forever (Skip)
                        // Using Launch here because onBackgroundClick is a callback
                        scope.launch { 
                            controller.dismissCurrent()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SpotlightLayer(
    rect: Rect,
    step: WalkthroughStep,
    onBackgroundClick: () -> Unit
) {
    val density = LocalDensity.current
    
    // Animation for the "Hole" entry
    val transition = updateTransition(targetState = true, label = "SpotlightEntry")
    val alpha by transition.animateFloat(label = "alpha") { if (it) 1f else 0f }
    
    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(999f) // Top of everything
    ) {
        // 1. The Dark Background (With Hole)
        // We use 4 boxes to create a "Hollow" rect.
        // All these boxes have onBackgroundClick to allow dismissal.
        
        val bgColor = Color.Black.copy(alpha = 0.8f * alpha)
        val interactionSource = remember { MutableInteractionSource() }

        // Top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { rect.top.toDp() })
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackgroundClick
                )
        )
        
        // Bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = with(density) { rect.bottom.toDp() })
                .fillMaxHeight()
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackgroundClick
                )
        )
        
        // Left
        Box(
            modifier = Modifier
                .width(with(density) { rect.left.toDp() })
                .height(with(density) { rect.height.toDp() })
                .offset { IntOffset(0, rect.top.toInt()) }
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackgroundClick
                )
        )
        
        // Right
        Box(
            modifier = Modifier
                .fillMaxWidth() // Fill remaining width
                .padding(start = with(density) { rect.right.toDp() })
                .height(with(density) { rect.height.toDp() })
                .offset { IntOffset(0, rect.top.toInt()) }
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackgroundClick
                )
        )
        
        // 2. The Visual Pulse Ring (Drawn over the hole, passes touches through)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = rect.center
            val radius = maxOf(rect.width, rect.height) / 2f + 16.dp.toPx()
            
            drawCircle(
                color = TempoRed,
                radius = radius * pulseScale,
                center = center,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                alpha = 0.6f * alpha
            )
        }
        
        // 3. Instruction Text
        // Dynamic positioning: If target is in top half, show text below. If bottom half, show above.
        val isTopHalf = rect.center.y < (this.constraints.maxHeight / 2)
        val textOffestY = if (isTopHalf) {
            rect.bottom + 32.dp.value * density.density
        } else {
            rect.top - 100.dp.value * density.density
        }
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, textOffestY.toInt()) }
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = getInstructionText(step),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = getSubInstructionText(step),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

fun getInstructionText(step: WalkthroughStep): String {
    return when(step) {
        WalkthroughStep.HOME_SPOTLIGHT -> "Your Daily Story"
        WalkthroughStep.STATS_SORT -> "Analyze Your Data"
        WalkthroughStep.STATS_ITEM_CLICK -> "Go Deeper"
        WalkthroughStep.HISTORY_FILTER -> "Filter Your History"
        WalkthroughStep.NONE -> ""
    }
}

fun getSubInstructionText(step: WalkthroughStep): String {
    return when(step) {
        WalkthroughStep.HOME_SPOTLIGHT -> "Tap here to watch your music journey unfold."
        WalkthroughStep.STATS_SORT -> "Change how your stats are sorted to see new patterns."
        WalkthroughStep.STATS_ITEM_CLICK -> "Tap any item to reveal detailed analytics and moods."
        WalkthroughStep.HISTORY_FILTER -> "Long press any song to tag it as a Podcast or Audiobook."
        WalkthroughStep.NONE -> ""
    }
}
