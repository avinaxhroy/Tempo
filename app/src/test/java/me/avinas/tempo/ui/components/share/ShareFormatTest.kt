package me.avinas.tempo.ui.components.share

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share band gives each of its three columns a third of the card, so the
 * count formatter is what decides whether a number fits. Five digits fit at
 * 20.sp; six do not.
 */
class ShareFormatTest {

    @Test
    fun `small counts print as themselves`() {
        assertEquals("0", formatShareCount(0))
        assertEquals("1247", formatShareCount(1247))
        assertEquals("99999", formatShareCount(99_999))
    }

    @Test
    fun `six digits compact to thousands`() {
        assertEquals("100K", formatShareCount(100_000))
        assertEquals("128K", formatShareCount(128_486))
        // Truncating keeps 999_999 from reading "1000K".
        assertEquals("999K", formatShareCount(999_999))
    }

    @Test
    fun `seven digits and up compact to millions`() {
        assertEquals("1.0M", formatShareCount(1_000_000))
        assertEquals("1.2M", formatShareCount(1_247_318))
        assertEquals("12.3M", formatShareCount(12_345_678))
    }
}
