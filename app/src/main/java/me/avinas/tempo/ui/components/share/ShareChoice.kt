package me.avinas.tempo.ui.components.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R

/**
 * One pickable page in a share sheet's theme strip.
 *
 * The strip used to be typed to [ShareThemeId], which was true while every sheet
 * in the app shared a card. The spotlight story breaks that: its default backdrop
 * is not a card theme at all — it is the story's own animated background, drawn
 * from the page's narrative colours, and no [me.avinas.tempo.ui.components.ShareTheme]
 * reproduces it.
 *
 * Rather than fork the carousel — and with it the capture source, the video
 * exporter, the gallery write and the tour — the sheet asks only what it needs
 * from the thing the user picked: which card theme paints it, if any. A choice
 * that paints itself answers null.
 */
interface ShareChoice {
    /** Stable identity: what "is this the selected one" compares. */
    val key: String

    /**
     * The card theme this choice is painted by, or null when the choice draws
     * its own backdrop.
     *
     * Null is also the answer to "can this be exported as video": a backdrop this
     * app draws frame by frame in Compose cannot be shaded by the motion
     * exporter, so a null card theme exports as a still.
     */
    val cardThemeId: ShareThemeId?

    /**
     * The colour the caption under the carousel is set in.
     *
     * Read from the same palette the page inks itself with, so the caption cannot
     * announce a colour the page never painted.
     */
    val accent: Color

    /** The caption line under the carousel. */
    @Composable fun label(): String

    /**
     * The second half of the caption — what this choice draws, in one line.
     *
     * Asked of the choice rather than worked out by the carousel, because only
     * the choice knows what it is: a card theme describes its scene, and the
     * story describes its own backdrop. A choice that has nothing to add
     * returns null and the caption is its name alone.
     *
     * @param motionEnabled false when this device cannot encode a scene, so a
     *   scene says it will share as a still instead of describing itself.
     */
    @Composable fun captionSuffix(motionEnabled: Boolean): String? = null
}

/**
 * One rendered page of a share sheet: what was picked, and the card theme that
 * paints it.
 *
 * The sheet hands this to its content slot instead of the bare theme id, because
 * a page may need to know which *choice* it is drawing — the story draws the same
 * slide on seven different backdrops and one of them is not a card theme at all.
 * [card] is always present so a card can be written without unwrapping: for a
 * self-painting choice it is that choice's fallback theme, and the choice itself
 * is what tells the page to ignore it.
 */
@Immutable
data class SharePage(
    val choice: ShareChoice,
    val card: ShareCardTheme,
)

/**
 * A card theme offered as a choice.
 *
 * This is the only adapter between the strip and the theme enum, so "every card
 * theme is pickable" stays a one-line fact rather than a mapping table that a
 * new theme could be left out of.
 */
@Immutable
internal data class CardChoice(val id: ShareThemeId) : ShareChoice {
    override val key: String get() = id.key
    override val cardThemeId: ShareThemeId get() = id
    override val accent: Color
        get() =
            id.paletteTheme.palette.accent.let { ink ->
                // MINIMUM and DITHER are black ink on paper, and the caption sits
                // on the sheet's dark scrim — the theme's own colour was invisible
                // there ("Halftone" printed black on black). Lift only the
                // near-black inks to a readable tint of the same hue; accents
                // that already carry on black keep their exact colour.
                if (luma(ink) < 0.08f) mixColors(ink, Color.White, 0.72f) else ink
            }

    @Composable
    override fun label(): String = id.label()

    @Composable
    override fun captionSuffix(motionEnabled: Boolean): String? {
        // A still theme gets no second half: "the theme's own backdrop, held
        // still" only restates the card the user is looking at.
        val scene = id.motion ?: return null
        return if (motionEnabled) {
            motionCaption(scene)
        } else {
            // A scene this device cannot encode is still previewable but not
            // shareable, and that gap has to be said here — it is the only
            // place the user is told before tapping a button that will
            // silently export a still.
            stringResource(R.string.stats_share_motion_unavailable)
        }
    }
}

/**
 * The card theme to draw this choice in, at [time], sampling [artwork].
 *
 * Every page draws as a card even when its choice paints itself: the sheet needs
 * *some* theme to answer colour questions with (the caption's accent, the
 * export's scene palette) before a page has had a chance to ignore it. A
 * self-painting choice falls back to [DefaultShareThemeId] and is expected to
 * disregard the result — if it does not, it is a card, not a backdrop.
 */
fun ShareChoice.cardAt(
    time: MotionTime = MotionTime.Still,
    artwork: ArtworkPalette? = null,
): ShareCardTheme = (cardThemeId ?: DefaultShareThemeId).at(time, artwork)

