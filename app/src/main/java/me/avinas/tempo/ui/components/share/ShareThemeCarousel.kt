package me.avinas.tempo.ui.components.share

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.ShareTheme

/** The size every share card is authored at, before the screen scales it down. */
internal val ShareCardDesignWidth = 360.dp
internal val ShareCardDesignHeight = 640.dp

internal const val ShareCardAspect: Float = 360f / 640f

/**
 * The order the themes appear in: a theme's card, then the moving versions of it.
 *
 * The order is derived, not listed. It used to be thirteen hand-written ids,
 * which is a second copy of "every theme there is" — a theme added to
 * [ShareTheme] or [ShareMotion] appeared in [ShareThemeId.all] and then silently
 * did not appear in the strip, with nothing to catch it.
 *
 * The shape of the order is the two constraints that matter:
 *
 *  - No two still themes are adjacent. A swipe must never look like the carousel
 *    only offers posters; stepping either way from any card crosses into a scene.
 *  - A scene sits with the theme it is seeded from, immediately after it. A scene
 *    is the card the user is looking at, set in motion, so the pairing is what
 *    makes the swipe legible.
 *
 * There are seven scenes and six themes, so in a loop one adjacency has to be
 * scene-next-to-scene (thirteen pages, alternating, would need a seventh theme to
 * separate them). Grouping each theme's scenes together puts that adjacency
 * between two scenes of the *same* seed — [ShareMotion.GRADIENT_WAVES] and
 * [ShareMotion.PIXEL_BLAST] are both MIDNIGHT — which is the one pairing that
 * still reads as a progression rather than a repetition.
 *
 * The strip loops, so the last entry and the first are neighbours too: the loop
 * closes on DITHER's scene next to MIDNIGHT.
 */
internal val ShareCarouselOrder: List<ShareThemeId> =
    ShareTheme.entries.flatMap { theme ->
        buildList {
            add(ShareThemeId.Still(theme))
            // Declaration order, so a theme's primary scene is the one it
            // declares first.
            ShareMotion.entries.filter { it.seedTheme == theme }.forEach { add(ShareThemeId.Motion(it)) }
        }
    }

/**
 * [ShareCarouselOrder] as the sheet sees it: every card theme, as a choice.
 *
 * A sheet that offers card themes and nothing else — which is every sheet except
 * the story's — passes this straight through. The story builds its own list, with
 * its background first and card themes after, because its default is not a card.
 */
internal val ShareCardChoices: List<ShareChoice> = ShareCarouselOrder.map { CardChoice(it) }

/**
 * A page index in the middle of the pager's range that maps onto [index].
 *
 * The carousel is endless in both directions, which a pager only offers as a
 * very large page count plus a modulo. Opening in the middle of that range is
 * what makes "slidable from the right" true: there are as many pages behind the
 * opening card as ahead of it, so dragging either way always lands on another
 * card instead of hitting a wall.
 */
private const val CarouselLoopOrigin = Int.MAX_VALUE / 2

internal fun carouselStartPage(index: Int, count: Int): Int =
    CarouselLoopOrigin - CarouselLoopOrigin % count + index

