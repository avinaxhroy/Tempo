package me.avinas.tempo.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.avinas.tempo.ui.components.share.MotionPalette
import me.avinas.tempo.ui.components.share.MOTION_START_SECONDS
import me.avinas.tempo.ui.components.share.MotionShaderRenderer
import me.avinas.tempo.ui.components.share.MotionShaders
import me.avinas.tempo.ui.components.share.ShareMotion
import me.avinas.tempo.utils.ShareVideoEncoder
import me.avinas.tempo.utils.ShareVideoFrameSource
import java.io.File

// CompositionLocal to indicate we're inside a CaptureWrapper
// Images should use software bitmaps (allowHardware = false) when true
val LocalInCaptureContext = compositionLocalOf { false }

/**
 * How many images inside a capture view are still decoding.
 *
 * A captured layer is drawn once and reused for every exported frame, so a
 * cover that is still loading when the layer is taken is missing from the whole
 * video. Waiting is unavoidable even for a card the user is looking at: a
 * capture view forces a software decode, which is a second, cold memory-cache
 * entry (see the `_sw` key in [CachedAsyncImage]), never the bitmap on screen.
 * [CachedAsyncImage] keeps the count; the exporter waits for it to read zero.
 */
class CaptureImageLoads {
    var pending by mutableIntStateOf(0)
        private set

    fun loading() {
        pending++
    }

    fun loaded() {
        if (pending > 0) pending--
    }
}

/** The [CaptureImageLoads] of the enclosing capture view, if there is one. */
val LocalCaptureImageLoads = staticCompositionLocalOf<CaptureImageLoads?> { null }


/**
 * True while drawing the *static* half of a motion export.
 *
 * A motion card is two layers: an opaque animated scene, then everything the
 * card itself draws on top of it. Rendering the whole card once per encoded
 * frame — recomposing a ComposeView 450 times and waiting for its draw pass —
 * is what made the old export slow and occasionally off by a frame. Here the
 * card paints its top layer once, with the scene suppressed (this local is the
 * flag that suppresses it), and the exporter composites that layer over a
 * freshly shaded frame.
 */
val LocalMotionOverlay = compositionLocalOf { false }

/**
 * Longest-edge cap, in pixels, for a captured bitmap.
 *
 * The capture is drawn on the UI thread (see [CaptureWrapper] for why it cannot be moved
 * off it), so the rendered surface is bounded here. A full-screen share canvas is typically
 * 1080x1920 px, i.e. 8.3 MB as ARGB_8888 and ~2.1 M pixels of software rasterisation on the
 * main thread. The cap is the story's own size, so a phone captures it 1:1; only a tablet
 * showing a larger card is scaled down, and even then never below 1080p.
 *
 * Compose replays its draw commands against the scaled [android.graphics.Canvas], so text,
 * shapes and gradients are re-rasterised crisply at the capped size. Only bitmap content
 * (album art) is resampled.
 */
private const val MAX_CAPTURE_LONG_EDGE_PX = 1920

/**
 * How long the exporter waits for the card's images before it takes the layer
 * anyway.
 *
 * A cover that is still decoding is missing from every frame, so the wait is
 * worth several seconds; a download that never finishes must not hold the sheet
 * for the whole render budget, so it is bounded and warned about instead.
 */
private const val IMAGE_WAIT_MS = 5_000L

/** Grid step for [hasPixels]: 34x60 samples across a 1080x1920 layer. */
private const val SAMPLE_STEP_PX = 32

/** How long an exported story stays in the cache before the next export sweeps it. */
private const val SHARE_FILE_TTL_MS = 60L * 60L * 1000L

class CaptureController {
    // A conflated channel, not a SharedFlow: a capture request is a command,
    // and a request emitted before the wrapper's collector starts must not be
    // lost (the old tryEmit returned false and left the button spinning until
    // the 8 s timeout).
    private val requests = Channel<Unit>(Channel.CONFLATED)
    val captureRequest = requests.receiveAsFlow()

