package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Spotlight recap sheet off-device so layout, type and spacing can be
 * reviewed without a full emulator install. Run with:
 *
 * ./gradlew :app:testDebugUnitTest --tests "*SpotlightRecapSheetScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "w360dp-h800dp-xxhdpi",
    // The real Application boots Hilt, WorkManager and Room invalidation, none of
    // which a pure composition test needs.
    application = android.app.Application::class
)
class SpotlightRecapSheetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val artUrls = (1..5).map { "file:///qa/$it.jpg" }

    @Before
    fun fakeArt() {
        // Solid sleeves keep the fan legible without any network or disk access.
        val engine = FakeImageLoaderEngine.Builder()
            .intercept("file:///qa/1.jpg", ColorImage(0xFF7B3F2A.toInt()))
            .intercept("file:///qa/2.jpg", ColorImage(0xFF2E4A62.toInt()))
            .intercept("file:///qa/3.jpg", ColorImage(0xFF8A5A2B.toInt()))
            .intercept("file:///qa/4.jpg", ColorImage(0xFF5A5F63.toInt()))
            .intercept("file:///qa/5.jpg", ColorImage(0xFF6E2B30.toInt()))
            .default(ColorImage(0xFF222A2A.toInt()))
            .build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context)
                .components { add(engine) }
                .build()
        }
    }

    private fun renderSheet(
        maxHeight: Int,
        periodLabel: String = "Nov 9 — 15",
        headline: String = "Your week, on repeat.",
        cta: String = "Explore your week"
    ) {
        compose.setContent {
            TempoTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF050808))) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        RecapSheet(
                            kicker = "Tempo Spotlight",
                            periodLabel = periodLabel,
                            headline = headline,
                            subtitle = "Your favorite tracks, top artists and listening highlights are ready.",
                            cta = cta,
                            maybeLater = "Maybe later",
                            closeLabel = "Close",
                            preview = SpotlightReminderPreview(artUrls = artUrls),
                            entrance = 1f,
                            maxHeight = maxHeight.dp,
                            onClose = {},
                            onViewStory = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun weeklySheet() {
        renderSheet(760)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/spotlight_sheet_360.png")
    }

    @Test
    fun monthlySheet() {
        renderSheet(
            maxHeight = 760,
            periodLabel = "November",
            headline = "Your month, bar by bar.",
            cta = "Explore your month"
        )
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/spotlight_sheet_monthly.png")
    }

    @Test
    fun yearlySheet() {
        renderSheet(
            maxHeight = 760,
            periodLabel = "2026",
            headline = "Your year, note by note.",
            cta = "Explore your year"
        )
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/spotlight_sheet_yearly.png")
    }

    @Test
    fun compactSheet() {
        renderSheet(560)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/spotlight_sheet_compact.png")
    }
}
