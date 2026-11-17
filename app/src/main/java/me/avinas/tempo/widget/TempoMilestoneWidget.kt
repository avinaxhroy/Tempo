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

class TempoMilestoneWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val trackName = prefs[stringPreferencesKey(WidgetHub.PREF_MILESTONE_TRACK_NAME)] ?: "No Data"
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_MILESTONE_ARTIST_NAME)] ?: ""
                val badge = prefs[stringPreferencesKey(WidgetHub.PREF_MILESTONE_BADGE)] ?: "#1 Most Played This Week"
                val imagePath = prefs[stringPreferencesKey(WidgetHub.PREF_MILESTONE_IMAGE)]?.takeIf { it.isNotEmpty() }
                
                // Get widget size
                val size = LocalSize.current
                val pixelSize = me.avinas.tempo.widget.utils.getPixelSize(context, size)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateMilestoneCard(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    trackName,
                    artistName,
                    badge,
                    imagePath
                )

                WidgetCard(
                    bitmap = generatedArt,
                    description = "Milestone Artifact",
                )
            }
        }
    }
}

class TempoMilestoneWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoMilestoneWidget()
}
