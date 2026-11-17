package me.avinas.tempo.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.ui.components.share.MotionTime
import me.avinas.tempo.ui.components.share.MotionPalette
import me.avinas.tempo.ui.components.share.ShareMotion
import me.avinas.tempo.ui.components.share.ShareMotionLayer
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.utils.MAX_SAFE_BITMAP_EDGE_PX
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// In-memory LRU cache for CPU-rendered backdrops (art aura, fluted glass, ascii art).
// Avoids redundant computations across preview and capture view, and speeds up theme toggles.
//
// The budget is bytes, not entries, because the entries are not the same size:
// an aura is 320x570 and a dithered plate is 800x1422, so counting eight of
// them could pin anywhere between four and thirty megabytes of ARGB_8888
// on a phone that may not have it. An eighth of the heap, kept inside a range
// that still holds a couple of full-size plates.
@Suppress("StaticFieldLeak")
private val backdropCacheBudget: Int by lazy {
    (Runtime.getRuntime().maxMemory() / 8).coerceIn(12L * 1024 * 1024, 32L * 1024 * 1024).toInt()
}

private val backdropBitmapCache =
    object : LruCache<String, Bitmap>(backdropCacheBudget) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

/**
 * Backdrop construction. Each style builds its own scene from different
 * geometry rather than swapping the hue of one backdrop: [CHROMA] re-exposes
 * the artwork as a moonlit print, [FLUTED_GLASS] refracts it through a vertical
 * ribbed pane, [ASCII_ARTWORK] converts it into true ASCII art, [SUN_WASH]
 * prints it on warm stock in daylight, and [DITHER_FIELD] reduces it to an
 * ordered-dither halftone.
 *
 * Everything is drawn with plain brushes/paths on Canvas — or baked once into a
 * bitmap on the CPU — so it renders identically on-screen and in the
 * software-canvas share capture.
 */
enum class ShareBackdropStyle {
    /**
     * Chroma: the artwork itself, re-exposed for moonlight and printed full
     * bleed. Nothing is drawn over it — no moon, no halo, no stars.
     */
    CHROMA,

    /** Fluted glass: the artwork refracted through a vertical ribbed pane. */
    FLUTED_GLASS,

    /** Terminal ASCII: a monospace glyph field shaded by layered waves. */
    ASCII_FIELD,

    /** Album art rendered as true ASCII art — brightness-mapped ramp glyphs
     *  tinted with the artwork's own colors on a terminal-dark field. */
    ASCII_ARTWORK,

    /** Paper: a flat sheet and one hairline frame. Nothing else. */
    PAPER_PAGE,

    /**
     * Sun wash: the artwork printed on warm stock and left in morning light —
     * a bright, low-contrast plate warmed by one wide bloom.
     */
    SUN_WASH,

    /** Ordered dither: the artwork printed as a dot-matrix halftone. */
    DITHER_FIELD,
}

/**
 * Backdrops that build their page from the artwork itself, so the surface must
 * not also lay the shared blurred-artwork base underneath: that would be two
 * covers, one blurred over the other. Each of these draws the aura — or a plate
 * baked from it — as its own base.
 */
internal val ShareBackdropStyle.ownsArtworkBase: Boolean
    get() =
        this == ShareBackdropStyle.FLUTED_GLASS ||
            this == ShareBackdropStyle.ASCII_ARTWORK ||
            this == ShareBackdropStyle.CHROMA ||
            this == ShareBackdropStyle.SUN_WASH ||
            this == ShareBackdropStyle.DITHER_FIELD

/**
 * How a ranking card composes its rows, as distinct from how it is inked.
 *
 * [STANDARD] is the poster: a hero row for the winner, badges, a glass summary
 * cell, a divider. [INDEX] is the drawn page: no badges, no hero, no cells — a
 * ruled table where the rank is a numeral in its own column and every row is
 * separated by a hairline. A theme that promises less has to *draw* less, so
 * this is a palette property rather than a flag threaded through the card.
 */
enum class ShareCardLayoutStyle {
    STANDARD,

    INDEX,
}

/**
 * Shared theme system for all share cards (stats, song details, artist
 * details). A theme declares color tone, backdrop construction, shape
 * language, and contrast rules; every card derives text, surfaces, badges,
 * and decorations from it so stats stay readable on dark and light backdrops.
 */
data class ShareThemePalette(
    val gradient: List<Color>,
    val overlay: List<Color>,
    val accent: Color,
    val glowTop: Color,
    val glowBottom: Color,
    val rank1Tint: Color,
    val isDark: Boolean = true,
    // True when the card is set over a motion scene rather than flat stock.
    // A scene is a field of mid tones — the halftone's masses run from paper to
    // near-black — and the still palettes were inked for a sheet with one tone.
    // The derived text slots darken here so the copy holds its contrast against
    // the busiest patch instead of the average one.
    val overScene: Boolean = false,
    // Backdrop identity — which scene the theme builds, how strongly, and
    // whether the blurred artwork is the backdrop source at all. Themes with
    // usesArtwork = false ignore the item art and draw their own background.
    val backdrop: ShareBackdropStyle = ShareBackdropStyle.CHROMA,
    val decorationAlpha: Float = 0.15f,
    val usesArtwork: Boolean = false,
    // Shape language — surfaces, thumbnails, and rank badges follow the theme;
    // winner rings (hero/podium avatars) stay circular by design.
    val cardShape: RoundedCornerShape = RoundedCornerShape(20.dp),
    val thumbShape: RoundedCornerShape = RoundedCornerShape(8.dp),
    val badgeShape: Shape = CircleShape,
    // Layout text system — lets a theme restyle the typography of every card,
    // not just its backdrop. Null means "use the card's default": only themes
    // that opt in (e.g. MINIMUM, with light headlines and thin, widely
    // tracked labels) change the type, so the other themes stay pixel-identical.
    val headlineWeight: FontWeight? = null,
    val labelWeight: FontWeight? = null,
    val labelTracking: TextUnit? = null,
    // Layout system — lets a theme restyle the *composition* of a ranking card,
    // not just its ink: INDEX drops the rank badges, the hero row and the glass
    // summary cell for a ruled table of numerals and hairlines. A theme that
    // claims to be minimal and still draws a card inside a card has only
    // changed its colour.
    val layoutStyle: ShareCardLayoutStyle = ShareCardLayoutStyle.STANDARD,
    // Medal tints for ranks two and three. Silver and bronze were written into
    // the ranking card itself, which pinned every theme — the monochrome print
    // ones included — to the same two metals.
    val rank2Tint: Color = Color(0xFFE2E8F0),
    val rank3Tint: Color = Color(0xFFCD7F32),
) {
    // Tone-derived slots so every layout stays readable on light and dark themes.
    val textPrimary: Color
        get() =
            when {
                isDark -> Color.White
                // A print over a scene is inked at full strength: the warm
                // near-black reads as a tint of the paper once a mid-tone mass
                // runs under it.
                overScene -> Color.Black
                else -> Color(0xFF241C10)
            }
    // Light themes sit on paper, print and photographs, and their secondary ink
    // at 0.62 disappeared into mottled backgrounds (the halftone scene turned
    // every label into a smudge). It has to survive a mid-tone field, not just a
    // flat cream sheet, while staying clearly subordinate to textPrimary.
    val textSecondary: Color
        get() =
            if (isDark) {
                Color.White.copy(alpha = 0.6f)
            } else {
                Color(0xFF241C10).copy(alpha = if (overScene) 0.94f else 0.82f)
            }

    // High-emphasis text; also used where semantic colors (fan badges, stat
    // icons) would lack contrast on a light backdrop.
    val textStrong: Color get() = if (isDark) Color.White else Color(0xFF241C10)
    val surface: Color get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val surfaceStrong: Color get() = if (isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.5f)
    // A rule over a scene has to cross the print's mid tones, not just flat
    // paper: at 0.12 the hairline disappeared wherever a halftone mass ran under
    // it, so the table lost its rows exactly where the card was busiest. The
    // still sheet keeps the lighter rule; only the scene is inked heavier.
    val divider: Color
        get() =
            if (isDark) {
                Color.White.copy(alpha = 0.15f)
            } else {
                Color.Black.copy(alpha = if (overScene) 0.20f else 0.12f)
            }
    val cellBackground: Color get() = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0E6D2)
    val cellPlaceholder: Color get() = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6D7BC)
    val branding: Color
        get() =
            if (isDark) {
                Color.White.copy(alpha = 0.7f)
            } else {
                Color(0xFF241C10).copy(alpha = if (overScene) 0.9f else 0.75f)
            }
    val heroGlow: Color get() = if (isDark) Color.White.copy(alpha = 0.25f) else Color(0xFFB45309).copy(alpha = 0.2f)
}

enum class ShareTheme(
    val palette: ShareThemePalette,
) {
    // Midnight: the cover itself, printed in moonlight.
    //
    // The two cuts before this one hung the artwork inside a scene the theme
    // painted — a moon with a halo, an aurora band, a field of stars — and the
    // scene always won: a drawn sky over somebody else's photograph reads as a
    // sticker on a poster, and at share size the moon was a white circle. The
    // page now belongs to the artwork. The cover is re-exposed into a handful of
    // blue plates, given one soft bloom where a light source would be, and
    // printed full bleed; the only thing laid over it is the veil that carries
    // the type.
    //
    // The medal metals are moonlight — near-white, blue-grey, slate — not gold
    // and bronze. There is no warm metal in a night, and a gold trophy was the
    // one thing on the card that looked bought rather than drawn.
    MIDNIGHT(
        ShareThemePalette(
            gradient = listOf(Color(0xFF060B1C), Color(0xFF03060F), Color(0xFF010206)),
            overlay =
                listOf(
                    Color(0xFF04070F).copy(alpha = 0.46f),
                    Color(0xFF03050C).copy(alpha = 0.64f),
                    Color(0xFF010206).copy(alpha = 0.84f),
                ),
            accent = Color(0xFFDCE6FF),
            glowTop = Color(0xFF16244D),
            glowBottom = Color(0xFF070C1E),
            rank1Tint = Color(0xFFE9EFFC),
            rank2Tint = Color(0xFF9EADCA),
            rank3Tint = Color(0xFF6B7A94),
            backdrop = ShareBackdropStyle.CHROMA,
            decorationAlpha = 1f,
            usesArtwork = true,
        ),
    ),

    // Fluted glass: the listener's own artwork refracted through vertical
    // glass ribs — the cover is converted into a fluted-glass texture (prism
    // bends, rib shadows, seam highlights) that becomes the card's backdrop,
    // under a smoked overlay that keeps the glass mood and text readable.
    GLASS(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0B0D14), Color(0xFF1A1330), Color(0xFF0F0B18)),
            overlay =
                listOf(
                    Color.Black.copy(alpha = 0.38f),
                    Color(0xFF0F0C1A).copy(alpha = 0.56f),
                    Color(0xFF0A0812).copy(alpha = 0.84f),
                ),
            accent = Color(0xFFBAE6FD),
            glowTop = Color(0xFF67E8F9),
            glowBottom = Color(0xFFE879F9),
            rank1Tint = Color(0xFF7DD3FC),
            backdrop = ShareBackdropStyle.FLUTED_GLASS,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(24.dp),
            thumbShape = RoundedCornerShape(14.dp),
        ),
    ),

    // Terminal: the #1 item's cover art converted into true ASCII art
    // (brightness-mapped ramp glyphs tinted with the artwork's own colors)
    // on near-black, sharp corners, phosphor-green UI accents.
    ASCII(
        ShareThemePalette(
            gradient = listOf(Color(0xFF040704), Color(0xFF08120A), Color(0xFF050A06)),
            overlay =
                listOf(
                    Color.Black.copy(alpha = 0.30f),
                    Color.Black.copy(alpha = 0.42f),
                    Color(0xFF020503).copy(alpha = 0.75f),
                ),
            accent = Color(0xFF4ADE80),
            glowTop = Color(0xFF22C55E),
            glowBottom = Color(0xFF86EFAC),
            rank1Tint = Color(0xFF4ADE80),
            backdrop = ShareBackdropStyle.ASCII_ARTWORK,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(4.dp),
            thumbShape = RoundedCornerShape(4.dp),
            badgeShape = RoundedCornerShape(4.dp),
        ),
    ),

    // Minimum: a drawn page, not a decorated one. Near-black ink on a white
    // sheet, one hairline frame, one rule under the header, no card inside the
    // card, no badges, no glow, no grain. The composition carries the theme
    // (INDEX layout: ruled rows, ranks as numerals), the type carries the
    // rest — light headlines, thin labels tracked wide, sentence case.
    MINIMUM(
        ShareThemePalette(
            gradient = listOf(Color(0xFFFCFCFA), Color(0xFFF7F6F2), Color(0xFFF1F0EB)),
            overlay =
                listOf(
                    Color(0xFFFFFFFF).copy(alpha = 0.40f),
                    Color(0xFFFBFBF9).copy(alpha = 0.62f),
                    Color(0xFFF5F4F0).copy(alpha = 0.80f),
                ),
            accent = Color(0xFF111111),
            glowTop = Color(0xFFE4E4DE),
            glowBottom = Color(0xFFEDEDE7),
            rank1Tint = Color(0xFF111111),
            rank2Tint = Color(0xFF4B4B49),
            rank3Tint = Color(0xFF7A7A76),
            isDark = false,
            backdrop = ShareBackdropStyle.PAPER_PAGE,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(0.dp),
            thumbShape = RoundedCornerShape(0.dp),
            badgeShape = RoundedCornerShape(0.dp),
            headlineWeight = FontWeight.Light,
            labelWeight = FontWeight.Normal,
            labelTracking = 1.8.sp,
            layoutStyle = ShareCardLayoutStyle.INDEX,
        ),
    ),

    // Daylight: the cover printed on warm stock and left in morning light.
    //
    // The cuts before this one painted an open sky over the artwork — a blue
    // zenith, a sun with a disc and a halo, cloud banks, ridges — and every one
    // of those shapes read as a sticker: a sun that is a circle is a sun nobody
    // believes. The light now lives in the plate. The cover is lifted toward
    // paper, warmed, and one wide bloom warms the corner it comes from; the
    // palette follows with near-white warms, because a pale blue sheet under a
    // lightened cover reads as a cold photograph.
    DAYLIGHT(
        ShareThemePalette(
            gradient = listOf(Color(0xFFFFFBF4), Color(0xFFFDF4E7), Color(0xFFF7E9D8)),
            overlay =
                listOf(
                    Color(0xFFFFFDF8).copy(alpha = 0.16f),
                    Color(0xFFFFFBF2).copy(alpha = 0.30f),
                    Color(0xFFFFF8EC).copy(alpha = 0.46f),
                ),
            accent = Color(0xFFB4530C),
            glowTop = Color(0xFFFFE3A8),
            glowBottom = Color(0xFFF6E2CB),
            rank1Tint = Color(0xFF9A3D0B),
            rank2Tint = Color(0xFF5A6B7C),
            rank3Tint = Color(0xFF94603A),
            isDark = false,
            backdrop = ShareBackdropStyle.SUN_WASH,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(14.dp),
            thumbShape = RoundedCornerShape(7.dp),
        ),
    ),

    // Dither: the artwork printed as an ordered-dither halftone, ported from
    // the Aceternity dither shader. One ink on one paper — the cover is reduced
    // to a 4x4 Bayer dot grid in a single warm ink colour, so the card is a
    // printed plate rather than a photograph. Replaces the old GRAIN theme: the
    // grain was a texture laid on a gradient, this is the picture itself.
    DITHER(
        ShareThemePalette(
            gradient = listOf(Color(0xFFF6EFDF), Color(0xFFF1E6D0), Color(0xFFF8F1E2)),
            // A printed plate is its own paper: the scrim only has to lift the
            // type, not reprint the sheet. Heavier alphas veiled the picture
            // into the wash the plate was already accused of being.
            overlay =
                listOf(
                    Color(0xFFFFFCF4).copy(alpha = 0.10f),
                    Color(0xFFFDF8EC).copy(alpha = 0.22f),
                    Color(0xFFFBF4E6).copy(alpha = 0.38f),
                ),
            accent = Color(0xFF2B1D12),
            glowTop = Color(0xFFD9C7A6),
            glowBottom = Color(0xFFF2E8D5),
            rank1Tint = Color(0xFF2B1D12),
            rank2Tint = Color(0xFF5C4834),
            rank3Tint = Color(0xFF8A7358),
            isDark = false,
            backdrop = ShareBackdropStyle.DITHER_FIELD,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(10.dp),
            thumbShape = RoundedCornerShape(6.dp),
            badgeShape = RoundedCornerShape(6.dp),
        ),
    ),
}

