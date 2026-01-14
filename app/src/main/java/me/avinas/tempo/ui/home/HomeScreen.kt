package me.avinas.tempo.ui.home

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.ui.home.components.*
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.TimeRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToStats: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToSpotlight: (TimeRange?) -> Unit,
    onNavigateToSupportedApps: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        viewModel.refresh()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(bottom = 200.dp), // Space for Bottom Nav and Floating Filter
                    verticalArrangement = Arrangement.spacedBy(0.dp) // Reset spacing to handle it manually
                ) {
                    // NEW: Vibe Header
                    VibeHeader(
                        energy = uiState.audioFeatures?.averageEnergy ?: 0.5f,
                        valence = uiState.audioFeatures?.averageValence ?: 0.5f,
                        userName = uiState.userName ?: "User",
                        isNewUser = uiState.isNewUser
                    )
                    
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp), // Add padding after header
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        if (uiState.hasData) {
                            // Hero Card
                            val overview = uiState.listeningOverview
                            val comparison = uiState.periodComparison
                            
                            // Format time (e.g., "42h 15m" or "0.5h" or "30m")
                            val totalMinutes = (overview?.totalListeningTimeMs ?: 0) / 1000 / 60
                            val hours = totalMinutes / 60
                            val minutes = totalMinutes % 60
                            
                            val decimalTime = String.format("%.1f", totalMinutes / 60.0)
                            val timeString = if (hours == 0L) {
                                 if (minutes > 0) "${minutes}m" else "0m"
                            } else {
                                 if (hours < 10) {
                                     "${decimalTime}h"
                                 } else {
                                     "${hours}h ${minutes}m"
                                 }
                            }
                            
                            // Trend data
                            val trendData = uiState.dailyListening.map { it.totalTimeMs.toFloat() }

                            HeroCard(
                                userName = uiState.userName ?: "User", // Kept for Hero internal logic, visually hidden title
                                listeningTime = timeString,
                                periodLabel = uiState.selectedTimeRange.name.replace("_", " ").lowercase(),
                                timeChangePercent = uiState.periodComparison?.timeChangePercent ?: 0.0,
                                trendData = trendData,
                                selectedRange = uiState.selectedTimeRange
                            )


                            // Trigger Walkthrough
                            val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current
                            LaunchedEffect(Unit) {
                                walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT)
                            }

                            SpotlightStoryCard(
                                onClick = {
                                    walkthroughController.dismiss()
                                    onNavigateToSpotlight(null)
                                },
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    walkthroughController.registerTarget(
                                        me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT,
                                        coordinates
                                    )
                                }
                            )

                            // NEW: Quick Stats (Side-by-side Top Artist & Track)
                            QuickStatsRow(
                                topArtistName = uiState.topArtist?.artist,
                                topArtistImage = uiState.topArtist?.imageUrl,
                                topTrackName = uiState.topTrack?.title,
                                topTrackImage = uiState.topTrack?.albumArtUrl
                            )
                            
                            // NEW: Dynamic Insight Feed
                            // Replaces WeekInReview, DiscoverySection, and HabitInsights
                            if (uiState.insights.isNotEmpty()) {
                                Text(
                                    text = "Your Signal",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                                )
                                
                                InsightFeed(
                                    insights = uiState.insights,
                                    onNavigateToTrack = onNavigateToTrack,
                                    onNavigateToArtist = { /* Handle Artist Nav */ }
                                )
                            }
                        } else if (!uiState.isLoading) {
                            // Empty State
                            // Use dynamic height to ensure centering and prevent truncation on varying screen sizes
                            me.avinas.tempo.ui.components.EmptyState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(me.avinas.tempo.ui.utils.rememberScreenHeightPercentage(0.7f)),
                                timeRange = if (uiState.isNewUser) null else uiState.selectedTimeRange,
                                type = me.avinas.tempo.ui.components.GhostScreenType.HOME,
                                onCheckSupportedApps = onNavigateToSupportedApps
                            )
                        }
                    }
                }
            }

            // Top Bar
            val scrollOffset = scrollState.value.toFloat()
            val maxScroll = 400f // px to fully transition
            val headerAlpha by animateFloatAsState(
                targetValue = (scrollOffset / maxScroll).coerceIn(0f, 1f),
                label = "headerAlpha"
            )
            
            val backgroundColor = TempoDarkBackground.copy(alpha = headerAlpha)
            val elevation = if (scrollOffset > 0) 4.dp * headerAlpha else 0.dp

            Surface(
                color = Color.Transparent,
                shadowElevation = elevation,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            ) {
                TopAppBar(
                    title = { 
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Home",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = headerAlpha) // Fade in title only when scrolled
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onNavigateToSettings,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = backgroundColor,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
            
            // Time Period Filter (Floating)
            TimePeriodSelector(
                selectedRange = uiState.selectedTimeRange,
                onRangeSelected = viewModel::onTimeRangeSelected,
                availableRanges = listOf(TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 100.dp)
                    .padding(horizontal = 32.dp)
            )
        }
        
        // Rate App Bottom Sheet
        if (uiState.showRateAppPopup) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RateAppBottomSheet(
                onDismiss = viewModel::onRateAppDismissed,
                onRate = {
                    viewModel.onRateAppClicked()
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${context.packageName}"))
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
                        context.startActivity(intent)
                    }
                }
            )
        }
        
        // Spotlight Story Reminder Popup
        val reminderType = uiState.reminderType
        if (uiState.showSpotlightReminder && reminderType != null) {
            me.avinas.tempo.ui.components.SpotlightReminderPopup(
                type = reminderType,
                onDismiss = viewModel::dismissSpotlightReminder,
                onViewStory = {
                    // Navigate to Spotlight with the specified time range
                    onNavigateToSpotlight(uiState.reminderTimeRange)
                    // Dismiss the reminder
                    viewModel.dismissSpotlightReminder()
                }
            )
        }
    }
}

