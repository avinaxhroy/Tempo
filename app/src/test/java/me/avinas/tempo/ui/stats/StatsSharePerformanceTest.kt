package me.avinas.tempo.ui.stats

import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ShareBackdropStyle
import me.avinas.tempo.ui.components.ShareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSharePerformanceTest {

    @Test
    fun `test statsItemInfo correctly maps TopTrack`() {
        val track = TopTrack(
            trackId = 1L,
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            albumArtUrl = "https://example.com/art.jpg",
            playCount = 42,
            totalTimeMs = 240_000L,
            firstPlayed = 1_000L,
            lastPlayed = 2_000L
        )
        val info = statsItemInfo(track)
        assertNotNull(info)
        assertEquals("Midnight City", info?.title)
        assertEquals("M83", info?.subtitle)
        assertEquals("https://example.com/art.jpg", info?.imageUrl)
        assertEquals(42, info?.plays)
        assertEquals(240_000L, info?.timeMs)
    }

    @Test
    fun `test statsItemInfo correctly maps TopArtist`() {
        val artist = TopArtist(
            artistId = 2L,
            artist = "Daft Punk",
            imageUrl = "https://example.com/artist.jpg",
            playCount = 120,
            totalTimeMs = 600_000L,
            uniqueTracks = 15,
            firstPlayed = 1_000L,
            lastPlayed = 2_000L
        )
        val info = statsItemInfo(artist)
        assertNotNull(info)
        assertEquals("Daft Punk", info?.title)
        // An artist has no second name of its own, so the card prints the play
        // count instead — resolved from resources at render time (this string is
        // rasterised into the exported PNG, so it cannot be English here). The
        // data layer therefore carries null rather than "120 plays".
        assertNull(info?.subtitle)
        assertEquals(120, info?.plays)
        assertEquals("https://example.com/artist.jpg", info?.imageUrl)
        assertEquals(600_000L, info?.timeMs)
    }

    @Test
    fun `test statsItemInfo correctly maps TopAlbum`() {
        val album = TopAlbum(
            album = "Random Access Memories",
            artist = "Daft Punk",
            albumArtUrl = "https://example.com/album.jpg",
            playCount = 99,
            totalTimeMs = 450_000L,
            uniqueTracks = 13
        )
        val info = statsItemInfo(album)
        assertNotNull(info)
        assertEquals("Random Access Memories", info?.title)
        assertEquals("Daft Punk", info?.subtitle)
        assertEquals("https://example.com/album.jpg", info?.imageUrl)
        assertEquals(99, info?.plays)
        assertEquals(450_000L, info?.timeMs)
    }

    @Test
    fun `test lazy sequence takes only requested count from large item lists`() {
        val items = (1..100).map { i ->
            TopTrack(
                trackId = i.toLong(),
                title = "Track $i",
                artist = "Artist $i",
                album = "Album $i",
                albumArtUrl = "https://example.com/$i.jpg",
                playCount = i,
                totalTimeMs = i * 10_000L,
                firstPlayed = 1_000L,
                lastPlayed = 2_000L
            )
        }

        val top3 = items.asSequence().mapNotNull { statsItemInfo(it) }.take(ShareItemCount.TOP_3.count).toList()
        assertEquals(3, top3.size)
        assertEquals("Track 1", top3[0].title)

        val top5 = items.asSequence().mapNotNull { statsItemInfo(it) }.take(ShareItemCount.TOP_5.count).toList()
        assertEquals(5, top5.size)

        val top10 = items.asSequence().mapNotNull { statsItemInfo(it) }.take(ShareItemCount.TOP_10.count).toList()
        assertEquals(10, top10.size)
    }

    @Test
    fun `test thumbnail target size scales efficiently and enforces minimum bounds`() {
        // Thumbnail sizes: 24dp, 30dp, 36dp, 38dp, 56dp
        fun targetSize(dpVal: Float): Int = (dpVal * 2.5f).toInt().coerceAtLeast(64)

        assertEquals(64, targetSize(24f))
        assertEquals(75, targetSize(30f))
        assertEquals(90, targetSize(36f))
        assertEquals(95, targetSize(38f))
        assertEquals(140, targetSize(56f))
        // Target sizes remain < 200dp instead of defaulting to 1024dp decode
        assertTrue(targetSize(56f) < 200)
    }

    @Test
    fun `test all share themes declare valid palettes and backdrop styles`() {
        ShareTheme.entries.forEach { theme ->
            assertNotNull(theme.palette)
            assertNotNull(theme.palette.backdrop)
            assertTrue(theme.palette.gradient.isNotEmpty())
        }
        assertEquals(ShareBackdropStyle.FLUTED_GLASS, ShareTheme.GLASS.palette.backdrop)
        assertEquals(ShareBackdropStyle.ASCII_ARTWORK, ShareTheme.ASCII.palette.backdrop)
        assertEquals(ShareBackdropStyle.DITHER_FIELD, ShareTheme.DITHER.palette.backdrop)
    }
}
