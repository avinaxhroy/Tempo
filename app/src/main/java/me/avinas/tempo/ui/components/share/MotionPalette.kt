package me.avinas.tempo.ui.components.share

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The palette a scene paints with, resolved from the card's theme and from the
 * cover art of the card's #1 item.
 *
 * The reference backgrounds ship their own colours (#5227FF horizon, #FF9FFC
 * wave, …). A share card cannot: it has to look like *this listener's* story.
 * So the hero colours are read out of the artwork — a small lightness-sorted
 * palette extraction plus a dominant-hue estimate — and then placed where the
 * reference shader puts its own: the sky/horizon takes the top of the cover,
 * the waves/ink take its most saturated colour, the crest takes its brightest
 * light. Everything stays in sRGB [Color]s here and is converted to linear by
 * the scenes that mix in linear space, mirroring each shader's own pipeline.
 */
data class MotionPalette(
    val isDark: Boolean,
    /** Page/backdrop; the reference `backgroundColor`. */
    val background: Color,
    /** Secondary body colour (reference `waveColor`, `uColor`). */
    val primary: Color,
    /** Secondary accent (reference `uAccent`, the tip of the ramp). */
    val secondary: Color,
    /** Lightest tone (reference `crestColor`, the brightest ramp stop). */
    val crest: Color,
    /** Sky / horizon glow (reference `horizonColor`, background haze). */
    val horizon: Color,
    /** Deepest tone, for shadowed bodies and the foot of the card. */
    val deep: Color,
    /** Mean saturation of the cover this palette came from (0 when theme-only). */
    val saturation: Float = 0f,
    /** Where the colours came from, for debugging a card that looks off. */
    val source: Source,
) {
    enum class Source { THEME, ARTWORK }

    val isLight: Boolean get() = !isDark

    companion object {
        /**
         * Builds the scene palette for [base] (the theme's own palette) with an
         * optional [artwork] palette already extracted from the #1 cover.
         *
         * Without artwork (nothing ranked yet, or the cover never decoded) the
         * theme keeps its identity and scenes fall back to the theme's own
         * gradient and accent stops — the card still looks like the theme the
         * listener picked, just without the personal colour cast.
         */
        fun of(
            base: me.avinas.tempo.ui.components.ShareThemePalette,
            artwork: ArtworkPalette?,
        ): MotionPalette {
            val isDark = base.isDark
            val themeStops: List<Color> = base.gradient + base.overlay + listOf(base.accent, base.glowTop)
            val themeDark: Color = themeStops.reduce { best, c -> if (luma(c) < luma(best)) c else best }
            if (artwork == null) {
                return MotionPalette(
                    isDark = isDark,
                    background = if (isDark) mixColors(themeDark, Color.Black, 0.45f) else base.gradient.last(),
                    primary = base.glowTop,
                    secondary = base.accent,
                    crest = if (isDark) Color.White else themeDark,
                    horizon = base.glowBottom,
                    deep = mixColors(themeDark, Color.Black, 0.55f),
                    saturation = 0f,
                    source = Source.THEME,
                )
            }

            // Dark themes keep the cover's colours but push the page to a deep
            // tinted ink so the card reads as a story, not a photo; light themes
            // lift the page to a paper tint of the same hue (DAYLIGHT's rule:
            // the card is a page, the scene is the print).
            val page =
                if (isDark) {
                    mixColors(mixColors(artwork.darkest, artwork.dominant, 0.35f), Color.Black, 0.55f)
                } else {
                    mixColors(mixColors(artwork.lightest, Color.White, 0.78f), artwork.dominant, 0.06f)
                }
            val crest = if (isDark) mixColors(artwork.lightest, Color.White, 0.35f) else mixColors(artwork.darkest, Color.Black, 0.25f)
            val horizon = if (isDark) artwork.dominant else mixColors(artwork.dominant, Color.White, 0.30f)
            return MotionPalette(
                isDark = isDark,
                background = page,
                primary = artwork.vivid,
                secondary = if (isDark) artwork.lightest else artwork.dominant,
                crest = crest,
                horizon = horizon,
                deep = mixColors(artwork.darkest, Color.Black, 0.5f),
                saturation = artwork.saturation,
                source = Source.ARTWORK,
            )
        }
    }
}

/**
 * What a cover actually offers a scene, measured once per artwork.
 *
 * Built from a 32×32 sample of the cover: the darkest and lightest tones (the
 * cover's own black point and white point), the most saturated tone (the colour
 * that makes it recognisable), a lightness-weighted average (its overall cast)
 * and the circular mean of its hues, which is what "the dominant hue" means for
 * images whose colours wrap around the colour circle.
 */
