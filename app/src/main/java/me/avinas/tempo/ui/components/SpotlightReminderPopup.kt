package me.avinas.tempo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter
import me.avinas.tempo.ui.theme.RecapSheetBase
import me.avinas.tempo.ui.theme.StorySerifFamily
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.TempoTheme

enum class SpotlightReminderType {
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * The sleeve art the prompt is selling, fetched alongside the reminder flag.
 *
 * Art only: the card is a teaser, so it hints at the week with
 * out-of-focus covers and never shows a number the story itself should reveal.
 */
@Immutable
data class SpotlightReminderPreview(
    val artUrls: List<String> = emptyList()
)

/**
 * Weekly / monthly / yearly story prompt.
 *
 * The surface is a floating recap sheet rather than a dialog: album art fanned
 * like a physical stack of sleeves, an editorial serif headline, then the three
 * numbers that make the week worth opening. It sits on solid ink — no glass —
 * and the only light is the mint rim and the two brush underlines.
 */
@Composable
fun SpotlightReminderPopup(
    type: SpotlightReminderType,
    timeRange: TimeRange? = null,
    onDismiss: () -> Unit,
    onViewStory: () -> Unit,
    preview: SpotlightReminderPreview? = null
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    val targetTimeRange = timeRange ?: when (type) {
        SpotlightReminderType.WEEKLY -> TimeRange.THIS_WEEK
        SpotlightReminderType.MONTHLY -> TimeRange.THIS_MONTH
        SpotlightReminderType.YEARLY -> TimeRange.THIS_YEAR
    }

    val periodLabel = remember(targetTimeRange) { recapPeriodLabel(targetTimeRange) }
    val headline = stringResource(
        when (type) {
            SpotlightReminderType.WEEKLY -> R.string.spotlight_recap_headline_weekly
            SpotlightReminderType.MONTHLY -> R.string.spotlight_recap_headline_monthly
            SpotlightReminderType.YEARLY -> R.string.spotlight_recap_headline_yearly
        }
    )
    val cta = stringResource(
        when (type) {
            SpotlightReminderType.WEEKLY -> R.string.spotlight_recap_cta_weekly
            SpotlightReminderType.MONTHLY -> R.string.spotlight_recap_cta_monthly
            SpotlightReminderType.YEARLY -> R.string.spotlight_recap_cta_yearly
        }
    )

    // One clock for the whole entrance: the underlines draw first, then the
    // sleeves cascade out from the centre. Nothing loops.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(70)
            entrance.animateTo(1f, tween(durationMillis = 760, easing = FastOutSlowInEasing))
        }
    }

    fun leave(action: () -> Unit) {
        if (!visible) return
        visible = false
        scope.launch {
            delay(190)
            action()
        }
    }

    Dialog(
        onDismissRequest = { leave(onDismiss) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible, enter = fadeIn(tween(240)), exit = fadeOut(tween(180))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.58f),
                                    Color.Black.copy(alpha = 0.84f)
                                )
                            )
                        )
                        // The window is full-bleed, so outside taps are ours to route.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { leave(onDismiss) }
                        )
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(
                        initialOffsetY = { it / 5 },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(tween(220)),
                    exit = slideOutVertically(targetOffsetY = { it / 6 }, animationSpec = tween(180)) +
                        fadeOut(tween(160))
                ) {
                    RecapSheet(
                        kicker = stringResource(R.string.home_spotlight_title),
                        periodLabel = periodLabel,
                        headline = headline,
                        subtitle = stringResource(R.string.spotlight_recap_subtitle),
                        cta = cta,
                        maybeLater = stringResource(R.string.spotlight_recap_maybe_later),
                        closeLabel = stringResource(R.string.spotlight_recap_close),
                        preview = preview,
                        entrance = entrance.value,
                        maxHeight = maxHeight,
                        onClose = { leave(onDismiss) },
                        onViewStory = { leave(onViewStory) }
                    )
                }
            }
        }
    }
}

/**
 * The silhouetted sheet from the reference card: both shoulders lift away from
 * the top edge, the crest sits left of centre, then the outline dips into a long
 * trough before rising to the right shoulder.
 *
 * A plain rounded rectangle looks like a dialog. This shape keeps the irregular
 * outline so the sheet does not read as one.
 */
