package me.avinas.tempo.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.local.dao.HistoryItem
import me.avinas.tempo.ui.components.*
import me.avinas.tempo.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

private val RelativeDateFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onNavigateToTrack: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = remember(uiState.viewMode, uiState.startDate, uiState.endDate, uiState.showSkips) { LazyListState() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    
    // Show feedback snackbar when message is set
    LaunchedEffect(uiState.feedbackMessage) {
        uiState.feedbackMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearFeedbackMessage()
        }
    }
    
    // Pagination — prefetch 4 items before end to prevent scrolling stalls and avoid per-pixel offset recalculation
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                false
            } else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= layoutInfo.totalItemsCount - 4
            }
        }
    }

    val sheetState = rememberModalBottomSheetState()
    var showFilterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            viewModel.loadMore()
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = TempoDarkSurfaceElevated,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassBorderMedium)
                )
            }
        ) {
            HistoryFilterSheetContent(
                currentShowSkips = uiState.showSkips,
                currentStartDate = uiState.startDate,
                currentEndDate = uiState.endDate,
                onApply = { start, end, skips ->
                    viewModel.onFilterChanged(start, end, skips)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.onFilterChanged(null, null, true)
                    showFilterSheet = false
                }
            )
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
                // List Content
                HistoryListContent(
                    // Recent Activity section
                    groupedItems = uiState.groupedItems,
                    isLoading = uiState.isLoading,
                    isLoadingMore = uiState.isLoadingMore,
                    showCoachMark = uiState.showCoachMark,
                    listState = listState,
                    onLoadMore = viewModel::loadMore,
                    viewModel = viewModel,
                    onNavigateToTrack = onNavigateToTrack,
                    onDismissCoachMark = viewModel::dismissCoachMark,
                    // Last.fm History section
                    viewMode = uiState.viewMode,
                    hasArchiveData = uiState.hasArchiveData,
                    lastFmGroupedItems = uiState.lastFmGroupedItems,
                    archiveItems = uiState.archiveItems,
                    archiveTrackCount = uiState.archiveTrackCount,
                    archiveTotalPlays = uiState.archiveTotalPlays,
                    isLoadingMoreLastFm = uiState.isLoadingMoreLastFm,
                    hasMoreLastFm = uiState.hasMoreLastFm,
                    onLoadMoreLastFm = viewModel::loadMoreLastFmHistory,
                    onViewModeChange = viewModel::setViewMode,
                    searchQuery = uiState.searchQuery,
                    onClearSearch = { viewModel.onSearchQueryChanged("") }
                )

                // Snackbar for feedback messages
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp), // Above nav bar
                    snackbar = { snackbarData ->
                        TempoSnackbar(snackbarData)
                    }
                )
                
                // Loading overlay when marking content
                if (uiState.isMarking) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(enabled = false) { }, // Block clicks
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = TempoPrimary,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            } // end PullToRefreshBox

            // Top Bar with scroll-aware elevation and studio glass styling
            val isScrolled by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
            }
            val headerAlpha by animateFloatAsState(
                targetValue = if (isScrolled) 1f else 0f,
                label = "historyHeaderAlpha"
            )

            Surface(
                color = TempoDarkBackground.copy(alpha = 0.95f * headerAlpha),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(10f)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Search Bar with obsidian glass styling
                            GlassCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                backgroundColor = TempoDarkSurfaceSunken.copy(alpha = 0.85f),
                                variant = GlassCardVariant.LowProminence,
                                borderColor = GlassBorderSoft,
                                borderWidth = 1.dp
                            ) {
                                val focusManager = LocalFocusManager.current
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = stringResource(R.string.history_search),
                                        tint = if (uiState.searchQuery.isNotBlank()) TempoPrimary else TextTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (uiState.searchQuery.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.history_search_hint),
                                                color = TextTertiary,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        BasicTextField(
                                            value = uiState.searchQuery,
                                            onValueChange = viewModel::onSearchQueryChanged,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                            cursorBrush = SolidColor(TempoPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .premiumClickable(onClick = { viewModel.onSearchQueryChanged("") }),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.stats_search_clear),
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Filter Button
                            val isFilterActive = uiState.startDate != null || !uiState.showSkips

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (isFilterActive) {
                                            Modifier
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(
                                                            TempoPrimary.copy(alpha = 0.25f),
                                                            TempoPrimaryDeep.copy(alpha = 0.35f)
                                                        )
                                                    )
                                                )
                                                .border(1.dp, TempoPrimary.copy(alpha = 0.6f), CircleShape)
                                        } else {
                                            Modifier
                                                .background(TempoDarkSurfaceElevated.copy(alpha = 0.85f))
                                                .border(0.8.dp, GlassBorderSoft, CircleShape)
                                        }
                                    )
                                    .premiumClickable(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showFilterSheet = true
                                        },
                                        pressedScale = 0.94f
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(R.string.history_title),
                                    tint = if (isFilterActive) TempoPrimary else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )

                                // Active indicator dot
                                if (isFilterActive) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 4.dp, end = 4.dp)
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(TempoAccentBright)
                                            .border(1.dp, TempoDarkBackground, CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    if (isScrolled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.8.dp)
                                .background(GlassBorderSoft.copy(alpha = 0.6f * headerAlpha))
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryFilterSheetContent(
    currentShowSkips: Boolean,
    currentStartDate: Long?,
    currentEndDate: Long?,
    onApply: (Long?, Long?, Boolean) -> Unit,
    onReset: () -> Unit
) {
    var showSkips by remember { mutableStateOf(currentShowSkips) }
    var selectedRange by remember { mutableStateOf(getRangeLabel(currentStartDate)) }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp)
            .navigationBarsPadding()
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.history_filter_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.history_reset),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selectedRange != "All Time" || !showSkips) TempoPrimary else TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedRange = "All Time"
                        showSkips = true
                        onReset()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Time Range Kicker
        Text(
            text = stringResource(R.string.history_time_range).uppercase(Locale.getDefault()),
            style = KickerSmall,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Segmented Pill Bar for Time Range
        val ranges = listOf(
            "All Time" to stringResource(R.string.history_all_time),
            "Last 7 Days" to stringResource(R.string.history_7_days_abbr),
            "Last 30 Days" to stringResource(R.string.history_30_days_abbr)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TempoDarkSurfaceSunken)
                .innerShadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    cornersRadius = 24.dp,
                    blur = 4.dp,
                    offsetY = 1.dp
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.03f)
                        )
                    ),
                    RoundedCornerShape(24.dp)
                )
                .padding(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ranges.forEach { (key, label) ->
                    val isSelected = selectedRange == key
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) TextOnAccent else TextSecondary,
                        label = "filterRangeContentColor"
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
                                            shape = RoundedCornerShape(21.dp),
                                            spotColor = TempoPrimary.copy(alpha = 0.4f),
                                            ambientColor = Color.Black.copy(alpha = 0.7f)
                                        )
                                        .clip(RoundedCornerShape(21.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    TempoAccentBright,
                                                    TempoPrimary,
                                                    TempoPrimaryDeep
                                                )
                                            )
                                        )
                                        .border(
                                            1.dp,
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.45f),
                                                    Color.Transparent
                                                )
                                            ),
                                            RoundedCornerShape(21.dp)
                                        )
                                } else {
                                    Modifier.clip(RoundedCornerShape(21.dp))
                                }
                            )
                            .premiumClickable(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedRange = key
                                },
                                pressedScale = 0.96f
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = contentColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Playback Kicker
        Text(
            text = stringResource(R.string.history_playback).uppercase(Locale.getDefault()),
            style = KickerSmall,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showSkips = !showSkips
                },
            shape = RoundedCornerShape(16.dp),
            backgroundColor = TempoDarkSurfaceSunken.copy(alpha = 0.7f),
            variant = GlassCardVariant.LowProminence,
            borderColor = GlassBorderSoft,
            borderWidth = 0.8.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.history_show_skipped),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Include songs skipped during playback",
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = showSkips,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showSkips = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextOnAccent,
                        checkedTrackColor = TempoPrimary,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = TempoDarkSurfaceElevated
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Apply Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = TempoPrimary.copy(alpha = 0.4f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            TempoAccentBright,
                            TempoPrimary,
                            TempoPrimaryDeep
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    ),
                    RoundedCornerShape(24.dp)
                )
                .premiumClickable(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val (start, end) = getRangeBounds(selectedRange)
                        onApply(start, end, showSkips)
                    },
                    pressedScale = 0.98f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.history_apply_filters),
                color = TextOnAccent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun getRangeLabel(start: Long?): String {
    if (start == null) return "All Time"
    val now = System.currentTimeMillis()
    val diff = now - start
    return when {
        diff <= 8 * 24 * 60 * 60 * 1000L -> "Last 7 Days"
        diff <= 31 * 24 * 60 * 60 * 1000L -> "Last 30 Days"
        else -> "All Time"
    }
}

