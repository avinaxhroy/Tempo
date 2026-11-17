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
import org.json.JSONArray
import org.json.JSONException

class TempoHeatmapWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val genreJson = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_GENRES_JSON)] ?: "[]"
                
                // Parse JSON: [{"genre":"Rock","count":15,"percent":42},...]
                val topGenres = mutableListOf<Pair<String, Int>>()
                val genrePercents = mutableListOf<Int>()

                try {
                    val entries = JSONArray(genreJson)
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        topGenres.add(entry.optString("genre") to entry.optInt("count"))
                        genrePercents.add(entry.optInt("percent"))
                    }
                } catch (_: JSONException) {
                    // Malformed state from an older install: render the card without a treemap.
                }
                
                // Get widget size
                // Device density, not the old hardcoded 3x (see getPixelSize).
                val pixelSize = getPixelSize(context, LocalSize.current)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateHeatmapSynth(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    topGenres.take(5),
                    genrePercents.take(5)
                )

                WidgetCard(
                    bitmap = generatedArt,
                    description = "Heatmap Synth",
                )
            }
        }
    }

}

class TempoHeatmapWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoHeatmapWidget()
}
