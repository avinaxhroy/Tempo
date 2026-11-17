package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The story's first second depends on this scan: the muxer drops leading samples
 * until one is flagged as a sync sample, and the Qualcomm encoder does not flag
 * the IDR it codes first, so the encoder flags it from the bytes instead.
 */
class ShareVideoEncoderNalTest {

    private val encoder = ShareVideoEncoder()

    @Test
    fun `an IDR behind a four-byte start code is found after the SEI`() {
        val sample = bufferOf(
            bytes(0, 0, 0, 1, 0x06, 0x05, 0x01), // SEI
            bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00), // IDR slice
        )

        assertEquals(listOf(6, 5), encoder.nalTypes(sample, 0, sample.capacity()))
    }

    @Test
    fun `a P frame carries no IDR and is left unflagged`() {
        val sample = bufferOf(bytes(0, 0, 1, 0x41, 0x9a, 0x22))

        val types = encoder.nalTypes(sample, 0, sample.capacity())

        assertEquals(listOf(1), types)
        assertFalse(5 in types)
    }

    @Test
    fun `the scan stays inside the sample's own bytes`() {
        // A config sample sits in front of the slice: only [offset, offset + size)
        // may be read, or the encoder flags a keyframe that is not there.
        val sample = bufferOf(
            bytes(0, 0, 0, 1, 0x67, 0x42, 0x80), // SPS in the config buffer
            bytes(0, 0, 1, 0x41, 0x9a), // P slice
        )

        assertEquals(listOf(7), encoder.nalTypes(sample, 0, 7))
    }

    @Test
    fun `a payload full of zero bytes does not run off the end`() {
        val sample = bufferOf(ByteArray(16), bytes(0, 0))

        assertEquals(emptyList<Int>(), encoder.nalTypes(sample, 0, sample.capacity()))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun bufferOf(vararg parts: ByteArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(parts.sumOf { it.size })
        parts.forEach { buffer.put(it) }
        buffer.position(0)
        return buffer
    }
}
