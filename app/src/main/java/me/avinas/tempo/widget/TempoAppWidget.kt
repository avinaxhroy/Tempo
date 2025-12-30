package me.avinas.tempo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.cornerRadius

class TempoAppWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val size = LocalSize.current
            GlanceTheme {
                when {
                    size.height >= LARGE_SIZE.height -> {
                        LargeWidgetContent(prefs, size.height)
                    }
                    size.width >= MEDIUM_SIZE.width -> {
                        MediumWidgetContent(prefs, size.height)
                    }
                    else -> {
                        SmallWidgetContent(prefs)
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallWidgetContent(prefs: Preferences) {
        val totalTime = prefs[PREF_TOTAL_TIME_TODAY] ?: "--"
        
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Today",
                    style = TextStyle(color = GlanceTheme.colors.onSurface)
                )
                Text(
                    text = totalTime,
                    style = TextStyle(color = GlanceTheme.colors.primary)
                )
            }
        }
    }

    @Composable
    private fun MediumWidgetContent(prefs: Preferences, availableHeight: androidx.compose.ui.unit.Dp) {
        val chartDataStr = prefs[PREF_DAILY_CHART_DATA] ?: ""
        val chartData = if (chartDataStr.isNotEmpty()) {
            chartDataStr.split(",").map { it.toFloatOrNull() ?: 0f }
        } else emptyList()

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Text(
                    text = "Last 7 Days",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = androidx.glance.text.FontWeight.Medium
                    )
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                if (chartData.isNotEmpty()) {
                    // Approximate chart height: available - padding - text - spacer
                    // Simple heuristic: 60% of available height
                    val chartHeight = availableHeight * 0.6f
                    BarChart(data = chartData, maxHeight = chartHeight, modifier = GlanceModifier.defaultWeight().fillMaxWidth())
                } else {
                    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                         Text("No Data", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
        }
    }

    @Composable
    private fun LargeWidgetContent(prefs: Preferences, availableHeight: androidx.compose.ui.unit.Dp) {
        val totalTime = prefs[PREF_TOTAL_TIME_TODAY] ?: "--"
        val playCount = prefs[PREF_PLAY_COUNT_TODAY] ?: 0
        val streak = prefs[PREF_STREAK_DAYS] ?: 0
        val topArtist = prefs[PREF_TOP_ARTIST_NAME] ?: "No Data"
        
        val chartDataStr = prefs[PREF_DAILY_CHART_DATA] ?: ""
        val chartData = if (chartDataStr.isNotEmpty()) {
            chartDataStr.split(",").map { it.toFloatOrNull() ?: 0f }
        } else emptyList()

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // Header Stats
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                     Text("Today", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                     Text(totalTime, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 24.sp, fontWeight = androidx.glance.text.FontWeight.Bold))
                }
                Column(horizontalAlignment = Alignment.End) {
                     Text("Streak", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                     Text("$streak days", style = TextStyle(color = GlanceTheme.colors.secondary, fontWeight = androidx.glance.text.FontWeight.Bold))
                }
            }
            
            Spacer(modifier = GlanceModifier.height(16.dp))
            
            // Top Artist
            Text("Top Artist", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            Text(topArtist, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = androidx.glance.text.FontWeight.Medium))
            
            Spacer(modifier = GlanceModifier.height(16.dp))
            
            // Chart
            if (chartData.isNotEmpty()) {
                 // Use remaining height roughly
                 val chartHeight = availableHeight * 0.4f
                BarChart(data = chartData, maxHeight = chartHeight, modifier = GlanceModifier.defaultWeight().fillMaxWidth())
            }
        }
    }

    @Composable
    private fun BarChart(data: List<Float>, maxHeight: androidx.compose.ui.unit.Dp, modifier: GlanceModifier = GlanceModifier) {
        val max = data.maxOrNull() ?: 1f
        val safeMax = if (max == 0f) 1f else max
        
        Row(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { value ->
                // Calc height steps
                val heightPercent = (value / safeMax).coerceIn(0.1f, 1f)
                val barHeight = maxHeight * heightPercent
                
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .background(GlanceTheme.colors.primary)
                            .cornerRadius(4.dp)
                    ) {}
                }
            }
        }
    }

    companion object {
        private val SMALL_SIZE = DpSize(110.dp, 40.dp)
        private val MEDIUM_SIZE = DpSize(200.dp, 100.dp)
        private val LARGE_SIZE = DpSize(200.dp, 200.dp)

        val PREF_TOTAL_TIME_TODAY = stringPreferencesKey("total_time_today")
        val PREF_PLAY_COUNT_TODAY = intPreferencesKey("play_count_today")
        val PREF_STREAK_DAYS = intPreferencesKey("streak_days")
        val PREF_TOP_ARTIST_NAME = stringPreferencesKey("top_artist_name")
        val PREF_DAILY_CHART_DATA = stringPreferencesKey("daily_chart_data") // JSON list of floats
    }
}
