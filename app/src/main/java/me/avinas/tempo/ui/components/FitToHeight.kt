package me.avinas.tempo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

/**
 * Fits one composable into the box it is given.
 *
 * The job this does for every share card is uniform scaling: a card's content is
 * a fixed composition and the sheet is a fixed frame, so when the composition is
 * taller than the frame it is drawn smaller instead of clipped at the foot.
 * Every card scales the same way, from the same origin, so preview and export
 * agree.
 *
 * [stretch] adds the second behaviour a ruled page needs. A poster card is a
 * fixed composition, so a short one simply leaves air at the foot; but a card
 * whose body is a *table* fails the other way — its rows stack at the top, run
 * out, and leave a third of the sheet blank underneath them. With [stretch] a
 * page shorter than its frame is composed again against the frame's exact
 * height, so a weighted row can claim what the fixed rows left.
 *
 * Re-composed rather than scaled up, deliberately: scaling up would enlarge the
 * type along with the gaps, and what a page needs is more room between rows.
 */
@Composable
fun FitToHeight(
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    content: @Composable () -> Unit
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        // An unbounded pass, because that is the only way to learn what the page
        // *wants* to be. A row sharing the column's slack contributes only its
        // own minimum when the column's height is unbounded, so this measures
        // the page at its tightest — exactly the size it would print at if
        // nothing else happened.
        val naturalConstraints =
            Constraints(
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity
            )
        val natural =
            subcompose(FitSlot.Natural, content).firstOrNull()?.measure(naturalConstraints)
        if (natural == null) {
            layout(0, 0) {}
        } else {
            val maxH = constraints.maxHeight
            val width =
                if (constraints.maxWidth != Constraints.Infinity) {
                    constraints.maxWidth
                } else {
                    natural.width
                }
            // Stretch only when the page is shorter than the frame; otherwise
            // there is no slack to share. A taller page keeps the natural pass and
            // is scaled down, as every poster card is — a grid of covers must not
            // be clipped at the foot just because its rows were told the sheet is
            // exactly this tall.
            //
            // A second subcomposition rather than a second measure, because a
            // Measurable may only be measured once.
            val stretched =
                if (stretch && maxH != Constraints.Infinity && natural.height < maxH) {
                    subcompose(FitSlot.Stretched, content).firstOrNull()?.measure(
                        Constraints(
                            minWidth = constraints.minWidth,
                            maxWidth = constraints.maxWidth,
                            minHeight = maxH,
                            maxHeight = maxH
                        )
                    )
                } else {
                    null
                }
            val placeable = stretched ?: natural
            val naturalHeight = placeable.height
            val scale =
                if (maxH != Constraints.Infinity && naturalHeight > maxH && naturalHeight > 0) {
                    maxH.toFloat() / naturalHeight.toFloat()
                } else {
                    1f
                }
            val height =
                (naturalHeight * scale).toInt().let {
                    if (maxH != Constraints.Infinity) it.coerceAtMost(maxH) else it
                }
            layout(width, height) {
                placeable.placeWithLayer(x = 0, y = 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
            }
        }
    }
}

/** Slots for the two passes; constant ids keep subcomposition stable. */
private enum class FitSlot { Natural, Stretched }
