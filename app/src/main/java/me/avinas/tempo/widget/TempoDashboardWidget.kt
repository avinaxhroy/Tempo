package me.avinas.tempo.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
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
                val artistImagePath = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]

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

                Box(
                    modifier = GlanceModifier
                        .appWidgetBackground()
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                     Image(
                        provider = ImageProvider(generatedArt),
                        contentDescription = "Dashboard Pulse",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }

}

class TempoDashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoDashboardWidget()

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
