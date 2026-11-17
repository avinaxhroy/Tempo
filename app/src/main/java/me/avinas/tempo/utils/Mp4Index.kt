package me.avinas.tempo.utils

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the box structure of a finished MP4.
 *
 * The encoder used to ask `MediaMetadataRetriever` whether the file it had just
 * written was playable. That is the wrong instrument: it answers "can this
 * device's player open it", which folds in codec support, media scanning and
 * whatever the platform image happens to do — and on the API 37 emulator it said
 * no for files that were structurally complete, failing every motion export with
 * "Video was written without a readable index".
 *
 * The question that actually matters is narrower: did the muxer write its index
 * (`moov`) next to the media data (`mdat`), and does that index declare the story
 * length? Both are readable from the bytes, with no player involved.
 */
internal object Mp4Index {

    private const val HEADER_BYTES = 8L

    /** One top-level box: its type, where it starts, how long it is. */
    data class Box(val type: String, val offset: Long, val size: Long, val headerBytes: Long)

    /**
     * What was found in the file. A null [durationMs] means the index exists but
     * did not declare a length, which is as unusable as a missing one.
     */
    data class Facts(
        val boxes: List<Box>,
        val durationMs: Long?,
    ) {
        /** The muxer's index is present. */
        val hasIndex: Boolean get() = boxes.any { it.type == "moov" }

        /** There is media to index; an empty mdat means every sample was lost. */
        val hasMedia: Boolean
            get() = boxes.any { it.type == "mdat" && it.size > it.headerBytes }
    }

    /**
     * Walks the file's top-level boxes and reads the movie-header duration.
     *
     * Returns null when the file cannot be walked at all — a truncated header, or
     * nothing that looks like an MP4. A partially walked file still returns what
     * it found, so a caller can log why it was rejected instead of just "no".
     */
    fun inspect(file: File): Facts? {
        if (!file.isFile || file.length() < 2 * HEADER_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val boxes = ArrayList<Box>()
                var cursor = 0L
                while (cursor + HEADER_BYTES <= length && boxes.size < MAX_BOXES) {
                    raf.seek(cursor)
                    var size = raf.readInt().toLong() and 0xFFFF_FFFFL
                    val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
                    var headerBytes = HEADER_BYTES
                    when {
                        // 64-bit size follows a bare 8-byte header.
                        size == 1L -> {
                            if (cursor + 2 * HEADER_BYTES > length) break
                            size = raf.readLong()
                            headerBytes = 2 * HEADER_BYTES
                        }
                        // Size 0 means "to the end of the file".
                        size == 0L -> size = length - cursor
                    }
                    if (size < headerBytes || cursor + size > length) {
                        // Record the damaged box, then stop: everything after a
                        // bad length is unreadable by construction.
                        boxes.add(Box(type, cursor, size, headerBytes))
                        break
                    }
                    boxes.add(Box(type, cursor, size, headerBytes))
                    cursor += size
                }
                Facts(boxes = boxes, durationMs = durationMs(raf, boxes))
            }
        }.getOrNull()
    }

    /** Duration declared by the `mvhd` inside `moov`, in milliseconds. */
    private fun durationMs(raf: RandomAccessFile, boxes: List<Box>): Long? {
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return null
        var cursor = moov.offset + moov.headerBytes
        // A box may claim more bytes than the file holds (a truncated download),
        // so the walk stops at whichever ends first.
        val end = minOf(moov.offset + moov.size, raf.length())
        while (cursor + HEADER_BYTES <= end) {
            raf.seek(cursor)
            var size = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
            if (size == 1L) size = raf.readLong()
            if (type == "mvhd") {
                val version = raf.readByte().toInt() and 0xFF
                raf.skipBytes(3) // flags
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    raf.skipBytes(16) // creation + modification, 64-bit each
                    timescale = raf.readInt().toLong() and 0xFFFF_FFFFL
                    duration = raf.readLong()
                } else {
                    raf.skipBytes(8) // creation + modification, 32-bit each
                    timescale = raf.readInt().toLong() and 0xFFFF_FFFFL
                    duration = raf.readInt().toLong() and 0xFFFF_FFFFL
                }
                if (timescale <= 0L) return null
                return duration * 1000L / timescale
            }
            if (size < HEADER_BYTES) return null
            cursor += size
        }
        return null
    }

    /** A real MP4 has a handful of top-level boxes; this stops a loop on garbage. */
    private const val MAX_BOXES = 64
}
