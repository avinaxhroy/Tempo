package me.avinas.tempo.ui.components

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService

private const val TAG = "CachedAsyncImage"

/**
 * A cached image component that ensures all images are properly cached.
 * 
 * This composable wraps Coil's AsyncImage with proper cache configuration:
 * - Uses explicit memory and disk cache keys based on URL (ignoring size)
 * - Uses INEXACT precision to allow cached images of different sizes to be reused
 * - Automatically fixes HTTP URLs to HTTPS
 * - Uses the app-wide singleton ImageLoader with 50MB disk cache
 * 
 * @param imageUrl The URL of the image to load
 * @param contentDescription Accessibility description
 * @param modifier Modifier for the image
 * @param contentScale How to scale the image
 * @param placeholder Optional placeholder to show while loading
 * @param error Optional fallback to show on error
 * @param showLoadingIndicator Whether to show a loading indicator
 * @param onSuccess Callback when image loads successfully (with the result)
 * @param onError Callback when image fails to load
 * @param allowHardware Whether to allow hardware bitmaps (set false for screenshots)
 */
@Composable
fun CachedAsyncImage(
    imageUrl: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
    showLoadingIndicator: Boolean = false,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
    allowHardware: Boolean = true
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    
    // Early return for null/blank URLs
    if (imageUrl.isNullOrBlank()) {
        if (error != null) {
            error()
        } else if (placeholder != null) {
            placeholder()
        } else {
            // Default empty state
            Box(modifier = modifier)
        }
        return
    }
    
    // Fix HTTP to HTTPS for security and caching consistency
    val fixedUrl = MusicBrainzEnrichmentService.fixHttpUrl(imageUrl)
    
    // Create a stable cache key from the URL
    // Strip query parameters for more cache hits, as they often contain timestamps
    val cacheKey = remember(fixedUrl) {
        createCacheKey(fixedUrl)
    }
    
    // Build the image request with aggressive caching
    val imageRequest = remember(fixedUrl, cacheKey, allowHardware) {
        ImageRequest.Builder(context)
            .data(fixedUrl)
            // Use URL-based cache keys, ignoring size
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            // INEXACT precision allows cached images of any size to be reused
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            // Aggressive caching policies
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // Short crossfade for better UX
            .crossfade(150)
            // Hardware bitmap control (disable for screenshots)
            .allowHardware(allowHardware)
            .build()
    }
    
    val painter = rememberAsyncImagePainter(
        model = imageRequest,
        imageLoader = imageLoader,
        contentScale = contentScale
    )
    
    // Track state and handle callbacks
    val painterState = painter.state
    
    LaunchedEffect(painterState) {
        when (painterState) {
            is AsyncImagePainter.State.Success -> {
                Log.d(TAG, "Image loaded successfully: $cacheKey")
                onSuccess?.invoke(painterState)
            }
            is AsyncImagePainter.State.Error -> {
                // Spotify content:// URIs are transient - only work when Spotify is running
                // These failures are expected and should not spam warnings
                val isSpotifyContentUri = fixedUrl?.contains("com.spotify.mobile.android.mediaapi") == true
                if (isSpotifyContentUri) {
                    // Silent failure for Spotify content URIs - expected behavior when app not running
                    // No logging needed - this is normal
                } else {
                    Log.w(TAG, "Image failed to load: $cacheKey - ${painterState.result.throwable.message}")
                }
                onError?.invoke(painterState)
            }
            else -> { /* Loading or Empty */ }
        }
    }
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (painterState) {
            is AsyncImagePainter.State.Loading -> {
                // Show placeholder or loading indicator while loading
                if (placeholder != null) {
                    placeholder()
                }
                if (showLoadingIndicator) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            is AsyncImagePainter.State.Error -> {
                // Show error placeholder
                if (error != null) {
                    error()
                } else if (placeholder != null) {
                    placeholder()
                }
            }
            is AsyncImagePainter.State.Success, is AsyncImagePainter.State.Empty -> {
                // Show the image
            }
        }
        
        // Always render the image (it will be empty/loading until ready)
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Simple cached image composable without callbacks - for common use cases.
 * 
 * @param imageUrl The URL of the image to load
 * @param contentDescription Accessibility description
 * @param modifier Modifier for the image
 * @param contentScale How to scale the image
 * @param allowHardware Whether to allow hardware bitmaps
 */
@Composable
fun SimpleCachedImage(
    imageUrl: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true
) {
    CachedAsyncImage(
        imageUrl = imageUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        allowHardware = allowHardware
    )
}

/**
 * Creates a stable cache key from a URL.
 * 
 * This strips common variable parameters like timestamps while keeping the
 * essential parts that identify the image.
 */
fun createCacheKey(url: String?): String {
    if (url.isNullOrBlank()) return ""
    
    return try {
        // Parse the URL and extract just the path + essential query params
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: ""
        val path = uri.path ?: ""
        
        // For common CDNs, we only need host + path
        // Query params often contain size/format hints that we want to ignore
        when {
            // Cover Art Archive - just use path
            host.contains("coverartarchive") || host.contains("archive.org") -> {
                "coverart:$path"
            }
            // MusicBrainz - just use path  
            host.contains("musicbrainz") -> {
                "musicbrainz:$path"
            }
            // iTunes/Apple - strip size params
            host.contains("mzstatic") || host.contains("apple") -> {
                // iTunes URLs have size in the path like 100x100bb.jpg
                // Normalize to a standard size for caching
                val normalizedPath = path.replace(Regex("\\d+x\\d+bb"), "600x600bb")
                "itunes:$normalizedPath"
            }
            // Spotify CDN
            host.contains("scdn.co") || host.contains("spotify") -> {
                "spotify:$path"
            }
            // Discogs
            host.contains("discogs") -> {
                "discogs:$path"
            }
            // Last.fm
            host.contains("lastfm") -> {
                "lastfm:$path"
            }
            // Default: use full URL but strip common variable params
            else -> {
                val cleanUrl = url
                    .replace(Regex("[?&](t|timestamp|v|version|_)=[^&]*"), "")
                    .replace(Regex("[?&]$"), "")
                "url:${cleanUrl.hashCode()}"
            }
        }
    } catch (e: Exception) {
        // Fallback to URL hash
        "hash:${url.hashCode()}"
    }
}

/**
 * Builds a properly cached ImageRequest for use with AsyncImage.
 * Use this when you need more control than CachedAsyncImage provides.
 * 
 * @param context The context
 * @param url The image URL
 * @param allowHardware Whether to allow hardware bitmaps
 * @param crossfade Crossfade duration in milliseconds (0 to disable)
 */
fun buildCachedImageRequest(
    context: android.content.Context,
    url: String?,
    allowHardware: Boolean = true,
    crossfade: Int = 150
): ImageRequest {
    val fixedUrl = MusicBrainzEnrichmentService.fixHttpUrl(url)
    val cacheKey = createCacheKey(fixedUrl)
    
    return ImageRequest.Builder(context)
        .data(fixedUrl)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .precision(Precision.INEXACT)
        .scale(Scale.FILL)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(crossfade)
        .allowHardware(allowHardware)
        .build()
}

/**
 * Default music note placeholder for album art.
 */
@Composable
fun MusicNotePlaceholder(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.5f)
) {
    Box(
        modifier = modifier.background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = tint
        )
    }
}
