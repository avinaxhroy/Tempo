package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.imageLoader
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.buildCachedImageRequest
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard

import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.ArtistShareCard
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ArtistDetailsScreen(
    artistId: Long? = null,
    artistName: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    viewModel: ArtistDetailsViewModel = hiltViewModel()
) {
    // Load artist by ID or name
    LaunchedEffect(artistId, artistName) {
        if (artistId != null && artistId > 0) {
            viewModel.loadArtistById(artistId)
        } else if (artistName != null) {
            viewModel.loadArtistByName(artistName)
        }
    }
    
    val uiState by viewModel.uiState.collectAsState()
    val artistDetails = uiState.artistDetails

    DeepOceanBackground {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TempoRed)
            }
        } else if (artistDetails != null) {
            ArtistDetailsContent(
                artistDetails = artistDetails,
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onNavigateToSong = onNavigateToSong
            )
        } else {
            // Error state with back button and retry option
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Back button row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                
                // Error content
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Artist not found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.retry() },
                            colors = ButtonDefaults.buttonColors(containerColor = TempoRed)
                        ) {
                            Text("Retry", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistDetailsContent(
    artistDetails: ArtistDetails,
    uiState: ArtistDetailsUiState,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit
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
                text = "Artist Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            
            IconButton(
                onClick = { showShareDialog = true },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            
            // More options menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MergeType,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Merge with...", color = Color.White)
                            }
                        },
                        onClick = {
                            showMenu = false
                            showMergeDialog = true
                        }
                    )
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
                Spacer(modifier = Modifier.height(8.dp))
                ArtistHeroSection(artistDetails = artistDetails)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                ArtistStatsGrid(artistDetails = artistDetails)
            }

            // Fan Status Badge
            if (artistDetails.personalPlayCount > 5) { // Show for almost everyone now
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    FanStatusBadge(
                        playCount = artistDetails.personalPlayCount,
                        percentile = uiState.artistPercentile
                    )
                }
            }


            item {
                Spacer(modifier = Modifier.height(24.dp))
                DiscoveryInsightCard(artistDetails = artistDetails)
            }
            
            // Top Songs Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Top Songs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
            }

            items(
                items = artistDetails.topSongs.take(5),
                key = { song -> song.trackId }
            ) { song ->
                TopSongItem(song = song, onClick = { onNavigateToSong(song.trackId) })
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Top Albums Section
            if (artistDetails.topAlbums.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Top Albums",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(
                            items = artistDetails.topAlbums,
                            key = { album -> "album_${artistDetails.topAlbums.indexOf(album)}_${album.album}_${album.artist}" }
                        ) { album ->
                            TopAlbumCard(album = album)
                        }
                    }
                }
            }
            
            // Mood & Genre Section
            if (artistDetails.moodSummary != null) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    MoodInsightsSection(moodSummary = artistDetails.moodSummary)
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showShareDialog) {
            SharePreviewDialog(
                onDismiss = { showShareDialog = false },
                contentToShare = {
                    ArtistShareCard(artistDetails = artistDetails)
                }
            )
        }
        
        // Artist Merge Dialog
        if (showMergeDialog) {
            ArtistMergeSearchDialog(
                sourceArtistId = artistDetails.artist.id,
                sourceArtistName = artistDetails.artist.name,
                onDismiss = { showMergeDialog = false },
                onMergeComplete = {
                    // Navigate back after successful merge
                    onNavigateBack()
                }
            )
        }
    }
}

@Composable
fun ArtistHeroSection(
    artistDetails: ArtistDetails
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
         // Premium Glow Container
        Box(contentAlignment = Alignment.Center) {
            // Glow Layer (Indigo/Violet for Artists)
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF6366F1).copy(alpha = 0.3f), // Indigo
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
                val context = LocalContext.current
                val imageUrl = artistDetails.artist.imageUrl
                
                // Log for debugging
                android.util.Log.d("ArtistHeroSection", "Artist: ${artistDetails.artist.name}, imageUrl: $imageUrl")
                
                if (imageUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = artistDetails.artist.name.firstOrNull()?.uppercase() ?: "?",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    var showPlaceholder by remember { mutableStateOf(false) }
                    
                    // Use cached image request for proper caching
                    val imageRequest = remember(imageUrl) {
                        buildCachedImageRequest(
                            context = context,
                            url = imageUrl,
                            allowHardware = true,
                            crossfade = 150
                        )
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = imageRequest,
                            imageLoader = context.imageLoader,
                            contentDescription = "Artist Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            onError = {
                                android.util.Log.e("ArtistHeroSection", "Failed to load image: ${it.result.throwable.message}")
                                showPlaceholder = true
                            }
                        )
                        
                        // Show placeholder on error
                        if (showPlaceholder) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = artistDetails.artist.name.firstOrNull()?.uppercase() ?: "?",
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Artist name
        Text(
            text = artistDetails.artist.name,
            style = MaterialTheme.typography.displaySmall, // Larger
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Artist Country
        if (!artistDetails.country.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            GlassCard(
                modifier = Modifier.wrapContentWidth(),
                backgroundColor = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(32.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                     Text(
                         text = "📍 ${artistDetails.country}",
                         style = MaterialTheme.typography.bodyMedium,
                         color = Color(0xFFCBD5E1) // Slate 300
                     )
                }
            }
        }
        
        // Genres
        if (artistDetails.artist.genres.isNotEmpty() || artistDetails.topGenres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF94A3B8) // Slate 400
                )
                Spacer(modifier = Modifier.width(4.dp))
                val genres = artistDetails.topGenres.ifEmpty { artistDetails.artist.genres }
                Text(
                    text = genres.take(3).joinToString(", ") { it.replaceFirstChar { char -> char.uppercase() } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8) // Slate 400
                )
            }
        }
    }
}