    private val _capturedBitmap = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val capturedBitmap = _capturedBitmap.asSharedFlow()

    /**
     * Reported when the card could not be drawn at all — most often because the
     * hidden source was never laid out. The sheet resets its button on this
     * instead of waiting out the 8 s watchdog.
     */
    private val _error = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    fun capture() {
        requests.trySend(Unit)
    }

    fun onCaptured(bitmap: Bitmap) {
        // A SharedFlow with replay 0 accepts a value even when nobody is
        // collecting, so tryEmit alone does not prove anyone received it. Check
        // the subscribers first or the 8 MB capture leaks.
        if (_capturedBitmap.subscriptionCount.value == 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (!_capturedBitmap.tryEmit(bitmap) && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    internal fun fail(error: Throwable) {
        _error.tryEmit(error)
    }
}

@Composable
fun rememberCaptureController(): CaptureController {
    return remember { CaptureController() }
}

/**
 * Renders a motion card into an MP4.
 *
 * One export is one 15-second story ([ShareVideoEncoder.DURATION_SECONDS]).
 * Frames are not screenshots of a recomposing view: the exporter shades the
 * scene itself at `t = index / 30` and composites the card's static layer over
 * it, so the video is exactly the preview's timeline, at exactly 30 fps, with no
 * dependency on when Compose happens to draw.
 *
 * [VideoCaptureWrapper] supplies the static layer once; the encoder loop then
 * runs entirely on IO.
 */
@Composable
fun VideoCaptureWrapper(
    controller: VideoCaptureController,
    modifier: Modifier = Modifier,
    /** See [CaptureWrapper]'s `captureDensity`: it must be provided inside. */
    captureDensity: Density? = null,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    // Covers decode again for the software canvas, so the layer that goes onto
    // all 450 frames is waited for rather than raced (see [CaptureImageLoads]).
    val captureImages = remember { CaptureImageLoads() }

    LaunchedEffect(controller) {
        controller.request.collectLatest { exportGeneration ->
            if (controller.isCancelled(exportGeneration)) return@collectLatest
            try {
                // The card lives in an AndroidView-hosted ComposeView, so on the
                // frame export() is called it may not have been measured yet.
                // Waiting a few frames is what keeps a fast tap from failing the
                // export with "not attached" before the view exists.
                val overlay =
                    awaitDrawableOverlay(controller, captureImages)
                        ?: throw IllegalStateException("Card layer never drew")
                val file =
                    withContext(Dispatchers.IO) {
                        val scene = controller.scene
                        val palette = controller.palette
                        requireNotNull(scene) { "No scene selected for export" }
                        requireNotNull(palette) { "No scene palette for export" }
                        val compositor = withContext(Dispatchers.Main.immediate) {
                            MotionExportCompositor(controller.context, scene, palette)
                        }
                        try {
                            ShareVideoEncoder().render(
                                controller.outputFile(exportGeneration),
                                source = object : ShareVideoFrameSource {
                                    override fun drawFrame(frame: Long) {
                                        // Only the GPU draw needs the UI thread.
                                        // The readback (an 8.3 MB copy plus the
                                        // poll for a ready buffer) stays on IO: it
                                        // used to run inside the main-thread block
                                        // and was most of what froze the screen
                                        // for a render.
                                        runBlocking(Dispatchers.Main.immediate) {
                                            compositor.drawFrame(
                                                frame / ShareVideoEncoder.FRAME_RATE.toFloat(),
                                                overlay,
                                            )
                                        }
                                    }

                                    override fun readFrame(): Bitmap = compositor.readFrame()
                                },
                                onProgress = { controller.reportProgress(it) },
                                isCancelled = { controller.isCancelled(exportGeneration) },
                            )
                        } finally {
                            // A close failure must not strand the 8 MB static
                            // overlay, and must not mask the encoder's error.
                            runCatching { compositor.close() }
                            overlay.recycle()
                        }
                    }
                controller.succeed(exportGeneration, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                controller.cancelled(exportGeneration)
            } catch (e: Exception) {
                if (controller.isCancelled(exportGeneration)) {
                    controller.cancelled(exportGeneration)
                } else {
                    android.util.Log.e("VideoCaptureWrapper", "Video export failed", e)
                    controller.fail(exportGeneration, e)
                }
            } finally {
                // The latch is cleared here, not in cancel(): a cancelled job
                // keeps encoding until its next frame check, and a second
                // export() must not start a concurrent codec in the meantime.
                controller.finishExport(exportGeneration)
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.compose.ui.platform.ComposeView(ctx).apply {
                setContent {
                    val hostDensity = LocalDensity.current
                    val density = captureDensity ?: hostDensity
                    CompositionLocalProvider(
                        LocalInCaptureContext provides true,
                        LocalMotionOverlay provides true,
                        LocalCaptureImageLoads provides captureImages,
                        LocalDensity provides density,
                    ) {
                        currentContent()
                    }
                }
            }
        },
        update = { view ->
            controller.drawOverlay = { drawOverlay(view) }
        },
        modifier = modifier,
    )
}

/**
 * The card's static layer, taken only once it is actually complete.
 *
 * Two things can be missing from an early capture, and both stay missing for
 * all 450 frames because the layer is drawn once: the card itself, when the
 * ComposeView has been measured but its composition has not drawn yet, and the
 * cover art, which decodes a second time inside a capture view (see
 * [CaptureImageLoads]). So a layer is accepted only when it carries pixels and
 * nothing is still loading; the retries return null only when nothing was ever
 * drawn, which the caller turns into a failed export rather than a video with a
 * blank card.
 */
private suspend fun awaitDrawableOverlay(
    controller: VideoCaptureController,
    images: CaptureImageLoads,
): Bitmap? {
    val deadline = SystemClock.uptimeMillis() + IMAGE_WAIT_MS
    repeat(30) {
        while (images.pending > 0 && SystemClock.uptimeMillis() < deadline) {
            withFrameNanos { }
        }
        val bitmap = withContext(Dispatchers.Main.immediate) { controller.drawOverlay?.invoke() }
        if (bitmap != null && bitmap.hasPixels()) {
            if (images.pending > 0) {
                android.util.Log.w(
                    "VideoCaptureWrapper",
                    "Capturing the card with ${images.pending} image(s) still loading",
                )
            }
            return bitmap
        }
        bitmap?.recycle()
        withFrameNanos { }
    }
    return null
}

/**
 * Whether a captured layer carries anything at all.
 *
 * A layer drawn before its composition has run is fully transparent, and
 * compositing that onto every frame would ship a video with no card on it.
 * Sampled on a coarse grid: one `getPixel` per [SAMPLE_STEP_PX] pixels tells
 * "empty" from "drawn" in well under a millisecond.
 */
private fun Bitmap.hasPixels(): Boolean {
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (getPixel(x, y) != 0) return true
            x += SAMPLE_STEP_PX
        }
        y += SAMPLE_STEP_PX
    }
    return false
}

/**
 * The card's static layer at story size, on a transparent canvas.
 *
 * Must run on the main thread: the view is attached to a live, hardware
 * accelerated window, and drawing it from anywhere else races its own layout
 * pass (same reason as [CaptureWrapper]).
 */
private fun drawOverlay(view: android.view.View): Bitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    val width = ShareVideoEncoder.WIDTH_PX
    val height = ShareVideoEncoder.HEIGHT_PX
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(width / view.width.toFloat(), height / view.height.toFloat())
    view.draw(canvas)
    return bitmap
}

/**
 * Composites one exported frame: scene underneath, the card's static layer on
 * top.
 *
 * Every frame is shaded at the full 1080×1920 story size: pattern scenes need
 * their cell edges, and the soft fields gain from the extra resolution too. The
 * static overlay is drawn over each frame even though its pixels never change;
 * the cost is one GPU blit, and skipping it would require a second offscreen
 * composite for no visible gain.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class MotionExportCompositor(
    context: Context,
    private val scene: ShareMotion,
    private val palette: MotionPalette,
) : AutoCloseable {

    private val width = ShareVideoEncoder.WIDTH_PX
    private val height = ShareVideoEncoder.HEIGHT_PX
    private val renderer = MotionShaderRenderer(context.assets)
    private val reader = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        2,
        (HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN).toLong(),
    )
    private val surface: Surface = reader.surface
    private var frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dest = RectF(0f, 0f, width.toFloat(), height.toFloat())
    private var padded: ByteArray? = null

    /**
     * Render one frame into the ImageReader surface.
     *
     * `lockHardwareCanvas` gives the scene a real GPU canvas, the same Skia
     * pipeline the on-screen preview uses. Offscreen RenderNode/HardwareBuffer
     * renderers returned empty buffers on both the software and host-GPU
     * emulator, even though their draw calls reported success; a surface-backed
     * hardware canvas is the path that actually produced pixels. It must run on
     * a Looper thread (the exporter uses the main thread), because the surface
     * canvas posts its buffer through that thread's render pipeline.
     */
    fun drawFrame(seconds: Float, overlay: Bitmap) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(palette.background.toArgb())
            renderer.draw(canvas, scene, palette, seconds + MOTION_START_SECONDS, width, height)
            canvas.drawBitmap(overlay, null, dest, blit)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Takes the frame [drawFrame] just posted, off the UI thread.
     *
     * Split from [drawFrame] on purpose: the poll below sleeps while it waits,
     * and the readback copies 8.3 MB per frame. Neither belongs on the thread
     * that is also drawing, but the draw itself still does (see [drawFrame]).
     */
    fun readFrame(): Bitmap {
        var image: Image? = null
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (image == null && SystemClock.uptimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(2)
        }
        checkNotNull(image) { "Export surface produced no frame" }.use(::copyToBitmap)
        return frame
    }

    private fun copyToBitmap(image: Image) {
        val buffer = image.planes[0].buffer
        val rowStride = image.planes[0].rowStride
        val rowBytes = width * 4
        buffer.rewind()
        if (rowStride == rowBytes) {
            frame.copyPixelsFromBuffer(buffer)
            return
        }
        // Some GPUs pad each row. Pack it into the reused array; allocate
        // before the first get(), or the first padded frame dereferences null.
        val pad = padded ?: ByteArray(width * height * 4).also { padded = it }
        var src = 0
        var dst = 0
        repeat(height) {
            buffer.position(src)
            buffer.get(pad, dst, rowBytes)
            src += rowStride
            dst += rowBytes
        }
        frame.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pad))
    }

    override fun close() {
        surface.release()
        reader.close()
        frame.recycle()
    }
}