/**
 * The theme picker: one swipeable card per theme, endless, with both neighbours
 * peeking in from the edges.
 *
 * This replaced a horizontally scrolling row of 44dp swatches, which the user
 * asked to be a carousel. The row had two problems. It was *flat* — a swatch can
 * show a theme's backdrop but not what a card in that theme looks like, so
 * thirteen swatches were thirteen guesses. And it was *one-directional*: stills
 * first, then the seven scenes, so its first half taught nothing about motion.
 * Here a page *is* the export — the same card the capture source draws — so
 * picking a theme is looking at a finished story.
 *
 * Cost is bounded on purpose. Only the settled page's clock runs; neighbours are
 * composed (ready before they land) but frozen, and the caller supplies the
 * per-page resolution through
 * [me.avinas.tempo.ui.components.share.LocalMotionResolutionScale].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShareThemeCarousel(
    choices: List<ShareChoice>,
    selected: ShareChoice,
    onSelect: (ShareChoice) -> Unit,
    cardSize: DpSize,
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    page: @Composable (choice: ShareChoice, focused: Boolean) -> Unit,
) {
    val count = choices.size
    val startIndex = choices.indexOfFirst { it.key == selected.key }.coerceAtLeast(0)
    val state =
        rememberPagerState(
            initialPage = carouselStartPage(startIndex, count),
            pageCount = { Int.MAX_VALUE },
        )
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // Reported while the card is still moving: the caption and the sheet's accent
    // should describe the card the user is closest to, not the one they left.
    val select by rememberUpdatedState(onSelect)
    // The peek alone says "there is another card over there", not "there are
    // cards in both directions". Until the user has actually moved the deck, two
    // drifting chevrons say the second half of that — and then get out of the way
    // for good, because a hint that survives the first swipe is just clutter.
    var hasMoved by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        var last = state.currentPage
        snapshotFlow { state.currentPage }.collect { pageIndex ->
            if (pageIndex != last) {
                hasMoved = true
                // One tick as the card snaps past the midpoint. Without it a
                // carousel of full cards has no tactile seam between pages.
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                last = pageIndex
            }
            select(choices[pageIndex.mod(count)])
        }
    }
    // Not while a render is in flight either: the chevrons invite a gesture that
    // is locked at exactly that moment.
    val showHint = !hasMoved && userScrollEnabled

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Equal insets on both sides centre the focused page; anything wider than
        // the card shows the neighbours behind it. There is no second row or
        // "see all" tray.
        val side = ((maxWidth - cardSize.width) / 2).coerceAtLeast(0.dp)
        HorizontalPager(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            pageSize = PageSize.Fixed(cardSize.width),
            pageSpacing = 12.dp,
            // Only pages inside the viewport are composed. Every page is a full
            // share card — ten rows, a blurred backdrop, a live scene — so
            // pre-composing an offscreen one is three cards of work for a view
            // nobody sees. The peeking neighbours are inside the viewport, so
            // they are already warm before they land.
            beyondViewportPageCount = 0,
            // Locked while a share is in flight: a swipe would change the exported
            // config under the running capture, which is the one thing the sheet
            // must not do mid-render.
            userScrollEnabled = userScrollEnabled,
            // A hair of overshoot on the snap. The default spring lands with no
            // bounce at all, which is correct for a list of text rows and slightly
            // dead for a card that is also scaling and turning as it lands: the
            // 0.86 damping gives the settle a fraction of a millimetre of follow-
            // through, which is what makes it feel physical rather than eased.
            flingBehavior =
                PagerDefaults.flingBehavior(
                    state = state,
                    snapAnimationSpec =
                        spring(
                            dampingRatio = 0.86f,
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = Int.VisibilityThreshold.toFloat(),
                        ),
                ),
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val choice = choices[pageIndex.mod(count)]
            // "Focused" is a composition-level fact (it starts the clock), so it
            // cannot be read inside the graphicsLayer lambda below — that would
            // recompose two live cards on every drag frame. It tracks
            // `currentPage` alone rather than "settled", so the card under the
            // user's finger keeps moving while they drag it.
            val focused = state.currentPage == pageIndex
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Distance from the snap position, in pages. Read in the
                            // draw phase: a drag costs a layer update instead of two
                            // card recompositions.
                            val raw = state.currentPage - pageIndex + state.currentPageOffsetFraction
                            val distance = if (raw < 0f) -raw else raw
                            val focus = (1f - distance).coerceIn(0f, 1f)
                            // Smoothstep instead of the raw ramp. A linear focus
                            // grows the card at a constant rate for the whole drag,
                            // which reads as a zoom rather than a card arriving;
                            // holding its size through the first third and
                            // completing quickly is what makes the settle feel like
                            // the end of a movement.
                            val eased = focus * focus * (3f - 2f * focus)
                            val scale = 0.82f + 0.18f * eased
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.3f + 0.7f * eased
                            // Neighbours sit lower and its own axis turns away: the
                            // card is about to be swiped past, so it should not sit
                            // flat beside the centred one.
                            translationY = (1f - eased) * 14.dp.toPx()
                            // 12° is under the angle where the card's own text
                            // starts to look bent, and the default camera distance
                            // keeps the perspective shallow — the neighbour leans
                            // away without the sheet looking like it is fisheye-ing.
                            rotationY = -raw.coerceIn(-1f, 1f) * 12f
                        },
                contentAlignment = Alignment.Center,
            ) {
                page(choice, focused)
                if (userScrollEnabled && !focused) {
                    // A peeking card is a target, not a surface: tapping it is the
                    // same gesture as swiping to it.
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) { scope.launch { state.animateScrollToPage(pageIndex) } },
                    )
                }
            }
        }

        // The hint for anyone who never saw the tour: two chevrons riding on the
        // peeking neighbours, drifting outward and back. They carry the one claim
        // the peek cannot make on its own — that the deck goes both ways — and
        // they retract the moment the user takes the hint.
        if (showHint) {
            ShareEdgeHints(modifier = Modifier.fillMaxSize(), side = side)
        }
    }
}

/**
 * Two drifting chevrons over the peeking cards.
 *
 * Animated rather than static because a still chevron is chrome — something to
 * decode — while a two-pixel drift reads as "this moves". They sit over the
 * neighbours, not at the sheet's edges, so the gesture they describe is the one
 * the cards themselves invite.
 */