/**
 * Where a card's backdrop art comes from.
 *
 * Every artwork theme is a *treatment* of one source: the art reduced to the
 * de-texted aura in [buildArtAuraField], then printed as moonlight, warm paper,
 * glass ribs, glyphs or dots. That source used to be a URL and nothing else,
 * because a cover is what a share card stood on. The badge and milestone cards
 * have no cover — they draw their own plate — and they handed it in through a
 * second parameter that only the glass theme read, so on the other five themes
 * their art was replaced by a generic fallback. One type instead of two: a card
 * names its art, and every theme treats it the same way, whether the app drew it
 * or Coil fetched it.
 */
sealed interface ShareArtSource {
    /** Stable identity for the bake caches: two cards with the same art share one bake. */
    val key: String

    /** Artwork fetched by Coil — a song, album or artist cover. */
    data class Url(
        val url: String,
    ) : ShareArtSource {
        override val key: String get() = url
    }

    /**
     * Art the app drew itself: a badge or milestone plate, already rasterised.
     * [key] stands in for the URL in the cache, so a procedural backdrop is baked
     * once per card aspect like any cover instead of on every recomposition.
     */
    data class BitmapSource(
        val bitmap: Bitmap,
        override val key: String,
    ) : ShareArtSource
}

/** The art behind [this] artwork URL, or null when there is nothing to load. */
internal fun String?.asShareArt(): ShareArtSource? =
    if (isNullOrBlank()) null else ShareArtSource.Url(this)

/** Instant stand-in blur, drawn only until [ShareBlurBase]'s aura field lands. */
internal val ShareBlurRadius = 64.dp

/**
 * The shared artwork base every artwork-backed theme sits on.
 *
 * A raw, merely-blurred cover is what breaks these backdrops. Album art is
 * mostly printed matter — a title, a logo, a face — so any blur that still
 * reads as artwork leaves those letterforms and hard edges recognisable, and
 * the moment a theme refracts the cover (glass ribs) or resamples it (ASCII
 * grid) they come back as smeared streaks. The card stops looking designed.
 *
 * So the artwork is never used directly. It is reduced to an *aura*: the cover
 * pushed out of focus, with the light at its extremes — exactly where printed
 * titles and logotypes live — pulled back into the art's own range, its chroma
 * lifted, and its exposure normalised so a dark cover still fills the card.
 * What is left is the cover as an out-of-focus photograph: its shading, depth
 * and colour intact, nothing legible surviving. Every theme is built from that
 * field — MIDNIGHT draws it as the final background under its glow orbs,
 * FLUTED_GLASS refracts it rib by rib, ASCII_ARTWORK samples its glyph grid
 * from it.
 *
 * The blurred [CachedAsyncImage] stays underneath as the instant placeholder:
 * the field is baked off the main thread, so until it lands the card shows the
 * heap blur rather than an empty gradient. Readability scrims stay per-backdrop
 * ([ShareThemePalette.overlay]) so text contrast is untouched.
 */