private class RecapSheetShape(private val compact: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { (if (compact) 26.dp else 32.dp).toPx() }
            .coerceAtMost(w / 4f)
        // The shoulders are wide, soft corner radii rather than arcs: the edge
        // never shows a straight segment, which is what makes it read organic.
        val leftShoulder = w * 0.075f
        val rightShoulder = w * 0.090f
        val dip = w * 0.072f
        val crest = w * 0.004f

        val path = Path().apply {
            moveTo(0f, h - r)
            // Left edge climbs the whole way, so the silhouette has one gesture.
            lineTo(0f, leftShoulder)
            cubicTo(
                0f, leftShoulder * 0.35f,
                w * 0.035f, dip * 0.30f,
                w * 0.130f, dip * 0.62f
            )
            // Ease through the shallow dip left of centre.
            cubicTo(
                w * 0.235f, dip * 0.94f,
                w * 0.330f, dip,
                w * 0.420f, dip * 0.78f
            )
            // The flourish: one crest, right of centre, then a soft fall.
            cubicTo(
                w * 0.520f, dip * 0.46f,
                w * 0.570f, crest,
                w * 0.660f, crest
            )
            cubicTo(
                w * 0.760f, crest,
                w * 0.845f, rightShoulder * 0.42f,
                w - rightShoulder, rightShoulder
            )
            // Top-right shoulder rolls into the straight edge.
            cubicTo(
                w - rightShoulder * 0.42f, rightShoulder,
                w, rightShoulder * 1.15f,
                w, rightShoulder * 1.9f
            )
            lineTo(w, h - r)
            quadraticTo(w, h, w - r, h)
            lineTo(r, h)
            quadraticTo(0f, h, 0f, h - r)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Everything under the content: a pool of mint light behind the fan, two
 * hairline curves that echo the silhouette, and a foot that sinks into the
 * scrim. Solid ink only — no art, no glass.
 */
@Composable
private fun BoxScope.RecapSheetBackdrop() {
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                TempoPrimary.copy(alpha = 0.15f),
                                TempoPrimary.copy(alpha = 0.045f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.50f, size.height * 0.60f),
                            radius = size.width * 0.86f
                        )
                    )
                    // A hairline threading behind the deck keeps the lower half
                    // from reading as an empty plate. It stays under the sleeves
                    // so it never cuts across the copy.
                    val hairline = 0.7.dp.toPx()
                    val path = Path().apply {
                        moveTo(-size.width * 0.06f, size.height * 0.72f)
                        cubicTo(
                            size.width * 0.26f, size.height * 0.60f,
                            size.width * 0.68f, size.height * 0.80f,
                            size.width * 1.06f, size.height * 0.64f
                        )
                    }
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                TempoPrimary.copy(alpha = 0.16f),
                                TempoPrimary.copy(alpha = 0.07f),
                                Color.Transparent
                            )
                        ),
                        style = Stroke(width = hairline, cap = StrokeCap.Round)
                    )
                    // The foot of the sheet sinks into the scrim.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.10f),
                            0.28f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.24f)
                        )
                    )
                }
        )
    }
}

