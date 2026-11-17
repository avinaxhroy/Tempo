package me.avinas.tempo.ui.spotlight

import androidx.compose.ui.graphics.Color
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.share.CardChoice
import me.avinas.tempo.ui.components.share.ShareCarouselOrder
import me.avinas.tempo.ui.components.share.ShareChoice
import me.avinas.tempo.ui.components.share.ShareMotion
import me.avinas.tempo.ui.components.share.ShareThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The story's share strip is built from the theme enums rather than written out,
 * so these tests guard the two things that decide what a user can pick: the story
 * backdrop must come first, and Midnight must not be in the list.
 */
class StoryShareChoicesTest {

    private val colors = SpotlightStoryColors(
        primary = Color(0xFF6366F1),
        secondary = Color(0xFF818CF8),
        tertiary = Color(0xFF4F46E5),
    )

    @Test
    fun `the story backdrop is the first choice`() {
        // The sheet opens on the first entry, and the user opens it while looking
        // at their own backdrop — so the strip has to start there, not on a card.
        val first = storyShareChoices(colors).first()
        assertTrue("Expected the story choice, got ${first.key}", first is StoryShareChoice)
    }

    @Test
    fun `midnight is not offered`() {
        // Midnight is the photo card: blurred artwork under glow orbs. A story
        // slide has no artwork to blur, so it renders as a plain dark gradient —
        // the least interesting entry in the strip and a near-duplicate of its
        // neighbour. The story backdrop takes its place.
        val ids = storyShareChoices(colors).mapNotNull { it.cardThemeId }
        assertTrue(
            "Midnight should not be selectable in the story sheet",
            ids.none { it == ShareThemeId.Still(ShareTheme.MIDNIGHT) },
        )
    }

    @Test
    fun `every other still theme is offered`() {
        // Built from the enum, so a theme added later appears without anyone
        // remembering to add it — and this fails if someone writes a list again.
        val stills = storyShareChoices(colors)
            .mapNotNull { it.cardThemeId }
            .filterIsInstance<ShareThemeId.Still>()
            .map { it.theme }
        assertEquals(
            ShareTheme.entries.filter { it != ShareTheme.MIDNIGHT }.toSet(),
            stills.toSet(),
        )
    }

    @Test
    fun `every scene is offered`() {
        val motions = storyShareChoices(colors)
            .mapNotNull { it.cardThemeId }
            .filterIsInstance<ShareThemeId.Motion>()
            .map { it.motion }
        assertEquals(ShareMotion.entries.toSet(), motions.toSet())
    }

    @Test
    fun `the story backdrop paints itself`() {
        // Null is what tells the sheet "this page draws its own backdrop", which
        // is also its answer to "cannot be exported as video".
        val story = storyShareChoices(colors).first() as StoryShareChoice
        assertNull(story.cardThemeId)
        assertEquals(StoryShareChoice.KEY, story.key)
    }

    @Test
    fun `no choice is offered twice`() {
        // The strip's selection is compared by key, so a duplicate key would make
        // two pages highlight as the selected one.
        val choices: List<ShareChoice> = storyShareChoices(colors)
        assertEquals(choices.size, choices.map { it.key }.toSet().size)
    }

    @Test
    fun `a scene is never more than one page from the still it follows`() {
        // The complaint this fixes: the strip ran every still theme and then every
        // scene, so the first half of a swipe read as "this story only offers
        // posters". Motion and still have to be mixed all the way along, not
        // sorted into two blocks.
        val kinds = storyShareChoices(colors).map { it.cardThemeId is ShareThemeId.Motion }
        kinds.forEachIndexed { index, isMotion ->
            val next = kinds[(index + 1) % kinds.size]
            assertTrue(
                "Two stills next to each other at $index: the strip must mix scenes in.",
                isMotion || next,
            )
        }
    }

    @Test
    fun `the strip runs the carousel order with the backdrop in midnight's slot`() {
        // One ordering for every share surface: the story does not re-sort the
        // themes, it only swaps the one card theme it cannot draw for its own
        // backdrop. Sharing the order is what keeps the pairing of a still theme
        // with its scenes — the thing that makes a swipe legible — identical here.
        val expected = ShareCarouselOrder.map {
            if (it == ShareThemeId.Still(ShareTheme.MIDNIGHT)) StoryShareChoice.KEY else it.key
        }
        assertEquals(expected, storyShareChoices(colors).map { it.key })
    }

    @Test
    fun `card choices read their key from the theme id`() {
        val choice = CardChoice(ShareThemeId.Still(ShareTheme.GLASS))
        assertEquals(ShareThemeId.Still(ShareTheme.GLASS).key, choice.key)
        assertEquals(ShareThemeId.Still(ShareTheme.GLASS), choice.cardThemeId)
    }
}