@Composable
internal fun ShareBlurBase(
    art: ShareArtSource?,
    modifier: Modifier = Modifier,
) {
    if (art == null) return
    // 0 until layout reports the real card aspect: baking at a guessed aspect
    // would only be thrown away a frame later, and every bake costs a cache slot.
    // Not keyed on the art: the card size outlives the track, so the measured
    // aspect is reused when the carousel swaps the artwork.
    var aspect by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    val aura by produceState<Bitmap?>(initialValue = null, art, aspect) {
        if (aspect <= 0f) return@produceState
        value = runCatching { buildArtAuraField(context, art, aspect) }.getOrNull()
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        // Quantised so sub-pixel layout jitter cannot re-bake.
                        val measured =
                            (size.width.toFloat() / size.height * 1000f).roundToInt() / 1000f
                        if (measured != aspect) aspect = measured
                    }
                }.clipToBounds(),
    ) {
        val field = aura
        // Once the field lands it covers the card completely, so the blurred
        // cover (and the 240dp bitmap behind it) is dropped rather than kept
        // alive under an opaque layer.
        if (field == null) {
            when (art) {
                is ShareArtSource.Url ->
                    CachedAsyncImage(
                        imageUrl = art.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer(scaleX = 1.1f, scaleY = 1.1f),
                        targetSizeDp = 240,
                        allowHardware = false,
                        blurRadius = ShareBlurRadius,
                    )
                // Art the app drew needs no loader and no blur: it is already a
                // soft plate, so it stands in for itself.
                is ShareArtSource.BitmapSource ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(
                            image = art.bitmap.asImageBitmap(),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // The field is already cropped to the card aspect and carries
                // its own baked grain, so stretching it to full size is a 1:1
                // map — no resampling artefacts left to show.
                drawImage(
                    image = field.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

// The mosaic the artwork is reduced to before any theme effect touches it: the
// width it is sampled down to, the width it is resampled back up to for display,
// and the look of the reduction itself.
private const val ART_AURA_WIDTH = 34
private const val ART_AURA_DECODE_WIDTH = 384
private const val ART_FIELD_DISPLAY_WIDTH = 320
private const val ART_FIELD_SATURATION = 1.38f
private const val ART_FIELD_CONTRAST = 1.06f
private const val ART_FIELD_TARGET_MEAN = 0.50f

// Box-blur rounds over the mosaic before it is scaled back up. One leaves the
// cell grid faintly visible through a theme's scrim; two melts the cells into
// each other, which is what stops the field from reading as tiles. The print is
// already gone by then — the *sampling* removed it, not this.
private const val ART_FIELD_SOFTEN_ROUNDS = 2

// ±LSB of baked surface noise at display size. The upscale turns it into a fine
// mottle, so the field reads as a surface rather than a stretched gradient —
// baked once, so nothing is paid per frame or per video frame.
private const val ART_FIELD_GRAIN = 3.5f

/**
 * Loads the artwork and reduces it to the de-texted aura every theme is built
 * from. Cached per art source + card aspect, so MIDNIGHT, FLUTED_GLASS and
 * ASCII_ARTWORK share one decode and one reduction instead of each baking the
 * cover again.
 */
private suspend fun buildArtAuraField(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): Bitmap? {
    val cacheKey = "aura:${art.key}:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    // Small decode on purpose: the field is [ART_AURA_WIDTH] across, and the
    // reduction below throws away everything a bigger decode would buy.
    val source = art.sourceBitmap(context, ART_AURA_DECODE_WIDTH) ?: return null
    return withContext(Dispatchers.Default) {
        val displayH =
            (ART_FIELD_DISPLAY_WIDTH / aspect)
                .roundToInt()
                .coerceIn(16, MAX_SAFE_BITMAP_EDGE_PX)
        val result =
            source
                .artAuraField(aspect)
                .fieldUpscale(ART_FIELD_DISPLAY_WIDTH, displayH)
        backdropBitmapCache.put(cacheKey, result)
        result
    }
}

/**
 * The art source as pixels.
 *
 * A URL is fetched through Coil; a bitmap the app already drew is handed back
 * as-is. Either way the caller gets something it can reduce — that is the whole
 * point of [ShareArtSource]: no theme can tell which of the two it was given,
 * so a badge plate gets moonlight and glass ribs exactly like a cover does.
 */
private suspend fun ShareArtSource.sourceBitmap(
    context: Context,
    decodeWidth: Int,
): Bitmap? =
    when (this) {
        is ShareArtSource.Url -> {
            val request =
                ImageRequest
                    .Builder(context)
                    .data(MusicBrainzEnrichmentService.fixHttpUrl(url))
                    .size(decodeWidth, decodeWidth)
                    .allowHardware(false)
                    .build()
            (context.imageLoader.execute(request).image as? BitmapImage)
                ?.bitmap
                ?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        }
        is ShareArtSource.BitmapSource ->
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
    }

/**
 * Where the paper page's ruled frame stops, measured up from the trim.
 *
 * The frame is the page edge, not a box around the text, so its foot has to
 * clear the wordmark that sits in the bottom margin. A rule drawn at one inset
 * all round runs through the top of the TEMPO colophon — the footer is only
 * 16dp off the trim — and the page then reads as printed over its own imprint.
 * Printed stock sets a wider foot margin than head margin for exactly this
 * reason, so the rule stops above the colophon and the branding keeps the
 * margin to itself.
 */
private val PAPER_FRAME_FOOT_MARGIN = 44.dp

/**
 * The paper page's inset hairline frame — the printed sheet's edge.
 *
 * Drawn by the still paper theme and by any scene seeded from it. A scene keeps
 * the theme's type and margins but was skipping the page rule, and without its
 * edge the halftone card stopped reading as a printed page: type floating on a
 * field with no border. The frame is drawn a little stronger over a scene
 * because the dots are mid-toned and a 14% hairline disappears into them.
 */
internal fun DrawScope.drawPaperFrame(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    val frameColor =
        palette.accent.copy(
            alpha = (if (palette.overScene) 0.30f else 0.14f) * palette.decorationAlpha,
        )
    val inset = w * 0.052f
    val top = inset * 1.35f
    // The foot of the rule clears the wordmark. A rule drawn at the same inset
    // top and bottom runs straight through the branding: the footer sits 16dp
    // off the trim, so the line crossed the top third of the letters and the
    // page read as printed over its own colophon. A printed page sets its text
    // block inside the rule and the imprint beneath it, so the rule stops at the
    // block and the wordmark gets the margin to itself.
    val foot = PAPER_FRAME_FOOT_MARGIN.toPx()
    drawRect(
        color = frameColor,
        topLeft = Offset(inset, top),
        size = Size(w - inset * 2f, h - top - foot),
        style = Stroke(width = w * 0.0018f),
    )
}

/**
 * Builds the theme's backdrop scene. Placed above the base gradient (and the
 * shared blurred artwork base when the theme uses artwork) and below the card
 * content. Clipped to its own bounds so light never leaks past the card edge
 * in preview dialogs.
 *
 * When [motion] is set the animated scene replaces the theme's static one — the
 * palette, shapes and typography still come from the theme, so a motion is a
 * living backdrop rather than a seventh theme. The blurred artwork base stays
 * underneath for art-based themes (scrimmed to a whisper) so the card keeps the
 * listener's own colours while it moves.
 */
@Composable
fun ShareThemeDecorations(
    palette: ShareThemePalette,
    art: ShareArtSource? = null,
    modifier: Modifier = Modifier,
    motion: ShareMotion? = null,
    motionTime: MotionTime = MotionTime.Still,
    /**
     * Scene colours for the motion theme, resolved by the card from its #1
     * artwork. Defaults to the theme's own stops so the song/artist cards keep
     * working untouched.
     */
    motionPalette: MotionPalette = MotionPalette.of(palette, null),
) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (motion != null) {
            // During a motion export the scene is shaded by the exporter and
            // this layer only carries the card's finish (see LocalMotionOverlay).
            if (!LocalMotionOverlay.current) {
                ShareMotionLayer(
                    motion = motion,
                    palette = motionPalette,
                    time = motionTime,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Film grain + top sheen are part of the card's finish; a moving
            // backdrop without them reads flatter than the static themes.
            Canvas(modifier = Modifier.fillMaxSize()) { drawShareFinish(palette) }
            return@Box
        }
        when (palette.backdrop) {
            // The backdrops that ARE the artwork. Each of these bakes a plate
            // from the shared aura — the same blurred field every theme is built
            // from — and prints it full bleed: moonlit plates for MIDNIGHT, warm
            // paper for DAYLIGHT, a dot screen for DITHER. Nothing is drawn over
            // the cover, which is the whole point: a hand-drawn sky on top of a
            // photograph is a sticker on a poster.
            ShareBackdropStyle.CHROMA,
            ShareBackdropStyle.SUN_WASH -> {
                ArtworkPlateBackdrop(palette = palette, art = art)
            }

            // No cover: screen the theme's own light instead of painting a
            // uniform ramp over the whole card. Same plate, same ink, same
            // scrim — the dots now cluster around a subject and leave the rows
            // on paper, which a full-bleed fallback cannot do.
            ShareBackdropStyle.DITHER_FIELD -> {
                ArtworkPlateBackdrop(
                    palette = palette,
                    art = art ?: rememberFallbackArt(palette),
                )
            }

            ShareBackdropStyle.ASCII_FIELD -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawAsciiWaveField(palette)
                }
            }

            // Fluted glass: the artwork refracted into a vertical ribbed pane.
            // Never a plain blurred photo: the glass scene owns the backdrop from
            // the first frame so it never reads as MIDNIGHT with an overlay on
            // top.
            ShareBackdropStyle.FLUTED_GLASS -> {
                // No cover: flute the theme's own light instead of swapping in
                // a second, flatter drawing. Same pane, same ribs, same scrim —
                // only the picture behind the glass is invented.
                FlutedGlassBackdrop(palette = palette, art = art ?: rememberFallbackArt(palette))
            }

            // True ASCII art of the cover.
            ShareBackdropStyle.ASCII_ARTWORK -> {
                // No cover: resolve the theme's own light into glyphs, so the
                // card still reads as cover-as-ASCII with its picture missing
                // rather than as a different design.
                AsciiArtworkBackdrop(palette = palette, art = art ?: rememberFallbackArt(palette))
            }

            // Paper: the sheet is already the gradient, so the backdrop adds the
            // two marks that make it a drawn page — an inset hairline frame and
            // a single rule under the header — and nothing else. No vignette, no
            // rings, no glow: every one of those is a decoration, and this theme
            // is the absence of decoration.
            ShareBackdropStyle.PAPER_PAGE -> {
                Canvas(modifier = Modifier.fillMaxSize()) { drawPaperFrame(palette) }
            }

        }
        // Shared finish: a thin top sheen plus light film grain over every scene
        // except the two that already carry their own surface — the paper page,
        // which is supposed to be clean stock, and the dithered plate, which is
        // made of dots rather than grain.
        if (palette.backdrop != ShareBackdropStyle.PAPER_PAGE && palette.backdrop != ShareBackdropStyle.DITHER_FIELD) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawShareFinish(palette) }
        }
    }
}

// ---------------------------------------------------------------------------
// ASCII_ARTWORK backdrop — the cover art converted into true ASCII art,
// learned from ascii-magic's styles: the brightness-mapped ramp of
// "Characters", the mixed letter/punctuation families of "Mixed Glyphs", and
// the ordered-dither texture of "Dither", on top of a per-artwork dynamic
// tone mapping so every cover — dark, hazy or blown-out — fills the ramp.
// ---------------------------------------------------------------------------

/** Glyph advance as a fraction of the monospace text size. */
private const val ASCII_COL_STEP = 0.72f

/** Line height as a fraction of the monospace text size. */
private const val ASCII_ROW_STEP = 1.25f

/** Horizontal resolution of the ASCII conversion, in glyph columns. */
private const val ASCII_COLUMNS = 84

/**
 * Mixed-family density ramp (ascii-magic "Mixed Glyphs"): punctuation flows
 * into letters and symbols from sparse to dense, giving portraits and
 * high-detail covers richer texture than a pure punctuation ramp.
 */
private const val ASCII_RAMP = " .,:;=+*coxm#%@"

/**
 * Ordered-dither strength (ascii-magic "Dither"): how far the Bayer noise may
 * swing a cell's brightness before ramp quantization, in 0..1 units.
 */
private const val ASCII_DITHER = 0.35f

/** Bayer 4x4 ordered-dither matrix, values 0..15. */
private val ASCII_BAYER_4X4 =
    arrayOf(
        intArrayOf(0, 8, 2, 10),
        intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9),
        intArrayOf(15, 7, 13, 5),
    )

/**
 * Precomputed ASCII conversion of one artwork. All the per-pixel work happens
 * once at build time; the draw pass only scales and paints, so the live
 * preview and the software-canvas share capture render the identical
 * composition.
 */
private class AsciiArtGrid(
    val columns: Int,
    val rows: Int,
    /** Ramp index per cell; 0 (space) marks an empty cell. */
    val rampIndices: IntArray,
    /** Per-cell brightness after the contrast curve, 0..1 — drives alpha. */
    val brightness: FloatArray,
    /** Per-cell glyph colour (opaque ARGB), sampled from the artwork. */
    val colors: IntArray,
)

/**
 * Renders [art] as ASCII art. The conversion runs once per
 * (source, aspect) on a background dispatcher; until it lands the deterministic
 * wave field keeps the card alive over the shared blur base. Stack is shared
 * blur + glyph effect + readability overlay + finish grain.
 */
@Composable
private fun AsciiArtworkBackdrop(
    palette: ShareThemePalette,
    art: ShareArtSource,
) {
    // 0 until layout reports the real card aspect: a bake at a guessed aspect is
    // thrown away a frame later, and the glyph bake is the expensive one.
    var aspect by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    val backdrop by produceState<Bitmap?>(initialValue = null, art, aspect) {
        if (aspect <= 0f) return@produceState
        value =
            runCatching {
                buildAsciiArtBackdrop(context, art, aspect)
            }.getOrNull()
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        val measured = (size.width.toFloat() / size.height * 1000f).roundToInt() / 1000f
                        if (measured != aspect) aspect = measured
                    }
                }.clipToBounds(),
    ) {
        // Shared blur base under the glyphs — one blur system, effect on top.
        ShareBlurBase(art = art)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = backdrop
            if (bitmap != null) {
                // The bitmap is pre-cropped to the card aspect, so a full-size
                // destination is a 1:1 map.
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawAsciiWaveField(palette)
            }
        }
        // Readability overlay above the glyph art.
        ShareReadabilityOverlay(palette)
    }
}

/**
 * Fluted-glass backdrop: the listener's artwork refracted through a vertical
 * ribbed pane — the paper.design fluted-glass model, tuned for background use
 * behind card content. The effect is computed on the CPU into a bitmap (prism
 * refraction per rib, rib shadows, seam highlight strokes, one-directional
 * soften) so it renders identically on-screen and in the software-canvas
 * share capture. Stack is shared blur + refracted glass + readability overlay +
 * finish grain; the fluid pools stand in while the artwork decodes.
 */
@Composable
private fun FlutedGlassBackdrop(
    palette: ShareThemePalette,
    art: ShareArtSource,
) {
    // 0 until layout reports the real card aspect: a bake at a guessed aspect is
    // thrown away a frame later, and the flute pass is heavy.
    var aspect by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    val fluted by produceState<Bitmap?>(initialValue = null, art, aspect) {
        if (aspect <= 0f) return@produceState
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    buildFlutedGlassBitmap(context, art, aspect)
                }.getOrNull()
            }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        val measured = (size.width.toFloat() / size.height * 1000f).roundToInt() / 1000f
                        if (measured != aspect) aspect = measured
                    }
                }.clipToBounds(),
    ) {
        // Shared blur base under the glass — one blur system, effect on top.
        ShareBlurBase(art = art)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = fluted
            if (bitmap != null) {
                // The bitmap is pre-cropped to the card aspect, so a full-size
                // destination is a 1:1 map.
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawGlassFluidPools(palette)
            }
        }
        // Readability overlay above the glass.
        ShareReadabilityOverlay(palette)
    }
}

/**
 * The scrim that keeps a card's type readable over whatever the theme painted
 * underneath it.
 *
 * Every backdrop ends with this same box — the ASCII glyph field, the fluted
 * glass, the blurred artwork — and each had written it out by hand, so a change
 * to how the scrim is built (a different blend, a vignette) had four places to
 * land in and one of them would have been missed. The stops come from the
 * palette, because contrast is a property of the theme, not of the composable.
 */
@Composable
internal fun ShareReadabilityOverlay(
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(brush = Brush.verticalGradient(palette.overlay)),
    )
}

/**
 * The stand-in picture for a card that has no artwork.
 *
 * Glass and ASCII are treatments, not designs: they take a picture and refract
 * it, or resample it into glyphs. With no cover they used to fall back to a
 * second, hand-drawn design — which is why those two cards read as a different
 * theme rather than the same theme with the picture missing. So instead of
 * painting a substitute, a missing cover gets a substitute *source*: a light
 * mass in the theme's own colours, run through the same flute and the same glyph
 * pass as a real cover, under the same scrim.
 */
@Composable
private fun rememberFallbackArt(palette: ShareThemePalette): ShareArtSource {
    val key =
        "fallback:" +
            palette.glowTop.value.toString(16) +
            palette.glowBottom.value.toString(16) +
            palette.accent.value.toString(16)
    return remember(key) {
        ShareArtSource.BitmapSource(bitmap = createFallbackArt(palette), key = key)
    }
}

/**
 * A subject and its surround, which is the cheapest honest impression of a
 * cover: a lit mass on dark, or an ink mass on paper.
 *
 * These passes re-expose whatever they are handed: the aura normalises to a
 * fixed mean, the glyph pass auto-levels its histogram, the dot screen inks
 * whatever is dark. So the treatment has to survive, not the drawing, and
 * brightness won't do it — polarity and shape will. The dot screen prints ink
 * over the dark half of its source, so a black frame on a light sheet fills the
 * card with ink. An edge-to-edge glow comes back as edge-to-edge texture, which
 * is why the first attempt read as noise rather than a picture.
 *
 * The light also stops at [FALLBACK_SUBJECT_BAND]. A glow reaching the last row
 * competes with the rows instead of sitting behind the title, and the
 * re-exposure above turns a mild spill into texture under the type.
 */
