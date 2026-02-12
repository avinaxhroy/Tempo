package me.avinas.tempo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import me.avinas.tempo.MainActivity
import me.avinas.tempo.widget.theme.TempoWidgetTheme
import me.avinas.tempo.widget.theme.TempoWidgetColors
import me.avinas.tempo.widget.utils.WidgetHub
import me.avinas.tempo.widget.utils.appWidgetBackground
import me.avinas.tempo.widget.utils.scheduleImmediateWidgetUpdate
import me.avinas.tempo.widget.utils.schedulePeriodicWidgetUpdate

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
                
                if (genreJson != "[]") {
                    genreJson
                        .removeSurrounding("[", "]")
                        .split("},")
                        .forEach { entry ->
                            try {
                                val cleaned = entry.removeSuffix("}").removePrefix("{")
                                val genre = cleaned
                                    .substringAfter("\"genre\":\"")
                                    .substringBefore("\"")
                                val count = cleaned
                                    .substringAfter("\"count\":")
                                    .substringBefore(",")
                                    .trim()
                                    .toIntOrNull() ?: 0
                                val percent = cleaned
                                    .substringAfter("\"percent\":")
                                    .trim()
                                    .removeSuffix("}")
                                    .toIntOrNull() ?: 0
                                topGenres.add(genre to count)
                                genrePercents.add(percent)
                            } catch (_: Exception) {}
                        }
                }
                
                // Get widget size
                val size = LocalSize.current
                val widthPx = size.width.value.toInt() * 3
                val heightPx = size.height.value.toInt() * 3
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateHeatmapSynth(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    topGenres.take(5),
                    genrePercents.take(5)
                )

                Box(
                    modifier = GlanceModifier
                        .appWidgetBackground()
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                     Image(
                        provider = ImageProvider(generatedArt),
                        contentDescription = "Heatmap Synth",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }

}

class TempoHeatmapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoHeatmapWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        scheduleImmediateWidgetUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicWidgetUpdate(context)
    }
}
