package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.BitmapImage
import coil3.imageLoader
import me.avinas.tempo.ui.components.buildCachedImageRequest
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats

@Composable
fun AlbumDetailsScreen(
    albumId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    viewModel: AlbumDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val albumDetails = uiState.albumDetails

    var dominantColor by remember { mutableStateOf(Color(0xFF121212)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(dominantColor.copy(alpha = 0.6f), MaterialTheme.colorScheme.background)
                )
            )
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (albumDetails != null) {
            AlbumDetailsContent(
                albumDetails = albumDetails,
                onNavigateBack = onNavigateBack,
                onNavigateToSong = onNavigateToSong,
                onPaletteExtracted = { color -> dominantColor = color }
            )
        } else {
            Text(
                text = uiState.error ?: "Album not found",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailsContent(
    albumDetails: AlbumDetails,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onPaletteExtracted: (Color) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            var showMenu by remember { mutableStateOf(false) }
            val context = LocalContext.current
            
            TopAppBar(
                title = { Text("Album Details", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    showMenu = false
                                    val shareText = "Check out ${albumDetails.album.title} by ${albumDetails.artistName} on Tempo!"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share album"))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                AlbumHeroSection(
                    albumDetails = albumDetails,
                    onPaletteExtracted = onPaletteExtracted
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                AlbumStatsRow(albumDetails = albumDetails)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
            }

            items(albumDetails.tracks) { track ->
                AlbumTrackItem(track = track, onClick = { onNavigateToSong(track.track.id) })
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun AlbumHeroSection(
    albumDetails: AlbumDetails,
    onPaletteExtracted: (Color) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.size(220.dp)
        ) {
            val context = LocalContext.current
            val artworkUrl = albumDetails.album.artworkUrl
            
            if (artworkUrl.isNullOrBlank()) {
                // Show placeholder when no artwork available
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💿",
                        fontSize = 64.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Use cached image request for proper caching
                val imageRequest = remember(artworkUrl) {
                    buildCachedImageRequest(
                        context = context,
                        url = artworkUrl,
                        allowHardware = false,
                        crossfade = 150
                    )
                }
                
                AsyncImage(
                    model = imageRequest,
                    imageLoader = context.imageLoader,
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Success) {
                            // Extract drawable and generate palette color
                            val image = state.result.image
                            val bitmap = (image as? BitmapImage)?.bitmap
                            bitmap?.let {
                                Palette.from(it).generate { palette ->
                                    palette?.dominantSwatch?.rgb?.let { color ->
                                        onPaletteExtracted(Color(color))
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = albumDetails.album.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = albumDetails.artistName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        albumDetails.album.releaseYear?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun AlbumStatsRow(albumDetails: AlbumDetails) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = albumDetails.totalPlayCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Plays",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${albumDetails.totalTimeMinutes}m",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Time",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${albumDetails.completionRate.toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Completion",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun AlbumTrackItem(track: TrackWithStats, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (track.track.id % 100).toString(), // Placeholder for track number if not available
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Text(
                text = "${track.playCount} plays",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Text(
            text = formatDuration(track.track.duration ?: 0),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 1000 / 60
    val seconds = (ms / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}
