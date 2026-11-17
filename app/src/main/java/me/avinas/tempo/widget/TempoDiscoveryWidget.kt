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

class TempoDiscoveryWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val discoveryText = prefs[stringPreferencesKey(WidgetHub.PREF_DISCOVERY_TEXT)] 
                    ?: "Explore more artists to see stats."
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_DISCOVERY_ARTIST_NAME)] ?: ""
                val tracksCount = prefs[stringPreferencesKey(WidgetHub.PREF_DISCOVERY_TRACKS_COUNT)] ?: "--"
                val hoursListened = prefs[stringPreferencesKey(WidgetHub.PREF_DISCOVERY_HOURS)] ?: "--"
                val percentile = prefs[stringPreferencesKey(WidgetHub.PREF_DISCOVERY_PERCENTILE)] ?: "--"
                
                // Get widget size
                val size = LocalSize.current
                val pixelSize = me.avinas.tempo.widget.utils.getPixelSize(context, size)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                // Generate the ART
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateDiscoveryCard(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    discoveryText,
                    artistName,
                    tracksCount,
                    hoursListened,
                    percentile
                )
                
                WidgetCard(
                    bitmap = generatedArt,
                    description = "Discovery Constellation",
                )
            }
        }
    }
}

class TempoDiscoveryWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoDiscoveryWidget()
}
