package me.avinas.tempo.ui.spotlight

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import android.util.Log
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.CaptureController
import me.avinas.tempo.ui.components.CaptureWrapper
import me.avinas.tempo.ui.components.rememberCaptureController
import me.avinas.tempo.utils.ShareUtils
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotlightStoryScreen(
    storyPages: List<SpotlightStoryPage>,
    onClose: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { storyPages.size })
    val coroutineScope = rememberCoroutineScope()
    val currentProgress = remember { Animatable(0f) }
    var isPaused by remember { mutableStateOf(false) }
    
    // Audio Controller
    val context = LocalContext.current
    val audioController = remember { SpotlightAudioController(context = context, scope = coroutineScope) }
    val isMuted by audioController.isMuted.collectAsState()
    
    DisposableEffect(Unit) {
        onDispose { audioController.release() }
    }
    
    // Manage Audio Playback
    
    // Preload the first track immediately to reduce latency
    LaunchedEffect(Unit) {
        // Find the first page that has a preview URL (usually the Intro)
        val firstAudioPage = storyPages.firstOrNull { it.previewUrl != null }
        val preloadUrl = firstAudioPage?.previewUrl
        
        if (preloadUrl != null) {
            Log.d("SpotlightStoryScreen", "Preloading audio: $preloadUrl")
            audioController.prepare(preloadUrl)
        }
    }
    
    LaunchedEffect(pagerState.currentPage) {
            val page = storyPages.getOrNull(pagerState.currentPage)
            val previewUrl = page?.previewUrl
            
            Log.d("SpotlightStoryScreen", "Page: ${page?.id}, PreviewUrl: $previewUrl")
            
            if (previewUrl != null) {
                if (page is SpotlightStoryPage.TopTrackSetup) {
                    // Lower volume build-up for the top track reveal
                    audioController.playSetup(previewUrl)
                } else {
                    // Standard/High volume for all other pages (Intro, Highlight, Genres, etc.)
                    audioController.playHighlight(previewUrl)
                }
            } else {
                Log.d("SpotlightStoryScreen", "Page has NULL previewUrl, stopping audio")
                audioController.fadeOutAndStop()
            }
    }

    // Determine colors based on current page
    val currentPage = storyPages.getOrNull(pagerState.currentPage)
    val (primaryColor, secondaryColor, tertiaryColor) = when (currentPage) {
        is SpotlightStoryPage.ListeningMinutes -> Triple(
            Color(0xFF8B5CF6), // Warm Violet
            Color(0xFFA855F7), // Purple
            Color(0xFF6366F1)  // Indigo (subtle cooler tone)
        )
        is SpotlightStoryPage.TopArtist -> Triple(
            Color(0xFFEC4899), // Pink
            Color(0xFFBE185D), // Dark Pink
            Color(0xFF831843)  // Burgundy
        )
        is SpotlightStoryPage.TopSongs -> Triple(
            Color(0xFFF59E0B), // Amber
            Color(0xFFD97706), // Dark Amber
            Color(0xFFB45309)  // Brown/Orange
        )
        is SpotlightStoryPage.TopTrackSetup -> Triple(
            Color(0xFFF59E0B), // Amber
            Color(0xFFD97706), // Dark Amber
            Color(0xFFB45309)  // Brown/Orange
        )
        is SpotlightStoryPage.TopGenres -> Triple(
            Color(0xFF10B981), // Emerald
            Color(0xFF059669), // Dark Emerald
            Color(0xFF047857)  // Darker Emerald
        )
        is SpotlightStoryPage.Personality -> Triple(
            Color(0xFF8B5CF6), // Violet
            Color(0xFF7C3AED), // Dark Violet
            Color(0xFF6D28D9)  // Darker Violet
        )
        is SpotlightStoryPage.Conclusion -> Triple(
            Color(0xFFF472B6), // Pink
            Color(0xFF60A5FA), // Blue
            Color(0xFF34D399)  // Green
        )
        null -> Triple(Color(0xFF8B5CF6), Color(0xFFA855F7), Color(0xFF6366F1))
    }

    val captureController = rememberCaptureController()

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            val success = ShareUtils.shareBitmap(context, bitmap)
            if (!success) {
                android.widget.Toast.makeText(context, "Failed to share story", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Reset progress when page changes
    LaunchedEffect(pagerState.currentPage) {
        currentProgress.snapTo(0f)
    }

    // Auto-advance logic with pause support
    LaunchedEffect(pagerState.currentPage, isPaused) {
        if (!isPaused) {
             val remainingTime = (1f - currentProgress.value) * 15000
             if (remainingTime > 0) {
                 currentProgress.animateTo(
                     targetValue = 1f,
                     animationSpec = tween(durationMillis = remainingTime.toInt(), easing = LinearEasing)
                 )
                 if (pagerState.currentPage < storyPages.size - 1) {
                     // Use coroutineScope to ensure animation isn't cancelled when currentPage changes
                     coroutineScope.launch {
                         pagerState.animateScrollToPage(pagerState.currentPage + 1)
                     }
                 } else {
                     onClose()
                 }
             }
        }
    }

    // Main Content Box
    Box(modifier = Modifier.fillMaxSize()) {
        
        // 0. Shadow Renderer (For Sharing) - Hidden offscreen
        // This renders the CURRENT page at exactly 1080x1920 px for consistent sharing
        val shadowPage = storyPages.getOrNull(pagerState.currentPage)
        if (shadowPage != null) {
            ShadowStoryRenderer(
                page = shadowPage,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                tertiaryColor = tertiaryColor,
                captureController = captureController
            )
        }
        
        // 1. Visible Content (Background + Pager)
        // No CaptureWrapper here anymore
        SpotlightStoryBackground(
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false // Disable swipe to rely on taps
                ) { pageIndex ->
                    val page = storyPages[pageIndex]
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (page) {
                            is SpotlightStoryPage.ListeningMinutes -> ListeningMinutesPage(page)
                            is SpotlightStoryPage.TopArtist -> TopArtistPage(page)
                            is SpotlightStoryPage.TopTrackSetup -> TopTrackSetupPage(page)
                            is SpotlightStoryPage.TopSongs -> TopSongsPage(page)
                            is SpotlightStoryPage.TopGenres -> TopGenresPage(page)
                            is SpotlightStoryPage.Personality -> PersonalityPage(page)
                            is SpotlightStoryPage.Conclusion -> ConclusionPage(page)
                        }
                    }
                }
                
                // Watermark (Visible on screen too, optional but keeps consistency)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Tempo Spotlight",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                blurRadius = 4f
                            )
                        ),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // 2. Gesture Overlay (Interactive layer, NOT captured)
        // Placed ABOVE Content to intercept touches throughout the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPaused = true
                            tryAwaitRelease()
                            isPaused = false
                        },
                        onTap = { offset ->
                            val width = size.width
                            coroutineScope.launch {
                                if (offset.x < width * 0.3f) {
                                    // Previous
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                } else {
                                    // Next
                                    if (pagerState.currentPage < storyPages.size - 1) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    } else {
                                        onClose()
                                    }
                                }
                            }
                        }
                    )
                }
        )

        // 3. UI Overlays (Top Bar, Share Button, etc.)
        
        // Top Bar (Progress & Close)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            // Progress Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                storyPages.forEachIndexed { index, _ ->
                    val progress = when {
                        index < pagerState.currentPage -> 1f
                        index == pagerState.currentPage -> currentProgress.value
                        else -> 0f
                    }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Spacer(modifier = Modifier.height(24.dp))
            
            // Top Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute Button
                IconButton(
                    onClick = { audioController.toggleMute() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        // Share Button (Bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp) // Moved up slightly to avoid overlapping watermark if visible
        ) {
             Button(
                onClick = { captureController.capture() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Share Your Story",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ShadowStoryRenderer(
    page: SpotlightStoryPage,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    captureController: CaptureController
) {
    // 1. Force Density for consistent scale (Pixel 2 XL density approx)
    val targetDensity = androidx.compose.ui.unit.Density(2.75f)
    
    // 2. Hide offscreen (safe culling avoidance)
    Box(
        modifier = Modifier
            .offset(x = 10000.dp)
            .wrapContentSize(unbounded = true)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides targetDensity
        ) {
            // 3. Custom Layout to force 1080x1920 measurement
            androidx.compose.ui.layout.Layout(
                content = {
                    CaptureWrapper(
                        controller = captureController,
                        modifier = Modifier.size(392.dp, 698.dp) // 1080px/2.75, 1920px/2.75 (Must match desired pixel / density)
                    ) {
                         SpotlightStoryBackground(
                            primaryColor = primaryColor,
                            secondaryColor = secondaryColor,
                            tertiaryColor = tertiaryColor
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (page) {
                                    is SpotlightStoryPage.ListeningMinutes -> ListeningMinutesPage(page)
                                    is SpotlightStoryPage.TopArtist -> TopArtistPage(page)
                                    is SpotlightStoryPage.TopTrackSetup -> TopTrackSetupPage(page)
                                    is SpotlightStoryPage.TopSongs -> TopSongsPage(page)
                                    is SpotlightStoryPage.TopGenres -> TopGenresPage(page)
                                    is SpotlightStoryPage.Personality -> PersonalityPage(page)
                                    is SpotlightStoryPage.Conclusion -> ConclusionPage(page)
                                }
                                
                                // Watermark
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 24.dp)
                                ) {
                                    Text(
                                        text = "Tempo Spotlight",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                                blurRadius = 4f
                                            )
                                        ),
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            ) { measurables, _ ->
                // Force measure to exactly 1080x1920 pixels
                // We derived the Dp size above (392x698) based on density 2.75
                // 392 * 2.75 = 1078. 698 * 2.75 = 1919.5. Close enough.
                // Better: rely on fixed Pixel size if possible? 
                // CaptureWrapper captures the View. The View size is determined by the Compose layout.
                // If we specify Modifier.size(392.72.dp, 698.18.dp), we get 1080x1920.
                
                val widthPx = 1080
                val heightPx = 1920
                
                val placeable = measurables[0].measure(
                     androidx.compose.ui.unit.Constraints.fixed(widthPx, heightPx)
                )
                
                layout(widthPx, heightPx) {
                    placeable.place(0, 0)
                }
            }
        }
    }
}