@Composable
fun ArtistCountry(country: String) {
    GlassCard(
        modifier = Modifier.wrapContentWidth(),
        backgroundColor = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(32.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
             Text(
                 text = "📍 $country",
                 style = MaterialTheme.typography.bodyMedium,
                 color = Color(0xFFCBD5E1) // Slate 300
             )
        }
    }
}

@Composable
fun ArtistStatsGrid(artistDetails: ArtistDetails) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassCard(
            modifier = Modifier.weight(1f),
            backgroundColor = Color(0xFF7F1D1D).copy(alpha = 0.2f), // Red
            variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Total Plays",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFCA5A5) // Red 300
                )
                Text(
                    text = formatCount(artistDetails.personalPlayCount.toLong()),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        GlassCard(
            modifier = Modifier.weight(1f),
            backgroundColor = Color(0xFF1E3A8A).copy(alpha = 0.2f), // Blue
            variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Listening Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF93C5FD) // Blue 300
                )
                Text(
                    text = formatListeningTime(artistDetails.personalTotalTimeMs.toLong()),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun DiscoveryInsightCard(artistDetails: ArtistDetails) {
    if (artistDetails.firstDiscovery != null) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.15f) // Violet tint
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFC4B5FD), // Violet 300
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Discovery Insight",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFC4B5FD),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                val dateStr = formatDate(artistDetails.firstDiscovery.firstListenTimestamp)
                
                Text(
                    text = buildAnnotatedString {
                        append("You first discovered ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                            append(artistDetails.artist.name)
                        }
                        append(" on ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                            append(dateStr)
                        }
                        append(". Since then, you've listened for ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                            append(formatListeningTime(artistDetails.personalTotalTimeMs))
                        }
                        append(" across ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                            append("${artistDetails.uniqueTracksPlayed} tracks")
                        }
                        append(".")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 24.sp
                )
            }
        }
    }
}

@Composable
fun TopSongItem(song: TopTrack, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = Color.White.copy(alpha = 0.05f),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedAsyncImage(
                imageUrl = song.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.album ?: "Unknown Album",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${song.playCount}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TempoRed
                )
                Text(
                    text = "plays",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
fun TopAlbumCard(album: TopAlbum) {
    GlassCard(
        modifier = Modifier.width(160.dp),
        backgroundColor = Color.White.copy(alpha = 0.05f),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Album art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF334155)),
                contentAlignment = Alignment.Center
            ) {
                if (album.albumArtUrl.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                } else {
                    CachedAsyncImage(
                        imageUrl = album.albumArtUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Album info
            Text(
                text = album.album,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${album.playCount} plays",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}





fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
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


@Composable
fun FanStatusBadge(playCount: Int, percentile: Double? = null) {
    val (status, emoji, color, description) = if (percentile != null) {
        when {
            percentile <= 1.0 -> Quadruple("Top 1% Artist", "👑", Color(0xFFFFD700), "This is one of your absolute favorites!")
            percentile <= 5.0 -> Quadruple("Top 5% Artist", "🌟", Color(0xFFF59E0B), "You listen to them more than most.")
            percentile <= 10.0 -> Quadruple("Top 10% Artist", "🔥", Color(0xFFEF4444), "Definitely one of your top picks.")
            percentile <= 25.0 -> Quadruple("Top 25% Artist", "🎧", Color(0xFF3B82F6), "A solid part of your rotation.")
            percentile <= 50.0 -> Quadruple("Top 50% Artist", "🎵", Color(0xFF8B5CF6), "You listen to them occasionally.")
            else -> Quadruple("Listener", "🎵", Color(0xFF94A3B8), "Keep listening to climb the ranks!")
        }
    } else {
        // Fallback to absolute counts if percentile not available
         when {
            playCount > 1000 -> Quadruple("Ultimate Stan", "👑", Color(0xFFFFD700), "You're in the top tier of listeners!")
            playCount > 500 -> Quadruple("Super Fan", "🌟", Color(0xFFF59E0B), "You really love this artist.")
            playCount > 200 -> Quadruple("Big Fan", "🔥", Color(0xFFEF4444), "One of your favorites.")
            playCount > 50 -> Quadruple("Regular Listener", "🎧", Color(0xFF3B82F6), "You listen to them quite a bit.")
            else -> Quadruple("Listener", "🎵", Color(0xFF94A3B8), "Keep listening to level up!")
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 24.sp)
            }
            
            Column {
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1) // Slate 300
                )
            }
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

fun formatDate(timestamp: Long): String {
    // Exact date format
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