private fun createFallbackArt(palette: ShareThemePalette): Bitmap {
    val width = FALLBACK_ART_WIDTH
    // 9:16 — the share card's own aspect, so the aura's crop keeps all of it.
    val height = (width * 16 / 9).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val w = width.toFloat()
    val h = height.toFloat()
    val fill = Paint()

    val dark = palette.isDark
    val ground = if (dark) Color.Black else palette.glowBottom
    val core = if (dark) lerp(palette.accent, Color.White, 0.30f) else palette.accent
    val halo = if (dark) palette.glowBottom else lerp(palette.glowBottom, palette.accent, 0.35f)

    canvas.drawColor(ground.toArgb())

    // Two masses, unequal and high in the frame: a primary subject with a
    // smaller companion, which is a photograph's out-of-focus bokeh rather than
    // one shape someone drew. The rim is where it fades to the ground, and that
    // rim is what the glyph pass traces.
    canvas.save()
    canvas.clipRect(0f, 0f, w, h * FALLBACK_SUBJECT_BAND)
    listOf(
        FallbackSubject(dx = 0.50f, dy = 0.09f, r = 0.25f, warmth = 1.00f),
        FallbackSubject(dx = 0.30f, dy = 0.15f, r = 0.12f, warmth = 0.50f),
    ).forEach { subject ->
        fill.shader =
            RadialGradient(
                w * subject.dx,
                h * subject.dy,
                w * subject.r,
                intArrayOf(
                    core.toArgb(),
                    lerp(core, halo, 0.55f).copy(alpha = 0.55f * subject.warmth).toArgb(),
                    halo.copy(alpha = 0.18f * subject.warmth).toArgb(),
                    ground.toArgb(),
                ),
                floatArrayOf(0f, 0.50f, 0.84f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(w * subject.dx, h * subject.dy, w * subject.r, fill)
    }
    canvas.restore()
    return bitmap
}

/** Sampled, not printed: the treatments only need a tonal field, not detail. */
private const val FALLBACK_ART_WIDTH = 288

/** Where the invented light stops, as a fraction of the card's height. */
private const val FALLBACK_SUBJECT_BAND = 0.34f

/** Where one out-of-focus light sits, in card fractions. */
private data class FallbackSubject(
    val dx: Float,
    val dy: Float,
    val r: Float,
    val warmth: Float,
)

/**
 * Loads the artwork through Coil (sharing the app's memory/disk cache) and
 * converts it into a fluted-glass texture at the card's aspect ratio.
 */
private suspend fun buildFlutedGlassBitmap(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): Bitmap? {
    val cacheKey = "fluted:${art.key}:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    // Flute the de-texted aura, never the raw cover: refraction stretches any
    // surviving letterform into a vertical streak, which is the single ugliest
    // artefact this backdrop had.
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    return withContext(Dispatchers.Default) {
        // Height follows the card's measured aspect, so it is bounded here: a very tall or
        // degenerate aspect must not drive Bitmap.createBitmap past the painter ceiling.
        val targetH =
            (FLUTED_RENDER_WIDTH / aspect).roundToInt().coerceIn(64, MAX_SAFE_BITMAP_EDGE_PX)
        val result = aura.fieldUpscale(FLUTED_RENDER_WIDTH, targetH).renderFlutedGlass()
        backdropBitmapCache.put(cacheKey, result)
        result
    }
}

/**
 * CPU fluted-glass pass (the paper.design model): soften the art into a glassy
 * base, refract it rib by rib, then shade the rods.
 */
private fun Bitmap.renderFlutedGlass(): Bitmap {
    val w = width
    val h = height
    var src = IntArray(w * h)
    getPixels(src, 0, w, 0, 0, w, h)
    // Stage 1 — soften the art into a glassy base (two h/v box rounds ≈ Gaussian).
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = true)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = false)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = true)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = false)
    val out = IntArray(w * h)
    val stripeW = w.toFloat() / FLUTED_STRIPE_COUNT
    val compressW = stripeW * FLUTED_COMPRESS
    val shiftPx = stripeW * FLUTED_SHIFT
    val smearPx = stripeW * FLUTED_BLUR
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val s = x / stripeW
            // Rib-local coordinate u ∈ [-1, 1) from the rib centre.
            val rib = (s + 0.5f).toInt()
            val u = (s - rib) * 2f
            // Cylindrical-lens refraction: the rib compresses a wider window
            // of the art into itself, nudged sideways for the melt.
            val ownX = rib * stripeW + u * compressW + shiftPx
            // Neighbour window across the nearest seam; the crossfade keeps
            // sampling continuous so seams flow instead of snapping.
            val neighbourX =
                if (u >= 0f) {
                    (rib + 1) * stripeW + (u - 2f) * compressW + shiftPx
                } else {
                    (rib - 1) * stripeW + (u + 2f) * compressW + shiftPx
                }
            val fadeT =
                ((abs(u) - (1f - FLUTED_SEAM_FADE)) / FLUTED_SEAM_FADE)
                    .coerceIn(0f, 1f)
            val fade = fadeT * fadeT * (3f - 2f * fadeT)
            val sampleX = ownX * (1f - fade) + neighbourX * fade
            // Directional smear along the refraction axis.
            val c1 = sampleFlutedColumn(src, w, sampleX - smearPx, y)
            val c2 = sampleFlutedColumn(src, w, sampleX, y)
            val c3 = sampleFlutedColumn(src, w, sampleX + smearPx, y)
            var r = ((c1 shr 16 and 0xFF) + (c2 shr 16 and 0xFF) + (c3 shr 16 and 0xFF)) / 3
            var g = ((c1 shr 8 and 0xFF) + (c2 shr 8 and 0xFF) + (c3 shr 8 and 0xFF)) / 3
            var b = ((c1 and 0xFF) + (c2 and 0xFF) + (c3 and 0xFF)) / 3
            // Cylindrical rod shading, luminance-adaptive: glass only reads
            // where the art is bright, so smooth fields stay hairline-free.
            val edge = abs(u).coerceIn(0f, 1f)
            val lum = (r + g + b) / 765f
            val shade = 1f - FLUTED_SHADOW * edge.pow(1.2f) * lum
            val strokeT = ((edge - 0.86f) / 0.14f).coerceIn(0f, 1f)
            val stroke = FLUTED_HIGHLIGHT * strokeT * strokeT * lum
            r = (r * shade + 255f * stroke).toInt().coerceIn(0, 255)
            g = (g * shade + 255f * stroke).toInt().coerceIn(0, 255)
            b = (b * shade + 255f * stroke).toInt().coerceIn(0, 255)
            out[row + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    return result
}

/**
 * One separable box-blur pass. Alternating horizontal/vertical rounds
 * approximate a Gaussian; the sliding-window sums keep the cost linear in
 * the pixel count regardless of radius.
 */
private fun blurBoxPass(
    src: IntArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean,
): IntArray {
    val out = IntArray(src.size)
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = (radius * 2 + 1).toFloat()
    for (line in 0 until lines) {
        var asum = 0
        var rs = 0
        var gs = 0
        var bs = 0
        for (d in -radius..radius) {
            val c = src[blurIndex(line, d.coerceIn(0, len - 1), w, horizontal)]
            asum += (c ushr 24) and 0xFF
            rs += (c shr 16) and 0xFF
            gs += (c shr 8) and 0xFF
            bs += c and 0xFF
        }
        for (i in 0 until len) {
            val a = (asum / div + 0.5f).toInt().coerceIn(0, 255)
            val r = (rs / div + 0.5f).toInt().coerceIn(0, 255)
            val g = (gs / div + 0.5f).toInt().coerceIn(0, 255)
            val b = (bs / div + 0.5f).toInt().coerceIn(0, 255)
            out[blurIndex(line, i, w, horizontal)] =
                (a shl 24) or (r shl 16) or (g shl 8) or b
            val addC = src[blurIndex(line, (i + radius + 1).coerceIn(0, len - 1), w, horizontal)]
            val remC = src[blurIndex(line, (i - radius).coerceIn(0, len - 1), w, horizontal)]
            asum += ((addC ushr 24) and 0xFF) - ((remC ushr 24) and 0xFF)
            rs += ((addC shr 16) and 0xFF) - ((remC shr 16) and 0xFF)
            gs += ((addC shr 8) and 0xFF) - ((remC shr 8) and 0xFF)
            bs += (addC and 0xFF) - (remC and 0xFF)
        }
    }
    return out
}

private fun blurIndex(
    line: Int,
    i: Int,
    w: Int,
    horizontal: Boolean,
): Int = if (horizontal) line * w + i else i * w + line

/** Horizontal bilinear sample with edge clamping. */
private fun sampleFlutedColumn(
    src: IntArray,
    w: Int,
    xf: Float,
    y: Int,
): Int {
    val x0 = xf.toInt()
    val f = (xf - x0).coerceIn(0f, 1f)
    val xa = x0.coerceIn(0, w - 1)
    val xb = (x0 + 1).coerceIn(0, w - 1)
    val ca = src[y * w + xa]
    val cb = src[y * w + xb]
    val r = (ca shr 16 and 0xFF) * (1f - f) + (cb shr 16 and 0xFF) * f
    val g = (ca shr 8 and 0xFF) * (1f - f) + (cb shr 8 and 0xFF) * f
    val b = (ca and 0xFF) * (1f - f) + (cb and 0xFF) * f
    return (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

// Fluted-glass parameters, tuned for background use behind card content:
// rib count across the card width, the base softening baked into the art,
// the cylindrical-lens compression (window width in rib widths) and its
// sideways nudge, the directional smear, the seam crossfade width that
// keeps sampling continuous, and the luminance-adaptive rod shading.
private const val FLUTED_RENDER_WIDTH = 576
private const val FLUTED_STRIPE_COUNT = 26
private const val FLUTED_SOFTEN_RADIUS = 10
private const val FLUTED_COMPRESS = 1.9f
private const val FLUTED_SHIFT = 0.35f
private const val FLUTED_BLUR = 0.35f
private const val FLUTED_SEAM_FADE = 0.35f
private const val FLUTED_SHADOW = 0.12f
private const val FLUTED_HIGHLIGHT = 0.10f

/**
 * A backdrop that IS the artwork: the shared aura re-exposed into a plate (see
 * [buildChromaPlate], [buildSunWashPlate] and [buildDitherBackdrop]) and printed
 * full bleed.
 *
 * The blurred cover is laid underneath as the base. Baking any of these plates
 * is a pass over a few hundred thousand pixels, and a share card can be on
 * screen for a few frames only, so the card opens on the listener's own artwork
 * and resolves into the print, instead of flashing an empty gradient first.
 *
 * That base is laid only while the plate is missing: the CHROMA and SUN_WASH
 * fallbacks are translucent recolourings of the cover, so the blur beneath is
 * what makes them read as the cover resolving into a print, and the DITHER
 * fallback opens on the same layered cover its screen is printed from — the
 * ASCII card's stack, with a dot screen instead of glyphs.
 */
@Composable
private fun ArtworkPlateBackdrop(
    palette: ShareThemePalette,
    art: ShareArtSource?,
) {
    if (art == null) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawPlateFallback(palette) }
        return
    }
    var aspect by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    val plate by produceState<Bitmap?>(initialValue = null, art, aspect, palette.backdrop) {
        if (aspect <= 0f) return@produceState
        val style = palette.backdrop
        value =
            runCatching {
                when (style) {
                    ShareBackdropStyle.SUN_WASH -> buildSunWashPlate(context, art, aspect)
                    ShareBackdropStyle.DITHER_FIELD -> buildDitherBackdrop(context, art, aspect, palette)
                    else -> buildChromaPlate(context, art, aspect)
                }
            }.getOrNull()
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        val measured = (size.width.toFloat() / size.height * 1000f).roundToInt() / 1000f
                        if (measured != aspect) aspect = measured
                    }
                }.clipToBounds(),
    ) {
        // The base the plate is a treatment OF. It also covers the bake: while
        // the plate is in flight the card shows the cover itself, blurred,
        // rather than a gradient standing in for it.
        //
        // Dropped once the plate lands: every plate is opaque, so afterwards
        // this is a decode, a full-card blur and a draw that nothing sees.
        if (plate == null) {
            ShareBlurBase(art = art)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baked = plate
            if (baked != null) {
                // The plate is baked at the card aspect, so a full-size
                // destination is a 1:1 map.
                drawImage(
                    image = baked.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawPlateFallback(palette)
            }
        }
        // Every plate is mid-toned by construction, so the sheet's own veil is
        // laid over it exactly as the other backdrops get theirs: this is what
        // holds the type off the picture.
        ShareReadabilityOverlay(palette)
    }
}

/** The plate's loading state, by scene. */
private fun DrawScope.drawPlateFallback(palette: ShareThemePalette) {
    when (palette.backdrop) {
        ShareBackdropStyle.SUN_WASH -> drawSunWashFallback(palette)
        ShareBackdropStyle.DITHER_FIELD -> drawDitherFallback(palette)
        else -> drawChromaFallback(palette)
    }
}

// Grain seed: deterministic speckles so the preview and the exported capture
// carry identical texture. Used by [drawShareFinish].
private const val GRAIN_SEED = 0x6A17

// ---------------------------------------------------------------------------
// CHROMA — the cover itself, printed in moonlight
//
// The plate is a treatment of the artwork, never a scene drawn on top of it.
// Three passes over the aura, all on the CPU so the preview and the exported
// PNG agree pixel for pixel: shape the light (contrast, then a bloom where a
// light source would be), step it into a handful of tones, then lay those onto
// a blue ramp. What prints is the cover the listener is actually listening to,
// read as a memory of it rather than as a photograph.
// ---------------------------------------------------------------------------

