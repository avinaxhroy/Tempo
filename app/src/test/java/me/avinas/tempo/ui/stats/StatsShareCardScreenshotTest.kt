package me.avinas.tempo.ui.stats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.share.ShareCardTheme
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.ui.components.share.ShareMotion
import me.avinas.tempo.ui.components.share.ShareThemeId
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the stats share card once per [ShareThemeId], off-device, so a designer
 * can review the whole strip without an emulator: the still themes followed by the
 * scenes, the same cards the carousel lays out. Each one prints the frame set
 * below — the LIST layout with five rows and a summary (the shape the stats screen
 * shares by default), the PODIUM layout with three, and the grids — because they
 * put the type at very different sizes over the same backdrop.
 *
 * The cards are driven from [ShareTheme.entries] and [ShareMotion.entries] rather
 * than a literal list of names: a theme that is renamed, added or retired updates
 * this set by construction, and the runner reports each card as its own test, so
 * one broken card does not hide the others and a single one can be re-run alone.
 * The scenes belong to the set because a scene is a card of the strip, not an
 * effect bolted onto a still one: its lettering, layout and item style have to be
 * reviewable beside the still it seeds off.
 *
 * ./gradlew :app:testDebugUnitTest --tests "*StatsShareCardScreenshotTest*" \
 *     -Proborazzi.test.record=true
 *
 * One card on its own, e.g. GLASS or the GLYPHS scene: add
 * --tests "*StatsShareCardScreenshotTest*GLASS*" (or *GRADIENT_ASCII*) to the same
 * command line.
 *
 * Output lands in app/build/outputs/roborazzi/ as stats_share_<card>_<frame>.png,
 * where <card> is the still theme's name or motion_<scene> for a scene.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi", application = android.app.Application::class)
