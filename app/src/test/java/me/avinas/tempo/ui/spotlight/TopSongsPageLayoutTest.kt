package me.avinas.tempo.ui.spotlight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Top Songs slide lists ten tracks: #1 as the hero, #2-#9 as a two-column
 * grid, #10 in the footer.
 *
 * The grid is pinned here because it broke without looking broken. Each cell put
 * its own `Modifier.weight(1f)` *inside* `EnterAnimation`, so while the entrance
 * animation was running the weight was read by the `AnimatedVisibility` wrapper
 * rather than by the row: the left cell took the whole row, the right cell
 * (#3, #5, #7, #9) measured to nothing, and the slide showed every other song.
 * Leaving the slide and coming back skipped the animation, which is why the
 * missing ranks reappeared and why any screenshot taken on a re-visit looked
 * correct. So the assertions below run on a first play-through, with no page
 * marked seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TopSongsPageLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun fakeArt() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept("file:///qa/art.jpg", ColorImage(0xFF7B3F2A.toInt()))
            .default(ColorImage(0xFF222A2A.toInt()))
            .build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context)
                .components { add(engine) }
                .build()
        }
    }

    /**
     * Bounds of the title node of one song, clipped — a cell squeezed to nothing
     * reports zero here instead of its spilled-over intrinsic width.
     */
    private fun titleBounds(rank: Int): Rect {
        val nodes = compose.onAllNodesWithText("Track $rank").fetchSemanticsNodes()
        assertTrue("Rank $rank never reached the tree", nodes.isNotEmpty())
        return nodes.first().boundsInRoot
    }

    @Test
    fun `every rank keeps its column on the first play-through`() {
        compose.setContent {
            TempoTheme {
                Box(Modifier.width(411.dp).height(891.dp).background(Color(0xFF050808))) {
                    TopSongsPage(page())
                }
            }
        }
        // The slide enters with staggered fades (the last card starts at 850ms and
        // runs for 450ms). Settle them so the bounds read below are the resting
        // layout rather than mid-slide.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        // #2-#9 are the grid's four rows, left-to-right.
        for (rowStart in 2..8 step 2) {
            val left = titleBounds(rowStart)
            val right = titleBounds(rowStart + 1)

            assertTrue(
                "Rank ${rowStart + 1} sits on top of rank $rowStart instead of beside it",
                right.left >= left.right
            )
            assertTrue(
                "Rank $rowStart collapsed: ${left.width}px wide",
                left.width > 0f && left.height > 0f
            )
            assertTrue(
                "Rank ${rowStart + 1} collapsed: ${right.width}px wide",
                right.width > 0f && right.height > 0f
            )
            // The bug left one cell holding the row, so measuring them against
            // each other is what catches it: equal columns, not a winner and a stub.
            assertTrue(
                "Columns $rowStart and ${rowStart + 1} are not the same width: " +
                    "${left.width}px vs ${right.width}px",
                right.width >= left.width * 0.7f && right.width <= left.width * 1.3f
            )
        }

        // #10 keeps the footer beat, under the grid rather than inside it.
        val footer = titleBounds(10)
        assertTrue(
            "Rank 10 is not below the grid",
            footer.top >= titleBounds(8).bottom
        )
    }

    private fun page(): SpotlightStoryPage.TopSongs =
        SpotlightStoryPage.TopSongs(
            conversationalText = "You clearly couldn't get enough of this one.",
            topSongTitle = "Track 1",
            topSongArtist = "Artist 1",
            topSongImageUrl = "file:///qa/art.jpg",
            playCount = 120,
            totalTimeMs = 3L * 60 * 60 * 1000 + 13L * 60 * 1000,
            topSongs = (1..10).map { rank ->
                SpotlightStoryPage.TopSongs.SongEntry(
                    rank = rank,
                    title = "Track $rank",
                    artist = "Artist $rank",
                    playCount = 120 - rank,
                    imageUrl = "file:///qa/art.jpg"
                )
            },
            previewUrl = null
        )
}
