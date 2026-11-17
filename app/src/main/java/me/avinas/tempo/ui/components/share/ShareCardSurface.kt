package me.avinas.tempo.ui.components.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.LocalMotionOverlay
import me.avinas.tempo.ui.components.ShareArtSource
import me.avinas.tempo.ui.components.ShareThemeDecorations
import me.avinas.tempo.ui.components.ShareThemePalette

/**
 * The page every share card is printed on: theme gradient, artwork, scene, veil,
 * branding — in that order, for every card the app can share.
 *
 * One surface rather than one per card family. The stats card and the
 * song/artist/badge cards used to each carry their own copy of this stack, and
 * the copies drifted: the stats one learned to draw a motion scene and the veil
 * that keeps white type alive over it, and the others could not animate at all.
 * A theme is supposed to look the same wherever it is offered, which is only
 * true if there is one place that draws it.
 *
 * The same argument applies to the ink. A moving theme seeds its type from a
 * still theme, but that theme's accent was picked against a flat backdrop:
 * ASCII's green over a cover's warm glyph field reads as a mistake, and the
 * halftone's amber disappears into its own print. The palette handed to
 * [content] therefore has its accent and rank tint re-derived from the scene,
 * so every card recolours its type the same way when a scene is on.
 */
@Composable
fun ShareCardSurface(
    card: ShareCardTheme,
    modifier: Modifier = Modifier,
    art: ShareArtSource? = null,
    brandingBottomPadding: Dp = 16.dp,
    content: @Composable BoxScope.(palette: ShareThemePalette) -> Unit,
) {
    val motion = card.motion
    val motionTime = card.motionTime
    // While a motion export is being composited, this card paints only its top
    // layer: the scene underneath is shaded by the exporter, so the page itself
    // has to be transparent.
    val overlayMode = motion != null && LocalMotionOverlay.current
    // Colours the scene is shaded with and the ink the card is set in. One
    // derivation, shared with the sheet that exports this card, so the preview
    // the user picks a theme on and the frame that gets exported cannot be
    // two different colours.
    val palettes = rememberShareCardPalettes(card)
    val scenePalette = palettes.scene
    val palette = palettes.ink

    Box(
        modifier =
            // Every share card is authored at the same aspect; the page owns it so
            // a card cannot be written at a different one by accident. Inside the
            // sheet the card is handed a fixed 360x640 box, where this is a no-op.
            modifier
                .aspectRatio(ShareCardAspect)
                .then(if (overlayMode) Modifier else Modifier.background(brush = Brush.verticalGradient(palette.gradient))),
    ) {
        // Theme-specific backdrop decoration, or the animated scene when one is
        // selected. This is the whole backdrop: the aura, the theme's treatment
        // of it and the readability scrim underneath the copy all come from
        // there, whichever kind of art the card handed in.
        ShareThemeDecorations(
            palette = palette,
            art = art,
            motion = motion,
            motionTime = motionTime,
            motionPalette = scenePalette,
        )

        // A scene is a background, not a poster: it is bright where it is
        // busiest, and the card's rows are white text. This scrim holds the copy
        // against the picture — heaviest at the header and the footer, where the
        // type is largest, and lighter through the middle of the card so the
        // scene keeps its own contrast.
        //
        // The scrim is the *scene's own page colour*, not the theme's last
        // gradient stop: a card whose motion came from the #1 cover must not have
        // the theme's tone painted back over it, or a dithered black page turns
        // violet and the artwork's colour stops reading as the artwork's.
        //
        // A paper scene needs it just as much, for the opposite reason: its type
        // is dark, and a print busy enough to be worth looking at is busy enough
        // to swallow a label. The same veil in the scene's own paper colour
        // pushes the print back toward the sheet under the copy, so it is applied
        // on both, only lighter where the page is already light.
        if (motion != null) {
            val tone = scenePalette.background
            // A dark page's veil is heavy and even. A moving scene is a *field*
            // of mid tones — the halftone's masses reach near-white — and white
            // type only survives it if the page underneath is taken most of the
            // way back to its own colour. The picture still reads: what is left
            // is its motion and the colour of its masses.
            //
            // A paper page needs the same, for the opposite reason: its type is
            // dark, its print is mid-toned, and a label in the cover's amber can
            // land on a mass of the same tone.
            val veil =
                if (scenePalette.isDark) {
                    listOf(0.88f, 0.80f, 0.76f, 0.80f, 0.90f)
                } else {
                    listOf(0.80f, 0.72f, 0.60f, 0.64f, 0.76f)
                }
            // Not every scene loads the page the same way. A print covers the
            // card with mid tones and needs the heavy veil; a dot field leaves
            // the page dark and only puts bright specks on it, and veiling that
            // as hard turns the blast into a smudge. The weight scales the veil
            // to how much of the page the scene actually fills.
            val weight =
                when (motion) {
                    // The print is the point of this scene; the full veil buried
                    // it. Keep enough page for white type without flattening the
                    // masses back to maroon.
                    ShareMotion.DITHERING -> 0.82f
                    ShareMotion.PIXEL_BLAST -> 0.55f
                    // A paper scene's type is dark, but the bars are a dense
                    // field: at 0.45 they ran through every label. The veil is
                    // the paper colour, so it mutes the meter without touching
                    // the black type.
                    ShareMotion.VERTICAL_BARS -> 1f
                    // This scene keeps its own page dark and only adds light on
                    // top of it, so type already has its contrast. Veiling it
                    // heavily was the last thing flattening the ramp: at 0.9 the
                    // background tone was laid over the whole card at ~0.7 alpha
                    // and every hue came back as a tint of that one colour.
                    ShareMotion.GRADIENT_ASCII -> 0.28f
                    else -> 1f
                }
            val scrim = { a: Float -> tone.copy(alpha = a * weight) }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to scrim(veil[0]),
                                0.13f to scrim(veil[1]),
                                0.45f to scrim(veil[2]),
                                0.80f to scrim(veil[3]),
                                1.00f to scrim(veil[4]),
                            ),
                        ),
            )
        }

        content(palette)

        // Branding footer
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = brandingBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.share_brand),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = palette.branding,
                letterSpacing = 4.sp,
            )
        }
    }
}