@Composable
internal fun RecapSheet(
    kicker: String,
    periodLabel: String,
    headline: String,
    subtitle: String,
    cta: String,
    maybeLater: String,
    closeLabel: String,
    preview: SpotlightReminderPreview?,
    entrance: Float,
    maxHeight: Dp,
    onClose: () -> Unit,
    onViewStory: () -> Unit
) {
    // Short phones and large font scales get a compressed sheet instead of a
    // scroll bar hiding the primary action.
    val compact = maxHeight < 620.dp
    val shape = remember(compact) { RecapSheetShape(compact = compact) }
    val rim = Brush.linearGradient(
        0f to TempoPrimary.copy(alpha = 0.50f),
        0.42f to TempoPrimary.copy(alpha = 0.05f),
        1f to TempoPrimary.copy(alpha = 0.30f)
    )

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .shadow(
                elevation = 30.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.60f),
                spotColor = Color.Black.copy(alpha = 0.70f)
            )
            .clip(shape)
            .background(RecapSheetBase)
            .border(1.dp, rim, shape)
    ) {
        RecapSheetBackdrop()

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // The wave dips below the top edge, so the close button sits low
            // enough to stay clear of the silhouette at any width.
            Box(Modifier.fillMaxWidth().height(if (compact) 88.dp else 96.dp)) {
                CloseButton(
                    label = closeLabel,
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Clear of the right shoulder, which falls away from the
                        // crest: any higher and the silhouette clips the button.
                        .padding(top = if (compact) 40.dp else 46.dp, end = 12.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_tempo_badge),
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (compact) 20.dp else 22.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    )
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(
                            text = kicker,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.1.sp,
                            lineHeight = 19.sp
                        )
                        Text(
                            text = periodLabel,
                            color = TextTertiary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))

                Headline(
                    text = headline,
                    compact = compact,
                    progress = entrance
                )

                // Room for the brush strokes, which sit just under the second line.
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = if (compact) 13.5.sp else 14.5.sp,
                    lineHeight = if (compact) 20.sp else 21.5.sp,
                    letterSpacing = 0.1.sp,
                    modifier = Modifier.fillMaxWidth(0.94f)
                )
            }

            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))

            ArtFan(
                artUrls = preview?.artUrls.orEmpty(),
                progress = entrance,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 112.dp else 130.dp)
            )

            Spacer(Modifier.height(if (compact) 12.dp else 18.dp))

            Button(
                onClick = onViewStory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(if (compact) 52.dp else 56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = TextOnAccent
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
                elevation = null
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = cta,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp
                    )
                    Spacer(Modifier.width(9.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(if (compact) 40.dp else 46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = TextTertiary
                ),
                shape = RoundedCornerShape(23.dp),
                contentPadding = PaddingValues(0.dp),
                elevation = null
            ) {
                Text(
                    text = maybeLater,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp
                )
            }

            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        }
    }
}

@Composable
private fun Headline(
    text: String,
    compact: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ranges = remember(text) { underlineRanges(text) }
    val drawProgress = ((progress - 0.06f) / 0.46f).coerceIn(0f, 1f)
    val measurer = rememberTextMeasurer()

    // One line, always: the size gives way to the width rather than the other
    // way round, which keeps the phrase a single editorial gesture even on a
    // 320dp phone or at 1.3x font scale.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val fitted = remember(text, available, compact) {
            fitHeadlineSize(measurer, text, if (compact) 37f else 42f, available)
        }

        // The strokes are painted by the text itself so they can be placed from
        // the measured word boxes instead of guessed line fractions.
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            style = TextStyle(
                fontFamily = StorySerifFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = fitted.sp,
                lineHeight = (fitted * 1.06f).sp,
                letterSpacing = (-0.3).sp,
                color = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .drawWithContent {
                    drawContent()
                    val result = layout ?: return@drawWithContent
                    if (drawProgress <= 0.01f) return@drawWithContent
                    // Sits on the baseline, not the word box: the serif's deep
                    // descender would otherwise push the stroke into the subtitle.
                    val baseline = runCatching { result.getLineBaseline(0) }.getOrNull()
                        ?: return@drawWithContent

                    ranges.forEachIndexed { index, range ->
                        val first = runCatching { result.getBoundingBox(range.first) }.getOrNull()
                            ?: return@forEachIndexed
                        val last = runCatching { result.getBoundingBox(range.last) }.getOrNull()
                            ?: return@forEachIndexed
                        // First stroke stops just short of the comma so the brush
                        // passes under the word instead of striking the punctuation.
                        val endsInComma = index == 0 && text.getOrNull(range.last + 1) == ','
                        val tail = if (endsInComma) -3.dp.toPx() else 1.5.dp.toPx()
                        val local = ((drawProgress - index * 0.16f) / 0.84f).coerceIn(0f, 1f)
                        if (local <= 0f) return@forEachIndexed

                        drawBrushStroke(
                            left = first.left - 1.dp.toPx(),
                            right = last.left + last.width + tail,
                            top = baseline + 3.dp.toPx(),
                            thickness = (if (index == 0) 6.6.dp else 5.8.dp).toPx(),
                            color = TempoAccent.copy(alpha = if (index == 0) 0.92f else 0.80f),
                            progress = local
                        )
                    }
                },
            onTextLayout = { layout = it }
        )
    }
}

