package me.avinas.tempo.utils

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.Image
import android.media.MediaMuxer
import androidx.annotation.WorkerThread
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.stream.IntStream

/**
 * Frame source for [ShareVideoEncoder]: the finished card for a frame index,
 * drawn at exactly the encoder's width and height.
 *
 * Two calls rather than one `drawFrame(index): Bitmap`, so the encoder can keep
 * the GPU and the CPU busy at the same time: it starts frame `i + 1` and only
 * then converts the pixels of frame `i`. A single call put the shade and the
 * conversion in lockstep and paid both in series.
 */
interface ShareVideoFrameSource {
    /**
     * Starts rendering [frame], in `0 until [ShareVideoEncoder.frameCount]`.
     *
     * Called once per frame, in order, and returns once the frame is queued for
     * drawing rather than drawn; [readFrame] waits for the pixels.
     */
    fun drawFrame(frame: Long)

    /**
     * Pixels of the frame [drawFrame] started last, blocking until they exist.
     *
     * The bitmap is reused between calls and owned by the source: the encoder
     * only reads it, and only before its next [drawFrame].
     */
    fun readFrame(): Bitmap
}

/** The length of one share story, in seconds. One source of truth for the
 *  encoder, the scene clock and the copy. */
const val SHARE_STORY_SECONDS = 15

/**
 * Encodes a card to a silent, 15-second social story MP4.
 *
 * Stories autoplay muted, so no audio track is written: the file is one H.264
 * track that Instagram, WhatsApp and the gallery all accept. The video layout is
 * fixed by the story share — 1080×1920, 30 fps, [SHARE_STORY_SECONDS] seconds.
 *
 * [source] draws one finished frame (moving background and static overlay
 * already composited) at the encoder's size and
 * keeps ownership of whatever it returns — the exporter may hand back the same
 * bitmap for every frame, so the encoder reads it and never releases it.
 *
 * Encoding is surface-free and colour-plane-aware: it writes into the codec's
 * `inputImage` planes using their reported strides, because most encoders hand
 * back interleaved NV12 chroma. Writing that as if it were planar swaps the
 * colour channels and shifts every card blue — the classic green-tint bug.
 */
