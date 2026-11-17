package me.avinas.tempo.ui.applemusic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Renders the Apple walkthrough at one frame per page, so a layout regression —
 * overflow past the bezel, a caption that no longer fits, the file-size menu
 * landing on the wrong row — is visible without a device.
 *
 * One composition, scrubbed forward on the test clock: the walkthrough owns a
 * single frame-clock timeline, so advancing it is the only way in.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*AppleMusicTutorialScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi/ as apple_<frame>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-xhdpi", application = android.app.Application::class)
class AppleMusicTutorialScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val caseTag = "apple_tutorial_case"

    /** Cumulative timeline positions, one per page — plus the two menu beats. */
    private enum class Frame(
        val slug: String,
        val atSeconds: Float,
    ) {
        MANAGE_DATA("1_manage_data", 0.8f),
        CATEGORIES("2_categories", 2.6f),
        CATEGORY_TICKED("3_ticked", 4.0f),
        SIZE_MENU_OPEN("4_size_menu", 6.5f),
        SIZE_READY("5_size", 7.6f),
        PREPARING("6_preparing", 9.2f),
        ZIP_TO_TEMPO("7_zip", 11.8f),
    }

    @Test
    fun tutorial() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(400.dp)
                        .height(720.dp)
                        .background(Color(0xFF0B0B0F))
                        // Tag before padding so the capture includes the margin
                        // instead of cropping flush against the step numbers.
                        .testTag(caseTag)
                        // Same breathing room the GlassCard gives it in the screen.
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    AppleMusicTutorial()
                }
            }
        }

        var previous = 0f
        Frame.entries.forEach { frame ->
            compose.mainClock.advanceTimeBy(((frame.atSeconds - previous) * 1000f).toLong())
            previous = frame.atSeconds
            compose.onNodeWithTag(caseTag)
                .captureRoboImage("build/outputs/roborazzi/apple_${frame.slug}.png")
        }
    }

    /** The caution strip on the import screen: wraps to a few lines on a phone and must not clip. */
    @Test
    fun backupNotice() {
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(400.dp)
                        .background(Color(0xFF0B0B0F))
                        .testTag(caseTag)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    AppleBackupNotice()
                }
            }
        }

        compose.onNodeWithTag(caseTag)
            .captureRoboImage("build/outputs/roborazzi/apple_import_backup_notice.png")
    }
}
