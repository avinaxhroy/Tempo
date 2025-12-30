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

@Composable
fun SpotlightScreen(
    navController: NavController,
    viewModel: SpotlightViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var showStory by remember { mutableStateOf(false) }

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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
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
                            SpotlightStoryButton(onClick = { showStory = true })
                        }
                    }

                    // Render specific cards in order if they exist
                    val timeDevotion = uiState.cards.find { it is SpotlightCardData.TimeDevotion } as? SpotlightCardData.TimeDevotion
                    if (timeDevotion != null) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(500, delayMillis = 100)) + 
                                       slideInVertically(animationSpec = tween(500, delayMillis = 100), initialOffsetY = { 50 })
                            ) {
                                DashboardTimeDevotionCard(timeDevotion)
                            }
                        }
                    }

                    val earlyAdopter = uiState.cards.find { it is SpotlightCardData.EarlyAdopter } as? SpotlightCardData.EarlyAdopter
                    if (earlyAdopter != null) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + 
                                       slideInVertically(animationSpec = tween(500, delayMillis = 200), initialOffsetY = { 50 })
                            ) {
                                DashboardEarlyAdopterCard(earlyAdopter)
                            }
                        }
                    }

                    val seasonalAnthem = uiState.cards.find { it is SpotlightCardData.SeasonalAnthem } as? SpotlightCardData.SeasonalAnthem
                    if (seasonalAnthem != null) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + 
                                       slideInVertically(animationSpec = tween(500, delayMillis = 300), initialOffsetY = { 50 })
                            ) {
                                DashboardSeasonalAnthemCard(seasonalAnthem)
                            }
                        }
                    }

                    val listeningPeak = uiState.cards.find { it is SpotlightCardData.ListeningPeak } as? SpotlightCardData.ListeningPeak
                    if (listeningPeak != null) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + 
                                       slideInVertically(animationSpec = tween(500, delayMillis = 400), initialOffsetY = { 50 })
                            ) {
                                DashboardListeningPeakCard(listeningPeak)
                            }
                        }
                    }
                    
                    // Render other cards that are not the main 4
                    val otherCards = uiState.cards.filter { 
                        it !is SpotlightCardData.TimeDevotion &&
                        it !is SpotlightCardData.EarlyAdopter &&
                        it !is SpotlightCardData.SeasonalAnthem &&
                        it !is SpotlightCardData.ListeningPeak
                    }
                    
                    items(otherCards) { card ->
                        // Fallback renderer for other cards
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

        // Story Overlay
        AnimatedVisibility(
            visible = showStory,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SpotlightStoryScreen(
                storyPages = uiState.storyPages,
                onClose = { showStory = false }
            )
        }
    }
}

@Composable
fun SpotlightStoryButton(onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = Color(0xFFA855F7).copy(alpha = 0.15f), // Purple Tint
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
                        brush = Brush.linearGradient(
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
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = "Your Wrapped",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap to view your story",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}



