package me.avinas.tempo.ui.spotlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.share.CardChoice
import me.avinas.tempo.ui.components.share.ShareCarouselOrder
import me.avinas.tempo.ui.components.share.ShareChoice
import me.avinas.tempo.ui.components.share.ShareThemeId

/**
 * The story's own backdrop, offered as one of the choices in its share sheet.
 *
 * This is the reason [ShareChoice] exists. Every other share surface in the app
 * shares a card, and its strip is a list of card themes. The story's default look
 * is not a card theme at all: it is [SpotlightStoryBackground], three drifting
 * blobs in the current slide's narrative colours, and no [ShareTheme] reproduces
 * it. It has to be pickable — it is what the user is looking at when they tap
 * Share — so a choice may say "I paint myself" and the sheet stops asking which
 * card theme draws it.
 *
 * A self-painted choice cannot be exported as a video: the exporter shades a
 * scene into a surface it controls, and this backdrop is drawn frame by frame in
 * Compose. It therefore shares as a still, and says so only by which page it is.
 */
@Immutable
internal class StoryShareChoice(
    private val colors: SpotlightStoryColors,
) : ShareChoice {
    override val key: String get() = KEY

    /**
     * Null: nothing outside this package paints it.
     *
     * The sheet reads this as "the page draws its own backdrop", which is also
     * its answer to whether the choice can become a video.
     */
    override val cardThemeId: ShareThemeId? get() = null

    /** The slide's own secondary hue, so the caption belongs to the backdrop. */
    override val accent: Color get() = colors.secondary

    @Composable
    override fun label(): String = stringResource(R.string.share_theme_story)

    @Composable
    override fun captionSuffix(motionEnabled: Boolean): String =
        stringResource(R.string.share_theme_story_caption)

    companion object {
        const val KEY: String = "story:backdrop"
    }
}

/**
 * The pages the story's share sheet offers: its own backdrop in Midnight's slot,
 * and then the carousel's order with that one substitution.
 *
 * Midnight is the one still theme left out. It is the photo card — blurred
 * artwork under glow orbs — and the story has no artwork to blur: on a story
 * slide it renders as a plain dark gradient, which is both the least interesting
 * option in the strip and indistinguishable from the theme next to it. The story
 * backdrop takes its place in the slot Midnight would have held, so the backdrop
 * arrives first — where a user looks for "the one I was already looking at" —
 * and Midnight's two scenes follow it, the way every scene follows the theme it
 * is seeded from.
 *
 * The order is [ShareCarouselOrder] with that substitution rather than a second
 * ordering written out here. The strip used to run every still theme and then
 * every scene, which made the first half of a swipe look like the story only
 * offers posters. A still theme and the scenes seeded from it stay paired here.
 * Because the order is derived from the enums, a theme or scene added later
 * appears without anyone remembering to add it.
 */
internal fun storyShareChoices(colors: SpotlightStoryColors): List<ShareChoice> {
    // Midnight's slot, not its card: the backdrop opens the strip and Midnight's
    // two scenes follow it exactly as they follow a still theme.
    val slot = ShareThemeId.Still(ShareTheme.MIDNIGHT)
    val story = StoryShareChoice(colors)
    return ShareCarouselOrder.map { id -> if (id == slot) story else CardChoice(id) }
}

/**
 * The story backdrop as a choice, remembered against the slide's colours so a
 * page change does not rebuild the strip's first entry on every recomposition.
 */
@Composable
internal fun rememberStoryShareChoices(colors: SpotlightStoryColors): List<ShareChoice> =
    remember(colors) { storyShareChoices(colors) }

/** The first choice of the strip: the story's own backdrop. */
@Composable
internal fun rememberStoryShareChoice(colors: SpotlightStoryColors): ShareChoice =
    remember(colors) { StoryShareChoice(colors) }
