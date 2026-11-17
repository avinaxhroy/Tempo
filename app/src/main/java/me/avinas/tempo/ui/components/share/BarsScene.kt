package me.avinas.tempo.ui.components.share

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Vertical Bars — a port of the 21st.dev `vertical-bars` component.
 *
 * The reference is a 2D canvas painter, not a shader: it lays horizontal rules
 * every 11 CSS px, then walks each rule in 8 px steps and drops a small dash
 * wherever a two-sine noise field rises above 0.5, sliding the dash along the
 * rule with a third sine. That is cheap to draw and impossible to reproduce
 * faithfully per pixel — each fragment would need five candidate dashes and
 * their noise reads — so this port keeps the painter: one [Path] for the rules
 * and one for the dashes, drawn on whatever canvas is current.
 *
 * Geometry is evaluated in the reference's CSS-pixel space and scaled by the
 * card's width. The reference's constants (11 px rules, 8 px dash pitch, 3–13 px
 * dashes) are sized for a desktop window; laid on a 360 dp card at 1:1 they turn
 * the field into a handful of fat bars that fight the card's type. The design
 * viewport is therefore quoted at 720 px — the reference at half scale — which
 * keeps its texture (rules, pitch and dash widths all halve together) while
 * still reading as a level meter at card size. Interaction terms (pointer
 * radius, click ripples) are not carried: a share card has no pointer, and the
 * reference's own defaults leave them at zero influence.
 */
internal class BarsScene {

    private companion object {
        /**
         * The reference's CSS-pixel viewport, at the scale it is drawn at on a card.
         *
         * 720 px is the component's own layout at half size: the card is 360 dp wide,
         * so every constant below lands at half its desktop pixel count, which is
         * what makes the field read as a fine engraved meter rather than a bar chart.
         */
        const val DESIGN_WIDTH = 720f

        /** 0.0005 per frame at 60 fps, the component's default animation speed. */
        const val ANIMATION_SPEED = 0.03f

        /**
         * The reference loops forever; a 15-second story does not. Five times the
         * reference clock advances the field 2.25 rad across the clip, enough for the
         * dashes to complete most of their 20 px slide, while leaving every
         * geometric constant untouched.
         */
        const val STORY_CLOCK = 5f

        /** Dash pitch in design pixels. */
        const val PITCH = 8f

        /** Covers the design width with a little slack for float division. */
        const val MAX_COLUMNS = 128
    }

    private val sinX16 = FloatArray(MAX_COLUMNS)
    private val cosX16 = FloatArray(MAX_COLUMNS)
    private val sinX11 = FloatArray(MAX_COLUMNS)
    private val cosX11 = FloatArray(MAX_COLUMNS)

    init {
        for (i in 0 until MAX_COLUMNS) {
            val x = i * PITCH
            sinX16[i] = sin(x * 0.016f)
            cosX16[i] = cos(x * 0.016f)
            sinX11[i] = sin(x * 0.011f)
            cosX11[i] = cos(x * 0.011f)
        }
    }

    private val rules = Path()
    private val dashes = Path()
    private var rulesWidth = -1f
    private var rulesHeight = -1f
    private var numLines = 0
    private var spacing = 0f
    private val rulesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val dashesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun draw(canvas: Canvas, palette: MotionPalette, seconds: Float, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val scale = width / DESIGN_WIDTH
        val w = width / scale
        val h = height / scale
        val t = seconds * ANIMATION_SPEED * STORY_CLOCK
        val sinT = sin(t)
        val cosT = cos(t)

        canvas.drawColor(palette.background.toArgb())

        // Rules: a full-width hairline per row, 0.3 alpha in the reference. The
        // crest is that line's role in a palette — near-white on a dark page,
        // ink on a light one. The path only depends on the size, so it survives
        // every frame after the first.
        if (w != rulesWidth || h != rulesHeight) {
            rules.rewind()
            numLines = floor(h / 11f).toInt().coerceAtLeast(1)
            spacing = h / numLines
            for (i in 0 until numLines) {
                val y = i * spacing + spacing / 2f
                rules.moveTo(0f, y)
                rules.lineTo(w, y)
            }
            rulesWidth = w
            rulesHeight = h
        }

        // Both inks are the scene's, not the reference's fixed #444/#000: the
        // rules take the artwork's ink with a cast of its vivid, the dashes the
        // vivid itself. On the paper page DAYLIGHT resolves, `crest` is the dark
        // end of the cover and `primary` its saturated colour, so the meter
        // prints in the record's own two colours.
        rulesPaint.color = mixColors(palette.crest, palette.primary, 0.35f).toArgb()
        rulesPaint.alpha = (0.30f * 255f).toInt()
        dashesPaint.color = mixColors(palette.primary, palette.crest, 0.55f).toArgb()
        dashesPaint.alpha = (0.66f * 255f).toInt()

        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawPath(rules, rulesPaint)

        // Dashes: the noise field's above-threshold runs, each slid along its
        // rule by its own noise value. All of them go into one path and one
        // drawPath. The old code issued ~10,000 drawRect calls per frame, which
        // was the dominant cost of this scene on both preview and export; a
        // single path keeps the beading (the gaps between dashes) and loses only
        // the 0.92-alpha overlap darkening, which is not visible at this size.
        //
        // The sine terms are expanded around t: sin(x·k + t) is precomputed for
        // every column and rotated by sin(t)/cos(t); the slide term is rotated
        // per rule. A frame does 4 trig calls per rule plus 2, not 8 per dash.
        dashes.rewind()
        for (i in 0 until numLines) {
            val y = i * spacing + spacing / 2f
            val cosY01 = cos(y * 0.01f + t)
            val cosY005 = cos(y * 0.005f + t)
            val sinY = sin(y * 0.0375f)
            val cosY = cos(y * 0.0375f)
            val slide = sinT * cosY + cosT * sinY
            var x = 0f
            var col = 0
            while (x < w && col < MAX_COLUMNS) {
                val a = (sinX16[col] * cosT + cosX16[col] * sinT) * cosY01
                val b = (sinX11[col] * cosT - cosX11[col] * sinT) * cosY005
                val n = (a + b + 1f) / 2f
                if (n > 0.46f) {
                    val barWidth = 3f + n * 6.5f
                    val barHeight = 1.6f + n * 2.8f
                    val animatedX = x + slide * 20f * n
                    dashes.addRect(
                        animatedX - barWidth / 2f,
                        y - barHeight / 2f,
                        animatedX + barWidth / 2f,
                        y + barHeight / 2f,
                        Path.Direction.CW,
                    )
                }
                x += PITCH
                col++
            }
        }
        canvas.drawPath(dashes, dashesPaint)
        canvas.restore()
    }

}
