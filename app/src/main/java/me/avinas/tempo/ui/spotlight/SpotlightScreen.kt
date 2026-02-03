package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.navigation.Screen

@Composable
fun SpotlightScreen(
    navController: NavController,
    viewModel: SpotlightViewModel = hiltViewModel(),
    initialTimeRange: TimeRange? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var showStory by remember { mutableStateOf(false) }
    
    // Apply initial time range if provided (e.g., from reminder)
    LaunchedEffect(initialTimeRange) {
        if (initialTimeRange != null && initialTimeRange != uiState.selectedTimeRange) {
            viewModel.onTimeRangeSelected(initialTimeRange)
        }
    }
    
    // Navigate to canvas with card ID
    val onShareCard: (SpotlightCardData) -> Unit = { card ->
        navController.navigate(Screen.ShareCanvas.createRoute(card.id))
    }

    DeepOceanBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .background(color = Color.White.copy(alpha = 0.1f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Spotlight",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        ) { paddingValues ->
            if (uiState.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please wait while we cooking your data",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            } else if (uiState.cards.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Not enough data yet. Keep listening!",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 100.dp) // Space for bottom bar
                ) {
                    // Spotlight Story Button
                    item {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(500)) + 
                                   slideInVertically(animationSpec = tween(500), initialOffsetY = { 50 })
                        ) {
                            SpotlightStoryButton(
                                onClick = { 
                                    if (!uiState.isStoryLocked) {
                                        showStory = true 
                                    }
                                },
                                isLocked = uiState.isStoryLocked,
                                lockMessage = uiState.storyLockMessage
                            )
                        }
                    }

                    // Dynamic Feed of Insights
                    items(
                        items = uiState.cards,
                        key = { card -> card.id }
                    ) { card ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(500)) + 
                                   slideInVertically(animationSpec = tween(500), initialOffsetY = { 50 })
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
        
        // Floating Time Period Selector
        TimePeriodSelector(
            selectedRange = uiState.selectedTimeRange,
            onRangeSelected = viewModel::onTimeRangeSelected,
            availableRanges = listOf(TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .navigationBarsPadding()
        )

        // Story Overlay - only show if story pages are loaded
        AnimatedVisibility(
            visible = showStory,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (uiState.storyLoading) {
                // Story still loading - show loading indicator
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Preparing your story...",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                SpotlightStoryScreen(
                    storyPages = uiState.storyPages,
                    onClose = { showStory = false }
                )
            }
        }
    }
}

@Composable
fun SpotlightStoryButton(
    onClick: () -> Unit,
    isLocked: Boolean = false,
    lockMessage: String = ""
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLocked, onClick = onClick),
        backgroundColor = if (isLocked) 
            Color.Gray.copy(alpha = 0.1f) 
        else 
            Color(0xFFA855F7).copy(alpha = 0.15f), // Purple Tint
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = if (isLocked) 
                            Brush.linearGradient(colors = listOf(Color.Gray, Color.DarkGray))
                        else 
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFA855F7),
                                    Color(0xFFEC4899)
                                )
                            ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = if (isLocked) "Story Locked" else "Your Wrapped",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = if (isLocked) 0.5f else 1f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLocked) lockMessage else "Tap to view your story",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}



