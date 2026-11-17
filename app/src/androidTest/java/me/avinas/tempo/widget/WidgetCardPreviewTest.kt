package me.avinas.tempo.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import me.avinas.tempo.widget.utils.BitmapGenerator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the two Today-screen cards at widget size and drops the PNGs on external
 * storage, which is how `res/drawable/widget_preview_today_total.png` and
 * `res/drawable/widget_preview_rhythm.png` are produced.
 *
 * Glance 1.1.1 has no `providePreviewContent`, so the launcher picker can only show a
 * static `android:previewImage`. This test is the way to regenerate those two images
 * when the card design changes; it also guards that both renderers produce real pixels
 * for a fully populated day.
 */
@RunWith(AndroidJUnit4::class)
class WidgetCardPreviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun renderTodayTotalPreview() {
        val bitmap = BitmapGenerator.generateTodayTotalCard(
            context = context,
            width = PREVIEW_WIDTH,
            height = PREVIEW_HEIGHT,
            heroTime = "1h 3m",
            playCount = 16,
            trackCount = 16,
            albumCount = 12,
            artistCount = 8,
            avgSession = "1.1h",
            todayShort = "1.1h",
            yesterdayShort = "8m",
            growthPercent = 664f,
            todayMs = 3_780_000L,
            yesterdayMs = 480_000L,
        )
        write(bitmap, "widget_preview_today_total.png")
    }

    @Test
    fun renderListeningRhythmPreview() {
        // 16 plays across the evening, peaking at 10 PM with 10 — the same shape as the
        // in-app card, so the preview reads as a real day rather than a demo stub.
        val hourly = MutableList(24) { 0 }
        hourly[19] = 2
        hourly[20] = 1
        hourly[21] = 3
        hourly[22] = 10

        val bitmap = BitmapGenerator.generateListeningRhythmCard(
            context = context,
            width = PREVIEW_WIDTH,
            height = PREVIEW_HEIGHT,
            hourlyPlays = hourly,
            currentHour = 22,
        )
        write(bitmap, "widget_preview_rhythm.png")
    }

    private fun write(bitmap: Bitmap, name: String) {
        val dir = context.getExternalFilesDir(null) ?: File(context.filesDir, "preview")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name was not written", file.length() > 0L)
    }

    private companion object {
        // 4×2 cells at the launcher's render density; the same shape the other
        // widget_preview_*.png assets use.
        const val PREVIEW_WIDTH = 810
        const val PREVIEW_HEIGHT = 408
    }
}
