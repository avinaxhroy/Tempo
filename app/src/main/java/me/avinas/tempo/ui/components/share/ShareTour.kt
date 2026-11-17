package me.avinas.tempo.ui.components.share

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.avinas.tempo.R
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextOnAccent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Whether this install has already been shown the share tour.
 *
 * Kept in the app's existing `settings` DataStore rather than in the Room
 * `UserPreferences` entity the older walkthroughs use: this is a one-bit UI flag,
 * and the alternative is a schema migration on a database that is already being
 * edited elsewhere in this tree. Same store as onboarding, same edit pattern.
 */
private val ShareTourSeenKey = booleanPreferencesKey("stats_share_tour_seen")

internal suspend fun Context.shareTourSeen(): Boolean =
    runCatching { dataStore.data.first()[ShareTourSeenKey] ?: false }.getOrDefault(false)

internal suspend fun Context.markShareTourSeen() {
    runCatching { dataStore.edit { it[ShareTourSeenKey] = true } }
}

/** The three surfaces the first-run tour points at, in the order it visits them. */
internal enum class ShareTourStep {
    CAROUSEL,
    DECK,
    SHARE,
}

/**
 * The tour's state: which step is on screen and where each step's spotlight goes.
 *
 * Not a ViewModel. The tour is scoped to one visit of one sheet, and
 * the only fact worth keeping between visits is the "seen" flag, which is written
 * straight to DataStore.
 */
@Stable
internal class ShareTourState(
    private val onFinished: () -> Unit,
    /**
     * The steps this sheet actually has, in visiting order.
     *
     * A card with nothing to configure has no deck, so its tour is two steps:
     * pointing at an empty strip above the button would teach the wrong thing
     * about what the sheet can do.
     */
    private val steps: List<ShareTourStep> = ShareTourStep.entries,
) {
    var step by mutableStateOf<ShareTourStep?>(null)
        private set

    /** Where each step's target landed this layout pass, in root coordinates. */
    internal val targets = mutableStateMapOf<ShareTourStep, Rect>()

    /** The steps this sheet is showing; the callout's dots read it. */
    internal val shownSteps: List<ShareTourStep> get() = steps

    val isActive: Boolean get() = step != null

    fun start() {
        step = steps.firstOrNull()
    }

    fun next() {
        val current = step ?: return
        val following = steps.getOrNull(steps.indexOf(current) + 1)
        if (following == null) finish() else step = following
    }

    /** Ends the tour and reports it, so a replay does not get forgotten mid-way. */
    fun finish() {
        if (step == null) return
        step = null
        onFinished()
    }

    internal fun register(
        target: ShareTourStep,
        bounds: Rect,
    ) {
        // Registered even while the tour is idle: a replay started from the header
        // would otherwise open on a zero-size hole and animate *in* from the
        // top-left corner, which reads as a glitch. The equality check keeps a
        // per-layout write from invalidating the overlay on every carousel drag.
        if (targets[target] != bounds) targets[target] = bounds
    }
}

/**
 * Marks this composable as a tour target.
 *
 * Bounds come from the layout pass rather than from arithmetic: the deck is three
 * labelled groups of different widths and the carousel resizes with the screen.
 * The write happens in `onGloballyPositioned`, after the pass, so nothing reads
 * state during layout.
 */
internal fun Modifier.shareTourTarget(
    state: ShareTourState,
    step: ShareTourStep,
): Modifier =
    this.onGloballyPositioned { coordinates ->
        state.register(step, coordinates.boundsInRoot())
    }

/**
 * The spotlight layer: everything dims except the current target, which gets a
 * breathing accent ring and a callout.
 *
 * The hole is one path with an even-odd fill rather than four rectangles, so the
 * ring can hug a rounded hole and the dimming stays a single draw. The hole
 * animates between steps, which is what makes the tour read as one camera moving
 * between controls instead of three unrelated cards.
 */
