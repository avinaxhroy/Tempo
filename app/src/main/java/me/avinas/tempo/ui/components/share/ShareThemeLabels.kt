package me.avinas.tempo.ui.components.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.ShareTheme

/**
 * Wording for one theme, and the one-line description of what a scene draws.
 *
 * Shared rather than owned by the stats sheet: the song/artist/badge share
 * sheets offer the same thirteen themes, and a card that said "Midnight" on one
 * screen and something else on the next would be describing two different
 * features.
 */

/** The carousel's caption name for a theme. */
@Composable
fun ShareThemeId.label(): String =
    when (this) {
        is ShareThemeId.Still -> stillLabel(theme)
        is ShareThemeId.Motion -> motionLabel(motion)
    }

@Composable
private fun stillLabel(theme: ShareTheme): String =
    when (theme) {
        ShareTheme.MIDNIGHT -> stringResource(R.string.share_theme_midnight)
        ShareTheme.GLASS -> stringResource(R.string.share_theme_glass)
        ShareTheme.ASCII -> stringResource(R.string.share_theme_ascii)
        ShareTheme.MINIMUM -> stringResource(R.string.share_theme_minimum)
        ShareTheme.DAYLIGHT -> stringResource(R.string.share_theme_daylight)
        ShareTheme.DITHER -> stringResource(R.string.share_theme_dither)
    }

@Composable
private fun motionLabel(motion: ShareMotion): String =
    when (motion) {
        ShareMotion.GRADIENT_WAVES -> stringResource(R.string.stats_share_motion_waves)
        ShareMotion.SHAPE_WAVES -> stringResource(R.string.stats_share_motion_shapes)
        ShareMotion.GRADIENT_ASCII -> stringResource(R.string.stats_share_motion_glyphs)
        ShareMotion.LIQUID_ETHER -> stringResource(R.string.stats_share_motion_ether)
        ShareMotion.PIXEL_BLAST -> stringResource(R.string.stats_share_motion_pixels)
        ShareMotion.VERTICAL_BARS -> stringResource(R.string.stats_share_motion_bars)
        ShareMotion.DITHERING -> stringResource(R.string.stats_share_motion_halftone)
    }

/** One-line description of a motion theme, in the user's own data. */
@Composable
fun motionCaption(motion: ShareMotion): String =
    when (motion) {
        ShareMotion.GRADIENT_WAVES -> stringResource(R.string.stats_share_motion_caption_waves)
        ShareMotion.SHAPE_WAVES -> stringResource(R.string.stats_share_motion_caption_shapes)
        ShareMotion.GRADIENT_ASCII -> stringResource(R.string.stats_share_motion_caption_glyphs)
        ShareMotion.LIQUID_ETHER -> stringResource(R.string.stats_share_motion_caption_ether)
        ShareMotion.PIXEL_BLAST -> stringResource(R.string.stats_share_motion_caption_pixels)
        ShareMotion.VERTICAL_BARS -> stringResource(R.string.stats_share_motion_caption_bars)
        ShareMotion.DITHERING -> stringResource(R.string.stats_share_motion_caption_halftone)
    }