class ArtworkPalette(
    val darkest: Color,
    val lightest: Color,
    val vivid: Color,
    val dominant: Color,
    /** Dominant hue in degrees, 0..360. */
    val hue: Float,
    /** Mean saturation of the cover, 0..1 — a dim cover gets a leaner scene. */
    val saturation: Float,
) {

    companion object {
        private const val SAMPLE = 32

        /**
         * Samples [bitmap] down to [SAMPLE]² and derives the palette. Cheap enough
         * to run whenever a different cover becomes #1 (pick a new count, a new
         * period, a new list), and deterministic so the preview and the export
         * always agree.
         */
        fun from(bitmap: Bitmap): ArtworkPalette? {
            if (bitmap.width < 2 || bitmap.height < 2) return null
            val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
            val pixels = IntArray(SAMPLE * SAMPLE)
            small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
            if (small !== bitmap) small.recycle()

            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumLw = 0f
            var sumS = 0f
            var hueX = 0f
            var hueY = 0f
            var hueW = 0f
            var darkestL = 2f
            var darkest = 0xFF000000.toInt()
            var lightestL = -1f
            var lightest = 0xFFFFFFFF.toInt()
            var vividS = -1f
            var vivid = 0xFF808080.toInt()
            var count = 0
            for (argb in pixels) {
                val a = (argb ushr 24) and 0xFF
                if (a < 16) continue
                count++
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val s = if (maxC <= 0f) 0f else (maxC - minC) / maxC
                if (l < darkestL) {
                    darkestL = l
                    darkest = (0xFF shl 24) or (argb and 0x00FFFFFF)
                }
                if (l > lightestL) {
                    lightestL = l
                    lightest = (0xFF shl 24) or (argb and 0x00FFFFFF)
                }
                // Vivid = the most saturated tone, weighted toward mid lightness
                // so a blown-out highlight never wins on saturation alone.
                val score = s * (1f - 0.5f * kotlin.math.abs(l - 0.5f) * 2f)
                if (score > vividS) {
                    vividS = score
                    vivid = (0xFF shl 24) or (argb and 0x00FFFFFF)
                }
                val weight = 0.25f + l
                sumR += r * weight
                sumG += g * weight
                sumB += b * weight
                sumLw += weight
                sumS += s
                if (s > 0.12f) {
                    val hx = hueOf(r, g, b)
                    val ang = hx * 2f * PI.toFloat()
                    hueX += cos(ang) * s
                    hueY += sin(ang) * s
                    hueW += s
                }
            }
            if (count == 0 || sumLw <= 0f) return null
            val hue = if (hueW > 0f) (kotlin.math.atan2(hueY, hueX) * 180f / PI.toFloat() + 360f) % 360f else 0f
            val dominant = if (hueW > 0f) hsv(hue, 0.62f, 0.62f) else Color(sumR / sumLw, sumG / sumLw, sumB / sumLw)
            return ArtworkPalette(
                darkest = Color(darkest),
                lightest = Color(lightest),
                vivid = Color(vivid),
                dominant = dominant,
                hue = hue,
                saturation = (sumS / count).coerceIn(0f, 1f),
            )
        }

        /** Hue of an sRGB triple, 0..1 around the colour circle. */
        private fun hueOf(
            r: Float,
            g: Float,
            b: Float,
        ): Float {
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val d = maxC - minC
            if (d <= 0f) return 0f
            val h =
                when (maxC) {
                    r -> ((g - b) / d) % 6f
                    g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                }
            return ((h / 6f) + 1f) % 1f
        }

        private fun hsv(
            hueDeg: Float,
            s: Float,
            v: Float,
        ): Color {
            val h = (hueDeg % 360f + 360f) % 360f / 60f
            val c = v * s
            val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
            val m = v - c
            val (r, g, b) =
                when (h.toInt()) {
                    0 -> Triple(c, x, 0f)
                    1 -> Triple(x, c, 0f)
                    2 -> Triple(0f, c, x)
                    3 -> Triple(0f, x, c)
                    4 -> Triple(x, 0f, c)
                    else -> Triple(c, 0f, x)
                }
            return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
        }
    }
}

/** Linear interpolation between [a] and [b] by [t]. */
internal fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/** Rec. 709 luma of an sRGB colour. */
internal fun luma(color: Color): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

/** Channel-wise mix in sRGB — used only for page/tint colours, never for gradients a shader mixes. */
internal fun mixColors(
    a: Color,
    b: Color,
    t: Float,
): Color = Color(lerp(a.red, b.red, t), lerp(a.green, b.green, t), lerp(a.blue, b.blue, t), lerp(a.alpha, b.alpha, t))