/** Width the plate is baked at. The aura is resampled to it and printed 1:1. */
private const val CHROMA_RENDER_WIDTH = 520

/** How hard the aura is pushed away from mid-grey before it is stepped. */
private const val CHROMA_CONTRAST = 1.30f
private const val CHROMA_LIFT = 0.02f

/**
 * Tones the light is quantised to. Seven is enough to hold a shape and few
 * enough that the bands are visible: the posterisation is the treatment.
 */
private const val CHROMA_POSTER_STEPS = 7f

/** The bloom — where the light on this page comes from. */
private const val CHROMA_BLOOM_X = 0.78f
private const val CHROMA_BLOOM_Y = 0.10f
private const val CHROMA_BLOOM_SIZE = 0.66f
private const val CHROMA_BLOOM_STRENGTH = 0.30f

/** How much of the cover's own colour survives the blue. */
private const val CHROMA_SOURCE_MIX = 0.26f

/** How far the foot of the page sinks, where the table prints white type. */
private const val CHROMA_FOOT_VEIL = 0.42f

/** The blue ramp, dark to light, as 24-bit RGB. */
private const val CHROMA_SHADOW = 0x0A1024
private const val CHROMA_MID = 0x243A66
private const val CHROMA_LIGHT = 0x9FB8E4

/** Opaque alpha in an ARGB pixel. */
private const val OPAQUE_ALPHA = -0x1000000

/**
 * Bakes the CHROMA plate.
 *
 * The aura is already a de-texted, tone-mapped memory of the cover, which is
 * the wrong source for a photograph and the right one for a print: it carries
 * masses and no lettering. Contrast separates those masses, the bloom gives the
 * page a light source, and the quantised ramp prints them in one blue. The
 * cover's own hue survives at [CHROMA_SOURCE_MIX], so a warm record still reads
 * warm under the blue instead of turning into a stock photo.
 */
private suspend fun buildChromaPlate(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): Bitmap? {
    val cacheKey = "chroma:${art.key}:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    return withContext(Dispatchers.Default) {
        val height =
            (CHROMA_RENDER_WIDTH / aspect)
                .roundToInt()
                .coerceIn(64, MAX_SAFE_BITMAP_EDGE_PX)
        val buffer = aura.artBuffer(CHROMA_RENDER_WIDTH, height)
        val out = IntArray(buffer.pixels.size)
        for (y in 0 until buffer.height) {
            val ny = y / (buffer.height - 1f)
            val veil = 1f - CHROMA_FOOT_VEIL * ny * ny
            val row = y * buffer.width
            for (x in 0 until buffer.width) {
                val nx = x / (buffer.width - 1f)
                val src = buffer.pixels[row + x]
                val luma = lumaOf(src).coerceIn(0f, 1f)
                val bloom = bloomAt(nx, ny, CHROMA_BLOOM_X, CHROMA_BLOOM_Y, CHROMA_BLOOM_SIZE)
                val tone =
                    (0.5f + (luma - 0.5f) * CHROMA_CONTRAST + CHROMA_LIFT + bloom * CHROMA_BLOOM_STRENGTH)
                        .coerceIn(0f, 1f)
                val step = (tone * CHROMA_POSTER_STEPS).roundToInt() / CHROMA_POSTER_STEPS
                val ramp =
                    if (step < 0.5f) {
                        mixRgb(CHROMA_SHADOW, CHROMA_MID, step * 2f)
                    } else {
                        mixRgb(CHROMA_MID, CHROMA_LIGHT, (step - 0.5f) * 2f)
                    }
                val printed = mixRgb(ramp, src and 0xFFFFFF, CHROMA_SOURCE_MIX)
                val r = (((printed shr 16) and 0xFF) * veil).roundToInt().coerceIn(0, 255)
                val g = (((printed shr 8) and 0xFF) * veil).roundToInt().coerceIn(0, 255)
                val b = ((printed and 0xFF) * veil).roundToInt().coerceIn(0, 255)
                out[row + x] = OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
            }
        }
        val plate = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        plate.setPixels(out, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        backdropBitmapCache.put(cacheKey, plate)
        plate
    }
}

/**
 * The plate before the artwork resolves: the same dark page and the same bloom
 * position as the bake, so the card does not jump when the print lands. Kept
 * translucent so [ShareBlurBase] underneath still shows the cover.
 */
private fun DrawScope.drawChromaFallback(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color(0xFF0B1330).copy(alpha = 0.80f),
                0.55f to Color(0xFF070C1E).copy(alpha = 0.86f),
                1f to Color(0xFF04060F).copy(alpha = 0.92f),
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors = listOf(Color(0xFFB8CDF2).copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * CHROMA_BLOOM_X, h * CHROMA_BLOOM_Y),
                radius = w * CHROMA_BLOOM_SIZE * 1.1f,
            ),
    )
}

// ---------------------------------------------------------------------------
// SUN_WASH — the same cover, printed on warm stock in morning light
//
// Not a sky. The light is in the plate: the aura is re-exposed toward paper,
// warmed, and given one wide bloom in the corner the light comes from. A drawn
// sun with a disc and a halo was the previous attempt, and a circle is a
// sticker no matter how soft its edge is — this plate casts light without
// drawing the object that casts it.
// ---------------------------------------------------------------------------

/** Width the plate is baked at, matching the moonlit plate so the pages rhyme. */
private const val SUN_WASH_RENDER_WIDTH = 520

/** Exposure and contrast of the re-exposed cover: low contrast, high light. */
private const val SUN_WASH_EXPOSURE = 0.70f
private const val SUN_WASH_CONTRAST = 0.52f

/** The bloom: wide, and high in the corner, so its own centre is off-page. */
private const val SUN_WASH_BLOOM_X = 0.82f
private const val SUN_WASH_BLOOM_Y = 0.04f
private const val SUN_WASH_BLOOM_SIZE = 0.95f
private const val SUN_WASH_BLOOM_STRENGTH = 0.26f

/** How much of the cover's own colour survives the warm stock. */
private const val SUN_WASH_SOURCE_MIX = 0.16f

/** How much weight the foot of the sheet carries, where the rows print. */
private const val SUN_WASH_FOOT = 0.20f

/** Warm stock, warm shadow, warm light, as 24-bit RGB. */
private const val SUN_WASH_PAPER = 0xFDF8EF
private const val SUN_WASH_SHADOW = 0xE3C4A6
private const val SUN_WASH_WARM = 0xFFD9A0

/**
 * Bakes the SUN_WASH plate.
 *
 * Same source as the moonlit plate — the aura — exposed the other way: lifted
 * toward paper instead of sunk into blue, with contrast pulled down so the
 * cover reads as a bright print rather than as a photograph left in the sun.
 * The bloom is added as light, not as an object: it warms whatever mass it
 * lands on and disappears into the paper where there is nothing to warm.
 */
private suspend fun buildSunWashPlate(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): Bitmap? {
    val cacheKey = "sunwash:${art.key}:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    return withContext(Dispatchers.Default) {
        val height =
            (SUN_WASH_RENDER_WIDTH / aspect)
                .roundToInt()
                .coerceIn(64, MAX_SAFE_BITMAP_EDGE_PX)
        val buffer = aura.artBuffer(SUN_WASH_RENDER_WIDTH, height)
        val out = IntArray(buffer.pixels.size)
        for (y in 0 until buffer.height) {
            val ny = y / (buffer.height - 1f)
            val row = y * buffer.width
            for (x in 0 until buffer.width) {
                val nx = x / (buffer.width - 1f)
                val src = buffer.pixels[row + x]
                val luma = lumaOf(src).coerceIn(0f, 1f)
                val bloom = bloomAt(nx, ny, SUN_WASH_BLOOM_X, SUN_WASH_BLOOM_Y, SUN_WASH_BLOOM_SIZE)
                val tone =
                    (SUN_WASH_EXPOSURE + (luma - 0.5f) * SUN_WASH_CONTRAST + bloom * SUN_WASH_BLOOM_STRENGTH)
                        .coerceIn(0f, 1f)
                var printed = mixRgb(SUN_WASH_SHADOW, SUN_WASH_PAPER, tone)
                // The bloom is light falling on the page, so it warms the mass
                // it lands on instead of painting a disc over it.
                printed = mixRgb(printed, SUN_WASH_WARM, bloom * SUN_WASH_BLOOM_STRENGTH)
                printed = mixRgb(printed, src and 0xFFFFFF, SUN_WASH_SOURCE_MIX)
                // The foot of the sheet holds more weight, so the last rows do
                // not sit on the brightest part of the page.
                printed = mixRgb(printed, SUN_WASH_SHADOW, SUN_WASH_FOOT * ny * ny)
                val r = ((printed shr 16) and 0xFF).coerceIn(0, 255)
                val g = ((printed shr 8) and 0xFF).coerceIn(0, 255)
                val b = (printed and 0xFF).coerceIn(0, 255)
                out[row + x] = OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
            }
        }
        val plate = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        plate.setPixels(out, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        backdropBitmapCache.put(cacheKey, plate)
        plate
    }
}

/** The warm page before the artwork resolves, translucent over the cover. */
private fun DrawScope.drawSunWashFallback(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color(0xFFFFFBF4).copy(alpha = 0.86f),
                0.55f to Color(0xFFFDF4E7).copy(alpha = 0.90f),
                1f to Color(0xFFF7E9D8).copy(alpha = 0.94f),
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color(0xFFFFE3A8).copy(alpha = 0.40f),
                        Color(0xFFFFD9A0).copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                center = Offset(w * SUN_WASH_BLOOM_X, h * SUN_WASH_BLOOM_Y),
                radius = w * SUN_WASH_BLOOM_SIZE,
            ),
    )
}

/**
 * The aura resampled to a plate size, as an ARGB buffer.
 *
 * The field is [ART_FIELD_DISPLAY_WIDTH] across, which at card scale is a
 * handful of pixels per printed dot. Resampling it up is deliberate: the
 * softness is the treatment. Both plates print a memory of the cover, not a
 * copy of it.
 */
private fun Bitmap.artBuffer(
    width: Int,
    height: Int,
): ArtBuffer {
    val up = fieldUpscale(width, height)
    val pixels = IntArray(up.width * up.height)
    up.getPixels(pixels, 0, up.width, 0, 0, up.width, up.height)
    return ArtBuffer(up.width, up.height, pixels)
}

/** A resampled aura: its size plus its ARGB pixels. */
private class ArtBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * A soft elliptical falloff around one point, used for both plates' blooms.
 * Cubic rather than gaussian: it reaches zero inside its own bounds, so no edge
 * of the light ever shows.
 */
private fun bloomAt(
    nx: Float,
    ny: Float,
    cx: Float,
    cy: Float,
    size: Float,
): Float {
    val dx = (nx - cx) / size
    val dy = (ny - cy) / size
    val d = (dx * dx + dy * dy).coerceIn(0f, 1f)
    val falloff = 1f - d
    return falloff * falloff * falloff
}

/** Channel-wise mix of two 24-bit RGB values: t = 0 is a, 1 is b. */
private fun mixRgb(
    a: Int,
    b: Int,
    t: Float,
): Int {
    val f = t.coerceIn(0f, 1f)
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val r = (ar + (((b shr 16) and 0xFF) - ar) * f).roundToInt().coerceIn(0, 255)
    val g = (ag + (((b shr 8) and 0xFF) - ag) * f).roundToInt().coerceIn(0, 255)
    val bl = (ab + ((b and 0xFF) - ab) * f).roundToInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or bl
}

/**
 * The plate before (or without) a cover: the same Bayer screen over a fixed
 * diagonal ramp, so the card opens as a print rather than as an empty
 * gradient. Deterministic geometry — preview and capture agree.
 */
private fun DrawScope.drawDitherFallback(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    drawRect(color = palette.glowBottom)
    val cell = (w / DITHER_COLUMNS).coerceAtLeast(2f)
    val cols = (w / cell).toInt() + 1
    val rows = (h / cell).toInt() + 1
    val ink = palette.accent.copy(alpha = 0.55f)
    // Same rule as the bake: on a light sheet the ramp runs the other way, so
    // the corner that is dark on paper is the corner that is empty on film.
    val inkIsDark = lumaOf(palette.glowBottom.toArgb()) > lumaOf(palette.accent.toArgb())
    // One path, one draw. This runs on every frame until the plate lands, and
    // a cell-per-drawRect version of it issued tens of thousands of native
    // draws per frame while the sheet was opening.
    val dots =
        Path().apply {
            for (row in 0 until rows) {
                val bayerRow = (row % 4) * 4
                val top = row * cell
                for (col in 0 until cols) {
                    val ramp = (col.toFloat() / cols) * 0.6f + (row.toFloat() / rows) * 0.4f
                    val tone = if (inkIsDark) 1f - ramp else ramp
                    val value = (0.5f + (tone - 0.5f) * DITHER_CONTRAST + DITHER_LIFT).coerceIn(0f, 1f)
                    val bayer = DitherBayer4x4[bayerRow + (col % 4)] / DITHER_BAYER_SCALE
                    if (value <= bayer) continue
                    // Whole cells, like the bake: the plate is a screen, not a
                    // grid of inset specks.
                    val left = col * cell
                    addRect(Rect(left, top, left + cell, top + cell))
                }
            }
        }
    drawPath(path = dots, color = ink)
}

