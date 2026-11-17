package me.avinas.tempo.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.ui.components.share.ShareThemeId
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the stats share card once per [ShareTheme] with no cover art at all.
 *
 * A ranking can arrive with blank artwork: a fresh import, a track whose album
 * has no cover, a service that hands back no image. Every artwork theme has to
 * stay a whole backdrop in that case — a printed plate, not an empty card — so
 * this harness captures the fallback each theme draws instead of the art.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*StatsShareNoArtScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi/ as stats_noart_<theme>.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class StatsShareNoArtScreenshotTest(
    private val theme: ShareTheme,
) {

    @get:Rule
    val compose = createComposeRule()

    private val caseTag = "stats_noart_case"

    @Test
    fun statsShareWithoutCoverArt() {
        val slug = theme.name.lowercase()
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(CARD_WIDTH_DP.dp)
                        .background(Color(0xFF050808))
                        .testTag(caseTag),
                ) {
                    StatsShareCard(
                        tab = StatsTab.TOP_SONGS,
                        timeRange = TimeRange.THIS_WEEK,
                        items = SAMPLE_TRACKS,
                        overview = SAMPLE_OVERVIEW,
                        config =
                            StatsShareConfig(
                                count = ShareItemCount.TOP_5,
                                layout = StatsShareLayout.LIST,
                                showSummary = true,
                            ),
                        card = ShareCardTheme(id = ShareThemeId.Still(theme)),
                    )
                }
            }
        }
        // The fallbacks are drawn on the first frame — there is no decode or bake
        // to wait for — but a pump keeps this honest if a theme grows one.
        repeat(BAKE_WAIT_STEPS) {
            compose.waitForIdle()
            Thread.sleep(BAKE_WAIT_STEP_MS)
        }
        compose.onNodeWithTag(caseTag)
            .captureRoboImage("build/outputs/roborazzi/stats_noart_$slug.png")
    }

    companion object {
        private const val CARD_WIDTH_DP = 360
        private const val BAKE_WAIT_STEPS = 5
        private const val BAKE_WAIT_STEP_MS = 100L

        private const val FIRST_PLAYED = 1_729_000_000_000L
        private const val LAST_PLAYED = 1_735_000_000_000L

        /** Every row has no artwork: the case this harness exists for. */
        private val SAMPLE_TRACKS: List<Any> =
            listOf(
                track(1L, "Wildfire", "North Bay", "Wildfire", 87, 3_900_000L),
                track(2L, "Afterglow", "Ana Reyes", "Afterglow", 104, 4_620_000L),
                track(3L, "Coastline", "Ivy Row", "Coastline", 131, 5_580_000L),
                track(4L, "Golden Hour", "Marble Sun", "Golden Hour", 167, 6_480_000L),
                track(5L, "Glass Rivers", "The Long Wait", "Glass Rivers", 198, 7_920_000L),
            )

        private fun track(
            id: Long,
            title: String,
            artist: String,
            album: String,
            plays: Int,
            timeMs: Long,
        ) = TopTrack(
            trackId = id,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = null,
            playCount = plays,
            totalTimeMs = timeMs,
            firstPlayed = FIRST_PLAYED,
            lastPlayed = LAST_PLAYED,
        )

        private val SAMPLE_OVERVIEW =
            ListeningOverview(
                totalListeningTimeMs = 7_739 * 60_000L,
                totalPlayCount = 1_247_318,
                uniqueTracksCount = 128_486,
                uniqueArtistsCount = 132,
                uniqueAlbumsCount = 210,
                averageSessionDurationMs = 25 * 60_000L,
                longestSessionMs = 190 * 60_000L,
                timeRange = TimeRange.THIS_WEEK,
            )

        /** One child test per theme, so `--tests` can select a single theme. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ShareTheme> = ShareTheme.entries.toList()
    }
}
