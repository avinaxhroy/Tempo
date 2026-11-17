package me.avinas.tempo.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the rate prompt off-device so its composition can be reviewed without an
 * emulator. Two frames come out of one composition because the card changes layout
 * rather than scaling: the regular phone at 360dp, and the small phone at 320dp
 * where [ReviewCard] drops to its compact metrics.
 *
 * The card is given an explicit width here instead of inheriting the test screen,
 * so a capture is the card at a known size on any host. The figure is a bitmap in
 * drawable-nodpi, so Robolectric decodes the real character rather than a stub.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*RateAppPopupScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi/ as rate_popup_regular.png and
 * rate_popup_small.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xxhdpi", application = android.app.Application::class)
class RateAppPopupScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Tag on the stage that holds the card, so the capture is the card and nothing else. */
    private val caseTag = "rate_popup_case"

    /** Which layout is on screen; the compose rule takes one setContent per test. */
    private val compact = mutableStateOf(false)

    @Test
    fun ratePopup() {
        compose.setContent {
            TempoTheme {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF050808))
                        .padding(18.dp)
                        .testTag(caseTag),
                    contentAlignment = Alignment.Center,
                ) {
                    // The dialog's own horizontal inset, so the capture shows the
                    // card at the width a phone actually gives it.
                    Box(Modifier.width(if (compact.value) 320.dp else 360.dp)) {
                        ReviewCard(
                            compact = compact.value,
                            maxHeight = if (compact.value) 620.dp else 720.dp,
                            entrance = 1f,
                            isSubmitting = false,
                            onRate = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/rate_popup_regular.png")

        compose.runOnIdle { compact.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/rate_popup_small.png")
    }
}
