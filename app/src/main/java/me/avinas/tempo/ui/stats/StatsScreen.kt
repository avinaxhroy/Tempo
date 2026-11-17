package me.avinas.tempo.ui.stats

import me.avinas.tempo.ui.details.formatListeningTime
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.GoldDark
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.innerShadow
import me.avinas.tempo.ui.theme.premiumClickable
import me.avinas.tempo.ui.components.TempoDropdownMenu
import me.avinas.tempo.ui.components.TempoDropdownMenuItem
import me.avinas.tempo.ui.components.TempoIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.MusicNotePlaceholder
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.data.stats.StatItem
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.ui.theme.TempoSecondary
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToSupportedApps: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // New tab/range/sort/search = new list: recreate state so scroll resets to
    // 0 synchronously before the next measure. The old manual scroll-to-item
    // clamp fought flings mid-measure (subcompose crash trigger) and is gone.
    val listState = remember(uiState.selectedTab, uiState.selectedTimeRange, uiState.selectedSortBy, uiState.searchQuery) { LazyListState() }
    val scope = rememberCoroutineScope()
    var showShareDialog by remember { mutableStateOf(false) }

    // Search drawer state: closed = search icon only; open = field slides over the top.
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            // Reveal first, type later: focusing immediately makes the keyboard animation
            // fight the drawer motion and reads as a jolt. Stagger until the reveal settles.
            delay(280)
            searchFocusRequester.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(enabled = searchOpen) {
        keyboard?.hide()
        searchOpen = false
    }

    // NOTE: the old totalItemCount + scrollToItem workaround was removed: it
    // issued a scroll during flings, fighting remeasure and triggering the
    // subcompose IllegalArgumentException. Stable keys + state recreation
    // above make it unnecessary (Lazy clamps internally).
    val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current

    // Pagination Logic - simplified for better scroll performance
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                false
            } else {
                // Trigger pagination 3 items before reaching end for smoother loading
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= layoutInfo.totalItemsCount - 3
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            viewModel.loadMore()
        }
    }
    val onStatItemClick = remember(onNavigateToTrack, onNavigateToArtist, onNavigateToAlbum) {
        { clickedItem: StatItem ->
            walkthroughController.dismiss()
            resolveNavigation(clickedItem, onNavigateToTrack, onNavigateToArtist, onNavigateToAlbum)
        }
    }


    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    scope.launch {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 100.dp, bottom = 200.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp) // Increased spacing for vertical rhythm
            ) {
                // 1. Sticky Tab Selector + Search
                stickyHeader(key = "sticky_tab_selector") {
                     Column(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(bottom = 12.dp)
                     ) {
                         StatsTabSelector(
                             selectedTab = uiState.selectedTab,
                             onTabSelected = viewModel::onTabSelected
                         )
                     }
                }

                item(key = "sort_by_selector") {
                    // Sort By Selector
                    LaunchedEffect(uiState.selectedTab) {
                        walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.STATS_SORT)
                    }
                    
                    SortBySelector(
                        selectedSortBy = uiState.selectedSortBy,
                        onSortBySelected = { 
                            walkthroughController.dismiss()
                            viewModel.onSortBySelected(it) 
                        },
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            walkthroughController.registerTarget(
                                me.avinas.tempo.ui.components.WalkthroughStep.STATS_SORT,
                                coordinates
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. Stats Items
                if (!uiState.isLoading && uiState.items.isEmpty()) {
                    item(key = "empty_state") {
                        if (uiState.error != null) {
                            // Display error state if load failed
                            StatsErrorState(
                                message = uiState.error.orEmpty(),
                                onRetry = { viewModel.retry() },
                                modifier = Modifier.fillParentMaxHeight(0.7f)
                            )
                        } else if (uiState.searchQuery.isNotBlank()) {
                            SearchEmptyState(query = uiState.searchQuery)
                        } else {
                            me.avinas.tempo.ui.components.EmptyState(
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .fillParentMaxHeight(0.7f),
                                 timeRange = uiState.selectedTimeRange,
                                 onCheckSupportedApps = onNavigateToSupportedApps
                            )
                        }
                    }
                } else if (!uiState.isLoading && uiState.items.isNotEmpty()) {
                    // In search mode there is no hero: every match is shown with its
                    // global ranking position, which is the point of the search.
                    val isSearching = uiState.searchQuery.isNotBlank()
                    val firstItem = if (isSearching) null else uiState.items.firstOrNull()
                    val remainingItems = if (isSearching) uiState.items else uiState.items.drop(1)


                    // Rank 1 Hero
                    // Rank 1 Hero
                    if (firstItem != null) {
                        item(key = "hero_item") {
                            LaunchedEffect(firstItem) {
                                // Try to trigger, controller handles priority/dismissal state
                                walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.STATS_ITEM_CLICK)
                            }
                            
                            Box(
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    walkthroughController.registerTarget(
                                        me.avinas.tempo.ui.components.WalkthroughStep.STATS_ITEM_CLICK,
                                        coordinates
                                    )
                                }
                            ) {

                                HeroStatItem(
                                    item = firstItem,
                                    onNavigate = { 
                                        walkthroughController.dismiss()
                                        resolveNavigation(firstItem, onNavigateToTrack, onNavigateToArtist, onNavigateToAlbum) 
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // Remaining Items
                    itemsIndexed(
                        items = remainingItems,
                        key = { _, item -> itemKey(item) },
                        contentType = { _, item ->
                            when (item) {
                                is TopTrack -> "track"
                                is TopArtist -> "artist"
                                is TopAlbum -> "album"
                            }
                        }
                    ) { index, item ->
                        val rank = if (isSearching) (itemRank(item) ?: (index + 1)) else (index + 2) // Search shows global rank
                        GlassStatItem(
                            rank = rank,
                            item = item,
                            onClick = onStatItemClick
                        )
                    }
                }

                // Loading More Indicator
                if (uiState.isLoadingMore) {
                    item(key = "loading_more") {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TempoPrimary)
                        }
                    }
                }
                }
            } // end PullToRefreshBox

            // Top Bar
            val isScrolled by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
            }
            if (!searchOpen) {
                StatsTopBar(
                    isScrolled = isScrolled,
                    hasItems = uiState.items.isNotEmpty(),
                    isSearchActive = uiState.searchQuery.isNotBlank(),
                    onOpenSearch = { searchOpen = true },
                    onOpenShare = { showShareDialog = true },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                )
            }

            // Time Period Filter
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 100.dp) // Reduced static padding, relying on insets + 100dp for lift
                    .padding(horizontal = 32.dp)
            ) {
                TimePeriodSelector(
                    selectedRange = uiState.selectedTimeRange,
                    onRangeSelected = viewModel::onTimeRangeSelected,
                    availableRanges = StatsAvailableRanges
                )
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = TempoPrimary)
            }

            // Search drawer: reveals horizontally from the search-icon side (End)
            // instead of dropping from the top, and stays minimal — just the
            // field itself, no full-width background block covering content.
            // Declared last so it draws above the top bar and list content.
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandHorizontally(
                    expandFrom = Alignment.End,
                    animationSpec = tween(280, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220)),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.End,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(160)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    StatsSearchField(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChanged,
                        placeholder = stringResource(
                            when (uiState.selectedTab) {
                                StatsTab.TOP_SONGS -> R.string.stats_search_hint_songs
                                StatsTab.TOP_ARTISTS -> R.string.stats_search_hint_artists
                                StatsTab.TOP_ALBUMS -> R.string.stats_search_hint_albums
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = searchFocusRequester,
                        onClose = {
                            keyboard?.hide()
                            searchOpen = false
                        }
                    )
                }
            }
        }

        if (showShareDialog) {
            StatsShareDialog(
                tab = uiState.selectedTab,
                timeRange = uiState.selectedTimeRange,
                items = uiState.items,
                overview = uiState.analyticsData?.overview,
                onDismiss = { showShareDialog = false }
            )
        }
    }
}

