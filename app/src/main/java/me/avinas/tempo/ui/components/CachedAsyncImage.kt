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
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.request.allowHardware
import coil3.request.transformations
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.utils.MAX_SAFE_BITMAP_EDGE_PX

private const val TAG = "CachedAsyncImage"

private val ITUNES_SIZE_REGEX = Regex("\\d+x\\d+bb")
private val URL_PARAM_STRIP_REGEX = Regex("[?&](t|timestamp|v|version|_)=[^&]*")
private val URL_TRAILING_SEP_REGEX = Regex("[?&]$")

/**
 * Coil [AsyncImage] wrapper configured with size-independent cache keys,
 * inexact size reuse, and automatic HTTP-to-HTTPS upgrade.
 *
 * `targetSizeDp` downsamples during decode to reduce memory usage; set
 * `allowHardware = false` for screenshots. `blurRadius` goes through a software
 * Coil [coil3.transform.Transformation] rather than `Modifier.blur`: RenderEffect
 * is dropped when a View is drawn to a software Canvas during capture, so the
 * baked blur survives in captured bitmaps.
 */
@Composable
fun CachedAsyncImage(
    imageUrl: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetSizeDp: Int? = null,
    placeholder: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
    showLoadingIndicator: Boolean = false,
    colorFilter: ColorFilter? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
    allowHardware: Boolean = true,
    blurRadius: Dp? = null
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Check if we're inside a CaptureWrapper - if so, disable hardware bitmaps
    val inCaptureContext = LocalInCaptureContext.current
    val effectiveAllowHardware = allowHardware && !inCaptureContext
    
    if (imageUrl.isNullOrBlank()) {
        if (error != null) {
            error()
        } else if (placeholder != null) {
            placeholder()
        } else {
            Box(modifier = modifier)
        }
        return
    }
    
    // Fix HTTP to HTTPS for security and caching consistency
    val fixedUrl = MusicBrainzEnrichmentService.fixHttpUrl(imageUrl)
    
    // Query params are stripped for more cache hits: they often carry timestamps.
    val cacheKey = remember(fixedUrl) {
        createCacheKey(fixedUrl)
    }
    
    val targetSizePx = remember(targetSizeDp, density) {
        targetSizeDp?.let { dp -> with(density) { dp.toFloat() * this.density }.toInt() }
    }

    // In pixels, baked into the decoded bitmap so it survives software-canvas capture.
    val blurRadiusPx = remember(blurRadius, density) {
        blurRadius?.let { with(density) { it.toPx() } }
    }

    // Keyed on the effective hardware flag and blur px so the request rebuilds
    // when capture context or blur settings change, and so a blurred decode can
    // never be served the sharp bitmap another call site already cached.
    val imageRequest = remember(fixedUrl, cacheKey, targetSizePx, effectiveAllowHardware, blurRadiusPx) {
        ImageRequest.Builder(context)
            .data(fixedUrl)
            // Hardware and software bitmaps must not share a memory cache entry.
            .memoryCacheKey(
                buildString {
                    append(cacheKey)
                    if (!effectiveAllowHardware) append("_sw")
                    if (blurRadiusPx != null && blurRadiusPx > 0f) append("_blur").append(blurRadiusPx.toInt())
                }
            )
            .diskCacheKey(cacheKey)
            .scale(Scale.FILL)
            .apply {
                if (targetSizePx != null) {
                    // Bound the decode to the app-wide painter ceiling. Every call site asks for a
                    // small thumbnail (the largest is 320.dp), so this clamp is inert in practice
                    // and only exists so a large `targetSizeDp` can never decode an oversized
                    // bitmap into this Image's painter.
                    val capped = minOf(targetSizePx, MAX_SAFE_BITMAP_EDGE_PX).coerceAtLeast(1)
                    size(Size(capped, capped))
                } else {
                    size(Size(1024, 1024))
                }
                precision(Precision.INEXACT)
                if (blurRadiusPx != null && blurRadiusPx > 0f) {
                    transformations(BlurTransformation(blurRadiusPx))
                }
            }
            // Disable hardware bitmaps when inside CaptureWrapper (software rendering)
            .allowHardware(effectiveAllowHardware)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    
    val painter = rememberAsyncImagePainter(
        model = imageRequest,
        imageLoader = imageLoader,
        contentScale = contentScale
    )
    
    val painterState = painter.state.value

    // A capture view is drawn once and reused for the whole export, so the
    // exporter has to know when this cover has landed rather than racing it
    // (see CaptureImageLoads). Outside a capture view there is nothing to wait
    // for, so the count stays unused.
    val captureLoads = LocalCaptureImageLoads.current
    if (captureLoads != null) {
        DisposableEffect(captureLoads, painterState) {
            val loading =
                painterState is AsyncImagePainter.State.Loading ||
                    painterState is AsyncImagePainter.State.Empty
            if (loading) captureLoads.loading()
            onDispose { if (loading) captureLoads.loaded() }
        }
    }
    
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
                if (error != null) {
                    error()
                } else if (placeholder != null) {
                    placeholder()
                }
            }
            is AsyncImagePainter.State.Success, is AsyncImagePainter.State.Empty -> {
            }
        }
        
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize()
        )
    }
}

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
 * Stable cache key for `url`: volatile params (timestamps, size/format hints)
 * are stripped while the parts that identify the image are kept.
 */
fun createCacheKey(url: String?): String {
    if (url.isNullOrBlank()) return ""
    
    return try {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: ""
        val path = uri.path ?: ""
        
        // For common CDNs only host + path matter; query params are size/format hints.
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
                val normalizedPath = path.replace(ITUNES_SIZE_REGEX, "600x600bb")
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
                    .replace(URL_PARAM_STRIP_REGEX, "")
                    .replace(URL_TRAILING_SEP_REGEX, "")
                "url:${cleanUrl.hashCode()}"
            }
        }
    } catch (e: Exception) {
        // Fallback to URL hash
        "hash:${url.hashCode()}"
    }
}

/**
 * Cached [ImageRequest] for callers that need more control than [CachedAsyncImage].
 * `crossfade` is milliseconds, 0 disables it.
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
        .precision(Precision.EXACT)
        .scale(Scale.FILL)
        .size(Size(1024, 1024))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
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
