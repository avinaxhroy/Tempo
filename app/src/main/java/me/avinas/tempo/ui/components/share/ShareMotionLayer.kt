package me.avinas.tempo.ui.components.share

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.ui.components.ShareTheme
import kotlin.math.roundToInt

/**
 * Compose side of the motion themes: the scene layer, the clock it reads and the
 * palette cache.
 *
 * The scenes themselves are fragment shaders (see [MotionShaderRenderer]); this
 * file only decides *when* to draw them and *what* they are drawn with.
 */

/**
 * A position on a scene's clock, in seconds.
 *
 * A reader rather than a value: [ShareMotionLayer] calls [seconds]
 * inside its draw block, so a ticking clock invalidates *that node's draw* and
 * nothing else. Passing the timestamp as a parameter would recompose the card —
 * and with it every row of the share sheet — sixty times a second, which is what
 * made the old preview stutter.
 */
fun interface MotionTime {
    fun seconds(): Float

    companion object {
        /** A frozen scene, for the still capture and for cards with no motion. */
        val Still: MotionTime = MotionTime { 0f }

        /**
         * A frozen scene at the story's first frame.
         *
         * Used for the carousel's off-centre cards. They are already composed so
         * they are ready the moment they land, and freezing them at [Still] made
         * them jump phase at settle: t=0 is a shader's blank intro, while the
         * clock a card starts animating from is [MOTION_START_SECONDS]. Freezing
         * at the same time the animation begins makes the handoff invisible.
         */
        val Opening: MotionTime = MotionTime { MOTION_START_SECONDS }
    }
}

/**
 * How much of the card's on-screen size a scene is shaded at.
 *
 * The share sheet sets this to the carousel card's layout scale, so a scene is
 * shaded at the pixels it actually occupies instead of always at 1080x1920. The
 * default is full resolution for any other host.
 */
val LocalMotionResolutionScale = staticCompositionLocalOf { 1f }

/**
 * The preview clock: one continuous, unquantised timestamp that advances with
 * the display.
 *
 * The previous clock snapped to 30 fps through a `Long` state, so even on a
 * 120 Hz screen the preview stepped three times per refresh and read as a
 * slideshow. This advances at whatever rate the display draws.
 */
@Composable
fun rememberMotionTime(animate: Boolean, key: Any? = Unit): MotionTime {
    // The preview opens on the same scene time as the file's first frame.
    val clock = remember(key) { mutableFloatStateOf(MOTION_START_SECONDS) }
    LaunchedEffect(animate, key) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            clock.floatValue = MOTION_START_SECONDS + (withFrameNanos { it } - start) / 1_000_000_000f
        }
    }
    return remember(clock) { MotionTime { clock.floatValue } }
}

/**
 * Cover art for the palette, loaded once and cached.
 *
 * The motion palettes are derived from the *artwork of the #1 item*, as the user
 * asked, and a palette extraction cannot run on every recomposition: it needs
 * the bitmap, and the bitmap arrives asynchronously from Coil. This loads the
 * covers once, extracts their palettes off the main thread, and returns a map
 * the card reads through [paletteFor].
 */
@Composable
fun rememberArtworkPalettes(urls: List<String?>): Map<String, ArtworkPalette> {
    val context = LocalContext.current
    val palettes = remember { mutableStateMapOf<String, ArtworkPalette>() }
    // Keyed on nothing: the set is the record of what has been *completed* (or
    // is in flight), so a new list identity must not forget it. Keyed on the
    // list it re-decoded the #1 cover on every reorder and never retried a URL
    // that had failed.
    val requested = remember { mutableSetOf<String>() }
    LaunchedEffect(urls) {
        val fresh = urls.filterNotNull().distinct().filter { it !in requested }
        if (fresh.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (url in fresh) {
                requested.add(url)
                val bitmap =
                    runCatching {
                        val request =
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(96)
                                .allowHardware(false)
                                .build()
                        (context.imageLoader.execute(request).image as? BitmapImage)?.bitmap
                    }.getOrNull()
                val palette = bitmap?.let { runCatching { ArtworkPalette.from(it) }.getOrNull() }
                if (palette == null) {
                    // A cover that never loaded must be retried when the list
                    // comes back; leaving it in `requested` made one network
                    // hiccup permanent for the life of the sheet.
                    requested.remove(url)
                    continue
                }
                withContext(Dispatchers.Main.immediate) { palettes[url] = palette }
                // Only the first ranked cover colours the card. Loading the
                // rest was a 96 px decode per cover (up to ten) whose palettes
                // were never read.
                return@withContext
            }
        }
    }
    return palettes
}

