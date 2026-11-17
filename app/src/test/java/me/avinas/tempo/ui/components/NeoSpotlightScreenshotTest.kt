package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.ui.home.components.SpotlightStoryCard
import me.avinas.tempo.ui.spotlight.SpotlightStoryHeroCard
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Spotlight entry points off-device at a fixed 320dp width so headline
 * wrapping can be checked without an emulator install.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*NeoSpotlightScreenshotTest*" \
 *     -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-xxhdpi", application = android.app.Application::class)
class NeoSpotlightScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val caseTag = "neo_case"

    @Before
    fun fakeArt() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept("file:///qa/story.jpg", ColorImage(0xFF7B3F2A.toInt()))
            .intercept("file:///qa/lead.jpg", ColorImage(0xFF2E4A62.toInt()))
            .default(ColorImage(0xFF222A2A.toInt()))
            .build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context)
                .components { add(engine) }
                .build()
        }
    }

    private fun capture(name: String, width: Dp = 320.dp, content: @Composable () -> Unit) {
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(width)
                        .background(Color(0xFF050808))
                        .testTag(caseTag)
                ) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun homeSpotlightCard() {
        capture("neo_home_spotlight") {
            SpotlightStoryCard(
                onClick = {},
                onRingClick = {},
                albumArtUrl = "file:///qa/story.jpg",
                storyAvailable = true,
                storyViewed = false
            )
        }
    }

    @Test
    fun heroUnlockedNarrow() {
        capture("neo_hero_unlocked") {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for Nov 9 — 15",
                isLocked = false,
                lockMessage = "",
                leadArtUrl = "file:///qa/lead.jpg",
                storyLoading = false
            )
        }
    }

    @Test
    fun heroUnlockedLongLabel() {
        capture("neo_hero_unlocked_long") {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for September 2026",
                isLocked = false,
                lockMessage = "",
                leadArtUrl = "file:///qa/lead.jpg",
                storyLoading = false
            )
        }
    }

    @Test
    fun heroLocked() {
        capture("neo_hero_locked") {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for Nov 9 — 15",
                isLocked = true,
                lockMessage = "Your Weekly Wrapped arrives on Sunday",
                leadArtUrl = null,
                storyLoading = false
            )
        }
    }

    /** 360dp phone minus the screen's 16dp side padding — the width the card really gets. */
    @Test
    fun homeSpotlightCardOnDevice() {
        capture("neo_home_spotlight_device", width = 328.dp) {
            SpotlightStoryCard(
                onClick = {},
                onRingClick = {},
                albumArtUrl = "file:///qa/story.jpg",
                storyAvailable = true,
                storyViewed = false
            )
        }
    }

    @Test
    fun heroUnlockedOnDevice() {
        capture("neo_hero_unlocked_device", width = 328.dp) {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for Nov 9 — 15",
                isLocked = false,
                lockMessage = "",
                leadArtUrl = "file:///qa/lead.jpg",
                storyLoading = false
            )
        }
    }
}
