package me.avinas.tempo.ui.spotlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.LocalInCaptureContext
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the three story slides whose list items are laid out by hand — the
 * artist grid, the song grid, and the closing slide at each item count — at the
 * card's design size, so a padding or wrapping regression is visible without a
 * device.
 *
 * The closing slide is printed three times because its lists are sized by the
 * 3/5/10 deck: one capture cannot say what ten rows do to the page.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*StoryPageScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * Output lands in app/build/outputs/roborazzi/ as story_<frame>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi", application = android.app.Application::class)
class StoryPageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Tag on the box that holds the slide, so the capture is the slide alone. */
    private val caseTag = "story_page_case"

    private val frame = mutableStateOf(Frame.SONGS)

    /**
     * Ink of a light share-card theme. The closing slide's two panels pick a
     * different wash per ink, so the light case needs its own capture.
     */
    private val lightInk = StoryInk(
        ink = Color(0xFF1C1917),
        surface = Color.White.copy(alpha = 0.6f),
        border = Color(0xFF1C1917).copy(alpha = 0.12f),
    )

    @Before
    fun fakeArt() {
        // The slides draw real covers through Coil; a ColorImage would stub the
        // decode, but the pages clip and round those bitmaps, so the intercepts
        // are real ones. Same reasoning as the share card harness.
        val builder = FakeImageLoaderEngine.Builder()
        COVER_URLS.forEachIndexed { index, url -> builder.intercept(url, coverArt(index).asImage()) }
        val engine = builder.default(coverArt(COVER_URLS.size).asImage()).build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context).components { add(engine) }.build()
        }
    }

    @Test
    fun storyPage() {
        compose.setContent {
            TempoTheme {
                val spec = frame.value
                Box(
                    Modifier
                        .width(CARD_WIDTH_DP.dp)
                        .height(CARD_HEIGHT_DP.dp)
                        .background(if (spec.light) Color(0xFFF2EDE4) else Color(0xFF12100E))
                        .testTag(caseTag)
                ) {
                    CompositionLocalProvider(
                        // Capture skips the entrance animations, as the real capture path does.
                        LocalInCaptureContext provides true,
                        // The frame a shared card gives a slide: status row on top, branding below.
                        LocalStoryCardFrame provides StoryCardFrame(top = 52.dp, bottom = 56.dp),
                        LocalStoryInk provides if (spec.light) lightInk else StoryInk.Dark,
                    ) {
                        SpotlightStoryPageContent(
                            page = spec.page,
                            showWatermark = true,
                            conclusionItemCount = spec.count,
                        )
                    }
                }
            }
        }
        Frame.entries.forEach { spec ->
            if (spec != frame.value) compose.runOnIdle { frame.value = spec }
            compose.waitForIdle()
            compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/story_${spec.slug}.png")
        }
    }

    /**
     * One slide per capture. The two grids print nine entries beside the hero and
     * a tenth on its own card; the closing slide prints whichever count is on the
     * deck, which is what the preview is supposed to honour.
     */
    private enum class Frame(
        val slug: String,
        val page: SpotlightStoryPage,
        val count: Int = 5,
        val light: Boolean = false,
    ) {
        ARTISTS("top_artists", topArtistsPage()),
        SONGS("top_songs", topSongsPage()),
        CONCLUSION_3("conclusion_three", conclusionPage(), 3),
        CONCLUSION_5("conclusion_five", conclusionPage(), 5),
        CONCLUSION_10("conclusion_ten", conclusionPage(), 10),
        CONCLUSION_LIGHT("conclusion_light", conclusionPage(), 10, light = true),
    }

    companion object {
        private const val CARD_WIDTH_DP = 360
        private const val CARD_HEIGHT_DP = 640
        private const val COVER_SIZE = 256

        private val COVER_URLS = (0 until 10).map { "file:///qa/story_cover$it.jpg" }

        private val COVER_PALETTES =
            listOf(
                0xFFF43F5E.toInt() to 0xFF6D28D9.toInt(),
                0xFF22D3EE.toInt() to 0xFF1E3A8A.toInt(),
                0xFFF59E0B.toInt() to 0xFFB91C1C.toInt(),
                0xFF34D399.toInt() to 0xFF065F46.toInt(),
                0xFF818CF8.toInt() to 0xFF1E1B4B.toInt(),
                0xFFFB7185.toInt() to 0xFF7C2D12.toInt(),
                0xFFA3E635.toInt() to 0xFF15803D.toInt(),
                0xFF06B6D4.toInt() to 0xFF312E81.toInt(),
                0xFFF97316.toInt() to 0xFF7E22CE.toInt(),
                0xFFEAB308.toInt() to 0xFF0F766E.toInt(),
            )

        private val ARTIST_NAMES =
            listOf(
                "Mitski", "Phoebe Bridgers", "Alex G", "Angel Olsen", "Big Thief",
                "Japanese Breakfast", "Snail Mail", "Lucy Dacus", "Weyes Blood", "Caroline Polachek",
            )

        private val SONG_TITLES =
            listOf(
                "Nobody", "Motion Sickness", "Sarah", "Shut Up Kiss Me", "Paul",
                "Be Sweet", "Pristine", "Night Shift", "Movies", "So Hot You're Hurting My Feelings",
            )

        private fun coverArt(index: Int): Bitmap {
            val (top, bottom) = COVER_PALETTES[index % COVER_PALETTES.size]
            val size = COVER_SIZE.toFloat()
            val bitmap = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, size, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, size, size, paint)
            paint.shader = null
            paint.color = 0x33FFFFFF
            canvas.drawCircle(size * 0.3f, size * 0.35f, size * 0.22f, paint)
            canvas.drawCircle(size * 0.72f, size * 0.7f, size * 0.14f, paint)
            return bitmap
        }

        private fun topArtistsPage(): SpotlightStoryPage.TopArtist =
            SpotlightStoryPage.TopArtist(
                conversationalText = "They defined your sound this year.",
                topArtistName = ARTIST_NAMES.first(),
                topArtistImageUrl = COVER_URLS.first(),
                topArtistPercentage = 0,
                topArtists = ARTIST_NAMES.mapIndexed { index, name ->
                    SpotlightStoryPage.TopArtist.ArtistEntry(
                        rank = index + 1,
                        name = name,
                        hoursListened = 40 - index * 3,
                        imageUrl = COVER_URLS[index],
                    )
                },
            )

        private fun topSongsPage(): SpotlightStoryPage.TopSongs =
            SpotlightStoryPage.TopSongs(
                conversationalText = "The track that stuck with you.",
                topSongTitle = SONG_TITLES.first(),
                topSongArtist = ARTIST_NAMES.first(),
                topSongImageUrl = COVER_URLS.first(),
                playCount = 128,
                totalTimeMs = 6L * 60 * 60 * 1000,
                topSongs = SONG_TITLES.mapIndexed { index, title ->
                    SpotlightStoryPage.TopSongs.SongEntry(
                        rank = index + 1,
                        title = title,
                        artist = ARTIST_NAMES[index],
                        playCount = 128 - index * 9,
                        imageUrl = COVER_URLS[index],
                    )
                },
                previewUrl = null,
            )

        private fun conclusionPage(): SpotlightStoryPage.Conclusion =
            SpotlightStoryPage.Conclusion(
                conversationalText = "See you next time.",
                totalMinutes = 4234,
                personalityType = "The Melophile",
                personalityKey = "melophile",
                topArtists = ARTIST_NAMES.mapIndexed { index, name ->
                    SpotlightStoryPage.Conclusion.ArtistEntry(name, COVER_URLS[index])
                },
                topSongs = SONG_TITLES.mapIndexed { index, title ->
                    SpotlightStoryPage.Conclusion.SongEntry(title, COVER_URLS[index])
                },
                topGenres = listOf("Indie Rock", "Shoegaze", "Dream Pop"),
                timeRange = TimeRange.THIS_YEAR,
            )
    }
}