/** The first cover in rank order that has a palette — the card's colour source. */
fun Map<String, ArtworkPalette>.firstFor(urls: List<String?>): ArtworkPalette? =
    urls.asSequence().filterNotNull().firstNotNullOfOrNull { this[it] }

/** The palette a card uses for its motion theme; see [MotionPalette.of]. */
fun paletteFor(
    theme: ShareTheme,
    artwork: ArtworkPalette? = null,
): MotionPalette = MotionPalette.of(theme.palette, artwork)

/**
 * Draws [motion] over the whole area — one shaded rectangle per frame.
 *
 * [time] is read here, in draw, so the clock advances without recomposing
 * anything. The shader is compiled once and kept for the life of the layer.
 */
@Composable
fun ShareMotionLayer(
    motion: ShareMotion,
    palette: MotionPalette,
    time: MotionTime,
    modifier: Modifier = Modifier,
) {
    if (MotionShaders.isSupported) {
        ShadedLayer(motion, palette, time, modifier)
    } else {
        // Below Android 13 there is no AGSL: the card keeps its palette but
        // stands still rather than paying for a software rasteriser.
        Box(modifier.background(Brush.verticalGradient(listOf(palette.background, palette.deep))))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShadedLayer(
    motion: ShareMotion,
    palette: MotionPalette,
    time: MotionTime,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val renderer = remember(context) { MotionShaderRenderer(context.assets) }
    val displayScale = LocalMotionResolutionScale.current
    // The scene is shaded at a fraction of its box and the layer is scaled back
    // up. These are soft, low-frequency fields, so the upscale is invisible at
    // card size while the fragment work drops by 4x (waves: 84 ray steps per
    // pixel, which is what a full-resolution preview spends its frame budget
    // on).
    Canvas(modifier.sceneResolution(motion.renderScale(displayScale))) {
        val seconds = time.seconds()
        // A shader this driver rejects must cost the card its detail, not the
        // sheet: the draw is probed once and the palette fill covers it.
        val drawn =
            runCatching {
                var ok = false
                drawIntoCanvas { canvas ->
                    ok = renderer.draw(canvas.nativeCanvas, motion, palette, seconds, size.width.roundToInt(), size.height.roundToInt())
                }
                ok
            }.getOrDefault(false)
        if (!drawn) {
            drawRect(Brush.verticalGradient(listOf(palette.background, palette.deep)))
        }
    }
}

/**
 * How much of the display's resolution a scene is actually shaded at.
 *
 * Ray-marched fields and fluid filigree lose nothing at half resolution — they
 * are gradients either way. The four pattern scenes are the opposite: their
 * picture *is* the cell, pixel and glyph edge. They are shaded at the display's
 * scale (the sheet supplies its layer scale) rather than a fixed 1080x1920, so
 * a phone that shows the card at 55% pays for 30% of the fragments. The export
 * has its own, larger budget; this only controls the preview.
 */
private fun ShareMotion.renderScale(displayScale: Float): Float {
    val base =
        when (this) {
            ShareMotion.GRADIENT_WAVES, ShareMotion.LIQUID_ETHER -> 0.5f
            ShareMotion.SHAPE_WAVES -> 0.6f
            ShareMotion.GRADIENT_ASCII, ShareMotion.PIXEL_BLAST,
            ShareMotion.VERTICAL_BARS, ShareMotion.DITHERING -> 1f
        }
    return (base * displayScale).coerceIn(0.35f, 1f)
}

/**
 * Lays the content out at [scale] of its slot, then paints it back at full size
 * through a filtered layer.
 *
 * Cheaper than any offscreen pass of our own — the scale-up happens in the
 * compositor, and the shader's `uRes` uniforms are read from the layout size, so
 * a scene always knows the pixel grid it is actually being evaluated on.
 */
private fun Modifier.sceneResolution(scale: Float): Modifier =
    if (scale >= 1f) {
        this
    } else {
        sceneDownscale(scale)
    }

private fun Modifier.sceneDownscale(scale: Float): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = (constraints.maxWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (constraints.maxHeight * scale).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }.graphicsLayer {
        scaleX = 1f / scale
        scaleY = 1f / scale
        transformOrigin = TransformOrigin(0f, 0f)
    }
