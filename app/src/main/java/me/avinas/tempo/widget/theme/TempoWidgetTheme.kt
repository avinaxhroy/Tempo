package me.avinas.tempo.widget.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

object TempoWidgetColors {
    val Primary = Color(0xFF80DAB0) // Soft Teal from mockup
    val OnPrimary = Color(0xFF003825)
    val PrimaryContainer = Color(0xFF005138)
    val OnPrimaryContainer = Color(0xFF9CF8CE)
    
    val Secondary = Color(0xFFCCC2DC)
    val OnSecondary = Color(0xFF332D41)
    val SecondaryContainer = Color(0xFF4A4458)
    val OnSecondaryContainer = Color(0xFFE8DEF8)
    
    val Tertiary = Color(0xFFEFB8C8)
    val OnTertiary = Color(0xFF492532)
    val TertiaryContainer = Color(0xFF633B48)
    val OnTertiaryContainer = Color(0xFFFFD8E4)

    val Background = Color(0xFF1C1B1F) // Dark detailed background
    val OnBackground = Color(0xFFE6E1E5)
    val Surface = Color(0xFF1C1B1F)
    val OnSurface = Color(0xFFE6E1E5)
    val SurfaceVariant = Color(0xFF49454F)
    val OnSurfaceVariant = Color(0xFFCAC4D0)
    val Outline = Color(0xFF938F99)

    // Specific Widget Gradients/Accents
    val WidgetBackgroundGradientStart = Color(0xFF202826)
    val WidgetBackgroundGradientEnd = Color(0xFF101413)
    
    val GlassSurface = Color(0xFF2D2A37).copy(alpha = 0.85f)
}

private val DarkColorScheme = darkColorScheme(
    primary = TempoWidgetColors.Primary,
    onPrimary = TempoWidgetColors.OnPrimary,
    primaryContainer = TempoWidgetColors.PrimaryContainer,
    onPrimaryContainer = TempoWidgetColors.OnPrimaryContainer,
    secondary = TempoWidgetColors.Secondary,
    onSecondary = TempoWidgetColors.OnSecondary,
    secondaryContainer = TempoWidgetColors.SecondaryContainer,
    onSecondaryContainer = TempoWidgetColors.OnSecondaryContainer,
    tertiary = TempoWidgetColors.Tertiary,
    onTertiary = TempoWidgetColors.OnTertiary,
    tertiaryContainer = TempoWidgetColors.TertiaryContainer,
    onTertiaryContainer = TempoWidgetColors.OnTertiaryContainer,
    background = TempoWidgetColors.Background,
    onBackground = TempoWidgetColors.OnBackground,
    surface = TempoWidgetColors.Surface,
    onSurface = TempoWidgetColors.OnSurface,
    surfaceVariant = TempoWidgetColors.SurfaceVariant,
    onSurfaceVariant = TempoWidgetColors.OnSurfaceVariant,
    outline = TempoWidgetColors.Outline
)

@Composable
fun TempoWidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(
        colors = ColorProviders(
            light = DarkColorScheme, // Force dark theme for consistency with "Vibe"
            dark = DarkColorScheme
        ),
        content = content
    )
}
