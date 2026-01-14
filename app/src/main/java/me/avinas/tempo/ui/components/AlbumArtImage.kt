package me.avinas.tempo.ui.components

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import java.io.File

private const val TAG = "AlbumArtImage"

/**
 * Album art image component with smart fallback system:
 * 
 * Flow:
 * 1. Local bitmap saved immediately as backup
 * 2. Enrichment tries to get hotlink URL
 * 3. UI loads hotlink first (HTTP→HTTPS fixed)
 * 4. If hotlink loads successfully → delete local file (save storage)
 * 5. If hotlink fails → fall back to local file
 * 
 * This optimizes storage while ensuring users always see cover art.
 * 
 * @param albumArtUrl The primary album art URL (hotlink from enrichment)
 * @param localArtUrl The local backup file URL (saved from MediaSession bitmap)
 * @param contentDescription Accessibility description
 * @param modifier Modifier for the image
 * @param contentScale How to scale the image
 * @param placeholderEmoji Emoji to show when no art is available
 * @param onHotlinkSuccess Callback when hotlink loads successfully (to clean up local file)
 */
@Composable
fun AlbumArtImage(
    albumArtUrl: String?,
    localArtUrl: String? = null,
    contentDescription: String? = "Album Art",
    modifier: Modifier = Modifier.fillMaxSize(),
    contentScale: ContentScale = ContentScale.Crop,
    placeholderEmoji: String = "🎵",
    onHotlinkSuccess: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get the singleton ImageLoader with our cache configuration
    // This uses the ImageLoaderFactory implementation in TempoApplication
    // which provides the Hilt-injected singleton with 50MB disk cache
    val imageLoader = context.imageLoader
    
    // State to track if we should try local fallback
    var useLocalFallback by remember(albumArtUrl, localArtUrl) { mutableStateOf(false) }
    
    // Determine which URL to use - only proceed if we have valid data
    val urlToLoad = when {
        !useLocalFallback && !albumArtUrl.isNullOrBlank() -> {
            // Try enriched hotlink first (fix HTTP to HTTPS)
            MusicBrainzEnrichmentService.fixHttpUrl(albumArtUrl)
        }
        !localArtUrl.isNullOrBlank() -> localArtUrl
        else -> null
    }
    
    // Show placeholder if no valid URL
    if (urlToLoad.isNullOrBlank()) {
        // No URL - show placeholder
        AlbumArtPlaceholder(emoji = placeholderEmoji, modifier = modifier)
    } else {
        val isHotlink = !useLocalFallback && !albumArtUrl.isNullOrBlank()
        
        // Create a stable cache key that ignores URL parameters and size
        val cacheKey = remember(urlToLoad) {
            createCacheKey(urlToLoad)
        }
        
        // Create a stable, cacheable image request with aggressive caching
        val imageRequest = remember(urlToLoad, cacheKey) {
            ImageRequest.Builder(context)
                .data(urlToLoad)
                // Force aggressive caching
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                // Use smart cache keys that normalize URLs for better cache hits
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // CRITICAL: Allow using smaller cached images and scaling up
                // INEXACT precision lets Coil reuse cached images even if size doesn't match
                .precision(Precision.INEXACT)
                .scale(Scale.FILL)
                .build()
        }
        
        // Use the singleton ImageLoader with painter
        val painter = rememberAsyncImagePainter(
            model = imageRequest,
            imageLoader = imageLoader,
            contentScale = contentScale
        )
        
        // Handle load state separately from the request
        val state = painter.state.value
        if (state is AsyncImagePainter.State.Success) {
            // Image loaded successfully
            if (isHotlink && !localArtUrl.isNullOrBlank() && localArtUrl.startsWith("file://")) {
                // Hotlink worked! Clean up local backup in background
                LaunchedEffect(urlToLoad) {
                    deleteLocalArtFile(localArtUrl)
                }
                onHotlinkSuccess?.invoke(albumArtUrl!!)
            }
        } else if (state is AsyncImagePainter.State.Error) {
             // Image failed to load
             if (isHotlink && !localArtUrl.isNullOrBlank()) {
                 // Hotlink failed, try local fallback
                 Log.w(TAG, "Hotlink failed: $urlToLoad, falling back to local")
                 useLocalFallback = true
             }
        }
        
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}

/**
 * Delete local album art file to save storage after hotlink loads successfully.
 */
private suspend fun deleteLocalArtFile(localArtUrl: String) {
    withContext(Dispatchers.IO) {
        try {
            val filePath = localArtUrl.removePrefix("file://")
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted local art file to save storage: $filePath")
                } else {
                    Log.w(TAG, "Failed to delete local art file: $filePath")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting local art file", e)
        }
    }
}

/**
 * Placeholder for album art when no image is available.
 */
@Composable
fun AlbumArtPlaceholder(
    emoji: String = "🎵",
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF59E0B).copy(alpha = 0.2f),
                        Color(0xFFD97706).copy(alpha = 0.1f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 48.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/**
 * Extension function to fix HTTP URLs to HTTPS.
 * Can be used in places where AlbumArtImage composable isn't suitable.
 */
fun String?.fixAlbumArtUrl(): String? {
    return MusicBrainzEnrichmentService.fixHttpUrl(this)
}
