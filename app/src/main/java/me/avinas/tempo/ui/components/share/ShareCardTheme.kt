package me.avinas.tempo.ui.components.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.ShareThemePalette

/**
 * The card a share sheet opens on.
 *
 * Single-sourced because "which theme does the app consider default" is not a
 * decision each screen should be free to answer differently: a user who opens
 * the song sheet after the stats sheet should not find the carousel parked on a
 * different card. The carousel order and every sheet read this.
 */
val DefaultShareTheme: ShareTheme = ShareTheme.MIDNIGHT

/** The default theme as a carousel id — i.e. "no scene, [DefaultShareTheme]". */
val DefaultShareThemeId: ShareThemeId = ShareThemeId.Still(DefaultShareTheme)

/**
 * Everything a share card needs to know about how it is printed, as one value.
 *
 * A card used to take four separate parameters for this — `theme`, `motion`,
 * `motionTime`, `artworkPalette` — and every one of the five cards declared them,
 * every one of the five call sites passed them, and the sheet's capture source
 * passed them a second time. Four parameters that must always travel together
 * are one parameter, and the fact that they travelled separately is why the
 * song/artist/badge cards were once impossible to animate while the stats card
 * was not: their copies of the contract had simply lost two of the four.
 *
 * @param id the selected theme. A [ShareThemeId.Motion] means the card is printed
 *   on a scene; a [ShareThemeId.Still] means its own backdrop, held still.
 * @param motionTime the clock the scene is drawn at. [MotionTime.Still] for the
 *   exported frame, the sheet's running clock for the preview.
 * @param artwork palette of the sheet's subject artwork, when there is any. A
 *   scene takes its colours from it; a card with a procedural backdrop (badge,
 *   milestone) has none and seeds from the theme alone.
 */
@Immutable
data class ShareCardTheme(
    val id: ShareThemeId = DefaultShareThemeId,
    val motionTime: MotionTime = MotionTime.Still,
    val artwork: ArtworkPalette? = null,
) {
    /** The scene, or null when the card is a still theme. */
    val motion: ShareMotion? get() = id.motion

    /**
     * The still theme that supplies type, shapes and weights. For a scene this is
     * its seed theme — a moving theme is a surface, not a different typography.
     */
    val theme: ShareTheme get() = id.paletteTheme

    companion object {
        /** The card the app opens on: no scene, [DefaultShareTheme]. */
        val Default = ShareCardTheme()
    }
}

/**
 * This theme at a moment, for a given artwork — what a sheet passes to a card
 * once it knows which slide is on screen and what its own clock reads.
 */
fun ShareThemeId.at(
    time: MotionTime = MotionTime.Still,
    artwork: ArtworkPalette? = null,
): ShareCardTheme = ShareCardTheme(id = this, motionTime = time, artwork = artwork)

/**
 * The colours one card is printed with, worked out once for the whole sheet.
 *
 * A card's palette is asked for in three places — the card's own type, the
 * exporter's shading, and the caption under the carousel — and they must agree,
 * because they describe the same frame. They used to be derived separately at
 * each of those places, which is why the caption could announce a theme accent
 * that the card itself never painted.
 */
@Immutable
data class ShareCardPalettes(
    /**
     * What the card's type, rules and shapes are drawn in. For a scene this is
     * the seed theme's palette with its accent re-derived from the scene.
     */
    val ink: ShareThemePalette,
    /** What the exporter shades the scene with. */
    val scene: MotionPalette,
    /**
     * The seed theme's own accent, unmodified: the caption sits on the sheet's
     * dark scrim rather than on the card, so it wants the theme's colour, not
     * the one mixed for the card's ink.
     */
    val seedAccent: Color,
)

/**
 * Raises a theme's own type weight to [floor] when it was inked lighter.
 *
 * MINIMUM sets its headlines in Light and its labels in Normal because it draws
 * on a flat cream sheet, where thin ink is the whole point. A scene is not that
 * sheet: the halftone's masses run from paper to near-black, and the same Light
 * headline that is elegant on stock dissolves into the print. Only weights the
 * theme already declares are raised — a theme that leaves them null keeps the
 * card's bold defaults, so this cannot lighten a dark theme's type.
 */
private fun legibleWeight(weight: FontWeight?, floor: FontWeight): FontWeight? =
    when {
        weight == null -> null
        weight.weight < floor.weight -> floor
        else -> weight
    }

/**
 * Derives [ShareCardPalettes] for [card] and remembers them.
 *
 * The ink rule lives here rather than in the surface because a moving theme
 * seeds its typography from a still theme whose accent was picked against a
 * flat backdrop: ASCII's green over a cover's warm glyph field reads as a
 * mistake, and the halftone's amber disappears into its own print. So a scene's
 * accent and #1 tint are re-mixed from the scene's own primary — and a card that
 * skips this (there is only one, and it is this function) would paint the
 * seed's accent over a scene that never contained it.
 */
@Composable
fun rememberShareCardPalettes(card: ShareCardTheme): ShareCardPalettes {
    val theme = card.theme
    val motion = card.motion
    val themePalette = theme.palette
    val scene =
        remember(motion, theme, card.artwork) {
            paletteFor(motion?.seedTheme ?: theme, card.artwork)
        }
    val ink =
        remember(themePalette, scene, motion) {
            if (motion == null) {
                themePalette
            } else {
                val accent =
                    if (themePalette.isDark) {
                        mixColors(scene.primary, scene.crest, 0.45f)
                    } else {
                        mixColors(scene.primary, scene.deep, 0.35f)
                    }
                themePalette.copy(
                    accent = accent,
                    rank1Tint = accent,
                    overScene = true,
                    // A scene that is printed as a ruled page says so itself;
                    // the rest keep the page their seed theme was drawn on.
                    layoutStyle = motion.layoutStyle ?: themePalette.layoutStyle,
                    // A scene is busier than any paper the still palettes were
                    // set for: the thin register that reads as print on stock
                    // reads as a smudge over a dot field. Lift only what the
                    // theme itself declared, so themes on the card's bold
                    // defaults keep them.
                    headlineWeight = legibleWeight(themePalette.headlineWeight, FontWeight.Bold),
                    labelWeight = legibleWeight(themePalette.labelWeight, FontWeight.Bold),
                )
            }
        }
    return remember(ink, scene, themePalette) {
        ShareCardPalettes(ink = ink, scene = scene, seedAccent = themePalette.accent)
    }
}
