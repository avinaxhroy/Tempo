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

class TempoDashboardWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                // Fetch data from prefs
                // Fetch data from prefs
                val weeklyHours = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_HOURS)] ?: "--"
                val weeklyGrowth = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_GROWTH)] ?: "+0%"
                val weeklyInsight = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_INSIGHT)] ?: ""
                val chartDataStr = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_CHART_DATA)] ?: ""
                val topArtistName = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_NAME)] ?: "No Data"
                val artistImagePath = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]?.takeIf { it.isNotEmpty() }

                val chartData = if (chartDataStr.isNotEmpty()) {
                    chartDataStr.split(",").mapNotNull { it.toFloatOrNull() }
                } else emptyList()
                
                // Get widget size
                val size = LocalSize.current
                val pixelSize = me.avinas.tempo.widget.utils.getPixelSize(context, size)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateDashboardCard(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    weeklyHours,
                    weeklyGrowth,
                    weeklyInsight,
                    chartData,
                    topArtistName,
                    artistImagePath
                )

                WidgetCard(
                    bitmap = generatedArt,
                    description = "Dashboard Pulse",
                )
            }
        }
    }

}

class TempoDashboardWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoDashboardWidget()
}