@Composable
private fun ShareEdgeHints(
    modifier: Modifier = Modifier,
    side: Dp,
) {
    val drift by
        rememberInfiniteTransition(label = "share-edge-hint").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1_400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "share-edge-drift",
        )
    Box(modifier = modifier) {
        EdgeChevron(
            icon = Icons.Default.ChevronLeft,
            alpha = 0.22f + 0.2f * drift,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = (side / 4).coerceAtLeast(4.dp)),
        )
        EdgeChevron(
            icon = Icons.Default.ChevronRight,
            // Opposite phase: the two arrows breathe away from each other, which
            // is the shape a two-way swipe makes.
            alpha = 0.22f + 0.2f * (1f - drift),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = (side / 4).coerceAtLeast(4.dp)),
        )
    }
}

@Composable
private fun EdgeChevron(
    icon: ImageVector,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        // Not a control: the cards underneath it are the touch targets, so this
        // is decoration and must not become a second stop for TalkBack.
        contentDescription = null,
        tint = Color.White.copy(alpha = alpha),
        modifier = modifier.size(22.dp),
    )
}

/**
 * The line under the carousel: which choice is on screen, and — for a scene —
 * what it draws.
 *
 * The old strip needed one line only for the motion themes, because the swatch
 * above already carried the name. A card carousel says neither, so the name
 * comes back and the two travel together.
 *
 * The accent comes from the choice, not from a separate argument: a caption in a
 * colour the page never painted is the kind of detail that only looks wrong when
 * it ships.
 */
@Composable
internal fun ShareChoiceCaption(
    choice: ShareChoice,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = choice.key, animationSpec = tween(180), label = "share-theme-caption") {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = choice.label(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = choice.accent,
                    maxLines = 1,
                )
                // The choice describes itself. A still card theme has nothing to
                // add — "the theme's own backdrop, held still" only restates the
                // card the user is looking at — and the story adds what it is
                // rather than borrowing a scene's words.
                choice.captionSuffix(motionEnabled)?.let { suffix ->
                    Text(
                        text = "  ·  ",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.22f),
                        maxLines = 1,
                    )
                    Text(
                        text = suffix,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

/**
 * How much of the neighbouring card is allowed to show on each side.
 *
 * A peek is a hint, not a preview: 26dp of a neighbour's edge says "there is
 * another one over there" without taking width away from the card that is
 * actually being shared. It used to be a fraction of the screen (0.76 of the
 * width), which spent ~50dp per side on a phone and left the card looking like a
 * thumbnail of itself in a sheet whose whole subject is that card. Fixed rather
 * than proportional for the same reason: a tablet should show the same amount of
 * neighbour as a phone, not a third of its own width in two slivers.
 */
internal val ShareCarouselPeek: Dp = 26.dp

/** The largest card that fits [availableWidth] x [availableHeight], leaving a peek each side. */
internal fun fitCard(
    availableWidth: Dp,
    availableHeight: Dp,
    peek: Dp = ShareCarouselPeek,
): DpSize {
    val width =
        minOf(availableHeight * ShareCardAspect, availableWidth - peek * 2)
            .coerceAtLeast(96.dp)
    return DpSize(width, width / ShareCardAspect)
}
