package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareThemeId
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the badge and level-milestone cards once per [ShareTheme].
 *
 * These two cards have no cover art to decode: the app draws their backdrop as a
 * bitmap and hands it in as a [ShareArtSource]. That is a different path into the
 * theme backdrops than a URL is, and it used to be a path only GLASS could read —
 * the other themes silently printed a generic gradient instead of the badge's own
 * bloom and emblem watermark. A capture per theme is the only way to see that the
 * badge art actually reaches each treatment.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*BadgeArtThemeScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi/ as
 * badge_art_<theme>_badge.png and badge_art_<theme>_milestone.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class BadgeArtThemeScreenshotTest(
    private val theme: ShareTheme,
) {

    @get:Rule
    val compose = createComposeRule()

    private val caseTag = "badge_art_case"

    /** Which card is on screen; the rule allows one setContent per test. */
    private val frame = mutableStateOf(CaptureFrame.BADGE)

    @Test
    fun badgeArtBackdrop() {
        val slug = theme.name.lowercase()
        val card = ShareCardTheme(id = ShareThemeId.Still(theme))
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(CARD_WIDTH_DP.dp)
                        .background(Color(0xFF050808))
                        .testTag(caseTag),
                ) {
                    when (frame.value) {
                        CaptureFrame.BADGE -> BadgeShareCard(
                            badge = SAMPLE_BADGE,
                            userName = "Avinash",
                            card = card,
                        )

                        CaptureFrame.MILESTONE -> MilestoneShareCard(
                            level = 42,
                            title = "",
                            userName = "Avinash",
                            totalXp = 184_500L,
                            streak = 37,
                            card = card,
                        )
                    }
                }
            }
        }
        CaptureFrame.entries.forEach { spec ->
            if (spec != frame.value) compose.runOnIdle { frame.value = spec }
            awaitBackdropBake()
            compose.onNodeWithTag(caseTag)
                .captureRoboImage("build/outputs/roborazzi/badge_art_${slug}_${spec.slug}.png")
        }
    }

    /**
     * The backdrop plate is built in a `produceState` from a bitmap the card
     * rasterises, so `waitForIdle` returns before the plate exists and a capture
     * would record the fallback. Bounded, so a bake that never lands cannot hang
     * the suite.
     */
    private fun awaitBackdropBake() {
        repeat(BAKE_WAIT_STEPS) {
            compose.waitForIdle()
            Thread.sleep(BAKE_WAIT_STEP_MS)
        }
    }

    private enum class CaptureFrame(
        val slug: String,
    ) {
        BADGE("badge"),
        MILESTONE("milestone"),
    }

    companion object {
        /** The card's design width; its height follows from the 360x640 share aspect. */
        private const val CARD_WIDTH_DP = 360

        private const val BAKE_WAIT_STEPS = 20
        private const val BAKE_WAIT_STEP_MS = 100L

        private val SAMPLE_BADGE =
            Badge(
                id = 1,
                badgeId = "streak_30",
                name = "Thirty Days",
                description = "Listened for thirty days straight",
                iconName = "streak",
                category = "STREAK",
                earnedAt = 1_730_000_000_000L,
                progress = 30,
                maxProgress = 30,
                isEarned = true,
                stars = 3,
                isAcknowledged = true,
            )

        /** One child test per theme, so `--tests` can select a single theme. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ShareTheme> = ShareTheme.entries.toList()
    }
}