class ShareVideoEncoder(
    private val widthPx: Int = WIDTH_PX,
    private val heightPx: Int = HEIGHT_PX,
    private val frameRate: Int = FRAME_RATE,
    private val durationSeconds: Int = DURATION_SECONDS,
    private val bitRate: Int = BIT_RATE,
) {
    /** Total frames in the file. */
    val frameCount: Long get() = frameRate.toLong() * durationSeconds

    /** Playback length of the file, in seconds. */
    val durationTotalSeconds: Int get() = durationSeconds

    /**
     * Wall-clock ceiling for rendering this file on a slow phone.
     *
     * 15 seconds per second of story keeps a mid-range device with an
     * unexpectedly absent GPU inside the budget, and still fails in minutes
     * rather than never. The caller also cancels this job when the sheet closes.
     */
    val renderTimeoutMs: Long get() = durationTotalSeconds * 15_000L

    /**
     * Whether the device lists a story-size AVC encoder. A caller uses this to
     * keep motion export out of the UI instead of failing after the tap; the
     * encode path still has to cope, because "listed" and "usable" are not the
     * same claim on every device.
     */
    fun isSupported(): Boolean = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(avcFormat()) != null }.getOrDefault(false)

    /**
     * Encodes every frame to [outputFile] and returns it.
     *
     * Synchronous and off-UI-thread: call from a background
     * dispatcher. Presentation timestamps come from the frame index, so the
     * story is exactly [SHARE_STORY_SECONDS] seconds no matter how long a frame
     * takes to draw.
     */
    @WorkerThread
    fun encode(
        outputFile: File,
        source: ShareVideoFrameSource,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): File {
        val format = avcFormat()
        // A previous timeout may have left a partial file at this path; the
        // muxer would otherwise append to a container with no index.
        outputFile.delete()

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // A full disk makes the muxer's constructor throw while the codec above
        // already holds native buffers: without this, each failed attempt leaks a
        // codec instance, and the device eventually refuses to create another.
        val muxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Throwable) {
            runCatching { codec.release() }
            throw e
        }
        var trackIndex = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        // Give up only if the codec itself stops answering. The caller draws a
        // full card per frame on this thread, so a wall-clock deadline on the
        // whole loop would kill healthy renders on slow phones; a codec that
        // takes this long to accept input or hand back output is stuck.
        val stallLimitUs = STALL_TIMEOUT_US
        var framesStarted = 0L
        var framesQueued = 0L
        var framesEncoded = 0L
        var eosQueued = false
        // A codec can keep accepting input while returning no output at all; the
        // per-call timeout alone then looks healthy forever. Track the last time
        // the encoded-frame count actually moved, so a wedged writer fails in
        // twenty seconds instead of holding the sheet for the full render budget.
        var lastEncoded = -1L
        var lastEncodedAtNs = System.nanoTime()
        val stagnationLimitNs = STALL_TIMEOUT_US * 1_000L
        val frameBytes = widthPx * heightPx * 3 / 2
        // Converts in horizontal bands across every core, avoiding both the full
        // 8.3 MB ARGB copy this loop used to hold and a single-threaded pass.
        // A 64-row band is 276 KB and stays in cache across the YUV writes.
        val converter = YuvFrameConverter(widthPx, heightPx)

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // Frame 0 is started before the loop so the first iteration has
            // pixels to convert; every later frame is started one iteration
            // ahead of its own conversion (see the loop body).
            source.drawFrame(0)
            framesStarted = 1

            while (framesEncoded < frameCount) {
                checkActive(isCancelled)
                if (framesQueued <= frameCount) {
                    val inputIndex = codec.dequeueInputBuffer(stallLimitUs)
                    if (inputIndex >= 0) {
                        val frameToConvert = minOf(framesQueued, frameCount - 1)
                        // No flexible image means this codec wants raw buffers
                        // instead; an empty buffer would be encoded as a black
                        // frame and shipped as a broken story.
                        val image = codec.getInputImage(inputIndex)
                            ?: error("Encoder input image unavailable at frame $frameToConvert")
                        // Read, start the next frame, convert: the GPU shades
                        // frame k + 1 while this thread converts frame k, so the
                        // shade stops adding to a frame's cost. The read has to
                        // come first — the source takes the reader's newest
                        // buffer, so posting before reading would hand back the
                        // next frame and drop this one.
                        val bitmap = source.readFrame()
                        if (framesStarted <= frameCount) {
                            // Held, never recycled: the source hands back one
                            // reused bitmap per frame (see MotionExportCompositor),
                            // so recycling it here would leave every later draw
                            // pointing at dead pixels.
                            source.drawFrame(minOf(framesStarted, frameCount - 1))
                            framesStarted++
                        }
                        converter.convert(image, bitmap)
                        // One frame is handed to the codec beyond the story, so
                        // an encoder that swallows an input still fills all 450
                        // samples; the surplus output is dropped by the
                        // framesEncoded cap when it is drained below.
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            frameBytes,
                            framesQueued * 1_000_000L / frameRate,
                            0,
                        )
                        framesQueued++
                    }
                } else if (!eosQueued) {
                    // Encoders hold the final frame until they see end-of-stream,
                    // and the API 37 software encoder still dropped the last real
                    // frame when EOS followed it directly. Queue one duplicate
                    // tail frame first: the codec then has a spare frame to hold
                    // back and all 450 story frames reach the muxer. The extra
                    // output is skipped by the framesEncoded cap below.
                    val inputIndex = codec.dequeueInputBuffer(stallLimitUs)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            (frameCount + 2) * 1_000_000L / frameRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        eosQueued = true
                    }
                }

                checkActive(isCancelled)
                when (val outputIndex = codec.dequeueOutputBuffer(info, stallLimitUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    // -3 is INFO_OUTPUT_BUFFERS_CHANGED, the codec swapping its
                    // buffer array: a no-op since API 21. Spelled as a literal
                    // because the constant is deprecated and referencing it fails
                    // the warning-free build — and because it used to be written
                    // as 1, which is a real output index, so every buffer the
                    // codec handed out as index 1 was dropped without ever being
                    // released, shrinking its output pool.
                    -3 -> Unit
                    else -> {
                        if (outputIndex < 0) continue
                        val buffer = codec.getOutputBuffer(outputIndex)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig && muxerStarted &&
                            framesEncoded < frameCount
                        ) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            // The muxer throws away leading samples until it sees
                            // one flagged as a sync sample, then starts the track
                            // there: "Video skip non-key frame", twenty-nine of
                            // them, and the story began at the encoder's next
                            // flagged keyframe a second in — 14.03 s of 15.
                            // The Qualcomm encoder leaves that flag clear on the
                            // IDR it codes first, which is why the story kept
                            // losing its first second no matter how the frames
                            // were submitted. The bitstream cannot lie about a
                            // keyframe, so it decides, and the muxer's seek table
                            // ends up describing the file that was written.
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) {
                                val types = nalTypes(buffer, info.offset, info.size)
                                if (IDR_SLICE in types) {
                                    info.flags = info.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else if (framesEncoded == 0L) {
                                    android.util.Log.w(
                                        "ShareVideoEncoder",
                                        "First sample has no IDR slice (NAL types $types)",
                                    )
                                }
                            }
                            muxer.writeSampleData(trackIndex, buffer, info)
                            framesEncoded++
                            // 450 frames at 30 fps: reporting every third frame is
                            // still a smooth ring and a third of the StateFlow
                            // writes across the IO/main boundary.
                            if (framesEncoded % PROGRESS_EVERY == 0L || framesEncoded == frameCount) {
                                onProgress?.invoke(framesEncoded.toFloat() / frameCount)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                if (framesEncoded != lastEncoded) {
                    lastEncoded = framesEncoded
                    lastEncodedAtNs = System.nanoTime()
                } else if (System.nanoTime() - lastEncodedAtNs > stagnationLimitNs) {
                    error("Encoder stopped after $framesEncoded of $frameCount frames")
                }
            }
        } finally {
            // The muxer is closed first, on purpose. `MediaCodec.stop()` blocks
            // on some software encoders (reproduced on the API 37 emulator,
            // where it never returns after the last frame), and anything behind
            // it in this block — including the muxer's index write — would never
            // run, leaving a multi-megabyte MP4 no player can open.
            //
            // Not swallowed: a failed stop leaves the MP4 without its index, and
            // that file would be shared as "a video" that no player can open.
            // Throwing here deletes it (see render) and reports the failure.
            // A cancelled render is deleted, so it does not need an index. Stop
            // is the call that can block indefinitely on the software encoder;
            // skipping it is what lets a cancelled job actually exit.
            try {
                if (muxerStarted && isCancelled?.invoke() != true) muxer.stop()
            } finally {
                // stop() can throw (or be skipped for a cancelled render); the
                // codec and muxer still have to be released. Without this nested
                // finally a failed stop leaked both native objects.
                runCatching { muxer.release() }
                // release() also tears the codec down; stop() is only the graceful
                // half of it and is the half that hangs.
                runCatching { codec.release() }
            }
        }
        // A cancelled render skips the muxer stop, so its file has no index by
        // design; it is deleted by render() and must not be reported as broken.
        checkActive(isCancelled)
        // A truncated file would still be happily shared as a broken video, so
        // anything short of every frame rejects the export outright.
        check(framesEncoded >= frameCount) { "Encoded $framesEncoded of $frameCount frames" }
        // Every frame reaching the muxer is not the same as a playable file, so
        // the container is checked rather than trusted.
        verifyIndexed(outputFile)
        return outputFile
    }

    /**
     * Encodes, and deletes the half-written file if the render fails.
     *
     * Sharing a corrupted two-second story is worse than telling the user the
     * render failed, so the failure path clears the cache entry rather than
     * leaving it for the next share to pick up.
     */
    @WorkerThread
    fun render(
        outputFile: File,
        source: ShareVideoFrameSource,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): File =
        try {
            encode(outputFile, source, onProgress, isCancelled)
        } catch (e: Throwable) {
            runCatching { outputFile.delete() }
            throw e
        }

    /**
     * Rejects a file whose container cannot be read back.
     *
     * The previous check asked `MediaMetadataRetriever` for a duration. That
     * measures the device's player, not the file: it folds in codec support and
     * media scanning, so a structurally complete export could be rejected — which
     * is what happened on the API 37 emulator, where every motion export failed
     * with "Video was written without a readable index" and the file it had just
     * written was fine.
     *
     * The muxer's contract is narrower and checkable from the bytes: the `moov`
     * index exists, the `mdat` holding the samples is not empty, and the index
     * declares the story's length. [Mp4Index] reads exactly that, with no player
     * involved, so a failure here means the file really is broken.
     */
    private fun verifyIndexed(file: File) {
        val facts = Mp4Index.inspect(file)
        val expectedMs = frameCount * 1000L / frameRate
        val detail = when {
            facts == null -> "unreadable container"
            !facts.hasIndex -> "no moov index (boxes: ${facts.boxes.map { it.type }})"
            !facts.hasMedia -> "no media data"
            facts.durationMs == null -> "index declares no duration"
            facts.durationMs < expectedMs - DURATION_TOLERANCE_MS ->
                "index declares ${facts.durationMs}ms, story is ${expectedMs}ms"
            else -> null
        }
        if (detail != null) {
            android.util.Log.e("ShareVideoEncoder", "Rejected export: $detail")
            error("Video was written without a readable index: $detail")
        }
    }

    /** Story-size AVC format; also what [isSupported] probes for. */
    private fun avcFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, widthPx, heightPx).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Baseline AVC has no B-frames, so decode order equals
            // presentation order and no frame is held back across the story.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }

    /**
     * Copies an ARGB bitmap into the codec's input planes, one horizontal band
     * at a time.
     *
     * Luma is taken at full resolution; chroma is sampled from every second
     * pixel of every second row, which is what 4:2:0 means. Each plane is
     * addressed through its own [Image.Plane.rowStride] and pixel stride, because
     * encoders disagree on the layout: interleaved NV12 (pixel stride 2, U and V
     * in adjacent bytes) and padded planar I420 (pixel stride 1, each plane its
     * own row stride).
     *
     * The frame is cut into slices and the slices run on the common pool: 2.07 M
     * pixels of per-pixel arithmetic is the one part of an export that scales
     * with cores, and every row of it is independent — luma and its own chroma
     * pair included — so slices never touch the same plane byte.
     *
     * Rows are built in a reusable [ByteArray] and written with one bulk
     * `put(row)`. The previous implementation made an absolute `put(index, byte)`
     * call for every luma byte and every chroma byte — 3.1 million bounds-checked
     * calls per frame at story size — while also holding a full 8.3 MB ARGB
     * scratch copy.
     */
    private class YuvFrameConverter(
        private val width: Int,
        private val height: Int,
    ) {
        /**
         * How many slices one frame is cut into.
         *
         * Taken from the device rather than fixed: the common pool runs the slices
         * and bounds concurrency, so two per core costs nothing and lets work
         * stealing even out a big.LITTLE cluster where one core runs at half its
         * neighbour's clock. A single-core device gets one slice and skips the
         * parallel path.
         */
        private val slices =
            (Runtime.getRuntime().availableProcessors() * 2)
                .coerceIn(1, maxOf(1, height / SLICE_MIN_ROWS))

        private val rowsPerSlice = (height + slices - 1) / slices

        /** Per-thread scratch: the only mutable state here belongs to one slice. */
        // Overriding initialValue() rather than ThreadLocal.withInitial keeps the
        // element type non-null; the factory's Java generics come back nullable.
        private val scratch = object : ThreadLocal<Scratch>() {
            override fun initialValue() = Scratch(minOf(YUV_BAND_ROWS, rowsPerSlice))
        }

        private inner class Scratch(val rows: Int) {
            val band = IntArray(rows * width)
            val yRow = ByteArray(width)
            val uRow = ByteArray(width / 2)
            val vRow = ByteArray(width / 2)
            val uvInterleaved = ByteArray((width / 2) * 2)
        }

        /** The codec image's planes and strides, read once and shared by the slices. */
        private class Planes(image: Image) {
            val y = image.planes[0].buffer
            val u = image.planes[1].buffer
            val v = image.planes[2].buffer
            val yRowStride = image.planes[0].rowStride
            val uRowStride = image.planes[1].rowStride
            val vRowStride = image.planes[2].rowStride
            val uvPixelStride = image.planes[1].pixelStride
            val vPixelStride = image.planes[2].pixelStride
        }

        fun convert(image: Image, bitmap: Bitmap) {
            val planes = Planes(image)
            if (slices == 1) {
                convertRows(planes, bitmap, 0, height)
                return
            }
            IntStream.range(0, slices).parallel().forEach { slice ->
                val from = slice * rowsPerSlice
                if (from < height) convertRows(planes, bitmap, from, minOf(height, from + rowsPerSlice))
            }
        }

        private fun convertRows(planes: Planes, bitmap: Bitmap, fromRow: Int, toRow: Int) {
            // ThreadLocal.get() is typed nullable in the platform annotations even though
            // initialValue() above never returns null.
            val s = requireNotNull(scratch.get())
            // A ByteBuffer's position is shared state, so every slice writes
            // through its own copies. `duplicate()` shares the bytes and the
            // plane-sized limit, which the NV12 write below depends on.
            val yBuffer = planes.y.duplicate()
            val uBuffer = planes.u.duplicate()
            val vBuffer = planes.v.duplicate()
            val yRowStride = planes.yRowStride
            val uRowStride = planes.uRowStride
            val vRowStride = planes.vRowStride
            val uvPixelStride = planes.uvPixelStride
            val vPixelStride = planes.vPixelStride

            var row = fromRow
            while (row < toRow) {
                val rows = minOf(s.rows, toRow - row)
                bitmap.getPixels(s.band, 0, width, 0, row, width, rows)
                for (r in 0 until rows) {
                    val y = row + r
                    val base = r * width
                    if (y and 1 == 0) {
                        // Even rows carry the whole 4:2:0 sample: luma for every
                        // pixel and one U/V pair for each even column. Computing
                        // both here keeps one RGB extraction per pixel instead of
                        // walking the row a second time and extracting it again.
                        for (col in 0 until width) {
                            val c = s.band[base + col]
                            val red = (c shr 16) and 0xFF
                            val green = (c shr 8) and 0xFF
                            val blue = c and 0xFF
                            s.yRow[col] =
                                (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).toByte()
                            if (col and 1 == 0) {
                                val i = col shr 1
                                val u =
                                    (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128).toByte()
                                val v =
                                    (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128).toByte()
                                if (uvPixelStride == 2) {
                                    s.uvInterleaved[i * 2] = u
                                    s.uvInterleaved[i * 2 + 1] = v
                                } else {
                                    s.uRow[i] = u
                                    s.vRow[i] = v
                                }
                            }
                        }
                    } else {
                        for (col in 0 until width) {
                            val c = s.band[base + col]
                            val red = (c shr 16) and 0xFF
                            val green = (c shr 8) and 0xFF
                            val blue = c and 0xFF
                            s.yRow[col] =
                                (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).toByte()
                        }
                    }
                    yBuffer.position(y * yRowStride)
                    yBuffer.put(s.yRow)

                    if (y and 1 != 0) continue
                    val uvRowStart = (y / 2) * uRowStride
                    if (uvPixelStride == 2) {
                        // NV12: U and V share one buffer, interleaved, so the
                        // pair row goes through U's buffer and leaves V's offset
                        // intact. Every byte of it but the last: `Image.Plane`
                        // sizes a slice down to that plane's own final sample,
                        // which makes U's buffer end on the last U — one byte
                        // short of the final pair. Writing the full row there
                        // overflows by that byte on the bottom chroma row and
                        // failed the whole export on the Qualcomm encoder while
                        // the planar emulator path never saw it. The last V is
                        // written through V's buffer, at V's own strides.
                        uBuffer.position(uvRowStart)
                        uBuffer.put(s.uvInterleaved, 0, width - 1)
                        vBuffer.position((y / 2) * vRowStride + (width / 2 - 1) * vPixelStride)
                        vBuffer.put(s.uvInterleaved, width - 1, 1)
                    } else {
                        uBuffer.position(uvRowStart)
                        uBuffer.put(s.uRow)
                        vBuffer.position((y / 2) * vRowStride)
                        vBuffer.put(s.vRow)
                    }
                }
                row += rows
            }
        }
    }

    /**
     * NAL unit types in one coded AVC sample, in bitstream order.
     *
     * AVC forbids a `00 00 01` sequence inside a NAL payload — emulation
     * prevention bytes exist precisely to keep start codes unique — so scanning
     * for start codes reads the sample's true structure. Encoders do not agree
     * on setting [MediaCodec.BufferInfo]'s key-frame flag, and a missing flag on
     * a coded IDR truncates the story at the muxer (see the write above), so the
     * bytes decide here rather than the flag.
     */
    internal fun nalTypes(buffer: ByteBuffer, offset: Int, size: Int): List<Int> {
        val types = ArrayList<Int>(2)
        val end = offset + size
        var i = offset
        // Four bytes are read per hit: the three-byte start code plus its header.
        while (i + 3 < end) {
            if (buffer.get(i) == 0.toByte() && buffer.get(i + 1) == 0.toByte() &&
                buffer.get(i + 2) == 1.toByte()
            ) {
                types.add(buffer.get(i + 3).toInt() and NAL_TYPE_MASK)
                i += 3
            } else {
                i++
            }
        }
        return types
    }

    private fun checkActive(isCancelled: (() -> Boolean)?) {
        if (isCancelled?.invoke() == true) {
            throw CancellationException("Share export cancelled")
        }
    }

    companion object {
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 1920
        const val FRAME_RATE = 30
        const val DURATION_SECONDS = SHARE_STORY_SECONDS

        /** 12 Mbps: gradient-heavy motion bands badly below this. */
        const val BIT_RATE = 12_000_000

        /** Cache subdirectory the encoder writes into (see res/xml/file_paths). */
        const val OUTPUT_DIR = "videos"

        /**
         * How long the codec may leave a dequeue unanswered before the render
         * is treated as hung. Must stay well below the caller's renderTimeoutMs
         * so the encoder, not the watchdog, reports the failure first.
         */
        private const val STALL_TIMEOUT_US = 20_000_000L

        /** Rows converted per [Bitmap.getPixels] call; even, and a cache fit. */
        private const val YUV_BAND_ROWS = 64

        /** Rows below which a slice is not worth handing to another core. */
        private const val SLICE_MIN_ROWS = 8

        /** Emit progress every third encoded frame. */
        private const val PROGRESS_EVERY = 3L

        /** Low five bits of a NAL unit's header byte hold its type. */
        private const val NAL_TYPE_MASK = 0x1F

        /** NAL type of an IDR slice: the only AVC picture a decoder can start on. */
        private const val IDR_SLICE = 5

        /**
         * How far the index's declared length may sit below the intended story
         * before the file counts as truncated.
         *
         * A muxer may declare the last frame's presentation time rather than the
         * story's end, which is one frame short; anything larger is a real gap.
         */
        private const val DURATION_TOLERANCE_MS = 250L

        /** False when the device has no story-size AVC encoder. */
        fun isSupported(): Boolean = encoderSupport

        /**
         * [isSupported]'s answer, computed once.
         *
         * Enumerating the codec list costs tens of milliseconds on some devices,
         * and this is read from composition (theme chips, the share button).
         * Computed lazily on first use, so a device that never shares never pays
         * for it, and cached because the hardware cannot change at runtime.
         */
        private val encoderSupport: Boolean by lazy { ShareVideoEncoder().isSupported() }
    }
}
