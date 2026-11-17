package me.avinas.tempo.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.TempoIcons
import me.avinas.tempo.ui.theme.StorySerifFamily
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoTheme
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

// ---------------------------------------------------------------------------
// Ink
//
// The card is a slice of the app's night, a shade greener than the feed itself
// so the mint rim has something to hold on to. Everything else is light: one
// cool pool behind the badge, one warm pool behind the figure, and the hairline
// that reads as a horizon under the copy.
// ---------------------------------------------------------------------------

private val RateCardTop = Color(0xFF0E2E29)
private val RateCardBottom = Color(0xFF06120F)
private val RateWarmGlow = Color(0xFFFFC46B)

/** The star-cushion portrait: 1239 x 1269, so the box tracks the art's aspect. */
private const val ART_ASPECT = 1269f / 1239f

/**
 * Play Store review prompt.
 *
 * A single editorial card rather than a bottom sheet: the badge and close button
 * ride the top edge, the serif question takes the left column, the listening
 * figure takes the right, and one mint pill carries the whole decision. The
 * review flow is handed to the caller as [onRate]; nothing here talks to the
 * store SDK itself.
 */
@Composable
fun RateAppPopup(
    onDismiss: () -> Unit,
    onRate: () -> Unit,
    isSubmitting: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    // One clock for the entrance: the card settles, the brush stroke draws, the
    // figure lifts into place. Nothing loops and nothing bounces.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(60)
            entrance.animateTo(1f, tween(durationMillis = 760, easing = FastOutSlowInEasing))
        }
    }

    fun leave(action: () -> Unit) {
        if (!visible) return
        visible = false
        scope.launch {
            delay(170)
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
            AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.62f),
                                    Color.Black.copy(alpha = 0.86f)
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
                    .padding(horizontal = 18.dp)
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    )
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(
                        initialScale = 0.94f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(tween(200)),
                    exit = scaleOut(targetScale = 0.97f, animationSpec = tween(150)) +
                        fadeOut(tween(130))
                ) {
                    ReviewCard(
                        compact = maxHeight < 660.dp,
                        maxHeight = maxHeight,
                        entrance = entrance.value,
                        isSubmitting = isSubmitting,
                        onRate = { leave(onRate) },
                        onDismiss = { leave(onDismiss) }
                    )
                }
            }
        }
    }
}

/**
 * The card itself, without the dialog window: the preview and the screenshot test
 * both render this directly, so what a designer reviews is what the device draws.
 */
@Composable
internal fun ReviewCard(
    compact: Boolean,
    maxHeight: Dp,
    entrance: Float,
    isSubmitting: Boolean,
    onRate: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(if (compact) 26.dp else 30.dp)
    val rim = Brush.verticalGradient(
        0f to TempoPrimary.copy(alpha = 0.38f),
        0.45f to Color.White.copy(alpha = 0.07f),
        1f to TempoPrimary.copy(alpha = 0.22f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .shadow(
                elevation = 28.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.70f)
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(RateCardTop, RateCardBottom)))
            .border(1.dp, rim, shape)
    ) {
        RateBackdrop(entrance)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 18.dp else 20.dp)
                .padding(top = if (compact) 16.dp else 18.dp, bottom = if (compact) 8.dp else 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayBadge(compact = compact)
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
                CloseButton(onClose = onDismiss)
            }

            Spacer(Modifier.height(if (compact) 16.dp else 20.dp))

            Hero(
                compact = compact,
                entrance = entrance,
                headline = stringResource(R.string.rate_enjoying),
                body = stringResource(R.string.rate_message)
            )

            Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

            PrimaryAction(isSubmitting = isSubmitting, compact = compact, onRate = onRate)

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 40.dp else 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = TextTertiary
                ),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(0.dp),
                elevation = null
            ) {
                Text(
                    text = stringResource(R.string.rate_maybe_later),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp
                )
            }
        }
    }
}

/**
 * Copy on the left, the figure on the right, both bottom-aligned so the shoes
 * land on the same line the sentence does. The column never reaches under the
 * figure: the silhouette is measured, not guessed, so no translation of the
 * body copy can collide with it.
 */
