package me.avinas.tempo.ui.details

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard

import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.SongShareCard
import androidx.compose.material.icons.filled.Share

@Composable
fun SongDetailsScreen(
    trackId: Long,
    onNavigateBack: () -> Unit,
    viewModel: SongDetailsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val trackDetails = uiState.trackDetails
    val context = LocalContext.current
    val isSpotifyConnected = spotifyViewModel.isConnected()

    DeepOceanBackground {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TempoRed)
            }
        } else if (trackDetails != null) {
            SongDetailsContent(
                trackDetails = trackDetails,
                listeningHistory = uiState.listeningHistory,
                moodSummary = uiState.moodSummary,
                engagement = uiState.engagement,
                genre = uiState.genre,
                releaseDate = uiState.releaseDate,
                releaseYear = uiState.releaseYear,
                recordLabel = uiState.recordLabel,
                isSpotifyConnected = isSpotifyConnected,
                onNavigateBack = onNavigateBack
            )
        } else {
            // Error state
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = uiState.error ?: "Track not found",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsContent(
    trackDetails: TrackDetails,
    listeningHistory: List<DailyListening>,
    moodSummary: TagBasedMoodAnalyzer.MoodSummary?,
    engagement: TrackEngagement?,
    genre: String?,
    releaseDate: String?,
    releaseYear: Int?,
    recordLabel: String?,
    isSpotifyConnected: Boolean,
    onNavigateBack: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            
            Text(
                text = "Song Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showShareDialog = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B)) // Dark slate background
                    ) {
                        DropdownMenuItem(
                            text = { Text("Merge duplicate...", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.CallMerge,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            },
                            onClick = {
                                showMenu = false
                                showMergeDialog = true
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SongHeroSection(trackDetails = trackDetails)
            }
            
            // Streaming Links
            if (trackDetails.appleMusicUrl != null || (isSpotifyConnected && trackDetails.spotifyUrl != null)) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    me.avinas.tempo.ui.components.MusicLinksRow(
                        appleMusicUrl = trackDetails.appleMusicUrl,
                        spotifyUrl = if (isSpotifyConnected) trackDetails.spotifyUrl else null
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
                if (trackDetails.isFavorite) {
                    AchievementBadge()
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                StatsGrid(
                    trackDetails = trackDetails,
                    genre = genre,
                    releaseDate = releaseDate,
                    releaseYear = releaseYear
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                ListeningTrendsChart(history = listeningHistory)
            }

            // Mood & Genre Section (from MusicBrainz tags)
            if (moodSummary != null) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    MoodInsightsSection(moodSummary = moodSummary)
                }
            }

            // Engagement Section (from user behavior)
            if (engagement != null) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    EngagementSection(engagement = engagement)
                }
            }
        }
    }


    if (showShareDialog) {
        SharePreviewDialog(
            onDismiss = { showShareDialog = false },
            contentToShare = {
                SongShareCard(trackDetails = trackDetails)
            }
        )
    }

    if (showMergeDialog) {
        MergeSearchDialog(
            sourceTrackId = trackDetails.track.id,
            onDismiss = { showMergeDialog = false },
            onTrackSelected = { /* Handled in ViewModel */ }
        )
    }
}

@Composable
fun SongHeroSection(
    trackDetails: TrackDetails
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Premium Glow Container
        Box(contentAlignment = Alignment.Center) {
            // Glow Layer (Gold/Amber to match generic premium feel, or matching accent)
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFF59E0B).copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Image Container
            GlassCard(
                modifier = Modifier.size(240.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(0.dp),
                backgroundColor = Color.White.copy(alpha = 0.1f)
            ) {
                me.avinas.tempo.ui.components.AlbumArtImage(
                    albumArtUrl = trackDetails.track.albumArtUrl,
                    localArtUrl = trackDetails.localBackupArtUrl,
                    contentDescription = "Album Art for ${trackDetails.track.title}",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Title with Shadow/Glow effect
        Text(
            text = trackDetails.track.title,
            style = MaterialTheme.typography.displaySmall, // Larger
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Artist Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = TempoRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = trackDetails.track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFCBD5E1), // Slate 300
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun AchievementBadge() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color(0xFFEF4444).copy(alpha = 0.1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally, 
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏆", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your All-Time Favorite",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This is your most played song ever.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1), // Slate 300
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun StatsGrid(
    trackDetails: TrackDetails,
    genre: String?,
    releaseDate: String?,
    releaseYear: Int?
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Row 1: Times Played (Full Width)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = Color(0xFF7F1D1D).copy(alpha = 0.2f), // Red
            variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Times Played",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFCA5A5) // Red 300
                )
                Text(
                    text = trackDetails.playCount.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Row 2: Total Listening & Peak Position
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFF1E3A8A).copy(alpha = 0.2f), // Blue
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Total Listening",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF93C5FD) // Blue 300
                    )
                    Text(
                        text = "${trackDetails.totalTimeMinutes}m",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFF581C87).copy(alpha = 0.2f), // Purple
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Peak Position",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFD8B4FE) // Purple 300
                    )
                    Text(
                        text = "#${trackDetails.peakRank ?: "-"}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Row 3: Release Date & Genre
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFF14532D).copy(alpha = 0.2f), // Green
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Release Date",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF86EFAC) // Green 300
                    )
                    Text(
                        text = formatReleaseDate(releaseDate) ?: releaseYear?.toString() ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFF7C2D12).copy(alpha = 0.2f), // Orange
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Genre",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFDBA74) // Orange 300
                    )
                    Text(
                        text = genre ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}