/** Drives [VideoCaptureWrapper]; one-shot, like [CaptureController]. */
class VideoCaptureController(
    internal val context: android.content.Context,
) {
    // Conflated command channel: the sheet can call export() in the same frame
    // the wrapper is first composed, before its collector has started. A
    // SharedFlow tryEmit would drop that request and leave isSharing stuck for
    // the whole 225 s watchdog.
    private val requests = Channel<Long>(Channel.CONFLATED)
    internal val request = requests.receiveAsFlow()

    /** Paints the card's static layer; installed by [VideoCaptureWrapper]. */
    internal var drawOverlay: (() -> Bitmap?)? = null

    /** Scene to shade under the static layer; set by the sheet before [export]. */
    var scene: ShareMotion? = null

    /** Palette the scene shades with; set together with [scene]. */
    var palette: MotionPalette? = null

    /** True while an export is in flight; guards against a second tap. */
    var isExporting: Boolean = false
        internal set

    /** Set by [cancel] and read by the encoder between frames. */
    @Volatile private var cancelRequested = false

    /**
     * Incremented for every export and every cancel. A job carries the value it
     * started with, so a render that outlives its sheet cannot deliver a video
     * into the next one.
     */
    @Volatile private var generation = 0L

    /**
     * The generation whose encode is still running, or 0 when none is.
     *
     * Separate from [generation] because a cancel bumps that one immediately
     * while the encoder thread lives on until its next frame check.
     */
    @Volatile private var activeGeneration = 0L

    private val _video = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val video = _video.asSharedFlow()

    /** Render progress, 0..1. Emitted per encoded frame so it never floods. */
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _error = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    /** Starts an export; false means nothing was queued. */
    fun export(): Boolean {
        if (isExporting) return false
        if (scene == null || palette == null || !MotionShaders.isSupported) return false
        cancelRequested = false
        generation++
        activeGeneration = generation
        isExporting = true
        _progress.value = 0f
        return requests.trySend(generation).isSuccess
    }

    /**
     * Stops the blocking encode at its next frame. Safe to call when nothing is
     * running: a dismissed sheet calls it unconditionally.
     */
    fun cancel() {
        generation++
        cancelRequested = true
        // Does NOT clear isExporting: the encode thread is still
        // inside MediaCodec until it reaches its next frame check, and dropping
        // the latch here let a second export() start a concurrent codec.
        _progress.value = 0f
    }

    /** Clears the in-flight latch once the encode job has actually ended. */
    internal fun finishExport(exportGeneration: Long) {
        if (exportGeneration != activeGeneration) return
        activeGeneration = 0L
        isExporting = false
    }

    internal fun isCancelled(exportGeneration: Long): Boolean =
        cancelRequested || exportGeneration != generation

    /**
     * A file per generation, not one shared `tempo_share.mp4`.
     *
     * A cancelled encode keeps running until its next frame check. If a new
     * export reused the same path, the old job's cleanup could delete the new
     * job's half-written file, or two muxers could open the same container.
     */
    internal fun outputFile(exportGeneration: Long): File {
        val dir = File(context.cacheDir, ShareVideoEncoder.OUTPUT_DIR).apply { mkdirs() }
        val file = File(dir, "tempo_share_$exportGeneration.mp4")
        // Sweep by age, never by "is it the previous one": the last story can
        // still be open in the share sheet while the user starts another export,
        // and deleting it there broke the pending share. An hour is far longer
        // than any chooser session, and still clears a render killed with the
        // process.
        dir.listFiles { entry ->
            entry.isFile &&
                entry.name.startsWith("tempo_share_") &&
                entry.name.endsWith(".mp4") &&
                entry.name != file.name &&
                System.currentTimeMillis() - entry.lastModified() > SHARE_FILE_TTL_MS
        }?.forEach { previous -> runCatching { previous.delete() } }
        return file
    }

    internal fun succeed(exportGeneration: Long, file: File) {
        if (exportGeneration != generation) {
            file.delete()
            return
        }
        cancelRequested = false
        // Same trap as onCaptured: a replay-0 SharedFlow accepts the emit with
        // no subscriber, so the result must be checked for an audience first.
        if (_video.subscriptionCount.value == 0) {
            // The sheet was dismissed between the last frame and this callback;
            // nobody will consume the result, and a multi-megabyte MP4 would
            // sit in the cache until the OS evicted it.
            file.delete()
            return
        }
        if (!_video.tryEmit(file)) file.delete()
    }

    internal fun fail(exportGeneration: Long, t: Throwable) {
        if (exportGeneration != generation) return
        cancelRequested = false
        _error.tryEmit(t)
    }

    /** A cancelled job reports nothing: there is no UI left to receive it. */
    internal fun cancelled(exportGeneration: Long) {
        if (exportGeneration != generation) return
        cancelRequested = false
        _progress.value = 0f
    }

    internal fun reportProgress(value: Float) {
        _progress.value = value.coerceIn(0f, 1f)
    }
}