// ---------------------------------------------------------------------------
// DITHER_FIELD — ordered dither, ported from the Aceternity dither shader
// (components/ui/dither-shader.tsx). The reference is WebGL; a share card has
// to render identically in a preview and in the software-canvas PNG export, so
// the same maths runs once on the CPU into a bitmap instead of per frame on the
// GPU.
//
// Kept faithful to the reference where it matters: the 4x4 Bayer matrix and its
// /16 scale, the per-cell sample of the source, the threshold bias applied
// after the pattern, and one ink on one paper (its "duotone" mode). Dropped
// deliberately: the animation clock (a share card is a still), pixelRatio (the
// plate is rasterised at a fixed width), and the four colour modes (a theme
// states its ink and its paper, it does not offer a picker).
// ---------------------------------------------------------------------------

/** Columns of dots across the plate — the visible "screen ruling" of the print. */
private const val DITHER_COLUMNS = 200

/**
 * Bake width in pixels. With [DITHER_COLUMNS] this makes a cell of
 * [DITHER_RENDER_WIDTH] / [DITHER_COLUMNS] = 4px: a screen whose dots can be
 * counted one by one, the way the reference prints them, rather than a film
 * grain that reads as noise at share size.
 */
private const val DITHER_RENDER_WIDTH = 800

/** 4x4 ordered-dither matrix, row-major, exactly as in the reference. */
private val DitherBayer4x4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** The reference divides by 16 for the 4x4 matrix. */
private const val DITHER_BAYER_SCALE = 16f

/**
 * Tone shaping before the threshold. The aura is already tone-mapped toward a
 * mid mean; a little extra contrast is what turns it into a print with
 * legible masses instead of a uniform field of dots.
 */
private const val DITHER_CONTRAST = 1.15f
private const val DITHER_LIFT = 0.02f

/**
 * How thin the plate is printed: the tone is raised to this power before it
 * meets the Bayer threshold. At 1f the levelled field sits on the threshold
 * and half the card comes out as ink. Above 1f only the cover's dark masses
 * clear a dot, so the sheet reads as stock with a picture pressed into it
 * instead of as a page printed solid behind its own type.
 */
private const val DITHER_INK_GAMMA = 2.8f

/**
 * How much of the paper the ink is mixed with before the plate is printed.
 * A plate in full-strength ink is a picture that eats the card: the type is
 * set over the same field the dots are in, and at share size a dark screen
 * under body copy is unreadable. Mixed once, up front, the screen keeps its
 * whole dots and its countable ruling while sitting under the page.
 */
private const val DITHER_INK_WEIGHT = 0.42f



/**
 * The dithered plate.
 *
 * One area-averaged sample per cell, shaped once, compared against the Bayer
 * entry for that cell; a cell that clears the threshold is filled with the ink
 * whole, and every other cell keeps the paper. That is the reference exactly:
 * a cleared cell is a dot, not a tint, and the tone of the picture comes from
 * how many dots cleared, never from how strongly each one was laid down.
 * Blending the ink in by strength is what flattened the earlier cuts of this
 * plate into a grey wash: half-inked cells on every part of the page read as
 * fog, not as a photograph.
 */
private fun ditherPlate(
    luminance: FloatArray,
    columns: Int,
    rows: Int,
    cell: Int,
    width: Int,
    height: Int,
    palette: ShareThemePalette,
): Bitmap {
    val paperColour = palette.glowBottom
    // One light ink, mixed with the paper up front. The tone comes from how many
    // dots cleared, not how strongly each was printed, so holding the field back
    // under the type means mixing once and printing only whole dots. That keeps
    // the dots crisp and countable.
    val inkColour = lerp(paperColour, palette.accent, DITHER_INK_WEIGHT).toArgb() or (0xFF shl 24)
    val paper = paperColour.toArgb()
    val ink = inkColour
    val pixels = IntArray(width * height)
    pixels.fill(paper or (0xFF shl 24))

    // Which way round the plate is inked. A print carries its picture in the
    // ink either way: on a dark sheet the ink is the light (a gold plate on
    // near-black), on a light sheet it is the shadow (sepia dots on cream). The
    // bake reads that from the palette instead of assuming one of them.
    val inkIsDark = lumaOf(paper) > lumaOf(ink)

    for (row in 0 until rows) {
        val bayerRow = (row % 4) * 4
        val y0 = row * cell
        val y1 = minOf(y0 + cell, height)
        for (col in 0 until columns) {
            val value = luminance[row * columns + col]
            val tone = if (inkIsDark) 1f - value else value
            val bayer = DitherBayer4x4[bayerRow + (col % 4)] / DITHER_BAYER_SCALE
            // The screen is printed *thin*. A field levelled straight to 0..1
            // has its mean on the half-tone threshold, and over a whole card
            // that means half the page is ink — which is a page no type can be
            // set on. Raising the tone before it meets the threshold prints
            // only the masses that are genuinely dark and leaves the rest as
            // stock, which is how a one-ink plate is printed.
            val inked = tone.pow(DITHER_INK_GAMMA)
            // Contrast around mid-grey, then a touch of lift: a dark cover has
            // to put *some* dots on the page or the card is a black rectangle,
            // and a light one needs a whisper of tone or the sheet is empty.
            val shaped = (((inked - 0.5f) * DITHER_CONTRAST) + 0.5f + DITHER_LIFT).coerceIn(0f, 1f)
            if (shaped <= bayer) continue
            val colour = inkColour
            val x0 = col * cell
            val x1 = minOf(x0 + cell, width)
            for (y in y0 until y1) {
                val rowStart = y * width
                for (x in x0 until x1) {
                    pixels[rowStart + x] = colour
                }
            }
        }
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/**
 * Bakes the dithered plate for [art] at the card aspect.
 *
 * The screen is printed over the shared aura rather than over the cover: the
 * same layering the ASCII card uses, with dots instead of glyphs. The layer is
 * what matters, not the decode — a dot screen is a *resampling*, so whatever
 * still reads in its source still reads in the dots, and printing the cover
 * gave the sleeve's title, logotype and credits back as sharp moiré over the
 * card's own type. The aura has already melted that printed matter into masses
 * and light, so the plate is left with the composition to print: one sample per
 * dot, thresholded whole, so the picture survives as the pattern of dots.
 */
private suspend fun buildDitherBackdrop(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
    palette: ShareThemePalette,
): Bitmap? {
    // The plate carries two theme colours, so the cache key carries them: a
    // baked plate in one ink must never be reused for a card in another.
    val inkKey = palette.accent.toArgb().toString() + ":" + palette.glowBottom.toArgb()
    val cacheKey = "dither:" + art.key + ":" + aspect + ":" + inkKey
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    // Cover art + layering: the aura is the layer the screen is printed over,
    // so the plate treats the cover instead of reprinting it.
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    return withContext(Dispatchers.Default) {
        val columns = DITHER_COLUMNS
        val cell = (DITHER_RENDER_WIDTH / DITHER_COLUMNS).coerceAtLeast(2)
        val width = columns * cell
        val height =
            (DITHER_RENDER_WIDTH / aspect)
                .roundToInt()
                .coerceIn(64, MAX_SAFE_BITMAP_EDGE_PX)
        val rows = (height + cell - 1) / cell
        val plateHeight = rows * cell

        // Reduce the layered field to one sample per dot. The platform's
        // filtered scale is a box average at this ratio, so each sample
        // already *is* the patch it will be printed as — no separate pass.
        // The aura itself is cached and shared, so it is never recycled here.
        val small = Bitmap.createScaledBitmap(aura, columns, rows, true)
        val samples = IntArray(columns * rows)
        small.getPixels(samples, 0, columns, 0, 0, columns, rows)
        small.recycle()
        val luminance = FloatArray(columns * rows) { lumaOf(samples[it]) }

        // Auto-levels between the 2nd and 98th percentiles. A cover is rarely
        // printed on a dot screen without this: a bright sleeve has almost no
        // tone below the threshold and a dark one prints almost solid, and the
        // card needs the same usable range from either.
        val ranked = luminance.copyOf()
        ranked.sort()
        val low = ranked[(ranked.size * 0.02f).toInt().coerceIn(0, ranked.lastIndex)]
        val high = ranked[((ranked.size - 1) * 0.98f).toInt().coerceIn(0, ranked.lastIndex)]
        val span = (high - low).coerceAtLeast(0.15f)
        for (i in luminance.indices) {
            luminance[i] = ((luminance[i] - low) / span).coerceIn(0f, 1f)
        }

        val plate = ditherPlate(luminance, columns, rows, cell, width, plateHeight, palette)
        backdropBitmapCache.put(cacheKey, plate)
        plate
    }
}

/**
 * Shared premium finish drawn over every backdrop except [ShareBackdropStyle.PAPER_PAGE]
 * and [ShareBackdropStyle.DITHER_FIELD] (which carry their own surface): a light deterministic film grain that kills
 * flat-gradient banding, plus a cool white-blue frost washing down from the top edge.
 * Fixed low alphas — intentionally NOT scaled by decorationAlpha — so MIDNIGHT's faint
 * glow setting still gets texture. Pure Canvas so preview and capture match.
 */
private fun DrawScope.drawShareFinish(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    // Cool frost from the top: the white-blue lift that keeps dark scenes from
    // reading as crushed black blur.
    if (palette.isDark) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color(0xFFDBEAFE).copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                    startY = 0f,
                    endY = h * 0.22f,
                ),
        )
    } else {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    startY = 0f,
                    endY = h * 0.22f,
                ),
        )
    }
    // Light film grain.
    val rng = Random(GRAIN_SEED + palette.backdrop.ordinal * 0x9E37)
    repeat(650) {
        val x = rng.nextFloat() * w
        val y = rng.nextFloat() * h
        val s = 1f + rng.nextFloat() * 1.6f
        val light = rng.nextBoolean()
        drawRect(
            color =
                if (palette.isDark) {
                    if (light) {
                        Color.White.copy(alpha = 0.035f)
                    } else {
                        Color.Black.copy(alpha = 0.06f)
                    }
                } else {
                    if (light) {
                        Color.White.copy(alpha = 0.05f)
                    } else {
                        Color.Black.copy(alpha = 0.045f)
                    }
                },
            topLeft = Offset(x, y),
            size = Size(s, s),
        )
    }
}

// ASCII backdrop parameters: render width of the baked composite, the blurred
// art base that gives the card its colour atmosphere, and the phosphor bloom
// drawn under the crisp glyphs so they read as luminous terminals.
private const val ASCII_RENDER_WIDTH = 576
private const val ASCII_BASE_DIM = 0.55f
private const val ASCII_GLOW_BLUR_RADIUS = 7
private const val ASCII_GLOW_ALPHA = 0.85f
private const val ASCII_BLUR_ROUNDS = 2

/**
 * Loads the artwork through Coil (sharing the app's memory/disk cache) and
 * converts it into an [AsciiArtGrid]. The conversion is *dynamic*: the
 * artwork's own luminance histogram is auto-leveled and re-gamma'd per image,
 * so dark, hazy and blown-out covers all fill the ramp instead of collapsing
 * into a black screen.
 */
