package me.avinas.tempo.ui.stats

import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.WarmVioletAccent
import me.avinas.tempo.ui.theme.innerShadow
import androidx.compose.ui.graphics.Brush

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.MusicNotePlaceholder
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.TempoSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Pagination Logic
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

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            viewModel.loadMore()
        }
    }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 100.dp, bottom = 160.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp) // Increased spacing for vertical rhythm
            ) {
                // 1. Sticky Tab Selector
                stickyHeader {
                     Box(
                         modifier = Modifier
                             .fillMaxWidth()
                             .background(TempoDarkBackground.copy(alpha = 0.95f))
                             .padding(bottom = 12.dp)
                     ) {
                         StatsTabSelector(
                             selectedTab = uiState.selectedTab,
                             onTabSelected = viewModel::onTabSelected
                         )
                     }
                }

                item {
                    // Sort By Selector
                    if (uiState.selectedTab != StatsTab.TOP_ALBUMS) {
                        SortBySelector(
                            selectedSortBy = uiState.selectedSortBy,
                            onSortBySelected = viewModel::onSortBySelected
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 2. Stats Items
                if (!uiState.isLoading && uiState.items.isEmpty()) {
                    item {
                        EmptyStatsState()
                    }
                } else if (!uiState.isLoading && uiState.items.isNotEmpty()) {
                    // Separate #1 Item for Hero Treatment
                    val firstItem = uiState.items.firstOrNull()
                    val remainingItems = uiState.items.drop(1)

                    // Rank 1 Hero
                    if (firstItem != null) {
                        item {
                            HeroStatItem(
                                item = firstItem,
                                onNavigate = { resolveNavigation(firstItem, onNavigateToTrack, onNavigateToArtist, onNavigateToAlbum) }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // Remaining Items
                    itemsIndexed(remainingItems) { index, item ->
                        val rank = index + 2 // Start from 2
                        GlassStatItem(
                            rank = rank,
                            item = item,
                            onClick = { resolveNavigation(item, onNavigateToTrack, onNavigateToArtist, onNavigateToAlbum) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Loading More Indicator
                if (uiState.isLoadingMore) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = WarmVioletAccent)
                        }
                    }
                }
            }

            // Top Bar
            val isScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            val headerAlpha by animateFloatAsState(targetValue = if (isScrolled) 1f else 0f, label = "headerAlpha")
            
            Surface(
                color = TempoDarkBackground.copy(alpha = headerAlpha),
                shadowElevation = if (isScrolled) 4.dp else 0.dp,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            ) {
                TopAppBar(
                    title = { 
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }

            // Time Period Filter
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .padding(horizontal = 32.dp)
            ) {
                TimePeriodSelector(
                    selectedRange = uiState.selectedTimeRange,
                    onRangeSelected = viewModel::onTimeRangeSelected,
                    availableRanges = listOf(TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME)
                )
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = WarmVioletAccent)
            }
        }
    }
}

// --- Helper for Navigation ---
private fun resolveNavigation(
    item: Any,
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

// --- Components ---



@Composable
fun HeroStatItem(item: Any, onNavigate: () -> Unit) {
    val (title, subtitle, imageUrl, label) = when (item) {
        is TopTrack -> Quad(item.title, item.artist, item.albumArtUrl, "#1 Track")
        is TopArtist -> Quad(item.artist, "${item.playCount} plays", item.imageUrl, "#1 Artist")
        is TopAlbum -> Quad(item.album, item.artist, item.albumArtUrl, "#1 Album")
        else -> Quad("Unknown", "", null, "")
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onNavigate),
        backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.25f), // Stronger Gold Tint
        contentPadding = PaddingValues(16.dp) // Reduced from 24.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Big Image
            Box(contentAlignment = Alignment.Center) {
                // Glow Layer
                Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(Color(0xFFF59E0B).copy(alpha = 0.25f))) // Reduced glow size
                
                // Image Layer
                if (imageUrl != null) {
                    CachedAsyncImage(
                        imageUrl = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                     Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                         Text(title.firstOrNull()?.toString() ?: "?", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                     }
                }
            }
            
            Spacer(modifier = Modifier.width(20.dp))
            
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

@Composable
fun GlassStatItem(rank: Int, item: Any, onClick: () -> Unit) {
    val (title, subtitle, imageUrl, timeMs) = when (item) {
        is TopTrack -> Quad(item.title, item.artist, item.albumArtUrl, item.totalTimeMs)
        is TopArtist -> Quad(item.artist, "${item.playCount} plays", item.imageUrl, item.totalTimeMs)
        is TopAlbum -> Quad(item.album, item.artist, item.albumArtUrl, item.totalTimeMs)
        else -> Quad("", "", null, 0L)
    }

    // "Fused" Styling: Premium colors for all items
    // Harmonized palette: Plums, Wines, Roses (No Blue-Violet)
    val palette = listOf(
        Color(0xFFC026D3), // Fuchsia 600 (Orchid)
        Color(0xFFDB2777), // Pink 600 (Rose)
        Color(0xFFF59E0B), // Gold
        Color(0xFF9333EA), // Purple 600 (slightly warmer than 500) -> Actually let's use Plum 700: 0xFFA21CAF
        Color(0xFFBE185D), // Pink 700 (Raspberry)
        Color(0xFFE879F9)  // Orchid
    )

    val (tintColor, bgAlpha) = when(rank) {
        1 -> Color(0xFFF59E0B) to 0.25f // Gold
        2 -> Color(0xFFE879F9) to 0.20f // Dusty Orchid (Warmer than Pale Violet)
        3 -> Color(0xFFB45309) to 0.20f // Bronze - Warmer
        else -> palette[(rank - 4) % palette.size] to 0.15f // Cycle through palette
    }
    
    // Smart Composition: Rank 1-3 get 3D/HighProminence, Rest get 2D/LowProminence
    // Since this composable handles rank 2+, we check against 3.
    val variant = if (rank <= 3) me.avinas.tempo.ui.components.GlassCardVariant.HighProminence else me.avinas.tempo.ui.components.GlassCardVariant.LowProminence

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .innerShadow(
                color = if (rank <= 3) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.2f),
                cornersRadius = 16.dp,
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
                color = if (rank <= 3) tintColor else Color.White.copy(alpha = 0.7f),
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
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
            
            Text(formatListeningTime(timeMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
        }
    }
}

// Utility class for destructuring
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun EmptyStatsState() {
     Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text("No stats yet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Start listening to see your top charts!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatsTabSelector(selectedTab: StatsTab, onTabSelected: (StatsTab) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatsTab.values().forEach { tab ->
            val isSelected = tab == selectedTab
            val MutedFuchsia = Color(0xFFCE58E0) // Desaturated for tabs
            val backgroundColor by animateColorAsState(if (isSelected) MutedFuchsia else Color.Transparent, label = "tabBackgroundColor")
            val contentColor by animateColorAsState(if (isSelected) Color.White else Color(0xFFE5E7EB), label = "tabContentColor") // Slightly brighter muted gray
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(backgroundColor)
                    .clickable { onTabSelected(tab) },
                contentAlignment = Alignment.Center
            ) {
                 Text(
                    text = when (tab) {
                        StatsTab.TOP_SONGS -> "Songs"
                        StatsTab.TOP_ARTISTS -> "Artists"
                        StatsTab.TOP_ALBUMS -> "Albums"
                    },
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SortBySelector(selectedSortBy: SortBy, onSortBySelected: (SortBy) -> Unit) {
     var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Sort by: ", style = MaterialTheme.typography.bodySmall, color = Color(0xFFCAC4D0))
        Box {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = when (selectedSortBy) {
                        SortBy.COMBINED_SCORE -> "Combined Score"
                        SortBy.PLAY_COUNT -> "Play Count"
                        SortBy.TOTAL_TIME -> "Total Time"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = WarmVioletAccent
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SortBy.values().forEach { sortBy ->
                    DropdownMenuItem(
                        text = { Text(when (sortBy) {
                            SortBy.COMBINED_SCORE -> "Combined Score"
                            SortBy.PLAY_COUNT -> "Play Count"
                            SortBy.TOTAL_TIME -> "Total Time"
                        }, fontWeight = if (sortBy == selectedSortBy) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { expanded = false; onSortBySelected(sortBy) },
                        leadingIcon = if (sortBy == selectedSortBy) { { Text("✓", color = WarmVioletAccent) } } else null
                    )
                }
            }
        }
    }
}

fun formatListeningTime(millis: Long): String {
    val totalMinutes = millis / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
