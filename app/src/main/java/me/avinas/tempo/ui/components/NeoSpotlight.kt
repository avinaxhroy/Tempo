package me.avinas.tempo.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────────────────────
// Neo-skeuomorphic Spotlight entry points.
//
// The rule: one matte body, one light source, one accent. The surface is a
// solid slab of tinted plastic — lit top bevel, shadowed lip along the bottom,
// soft contact shadow. Colour sits in the accent disc and in the light falling
// across the slab's lit corner; nothing glows.
// ──────────────────────────────────────────────────────────────

/** Muted brand teal — saturated enough to read as material, not as a light source. */
private val SlabTeal = Color(0xFF25C0A8)
private val SlabTealDeep = Color(0xFF0E5B52)
private val SlabInk = Color(0xFF050A09)

/** Body of the slab: matte, tinted dark, lit from the top-left. */
private fun slabFace(enabled: Boolean): Brush =
    if (enabled) {
        Brush.verticalGradient(
            0.0f to Color(0xFF2B4A42),
            0.40f to Color(0xFF1A2A25),
            1.0f to Color(0xFF0C1311)
        )
    } else {
        Brush.verticalGradient(
            0.0f to Color(0xFF232C2A),
            1.0f to Color(0xFF101514)
        )
    }

/** Disc body: teal at the lit crown, deeper at the shadowed base. */
private fun discFace(enabled: Boolean): Brush =
    if (enabled) {
        Brush.verticalGradient(
            0.0f to Color(0xFF3FD3BB),
            0.55f to SlabTeal,
            1.0f to SlabTealDeep
        )
    } else {
        Brush.verticalGradient(
            0.0f to Color(0xFF2C3332),
            1.0f to Color(0xFF191F1E)
        )
    }

/**
 * A raised, solid card with a lit bevel and a shadowed lip. Pass [onClick] to make
 * the whole slab a button; it sinks toward the floor while pressed.
 */
@Composable
fun NeoSpotlightSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = enabled && isPressed

    val lift by animateDpAsState(
        targetValue = if (pressed) 3.dp else 10.dp,
        animationSpec = tween(130, easing = FastOutSlowInEasing),
        label = "slab_lift"
    )
    val sink by animateDpAsState(
        targetValue = if (pressed) 1.dp else 0.dp,
        animationSpec = tween(130, easing = FastOutSlowInEasing),
        label = "slab_sink"
    )

    val face = remember(enabled) { slabFace(enabled) }

    Box(
        modifier = modifier
            .graphicsLayer { translationY = sink.toPx() }
            .shadow(
                elevation = lift,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.65f)
            )
            .clip(shape)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val radius = cornerRadius.toPx()

            // 1. Matte body.
            drawRoundRect(
                brush = face,
                size = Size(w, h),
                cornerRadius = CornerRadius(radius, radius)
            )

            if (enabled) {
                // 2. Light falling across the lit corner: colour as illumination.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(SlabTeal.copy(alpha = 0.26f), Color.Transparent),
                        center = Offset(w * 0.05f, 0f),
                        radius = w * 0.85f
                    ),
                    size = Size(w, h)
                )
            }

            // 3. Lit top bevel. A soft band, not a hairline: light rakes across the
            //    upper face and dies out before it reaches the sides.
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (enabled) 0.20f else 0.06f),
                    0.55f to Color.White.copy(alpha = if (enabled) 0.06f else 0.02f),
                    1.0f to Color.Transparent,
                    startY = 0f,
                    endY = 16.dp.toPx()
                ),
                size = Size(w, 16.dp.toPx())
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.12f to Color.White.copy(alpha = if (enabled) 0.42f else 0.12f),
                        0.68f to Color.White.copy(alpha = if (enabled) 0.16f else 0.06f),
                        1f to Color.Transparent
                    )
                ),
                start = Offset(radius * 0.45f, 1.dp.toPx()),
                end = Offset(w - radius * 0.45f, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )

            // 4. Shadowed lip plus a thin extruded wall: the face turns away from the
            //    light, then meets the floor before the silhouette closes.
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.40f to SlabInk.copy(alpha = 0.20f),
                        1f to SlabInk.copy(alpha = 0.68f)
                    ),
                    startY = h - 16.dp.toPx(),
                    endY = h
                ),
                topLeft = Offset(0f, h - 16.dp.toPx()),
                size = Size(w, 16.dp.toPx())
            )
            // Light bouncing off the floor catches the very bottom edge.
            drawLine(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.25f to Color.White.copy(alpha = if (enabled) 0.14f else 0.05f),
                        0.75f to Color.White.copy(alpha = if (enabled) 0.14f else 0.05f),
                        1f to Color.Transparent
                    )
                ),
                start = Offset(radius, h - 1.dp.toPx()),
                end = Offset(w - radius, h - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )

            // 5. Light gathering on the lit flank — the reflective edge of the mould.
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (enabled) 0.18f else 0.05f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.55f, h * 0.9f)
                ),
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
                cornerRadius = CornerRadius(radius - 1.dp.toPx(), radius - 1.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )

            // 6. Dark hairline silhouette — separates the slab without an outline glow.
            drawRoundRect(
                color = SlabInk.copy(alpha = if (enabled) 0.50f else 0.38f),
                topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                size = Size(w - 1.dp.toPx(), h - 1.dp.toPx()),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

/**
 * The physical action cue: a moulded teal disc with a specular crown, a shadowed
 * base and a contact shadow. Decorative by default; pass [onClick] to make it
 * actionable on its own.
 */
@Composable
fun NeoSpotlightCta(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    enabled: Boolean = true,
    icon: ImageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
    iconSize: Dp = 13.dp,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = enabled && isPressed

    val sink by animateDpAsState(
        targetValue = if (pressed) 1.5.dp else 0.dp,
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "disc_sink"
    )

    val face = remember(enabled) { discFace(enabled) }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { translationY = sink.toPx() }
            .shadow(
                elevation = if (enabled) 7.dp else 0.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.70f)
            )
            .clip(CircleShape)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val r = w / 2f

            // Moulded body.
            drawCircle(brush = face, radius = r, center = center)

            if (enabled) {
                // Specular crown: soft, off-centre, upper-left.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (pressed) 0.22f else 0.34f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.30f, h * 0.16f),
                        radius = w * 0.44f
                    ),
                    radius = w * 0.44f,
                    center = Offset(w * 0.30f, h * 0.16f)
                )

                // Shadowed base: the lower third rolls away from the light.
                drawCircle(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.60f to Color.Transparent,
                            1f to SlabInk.copy(alpha = 0.34f)
                        )
                    ),
                    radius = r,
                    center = center
                )
            }

            // Rim: lit at the crown, inked at the base.
            drawCircle(
                brush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (enabled) 0.50f else 0.10f),
                    0.5f to Color.White.copy(alpha = if (enabled) 0.09f else 0.04f),
                    1.0f to SlabInk.copy(alpha = 0.35f)
                ),
                radius = r - 0.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Hairline silhouette.
            drawCircle(
                color = SlabInk.copy(alpha = 0.28f),
                radius = r - 0.25.dp.toPx(),
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }

        // Engraved arrow: a dark offset copy under the light glyph.
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = SlabInk.copy(alpha = 0.38f),
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    translationX = 0.5.dp.toPx()
                    translationY = 1.dp.toPx()
                }
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color(0xFFF2FFFB) else Color.White.copy(alpha = 0.50f),
            modifier = Modifier.size(iconSize)
        )
    }
}
