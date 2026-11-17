package me.avinas.tempo.ui.stats

import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.stats.StatItem
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsUiStateTest {

    @Test
    fun `test StatItem interface contract across all top stat models`() {
        val track: StatItem = TopTrack(
            trackId = 101L,
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            albumArtUrl = "https://example.com/art.jpg",
            playCount = 42,
            totalTimeMs = 240_000L,
            firstPlayed = 1_000L,
            lastPlayed = 2_000L
        )
        val artist: StatItem = TopArtist(
            artistId = 202L,
            artist = "M83",
            playCount = 100,
            totalTimeMs = 600_000L,
            uniqueTracks = 10,
            firstPlayed = 1_000L,
            lastPlayed = 2_000L
        )
        val album: StatItem = TopAlbum(
            album = "Hurry Up, We're Dreaming",
            artist = "M83",
            albumArtUrl = "https://example.com/art.jpg",
            playCount = 50,
            totalTimeMs = 300_000L,
            uniqueTracks = 5
        )

        // Membership in the sealed interface is a compile-time fact; assert that each
        // model stays reachable through StatItem and serves its derived contract.
        assertEquals(TopTrack::class, track::class)
        assertEquals(TopArtist::class, artist::class)
        assertEquals(TopAlbum::class, album::class)
        assertEquals(4L, (track as TopTrack).totalTimeMinutes)
        assertEquals(1.0 / 6.0, (artist as TopArtist).totalTimeHours, 1e-9)
        assertEquals(5L, (album as TopAlbum).totalTimeMinutes)
    }

    @Test
    fun `test identity-based key generation ensures stability without index baking`() {
        fun computeKey(item: StatItem): String = when (item) {
            is TopTrack -> "track_${item.trackId}"
            is TopArtist -> "artist_${item.artistId ?: item.artist}"
            is TopAlbum -> "album_${item.album}_${item.artist}"
        }

        val track = TopTrack(1L, "Song", "Artist", null, null, 1, 1000L, 0L, 0L)
        val artistWithId = TopArtist(artistId = 10L, artist = "Artist", playCount = 1, totalTimeMs = 1000L, uniqueTracks = 1, firstPlayed = 0L, lastPlayed = 0L)
        val artistWithoutId = TopArtist(artistId = null, artist = "Unknown Artist", playCount = 1, totalTimeMs = 1000L, uniqueTracks = 1, firstPlayed = 0L, lastPlayed = 0L)
        val album = TopAlbum(album = "Album", artist = "Artist", albumArtUrl = null, playCount = 1, totalTimeMs = 1000L, uniqueTracks = 1)

        assertEquals("track_1", computeKey(track))
        assertEquals("artist_10", computeKey(artistWithId))
        assertEquals("artist_Unknown Artist", computeKey(artistWithoutId))
        assertEquals("album_Album_Artist", computeKey(album))
    }

    @Test
    fun `test item deduplication during pagination prevents duplicate keys in LazyColumn`() {
        fun itemKey(item: StatItem): String = when (item) {
            is TopTrack -> "track_${item.trackId}"
            is TopArtist -> "artist_${item.artistId ?: item.artist}"
            is TopAlbum -> "album_${item.album}_${item.artist}"
        }

        val page0 = listOf(
            TopTrack(1L, "Song 1", "Artist", null, null, 10, 1000L, 0L, 0L),
            TopTrack(2L, "Song 2", "Artist", null, null, 8, 800L, 0L, 0L)
        )
        // Overlap: Song 2 shifted into page 1 due to live play counts
        val page1 = listOf(
            TopTrack(2L, "Song 2", "Artist", null, null, 8, 800L, 0L, 0L),
            TopTrack(3L, "Song 3", "Artist", null, null, 5, 500L, 0L, 0L)
        )

        val combined = (page0 + page1).distinctBy { itemKey(it) }

        assertEquals(3, combined.size)
        assertEquals(listOf(1L, 2L, 3L), combined.map { (it as TopTrack).trackId })
    }

    @Test
    fun `test StatsUiState defaults and state copy`() {
        val state = StatsUiState()

        assertTrue(state.isLoading)
        assertFalse(state.isLoadingMore)
        assertFalse(state.isRefreshing)
        assertEquals(StatsTab.TOP_SONGS, state.selectedTab)
        assertEquals(TimeRange.THIS_WEEK, state.selectedTimeRange)
        assertEquals(SortBy.COMBINED_SCORE, state.selectedSortBy)
        assertTrue(state.items.isEmpty())

        val updated = state.copy(
            isLoading = false,
            items = listOf(TopTrack(1L, "Song", "Artist", null, null, 1, 1000L, 0L, 0L))
        )
        assertFalse(updated.isLoading)
        assertEquals(1, updated.items.size)
    }
}