/** Largest size at which the whole phrase still measures inside one line. */
private fun fitHeadlineSize(
    measurer: TextMeasurer,
    text: String,
    startSp: Float,
    maxWidthPx: Int
): Float {
    var size = startSp
    while (size > 22f) {
        val measured = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                fontFamily = StorySerifFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = size.sp,
                letterSpacing = (-0.3).sp
            ),
            maxLines = 1,
            softWrap = false
        )
        if (measured.size.width <= maxWidthPx) return size
        size -= 0.5f
    }
    return 22f
}

/**
 * Character ranges of the words that carry the brush strokes: the word before
 * the comma, and the closing word — both on the one headline line.
 */
private fun underlineRanges(text: String): List<IntRange> {
    val words = Regex("[A-Za-z']+").findAll(text).map { it.range }.toList()
    if (words.size < 2) return emptyList()

    val first = words.firstOrNull { text.getOrNull(it.last + 1) == ',' } ?: words.first()
    val last = words.last()
    return if (first.first <= last.first) listOf(first, last) else listOf(first)
}

/**
 * A single dry-brush swipe: thin at the tail, loaded through the middle, tapering
 * to a point that grows with [progress]. No square caps, no filled bars.
 */
private fun DrawScope.drawBrushStroke(
    left: Float,
    right: Float,
    top: Float,
    thickness: Float,
    color: Color,
    progress: Float
) {
    val width = (right - left).coerceAtLeast(1f)
    val tip = left + width * progress
    if (tip - left < thickness * 0.6f) return

    val path = Path()
    path.moveTo(left, top + thickness * 0.34f)
    path.cubicTo(
        left + width * 0.20f, top + thickness * 0.04f,
        left + width * 0.72f, top + thickness * 0.08f,
        tip, top + thickness * 0.30f
    )
    path.cubicTo(
        left + width * 0.70f, top + thickness * 0.92f,
        left + width * 0.20f, top + thickness * 0.98f,
        left, top + thickness * 0.34f
    )
    path.close()
    drawPath(path, color)
}

private data class FanSlot(
    val size: Dp,
    val centerXFraction: Float,
    val topOffset: Dp,
    val rotation: Float,
    /** 0 is the hero sleeve; each step back lifts a little later on entrance. */
    val cascade: Int,
    /** Which sleeve of the period this card carries. */
    val artIndex: Int
)

/**
 * Deck positions for [count] sleeves, back to front. Odd counts hang a hero
 * sleeve off a pair of wings; even counts sit in mirror pairs with no hero, so
 * a short period never shows an unmatched card drifting at one edge — and no
 * sleeve is ever printed twice.
 */
private fun fanLayout(count: Int, scale: Float): List<FanSlot> {
    fun slot(
        size: Float,
        centerX: Float,
        top: Float,
        rotation: Float,
        cascade: Int,
        artIndex: Int
    ) = FanSlot(size.dp * scale, centerX, top.dp * scale, rotation, cascade, artIndex)

    return when (count) {
        1 -> listOf(slot(124f, 0f, 0f, 0f, 0, 0))
        2 -> listOf(
            slot(120f, 0.126f, 4f, 5.5f, 0, 0),
            slot(120f, -0.126f, 4f, -5.5f, 0, 1)
        )
        3 -> listOf(
            slot(96f, 0.165f, 12f, 7.5f, 1, 1),
            slot(96f, -0.165f, 12f, -7.5f, 1, 2),
            slot(118f, 0f, 0f, 0f, 0, 0)
        )
        4 -> listOf(
            slot(84f, 0.244f, 18f, 12f, 1, 3),
            slot(84f, -0.244f, 18f, -12f, 1, 2),
            slot(100f, 0.076f, 6f, 5f, 0, 1),
            slot(100f, -0.076f, 6f, -5f, 0, 0)
        )
        else -> listOf(
            slot(74f, 0.300f, 21f, 13f, 2, 4),
            slot(74f, -0.300f, 21f, -13f, 2, 3),
            slot(92f, 0.148f, 11f, 7f, 1, 2),
            slot(92f, -0.148f, 11f, -7f, 1, 1),
            slot(118f, 0f, 0f, 0f, 0, 0)
        )
    }
}

