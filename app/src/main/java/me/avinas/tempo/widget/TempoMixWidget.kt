package me.avinas.tempo.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import me.avinas.tempo.widget.theme.TempoWidgetTheme
import me.avinas.tempo.widget.utils.TempoGlanceReceiver
import me.avinas.tempo.widget.utils.WidgetCard
import me.avinas.tempo.widget.utils.WidgetHub
import me.avinas.tempo.widget.utils.getPixelSize

class TempoMixWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val mixImage = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]?.takeIf { it.isNotEmpty() }
                
                val mixTitle = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_TITLE)] ?: "Weekly Mix"
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_ARTIST)] ?: "Made for you"
                val chartDataStr = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_CHART_DATA)] ?: ""
                val chartData = if (chartDataStr.isNotEmpty()) {
                    chartDataStr.split(",").mapNotNull { it.toFloatOrNull() }
                } else emptyList()
                
                // Get widget size
                // Device density, not the old hardcoded 3x (see getPixelSize).
                val pixelSize = getPixelSize(context, LocalSize.current)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateMixPortal(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    mixTitle,
                    artistName,
                    mixImage,
                    chartData
                )

                WidgetCard(
                    bitmap = generatedArt,
                    description = "Mix Portal",
                )
            }
        }
    }

}

class TempoMixWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoMixWidget()
}