fun getRangeBounds(label: String): Pair<Long?, Long?> {
    val now = System.currentTimeMillis()
    return when (label) {
        "Last 7 Days" -> Pair(now - 7 * 24 * 60 * 60 * 1000L, now)
        "Last 30 Days" -> Pair(now - 30 * 24 * 60 * 60 * 1000L, now)
        else -> Pair(null, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteHistoryItem(
    item: HistoryItem,
    index: Int,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onMarkContent: ((Long, String, Boolean) -> Unit)? = null,
    onMarkArtist: ((Long, String, Boolean) -> Unit)? = null
) {
    // Use item.id as key for all state to prevent state reuse when items shift
    var showDeleteDialog by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    
    // Reset dismiss state when item.id changes (item was deleted and a new one appeared)
    val dismissState = key(item.id) {
        rememberSwipeToDismissBoxState()
    }
    
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showDeleteDialog = true
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        Dialog(onDismissRequest = { 
            showDeleteDialog = false 
            scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
        }) {
            TempoDialogSurface {
                TempoDialogIcon(
                    icon = TempoIcons.Trash,
                    tint = TempoError,
                    size = 48
                )
                Spacer(modifier = Modifier.height(16.dp))
                TempoDialogTitle(text = stringResource(R.string.history_delete_dialog_title))
                Spacer(modifier = Modifier.height(8.dp))
                TempoDialogBody(text = stringResource(R.string.history_delete_dialog_message, item.title))
                Spacer(modifier = Modifier.height(24.dp))
                TempoDialogDangerButton(
                    text = stringResource(R.string.history_delete_button),
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    icon = TempoIcons.Trash
                )
                Spacer(modifier = Modifier.height(8.dp))
                TempoDialogSecondaryButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = {
                        showDeleteDialog = false
                        scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                    }
                )
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isDismissing = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart ||
                    dismissState.currentValue == SwipeToDismissBoxValue.EndToStart

            if (isDismissing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    TempoErrorDeep.copy(alpha = 0.6f),
                                    TempoError.copy(alpha = 0.95f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        imageVector = TempoIcons.Trash,
                        contentDescription = stringResource(R.string.history_delete_button),
                        tint = Color.White,
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .size(22.dp)
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        HistoryListItem(
            item = item, 
            index = index, 
            onClick = onClick,
            onDeleteClick = { showDeleteDialog = true },
            onMarkContent = { type, delete -> onMarkContent?.invoke(item.track_id, type, delete) },
            onMarkArtist = { type, delete -> onMarkArtist?.invoke(item.track_id, type, delete) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryListContent(
    // Recent Activity section (live tracking, source != fm.last.import)
    groupedItems: Map<String, List<HistoryItem>>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    showCoachMark: Boolean,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    viewModel: HistoryViewModel,
    onNavigateToTrack: (Long) -> Unit,
    onDismissCoachMark: () -> Unit,
    // View mode and archive data
    viewMode: HistoryViewMode = HistoryViewMode.UNIFIED,
    hasArchiveData: Boolean = false,
    archiveItems: List<ArchiveHistoryItem> = emptyList(),
    archiveTrackCount: Int = 0,
    archiveTotalPlays: Long = 0,
    // Last.fm History section (source == fm.last.import)
    lastFmGroupedItems: Map<String, List<HistoryItem>> = emptyMap(),
    isLoadingMoreLastFm: Boolean = false,
    hasMoreLastFm: Boolean = false,
    onLoadMoreLastFm: () -> Unit = {},
    onViewModeChange: (HistoryViewMode) -> Unit = {},
    searchQuery: String = "",
    onClearSearch: () -> Unit = {}
) {
    // Workaround for LazyColumn crash when item count drops below current scroll index
    val totalItemCount = remember(
        groupedItems, lastFmGroupedItems, archiveItems,
        viewMode, hasArchiveData, isLoading,
        isLoadingMore, isLoadingMoreLastFm
    ) {
        var count = 0
        if (hasArchiveData && viewMode == HistoryViewMode.SEPARATED) count += 1
        val allEmpty = groupedItems.isEmpty() && lastFmGroupedItems.isEmpty() && archiveItems.isEmpty()
        if (!isLoading && allEmpty) count += 1
        if (viewMode == HistoryViewMode.SEPARATED && groupedItems.isNotEmpty()) count += 1
        groupedItems.values.forEach { count += it.size + 1 }
        if (isLoadingMore) count += 1
        if (viewMode == HistoryViewMode.SEPARATED && (lastFmGroupedItems.isNotEmpty() || archiveItems.isNotEmpty())) {
            count += 1
            lastFmGroupedItems.values.forEach { count += it.size + 1 }
            if (isLoadingMoreLastFm) count += 1
            if (archiveItems.isNotEmpty()) {
                count += 1 + archiveItems.size
            }
        }
        if (viewMode == HistoryViewMode.UNIFIED && archiveItems.isNotEmpty()) {
            count += 1 + archiveItems.size
        }
        count
    }
    LaunchedEffect(totalItemCount) {
        if (totalItemCount > 0 && listState.firstVisibleItemIndex >= totalItemCount) {
            listState.requestScrollToItem(totalItemCount - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Top padding for persistent search bar (status bar + 48dp bar + paddings), bottom for bottom nav
            contentPadding = PaddingValues(top = 96.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // View Mode Toggle Banner (only show if user has archive data in SEPARATED mode)
            if (hasArchiveData && viewMode == HistoryViewMode.SEPARATED) {
                item(key = "view_mode_banner") {
                    SeparatedModeInfoBanner(
                        archiveTrackCount = archiveTrackCount,
                        archiveTotalPlays = archiveTotalPlays
                    )
                }
            }
            
            // Empty State
            val allEmpty = groupedItems.isEmpty() && lastFmGroupedItems.isEmpty() && archiveItems.isEmpty()
            if (!isLoading && allEmpty) {
                item(key = "empty_state") {
                    if (searchQuery.isNotBlank()) {
                        SearchEmptyState(
                            query = searchQuery,
                            onClear = onClearSearch
                        )
                    } else {
                        HistoryEmptyState()
                    }
                }
            }

            // SECTION 1: Recent Activity (Live Tracking)
            // In SEPARATED mode, show section header
            if (viewMode == HistoryViewMode.SEPARATED && groupedItems.isNotEmpty()) {
                item(key = "recent_activity_header") {
                    SectionDividerHeader(
                        title = stringResource(R.string.history_recent_activity),
                        subtitle = stringResource(R.string.history_recent_activity_subtitle),
                        icon = Icons.Default.PlayCircle,
                        iconTint = TempoPrimary
                    )
                }
            }
            
            groupedItems.entries.forEachIndexed { groupIndex, (header, itemsList) ->
                stickyHeader(key = "header_$header") {
                    HistorySectionHeader(header)
                }

                items(
                    count = itemsList.size,
                    key = { index -> itemsList[index].id },
                    contentType = { "history_item" }
                ) { index ->
                    val item = itemsList[index]
                    val isFirstItem = groupIndex == 0 && index == 0
                    
                    // Walkthrough Integration
                    val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current
                    
                    if (isFirstItem) {
                        LaunchedEffect(Unit) {
                            walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.HISTORY_FILTER)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isFirstItem) {
                                    Modifier.onGloballyPositioned { coordinates ->
                                        walkthroughController.registerTarget(
                                            me.avinas.tempo.ui.components.WalkthroughStep.HISTORY_FILTER, 
                                            coordinates
                                        )
                                    }
                                } else Modifier
                            )
                    ) {
                        SwipeToDeleteHistoryItem(
                            item = item,
                            index = index,
                            onDelete = { viewModel.deleteListeningEvent(item.id) },
                            onClick = { onNavigateToTrack(item.track_id) },
                            onMarkContent = viewModel::markContent,
                            onMarkArtist = viewModel::markArtistContent
                        )
                    }
                }
            }

            // Loading more indicator for Recent Activity
            if (isLoadingMore) {
                item(key = "loading_more_recent") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = TempoPrimary,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            }
            
            // SECTION 2: Last.fm History (only in SEPARATED mode)
            if (viewMode == HistoryViewMode.SEPARATED && (lastFmGroupedItems.isNotEmpty() || archiveItems.isNotEmpty())) {
                // Section header for Last.fm History
                item(key = "lastfm_history_header") {
                    SectionDividerHeader(
                        title = stringResource(R.string.history_lastfm_history),
                        subtitle = stringResource(R.string.history_lastfm_subtitle),
                        icon = Icons.Default.History,
                        iconTint = GoldDark
                    )
                }
                
                // Last.fm imported events from listening_events table
                lastFmGroupedItems.entries.forEachIndexed { groupIndex, (header, itemsList) ->
                    stickyHeader(key = "lastfm_header_$header") {
                        HistorySectionHeader(header, isLastFmSection = true)
                    }

                    items(
                        count = itemsList.size,
                        key = { index -> "lastfm_${itemsList[index].id}" },
                        contentType = { "history_item" }
                    ) { index ->
                        val item = itemsList[index]
                        Box(modifier = Modifier.fillMaxWidth()) {
                            SwipeToDeleteHistoryItem(
                                item = item,
                                index = index,
                                onDelete = { viewModel.deleteListeningEvent(item.id) },
                                onClick = { onNavigateToTrack(item.track_id) },
                                onMarkContent = viewModel::markContent,
                                onMarkArtist = viewModel::markArtistContent
                            )
                        }
                    }
                }
                
                // Loading more indicator for Last.fm section
                if (isLoadingMoreLastFm) {
                    item(key = "loading_more_lastfm") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = GoldDark,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                }
                
                // Archive items (long-tail tracks)
                if (archiveItems.isNotEmpty()) {
                    item(key = "archive_sub_header") {
                        ArchiveSectionHeader(
                            itemCount = archiveItems.size,
                            totalTracks = archiveTrackCount
                        )
                    }
                    
                    items(
                        count = archiveItems.size,
                        key = { index -> "archive_sep_${archiveItems[index].archiveId}" },
                        contentType = { "archive_item" }
                    ) { index ->
                        val archiveItem = archiveItems[index]
                        ArchiveHistoryListItem(item = archiveItem)
                    }
                }
            }
            
            // UNIFIED MODE: Archive section at bottom
            if (viewMode == HistoryViewMode.UNIFIED && archiveItems.isNotEmpty()) {
                item(key = "archive_header") {
                    ArchiveSectionHeader(
                        itemCount = archiveItems.size,
                        totalTracks = archiveTrackCount
                    )
                }
                
                items(
                    count = archiveItems.size,
                    key = { index -> "archive_uni_${archiveItems[index].archiveId}" },
                    contentType = { "archive_item" }
                ) { index ->
                    val archiveItem = archiveItems[index]
                    ArchiveHistoryListItem(item = archiveItem)
                }
            }
        }
    }
}

/**
 * Info banner shown in SEPARATED mode to explain the two-section layout.
 */
@Composable
fun SeparatedModeInfoBanner(
    archiveTrackCount: Int,
    archiveTotalPlays: Long
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        backgroundColor = TempoDarkSurfaceElevated.copy(alpha = 0.75f),
        accentColor = GoldDark,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp),
        variant = GlassCardVariant.QuietGlass
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GoldDark.copy(alpha = 0.15f))
                    .border(0.8.dp, GoldDark.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = GoldLight,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.history_lastfm_imported),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "${archiveTotalPlays.formatNumber()} plays • $archiveTrackCount tracks archived",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

/**
 * Section divider header for Recent Activity / Last.fm History sections.
 */
@Composable
fun SectionDividerHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f))
                    .border(0.8.dp, iconTint.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.8.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            iconTint.copy(alpha = 0.4f),
                            iconTint.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * Header for the archive section in history.
 */
@Composable
fun ArchiveSectionHeader(
    itemCount: Int,
    totalTracks: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GoldDark.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = GoldLight,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = stringResource(R.string.history_from_archive).uppercase(Locale.getDefault()),
                style = KickerSmall,
                fontWeight = FontWeight.Bold,
                color = GoldLight,
                letterSpacing = 1.sp
            )
        }
        Text(
            text = "Showing $itemCount of $totalTracks archived tracks",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(start = 36.dp, top = 2.dp)
        )
    }
}

/**
 * Display item for archived scrobbles.
 * Visually distinct with gold telemetry accents.
 */
@Composable
fun ArchiveHistoryListItem(item: ArchiveHistoryItem) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        backgroundColor = TempoDarkSurfaceElevated.copy(alpha = 0.5f),
        accentColor = GoldDark,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp),
        variant = GlassCardVariant.LowProminence,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box {
                CachedAsyncImage(
                    imageUrl = item.albumArtUrl,
                    contentDescription = "Album art for ${item.trackTitle}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                    targetSizeDp = 52,
                    placeholder = {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TempoDarkSurfaceSunken),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Album,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )
                // Archive play count badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldDark)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${item.playCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextOnAccent
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Track Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.trackTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Play count pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GoldDark.copy(alpha = 0.12f))
                    .border(0.8.dp, GoldDark.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${item.playCount} plays",
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldLight,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Format large numbers with K/M suffixes.
 */
private fun Long.formatNumber(): String {
    return when {
        this >= 1_000_000 -> String.format(Locale.US, "%.1fM", this / 1_000_000.0)
        this >= 1_000 -> String.format(Locale.US, "%.1fK", this / 1_000.0)
        else -> this.toString()
    }
}

@Composable
fun HistorySectionHeader(title: String, isLastFmSection: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(TempoDarkBackground.copy(alpha = 0.95f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isLastFmSection) GoldDark.copy(alpha = 0.14f)
                        else TempoPrimary.copy(alpha = 0.12f)
                    )
                    .border(
                        0.8.dp,
                        if (isLastFmSection) GoldDark.copy(alpha = 0.35f)
                        else TempoPrimary.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = title.uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isLastFmSection) GoldLight else TempoAccentBright,
                    letterSpacing = 1.2.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(0.8.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                if (isLastFmSection) GoldDark.copy(alpha = 0.3f)
                                else GlassBorderMedium,
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryListItem(
    item: HistoryItem, 
    index: Int, 
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMarkContent: ((String, Boolean) -> Unit)? = null, // type, deleteFromHistory
    onMarkArtist: ((String, Boolean) -> Unit)? = null // type, deleteFromHistory - artist level
) {
    var showMenu by remember { mutableStateOf(false) }
    val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current
    val haptics = LocalHapticFeedback.current

    Box {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .combinedClickable(
                    onClick = { 
                        walkthroughController.dismiss()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick() 
                    },
                    onLongClick = { 
                        walkthroughController.dismiss()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true 
                    }
                ),
            shape = RoundedCornerShape(16.dp),
            backgroundColor = TempoDarkSurfaceElevated.copy(alpha = 0.6f),
            contentPadding = PaddingValues(12.dp),
            variant = GlassCardVariant.LowProminence,
            borderColor = GlassBorderSoft,
            borderWidth = 0.8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.album_art_url.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(TempoDarkSurfaceSunken),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        CachedAsyncImage(
                            imageUrl = item.album_art_url,
                            contentDescription = "Album art for ${item.title}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            targetSizeDp = 52
                        )
                    }
                    
                    // Visual Badge for Non-Music Content on Art
                    if (item.content_type != "MUSIC") {
                        val badgeColor = when(item.content_type) {
                            "PODCAST" -> TempoCyan
                            "AUDIOBOOK" -> GoldenAmber
                            else -> TextTertiary
                        }
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                                .size(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (item.content_type == "PODCAST") 
                                    TempoIcons.Podcast 
                                else 
                                    TempoIcons.Audiobook,
                                contentDescription = item.content_type,
                                tint = TextOnAccent,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(14.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        
                        // Inline Badge Text
                        if (item.content_type != "MUSIC") {
                            val badgeColor = when(item.content_type) {
                                "PODCAST" -> TempoCyan
                                "AUDIOBOOK" -> GoldenAmber
                                else -> TextTertiary
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(badgeColor.copy(alpha = 0.14f))
                                    .border(0.8.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = item.content_type.take(1),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        // Desktop source tag
                        if (item.source.startsWith("desktop:")) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.14f))
                                    .border(0.8.dp, Color(0xFF8B5CF6).copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "DESK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = Color(0xFFA78BFA),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))
    
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = formatRelativeTime(item.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .premiumClickable(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDeleteClick()
                                },
                                pressedScale = 0.88f
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TempoIcons.Trash,
                            contentDescription = stringResource(R.string.history_delete_button),
                            tint = TextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        
        // Context Menu for Blocking Content
        TempoDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            TempoMenuKicker(text = stringResource(R.string.tracking_always_music))
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_always_music_track),
                leadingIcon = Icons.Rounded.MusicNote,
                leadingIconTint = TempoPrimary,
                onClick = {
                    onMarkContent?.invoke("ALWAYS_MUSIC", false)
                    showMenu = false
                }
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_always_music_artist, item.artist),
                leadingIcon = Icons.Rounded.MusicNote,
                leadingIconTint = TempoPrimary,
                enabled = !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(item.artist),
                onClick = {
                    onMarkArtist?.invoke("ALWAYS_MUSIC", false)
                    showMenu = false
                }
            )

            TempoMenuDivider()

            TempoMenuKicker(text = stringResource(R.string.history_block_track))
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_its_a_podcast),
                subtitle = stringResource(R.string.history_remove_and_block),
                leadingIcon = TempoIcons.Podcast,
                leadingIconTint = TempoCyan,
                onClick = {
                    onMarkContent?.invoke("PODCAST", true)
                    showMenu = false
                }
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_its_an_audiobook),
                subtitle = stringResource(R.string.history_remove_and_block),
                leadingIcon = TempoIcons.Audiobook,
                leadingIconTint = GoldenAmber,
                onClick = {
                    onMarkContent?.invoke("AUDIOBOOK", true)
                    showMenu = false
                }
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_video_non_music_track),
                subtitle = stringResource(R.string.history_video_non_music_track_description),
                leadingIcon = Icons.Default.PlayCircle,
                leadingIconTint = TempoError,
                isDestructive = true,
                onClick = {
                    onMarkContent?.invoke("NON_MUSIC", true)
                    showMenu = false
                }
            )

            TempoMenuDivider()

            TempoMenuKicker(
                text = stringResource(R.string.history_block_entire_artist),
                color = TempoError.copy(alpha = 0.8f)
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_artist_is_podcast, item.artist),
                subtitle = stringResource(R.string.history_remove_all_from_source),
                leadingIcon = TempoIcons.Podcast,
                leadingIconTint = TempoError,
                isDestructive = true,
                onClick = {
                    onMarkArtist?.invoke("PODCAST", true)
                    showMenu = false
                }
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_artist_is_audiobook, item.artist),
                subtitle = stringResource(R.string.history_remove_all_from_source),
                leadingIcon = TempoIcons.Audiobook,
                leadingIconTint = TempoError,
                isDestructive = true,
                onClick = {
                    onMarkArtist?.invoke("AUDIOBOOK", true)
                    showMenu = false
                }
            )
            TempoDropdownMenuItem(
                title = stringResource(R.string.history_video_non_music_artist, item.artist),
                subtitle = stringResource(R.string.history_video_non_music_artist_description),
                leadingIcon = Icons.Default.Close,
                leadingIconTint = TempoError,
                isDestructive = true,
                onClick = {
                    onMarkArtist?.invoke("NON_MUSIC", true)
                    showMenu = false
                }
            )

        }
    }
}

fun formatRelativeTime(timestamp: Long): String {
    val now = Instant.now()
    val time = Instant.ofEpochMilli(timestamp)
    val diff = ChronoUnit.MINUTES.between(time, now)

    return when {
        diff < 1 -> "Just now"
        diff < 60 -> "${diff}m ago"
        diff < 24 * 60 -> "${diff / 60}h ago"
        else -> {
            val date = time.atZone(ZoneId.systemDefault()).toLocalDate()
            val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
            if (date.isEqual(today.minusDays(1))) "Yesterday"
            else date.format(RelativeDateFormatter)
        }
    }
}

@Composable
fun HistoryEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(TempoPrimary.copy(alpha = 0.08f))
                    .border(1.dp, TempoPrimary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = TempoPrimary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.history_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SearchEmptyState(query: String, onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(GlassFrostSoft)
                    .border(1.dp, GlassBorderSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = TextTertiary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No scrobbles found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "No matches for \"$query\"",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(TempoPrimary.copy(alpha = 0.12f))
                    .border(1.dp, TempoPrimary.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .premiumClickable(onClick = onClear)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.stats_search_clear),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TempoPrimary
                )
            }
        }
    }
}
