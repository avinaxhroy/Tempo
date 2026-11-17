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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Pause
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.asShareArt
import me.avinas.tempo.ui.components.share.ShareCardSheet
import me.avinas.tempo.ui.components.share.ShareCardSurface
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.ui.components.share.ShareItemCountDeck
import me.avinas.tempo.ui.components.share.SharePage
import me.avinas.tempo.ui.components.share.rememberShareCardPalettes
import me.avinas.tempo.ui.components.share.ShareCardBrandingInset
import me.avinas.tempo.ui.components.share.ShareStorySafeTop

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotlightStoryScreen(
    storyPages: List<SpotlightStoryPage>,
    onClose: () -> Unit
) {
    // Freeze the page list for the whole playback session. The ViewModel regenerates
    // story pages reactively on every new listening event; if the live list were used,
    // a scrobble landing mid-story could change the page count (optional slides vary),
    // clamping the pager's current page and restarting the timer mid-playback.
    val storyPages = remember { storyPages }
    val pagerState = rememberPagerState(pageCount = { storyPages.size })
    val coroutineScope = rememberCoroutineScope()
    val currentProgress = remember { Animatable(0f) }
    var isPaused by remember { mutableStateOf(false) }

    // Sharing is a modal over the story: the sheet covers the screen, so the
    // timer must not advance behind it or the user returns to a different slide
    // than the one they were customising.
    var isSharing by remember { mutableStateOf(false) }

    // How many artists and songs the closing slide lists. Only the closing slide
    // reads it, and only its sheet offers the control — every other slide has
    // nothing to configure, which is why the sheet shows no deck for them.
    var conclusionItemCount by remember { mutableStateOf(ShareItemCount.TOP_5) }

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

    // The slide's narrative colours. The script itself lives in
    // `SpotlightStoryColors.kt`, because the share sheet needs to know what the
    // current slide's backdrop is painted in to offer it as a choice.
    val currentPage = storyPages.getOrNull(pagerState.currentPage)
    val storyColors = spotlightStoryColors(currentPage)

    // Tracks which page the current progress bar value belongs to. Progress must be
    // reset inside the auto-advance coroutine itself, not in a separate LaunchedEffect:
    // a sibling reset effect races this one over the Animatable's mutex, and if the
    // timer reads the previous page's near-1f progress before the snap lands, the
    // remaining duration collapses to ~0ms and the story skips pages instantly.
    val timerPageIndex = remember { mutableIntStateOf(-1) }

    // Tracks viewed pages so re-visits skip entry animations
    val seenPages = remember { SnapshotStateMap<String, Boolean>() }
    var lastViewedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != lastViewedIndex) {
            storyPages.getOrNull(lastViewedIndex)?.let { seenPages[it.id] = true }
            lastViewedIndex = pagerState.currentPage
        }
    }

    // Auto-advance logic with pause support
    LaunchedEffect(pagerState.currentPage, isPaused, isSharing) {
        if (!isPaused && !isSharing) {             if (timerPageIndex.value != pagerState.currentPage) {
                 currentProgress.snapTo(0f)
                 timerPageIndex.value = pagerState.currentPage
             }
             val remainingTime = (1f - currentProgress.value) * 15000
             if (remainingTime > 0) {
                 currentProgress.animateTo(
                     targetValue = 1f,
                     animationSpec = tween(durationMillis = remainingTime.toInt(), easing = LinearEasing)
                 )
                 if (pagerState.currentPage < storyPages.size - 1) {
                     // Launch in outer coroutineScope so scroll animation survives currentPage change
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

        // 1. Visible Content (Background + Pager)
        SpotlightStoryBackground(
            primaryColor = storyColors.primary,
            secondaryColor = storyColors.secondary,
            tertiaryColor = storyColors.tertiary
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false // Disable swipe to rely on taps
                ) { pageIndex ->
                    val page = storyPages[pageIndex]
                    Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalStorySeenPages provides seenPages,
                            LocalStoryPageId provides page.id
                        ) {
                            // One mapping for the pager, the share card and the
                            // export: eighteen branches used to be written out
                            // here and again in the offscreen renderer.
                            SpotlightStoryPageContent(
                                page = page,
                                showWatermark = false,
                                conclusionItemCount = conclusionItemCount.count,
                            )
                        }
                    }
                }

                // Watermark (Visible on screen too, optional but keeps consistency)
                StoryWatermark(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
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

        // Visual indicator shown during hold-to-pause
        AnimatedVisibility(
            visible = isPaused,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.spotlight_paused),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        // Top Bar (Progress & Close)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            // Progress Indicators
            StoryProgressRow(
                pageCount = storyPages.size,
                currentPage = pagerState.currentPage,
                progress = { currentProgress.value },
                modifier = Modifier.fillMaxWidth(),
            )

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
                onClick = { if (!isSharing) isSharing = true },
                enabled = !isSharing,
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
                    stringResource(R.string.spotlight_share_your_story),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // The share sheet. Same component, same capture pipeline and same gallery
    // write as every card surface in the app; the only thing that differs is the
    // strip it offers, whose first entry is the story's own backdrop rather than
    // a card theme.
    if (isSharing && currentPage != null) {
        val storyChoices = rememberStoryShareChoices(storyColors)
        val storyChoice = rememberStoryShareChoice(storyColors)
        ShareCardSheet(
            onDismiss = { isSharing = false },
            title = stringResource(R.string.spotlight_share_title),
            artworkUrls = storyShareArtwork(currentPage),
            choices = storyChoices,
            initial = storyChoice,
            // A story slide has nothing to configure — except the closing slide,
            // where list length matters: three rows of large print is a different
            // slide from ten rows of small.
            controls =
                if (currentPage is SpotlightStoryPage.Conclusion) {
                    { enabled ->
                        ShareItemCountDeck(
                            count = conclusionItemCount,
                            onCountChange = { conclusionItemCount = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            label = stringResource(R.string.spotlight_conclusion_items_label),
                            enabled = enabled,
                        )
                    }
                } else {
                    null
                },
        ) { page ->
            StorySharePage(
                storyPage = currentPage,
                colors = storyColors,
                conclusionItemCount = conclusionItemCount.count,
                page = page,
            )
        }
    }
}

/**
 * The cover a gradient theme should sample, per slide.
 *
 * The story's own backdrop ignores it, but the card themes are built around
 * artwork — the ones that blur a cover or pull their palette from one would sit
 * on a blank page without it. Mirrors the lead-art mapping the story ring uses,
 * so the slide the user came from is the cover the card gets.
 */
private fun storyShareArtwork(page: SpotlightStoryPage?): List<String?> =
    listOfNotNull(
        when (page) {
            is SpotlightStoryPage.TopArtist -> page.topArtistImageUrl
            is SpotlightStoryPage.TopTrackSetup -> page.topSongImageUrl
            is SpotlightStoryPage.TopSongs -> page.topSongImageUrl
            is SpotlightStoryPage.TopAlbum -> page.albumArtUrl
            is SpotlightStoryPage.Conclusion ->
                page.topSongs.firstOrNull()?.imageUrl ?: page.topArtists.firstOrNull()?.imageUrl
            else -> null
        }?.takeIf { it.isNotBlank() }
    )

/**
 * One story slide as a share card.
 *
 * Two shapes, because the strip offers two kinds of choice. The story backdrop is
 * the slide exactly as it plays, over its own drifting background. A card theme
 * puts the same slide on that theme's card — and since a theme may be light, the
 * slide's ink is switched over to the theme's palette; without that, white type
 * on a paper card is invisible.
 */
@Composable
private fun StorySharePage(
    storyPage: SpotlightStoryPage,
    colors: SpotlightStoryColors,
    conclusionItemCount: Int,
    page: SharePage,
) {
    // The frame a slide gets when it is a picture rather than a screen: the same
    // story-safe top margin the card sheets use, so the host app's username row
    // does not land on the heading, and just enough bottom room for the credit.
    // Without it the pages keep their player padding, which leaves a fifth of the
    // export empty below the copy.
    val frame = remember { StoryCardFrame(top = ShareStorySafeTop, bottom = 56.dp) }
    CompositionLocalProvider(LocalStoryCardFrame provides frame) {
        if (page.choice is StoryShareChoice) {
            // The slide as it plays: same background, same ink. The watermark
            // stays — it is the story's own credit, and it was in the shared
            // image before this sheet existed.
            SpotlightStoryBackground(
                primaryColor = colors.primary,
                secondaryColor = colors.secondary,
                tertiaryColor = colors.tertiary,
            ) {
                SpotlightStoryPageContent(
                    page = storyPage,
                    showWatermark = true,
                    conclusionItemCount = conclusionItemCount,
                )
            }
        } else {
            val palettes = rememberShareCardPalettes(page.card)
            val ink = palettes.ink
            val cardInk =
                if (ink.isDark) {
                    StoryInk.Dark
                } else {
                    // A light card needs dark type. The theme already knows which
                    // colour that is and how strong each surface should be, so the
                    // story borrows its answer instead of guessing a grey.
                    StoryInk(
                        ink = ink.textStrong,
                        surface = ink.surface,
                        border = ink.divider,
                    )
                }
            ShareCardSurface(
                card = page.card,
                modifier = Modifier.fillMaxSize(),
                art = storyShareArtwork(storyPage).firstOrNull().asShareArt(),
                brandingBottomPadding = ShareCardBrandingInset,
            ) {
                CompositionLocalProvider(LocalStoryInk provides cardInk) {
                    SpotlightStoryPageContent(
                        page = storyPage,
                        showWatermark = false,
                        conclusionItemCount = conclusionItemCount,
                    )
                }
            }
        }
    }
}

/**
 * Story progress segments.
 *
 * [progress] is invoked from the draw phase on purpose: the caller animates it with an
 * [Animatable] that ticks every frame, and reading its value in composition re-composed this
 * entire screen ~900 times per 15 s story. A draw-phase read invalidates only the fill rect.
 */
@Composable
private fun StoryProgressRow(
    pageCount: Int,
    currentPage: Int,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(pageCount) { index ->
            // Dim upcoming segments and brighten watched ones
            val trackAlpha =
                when {
                    index < currentPage -> 0.5f
                    index == currentPage -> 0.3f
                    else -> 0.16f
                }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = trackAlpha))
                        .drawBehind {
                            val fill =
                                when {
                                    index < currentPage -> 1f
                                    index == currentPage -> progress()
                                    else -> 0f
                                }
                            if (fill > 0f) {
                                drawRect(
                                    color = Color.White,
                                    size = Size(size.width * fill.coerceIn(0f, 1f), size.height),
                                )
                            }
                        },
            )
        }
    }
}