@Composable
internal fun ShareTourOverlay(
    state: ShareTourState,
    modifier: Modifier = Modifier,
) {
    val current = state.step ?: return
    val density = LocalDensity.current
    val target = state.targets[current]
    // Animatable rather than an animated value, because the *first* hole has to be
    // a cut, not a move: the tour opens on an already-laid-out screen, so growing
    // the hole out of the top-left corner would look like the sheet glitching.
    // Steps two and three travel, which is the point of animating at all.
    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(current, target) {
        if (target == null) return@LaunchedEffect
        if (hole.value.width <= 0f) {
            hole.snapTo(target)
        } else {
            hole.animateTo(target, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        }
    }
    val holeRect = hole.value
    val scrimAlpha = if (holeRect.width > 0f) 0.72f else 0f

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenHeightPx = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val radius = with(density) { 18.dp.toPx() }
            val scrim =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    if (holeRect.width > 0f && holeRect.height > 0f) {
                        addRoundRect(RoundRect(rect = holeRect.inflate(radius * 0.4f), cornerRadius = CornerRadius(radius)))
                    }
                }
            drawPath(scrim, Color.Black.copy(alpha = scrimAlpha))
        }

        if (holeRect.width > 0f && holeRect.height > 0f) {
            // A slow breath on the ring, not a strobe: it says "look here" while
            // the copy carries the instruction.
            val pulse by
                rememberInfiniteTransition(label = "share-tour-pulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(1_100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "share-tour-ring",
                )
            Canvas(Modifier.fillMaxSize()) {
                val grow = with(density) { 3.dp.toPx() }
                drawRoundRect(
                    color = TempoPrimary.copy(alpha = 0.2f + 0.45f * pulse),
                    topLeft = Offset(holeRect.left - grow, holeRect.top - grow),
                    size = Size(holeRect.width + grow * 2f, holeRect.height + grow * 2f),
                    cornerRadius = CornerRadius(with(density) { 18.dp.toPx() }),
                    style = Stroke(width = with(density) { 1.5.dp.toPx() }),
                )
            }
        }

        // Tapping the dimmed area advances. The tour never traps someone who does
        // not want to hunt for a small button.
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = interaction, indication = null) { state.next() },
        )

        // Out here rather than in the card: the hand has to be over the deck it is
        // describing, and the deck is the only place with room for it to travel.
        if (current == ShareTourStep.CAROUSEL) {
            ShareSwipeCoach(hole = holeRect)
        }

        ShareTourCallout(
            step = current,
            hole = holeRect,
            screenHeightPx = screenHeightPx,
            steps = state.shownSteps,
            onNext = state::next,
            onSkip = state::finish,
        )
    }
}

/**
 * The instruction card, placed clear of the spotlight: under it when there is
 * room, above it when the target sits near the bottom of the sheet.
 */
@Composable
private fun ShareTourCallout(
    step: ShareTourStep,
    hole: Rect,
    screenHeightPx: Float,
    /** The steps this sheet has, so the dots and the last label follow it. */
    steps: List<ShareTourStep>,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val last = step == steps.lastOrNull()
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0) }
    // Each step's card rises the last few pixels instead of appearing fully drawn:
    // the hole is already moving, and a card that pops while the spotlight slides
    // reads as two unrelated things happening.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(step) {
        appear.snapTo(0f)
        appear.animateTo(1f, tween(durationMillis = 220, delayMillis = 80, easing = FastOutSlowInEasing))
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .offset {
                    val gap = with(density) { 20.dp.toPx() }
                    val below = hole.bottom + gap
                    val above = hole.top - gap - height
                    val roomBelow = screenHeightPx > 0f && below + height < screenHeightPx - gap
                    val preferred =
                        when {
                            // Below first. The carousel's hole is most of the sheet,
                            // and the only strip that can hold a card is under it —
                            // over the caption and the deck, which the copy is
                            // about anyway. Dropping it over the hole itself would
                            // hide the card the step is pointing at.
                            roomBelow -> below
                            above >= gap -> above
                            // Neither side fits. Fall back to the bottom of the
                            // hole rather than the top: the card's header and its
                            // first row live at the top.
                            else -> hole.bottom - height - gap
                        }
                    // Clamped, not just chosen: a tall hole can leave neither side
                    // with room, and an unclamped offset would push the card off
                    // the screen.
                    val maxTop = (screenHeightPx - height - gap).coerceAtLeast(gap)
                    val y = preferred.coerceIn(gap, maxTop)
                    IntOffset(
                        0,
                        // Rise into place as it fades in, then sit still.
                        (y + (1f - appear.value) * with(density) { 8.dp.toPx() }).roundToInt(),
                    )
                }
                .graphicsLayer {
                    alpha = appear.value
                }
                .onSizeChanged { height = it.height }
                .clip(RoundedCornerShape(20.dp))
                .background(TempoSurfaceDialog)
                .border(1.dp, TempoPrimary.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = step.title(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            // Step dots: a tour of two or three needs a length, or the last card
            // is a surprise.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { entry ->
                    Box(
                        Modifier
                            .size(if (entry == step) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (entry == step) TempoPrimary else Color.White.copy(alpha = 0.22f)),
                    )
                }
            }
        }
        Text(
            text = step.body(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_share_tour_skip),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.5f),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(
                modifier =
                    Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(TempoPrimary)
                        .clickable(onClick = onNext)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            if (last) R.string.stats_share_tour_done else R.string.stats_share_tour_next,
                        ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextOnAccent,
                )
            }
        }
    }
}

