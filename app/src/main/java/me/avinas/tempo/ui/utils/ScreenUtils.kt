package me.avinas.tempo.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

@Composable
fun adaptiveSize(normal: Dp, small: Dp): Dp {
    return if (isSmallScreen()) small else normal
}

@Composable
fun adaptiveSize(normal: Dp, small: Dp, verySmall: Dp): Dp {
    val config = LocalConfiguration.current
    return when {
         config.screenHeightDp < 600 -> verySmall
         config.screenHeightDp < 700 -> small
         else -> normal
    }
}

@Composable
fun adaptiveTextUnit(normal: androidx.compose.ui.unit.TextUnit, small: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit {
    return if (isSmallScreen()) small else normal
}

@Composable
fun <T> adaptiveValue(normal: T, small: T): T {
    return if (isSmallScreen()) small else normal
}