// Helper for Navigation
private fun resolveNavigation(
    item: StatItem,
    onTrack: (Long) -> Unit,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit
) {
    when (item) {
        is TopTrack -> onTrack(item.trackId)
        is TopArtist -> if (item.artistId != null && item.artistId > 0) onArtist("id:${item.artistId}") else onArtist(item.artist)
        is TopAlbum -> onAlbum("${item.album}|${item.artist}")
    }
}

// Components



@Composable
fun HeroStatItem(item: StatItem, onNavigate: () -> Unit) {
    val title = when (item) {
        is TopTrack -> item.title
        is TopArtist -> item.artist
        is TopAlbum -> item.album
    }
    val subtitle = when (item) {
        is TopTrack -> item.artist
        is TopArtist -> "${item.playCount} plays"
        is TopAlbum -> item.artist
    }
    val imageUrl = when (item) {
        is TopTrack -> item.albumArtUrl
        is TopArtist -> item.imageUrl
        is TopAlbum -> item.albumArtUrl
    }
    val label = when (item) {
        is TopTrack -> stringResource(R.string.stats_rank_1_track)
        is TopArtist -> stringResource(R.string.stats_rank_1_artist)
        is TopAlbum -> stringResource(R.string.stats_rank_1_album)
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .premiumClickable(onClick = onNavigate),
        backgroundColor = GoldDark.copy(alpha = 0.15f), // Reduced from 0.25f for better blend
        contentPadding = PaddingValues(16.dp) // Reduced from 24.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Big Image
            Box(contentAlignment = Alignment.Center) {
                // Glow Layer
                Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(GoldDark.copy(alpha = 0.25f))) // Reduced glow size
                
                // Image Layer
                if (imageUrl != null) {
                    CachedAsyncImage(
                        imageUrl = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        targetSizeDp = 80 // Downsample for faster decode & less memory
                    )
                } else {
                     Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                         Text(title.firstOrNull()?.toString() ?: "?", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                     }
                }
            }
            
            Spacer(modifier = Modifier.width(20.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = GoldDark, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    // Shrink to fit a long #1 title instead of clipping it; 14sp is the
                    // floor, past that it elides like the rows below.
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp),
                    overflow = TextOverflow.Ellipsis
                )
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            val timeMs = when (item) {
                is TopTrack -> item.totalTimeMs
                is TopArtist -> item.totalTimeMs
                is TopAlbum -> item.totalTimeMs
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = formatListeningTime(timeMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun GlassStatItem(rank: Int, item: StatItem, onClick: (StatItem) -> Unit) {
    val title = when (item) {
        is TopTrack -> item.title
        is TopArtist -> item.artist
        is TopAlbum -> item.album
    }
    val subtitle = when (item) {
        is TopTrack -> item.artist
        is TopArtist -> "${item.playCount} plays"
        is TopAlbum -> item.artist
    }
    val imageUrl = when (item) {
        is TopTrack -> item.albumArtUrl
        is TopArtist -> item.imageUrl
        is TopAlbum -> item.albumArtUrl
    }
    val timeMs = when (item) {
        is TopTrack -> item.totalTimeMs
        is TopArtist -> item.totalTimeMs
        is TopAlbum -> item.totalTimeMs
    }

    val tintColor: Color
    val bgAlpha: Float
    when (rank) {
        1 -> {
            tintColor = GoldDark
            bgAlpha = 0.15f
        }
        2 -> {
            tintColor = Color(0xFFE879F9)
            bgAlpha = 0.12f
        }
        3 -> {
            tintColor = Color(0xFFB45309)
            bgAlpha = 0.12f
        }
        else -> {
            tintColor = GlassStatItemPalette[(rank - 4) % GlassStatItemPalette.size]
            bgAlpha = 0.15f
        }
    }
    
    // Smart Composition: Rank 1-3 get 3D/HighProminence, Rest get 2D/LowProminence
    // Since this composable handles rank 2+, we check against 3.
    val variant = if (rank <= 3) me.avinas.tempo.ui.components.GlassCardVariant.HighProminence else me.avinas.tempo.ui.components.GlassCardVariant.LowProminence

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .premiumClickable(onClick = { onClick(item) })
            .innerShadow(
                color = if (rank <= 3) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.2f),
                cornersRadius = 24.dp,
                spread = 1.dp,
                blur = 2.dp
            ),
        backgroundColor = tintColor.copy(alpha = bgAlpha), // Increased alpha
        contentPadding = PaddingValues(12.dp), // Slightly tighter padding
        variant = variant
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.titleMedium, // Reduced from Large
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) tintColor else TextSecondary,
                modifier = Modifier.width(36.dp)
            )
            
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl != null) {
                    CachedAsyncImage(
                        imageUrl = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetSizeDp = 48 // Downsample for faster decode & less memory
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            
            Text(formatListeningTime(timeMs), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

// Top-level palette to avoid allocation on every recomposition
private val GlassStatItemPalette = listOf(
    Color(0xFFC026D3), // Fuchsia 600 (Orchid)
    Color(0xFFDB2777), // Pink 600 (Rose)
    Color(0xFFF59E0B), // Gold
    Color(0xFF9333EA), // Purple 600
    Color(0xFFBE185D), // Pink 700 (Raspberry)
    Color(0xFFE879F9)  // Orchid
)

@Composable
fun EmptyStatsState() {
     Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.stats_no_stats_yet), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.stats_start_listening), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatsTabSelector(selectedTab: StatsTab, onTabSelected: (StatsTab) -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(TempoDarkSurfaceSunken)
            .innerShadow(
                color = Color.Black.copy(alpha = 0.55f),
                cornersRadius = 25.dp,
                blur = 4.dp,
                offsetY = 2.dp
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.03f)
                    )
                ),
                RoundedCornerShape(25.dp)
            )
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StatsTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) TextOnAccent else TextSecondary,
                    label = "tabContentColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (isSelected) {
                                Modifier
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = RoundedCornerShape(22.dp),
                                        spotColor = TempoPrimary.copy(alpha = 0.4f),
                                        ambientColor = Color.Black.copy(alpha = 0.7f)
                                    )
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(TabSelectedBgBrush)
                                    .border(
                                        1.dp,
                                        TabSelectedBorderBrush,
                                        RoundedCornerShape(22.dp)
                                    )
                            } else {
                                Modifier.clip(RoundedCornerShape(22.dp))
                            }
                        )
                        .premiumClickable(
                            onClick = { onTabSelected(tab) },
                            pressedScale = 0.96f
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (tab) {
                            StatsTab.TOP_SONGS -> stringResource(R.string.stats_tab_songs)
                            StatsTab.TOP_ARTISTS -> stringResource(R.string.stats_tab_artists)
                            StatsTab.TOP_ALBUMS -> stringResource(R.string.stats_tab_albums)
                        },
                        color = contentColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClose: (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(23.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.Obsidian,
        shadowElevation = 8.dp
    ) {
        val focusManager = LocalFocusManager.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            if (onClose != null) {
                // Drawer mode: leading close affordance.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.stats_search_close),
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextSecondary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    cursorBrush = SolidColor(TempoPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                )
            }
            if (query.isNotEmpty()) {
                // 48dp touch target for clear button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.stats_search_clear),
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = TextTertiary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.stats_search_no_results, query),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Error state view with retry action. */
@Composable
private fun StatsErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = TextTertiary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.stats_error_title),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = TextOnAccent
                )
            ) {
                Text(stringResource(R.string.stats_error_retry))
            }
        }
    }
}

