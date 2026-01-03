package me.avinas.tempo.ui.history

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.local.dao.HistoryItem
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onNavigateToTrack: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    
    // Pagination
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0) {
                false
            } else {
                val lastVisibleItem = visibleItemsInfo.last()
                val viewportHeight = layoutInfo.viewportEndOffset + layoutInfo.viewportStartOffset
                (lastVisibleItem.index + 1 == layoutInfo.totalItemsCount) &&
                        (lastVisibleItem.offset + lastVisibleItem.size <= viewportHeight)
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
            containerColor = Color(0xFF1E1B24) // Charcoal Surface
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
            // List Content
            LazyColumn(
                state = listState,
                // Top padding for header (approx 80dp + status bars), Bottom for nav
                contentPadding = PaddingValues(top = 100.dp, bottom = 120.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Empty State
                if (!uiState.isLoading && uiState.rawItems.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (uiState.searchQuery.isNotBlank()) Icons.Default.Search else Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(80.dp)
                                    .padding(bottom = 16.dp),
                                tint = Color(0xFF2D2A32) // Charcoal Lighter
                            )
                            Text(
                                text = if (uiState.searchQuery.isNotBlank() || uiState.startDate != null) "No results found" else "History is empty",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) "Try adjusting your search or filters." else "Play some music to see it here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCAC4D0) // Warm Gray
                            )
                        }
                    }
                }

                uiState.groupedItems.forEach { (dateHeader, items) ->
                    stickyHeader {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TempoDarkBackground.copy(alpha = 0.8f)) // Semitransparent background for sticky header
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = dateHeader,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TempoRed,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        SwipeToDeleteHistoryItem(
                            item = item,
                            index = index,
                            onDelete = { viewModel.deleteListeningEvent(item.id) },
                            onClick = { onNavigateToTrack(item.track_id) }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                if (uiState.isLoading || uiState.isLoadingMore) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = TempoRed,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }

            // Search & Filter Header (Overlay)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(TempoDarkBackground.copy(alpha = 0.9f)) // Almost opaque status bar/header area for legibility
                    .statusBarsPadding()
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search Bar
                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        backgroundColor = Color(0xFF1E1B24).copy(alpha = 0.6f),
                        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color(0xFFCAC4D0)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search songs...",
                                        color = Color(0xFFCAC4D0),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = viewModel::onSearchQueryChanged,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                    singleLine = true,
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TempoRed),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.onSearchQueryChanged("") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFCAC4D0)
                                    )
                                }
                            }
                        }
                    }

                    // Filter Button
                    val isFilterActive = uiState.startDate != null || !uiState.showSkips
                    val filterBgColor = if (isFilterActive) TempoRed.copy(alpha = 0.2f) else Color(0xFF1E1B24).copy(alpha = 0.6f)
                    val filterIconColor = if (isFilterActive) TempoRed else Color(0xFFCAC4D0)

                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(filterBgColor)
                            .clickable { showFilterSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Filter",
                            tint = filterIconColor
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = "Filter History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "Time Range", 
            style = MaterialTheme.typography.labelLarge, 
            color = Color(0xFFCAC4D0)
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedRange == "All Time",
                onClick = { selectedRange = "All Time" },
                label = { Text("All Time") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TempoRed,
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF2D2A32),
                    labelColor = Color(0xFFCAC4D0)
                ),
                border = null
            )
            FilterChip(
                selected = selectedRange == "Last 7 Days",
                onClick = { selectedRange = "Last 7 Days" },
                label = { Text("7 Days") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TempoRed,
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF2D2A32),
                    labelColor = Color(0xFFCAC4D0)
                ),
                border = null
            )
            FilterChip(
                selected = selectedRange == "Last 30 Days",
                onClick = { selectedRange = "Last 30 Days" },
                label = { Text("30 Days") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TempoRed,
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF2D2A32),
                    labelColor = Color(0xFFCAC4D0)
                ),
                border = null
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "Playback", 
            style = MaterialTheme.typography.labelLarge, 
            color = Color(0xFFCAC4D0)
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        GlassCard(
            modifier = Modifier.fillMaxWidth().clickable { showSkips = !showSkips },
            backgroundColor = Color(0xFF2D2A32).copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(16.dp),
            variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Show Skipped Songs", 
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showSkips,
                    onCheckedChange = { showSkips = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TempoRed
                    )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0))
            ) {
                Text("Reset")
            }
            
            Button(
                onClick = { 
                    val (start, end) = getRangeBounds(selectedRange)
                    onApply(start, end, showSkips) 
                },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TempoRed)
            ) {
                Text("Apply Filters")
            }
        }
    }
}

fun getRangeLabel(start: Long?): String {
    if (start == null) return "All Time"
    val now = System.currentTimeMillis()
    val diff = now - start
    return when {
        diff < 7L * 24 * 60 * 60 * 1000 -> "Last 7 Days"
        diff < 30L * 24 * 60 * 60 * 1000 -> "Last 30 Days"
        else -> "Custom"
    }
}

fun getRangeBounds(label: String): Pair<Long?, Long?> {
    val now = System.currentTimeMillis()
    return when (label) {
        "Last 7 Days" -> Pair(now - 7L * 24 * 60 * 60 * 1000, now)
        "Last 30 Days" -> Pair(now - 30L * 24 * 60 * 60 * 1000, now)
        else -> Pair(null, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteHistoryItem(
    item: HistoryItem,
    index: Int,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                showDeleteDialog = true
            }
            false // Don't auto-dismiss, wait for user confirmation
        }
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = TempoRed
                )
            },
            title = {
                Text("Delete from history?")
            },
            text = {
                Text("This will permanently remove \"${item.title}\" from your listening history.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TempoRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1E1B24), // Charcoal Surface
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCAC4D0) // Warm Gray
        )
    }

    val isSwiping = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart || 
                    dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
                    dismissState.progress > 0.0f

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Delete background - Only visible when swiping
            val backgroundColor = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                TempoRed.copy(alpha = 0.9f)
            } else {
                 Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp)) // Match card shape
                    .background(backgroundColor),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.padding(end = 24.dp)
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
            onDeleteClick = { showDeleteDialog = true }
        )
    }
}

@Composable
fun HistoryListItem(
    item: HistoryItem, 
    index: Int, 
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        backgroundColor = Color(0xFF1E1B24).copy(alpha = 0.4f), // Warm Charcoal Transparent
        contentPadding = PaddingValues(12.dp),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (item.album_art_url.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                } else {
                    CachedAsyncImage(
                        imageUrl = item.album_art_url,
                        contentDescription = "Album art for ${item.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCAC4D0), // Warm Gray
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.height(56.dp) // Match height of album art for alignment
            ) {
                Text(
                    text = formatRelativeTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFA8A29E) // Warm Mid-Gray
                )
                
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFA8A29E), // Warm Mid-Gray
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

fun formatRelativeTime(timestamp: Long): String {
    val now = Instant.now()
    val time = Instant.ofEpochMilli(timestamp)
    val diff = ChronoUnit.MINUTES.between(time, now)

    return when {
        diff < 60 -> "${diff}m ago"
        diff < 24 * 60 -> "${diff / 60}h ago"
        else -> {
            val date = time.atZone(ZoneId.systemDefault()).toLocalDate()
            val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
            if (date.isEqual(today.minusDays(1))) "Yesterday"
            else date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }
    }
}