private suspend fun buildAsciiArtGrid(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): AsciiArtGrid? {
    // Sampled from the de-texted aura, never the raw cover: cover typography
    // rendered into glyph cells reads as a legible smear of letterforms, which
    // is exactly the artefact this backdrop is meant to avoid.
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    return withContext(Dispatchers.Default) {
        // Row density that keeps the artwork undistorted on glyph cells
        // taller than wide: rows/columns must be (colStep/rowStep)/aspect.
        // Bounded like every other aspect-derived size: this drives the sampled grid and its
        // IntArray, so a degenerate aspect must not blow them up.
        val rows =
            (ASCII_COLUMNS * (ASCII_COL_STEP / ASCII_ROW_STEP) / aspect)
                .roundToInt()
                .coerceIn(8, MAX_SAFE_BITMAP_EDGE_PX)
        val sampled = aura.downscaledTo(ASCII_COLUMNS, rows)
        val pixels = IntArray(ASCII_COLUMNS * rows)
        sampled.getPixels(pixels, 0, ASCII_COLUMNS, 0, 0, ASCII_COLUMNS, rows)

        // Pass 1 — raw Rec.709 luminance per cell + luminance histogram.
        val count = pixels.size
        val lum = FloatArray(count)
        val hist = IntArray(64)
        for (i in 0 until count) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            lum[i] = l
            hist[(l * 63f).toInt().coerceIn(0, 63)]++
        }

        // Pass 2 — dynamic tone mapping. A fixed contrast curve only works
        // for well-exposed art; dark or hazy covers collapse into empty
        // cells and the card reads as a black screen. Instead, auto-level
        // the artwork's own histogram (5th–98th percentile stretch) and
        // pick a gamma that re-centres the mean, so every artwork fills
        // the full ramp.
        var lo = 0f
        var hi = 1f
        val loTarget = (count * 0.05f).toInt()
        val hiTarget = (count * 0.98f).toInt()
        var acc = 0
        for (bin in 0..63) {
            acc += hist[bin]
            if (acc >= loTarget) {
                lo = bin / 63f
                break
            }
        }
        acc = 0
        for (bin in 0..63) {
            acc += hist[bin]
            if (acc >= hiTarget) {
                hi = bin / 63f
                break
            }
        }
        // Very flat art (near-solid covers): keep the full range so the
        // ordered dither still carries texture instead of one flat glyph.
        if (hi - lo < 0.20f) {
            lo = 0f
            hi = 1f
        }
        val range = (hi - lo).coerceAtLeast(0.05f)
        var sum = 0f
        val stretched = FloatArray(count)
        for (i in 0 until count) {
            val b = ((lum[i] - lo) / range).coerceIn(0f, 1f)
            stretched[i] = b
            sum += b
        }
        val mean = (sum / count).coerceIn(0.02f, 0.95f)
        val gamma = (ln(0.48f) / ln(mean)).coerceIn(0.6f, 1.8f)

        // Pass 3 — ramp selection + colour.
        val rampIndices = IntArray(count)
        val brightness = FloatArray(count)
        val colors = IntArray(count)
        val rampMax = ASCII_RAMP.length - 1
        val hsv = FloatArray(3)
        for (i in 0 until count) {
            val x = i % ASCII_COLUMNS
            val y = i / ASCII_COLUMNS
            val b = stretched[i].pow(gamma)
            brightness[i] = b
            // Ordered (Bayer 4x4) dithering before quantization — the
            // ascii-magic retro trick: smooth gradients cross-hatch into
            // glyph texture instead of banding into flat empty bands.
            val dither =
                ((ASCII_BAYER_4X4[y and 3][x and 3] + 0.5f) / 16f - 0.5f) *
                    ASCII_DITHER
            rampIndices[i] = ((b + dither) * rampMax + 0.5f).toInt().coerceIn(0, rampMax)
            // Richer colour: the artwork's own hue, saturated for the dark
            // field, with the value riding the normalized brightness — but
            // with a floor, so dark covers still paint a clearly legible
            // picture. The brightest cells lean toward white for pop.
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val bl = px and 0xFF
            android.graphics.Color.RGBToHSV(r, g, bl, hsv)
            hsv[1] = (hsv[1] * 1.25f + 0.06f).coerceAtMost(1f)
            hsv[2] = (0.45f + 0.55f * b).coerceAtMost(1f)
            var color = android.graphics.Color.HSVToColor(hsv)
            if (b > 0.85f) {
                color = mixTowardWhite(color, (b - 0.85f) / 0.6f)
            }
            colors[i] = color
        }
        AsciiArtGrid(ASCII_COLUMNS, rows, rampIndices, brightness, colors)
    }
}

/**
 * Builds the full ASCII backdrop as one baked bitmap:
 *
 * 1. Base — the artwork heavily blurred and dimmed to terminal darkness, so the
 *    card picks up the art's colour atmosphere instead of floating glyphs on a
 *    flat void (the "cheap poster" look).
 * 2. Glow — the glyph layer box-blurred into a phosphor bloom under the crisp
 *    glyphs, so they read as luminous, not painted.
 * 3. Glyphs — the crisp ASCII conversion, same ramp/dither/tone mapping as
 *    before, on the wave field's monospace geometry.
 *
 * CPU-rendered into a single bitmap, so the live preview and the software-canvas
 * share capture stay pixel-identical.
 */
private suspend fun buildAsciiArtBackdrop(
    context: Context,
    art: ShareArtSource,
    aspect: Float,
): Bitmap? {
    val cacheKey = "ascii:${art.key}:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val grid = buildAsciiArtGrid(context, art, aspect) ?: return null
    // Same de-texted field the grid was sampled from (one cached decode): the
    // colour base must not carry the typography the glyphs dropped.
    val aura = buildArtAuraField(context, art, aspect) ?: return null
    val palette = ShareTheme.ASCII.palette
    return withContext(Dispatchers.Default) {
        val w = ASCII_RENDER_WIDTH
        // Aspect-derived height, bounded to the painter ceiling (see the fluted-glass path).
        val h = (w / aspect).roundToInt().coerceIn(64, MAX_SAFE_BITMAP_EDGE_PX)
        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(composite)

        // 1 — the aura field, dimmed to terminal darkness. Warped rather than
        // stretched so the base keeps the same soft currents as the display field.
        canvas.drawBitmap(aura.fieldUpscale(w, h).dimmed(ASCII_BASE_DIM), 0f, 0f, null)

        // 2 — the soft phosphor halo behind the glyph art.
        val halo = Paint().apply { isAntiAlias = true }
        halo.shader =
            RadialGradient(
                w * 0.5f,
                h * 0.28f,
                w * 0.9f,
                intArrayOf(
                    palette.glowTop.copy(alpha = 0.10f * palette.decorationAlpha).toArgb(),
                    Color.Transparent.toArgb(),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), halo)

        // 3 — glyph layer: phosphor glow underneath, crisp glyphs on top.
        val glyphs = renderAsciiGlyphLayer(grid, w, h, palette)
        val glow = glyphs.boxBlurredCopy(ASCII_GLOW_BLUR_RADIUS, ASCII_BLUR_ROUNDS)
        val glowPaint = Paint().apply { alpha = (ASCII_GLOW_ALPHA * 255f).roundToInt() }
        canvas.drawBitmap(glow, 0f, 0f, glowPaint)
        canvas.drawBitmap(glyphs, 0f, 0f, null)
        backdropBitmapCache.put(cacheKey, composite)
        composite
    }
}

/**
 * Renders the precomputed [AsciiArtGrid] into a transparent bitmap with the
 * monospace geometry of the wave field ([ASCII_COL_STEP] advance,
 * [ASCII_ROW_STEP]-derived line height). The grid was built with matching row
 * density, so the artwork lands on the card undistorted — exactly like
 * ContentScale.Crop would place it.
 */
private fun renderAsciiGlyphLayer(
    grid: AsciiArtGrid,
    w: Int,
    h: Int,
    palette: ShareThemePalette,
): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cellW = w.toFloat() / grid.columns
    val cellH = h.toFloat() / grid.rows
    val textSize = cellW / ASCII_COL_STEP
    val paint =
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            this.textSize = textSize
        }
    val ramp = ASCII_RAMP
    // Baseline that visually centres each glyph in its cell.
    var y = cellH * 0.5f + textSize * 0.38f
    var row = 0
    while (row < grid.rows && y - textSize < h) {
        var x = cellW * 0.14f
        var column = 0
        while (column < grid.columns && x < w) {
            val index = row * grid.columns + column
            val rampIndex = grid.rampIndices[index]
            if (rampIndex > 0) {
                val b = grid.brightness[index]
                // High visibility floor: every cell keeps its colour so
                // dark covers still read as a picture, not a void.
                val alpha =
                    ((0.42f + 0.58f * b) * palette.decorationAlpha)
                        .coerceIn(0f, 1f)
                paint.color = (grid.colors[index] and 0x00FFFFFF) or
                    ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)
                canvas.drawText(ramp[rampIndex].toString(), x, y, paint)
            }
            x += cellW
            column++
        }
        y += cellH
        row++
    }
    return bitmap
}

/** Multiplies every pixel's RGB by [factor]; alpha is kept. */
private fun Bitmap.dimmed(factor: Float): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = ((c shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
        pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/** Gaussian-approximating box blur (via [blurBoxPass]) as a new bitmap. */
private fun Bitmap.boxBlurredCopy(
    radius: Int,
    rounds: Int,
): Bitmap {
    var pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(rounds) {
        pixels = blurBoxPass(pixels, width, height, radius, horizontal = true)
        pixels = blurBoxPass(pixels, width, height, radius, horizontal = false)
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/**
 * The liquid-glass pools, kept as the fluted glass loading/missing-art state:
 * iridescent blobs and sheen streaks on the smoked pane.
 */
private fun DrawScope.drawGlassFluidPools(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    val colors = listOf(palette.glowTop, palette.glowBottom, palette.accent)

    // Vertical fluted glass ribs (cylindrical architectural glass pane texture)
    val stripeCount = 36
    val stripeW = w / stripeCount
    for (i in 0 until stripeCount) {
        val x = i * stripeW
        // Soft cylindrical shadow seam on left
        drawLine(
            color = Color.Black.copy(alpha = 0.16f * palette.decorationAlpha),
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 1.dp.toPx(),
        )
        // Specular highlight seam on right
        drawLine(
            color = Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
            start = Offset(x + stripeW, 0f),
            end = Offset(x + stripeW, h),
            strokeWidth = 0.8.dp.toPx(),
        )
    }

    // Iridescent fluid pools: elongated, tilted radial blobs pooling
    // like molten glass inside a dark smoked pane.
    GlassFluidBlobs.forEach { blob ->
        val color = colors[blob.colorIndex % colors.size]
        val center = Offset(blob.cx * w, blob.cy * h)
        val radius = maxOf(blob.rx, blob.ry) * w
        rotate(degrees = blob.rot, pivot = center) {
            drawOval(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                color.copy(alpha = blob.alpha * palette.decorationAlpha),
                                color.copy(alpha = blob.alpha * 0.45f * palette.decorationAlpha),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = radius,
                    ),
                topLeft = Offset(center.x - blob.rx * w, center.y - blob.ry * w),
                size = Size(blob.rx * 2 * w, blob.ry * 2 * w),
            )
        }
    }
    // Diagonal sheen streaks — the specular highlights that sell
    // the pane-of-glass read.
    rotate(degrees = 26f, pivot = Offset(w * 0.5f, h * 0.5f)) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
                            Color.Transparent,
                        ),
                ),
            topLeft = Offset(w * 0.16f, -h * 0.45f),
            size = Size(w * 0.10f, h * 1.9f),
        )
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f * palette.decorationAlpha),
                            Color.Transparent,
                        ),
                ),
            topLeft = Offset(w * 0.62f, -h * 0.45f),
            size = Size(w * 0.045f, h * 1.9f),
        )
    }
    // Gloss along the top edge, like light catching the rim.
    drawRect(
        brush =
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
                        Color.Transparent,
                    ),
                startY = 0f,
                endY = h * 0.12f,
            ),
    )
}

/** ContentScale.Crop equivalent: centre-crops the bitmap to [aspect] (w/h). */
private fun Bitmap.centerCroppedToAspect(aspect: Float): Bitmap {
    val srcAspect = width.toFloat() / height
    val cropW: Int
    val cropH: Int
    if (srcAspect > aspect) {
        cropH = height
        cropW = (height * aspect).roundToInt().coerceAtMost(width)
    } else {
        cropW = width
        cropH = (width / aspect).roundToInt().coerceAtMost(height)
    }
    val left = (width - cropW) / 2
    val top = (height - cropH) / 2
    return Bitmap.createBitmap(this, left, top, cropW.coerceAtLeast(1), cropH.coerceAtLeast(1))
}

/**
 * High-quality area-average downscale to one pixel per glyph cell: successive
 * halvings keep the cells faithful to the artwork — a single bilinear step
 * this deep would alias most of the detail away.
 */
private fun Bitmap.downscaledTo(
    targetW: Int,
    targetH: Int,
): Bitmap {
    var current = this
    while (current.width > targetW * 2 && current.height > targetH * 2) {
        current =
            Bitmap.createScaledBitmap(
                current,
                (current.width / 2).coerceAtLeast(targetW),
                (current.height / 2).coerceAtLeast(targetH),
                true,
            )
    }
    return Bitmap.createScaledBitmap(current, targetW, targetH, true)
}