/** Global ranking position carried by search results (null outside search mode). */
private fun itemRank(item: StatItem): Int? = when (item) {
    is TopTrack -> item.rank
    is TopArtist -> item.rank
    is TopAlbum -> item.rank
}

private fun itemKey(item: StatItem): String = when (item) {
    is TopTrack -> "track_${item.trackId}"
    is TopArtist -> "artist_${item.artistId ?: item.artist}"
    is TopAlbum -> "album_${item.album}_${item.artist}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsTopBar(
    isScrolled: Boolean,
    hasItems: Boolean,
    isSearchActive: Boolean,
    onOpenSearch: () -> Unit,
    onOpenShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headerAlpha by animateFloatAsState(targetValue = if (isScrolled) 1f else 0f, label = "headerAlpha")

    Surface(
        color = TempoDarkBackground.copy(alpha = headerAlpha),
        shadowElevation = if (isScrolled) 4.dp else 0.dp,
        modifier = modifier
    ) {
        TopAppBar(
            navigationIcon = {
                // Balances the action row (Search + Share) so the centered title is optically centered.
                Spacer(
                    modifier = Modifier.width(
                        if (hasItems) 96.dp else 48.dp
                    )
                )
            },
            title = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.stats_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.stats_search_open),
                        // Tinted while a search filter is active so search mode is never invisible.
                        tint = if (isSearchActive) TempoPrimary else TextPrimary
                    )
                }
                if (hasItems) {
                    IconButton(onClick = onOpenShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.stats_share),
                            tint = Color.White
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

private val StatsAvailableRanges = listOf(TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME)

private val TabSelectedBgBrush = Brush.verticalGradient(
    listOf(
        TempoAccentBright,
        TempoPrimary,
        TempoPrimaryDeep
    )
)

private val TabSelectedBorderBrush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.45f),
        Color.Transparent
    )
)