@Composable
fun ListeningTrendsChart(history: List<DailyListening>) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Listening Trends",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data available", color = Color.White.copy(alpha = 0.5f))
                }
            } else {
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                ) {
                    val maxPlays = history.maxOfOrNull { it.playCount }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                    // Add padding to top (max value at 80% height)
                    val yRange = maxPlays * 1.25f
                    
                    val widthPerPoint = size.width / (history.size - 1).coerceAtLeast(1)
                    
                    val path = Path()
                    val fillPath = Path()
                    
                    fillPath.moveTo(0f, size.height)
                    
                    if (history.size == 1) {
                        // Single point - draw a flat line
                        val item = history.first()
                        val y = size.height - (item.playCount / yRange * size.height)
                        
                        path.moveTo(0f, y)
                        path.lineTo(size.width, y)
                        
                        fillPath.lineTo(0f, y)
                        fillPath.lineTo(size.width, y)
                        fillPath.lineTo(size.width, size.height)
                    } else {
                        history.forEachIndexed { index, item ->
                            val x = index * widthPerPoint
                            val y = size.height - (item.playCount / yRange * size.height)
                            
                            if (index == 0) {
                                path.moveTo(x, y)
                                fillPath.lineTo(x, y)
                            } else {
                                // Smooth curve
                                val prevX = (index - 1) * widthPerPoint
                                val prevY = size.height - (history[index - 1].playCount / yRange * size.height)
                                val controlX1 = prevX + (x - prevX) / 2
                                val controlX2 = prevX + (x - prevX) / 2
                                
                                path.cubicTo(controlX1, prevY, controlX2, y, x, y)
                                fillPath.cubicTo(controlX1, prevY, controlX2, y, x, y)
                            }
                        }
                        fillPath.lineTo(size.width, size.height)
                    }
                    
                    fillPath.close()
                    
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                TempoRed.copy(alpha = 0.4f),
                                TempoRed.copy(alpha = 0.0f)
                            )
                        )
                    )
                    
                    drawPath(
                        path = path,
                        color = TempoRed,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

@Composable
fun MoodInsightsSection(moodSummary: TagBasedMoodAnalyzer.MoodSummary) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mood & Genre",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                // Removed attribution text

            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Main mood summary
            GlassCard(
                contentPadding = PaddingValues(12.dp),
                backgroundColor = Color.White.copy(alpha = 0.05f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MoodIndicator(
                        emoji = moodSummary.moodEmoji,
                        label = moodSummary.moodName,
                        sublabel = "Mood"
                    )
                    MoodIndicator(
                        emoji = getEnergyEmoji(moodSummary.energy.value),
                        label = moodSummary.energyName,
                        sublabel = "Energy"
                    )
                    if (moodSummary.primaryGenre != null) {
                        MoodIndicator(
                            emoji = "🎵",
                            label = moodSummary.primaryGenre.take(12),
                            sublabel = "Genre"
                        )
                    }
                }
            }
            
            // Energy bar
            Spacer(modifier = Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Estimated Energy",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${moodSummary.energyPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { moodSummary.energy.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFFEF4444),
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
            }
            
            // Mood tags if available
            if (moodSummary.moodTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    moodSummary.moodTags.forEach { tag ->
                        TagChip(tag = tag)
                    }
                }
            }
        }
    }
}

