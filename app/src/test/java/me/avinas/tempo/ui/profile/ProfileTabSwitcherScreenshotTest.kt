package me.avinas.tempo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Renders the profile's Quest | Badges toggle off-device so the bezel, the lit
 * crown and the raised key can be checked without an emulator install.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*ProfileTabSwitcherScreenshotTest*" \
 *     -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-xxhdpi", application = android.app.Application::class)
class ProfileTabSwitcherScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val caseTag = "tab_switcher_case"

    private fun capture(name: String, tabs: List<String>, selected: Int) {
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(328.dp)
                        .background(Color(0xFF080A0A))
                        .padding(16.dp)
                        .testTag(caseTag)
                ) {
                    var selection by remember { mutableIntStateOf(selected) }
                    TabSwitcher(tabs = tabs, selectedTab = selection, onTabSelected = { selection = it })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun questsSelected() = capture("profile_toggle_quests", listOf("Quests", "Badges"), 0)

    @Test
    fun badgesSelected() = capture("profile_toggle_badges", listOf("Quests", "Badges"), 1)

    @Test
    fun wideLabels() = capture("profile_toggle_wide", listOf("Challenges", "Badges"), 0)
}