class StatsShareCardScreenshotTest(
    private val card: ShareThemeId,
) {

    @get:Rule
    val compose = createComposeRule()

    /** Tag on the box that holds the card, so the capture is the card and nothing else. */
    private val caseTag = "stats_share_case"

    /** File-name stem for this card: the still theme's name, or the scene's. */
    private val slug: String =
        when (card) {
            is ShareThemeId.Still -> card.theme.name.lowercase()
            is ShareThemeId.Motion -> "motion_${card.motion.name.lowercase()}"
        }

    /**
     * Which frame is on screen. The compose rule accepts a single setContent per
     * test, so the frames are one composition with a switch rather than four
     * compositions — the card keeps its size (and its baked backdrop) across the
     * switch instead of being laid out, and re-baked, from cold each time.
     *
     * The two GRID frames are there because the grid prints by item shape rather
     * than by item count: a track and an album are squares, an artist is a
     * circle, and a capture of one says nothing about the other.
     */
    private val frame = mutableStateOf(CaptureFrame.LIST)

    @Before
    fun fakeArt() {
        // The share themes bake their backdrop from the artwork bitmap itself
        // (blur, fluted glass, ASCII sampling), and that bake casts the decoded
        // image to BitmapImage. A ColorImage here would decode fine and then be
        // thrown away by the cast, so every theme would print its fallback
        // gradient and the captures would all look the same. These intercepts are
        // real bitmaps for exactly that reason.
        val builder = FakeImageLoaderEngine.Builder()
        COVER_URLS.forEachIndexed { index, url ->
            builder.intercept(url, coverArt(index).asImage())
        }
        val engine = builder.default(coverArt(COVER_URLS.size).asImage()).build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context)
                .components { add(engine) }
                .build()
        }
    }

    @Test
    fun statsShareCard() {
        compose.setContent {
            TempoTheme {
                Box(
                    Modifier
                        .width(CARD_WIDTH_DP.dp)
                        .background(Color(0xFF050808))
                        .testTag(caseTag)
                ) {
                    val spec = frame.value
                    StatsShareCard(
                        tab = spec.tab,
                        timeRange = TimeRange.THIS_WEEK,
                        items = if (spec.tab == StatsTab.TOP_ARTISTS) SAMPLE_ARTISTS else SAMPLE_TRACKS,
                        overview = SAMPLE_OVERVIEW,
                        config =
                            StatsShareConfig(
                                count = spec.count,
                                layout = spec.layout,
                                showSummary = true,
                            ),
                        card = ShareCardTheme(id = card),
                    )
                }
            }
        }
        CaptureFrame.entries.forEach { spec ->
            if (spec != frame.value) {
                compose.runOnIdle { frame.value = spec }
            }
            // Only the first frame waits for a bake. The backdrop is keyed on
            // the theme and the artwork, so every frame after it reuses the
            // bitmap the first one already waited for.
            if (spec == CaptureFrame.entries.first()) awaitBackdropBake()
            capture("stats_share_${slug}_${spec.slug}")
        }
    }

    private fun capture(name: String) {
        compose.onNodeWithTag(caseTag).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * Waits for the backdrop to land before capturing.
     *
     * A share backdrop is not drawn with the card: it is baked in a
     * `produceState` on Dispatchers.Default from a Coil decode, and it cannot even
     * start until the card has been measured (the aspect ratio reaches it through
     * `onSizeChanged`). `waitForIdle` therefore returns while the bake is still in
     * flight and the capture would record the fallback gradient instead of the
     * real art. Pumping the main looper and yielding the wall clock lets the
     * decode and the bake settle; the loop is bounded so a bake that never finishes
     * cannot hang the suite.
     */
    private fun awaitBackdropBake() {
        repeat(BAKE_WAIT_STEPS) {
            compose.waitForIdle()
            Thread.sleep(BAKE_WAIT_STEP_MS)
        }
    }

    /**
     * The frames this harness prints for every theme.
     *
     * A layout alone is not enough for the grid: what it draws depends on which
     * ranking is on it and on how many entries it holds, so songs, artists and
     * the ten-item sheet are separate frames rather than one grid capture with a
     * note saying the others look different.
     */
    private enum class CaptureFrame(
        val slug: String,
        val tab: StatsTab,
        val layout: StatsShareLayout,
        val count: ShareItemCount,
    ) {
        LIST("list", StatsTab.TOP_SONGS, StatsShareLayout.LIST, ShareItemCount.TOP_5),
        // The ten-row page is the hardest thing the index sets: every row has
        // to carry art, a title, a subtitle and a value on the narrowest
        // share of the sheet, and it is the frame the item layout gets
        // reviewed on.
        LIST_TEN("list_ten", StatsTab.TOP_SONGS, StatsShareLayout.LIST, ShareItemCount.TOP_10),
        PODIUM("podium", StatsTab.TOP_SONGS, StatsShareLayout.PODIUM, ShareItemCount.TOP_3),
        GRID("grid", StatsTab.TOP_SONGS, StatsShareLayout.GRID, ShareItemCount.TOP_5),
        GRID_TEN("grid_ten", StatsTab.TOP_SONGS, StatsShareLayout.GRID, ShareItemCount.TOP_10),
        ARTISTS("grid_artists", StatsTab.TOP_ARTISTS, StatsShareLayout.GRID, ShareItemCount.TOP_5),
    }

    companion object {
        /** The card's design width; its height follows from the 360x640 share aspect. */
        private const val CARD_WIDTH_DP = 360

        private const val BAKE_WAIT_STEPS = 40
        private const val BAKE_WAIT_STEP_MS = 100L

        private const val COVER_SIZE = 384
        private const val FIRST_PLAYED = 1_729_000_000_000L
        private const val LAST_PLAYED = 1_735_000_000_000L

        private val COVER_URLS = (0 until 10).map { "file:///qa/cover$it.jpg" }

        /** Saturated pairs, one per cover, so a blurred theme cannot hide a flat image. */
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

        /**
         * A stand-in cover: a vertical two-stop gradient with a few translucent
         * circles over it. Blurred, refracted or sampled by the themes, this reads
         * as artwork with structure rather than as a flat block of colour, so the
         * captures show what each backdrop actually does to the art.
         */
        private fun coverArt(index: Int): Bitmap {
            val (top, bottom) = COVER_PALETTES[index % COVER_PALETTES.size]
            val size = COVER_SIZE.toFloat()
            val bitmap = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, size, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, size, size, paint)
            paint.shader = null
            repeat(3) { ring ->
                val cx = size * (0.25f + 0.22f * ((index + ring) % 3))
                val cy = size * (0.30f + 0.20f * ((index + ring + 1) % 3))
                val radius = size * (0.12f + 0.05f * (ring + 1))
                paint.color = if (ring % 2 == 0) 0x59FFFFFF else 0x40000000
                canvas.drawCircle(cx, cy, radius, paint)
            }
            return bitmap
        }

        /**
         * Ten rows: short titles plus one long featured-artist title that used to
         * be ellipsized on the ten-row page, over artist subtitles, climbing play
         * counts, and listening times from just over an hour to six so the
         * formatted durations vary in width.
         */
        private val SAMPLE_TRACKS: List<Any> =
            listOf(
                track(1L, "Wildfire", "North Bay", "Wildfire", 0, 87, 3_900_000L),
                track(2L, "Afterglow", "Ana Reyes", "Afterglow", 1, 104, 4_620_000L),
                track(3L, "Coastline", "Ivy Row", "Coastline", 2, 131, 5_580_000L),
                track(4L, "Golden Hour", "Marble Sun", "Golden Hour", 3, 167, 6_480_000L),
                track(5L, "Glass Rivers", "The Long Wait", "Glass Rivers", 4, 198, 7_920_000L),
                track(6L, "Maharani (feat. Arpit Bala)", "Karan Aujla", "Making Memories", 5, 240, 9_600_000L),
                track(7L, "Neon Harbour", "Sable Court", "Neon Harbour", 6, 275, 11_700_000L),
                track(8L, "Velvet Static", "Low Ceiling", "Velvet Static", 7, 318, 14_100_000L),
                track(9L, "Midnight City", "M83", "Hurry Up", 8, 366, 16_800_000L),
                track(10L, "Slow Burn", "Harbour Lights", "Slow Burn", 9, 412, 21_600_000L),
            )

        /**
         * Five artists, on the same covers as the tracks so a theme is shaded from
         * the same artwork in both grids and the two captures compare like for
         * like.
         */
        private val SAMPLE_ARTISTS: List<Any> =
            listOf(
                artist(1L, "North Bay", 0, 96, 4_500_000L),
                artist(2L, "Ana Reyes", 1, 118, 5_100_000L),
                artist(3L, "Ivy Row", 2, 143, 6_300_000L),
                artist(4L, "Marble Sun", 3, 171, 7_200_000L),
                artist(5L, "The Long Wait", 4, 205, 8_400_000L),
            )

        private fun artist(
            id: Long,
            name: String,
            cover: Int,
            plays: Int,
            timeMs: Long,
        ) = TopArtist(
            artistId = id,
            artist = name,
            playCount = plays,
            totalTimeMs = timeMs,
            uniqueTracks = 12,
            firstPlayed = FIRST_PLAYED,
            lastPlayed = LAST_PLAYED,
            imageUrl = COVER_URLS[cover],
        )

        private fun track(
            id: Long,
            title: String,
            artist: String,
            album: String,
            cover: Int,
            plays: Int,
            timeMs: Long,
        ) = TopTrack(
            trackId = id,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = COVER_URLS[cover],
            playCount = plays,
            totalTimeMs = timeMs,
            firstPlayed = FIRST_PLAYED,
            lastPlayed = LAST_PLAYED,
        )

        private val SAMPLE_OVERVIEW =
            ListeningOverview(
                totalListeningTimeMs = 4_320 * 60_000L,
                totalPlayCount = 1247,
                uniqueTracksCount = 486,
                uniqueArtistsCount = 132,
                uniqueAlbumsCount = 210,
                averageSessionDurationMs = 25 * 60_000L,
                longestSessionMs = 190 * 60_000L,
                timeRange = TimeRange.THIS_WEEK,
            )

        /**
         * One child test per card, named by the card, so `--tests` can select a
         * single one and a failure names the card it belongs to. Stills first,
         * then the scenes, matching the order the carousel shows.
         */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cards(): List<ShareThemeId> =
            ShareTheme.entries.map(ShareThemeId::Still) + ShareMotion.entries.map(ShareThemeId::Motion)
    }
}