@Composable
fun EngagementSection(engagement: TrackEngagement) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Engagement",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = engagement.engagementEmoji,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = engagement.engagementLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = getEngagementColor(engagement.engagementScore)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Engagement score
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Engagement Score",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${engagement.engagementScore}/100",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { engagement.engagementScore / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = getEngagementColor(engagement.engagementScore),
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Behavior stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BehaviorStat(
                    label = "Full Plays",
                    value = "${engagement.fullPlaysCount}",
                    subtext = "of ${engagement.playCount}"
                )
                BehaviorStat(
                    label = "Avg. Completion",
                    value = "${engagement.averageCompletionPercent.toInt()}%",
                    subtext = engagement.completionPattern
                )
                BehaviorStat(
                    label = "Replays",
                    value = "${engagement.replayCount}",
                    subtext = "back-to-back"
                )

            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Completion & Skip Rate Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Completion Rate Circular Indicator
                GlassCard(
                    modifier = Modifier.weight(1f),
                    backgroundColor = Color.White.copy(alpha = 0.05f),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Completion Rate", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { engagement.averageCompletionPercent / 100f },
                                modifier = Modifier.size(64.dp),
                                color = Color(0xFF10B981), // Emerald
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeWidth = 6.dp
                            )
                            Text(
                                text = "${engagement.averageCompletionPercent.toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                
                // Skip Rate Circular Indicator
                GlassCard(
                    modifier = Modifier.weight(1f),
                    backgroundColor = Color.White.copy(alpha = 0.05f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Skip Rate", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(12.dp))
                         // Calculate skip rate
                        val skipRate = if (engagement.playCount > 0) (engagement.skipsCount.toFloat() / engagement.playCount) else 0f
                        
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { skipRate.coerceIn(0f, 1f) },
                                modifier = Modifier.size(64.dp),
                                color = if (skipRate > 0.5f) Color(0xFFEF4444) else Color(0xFFF59E0B), // Red/Amber
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeWidth = 6.dp
                            )
                            Text(
                                text = "${(skipRate * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            
            // Skip info if any
            if (engagement.skipsCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⏭️ Skipped ${engagement.skipsCount} time${if (engagement.skipsCount > 1) "s" else ""} before finishing",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            // Pause info if significant
            if (engagement.averagePauseCount > 0.5f) {
                Spacer(modifier = Modifier.height(8.dp))
                val pauseText = when {
                    engagement.averagePauseCount >= 3f -> "⏸️ Frequently paused (avg ${String.format("%.1f", engagement.averagePauseCount)} pauses/play)"
                    engagement.averagePauseCount >= 1f -> "⏸️ Occasionally paused"
                    else -> "⏸️ Rarely paused"
                }
                Text(
                    text = pauseText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            // Sessions info if available
            if (engagement.uniqueSessionsCount > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📅 Played across ${engagement.uniqueSessionsCount} different sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun TagChip(tag: String) {
    Box(
        modifier = Modifier
            .background(
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun BehaviorStat(
    label: String,
    value: String,
    subtext: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
        Text(
            text = subtext,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp
        )
    }
}

private fun getEngagementColor(score: Int): Color {
    return when {
        score >= 80 -> Color(0xFFEF4444) // Red - favorite
        score >= 60 -> Color(0xFFF59E0B) // Amber - loved
        score >= 40 -> Color(0xFF22C55E) // Green - enjoyed
        score >= 20 -> Color(0xFF3B82F6) // Blue - casual
        else -> Color(0xFF6B7280)        // Gray - background
    }
}

@Composable
fun MoodIndicator(
    emoji: String,
    label: String,
    sublabel: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = sublabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun AudioFeatureBar(
    label: String,
    value: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

private fun getEnergyEmoji(energy: Float): String {
    return when {
        energy >= 0.7f -> "⚡"
        energy >= 0.5f -> "🔥"
        energy >= 0.3f -> "🌊"
        else -> "😴"
    }
}

fun formatReleaseDate(dateString: String?): String? {
    if (dateString.isNullOrBlank()) return null
    return try {
        // Try parsing ISO date (e.g. 2023-01-15)
        val date = java.time.LocalDate.parse(dateString.take(10))
        java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy").format(date)
    } catch (e: Exception) {
        // If parsing fails (e.g. just a year), just return it as is or null if we want to fallback to year int
        null 
    }
}