@Composable
fun rememberVideoCaptureController(): VideoCaptureController {
    val context = LocalContext.current
    return remember(context) { VideoCaptureController(context) }
}

/**
 * Waits for the hidden source to be measured, or gives up after ~half a second.
 *
 * `withFrameNanos` (not a delay) so the wait ends on the very frame the card is
 * laid out rather than on an arbitrary timer.
 */
private suspend fun awaitMeasuredView(view: () -> android.view.View?): android.view.View? {
    repeat(30) {
        val current = view()
        if (current != null && current.width > 0 && current.height > 0) return current
        withFrameNanos { }
    }
    return view()
}

@Composable
fun CaptureWrapper(
    controller: CaptureController,
    modifier: Modifier = Modifier,
    /**
     * Density for the captured card, when the caller wants a fixed one.
     *
     * It has to be provided *inside* this ComposeView, not around the call: a
     * ComposeView hosted in an AndroidView starts its own composition and
     * re-provides the device's density from its AndroidComposeView, so an
     * outer `LocalDensity provides` never reaches the card that gets captured.
     */
    captureDensity: Density? = null,
    content: @Composable () -> Unit
) {
    var capturedView: android.view.View? by remember { mutableStateOf(null) }
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(controller) {
        controller.captureRequest.collectLatest {
            // The source is a ComposeView inside an AndroidView and may not be
            // measured yet on the frame the button is tapped. Retry across a few
            // frames instead of only logging, which left the button spinning
            // until the 8 s watchdog.
            val view = awaitMeasuredView { capturedView }
            if (view == null) {
                controller.fail(IllegalStateException("Card view is not attached"))
                return@collectLatest
            }
            try {
                run {
                    if (view.width > 0 && view.height > 0) {
                        // Bound the captured surface (see MAX_CAPTURE_LONG_EDGE_PX).
                        val longestEdge = maxOf(view.width, view.height)
                        val scale = if (longestEdge > MAX_CAPTURE_LONG_EDGE_PX) {
                            MAX_CAPTURE_LONG_EDGE_PX.toFloat() / longestEdge
                        } else {
                            1f
                        }
                        val bitmapWidth = (view.width * scale).toInt().coerceAtLeast(1)
                        val bitmapHeight = (view.height * scale).toInt().coerceAtLeast(1)

                        // Create bitmap and canvas for capture
                        val bitmap = Bitmap.createBitmap(
                            bitmapWidth,
                            bitmapHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        if (scale != 1f) {
                            // Scales the drawn output without changing the view's own layout,
                            // so what is captured still matches the on-screen composition.
                            canvas.scale(scale, scale)
                        }

                        // Draw the view hierarchy to the canvas.
                        // Must stay on the main thread: this ComposeView is attached to a live,
                        // hardware-accelerated window, and AndroidComposeView.dispatchDraw() calls
                        // measureAndLayout() and records into RenderNodes. Drawing it from a
                        // background thread races the UI thread's own layout/draw pass.
                        // Safe: LocalInCaptureContext forces software bitmap allocation
                        view.draw(canvas)

                        controller.onCaptured(bitmap)
                    } else {
                        android.util.Log.e("CaptureWrapper", "View has 0 dimensions: w=${view.width}, h=${view.height}")
                        controller.fail(IllegalStateException("Card has no size"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("CaptureWrapper", "Error capturing view", e)
                controller.fail(e)
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.compose.ui.platform.ComposeView(ctx).apply {
                setContent {
                    val hostDensity = LocalDensity.current
                    val density = captureDensity ?: hostDensity
                    // Provide capture context to all children
                    // This signals that images should use software bitmaps
                    CompositionLocalProvider(
                        LocalInCaptureContext provides true,
                        LocalDensity provides density,
                    ) {
                        currentContent()
                    }
                }
            }
        },
        update = { view ->
            capturedView = view
        },
        modifier = modifier
    )
}
