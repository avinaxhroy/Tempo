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
import androidx.glance.layout.fillMaxHeight
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
import me.avinas.tempo.R
import me.avinas.tempo.widget.theme.TempoWidgetTheme
import me.avinas.tempo.widget.theme.TempoWidgetColors
import me.avinas.tempo.widget.utils.WidgetHub
import me.avinas.tempo.widget.utils.appWidgetBackground
import me.avinas.tempo.widget.utils.scheduleImmediateWidgetUpdate
import me.avinas.tempo.widget.utils.schedulePeriodicWidgetUpdate

class TempoMixWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val mixImage = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]
                
                val mixTitle = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_TITLE)] ?: "Weekly Mix"
                val artistName = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_ARTIST)] ?: "Made for you"
                val chartDataStr = prefs[stringPreferencesKey(WidgetHub.PREF_MIX_CHART_DATA)] ?: ""
                val chartData = if (chartDataStr.isNotEmpty()) {
                    chartDataStr.split(",").mapNotNull { it.toFloatOrNull() }
                } else emptyList()
                
                // Get widget size
                val size = LocalSize.current
                val widthPx = size.width.value.toInt() * 3
                val heightPx = size.height.value.toInt() * 3
                
                val generatedArt = me.avinas.tempo.widget.utils.BitmapGenerator.generateMixPortal(
                    context,
                    widthPx.coerceAtLeast(100),
                    heightPx.coerceAtLeast(100),
                    mixTitle,
                    artistName,
                    mixImage,
                    chartData
                )

                Box(
                    modifier = GlanceModifier
                        .appWidgetBackground()
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                     Image(
                        provider = ImageProvider(generatedArt),
                        contentDescription = "Mix Portal",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }

}

class TempoMixWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoMixWidget()

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
