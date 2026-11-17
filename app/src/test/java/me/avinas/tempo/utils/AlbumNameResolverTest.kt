package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Album names come from whatever the playing app exposes, and a track's album used to be
 * written once at first listen and never corrected. Notification sub-text/info-text is the
 * usual fallback source, and those slots carry the artist, the app's own name, or
 * "Now playing" just as often as a release title — which is how songs ended up under albums
 * they do not belong to.
 *
 * [AlbumNameResolver] decides what may be stored as an album, so every capture and enrichment
 * write site can reject a bogus value and adopt a real one later.
 */
class AlbumNameResolverTest {
    @Test
    fun `real release names pass through unchanged`() {
        val cases =
            listOf(
                "WDTBU",
                "Karma & Uptown Slick",
                "Abbey Road (Remastered)",
                "1989 (Taylor's Version)",
                "The Dark Side of the Moon",
                "OK Computer",
                "18 Months",
                "Songs in the Key of Life",
                "Album Title: A Very Long Subtitle",
                "Émoji Album 💿",
            )
        for (album in cases) {
            assertEquals(album, AlbumNameResolver.clean(album, "Some Song", "Some Artist"))
        }
    }

    @Test
    fun `values that merely repeat the track or artist are rejected`() {
        // The common notification misfire: SUB_TEXT is the artist when no album is published.
        assertNull(AlbumNameResolver.clean("Karma & Uptown Slick", "Lotus", "Karma & Uptown Slick"))
        assertNull(AlbumNameResolver.clean("Lotus", "Lotus", "Karma & Uptown Slick"))
        // Case and punctuation must not rescue it.
        assertNull(AlbumNameResolver.clean("KARMA & UPTOWN SLICK", "Lotus", "Karma & Uptown Slick"))
        assertNull(AlbumNameResolver.clean("Karma & Uptown Slick!", "Lotus", "Karma & Uptown Slick"))
        assertNull(AlbumNameResolver.clean("lotus", "Lotus", "Karma & Uptown Slick"))
    }

    @Test
    fun `placeholders and app names are rejected`() {
        val rejected =
            listOf(
                "",
                "   ",
                "Unknown",
                "Unknown Album",
                "No album",
                "None",
                "N/A",
                "-",
                "Now Playing",
                "Spotify",
                "YouTube Music",
                "Apple Music",
                "SoundCloud",
                "https://open.spotify.com/album/xyz",
            )
        for (album in rejected) {
            assertNull("expected '$album' to be rejected", AlbumNameResolver.clean(album, "Song", "Artist"))
        }
    }

    @Test
    fun `descriptions are rejected`() {
        assertNull(
            AlbumNameResolver.clean(
                "Now playing from your Daily Mix",
                "Song",
                "Artist",
            ),
        )
        assertNull(AlbumNameResolver.clean("Official audio", "Song", "Artist"))
        assertNull(
            AlbumNameResolver.clean(
                "a".repeat(151),
                "Song",
                "Artist",
            ),
        )
    }

    @Test
    fun `album prefixes and stray quotes are stripped`() {
        assertEquals("Discovery", AlbumNameResolver.clean("Album: Discovery", "One More Time", "Daft Punk"))
        assertEquals("Discovery", AlbumNameResolver.clean("\u201CDiscovery\u201D", "One More Time", "Daft Punk"))
        assertEquals("Discovery", AlbumNameResolver.clean("  Album -  Discovery ", "One More Time", "Daft Punk"))
    }

    @Test
    fun `valid album is kept over a later candidate`() {
        assertEquals(
            "WDTBU",
            AlbumNameResolver.choose(
                existing = "WDTBU",
                candidate = "Something Else",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
    }

    @Test
    fun `junk or blank album is replaced by a valid candidate`() {
        // This is the write-once bug: a track first seen with no album (or with the artist in
        // the album slot) must adopt the real release once the player exposes it.
        assertEquals(
            "WDTBU",
            AlbumNameResolver.choose(
                existing = null,
                candidate = "WDTBU",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
        assertEquals(
            "WDTBU",
            AlbumNameResolver.choose(
                existing = "Karma & Uptown Slick",
                candidate = "WDTBU",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
        assertEquals(
            "WDTBU",
            AlbumNameResolver.choose(
                existing = "Unknown",
                candidate = "WDTBU",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
    }

    @Test
    fun `choose never invents an album from a junk candidate`() {
        assertNull(
            AlbumNameResolver.choose(
                existing = null,
                candidate = "Spotify",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
        assertNull(
            AlbumNameResolver.choose(
                existing = "   ",
                candidate = "Karma & Uptown Slick",
                title = "Lotus",
                artist = "Karma & Uptown Slick",
            ),
        )
    }

    @Test
    fun `validity check agrees with clean`() {
        assertTrue(AlbumNameResolver.isValid("WDTBU", "Lotus", "Karma & Uptown Slick"))
        assertFalse(AlbumNameResolver.isValid("Spotify", "Lotus", "Karma & Uptown Slick"))
        assertFalse(AlbumNameResolver.isValid(null, "Lotus", "Karma & Uptown Slick"))
    }
}