@Composable
private fun mapInsightToHabit(insight: InsightCardData): HabitInsightData {
    val (icon, color, gradient) = when(insight.type) {
        InsightType.MOOD -> Triple(Icons.Default.Face, Color(0xFF8B5CF6), listOf(Color(0xFF8B5CF6).copy(alpha=0.4f), Color(0xFF6D28D9).copy(alpha=0.1f)))
        InsightType.PEAK_TIME -> Triple(Icons.Default.DateRange, Color(0xFFF59E0B), listOf(Color(0xFFF59E0B).copy(alpha=0.4f), Color(0xFFD97706).copy(alpha=0.1f)))
        InsightType.BINGE -> Triple(Icons.Filled.Bolt, Color(0xFFEC4899), listOf(Color(0xFFEC4899).copy(alpha=0.4f), Color(0xFFBE185D).copy(alpha=0.1f)))
        InsightType.DISCOVERY -> Triple(Icons.Default.Celebration, Color(0xFF10B981), listOf(Color(0xFF10B981).copy(alpha=0.4f), Color(0xFF059669).copy(alpha=0.1f)))
        InsightType.ENERGY -> Triple(Icons.Default.Bolt, Color(0xFFEF4444), listOf(Color(0xFFEF4444).copy(alpha=0.4f), Color(0xFF991B1B).copy(alpha=0.1f)))
        InsightType.DANCEABILITY -> Triple(Icons.Default.Celebration, Color(0xFFA855F7), listOf(Color(0xFFA855F7).copy(alpha=0.4f), Color(0xFF6B21A8).copy(alpha=0.1f)))
        InsightType.TEMPO -> Triple(Icons.Default.Speed, Color(0xFF06B6D4), listOf(Color(0xFF06B6D4).copy(alpha=0.4f), Color(0xFF155E75).copy(alpha=0.1f)))
        InsightType.ACOUSTICNESS -> Triple(Icons.Default.Piano, Color(0xFF22C55E), listOf(Color(0xFF22C55E).copy(alpha=0.4f), Color(0xFF166534).copy(alpha=0.1f)))
        else -> Triple(Icons.Default.DateRange, Color.Gray, listOf(Color.Gray.copy(alpha=0.2f), Color.DarkGray.copy(alpha=0.1f)))
    }
    
    return HabitInsightData(
        title = insight.title,
        subtitle = insight.description,
        icon = icon,
        iconColor = color,
        iconBgColor = color.copy(alpha = 0.2f),
        gradient = gradient
    )
}