/**
 * Reduces the artwork to the surface every artwork theme is built from.
 *
 * The brief has two halves that pull against each other. The cover's printed
 * matter — title, logotype, credits, barcode — must stop reading as text. But
 * the cover itself, its composition, its colour, its light, is the whole reason
 * an artwork theme is worth having. Deleting the cover satisfies the first half
 * and destroys the second: a palette or a flat wash is clean and has nothing
 * left of the record. Blurring it satisfies the second and leaves the first
 * legible, because blur is composition-preserving and cover type is *large*, so
 * it survives every radius that still leaves a picture in the frame.
 *
 * The resolution that squares both is a coarse mosaic:
 *
 * 1. The cover is sampled down to [ART_AURA_WIDTH] across. At that width a
 *    letterform is a couple of pixels — strokes can no longer join into an
 *    alphabet — while a composition is still a composition: a figure is still a
 *    figure-shaped mass, a light is still a light, a dark corner still dark.
 * 2. The cell grid is box-blurred [ART_FIELD_SOFTEN_ROUNDS] times, so the
 *    mosaic reads as soft colour rather than as tiles once a theme has laid its
 *    scrim over it.
 * 3. Saturation, mid-contrast and exposure are restored. Sampling down is an
 *    average, and averages go grey and flat; without this the field comes back
 *    looking like a washed-out thumbnail.
 *
 * Nothing here is a blur at card resolution, so no enlarged type can survive;
 * nothing here throws the cover away, so the backdrop still reads as the record
 * it came from.
 */
private fun Bitmap.artAuraField(aspect: Float): Bitmap {
    val fieldW = ART_AURA_WIDTH
    val fieldH = (fieldW / aspect).roundToInt().coerceIn(8, fieldW * 8)
    if (fieldW < 4 || fieldH < 4) {
        return centerCroppedToAspect(aspect).downscaledTo(fieldW, fieldH)
    }

    // 1 — sample the cover down to the mosaic.
    val count = fieldW * fieldH
    val mosaic = centerCroppedToAspect(aspect).downscaledTo(fieldW, fieldH)
    var pixels = IntArray(count)
    mosaic.getPixels(pixels, 0, fieldW, 0, 0, fieldW, fieldH)

    // 2 — melt the cell grid. One round still reads as tiles under a theme's
    // scrim, two reads as colour. The print is already gone: step 1 removed it.
    repeat(ART_FIELD_SOFTEN_ROUNDS) {
        pixels = blurBoxPass(pixels, fieldW, fieldH, 1, horizontal = true)
        pixels = blurBoxPass(pixels, fieldW, fieldH, 1, horizontal = false)
    }

    // 3 — put the colour and the separation back that the average took out.
    for (i in 0 until count) {
        val px = pixels[i]
        val r = ((px shr 16) and 0xFF) / 255f
        val g = ((px shr 8) and 0xFF) / 255f
        val b = (px and 0xFF) / 255f
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val r2 = midContrast((l + (r - l) * ART_FIELD_SATURATION).coerceIn(0f, 1f))
        val g2 = midContrast((l + (g - l) * ART_FIELD_SATURATION).coerceIn(0f, 1f))
        val b2 = midContrast((l + (b - l) * ART_FIELD_SATURATION).coerceIn(0f, 1f))
        pixels[i] =
            (0xFF shl 24) or
            ((r2 * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g2 * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (b2 * 255f).roundToInt().coerceIn(0, 255)
    }

    // 4 — exposure, so a dark cover still fills the card.
    var sumLuma = 0f
    for (i in 0 until count) sumLuma += lumaOf(pixels[i]).coerceIn(0f, 1f)
    val meanLuma = (sumLuma / count).coerceIn(0.02f, 0.95f)
    val gamma = (ln(ART_FIELD_TARGET_MEAN) / ln(meanLuma)).coerceIn(0.45f, 2.4f)
    val curve = FloatArray(256) { ((it / 255f).pow(gamma)).coerceIn(0f, 1f) }
    fun shape(v: Float): Float {
        val pos = (v.coerceIn(0f, 1f) * 255f).coerceIn(0f, 255f)
        val i0 = pos.toInt().coerceAtMost(254)
        val t = pos - i0
        return curve[i0] + (curve[i0 + 1] - curve[i0]) * t
    }
    for (i in 0 until count) {
        val px = pixels[i]
        val r = shape(((px shr 16) and 0xFF) / 255f)
        val g = shape(((px shr 8) and 0xFF) / 255f)
        val b = shape((px and 0xFF) / 255f)
        pixels[i] =
            (0xFF shl 24) or
            ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (b * 255f).roundToInt().coerceIn(0, 255)
    }

    val out = Bitmap.createBitmap(fieldW, fieldH, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, fieldW, 0, 0, fieldW, fieldH)
    return out
}

/** Pushes a channel away from mid-grey — the mosaic has been averaged flat. */
private fun midContrast(v: Float): Float = (0.5f + (v - 0.5f) * ART_FIELD_CONTRAST).coerceIn(0f, 1f)


/** Rec. 709 luma of a packed ARGB pixel, as 0..1. */
private fun lumaOf(px: Int): Float =
    (0.2126f * ((px shr 16) and 0xFF) +
        0.7152f * ((px shr 8) and 0xFF) +
        0.0722f * (px and 0xFF)) / 255f

/**
 * Bilinear resample of the aura to display (or effect) size, with a whisper of
 * baked surface grain.
 *
 * The field is much smaller than the card, and a plain stretch of it shows the
 * bilinear mesh as faint straight seams once a theme lays its scrim on top. The
 * grain breaks those up and gives the backdrop the tooth of a printed surface
 * instead of the slickness of an upscaled gradient. It is baked into the bitmap
 * exactly once, so nothing here costs anything per frame — including while a
 * share video is being captured.
 */
private fun Bitmap.fieldUpscale(
    targetW: Int,
    targetH: Int,
): Bitmap {
    val dstW = targetW.coerceIn(2, MAX_SAFE_BITMAP_EDGE_PX)
    val dstH = targetH.coerceIn(2, MAX_SAFE_BITMAP_EDGE_PX)
    val src = IntArray(width * height)
    getPixels(src, 0, width, 0, 0, width, height)
    val out = IntArray(dstW * dstH)
    val sx = width.toFloat() / dstW
    val sy = height.toFloat() / dstH
    for (y in 0 until dstH) {
        val row = y * dstW
        // Pixel-centred: (n + 0.5) * step - 0.5 lines the taps up with the
        // destination pixels instead of shifting the whole field by half a texel.
        val yf = (y + 0.5f) * sy - 0.5f
        for (x in 0 until dstW) {
            val px = sampleBilinear(src, width, height, (x + 0.5f) * sx - 0.5f, yf)
            if (ART_FIELD_GRAIN <= 0f) {
                out[row + x] = px
                continue
            }
            // Deterministic hash, so the same artwork always grains identically
            // and the exported video matches what the preview showed.
            var hsh = x * 0x1F1F1F1F xor y * 0x27D4EB2D
            hsh = hsh xor (hsh ushr 15)
            hsh *= 0x85EBCA6B.toInt()
            hsh = hsh xor (hsh ushr 13)
            val d = ((hsh and 0xFF) / 255f - 0.5f) * 2f * ART_FIELD_GRAIN
            val r = (((px shr 16) and 0xFF) + d).roundToInt().coerceIn(0, 255)
            val g = (((px shr 8) and 0xFF) + d).roundToInt().coerceIn(0, 255)
            val b = ((px and 0xFF) + d).roundToInt().coerceIn(0, 255)
            out[row + x] = (px and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
    }
    val result = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, dstW, 0, 0, dstW, dstH)
    return result
}

/** Bilinear tap into an ARGB pixel buffer with edge clamping. */
private fun sampleBilinear(
    pixels: IntArray,
    w: Int,
    h: Int,
    xf: Float,
    yf: Float,
): Int {
    val x = xf.coerceIn(0f, (w - 1).toFloat())
    val y = yf.coerceIn(0f, (h - 1).toFloat())
    val x0 = x.toInt()
    val y0 = y.toInt()
    val x1 = (x0 + 1).coerceAtMost(w - 1)
    val y1 = (y0 + 1).coerceAtMost(h - 1)
    val tx = x - x0
    val ty = y - y0
    val c00 = pixels[y0 * w + x0]
    val c10 = pixels[y0 * w + x1]
    val c01 = pixels[y1 * w + x0]
    val c11 = pixels[y1 * w + x1]
    var r = 0
    var g = 0
    var b = 0
    for (shift in intArrayOf(16, 8, 0)) {
        val p00 = (c00 shr shift) and 0xFF
        val p10 = (c10 shr shift) and 0xFF
        val p01 = (c01 shr shift) and 0xFF
        val p11 = (c11 shr shift) and 0xFF
        val top = p00 + (p10 - p00) * tx
        val bottom = p01 + (p11 - p01) * tx
        val value = (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
        when (shift) {
            16 -> r = value
            8 -> g = value
            else -> b = value
        }
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Linear blend of [color] toward white by [fraction] (0..1). */
private fun mixTowardWhite(
    color: Int,
    fraction: Float,
): Int {
    val f = fraction.coerceIn(0f, 1f)
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val nr = (r + (255 - r) * f).toInt().coerceIn(0, 255)
    val ng = (g + (255 - g) * f).toInt().coerceIn(0, 255)
    val nb = (b + (255 - b) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
}

/**
 * Deterministic wave-shaded monospace glyph field: the [ASCII_FIELD] look,
 * and the no-artwork fallback for [ASCII_ARTWORK]. Brightness is shaped by
 * layered sine waves so the field reads as flowing terrain rather than
 * uniform static, and being a pure function of position keeps the live
 * preview and the share capture pixel-identical.
 */
private fun DrawScope.drawAsciiWaveField(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    // Soft phosphor halo behind the glyph field.
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette.glowTop.copy(alpha = 0.12f * palette.decorationAlpha),
                        Color.Transparent,
                    ),
                center = Offset(w * 0.5f, h * 0.30f),
                radius = w * 0.85f,
            ),
        radius = w * 0.85f,
        center = Offset(w * 0.5f, h * 0.30f),
    )
    val paint =
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = w * 0.030f
        }
    val ramp = " .,:;=+*#%@"
    val cell = paint.textSize
    drawIntoCanvas { canvas ->
        var y = cell * 1.15f
        while (y < h) {
            var x = cell * 0.30f
            while (x < w) {
                val nx = x / w
                val ny = y / h
                val wave =
                    sin(nx * 9.4f + sin(ny * 7.3f) * 2.1f) *
                        cos(ny * 6.1f - sin(nx * 4.2f) * 1.7f)
                val b = ((wave + 1f) * 0.5f).coerceIn(0f, 1f)
                val glyph = ramp[(b * (ramp.length - 1)).toInt()]
                if (glyph != ' ') {
                    paint.color =
                        if (b > 0.72f) {
                            palette.accent
                                .copy(alpha = (0.20f + (b - 0.72f) * 1.1f).coerceAtMost(0.55f) * palette.decorationAlpha)
                                .toArgb()
                        } else {
                            Color.White
                                .copy(alpha = (0.05f + b * 0.13f) * palette.decorationAlpha)
                                .toArgb()
                        }
                    canvas.nativeCanvas.drawText(glyph.toString(), x, y, paint)
                }
                x += cell * ASCII_COL_STEP
            }
            y += cell * ASCII_ROW_STEP
        }
    }
}

/**
 * Radial decoration inside a fixed-size circle: fades from [color] at its
 * centre to transparent (or to [edge] when given).
 */
@Composable
private fun GlowOrb(
    scope: BoxScope,
    alignment: Alignment,
    x: Dp,
    y: Dp,
    size: Dp,
    color: Color,
    edge: Color = Color.Transparent,
) {
    with(scope) {
        Box(
            modifier =
                Modifier
                    .align(alignment)
                    .offset(x = x, y = y)
                    .size(size)
                    .background(
                        brush = Brush.radialGradient(colors = listOf(color, edge)),
                        shape = CircleShape,
                    ),
        )
    }
}

// Scene geometry — fixed layouts so the backdrop is identical on every render
// and in the share capture. Coordinates are fractions of card width/height.

private data class GlassFluidBlob(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rot: Float,
    val colorIndex: Int,
    val alpha: Float,
)

private val GlassFluidBlobs =
    listOf(
        GlassFluidBlob(0.28f, 0.14f, 0.55f, 0.30f, -24f, 0, 0.34f),
        GlassFluidBlob(0.86f, 0.32f, 0.45f, 0.26f, 18f, 1, 0.30f),
        GlassFluidBlob(0.10f, 0.54f, 0.50f, 0.24f, 30f, 2, 0.26f),
        GlassFluidBlob(0.70f, 0.80f, 0.60f, 0.28f, -14f, 1, 0.28f),
        GlassFluidBlob(0.38f, 0.96f, 0.45f, 0.22f, 10f, 0, 0.24f),
        GlassFluidBlob(0.52f, 0.42f, 0.35f, 0.18f, -32f, 1, 0.16f),
    )

/**
 * Text color for rank badges, pedestal numbers, and swatch selection dots.
 * Pure luminance pick: bright chips (gold/silver metals on dark themes) get
 * black text, dark chips (light-theme accents) get white. Threshold 0.5 is
 * the crossover that keeps the original black-on-metal look while making
 * dark chips on the Daylight theme readable.
 */
internal fun ShareThemePalette.contrastingText(chip: Color): Color =
    if (0.299f * chip.red + 0.587f * chip.green + 0.114f * chip.blue > 0.5f) Color.Black else Color.White