@Composable
fun SortBySelector(
    selectedSortBy: SortBy, 
    onSortBySelected: (SortBy) -> Unit,
    modifier: Modifier = Modifier
) {
     var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = stringResource(R.string.stats_sort_by), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Box {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = when (selectedSortBy) {
                        SortBy.COMBINED_SCORE -> stringResource(R.string.stats_sort_combined)
                        SortBy.PLAY_COUNT -> stringResource(R.string.stats_sort_play_count)
                        SortBy.TOTAL_TIME -> stringResource(R.string.stats_sort_total_time)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = TempoPrimary
                )
            }
            TempoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SortBy.entries.forEach { sortBy ->
                    val title = when (sortBy) {
                        SortBy.COMBINED_SCORE -> stringResource(R.string.stats_sort_combined)
                        SortBy.PLAY_COUNT -> stringResource(R.string.stats_sort_play_count)
                        SortBy.TOTAL_TIME -> stringResource(R.string.stats_sort_total_time)
                    }
                    val icon = when (sortBy) {
                        SortBy.COMBINED_SCORE -> Icons.Rounded.AutoAwesome
                        SortBy.PLAY_COUNT -> Icons.Rounded.PlayArrow
                        SortBy.TOTAL_TIME -> Icons.Rounded.Schedule
                    }
                    TempoDropdownMenuItem(
                        title = title,
                        onClick = {
                            expanded = false
                            onSortBySelected(sortBy)
                        },
                        leadingIcon = icon,
                        isSelected = sortBy == selectedSortBy
                    )
                }
            }
        }
    }
}
