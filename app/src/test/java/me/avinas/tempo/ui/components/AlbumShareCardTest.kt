package me.avinas.tempo.ui.components

import me.avinas.tempo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The album card is the newest member of the share family; these pin the two
 * decisions it makes about *what* to say, because both of them are choices to
 * stay quiet rather than to print something.
 */
class AlbumShareCardTest {
    @Test
    fun `standing kicker stays silent for an album with nothing to its name`() {
        // Two plays, half finished: the cover is the opener, no pill.
        assertNull(resolveAlbumStandingKicker(playCount = 2, completionRate = 50.0))
    }

    @Test
    fun `standing kicker names a record played through`() {
        val complete = resolveAlbumStandingKicker(playCount = 12, completionRate = 97.0)
        assertEquals(R.string.share_kicker_album_complete, complete?.id)
    }

    @Test
    fun `standing kicker orders volume above completion`() {
        val century = resolveAlbumStandingKicker(playCount = 120, completionRate = 40.0)
        assertEquals(R.string.share_kicker_album_century, century?.id)

        val rotation = resolveAlbumStandingKicker(playCount = 45, completionRate = 40.0)
        assertEquals(R.string.share_kicker_album_rotation, rotation?.id)
    }

    @Test
    fun `completion highlight needs plays to be worth printing`() {
        // A record opened once and not finished is not a flex.
        assertNull(resolveAlbumFlexHighlight(completionRate = 100.0, playCount = 2))
    }

    @Test
    fun `completion highlight prints the rate once it clears half`() {
        val highlight = resolveAlbumFlexHighlight(completionRate = 82.4, playCount = 20)
        assertEquals(R.string.share_highlight_album_completion, highlight?.id)
        assertEquals(listOf(82), highlight?.args)
    }

    @Test
    fun `completion highlight stays silent below the threshold`() {
        assertNull(resolveAlbumFlexHighlight(completionRate = 55.0, playCount = 20))
    }
}
