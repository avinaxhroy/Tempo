package me.avinas.tempo.ui.components.share

import me.avinas.tempo.ui.components.ShareCardLayoutStyle
import me.avinas.tempo.ui.components.ShareTheme

/**
 * The motion themes, as themes.
 *
 * The user's framing: "*it is extension of 6 still theme + 7 motion theme*". A
 * motion theme is not a separate switch that samples a background — it occupies
 * the same slot in the theme strip as DITHER or DAYLIGHT, it carries its own
 * palette (resolved from the card's #1 artwork), and picking it is the same
 * gesture as picking a still theme. So this enum is a parallel list to
 * [ShareTheme] with the same nine entries, and the card asks one question:
 * "which theme id is selected?" — [ShareThemeId].
 *
 * The nine motion ids are the six still themes whose identity a moving
 * background can honour (MIDNIGHT, GLASS, ASCII, MINIMUM, DAYLIGHT, DITHER) plus
 * the seven reference backgrounds named by the user.
 */
enum class ShareMotion {
    /** ReactBits Gradient Waves — a ray-marched sea rolling under a dark sky. */
    GRADIENT_WAVES,

    /** ReactBits Shape Waves — a grid of dots snapping triangle → circle → square. */
    SHAPE_WAVES,

    /** ReactBits Gradient ASCII — a dithered horizon printed as monospace glyphs. */
    GRADIENT_ASCII,

    /** ReactBits Liquid Ether — curl-noise fluid coloured by speed. */
    LIQUID_ETHER,

    /** ReactBits Pixel Blast — a dithered pixel field revealed by a travelling wave. */
    PIXEL_BLAST,

    /** 21st.dev Vertical Bars — the ranking rendered as a breathing equaliser. */
    VERTICAL_BARS,

    /** 21st.dev Dithering — halftone blobs screened through a Bayer matrix. */
    DITHERING,
    ;

    /**
     * The still theme this motion theme is a moving version of, if any.
     *
     * Motion is a theme choice, not an effect bolted onto one: a motion theme
     * states which static theme it belongs to so the card's type, badge shapes
     * and layout rules come from that theme rather than from a generic dark
     * default. `null` seeds off the palest still theme so a bare motion pick
     * still has a coherent type system.
     */
    val seedTheme: ShareTheme
        get() = when (this) {
            GRADIENT_WAVES -> ShareTheme.MIDNIGHT
            SHAPE_WAVES -> ShareTheme.GLASS
            GRADIENT_ASCII -> ShareTheme.ASCII
            LIQUID_ETHER -> ShareTheme.DITHER
            PIXEL_BLAST -> ShareTheme.MIDNIGHT
            // The bars reference is black ink on paper, not a dark scene, and the
            // user asked for that page: a white field. A light seed is what makes
            // the card's copy dark and every rule land as print.
            VERTICAL_BARS -> ShareTheme.DAYLIGHT
            // The dithering reference is a dark print: a near-black page and pale
            // dots. A paper page made it a washed photocopy, so it seeds off the
            // black still theme and the print carries white type over its masses.
            DITHERING -> ShareTheme.MINIMUM
        }

    /** The page the scene is printed on, following its seed theme. */
    val isDark: Boolean get() = seedTheme.palette.isDark

    /**
     * The composition the scene's card is printed with, when its seed theme's
     * own page is the wrong one for it.
     *
     * A scene inherits its layout from the still theme it moves, which is right
     * when the scene *is* that theme in motion. Halftone's page, though, is a
     * ruled table rather than a poster, and Glyphs and Bars sit beside it in the
     * same strip seeded off ASCII and DAYLIGHT: without this, swiping from one
     * scene to the next changes the deck from a printed page to a card of
     * badges. Null follows the seed.
     */
    val layoutStyle: ShareCardLayoutStyle?
        get() = when (this) {
            GRADIENT_ASCII, VERTICAL_BARS -> ShareCardLayoutStyle.INDEX
            else -> null
        }
}

/**
 * One selectable theme: a still theme or a motion theme.
 *
 * The strip is a single list of these, so "6 still + 7 motion" is one flat row of
 * thirteen choices rather than two controls that can disagree with each other.
 */
sealed interface ShareThemeId {
    val key: String

    data class Still(val theme: ShareTheme) : ShareThemeId {
        override val key: String get() = "still:${theme.name}"
    }

    data class Motion(val motion: ShareMotion) : ShareThemeId {
        override val key: String get() = "motion:${motion.name}"
    }

    companion object {
        /**
         * Every selectable theme, once: the stills, then the scenes.
         *
         * The carousel orders this set rather than copying it: [ShareCarouselOrder]
         * groups each scene with the theme it is seeded from and walks the two enums
         * directly, so a newly added theme shows up in the strip without a second
         * edit. The strip is tested against this list.
         */
        val all: List<ShareThemeId> =
            ShareTheme.entries.map { Still(it) } + ShareMotion.entries.map { Motion(it) }
    }
}

/** Convenience: the motion behind an id, or null for a still theme. */
val ShareThemeId.motion: ShareMotion?
    get() = (this as? ShareThemeId.Motion)?.motion

/** Convenience: the still theme behind an id — the motion theme's seed when it is one. */
val ShareThemeId.paletteTheme: ShareTheme
    get() = when (this) {
        is ShareThemeId.Still -> theme
        is ShareThemeId.Motion -> motion.seedTheme
    }
