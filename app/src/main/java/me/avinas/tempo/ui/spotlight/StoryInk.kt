package me.avinas.tempo.ui.spotlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Colour palette a Spotlight story page draws its content with. The same pages are
 * now rendered over share-card themes, some of them light, so the white ink they
 * used to hardcode has to become a source the theme can override.
 */
@Immutable
data class StoryInk(
    /** Text, icons, and any ink drawn over the page background. */
    val ink: Color,
    /** Fill of the frosted content cards the pages draw (today `Color.White.copy(alpha = 0.08f)`). */
    val surface: Color,
    /** Border of those cards (today `Color.White.copy(alpha = 0.12f)`). */
    val border: Color,
) {
    /**
     * The playback palette. Every value is exactly what the pages drew before the
     * local existed, so watching a story stays pixel-identical.
     */
    companion object {
        val Dark = StoryInk(
            ink = Color.White,
            surface = Color.White.copy(alpha = 0.08f),
            border = Color.White.copy(alpha = 0.12f),
        )
    }
}

/**
 * Palette the current story page draws with. Pages read it only through the helpers
 * below so a share-card theme can provide its own; the default is playback's white.
 */
val LocalStoryInk = staticCompositionLocalOf { StoryInk.Dark }

/**
 * Ink for text and icons drawn over the page background. Defaults to white so
 * playback renders exactly as it always did.
 */
@Composable
fun storyInk(): Color = LocalStoryInk.current.ink

/**
 * Ink at a translucent strength — secondary copy, hairlines, soft fills. A pure
 * white ink keeps the literal alpha the pages used; a themed ink is scaled by it.
 */
@Composable
fun storyInk(alpha: Float): Color = LocalStoryInk.current.ink.let {
    if (it == Color.White) Color.White.copy(alpha = alpha) else it.copy(alpha = it.alpha * alpha)
}

/**
 * Fill of the frosted content cards. Defaults to the same white wash the pages
 * have always drawn, so playback is unchanged.
 */
@Composable
fun storySurface(): Color = LocalStoryInk.current.surface

/**
 * Border of the frosted content cards. Defaults to the same white hairline the
 * pages have always drawn, so playback is unchanged.
 */
@Composable
fun storyBorder(): Color = LocalStoryInk.current.border

/**
 * How much of the slide the page keeps clear at its top and bottom edges.
 *
 * Playback and a shared card have the same job at the top — clear the host app's
 * status area, or a story's username row — but different jobs at the bottom: on
 * screen the "Share your story" button sits there, while in a card the only thing
 * below the copy is the branding footer. Reusing playback's bottom inset inside a
 * card would leave a fifth of the exported image empty, which is why the frame is
 * a value the page reads from here instead of a constant baked into every page.
 */
@Immutable
data class StoryCardFrame(
    val top: Dp,
    val bottom: Dp,
)

/**
 * The frame the current slide is drawn in. Null means playback, where each page
 * uses its own height-derived padding as it always has.
 */
val LocalStoryCardFrame = staticCompositionLocalOf<StoryCardFrame?> { null }