/** The small "?" that replays the tour from the sheet's header. */
@Composable
internal fun ShareTourReplayButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stats_share_tour_replay)
    Box(
        modifier =
            modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                // A question mark is not a description: the glyph is the visual,
                // the label is what TalkBack reads and what makes the button's
                // purpose ("show tips again") discoverable out loud.
                .semantics { contentDescription = label }
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f),
        )
    }
}

/**
 * The swipe demo for the carousel step: a hand actually dragging the deck.
 *
 * It lives out here on the spotlight, not inside the instruction card, because
 * that is where the gesture happens — a postage-stamp picture of dragging, printed
 * on a card of prose, describes a swipe without ever showing the deck move.
 *
 * The hand crosses the entire deck and leaves the card on both sides; it is
 * teaching a swipe of the sheet, not a nudge inside one card, so a hand that
 * stays within the card's bounds teaches the wrong amplitude. It pauses at each
 * extreme and lifts between passes, because a finger that glides back and forth
 * without releasing reads as a slider, not two swipes.
 *
 * Drawn, not an icon: outlined "swipe" glyphs are furniture at this size. A light
 * hand with weight under it, a nail, a shadow and a contact ripple is an
 * instruction.
 */
@Composable
private fun ShareSwipeCoach(
    hole: Rect,
    modifier: Modifier = Modifier,
) {
    // 0 = centred, -1 = dragged left, +1 = dragged right. An Animatable rather
    // than a transition so the dwell at each end is a real pause and not an
    // easing curve standing in for one.
    val travel = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            // Left: press, drag, hold, lift, drift back.
            press.animateTo(1f, tween(120))
            travel.animateTo(-1f, tween(680, easing = FastOutSlowInEasing))
            delay(260)
            press.animateTo(0f, tween(140))
            travel.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
            delay(200)
            // Right: the same, mirrored. Two separate presses keep the two
            // directions as two gestures instead of one long wobble.
            press.animateTo(1f, tween(120))
            travel.animateTo(1f, tween(680, easing = FastOutSlowInEasing))
            delay(260)
            press.animateTo(0f, tween(140))
            travel.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
            delay(560)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (hole.width <= 0f || hole.height <= 0f) return@Canvas
        // Fingertip travels most of the card's width. Not the full width: the hand
        // is a physical object the width of a thumb, and dragging it right out of
        // frame clips it against the screen edge, which reads as a rendering bug
        // rather than a gesture.
        val span = hole.width * 0.30f
        val cx = hole.center.x + travel.value * span
        // Low on the card, where a thumb actually sits, and clear of both the
        // title block at the top and the callout over the bottom edge. The wrist
        // disappears behind the callout, which is the occlusion a real hand would
        // have against a card lying on top of it.
        val cy = hole.top + hole.height * 0.58f

        // Friction line: one hairline from where the finger started this run to
        // where it is now, fading out behind it. The ghost hands say "the hand is
        // moving"; this says the fingertip is *dragging* — that it is pressed
        // against the glass and something is holding it back. Drawn before the
        // hand so the palm covers its origin instead of having a stroke run out
        // from under the wrist.
        val drag = abs(travel.value)
        if (drag > 0.02f) {
            val trailStartX = hole.center.x
            val trailEndX = cx
            // Thin at the ends, thickest where the finger is: pressure, not a
            // highlighter. At rest the line collapses into the contact pool.
            drawLine(
                brush =
                    Brush.horizontalGradient(
                        colors = listOf(TempoPrimary.copy(alpha = 0f), TempoPrimary.copy(alpha = 0.55f)),
                        startX = trailStartX,
                        endX = trailEndX,
                    ),
                start = Offset(trailStartX, cy),
                end = Offset(trailEndX, cy),
                strokeWidth = (1.5.dp + 2.5.dp * drag).toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Motion smear: the hand redrawn at its own recent positions, each copy
        // fainter. A coloured streak says "swipe" the way an arrow says "go" —
        // it is a symbol. Ghosts of the moving object are what a real hand
        // crossing a screen leaves behind, and they need no legend.
        val smearDir = if (travel.value < 0f) 1f else -1f
        val smear = min(1f, abs(travel.value) * 2f)
        repeat(3) { index ->
            val ghost = (index + 1) / 3f
            drawTouchHand(
                center = Offset(cx + smearDir * ghost * 17.dp.toPx() * smear, cy),
                press = press.value,
                alpha = 0.17f * (1f - ghost),
            )
        }

        // Soft contact pool under the fingertip: the hand needs to sit *on* the
        // card, and a cast shadow alone leaves it hovering.
        drawCircle(
            color = Color.Black.copy(alpha = 0.10f + 0.06f * press.value),
            radius = 13.dp.toPx(),
            center = Offset(cx, cy + 2.dp.toPx()),
        )

        drawTouchHand(center = Offset(cx, cy), press = press.value)
    }
}

/**
 * A pointing hand, seen from above, fingertip on the glass.
 *
 * The outline is traced from a real hand SVG rather than assembled from rounded
 * rectangles: the fingertip, the folded fingers, the thumb folded across the palm
 * and the heel of the hand are all one contour, which is the difference between a
 * hand and a stack of pills. Coordinates below are the SVG's own units so the
 * shape can be re-traced from the source if it ever needs a tweak; [px]/[py] scale
 * and anchor it, with the fingertip landing exactly on the touch point.
 *
 * Drawn twice: once displaced and dark for the cast shadow, once lit, and the
 * lit pass is filled with one gradient so a knuckle never shows a seam.
 */
private fun DrawScope.drawTouchHand(
    center: Offset,
    press: Float,
    alpha: Float = 1f,
) {
    // Pressing squashes the hand a touch; it is the cheapest cue that the finger
    // is loaded against the glass rather than hovering over it.
    val h = 82.dp.toPx() * (1f - 0.03f * press)
    // A hand pointing dead straight up looks like a diagram. This matches how a
    // thumb-side grip reaches across a phone screen.
    val tilt = -17f

    rotate(degrees = tilt, pivot = center) {
        // Cast shadow, offset away from the light, and a thin rim so the hand keeps
        // an edge over a bright theme card as well as a dark one. Ghost copies skip
        // the shadow and the rim: at a sixth of an alpha they would only add haze.
        if (alpha > 0.4f) {
            val shadow = handPath(Offset(center.x + 2.dp.toPx(), center.y + 3.5.dp.toPx()), h)
            drawPath(path = shadow, color = Color.Black.copy(alpha = 0.32f * alpha))
        }
        drawPath(
            path = handPath(center, h),
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFEFEFE), Color(0xFFCFD6D6)),
                    start = Offset(center.x - h * 0.6f, center.y),
                    end = Offset(center.x + h * 0.6f, center.y + h),
                ),
            alpha = alpha,
        )
        // Rim inside the silhouette: cheap, and it survives a white card.
        if (alpha > 0.4f) {
            drawPath(
                path = handPath(center, h),
                color = Color(0xFF0A0E0E).copy(alpha = 0.16f * alpha),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * The hand outline, traced from an SVG, anchored so the fingertip is [center].
 *
 * [h] is the height of the drawing in pixels. The source contour spans y 4.31 to
 * 93.75 and x 14.13 to 84.38 in its own units, so one scale factor serves both
 * axes and the hand never stretches.
 */
private fun handPath(
    center: Offset,
    h: Float,
): Path {
    // Source-unit -> pixel mapping. The contour spans y 6.36 (the top of the
    // fingertip cap, where the two control points at 4.31 peak) to 93.75, so
    // 87.39 units become [h] pixels; one factor serves both axes so the hand
    // never stretches. 37.5 is the fingertip's x.
    val scale = h / 87.39f
    fun px(x: Float) = center.x + (x - 37.5f) * scale
    fun py(y: Float) = center.y + (y - 6.36f) * scale

    return Path().apply {
        moveTo(px(73.438f), py(37.5f))
        lineTo(px(71.8755f), py(37.5f))
        lineTo(px(71.8755f), py(45.3125f))
        cubicTo(px(71.8755f), py(47.375f), px(68.7505f), py(47.375f), px(68.7505f), py(45.3125f))
        lineTo(px(68.7505f), py(34.6245f))
        cubicTo(px(67.5005f), py(33.312f), px(65.438f), py(31.2495f), px(59.3755f), py(31.2495f))
        lineTo(px(59.3755f), py(42.1875f))
        cubicTo(px(59.3755f), py(44.25f), px(56.2505f), py(44.25f), px(56.2505f), py(42.1875f))
        lineTo(px(56.2505f), py(30.5625f))
        cubicTo(px(53.0943f), py(27.7187f), px(50.0005f), py(28.125f), px(46.8755f), py(28.125f))
        lineTo(px(46.8755f), py(39.063f))
        cubicTo(px(46.8755f), py(41.1255f), px(43.7505f), py(41.1255f), px(43.7505f), py(39.063f))
        // The index finger: straight up, cap rounded by the cubic above.
        lineTo(px(43.7505f), py(12.501f))
        cubicTo(px(43.7505f), py(4.3135f), px(31.2505f), py(4.3135f), px(31.2505f), py(12.501f))
        lineTo(px(31.2505f), py(51.563f))
        cubicTo(px(31.2505f), py(53.6255f), px(28.1255f), py(53.6255f), px(28.1255f), py(51.563f))
        // Knuckle notch, then the thumb folding down the left side.
        lineTo(px(28.1255f), py(36.219f))
        lineTo(px(20.188f), py(44.2502f))
        cubicTo(px(14.9692f), py(49.5314f), px(14.1255f), py(58.3442f), px(18.2192f), py(64.7192f))
        cubicTo(px(26.2192f), py(77.2502f), px(28.1254f), py(79.0942f), px(28.1254f), py(84.3752f))
        cubicTo(px(28.1254f), py(89.6562f), px(32.3442f), py(93.7502f), px(37.5004f), py(93.7502f))
        // Heel of the hand across the bottom, then up the outside edge.
        lineTo(px(66.2814f), py(93.7502f))
        cubicTo(px(70.7814f), py(93.7502f), px(74.6564f), py(90.5314f), px(75.5002f), py(86.0627f))
        cubicTo(px(77.6877f), py(74.7187f), px(84.3752f), py(74.0937f), px(84.3752f), py(58.7187f))
        lineTo(px(84.3752f), py(49.9999f))
        cubicTo(px(84.3752f), py(44.0937f), px(79.6877f), py(37.4999f), px(73.4372f), py(37.4999f))
        close()
    }
}

/** Title/body copy for one step. Kept beside the enum so order and copy cannot drift. */
@Composable
private fun ShareTourStep.title(): String =
    when (this) {
        ShareTourStep.CAROUSEL -> stringResource(R.string.stats_share_tour_carousel_title)
        ShareTourStep.DECK -> stringResource(R.string.stats_share_tour_deck_title)
        ShareTourStep.SHARE -> stringResource(R.string.stats_share_tour_share_title)
    }

@Composable
private fun ShareTourStep.body(): String =
    when (this) {
        ShareTourStep.CAROUSEL -> stringResource(R.string.stats_share_tour_carousel_body)
        ShareTourStep.DECK -> stringResource(R.string.stats_share_tour_deck_body)
        ShareTourStep.SHARE -> stringResource(R.string.stats_share_tour_share_body)
    }