/**
 * The hairline between two statistics on a card.
 *
 * Eight copies of this existed across the ranking card and the song/artist/badge
 * cards, in three heights, each written out as
 * `Box(Modifier.height(n.dp).width(1.dp).background(palette.divider))`. The
 * heights differ for a reason — the rows they sit in are of different heights —
 * so the height stays a parameter and everything else is shared.
 */
@Composable
fun ShareStatDivider(
    palette: ShareThemePalette,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(height)
                .width(1.dp)
                .background(palette.divider),
    )
}

/**
 * The story safe zone: how far every card keeps its content from the top edge.
 *
 * A share card is posted to Instagram/TikTok stories, where the host draws its
 * own chrome over the canvas — a username row and avatar across the top. The
 * ranking card reserved room for that overlay from the start (52dp of the 640dp
 * card, the top eighth), but the single-subject cards only inset 26dp and 36dp:
 * the standing kicker on a song card and the identity pill on a badge card both
 * landed inside the avatar row, so the first line of every such card was partly
 * hidden the moment it was posted.
 *
 * One constant rather than a number per card, because the overlay is the host's,
 * not the card's: it does not get smaller for a badge.
 */
val ShareStorySafeTop: Dp = 52.dp

/**
 * The margin a card standing on a cover sets its content in: song, artist.
 *
 * The four single-subject cards had two frames between them, each written out
 * twice — one for the cards with a cover (song, artist) and one for the cards
 * with a procedural backdrop (badge, milestone), which start lower so the emblem
 * has room. Written out at each card, the members of a pair could drift apart
 * one spacing change at a time, and a badge and a milestone showing different
 * margins is not a decision anyone would make on purpose.
 */
val ShareArtworkCardPadding =
    PaddingValues(top = ShareStorySafeTop, bottom = 64.dp, start = 24.dp, end = 24.dp)

/** The margin for a card drawn from a procedural backdrop: badge, milestone. */
val ShareProceduralCardPadding =
    PaddingValues(top = ShareStorySafeTop, bottom = 68.dp, start = 24.dp, end = 24.dp)

/**
 * How far a single-subject card's branding sits from the bottom edge — a little
 * higher than the ranking card's 16.dp, whose last row is a table rather than a
 * subject line.
 */
val ShareCardBrandingInset: Dp = 36.dp

/** The middot between two facts on one line of a card. */
internal const val SHARE_META_SEPARATOR = "·"

/**
 * The stamp at the top of a badge or milestone card: who is being celebrated,
 * and for what.
 *
 * Written out twice before — once on the badge card, once on the milestone card —
 * as the same twenty lines of pill, avatar-or-initial, name and separator. The
 * two only ever differed in the word after the separator and the accent it is
 * set in, so those are the parameters and the rest is one implementation: a
 * profile picture that renders in one card and an initial in the other is not a
 * distinction anyone chose.
 */
@Composable
fun ShareIdentityPill(
    palette: ShareThemePalette,
    userName: String,
    profileImagePath: String?,
    accent: Color,
    label: String,
    modifier: Modifier = Modifier,
    labelLetterSpacing: TextUnit = 1.sp,
) {
    val pillShape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .clip(pillShape)
                .background(palette.surface)
                .border(1.dp, palette.divider, pillShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!profileImagePath.isNullOrBlank()) {
                CachedAsyncImage(
                    imageUrl = profileImagePath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    // A hardware bitmap cannot be drawn into the exported canvas.
                    allowHardware = false,
                )
            } else {
                Text(
                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                )
            }
        }
        Text(
            text = userName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
        )
        Text(
            text = SHARE_META_SEPARATOR,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            // On a dark page the accent is the only colour in the row; on a light
            // one it is too pale to read, so the theme's strong ink stands in.
            color = if (palette.isDark) accent else palette.textStrong,
            letterSpacing = labelLetterSpacing,
        )
    }
}

/**
 * The pill a card opens with when its subject has a standing worth stating.
 *
 * Written out three times before — once on the artist card, once on the song
 * card, once on the album card — as the same twenty lines differing only in the
 * sentence inside. The sentence comes from that card's resolver; the pill is one
 * implementation here, so the three cannot drift apart again.
 */
@Composable
fun ShareStatusPill(
    text: String,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .clip(pillShape)
                .background(palette.surface)
                .border(1.dp, palette.divider, pillShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            // Dark pages have no colour other than the accent; on a light page it
            // is too pale to read, so the theme's strong ink stands in.
            color = if (palette.isDark) palette.accent else palette.textStrong,
            letterSpacing = 1.5.sp,
            fontSize = 10.sp,
        )
    }
}
