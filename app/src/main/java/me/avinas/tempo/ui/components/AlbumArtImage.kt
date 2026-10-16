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
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService

private const val TAG = "AlbumArtImage"

/**
 * Album art image with tiered fallback resolution:
 * 1. Attempts remote [albumArtUrl].
 * 2. Falls back to [localArtUrl] if remote fails or is unavailable.
 * 3. Triggers [onHotlinkSuccess] to purge the local file once the remote asset loads.
 */
@Composable
fun AlbumArtImage(
    albumArtUrl: String?,
    localArtUrl: String? = null,
    contentDescription: String? = "Album Art",
    modifier: Modifier = Modifier.fillMaxSize(),
    contentScale: ContentScale = ContentScale.Crop,
    placeholderEmoji: String = "🎵",
    targetSizePx: Int = 1024,
    onHotlinkSuccess: ((String) -> Unit)? = null,
    onPaletteExtracted: ((Color) -> Unit)? = null,
    onArtworkReady: ((android.graphics.Bitmap, Palette?) -> Unit)? = null,
    onImageLoaded: (() -> Unit)? = null,
    onImageError: ((Throwable?) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Get the singleton ImageLoader with our cache configuration
    // This uses the ImageLoaderFactory implementation in TempoApplication
    // which provides the Hilt-injected singleton with 50MB disk cache
    val imageLoader = context.imageLoader

    // State to track if we should try local fallback
    var useLocalFallback by remember(albumArtUrl, localArtUrl) { mutableStateOf(false) }

    // Determine which URL to use - only proceed if we have valid data
    val urlToLoad =
        when {
            !useLocalFallback && !albumArtUrl.isNullOrBlank() -> {
                // Try enriched hotlink first (fix HTTP to HTTPS)
                MusicBrainzEnrichmentService.fixHttpUrl(albumArtUrl)
            }

            !localArtUrl.isNullOrBlank() -> {
                localArtUrl
            }

            else -> {
                null
            }
        }

    // Show placeholder if no valid URL
    if (urlToLoad.isNullOrBlank()) {
        // No URL - show placeholder
        AlbumArtPlaceholder(emoji = placeholderEmoji, modifier = modifier)
    } else {
        val isHotlink = !useLocalFallback && !albumArtUrl.isNullOrBlank()

        // Create a stable cache key that ignores URL parameters and size
        val cacheKey =
            remember(urlToLoad) {
                createCacheKey(urlToLoad)
            }

        // Create a stable, cacheable image request with aggressive caching
        val imageRequest =
            remember(urlToLoad, cacheKey, targetSizePx) {
                ImageRequest
                    .Builder(context)
                    .data(urlToLoad)
                    // Force aggressive caching
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    // Use smart cache keys that normalize URLs for better cache hits
                    .memoryCacheKey("$cacheKey:$targetSizePx")
                    .diskCacheKey(cacheKey)
                    // EXACT precision with targetSizePx cap guarantees the decoded bitmap never exceeds
                    // a safe size, preventing Canvas "too large bitmap" crashes on devices with
                    // strict software bitmap limits.
                    .precision(Precision.EXACT)
                    .scale(Scale.FILL)
                    .size(Size(targetSizePx, targetSizePx))
                    .build()
            }

        // Use the singleton ImageLoader with painter
        val painter =
            rememberAsyncImagePainter(
                model = imageRequest,
                imageLoader = imageLoader,
                contentScale = contentScale,
            )

        // painter.state is NOT composition-reactive in this Coil/Compose combo
        // (the painter draws fine, but reading .value in composition never
        // recomposes). Drive all load-state logic from local state instead.
        var painterState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }
        val hasCanvasCallbacks = onPaletteExtracted != null || onArtworkReady != null
        LaunchedEffect(painter) {
            snapshotFlow { painter.state.value }.collect { painterState = it }
        }

        // Handle load state callbacks in a standalone effect
        LaunchedEffect(painterState, urlToLoad) {
            when (val s = painterState) {
                is AsyncImagePainter.State.Success -> {
                    onImageLoaded?.invoke()
                    if (isHotlink && !localArtUrl.isNullOrBlank() && localArtUrl.startsWith("file://")) {
                        onHotlinkSuccess?.invoke(albumArtUrl!!)
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    onImageError?.invoke(s.result.throwable)
                    if (isHotlink && !localArtUrl.isNullOrBlank()) {
                        Log.w(TAG, "Hotlink failed: $urlToLoad, falling back to local")
                        useLocalFallback = true
                    }
                }
                else -> Unit
            }
        }

        // Palette extraction in a standalone effect - never nested in the
        // conditional composition so it cannot be skipped by recomposition order.
        LaunchedEffect(painterState) {
            val s = painterState
            if (s is AsyncImagePainter.State.Success && hasCanvasCallbacks) {
                val image = s.result.image
                val bitmap = (image as? BitmapImage)?.bitmap
                bitmap?.let {
                    // Coil caches HARDWARE bitmaps - Palette can't read their pixels.
                    val software =
                        if (it.config == android.graphics.Bitmap.Config.HARDWARE) {
                            it.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        } else {
                            it
                        }
                    Palette.from(software).generate { palette ->
                        // Palette can come back empty for flat/low-saturation
                        // art (e.g. white covers) - fall back to the bitmap's
                        // average color so callbacks ALWAYS fire.
                        val dominant = palette?.dominantSwatch?.rgb ?: averageColor(software)
                        onPaletteExtracted?.invoke(Color(dominant))
                        // The bitmap always goes to the canvas, palette or not.
                        onArtworkReady?.invoke(software, palette)
                    }
                }
            }
        }

        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/**
 * Grid-average color of a bitmap - cheap fallback when Palette returns no
 * swatches (flat, near-white, or low-saturation artwork).
 */
private fun averageColor(bitmap: android.graphics.Bitmap): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val step = maxOf(1, bitmap.width / 8)
    var x = 0
    while (x < bitmap.width) {
        var y = 0
        while (y < bitmap.height) {
            val c = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(c)
            g += android.graphics.Color.green(c)
            b += android.graphics.Color.blue(c)
            n++
            y += step
        }
        x += step
    }
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}


/**
 * Placeholder for album art when no image is available.
 */
@Composable
fun AlbumArtPlaceholder(
    emoji: String = "🎵",
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(
        modifier =
            modifier
                .background(
                    Brush.linearGradient(
                        colors =
                            listOf(
                                Color(0xFFF59E0B).copy(alpha = 0.2f),
                                Color(0xFFD97706).copy(alpha = 0.1f),
                            ),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = 48.sp,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/**
 * Extension function to fix HTTP URLs to HTTPS.
 * Can be used in places where AlbumArtImage composable isn't suitable.
 */
fun String?.fixAlbumArtUrl(): String? = MusicBrainzEnrichmentService.fixHttpUrl(this)
