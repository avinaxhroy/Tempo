package me.avinas.tempo.data.applemusic

import android.net.Uri
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the service's local-file fast path: the worker hands over an already-staged private
 * file, so re-copying it into the cache would double a multi-hundred-megabyte export's disk use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleMusicImportServiceTest {

    private val tempDir = createTempDirectory("apple-import-service").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `staged file uri is imported in place`() {
        val staged = File(tempDir, "apple-music-import-1.zip").apply { writeText("payload") }

        val resolved = AppleMusicImportService.localFileOrNull(Uri.fromFile(staged))

        assertEquals(staged, resolved)
    }

    @Test
    fun `content uri still goes through the copying path`() {
        assertNull(AppleMusicImportService.localFileOrNull(Uri.parse("content://downloads/all/42")))
    }

    @Test
    fun `missing or unparsable local path falls back to copying`() {
        assertNull(AppleMusicImportService.localFileOrNull(Uri.fromFile(File(tempDir, "absent.zip"))))
        assertNull(AppleMusicImportService.localFileOrNull(Uri.parse("file:///dev/null/nope.zip")))
    }

    @Test
    fun `play duration below the tracking minimum is skipped`() {
        assertTrue(AppleMusicImportService.shouldImportApplePlay(25_000L))
        assertEquals(false, AppleMusicImportService.shouldImportApplePlay(24_999L))
    }
}
