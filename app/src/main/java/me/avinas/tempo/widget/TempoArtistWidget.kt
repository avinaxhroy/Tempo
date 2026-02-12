package me.avinas.tempo.widget

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
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

class TempoArtistWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_NAME)] ?: "No Data"
                val artistHours = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_HOURS)] ?: "0h"
                val artistImageInfo = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]
                
                // Get widget size to generate pixel-perfect bitmap
                val size = LocalSize.current
                // Convert Dp to Px (Approximate for now, or use a fixed high-res canvas like 800x800 and scale down)
                // Since we can't easily get density here without context helper, we'll aim for a standard high-res
                val widthPx = size.width.value.toInt() * 3 // 3x density assumption for crispness
                val heightPx = size.height.value.toInt() * 3
                
                // Decode artist image
                val artistBitmap = if (artistImageInfo != null) {
                    BitmapFactory.decodeFile(artistImageInfo)
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
                
                Box(
                    modifier = GlanceModifier
                        .appWidgetBackground()
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(generatedArt),
                        contentDescription = "Artist Spotlight for $artistName",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }
}

class TempoArtistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoArtistWidget()

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