@Composable
private fun ArtFan(
    artUrls: List<String>,
    progress: Float,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val scale = if (compact) 0.82f else 1f
    val urls = remember(artUrls) { artUrls.distinct().take(5) }
    // An empty preview still shows the deck, in solid ink, so the sheet keeps
    // its shape on the first frame of a fresh install.
    val slots = remember(urls.size, scale) {
        fanLayout(if (urls.isEmpty()) 5 else urls.size, scale)
    }

    BoxWithConstraints(modifier) {
        val width = maxWidth
        slots.forEach { slot ->
            val local = fanProgress(progress, slot.cascade)
            val shape = RoundedCornerShape(if (slot.size > 110.dp * scale) 13.dp else 10.dp)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = width * slot.centerXFraction, y = slot.topOffset)
                    .size(slot.size)
                    .graphicsLayer {
                        alpha = local
                        translationY = (1f - local) * 26.dp.toPx()
                        scaleX = 0.92f + 0.08f * local
                        scaleY = scaleX
                        rotationZ = slot.rotation
                        shadowElevation = (if (slot.cascade == 0) 14.dp else 8.dp).toPx()
                        this.shape = shape
                        clip = true
                    }
            ) {
                Cover(artUrl = urls.getOrNull(slot.artIndex), seed = slot.artIndex, shape = shape)
            }
        }
    }
}

private fun fanProgress(progress: Float, cascade: Int): Float {
    val start = 0.10f + cascade * 0.16f
    return ((progress - start) / 0.45f).coerceIn(0f, 1f)
}

@Composable
private fun Cover(artUrl: String?, seed: Int, shape: Shape) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(Brush.linearGradient(listOf(TempoDarkSurface, TempoDarkSurfaceSunken)))
    ) {
        // Placeholder sheen, only visible while art loads or when a track has none.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            TempoPrimaryDeep.copy(alpha = 0.10f + seed * 0.018f),
                            Color.Transparent
                        )
                    )
                )
        )
        if (!artUrl.isNullOrBlank()) {
            // Out of focus on purpose: the sleeves hint at the week without
            // naming it, which is what the prompt is for.
            CachedAsyncImage(
                imageUrl = artUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                targetSizeDp = 220,
                allowHardware = false,
                blurRadius = 11.dp
            )
        }
        // Ink gathers at the foot of each sleeve so the stack sits in the card.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.30f)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
        )
    }
}

@Composable
private fun CloseButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(12.dp)) {
                val stroke = 1.6.dp.toPx()
                val inset = stroke / 2f
                drawLine(
                    Color.White.copy(alpha = 0.72f),
                    Offset(inset, inset),
                    Offset(size.width - inset, size.height - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    Color.White.copy(alpha = 0.72f),
                    Offset(size.width - inset, inset),
                    Offset(inset, size.height - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun recapPeriodLabel(timeRange: TimeRange): String {
    val label = SpotlightPeriodFormatter.periodLabel(timeRange)
    if (timeRange != TimeRange.THIS_WEEK) return label
    // "Nov 9-15" -> "Nov 9 — 15" for the quiet date line under the kicker.
    return label
        .replace(" - ", " — ")
        .replace(Regex("(?<=\\d)-(?=\\d)"), " — ")
}

// ---------------------------------------------------------------------------
// Previews
//
// Live in Android Studio's preview pane — no Gradle run, no emulator. The sheet
// is stateless apart from its entrance clock, so it renders from literals alone.
// ---------------------------------------------------------------------------

@Preview(name = "Recap — weekly", widthDp = 392, heightDp = 812, showBackground = true)
@Preview(name = "Recap — monthly", widthDp = 392, heightDp = 812, showBackground = true)
@Preview(name = "Recap — yearly", widthDp = 392, heightDp = 812, showBackground = true)
@Preview(name = "Recap — small phone", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
private fun SpotlightRecapSheetPreview() {
    TempoTheme {
        PreviewStage {
            RecapSheet(
                kicker = "Tempo Spotlight",
                periodLabel = "Nov 9 — 15",
                headline = "Your week, on repeat.",
                subtitle = "Your favorite tracks, top artists and listening highlights are ready.",
                cta = "Explore your week",
                maybeLater = "Maybe later",
                closeLabel = "Close",
                // No URLs: sleeves fall back to their ink gradient, which is what
                // the blurred reference card reads as anyway.
                preview = SpotlightReminderPreview(),
                entrance = 1f,
                maxHeight = 720.dp,
                onClose = {},
                onViewStory = {}
            )
        }
    }
}

@Composable
private fun PreviewStage(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TempoDarkBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
