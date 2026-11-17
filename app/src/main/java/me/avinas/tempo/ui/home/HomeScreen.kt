package me.avinas.tempo.ui.home

import me.avinas.tempo.ui.theme.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.ui.stats.StatsShareDialog
import me.avinas.tempo.ui.stats.StatsTab
import me.avinas.tempo.ui.home.components.*
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.TimeRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.ui.profile.CompactLevelRing
import me.avinas.tempo.ui.components.LevelUpOverlay
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.utils.ReviewUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    analyticsDisclosureViewModel: AnalyticsDisclosureViewModel = hiltViewModel(),
    onNavigateToStats: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToSpotlight: (TimeRange?, Boolean) -> Unit,
    onNavigateToSupportedApps: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val flags by viewModel.flagsState.collectAsState()
    val analyticsDisclosure by analyticsDisclosureViewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var isLaunchingReview by remember { mutableStateOf(false) }
    var showTodaysOverview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    // Streak insight cards are dropped for someone who asked not to see streaks, and the
    // section header goes with them when they were the only thing left in it.
    val hideStreaks = me.avinas.tempo.ui.components.LocalHideStreaks.current
    val insights = remember(uiState.insights, hideStreaks) {
        if (hideStreaks) uiState.insights.filterNot { it.type == InsightType.STREAK } else uiState.insights
    }
    
    // Gamification state
    val gamificationRepo = viewModel.gamificationRepository
    val userLevel by gamificationRepo.observeUserLevel().collectAsState(initial = null)
    
    // Level-up detection
    var lastKnownLevel by remember { mutableStateOf(-1) }
    var showLevelUp by remember { mutableStateOf(false) }
    var levelUpLevel by remember { mutableStateOf(0) }
    var levelUpTitle by remember { mutableStateOf("") }
    
    LaunchedEffect(userLevel?.currentLevel) {
        val currentLevel = userLevel?.currentLevel ?: 0
        if (lastKnownLevel >= 0 && currentLevel > lastKnownLevel) {
            levelUpLevel = currentLevel
            levelUpTitle = userLevel?.title ?: ""
            showLevelUp = true
        }
        if (currentLevel > 0) lastKnownLevel = currentLevel
    }
    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = flags.isRefreshing,
                onRefresh = {
                    scope.launch {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 200.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "header") {
                        VibeHeader(
                            energy = uiState.audioFeatures?.averageEnergy ?: 0.5f,
                            valence = uiState.audioFeatures?.averageValence ?: 0.5f,
                            userName = uiState.userName ?: stringResource(R.string.home_user_default),
                            profileImagePath = uiState.profileImagePath,
                            isNewUser = uiState.isNewUser,
                            userLevel = userLevel?.currentLevel,
                            levelProgress = userLevel?.levelProgress ?: 0f,
                            levelTitle = userLevel?.title,
                            isGamificationEnabled = uiState.isGamificationEnabled,
                            onLevelClick = onNavigateToProfile,
                            onSettingsClick = onNavigateToSettings
                        )
                    }
                    
                    if (uiState.hasData) {
                        item(key = "hero") {
                            
                            val timeString = remember(uiState.listeningOverview?.totalListeningTimeMs) {
                                val totalMs = uiState.listeningOverview?.totalListeningTimeMs ?: 0
                                val totalMinutes = totalMs / 1000 / 60
                                val hours = totalMinutes / 60
                                val minutes = totalMinutes % 60
                                val decimalTime = String.format(java.util.Locale.US, "%.1f", totalMinutes / 60.0)
                                if (hours == 0L) {
                                    if (minutes > 0) "${minutes}m" else "0m"
                                } else if (hours < 10) {
                                    "${decimalTime}h"
                                } else {
                                    "${hours}h ${minutes}m"
                                }
                            }

                            val trendData = remember(uiState.dailyListening) {
                                uiState.dailyListening.map { it.totalTimeMs.toFloat() / 1000 / 60 }
                            }
                            
                            val dailyLabels = uiState.chartLabels

                            val periodLabel = when(uiState.selectedTimeRange) {
                                TimeRange.TODAY -> stringResource(R.string.period_today)
                                TimeRange.THIS_WEEK -> stringResource(R.string.period_this_week)
                                TimeRange.THIS_MONTH -> stringResource(R.string.period_this_month)
                                TimeRange.THIS_YEAR -> stringResource(R.string.period_this_year)
                                TimeRange.ALL_TIME -> stringResource(R.string.period_all_time)
                            }
                            
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                            ) {
                                HeroCard(
                                    userName = uiState.userName ?: stringResource(R.string.home_user_default),
                                    listeningTime = timeString,
                                    periodLabel = periodLabel,
                                    timeChangePercent = uiState.periodComparison?.timeChangePercent ?: 0.0,
                                    trendData = trendData,
                                    selectedRange = uiState.selectedTimeRange,
                                    dailyLabels = dailyLabels,
                                    dailyAvgMinutes = uiState.dailyAvgMinutes,
                                    activeDays = uiState.activeDaysCount,
                                    totalDays = uiState.totalDaysCount,
                                    peakDayMinutes = uiState.peakDayMinutes
                                )
                            }
                        }

                        item(key = "spotlight") {
                            val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current
                            LaunchedEffect(uiState.hasData) {
                                if (uiState.hasData) {
                                    walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT)
                                }
                            }

                            val directStoryTimeRange = remember {
                                me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                            }

                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                SpotlightStoryCard(
                                    onClick = {
                                        walkthroughController.dismiss()
                                        viewModel.onSpotlightViewed()
                                        if (directStoryTimeRange != null) {
                                            onNavigateToSpotlight(directStoryTimeRange, false)
                                        } else {
                                            onNavigateToSpotlight(null, false)
                                        }
                                    },
                                    onRingClick = {
                                        walkthroughController.dismiss()
                                        viewModel.onSpotlightViewed()
                                        if (directStoryTimeRange != null) {
                                            onNavigateToSpotlight(directStoryTimeRange, true)
                                        } else {
                                            onNavigateToSpotlight(null, true)
                                        }
                                    },
                                    albumArtUrl = uiState.spotlightTopTrack?.albumArtUrl,
                                    storyAvailable = directStoryTimeRange != null,
                                    storyViewed = flags.spotlightStoryViewed,
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        walkthroughController.registerTarget(
                                            me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT,
                                            coordinates
                                        )
                                    }
                                )
                            }
                        }

                        item(key = "quickstats") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                QuickStatsRow(
                                    topArtistName = uiState.topArtist?.artist,
                                    topArtistImage = uiState.topArtist?.imageUrl,
                                    topTrackName = uiState.topTrack?.title,
                                    topTrackImage = uiState.topTrack?.albumArtUrl,
                                    topArtistPlayCount = uiState.topArtist?.playCount,
                                    topTrackArtist = uiState.topTrack?.artist,
                                    topTrackPlayCount = uiState.topTrack?.playCount,
                                    onArtistClick = {
                                        val artist = uiState.topArtist
                                        if (artist != null) {
                                            val id = artist.artistId
                                            if (id != null && id > 0) {
                                                onNavigateToArtist("id:$id")
                                            } else {
                                                onNavigateToArtist(artist.artist)
                                            }
                                        }
                                    },
                                    onTrackClick = {
                                        uiState.topTrack?.trackId?.let { trackId ->
                                            onNavigateToTrack(trackId)
                                        }
                                    }
                                )
                            }
                        }
                    } else if (!flags.isLoading) {
                        item(key = "empty") {
                            me.avinas.tempo.ui.components.EmptyState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(me.avinas.tempo.ui.utils.rememberScreenHeightPercentage(0.7f)),
                                timeRange = if (uiState.isNewUser) null else uiState.selectedTimeRange,
                                onCheckSupportedApps = onNavigateToSupportedApps
                            )
                        }
                    }
                    
                    if (uiState.todayOverview?.totalPlayCount?.let { it > 0 } == true) {
                        item(key = "todays_listen") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                TodaysListenWidget(
                                    todayOverview = uiState.todayOverview,
                                    topTrack = uiState.todayTopTrack,
                                    topArtist = uiState.todayTopArtist,
                                    hourlyDistribution = uiState.todayHourlyDistribution,
                                    periodComparison = uiState.todayPeriodComparison,
                                    onTrackClick = {
                                        uiState.todayTopTrack?.trackId?.let { trackId ->
                                            onNavigateToTrack(trackId)
                                        }
                                    },
                                    onArtistClick = {
                                        val artist = uiState.todayTopArtist
                                        if (artist != null) {
                                            val id = artist.artistId
                                            if (id != null && id > 0) {
                                                onNavigateToArtist("id:$id")
                                            } else {
                                                onNavigateToArtist(artist.artist)
                                            }
                                        }
                                    },
                                    onMoreInsightsClick = { showTodaysOverview = true },
                                    onOpenOverview = { showTodaysOverview = true }
                                )
                            }
                        }
                    }

                    if (insights.isNotEmpty()) {
                        item(key = "insights_header") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    text = stringResource(R.string.home_your_signal),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = DisplayFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.2).sp
                                    ),
                                    color = TextPrimary
                                )
                            }
                        }

                        item(key = "insights_feed") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                InsightFeed(
                                    insights = insights,
                                    onNavigateToArtist = onNavigateToArtist
                                )
                            }
                        }
                    }
                }
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
        
        // Rate App prompt
        if (flags.showRateAppPopup) {
            RateAppPopup(
                onDismiss = viewModel::onRateAppDismissed,
                onRate = {
                    if (isLaunchingReview) return@RateAppPopup
                    val activity = ReviewUtils.run { context.findActivity() }
                    if (activity == null) {
                        ReviewUtils.openPlayStoreListing(context)
                        viewModel.onRateAppFlowHandled()
                        return@RateAppPopup
                    }
                    // Set the guard BEFORE launching the coroutine so a rapid second tap
                    // is blocked even if the coroutine hasn't begun executing yet.
                    isLaunchingReview = true
                    scope.launch {
                        val launchedInAppReview = ReviewUtils.launchInAppReview(activity)
                        if (!launchedInAppReview) {
                            ReviewUtils.openPlayStoreListing(context)
                        }
                        viewModel.onRateAppFlowHandled()
                        isLaunchingReview = false
                    }
                },
                isSubmitting = isLaunchingReview
            )
        }
        
        // Share Nudge — gated in the ViewModel; opens the artist share preview
        // (music share card) directly instead of showing a message popup. Never
        // stacks with the rate popup or spotlight reminder.
        if (flags.showShareNudge && flags.shareNudgeArtists.isNotEmpty() &&
            !flags.showRateAppPopup && !flags.showSpotlightReminder
        ) {
            val nudgeRange = flags.shareNudgeTimeRange ?: TimeRange.THIS_WEEK
            StatsShareDialog(
                tab = StatsTab.TOP_ARTISTS,
                timeRange = nudgeRange,
                items = flags.shareNudgeArtists,
                overview = flags.shareNudgeOverview,
                nudgeCaption = stringResource(
                    if (nudgeRange == TimeRange.THIS_MONTH) R.string.share_nudge_caption_monthly
                    else R.string.share_nudge_caption_weekly
                ),
                onDismiss = viewModel::onShareNudgeDismissed,
                onShared = viewModel::onShareNudgeShared
            )
        }

        // Spotlight Story Reminder Popup
        val reminderType = flags.reminderType
        if (flags.showSpotlightReminder && reminderType != null) {
            me.avinas.tempo.ui.components.SpotlightReminderPopup(
                type = reminderType,
                timeRange = flags.reminderTimeRange,
                onDismiss = viewModel::dismissSpotlightReminder,
                onViewStory = {
                    // Navigate to Spotlight with the specified time range
                    onNavigateToSpotlight(flags.reminderTimeRange, false)
                    // Dismiss the reminder
                    viewModel.dismissSpotlightReminder()
                },
                preview = flags.reminderPreview
            )
        }

                // Today Overview Overlay — opened by tapping the Today's Listening widget
        // or its "More insights" link; slides over the home feed until dismissed.
        if (showTodaysOverview && uiState.todayOverview != null) {
            TodaysOverviewOverlay(
                overview = uiState.todayOverview,
                topTracks = uiState.todayTopTracks,
                topArtists = uiState.todayTopArtists,
                hourlyDistribution = uiState.todayHourlyDistribution,
                periodComparison = uiState.todayPeriodComparison,
                onDismiss = { showTodaysOverview = false },
                onViewAllStats = {
                    showTodaysOverview = false
                    onNavigateToStats()
                },
                onNavigateToTrack = onNavigateToTrack,
                onNavigateToArtist = onNavigateToArtist
            )
        }

        // Level Up Celebration Overlay
        if (uiState.isGamificationEnabled && showLevelUp) {
            LevelUpOverlay(
                newLevel = levelUpLevel,
                title = levelUpTitle,
                onDismiss = { showLevelUp = false }
            )
        }

        // One-time analytics disclosure. Overlapping dialog so it can't be
        // scrolled past or mistaken for feed content.
        if (analyticsDisclosure.shouldShow) {
            AnalyticsDisclosureDialog(
                viewModel = analyticsDisclosureViewModel,
            )
        }
    }
}

