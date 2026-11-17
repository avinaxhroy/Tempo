package me.avinas.tempo.ui.components.share

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import me.avinas.tempo.utils.SHARE_STORY_SECONDS
import kotlin.math.roundToInt

/**
 * The motion scenes, as GPU fragment shaders.
 *
 * Every scene is a single SkSL pass in `assets/motion/<scene>.glsl`, ported from
 * the reference implementation the user pointed at, and drawn with
 * [RuntimeShader]: one `drawRect` per frame on whatever GPU canvas is current —
 * the card's own canvas on screen, or the exporter's RenderNode.
 *
 * The previous renderer evaluated the same pictures per pixel in Kotlin and
 * uploaded a bitmap every frame. That is why the preview stuttered and why the
 * theme chips (seven more full rasterisations per frame) were unusable: the work
 * was O(pixels) on the UI thread. Here the work is one rectangle on the GPU.
 *
 * Only the scenes' *inputs* are declared in Kotlin: each [Program] reads the
 * uniforms its GLSL declares and binds them by name, so the shader files stay the
 * single source of truth for the look. Binding is allocation-free after the first
 * frame — colours are re-sent only when the palette changes, and the clock and
 * size are plain float writes — because this runs sixty times a second next to
 * the rest of the card.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class MotionShaderRenderer(private val assets: AssetManager) {

    /**
     * One slot per scene. `attempted` distinguishes "not loaded yet" from "failed
     * to compile", so a shader this driver rejects is not parsed again on every
     * frame.
     */
    private val programs = arrayOfNulls<Program>(ShareMotion.entries.size)
    private val attempted = BooleanArray(ShareMotion.entries.size)
    private var atlasShader: BitmapShader? = null
    private val paint = Paint().apply { isDither = true; isFilterBitmap = true }

    /**
     * Bars keep a mutable path between frames. Each renderer owns its instance:
     * the preview and the exporter shade on different threads, and a shared
     * singleton path would let one rewind the other's frame mid-draw.
     */
    private val bars = BarsScene()

    /**
     * Paints [motion] over the whole of [canvas] at [seconds] into its clock.
     *
     * Returns false when the scene could not be prepared (a shader that fails to
     * compile on this driver), so the caller can fall back to the palette fill
     * instead of drawing nothing.
     */
    fun draw(
        canvas: Canvas,
        motion: ShareMotion,
        palette: MotionPalette,
        seconds: Float,
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        // The bars scene is painted, not shaded: its dashes are five sine reads
        // per candidate per rule, which is cheap in a Path and ruinous per
        // fragment. Same call site, same canvas.
        if (motion == ShareMotion.VERTICAL_BARS) {
            bars.draw(canvas, palette, seconds, width, height)
            return true
        }
        val program = program(motion) ?: return false
        if (program.needsAtlas && !program.atlasBound) {
            // The glyph atlas is built once per renderer: a monospace strip of the
            // ten density marks the ASCII scene picks from.
            program.shader.setInputShader(ATLAS, atlasShader() ?: return false)
            program.atlasBound = true
        }
        program.bind(palette, seconds, width, height)
        paint.shader = program.shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return true
    }

    private fun program(motion: ShareMotion): Program? {
        val index = motion.ordinal
        if (!attempted[index]) {
            attempted[index] = true
            programs[index] =
                runCatching { Program(assets, motion) }
                    .onFailure {
                        // A shader the driver rejects paints nothing, and the
                        // compositor silently falls back to a flat fill. Say why
                        // once, so a device-specific compile failure is visible
                        // instead of looking like a palette bug.
                        Log.e("MotionShaderRenderer", "Could not compile $motion", it)
                    }
                    .getOrNull()
        }
        return programs[index]
    }

    private fun atlasShader(): BitmapShader? {
        atlasShader?.let { return it }
        return BitmapShader(buildAtlasBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            atlasShader = it
        }
    }

    /**
     * The glyph atlas: ten marks in a 16-column grid, each in a 60×100 cell,
     * white on transparent.
     *
     * The reference builds the same sheet in a 2D canvas — one column per
     * character, 60:100 cells, a 78 px light monospace face centred both ways —
     * and samples it from the glyph pass. The cell is that tall because a
     * 60:100 cell is what makes the marks read as a type specimen rather than
     * as dots at the size the card draws them.
     */
    private fun buildAtlasBitmap(): Bitmap {
        val rows = (GLYPH_COUNT + ATLAS_COLUMNS - 1) / ATLAS_COLUMNS
        val bitmap =
            Bitmap.createBitmap(
                ATLAS_COLUMNS * ATLAS_CELL_W.toInt(),
                rows * ATLAS_CELL_H.toInt(),
                Bitmap.Config.ALPHA_8,
            )
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                typeface = Typeface.create(Typeface.MONOSPACE, ATLAS_WEIGHT, false)
                textSize = ATLAS_CELL_H * 0.78f
                textAlign = Paint.Align.CENTER
            }
        // The reference centres on the text's middle baseline, +3 px: that is
        // the font's (ascent + descent) / 2 above the baseline.
        val metrics = paint.fontMetrics
        val baseline = ATLAS_CELL_H * 0.5f - (metrics.ascent + metrics.descent) * 0.5f + 3f
        GLYPHS.forEachIndexed { index, mark ->
            val column = index % ATLAS_COLUMNS
            val row = index / ATLAS_COLUMNS
            canvas.drawText(
                mark.toString(),
                column * ATLAS_CELL_W + ATLAS_CELL_W * 0.5f,
                row * ATLAS_CELL_H + baseline,
                paint,
            )
        }
        return bitmap
    }

    /**
     * One compiled shader plus the uniforms its source declares.
     *
     * The uniform list is read out of the GLSL rather than kept in a Kotlin
     * table: adding a scene means adding a file, and a shader that declares a
     * uniform this class has no value for still compiles — it just keeps the
     * driver's zero.
     */
    private class Program(assets: AssetManager, motion: ShareMotion) {
        val shader: RuntimeShader
        val needsAtlas: Boolean
        var atlasBound = false

        private val declaresTime: Boolean
        private val declaresWidth: Boolean
        private val declaresHeight: Boolean
        private val declaresAspect: Boolean
        private val colorNames: Array<String>
        private var boundPalette: MotionPalette? = null

        init {
            val source =
                assets.open("motion/${motion.name.lowercase()}.glsl")
                    .bufferedReader()
                    .use { it.readText() }
            val declared = HashSet<String>(12)
            var atlas = false
            UniformRegex.findAll(source).forEach { match ->
                val type = match.groupValues[1]
                val name = match.groupValues[2]
                if (type == "shader") {
                    if (name == ATLAS) atlas = true
                    return@forEach
                }
                declared += name
            }
            needsAtlas = atlas
            declaresTime = TIME in declared
            declaresWidth = WIDTH in declared
            declaresHeight = HEIGHT in declared
            declaresAspect = ASPECT in declared
            colorNames =
                PALETTE_UNIFORMS.filter { it in declared }
                    .toTypedArray()
            shader = RuntimeShader(source)
            // Everything that does not change between frames is written once,
            // here, instead of being recomputed per frame.
            if (SEED in declared) shader.setFloatUniform(SEED, 0.5f)
            if (INTRO in declared) shader.setFloatUniform(INTRO, 1f)
            if (LOOP in declared) shader.setFloatUniform(LOOP, MOTION_LOOP_SECONDS.toFloat())
            if (GLYPH_SIZE in declared) shader.setFloatUniform(GLYPH_SIZE, GLYPH_PX.toFloat())
            if (GLYPH_TOTAL in declared) shader.setFloatUniform(GLYPH_TOTAL, GLYPH_COUNT.toFloat())
            if (TWEAK in declared) shader.setFloatUniform(TWEAK, knobs(motion))
        }

        /**
         * Writes this frame's inputs.
         *
         * The palette comparison is by identity: the card derives one palette per
         * theme and holds it for as long as the sheet is open, so six colour
         * uniforms are sent when the user picks a theme and never again.
         */
        fun bind(palette: MotionPalette, seconds: Float, width: Int, height: Int) {
            if (palette !== boundPalette) {
                boundPalette = palette
                colorNames.forEach { name ->
                    val color =
                        when (name) {
                            BACKGROUND -> palette.background
                            PRIMARY -> palette.primary
                            SECONDARY -> palette.secondary
                            CREST -> palette.crest
                            HORIZON -> palette.horizon
                            else -> palette.deep
                        }
                    shader.setFloatUniform(name, color.red, color.green, color.blue, 1f)
                }
            }
            if (declaresTime) shader.setFloatUniform(TIME, seconds)
            if (declaresWidth) shader.setFloatUniform(WIDTH, width.toFloat())
            if (declaresHeight) shader.setFloatUniform(HEIGHT, height.toFloat())
            if (declaresAspect) {
                shader.setFloatUniform(ASPECT, width.toFloat() / height.coerceAtLeast(1))
            }
        }
    }

    companion object {
        private const val ATLAS = "uAtlas"
        private const val GLYPH_COUNT = 10

        /** Atlas cell height; the shader derives the 60:100 width from it. */
        private const val GLYPH_PX = 100
        private const val ATLAS_CELL_W = 60f
        private const val ATLAS_CELL_H = 100f
        private const val ATLAS_COLUMNS = 16
        private const val ATLAS_WEIGHT = 300
        private const val DESIGN_WIDTH = 1080f

        private const val TIME = "uTime"
        private const val WIDTH = "uResX"
        private const val HEIGHT = "uResY"
        private const val ASPECT = "uAspect"
        private const val SEED = "uSeed"
        private const val INTRO = "uIntro"
        private const val LOOP = "uLoop"
        private const val GLYPH_SIZE = "uGlyphPx"
        private const val GLYPH_TOTAL = "uGlyphCount"
        private const val TWEAK = "uTweak"

        private const val BACKGROUND = "uBg"
        private const val PRIMARY = "uPrimary"
        private const val SECONDARY = "uSecondary"
        private const val CREST = "uCrest"
        private const val HORIZON = "uHorizon"
        private const val DEEP = "uDeep"

        private val PALETTE_UNIFORMS =
            arrayOf(BACKGROUND, PRIMARY, SECONDARY, CREST, HORIZON, DEEP)

        /**
         * The reference's density ramp, in its order: the space first so the
         * darkest cells drop out entirely, the heaviest mark last.
         */
        private const val GLYPHS = " =+xX0S8#@"

        private val UniformRegex =
            Regex("""uniform\s+(float4|float3|float2|float|shader)\s+(\w+)\s*;""")

        /**
         * Scene knobs: `x`/`y` are scene-specific, `z` is the grid the scene
         * quantises onto (columns of glyphs, cells, bars) and `w` the scene's
         * overall opacity.
         *
         * The grid counts are quoted for a 1080-wide card and the scenes divide
         * their own width by them, so a scene keeps the same number of cells in
         * the preview, in a theme chip and in the exported 1080p frame.
         */
        private fun knobs(motion: ShareMotion): FloatArray =
            when (motion) {
                ShareMotion.GRADIENT_WAVES -> floatArrayOf(0f, 0.05f, 0f, 1f)
                ShareMotion.GRADIENT_ASCII -> floatArrayOf(0f, 0f, columns(32f), 1f)
                else -> floatArrayOf(0f, 0f, 0f, 1f)
            }

        private fun columns(cellPx: Float): Float = (DESIGN_WIDTH / cellPx).roundToInt().toFloat()
    }
}

/** Whether this device can draw the motion scenes (AGSL needs Android 13). */
object MotionShaders {
    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/** Length of one scene cycle, in seconds; the export writes exactly this much. */
const val MOTION_LOOP_SECONDS = SHARE_STORY_SECONDS

/** The story opens here: every shader's intro is over, so frame 0 is not blank. */
const val MOTION_START_SECONDS = 2.4f
