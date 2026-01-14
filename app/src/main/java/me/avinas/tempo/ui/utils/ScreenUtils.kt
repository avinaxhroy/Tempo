package me.avinas.tempo.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Screen size category detection
// Compact: < 670dp (small phones, < 6")
// Medium: 670-780dp (6.0-6.4" phones)
// Expanded: > 780dp (6.5"+ phones)

@Composable
fun isCompactScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp < 670
}

@Composable
fun isMediumScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp in 670..780
}

@Composable
fun isExpandedScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp > 780
}

// Legacy functions for backwards compatibility
@Composable
fun isSmallScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp < 700 || configuration.screenWidthDp < 360
}

@Composable
fun isVerySmallScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp < 600
}

// Two-tier adaptive sizing (legacy)
@Composable
fun adaptiveSize(normal: Dp, small: Dp): Dp {
    return if (isSmallScreen()) small else normal
}

// Three-tier adaptive sizing with verySmall (legacy)
@Composable
fun adaptiveSize(normal: Dp, small: Dp, verySmall: Dp): Dp {
    val config = LocalConfiguration.current
    return when {
         config.screenHeightDp < 600 -> verySmall
         config.screenHeightDp < 700 -> small
         else -> normal
    }
}

// New three-tier adaptive sizing (compact/medium/expanded)
@Composable
fun adaptiveSizeByCategory(expanded: Dp, medium: Dp, compact: Dp): Dp {
    val config = LocalConfiguration.current
    return when {
        config.screenHeightDp < 670 -> compact
        config.screenHeightDp <= 780 -> medium
        else -> expanded
    }
}

// Proportional scaling based on screen height
@Composable
fun scaledSize(baseDp: Dp, minScale: Float = 0.85f, maxScale: Float = 1.15f): Dp {
    val config = LocalConfiguration.current
    val heightDp = config.screenHeightDp
    
    // Reference height: 720dp (typical 6.2" phone)
    val referenceHeight = 720f
    val scale = when {
        heightDp < 670 -> minScale // Compact screens
        heightDp > 850 -> maxScale // Large screens
        else -> (heightDp / referenceHeight).coerceIn(minScale, maxScale)
    }
    
    return baseDp * scale
}

// Two-tier adaptive text sizing (legacy)
@Composable
fun adaptiveTextUnit(normal: TextUnit, small: TextUnit): TextUnit {
    return if (isSmallScreen()) small else normal
}

// New three-tier adaptive text sizing
@Composable
fun adaptiveTextUnitByCategory(expanded: TextUnit, medium: TextUnit, compact: TextUnit): TextUnit {
    val config = LocalConfiguration.current
    return when {
        config.screenHeightDp < 670 -> compact
        config.screenHeightDp <= 780 -> medium
        else -> expanded
    }
}

@Composable
fun <T> adaptiveValue(normal: T, small: T): T {
    return if (isSmallScreen()) small else normal
}

@Composable
fun rememberScreenHeightPercentage(percentage: Float): Dp {
    val config = LocalConfiguration.current
    return (config.screenHeightDp * percentage).dp
}

/**
 * Returns a Dp value as a percentage of screen width.
 * Useful for width-dependent spacing and sizing.
 */
@Composable
fun rememberScreenWidthPercentage(percentage: Float): Dp {
    val config = LocalConfiguration.current
    return (config.screenWidthDp * percentage).dp
}

/**
 * Returns a Dp value as a percentage of screen height, 
 * clamped between min and max values. This prevents
 * elements from becoming too small on compact screens
 * or too large on expanded screens.
 */
@Composable
fun rememberClampedHeightPercentage(percentage: Float, minDp: Dp, maxDp: Dp): Dp {
    val config = LocalConfiguration.current
    val calculated = (config.screenHeightDp * percentage).dp
    return calculated.coerceIn(minDp, maxDp)
}

/**
 * Returns a Dp value as a percentage of screen width,
 * clamped between min and max values.
 */
@Composable
fun rememberClampedWidthPercentage(percentage: Float, minDp: Dp, maxDp: Dp): Dp {
    val config = LocalConfiguration.current
    val calculated = (config.screenWidthDp * percentage).dp
    return calculated.coerceIn(minDp, maxDp)
}

/**
 * Three-tier adaptive sizing that returns fraction of screen height,
 * with different fractions for each screen category.
 * Useful when elements need proportional scaling per category.
 */
@Composable
fun adaptiveHeightFraction(expandedFrac: Float, mediumFrac: Float, compactFrac: Float): Dp {
    val config = LocalConfiguration.current
    val fraction = when {
        config.screenHeightDp < 670 -> compactFrac
        config.screenHeightDp <= 780 -> mediumFrac
        else -> expandedFrac
    }
    return (config.screenHeightDp * fraction).dp
}