@Composable
private fun Hero(
    compact: Boolean,
    entrance: Float,
    headline: String,
    body: String
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val artWidth = maxWidth * (if (compact) 0.44f else 0.50f)
        val artHeight = artWidth * ART_ASPECT
        val gap = 6.dp
        val copyWidth = (maxWidth - artWidth - gap).coerceAtLeast(maxWidth * 0.5f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.width(copyWidth)) {
                Headline(
                    text = headline,
                    width = copyWidth,
                    compact = compact,
                    progress = entrance
                )
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
                Text(
                    text = body,
                    color = TextSecondary,
                    fontSize = if (compact) 12.5.sp else 13.5.sp,
                    lineHeight = if (compact) 19.sp else 20.5.sp,
                    letterSpacing = 0.1.sp
                )
            }

            Spacer(Modifier.width(gap))

            Box(Modifier.size(artWidth, artHeight)) {
                Portrait(entrance = entrance)
            }
        }
    }
}

/**
 * The serif question, sized to the column rather than to a fixed sp, with one
 * brush stroke under its last line. The stroke is painted by the text itself so
 * it follows the measured line box on every width and font scale.
 */
@Composable
private fun Headline(
    text: String,
    width: Dp,
    compact: Boolean,
    progress: Float
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    val fit = remember(text, widthPx, compact) {
        fitHeadlineSize(measurer, text, if (compact) 34f else 38f, widthPx)
    }
    val drawProgress = ((progress - 0.12f) / 0.44f).coerceIn(0f, 1f)

    Text(
        text = text,
        style = TextStyle(
            fontFamily = StorySerifFamily,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            fontSize = fit.sp,
            lineHeight = (fit * 1.02f).sp,
            letterSpacing = (-0.4).sp,
            color = TextPrimary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .drawWithContent {
                drawContent()
                val result = layout ?: return@drawWithContent
                if (drawProgress <= 0.02f) return@drawWithContent
                val lastLine = result.lineCount - 1
                // On the baseline, not the line box: the serif's deep descender
                // would otherwise drag the stroke into the body copy.
                val baseline = runCatching { result.getLineBaseline(lastLine) }.getOrNull()
                    ?: return@drawWithContent
                val left = runCatching { result.getLineLeft(lastLine) }.getOrNull()
                    ?: return@drawWithContent
                val right = runCatching { result.getLineRight(lastLine) }.getOrNull()
                    ?: return@drawWithContent
                if (right - left < 12.dp.toPx()) return@drawWithContent

                drawBrushStroke(
                    left = left - 1.dp.toPx(),
                    right = right + 1.5.dp.toPx(),
                    top = baseline + 3.dp.toPx(),
                    thickness = 5.4.dp.toPx(),
                    color = TempoAccent.copy(alpha = 0.88f),
                    progress = drawProgress
                )
            },
        onTextLayout = { layout = it }
    )
}

/** Largest size at which the question still measures inside two lines. */
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
                letterSpacing = (-0.4).sp
            ),
            constraints = Constraints(maxWidth = maxWidthPx)
        )
        if (measured.lineCount <= 2) return size
        size -= 0.5f
    }
    return 22f
}

/**
 * The portrait and the pool of shadow under it. The figure lifts into place on
 * the same entrance clock as the rest of the card.
 */
@Composable
private fun Portrait(entrance: Float) {
    val lift = 16.dp * (1f - entrance)
    // Clipped by the card, never by this box: the shoes are allowed to sit on
    // the card's own foot rather than float a few dp above it.
    Canvas(Modifier.fillMaxSize().graphicsLayer { translationY = lift.toPx() }) {
        val poolHeight = size.height * 0.16f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.42f * (0.4f + 0.6f * entrance)),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.52f, size.height * 0.94f),
                radius = size.width * 0.62f
            ),
            topLeft = Offset(size.width * 0.02f, size.height - poolHeight),
            size = Size(size.width * 0.96f, poolHeight)
        )
    }

    Image(
        painter = painterResource(R.drawable.rate_starboy),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = lift.toPx()
                alpha = 0.25f + 0.75f * entrance
            }
    )
}

/**
 * Everything under the content: a cool pool behind the badge, a warm one behind
 * the figure, the hairline that reads as a horizon, and a little dust. Solid ink
 * only, so the card still reads as the app's night rather than as glass.
 */
