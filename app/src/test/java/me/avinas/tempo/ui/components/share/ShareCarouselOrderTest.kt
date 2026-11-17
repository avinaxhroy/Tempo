package me.avinas.tempo.ui.components.share

import me.avinas.tempo.ui.components.ShareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carousel's running order used to be thirteen hand-written ids, i.e. a
 * second copy of "every theme there is" that nothing kept in sync with the
 * enums. It is derived now, so these tests are what stop that from silently
 * regressing into a partial strip.
 */
class ShareCarouselOrderTest {

    @Test
    fun `every theme in the app is reachable in the strip`() {
        val expected = ShareTheme.entries.map { ShareThemeId.Still(it) as ShareThemeId } +
            ShareMotion.entries.map { ShareThemeId.Motion(it) }
        assertEquals(
            "A theme that is not in the strip cannot be chosen, so it can never be shared.",
            expected.toSet(),
            ShareCarouselOrder.toSet(),
        )
        assertEquals("No theme may appear twice.", expected.size, ShareCarouselOrder.size)
    }

    @Test
    fun `no two still themes are adjacent`() {
        // The user's constraint: a swipe must never look like the carousel only
        // offers posters. Seven scenes to six themes means one scene-next-to-scene
        // adjacency is unavoidable in a loop; two stills in a row is not.
        val kinds = ShareCarouselOrder.map { it is ShareThemeId.Motion }
        kinds.forEachIndexed { index, isMotion ->
            val next = kinds[(index + 1) % kinds.size]
            assertTrue(
                "Two stills next to each other at $index in $ShareCarouselOrder",
                isMotion || next,
            )
        }
    }

    @Test
    fun `the loop seam is not two of a kind`() {
        // The strip loops, so the last page and the first are neighbours even
        // though nothing in the list says so.
        val first = ShareCarouselOrder.first()
        val last = ShareCarouselOrder.last()
        assertNotEquals(
            "Wrapping from $last to $first puts two of a kind next to each other.",
            last is ShareThemeId.Motion,
            first is ShareThemeId.Motion,
        )
    }

    @Test
    fun `a scene follows the still theme it is seeded from`() {
        // The pairing is what makes a swipe legible: the scene is the card the
        // user is already looking at, in motion. Scenes are grouped directly
        // after their seed, so every scene is either right after its seed theme
        // or right after another scene of the same seed.
        ShareCarouselOrder.forEachIndexed { index, id ->
            val motion = id.motion ?: return@forEachIndexed
            val previous = ShareCarouselOrder[(index - 1 + ShareCarouselOrder.size) % ShareCarouselOrder.size]
            assertEquals(
                "$motion is seeded from ${motion.seedTheme}, so it should sit with it, not after $previous.",
                motion.seedTheme,
                previous.paletteTheme,
            )
        }
    }

    @Test
    fun `the start page lands on the requested index`() {
        // The pager is endless and opens in the middle of its range; an off-by-one
        // here silently opens the sheet on the neighbouring theme.
        ShareCarouselOrder.indices.forEach { index ->
            val page = carouselStartPage(index, ShareCarouselOrder.size)
            assertEquals(index, page % ShareCarouselOrder.size)
            assertTrue("Page $page for index $index is too close to the start", page > 1_000_000)
        }
    }
}
