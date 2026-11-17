package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The encoder's post-write check used to ask a media player whether the file it
 * had just produced was playable, which rejected complete exports on devices
 * whose player could not open them. It now reads the container itself, so this
 * covers the byte layout it depends on.
 */
class Mp4IndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    /** An `mvhd` declaring [durationMs] at a 1000 timescale. */
    private fun mvhd(durationMs: Int, version: Int = 0): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(if (version == 1) 112 else 100)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.put(version.toByte())
        buffer.put(ByteArray(3)) // flags
        if (version == 1) {
            buffer.putLong(0L) // creation
            buffer.putLong(0L) // modification
            buffer.putInt(1000) // timescale
            buffer.putLong(durationMs.toLong())
        } else {
            buffer.putInt(0) // creation
            buffer.putInt(0) // modification
            buffer.putInt(1000) // timescale
            buffer.putInt(durationMs)
        }
        return buffer.array()
    }

    private fun file(name: String, vararg boxes: ByteArray): File =
        folder.newFile(name).also { file ->
            file.outputStream().use { out -> boxes.forEach { out.write(it) } }
        }

    @Test
    fun `a complete export is accepted`() {
        val file = file(
            "ok.mp4",
            box("ftyp", ByteArray(16)),
            box("mdat", ByteArray(64)),
            box("moov", box("mvhd", mvhd(15_000))),
        )
        val facts = Mp4Index.inspect(file)
        assertNotNull(facts)
        assertTrue(facts!!.hasIndex)
        assertTrue(facts.hasMedia)
        assertEquals(15_000L, facts.durationMs)
    }

    @Test
    fun `a file with no index is rejected`() {
        // What a stop() that never ran leaves behind: the samples are all there,
        // and nothing says where they are.
        val file = file("no-index.mp4", box("ftyp", ByteArray(16)), box("mdat", ByteArray(64)))
        val facts = Mp4Index.inspect(file)
        assertFalse(facts!!.hasIndex)
        assertNull(facts.durationMs)
    }

    @Test
    fun `an index with no media is rejected`() {
        val file = file(
            "no-media.mp4",
            box("ftyp", ByteArray(16)),
            box("mdat", ByteArray(0)),
            box("moov", box("mvhd", mvhd(15_000))),
        )
        val facts = Mp4Index.inspect(file)!!
        assertTrue(facts.hasIndex)
        assertFalse("An empty mdat means every sample was lost", facts.hasMedia)
    }

    @Test
    fun `a 64-bit movie header is read`() {
        val file = file(
            "v1.mp4",
            box("ftyp", ByteArray(16)),
            box("mdat", ByteArray(64)),
            box("moov", box("mvhd", mvhd(15_000, version = 1))),
        )
        assertEquals(15_000L, Mp4Index.inspect(file)!!.durationMs)
    }

    @Test
    fun `a box running past the end of the file stops the walk`() {
        // A truncated download: the declared size is honest, the bytes are not
        // there. Everything before it still has to be readable.
        val truncated = box("ftyp", ByteArray(16)) + box("mdat", ByteArray(64))
        val lyingSize = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val file = folder.newFile("short.mp4").apply {
            outputStream().use { out ->
                out.write(truncated)
                out.write(lyingSize)
                out.write("moov".toByteArray(Charsets.ISO_8859_1))
            }
        }
        val facts = Mp4Index.inspect(file)!!
        assertEquals(listOf("ftyp", "mdat", "moov"), facts.boxes.map { it.type })
        assertNull(facts.durationMs)
    }

    @Test
    fun `a file too short to be an mp4 is unreadable`() {
        val file = folder.newFile("empty.mp4")
        assertNull(Mp4Index.inspect(file))
        file.writeText("not an mp4")
        assertNull(Mp4Index.inspect(file))
    }
}
