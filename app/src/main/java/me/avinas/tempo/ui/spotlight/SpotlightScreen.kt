package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.*
import me.avinas.tempo.ui.navigation.Screen
import me.avinas.tempo.ui.theme.*
import java.util.Locale

@Composable
fun SpotlightScreen(
    navController: NavController,
    viewModel: SpotlightViewModel = hiltViewModel(),
    initialTimeRange: TimeRange? = null,
    directLaunch: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // For direct launches, show the story overlay immediately so the
    // dashboard never flashes underneath before the story is ready to play.
    var showStory by remember { mutableStateOf(directLaunch) }

    // Apply initial time range if provided (e.g., from reminder)
    LaunchedEffect(initialTimeRange) {
        if (initialTimeRange != null && initialTimeRange != uiState.selectedTimeRange) {
            viewModel.onTimeRangeSelected(initialTimeRange)
        }
    }

    // Direct launch fallback: if the story can't be played (locked or no data),
    // dismiss the overlay to reveal the dashboard instead of being stuck on a spinner.
    LaunchedEffect(directLaunch, uiState.storyLoading, uiState.isStoryLocked, uiState.storyPages.isEmpty()) {
        if (!directLaunch) return@LaunchedEffect
        if (uiState.storyLoading) return@LaunchedEffect
        if (uiState.isStoryLocked || uiState.storyPages.isEmpty()) {
            showStory = false
        }
    }

    val onShareCard: (SpotlightCardData) -> Unit = { card ->
        navController.navigate(Screen.ShareCanvas.createRoute(card.id))
    }

    val periodLabel = remember(uiState.selectedTimeRange) {
        SpotlightPeriodFormatter.periodLabel(uiState.selectedTimeRange)
    }

    val storyButtonText = remember(uiState.selectedTimeRange) {
        SpotlightPeriodFormatter.viewStoryText(context, uiState.selectedTimeRange)
    }

    // Lead artwork thumbnail URL for the story ring preview
    val leadArtUrl: String? = remember(uiState.cards, uiState.storyPages) {
        val storyArt: String? = uiState.storyPages.firstNotNullOfOrNull { page ->
            when (page) {
                is SpotlightStoryPage.TopSongs -> page.topSongImageUrl?.takeIf { it.isNotBlank() }
                is SpotlightStoryPage.TopTrackSetup -> page.topSongImageUrl?.takeIf { it.isNotBlank() }
                is SpotlightStoryPage.TopAlbum -> page.albumArtUrl?.takeIf { it.isNotBlank() }
                is SpotlightStoryPage.TopArtist -> page.topArtistImageUrl?.takeIf { it.isNotBlank() }
                is SpotlightStoryPage.Conclusion -> page.topSongs.firstOrNull()?.imageUrl?.takeIf { it.isNotBlank() }
                    ?: page.topArtists.firstOrNull()?.imageUrl?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        storyArt ?: uiState.cards.firstNotNullOfOrNull { card ->
            when (card) {
                is SpotlightCardData.ForgottenFavorite -> card.albumArtUrl?.takeIf { it.isNotBlank() }
                is SpotlightCardData.NewObsession -> card.artistImageUrl?.takeIf { it.isNotBlank() }
                is SpotlightCardData.EarlyAdopter -> card.artistImageUrl?.takeIf { it.isNotBlank() }
                is SpotlightCardData.ArtistLoyalty -> card.artistImageUrl?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    SpotlightTopBar(
                        periodLabel = periodLabel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            ) { paddingValues ->
                when {
                    uiState.isLoading -> {
                        SpotlightLoadingView(
                            periodLabel = periodLabel,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        )
                    }

                    uiState.cards.isEmpty() -> {
                        SpotlightEmptyView(
                            periodLabel = periodLabel,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)
                        ) {
                            // Spotlight Story Hero Card
                            item(key = "story_hero") {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(animationSpec = tween(400)) +
                                            slideInVertically(animationSpec = tween(400), initialOffsetY = { 20 })
                                ) {
                                    SpotlightStoryHeroCard(
                                        onClick = {
                                            if (!uiState.isStoryLocked) {
                                                showStory = true
                                            }
                                        },
                                        buttonText = storyButtonText,
                                        isLocked = uiState.isStoryLocked,
                                        lockMessage = uiState.storyLockMessage,
                                        leadArtUrl = leadArtUrl,
                                        storyLoading = uiState.storyLoading
                                    )
                                }
                            }

                            // Generative Visual Cards Feed
                            items(
                                items = uiState.cards,
                                key = { card -> card.id }
                            ) { card ->
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(animationSpec = tween(400)) +
                                            slideInVertically(animationSpec = tween(400), initialOffsetY = { 40 })
                                ) {
                                    when (card) {
                                        is SpotlightCardData.CosmicClock -> DashboardCosmicClockCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.WeekendWarrior -> DashboardWeekendWarriorCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.ForgottenFavorite -> DashboardForgottenFavoriteCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.DeepDive -> DashboardDeepDiveCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.NewObsession -> DashboardNewObsessionCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.EarlyAdopter -> DashboardEarlyAdopterCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.ListeningPeak -> DashboardListeningPeakCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.ArtistLoyalty -> DashboardArtistLoyaltyCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                        is SpotlightCardData.Discovery -> DashboardDiscoveryCard(
                                            data = card,
                                            onShareClick = { onShareCard(card) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Time Period Selector
            TimePeriodSelector(
                selectedRange = uiState.selectedTimeRange,
                onRangeSelected = viewModel::onTimeRangeSelected,
                availableRanges = listOf(TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 20.dp)
                    .padding(horizontal = 32.dp)
            )

            // Fullscreen Story Overlay
            // Trigger analytics once when story playback starts.
            val storyPlaying = showStory && !uiState.storyLoading && uiState.storyPages.isNotEmpty()
            LaunchedEffect(storyPlaying) {
                if (storyPlaying) viewModel.trackStoryOpened()
            }
            AnimatedVisibility(
                visible = showStory,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(250))
            ) {
                if (uiState.storyLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.94f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(16.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            FrostedIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Close",
                                onClick = { showStory = false }
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            CircularProgressIndicator(
                                color = TempoPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = stringResource(R.string.spotlight_preparing),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = TextPrimary
                            )
                        }
                    }
                } else {
                    // The streak page is generated with the rest of the story; it is dropped here
                    // rather than at generation so turning the setting off needs no regeneration.
                    val storyPages =
                        if (me.avinas.tempo.ui.components.LocalHideStreaks.current) {
                            uiState.storyPages.filterNot { it is SpotlightStoryPage.ListeningStreak }
                        } else {
                            uiState.storyPages
                        }
                    SpotlightStoryScreen(
                        storyPages = storyPages,
                        onClose = { showStory = false }
                    )
                }
            }
        }
    }
}

/**
 * Top bar with back button, screen title, and the selected period pill.
 */
@Composable
private fun SpotlightTopBar(
    periodLabel: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            FrostedIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.details_action_back),
                onClick = onNavigateBack
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = stringResource(R.string.spotlight_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    letterSpacing = (-0.5).sp
                ),
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Active Period Pill Chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(GlassFrostMedium)
                .border(0.8.dp, GlassBorderSoft, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = periodLabel.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                ),
                color = TextSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * Header card linking to the fullscreen Spotlight story.
 */
@Composable
fun SpotlightStoryHeroCard(
    onClick: () -> Unit,
    buttonText: String,
    isLocked: Boolean,
    lockMessage: String,
    leadArtUrl: String?,
    storyLoading: Boolean,
    modifier: Modifier = Modifier
) {
    NeoSpotlightSurface(
        modifier = modifier.fillMaxWidth(),
        enabled = !isLocked,
        onClick = if (isLocked) null else onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Visual Story Avatar (Ring, Cover Preview, or Lock)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (isLocked) TempoDarkSurfaceSunken.copy(alpha = 0f)
                        else Color(0xFFA855F7).copy(alpha = 0.2f)
                    )
                    .border(
                        1.dp,
                        if (isLocked) SolidColor(Color.White.copy(alpha = 0.12f))
                        else Brush.linearGradient(
                            listOf(TempoPrimary, Color(0xFFA855F7), Color(0xFFEC4899))
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                } else if (!leadArtUrl.isNullOrBlank()) {
                    CachedAsyncImage(
                        imageUrl = leadArtUrl,
                        contentDescription = "Story preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Headline & Description. The headline names the period, which is the reason
            // to tap, so it must never be cut off: it shrinks to fit the space the card
            // actually has instead of clipping a contract that grows per language.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLocked) "Story Locked" else buttonText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        letterSpacing = (-0.2).sp
                    ),
                    color = if (isLocked) TextSecondary else TextPrimary,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 13.sp,
                        maxFontSize = 17.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = when {
                        isLocked -> lockMessage.ifBlank { "Unlocks at the end of the period" }
                        storyLoading -> "Composing your story..."
                        else -> "Tap to play your interactive story"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Trailing action cue
            if (!isLocked) {
                NeoSpotlightCta(size = 38.dp, iconSize = 12.dp)
            }
        }
    }
}

/**
 * Loading indicator shown while analytical cards are generated.
 */
@Composable
private fun SpotlightLoadingView(
    periodLabel: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = TempoPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Analyzing $periodLabel…",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Building your personalized music cards and story",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Empty state shown when no listening data exists for the selected period.
 */
@Composable
private fun SpotlightEmptyView(
    periodLabel: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            variant = GlassCardVariant.Obsidian,
            shape = RoundedCornerShape(22.dp),
            borderColor = GlassBorderSoft,
            borderWidth = 0.8.dp,
            contentPadding = PaddingValues(28.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TempoDarkSurfaceSunken)
                        .border(0.8.dp, GlassBorderSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "No Insights for $periodLabel",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Keep listening to your favorite tracks or switch time periods below to see your music visualized.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * Backward compatibility wrapper for [SpotlightStoryHeroCard].
 */
@Composable
fun SpotlightStoryButton(
    onClick: () -> Unit,
    buttonText: String,
    isLocked: Boolean = false,
    lockMessage: String = ""
) {
    SpotlightStoryHeroCard(
        onClick = onClick,
        buttonText = buttonText,
        isLocked = isLocked,
        lockMessage = lockMessage,
        leadArtUrl = null,
        storyLoading = false
    )
}
