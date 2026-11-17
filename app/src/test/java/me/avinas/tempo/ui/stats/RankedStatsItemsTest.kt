package me.avinas.tempo.ui.stats

import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.ui.components.share.ShareItemCount
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking is what both the card and the sheet's palette extraction read, so
 * "which items, in what order" is a contract between two composables rather than
 * an implementation detail of one.
 */
class RankedStatsItemsTest {

    private fun track(index: Int) =
        TopTrack(
            trackId = index.toLong(),
            title = "Track $index",
            artist = "Artist $index",
            album = "Album $index",
            albumArtUrl = "https://example.com/$index.jpg",
            playCount = 100 - index,
            totalTimeMs = index * 10_000L,
            firstPlayed = 1_000L,
            lastPlayed = 2_000L,
        )

    @Test
    fun `keeps the order it was given`() {
        // The caller has already ranked: the stats screen hands over the list top
        // first. Sorting here would silently reorder an "all time" chart by play
        // count and hide the tie-breaking the screen did.
        val ranked = rankedStatsItems(listOf(track(1), track(2), track(3)), ShareItemCount.TOP_5)
        assertEquals(listOf("Track 1", "Track 2", "Track 3"), ranked.map { it.title })
    }

    @Test
    fun `stops at the requested count`() {
        val items = (1..20).map { track(it) }
        ShareItemCount.entries.forEach { count ->
            assertEquals(count.count, rankedStatsItems(items, count).size)
        }
    }

    @Test
    fun `a shorter list is not padded`() {
        // "Top 10" over a library of three songs is three rows, not three rows and
        // seven blanks: the card's layouts size themselves to what they are given.
        val ranked = rankedStatsItems(listOf(track(1), track(2)), ShareItemCount.TOP_10)
        assertEquals(2, ranked.size)
    }

    @Test
    fun `unprintable items are dropped rather than rendered blank`() {
        val mixed = listOf(track(1), "not an item", track(2))
        val ranked = rankedStatsItems(mixed, ShareItemCount.TOP_5)
        assertEquals(listOf("Track 1", "Track 2"), ranked.map { it.title })
    }

    @Test
    fun `the first entry is the sheet's colour source`() {
        // The sheet takes `artworkUrls = ranked.map { it.imageUrl }`, so #1's cover
        // shades the scene. If the two rankings ever diverge the card would print
        // cover A on a scene mixed from cover B.
        val ranked = rankedStatsItems(listOf(track(7), track(3)), ShareItemCount.TOP_3)
        assertEquals("https://example.com/7.jpg", ranked.first().imageUrl)
    }

    @Test
    fun `every item kind maps to something printable`() {
        val artist =
            TopArtist(
                artistId = 1L,
                artist = "M83",
                imageUrl = "https://example.com/a.jpg",
                playCount = 5,
                totalTimeMs = 1_000L,
                uniqueTracks = 2,
                firstPlayed = 1L,
                lastPlayed = 2L,
            )
        val album =
            TopAlbum(
                album = "Hurry Up",
                artist = "M83",
                albumArtUrl = "https://example.com/b.jpg",
                playCount = 3,
                totalTimeMs = 900L,
                uniqueTracks = 1,
            )
        val ranked = rankedStatsItems(listOf(track(1), artist, album), ShareItemCount.TOP_5)
        assertEquals(
            listOf("Track 1", "M83", "Hurry Up"),
            ranked.map { it.title },
        )
        assertTrue("An artist keeps its play count for the subtitle", ranked[1].plays == 5)
    }
}