@Composable
private fun BoxScope.RateBackdrop(progress: Float) {
    Box(
        Modifier
            .matchParentSize()
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TempoPrimary.copy(alpha = 0.16f * progress),
                            TempoPrimary.copy(alpha = 0.05f * progress),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.10f, size.height * 0.01f),
                        radius = size.width * 1.05f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            RateWarmGlow.copy(alpha = 0.15f * progress),
                            RateWarmGlow.copy(alpha = 0.04f * progress),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.52f),
                        radius = size.width * 0.62f
                    )
                )
                // Sheen along the top edge, so the rim has a lit side.
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.05f),
                        0.22f to Color.Transparent
                    )
                )

                val horizon = Path().apply {
                    moveTo(-size.width * 0.06f, size.height * 0.755f)
                    cubicTo(
                        size.width * 0.28f, size.height * 0.700f,
                        size.width * 0.66f, size.height * 0.815f,
                        size.width * 1.06f, size.height * 0.715f
                    )
                }
                drawPath(
                    path = horizon,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            TempoPrimary.copy(alpha = 0.14f * progress),
                            TempoPrimary.copy(alpha = 0.06f * progress),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round)
                )

                val dust = listOf(
                    Triple(0.58f, 0.16f, 0.9f),
                    Triple(0.66f, 0.27f, 0.5f),
                    Triple(0.92f, 0.30f, 0.7f),
                    Triple(0.86f, 0.10f, 0.4f),
                    Triple(0.50f, 0.44f, 0.35f)
                )
                dust.forEach { (fx, fy, alpha) ->
                    drawCircle(
                        color = TempoAccent.copy(alpha = alpha * 0.55f * progress),
                        radius = 1.3.dp.toPx(),
                        center = Offset(size.width * fx, size.height * fy)
                    )
                }
            }
    )
}

/** Translucent capsule: the store the review is written to. */
@Composable
private fun PlayBadge(compact: Boolean) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(
                horizontal = if (compact) 9.dp else 11.dp,
                vertical = if (compact) 5.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = TempoIcons.GooglePlay,
            contentDescription = null,
            tint = TempoAccent.copy(alpha = 0.92f),
            modifier = Modifier.size(if (compact) 12.dp else 13.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(R.string.rate_badge_on_play),
            color = TextPrimary.copy(alpha = 0.86f),
            fontSize = if (compact) 10.5.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val label = stringResource(R.string.rate_close)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(11.dp)) {
            val stroke = 1.6.dp.toPx()
            val inset = stroke / 2f
            val ink = Color.White.copy(alpha = 0.78f)
            drawLine(
                ink,
                Offset(inset, inset),
                Offset(size.width - inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                ink,
                Offset(size.width - inset, inset),
                Offset(inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    isSubmitting: Boolean,
    compact: Boolean,
    onRate: () -> Unit
) {
    Button(
        onClick = onRate,
        enabled = !isSubmitting,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 52.dp else 56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TempoPrimary,
            contentColor = TextOnAccent,
            disabledContainerColor = TempoPrimary.copy(alpha = 0.45f),
            disabledContentColor = TextOnAccent.copy(alpha = 0.75f)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        elevation = null
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = TextOnAccent,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.rate_cta_review),
                fontSize = if (compact) 15.sp else 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp
            )
        }
    }
}

/**
 * The brush swipe under the headline: thin at the tail, loaded through the
 * middle, tapering to a point that grows with [progress].
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

// ---------------------------------------------------------------------------
// Previews
//
// Live in Android Studio's preview pane: the card is stateless apart from its
// entrance clock, so it renders from literals alone.
// ---------------------------------------------------------------------------

@Preview(name = "Review prompt", widthDp = 392, heightDp = 812, showBackground = true)
@Preview(name = "Review prompt - small phone", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
private fun RateAppPopupPreview() {
    TempoTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TempoDarkBackground)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            ReviewCard(
                compact = false,
                maxHeight = 720.dp,
                entrance = 1f,
                isSubmitting = false,
                onRate = {},
                onDismiss = {}
            )
        }
    }
}
