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

class TempoArtistWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_NAME)] ?: "No Data"
                val artistHours = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_HOURS)] ?: "0h"
                val artistImageInfo = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]?.takeIf { it.isNotEmpty() }
                
                // Get widget size to generate pixel-perfect bitmap
                // Device density, not the old hardcoded 3x: at 2x density that rendered the
                // bitmap 1.5x over the slot, which is the RemoteViews memory ceiling
                // getPixelSize() exists to stay under.
                val pixelSize = getPixelSize(context, LocalSize.current)
                val widthPx = pixelSize.width
                val heightPx = pixelSize.height
                
                // Decode artist image (with size cap to prevent OOM)
                val artistBitmap = if (artistImageInfo != null) {
                    me.avinas.tempo.widget.utils.BitmapGenerator.decodeBitmapSafe(artistImageInfo, 512)
                } else null
                
                // Generate the ART
                // In a real app complexity, this should be cached/worker-generated to save UI thread, 
                // but since this is 'provideContent' it runs in a suspend function which is okay for on-demand generation 
                // IF it's not too heavy. 
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateArtistSpotlight(
                    context, 
                    widthPx.coerceAtLeast(100), // Safety min size
                    heightPx.coerceAtLeast(100), 
                    artistBitmap, 
                    artistName, 
                    artistHours
                )
                
                WidgetCard(
                    bitmap = generatedArt,
                    description = "Artist Spotlight for $artistName",
                )
            }
        }
    }
}

class TempoArtistWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoArtistWidget()
}
