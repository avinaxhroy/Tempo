package me.avinas.tempo.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.widget.theme.TempoWidgetColors
import me.avinas.tempo.widget.theme.TempoWidgetTheme
import me.avinas.tempo.widget.utils.WidgetHub

class TempoAppWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val size = LocalSize.current
            
            // The card colours are hardcoded dark; the bare GlanceTheme followed the system, so
            // in light mode its onSurface/onSurfaceVariant text was near-black on this dark
            // background and colors.surface put a near-white panel behind white chart text.
            TempoWidgetTheme {
                // Outer Box handles the main click
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(TempoWidgetColors.WidgetBackgroundGradientStart))
                        .cornerRadius(28.dp)
                        .clickable(actionStartActivity<MainActivity>())
                        .padding(getPadding(size))
                ) {
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
    }
    
    private fun getPadding(size: DpSize): androidx.compose.ui.unit.Dp {
        // Less padding for small widgets to maximize detailed view
         return if (size.width < MEDIUM_SIZE.width) 12.dp else 16.dp
    }

    @Composable
    private fun SmallWidgetContent(prefs: Preferences) {
        val totalTime = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_HOURS)]?.let { "${it}h" } ?: "--"
        
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon could go here
                 Image(
                    provider = ImageProvider(R.drawable.ic_launcher_foreground), // Fallback/Placeholder
                    contentDescription = null,
                    modifier = GlanceModifier.size(24.dp)
                 )
                 Spacer(modifier = GlanceModifier.width(8.dp))
                 Text(
                    text = "This Week",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = totalTime,
                    style = TextStyle(
                        color = ColorProvider(TempoWidgetColors.Primary),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
        }
    }

    @Composable
    private fun MediumWidgetContent(prefs: Preferences, availableHeight: androidx.compose.ui.unit.Dp) {
        val chartDataStr = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_CHART_DATA)] ?: ""
        val totalTime = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_HOURS)]?.let { "${it}h" } ?: "--"
        val chartData = if (chartDataStr.isNotEmpty()) {
            chartDataStr.split(",").map { it.toFloatOrNull() ?: 0f }
        } else emptyList()

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Side: Stats
            Column(
                modifier = GlanceModifier.defaultWeight().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(
                    text = "Weekly",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = totalTime, 
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                     text = "This Week",
                     style = TextStyle(
                         color = GlanceTheme.colors.primary,
                         fontSize = 12.sp
                     )
                )
            }
            
            // Right Side: Chart
            if (chartData.isNotEmpty()) {
                 Box(
                     modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(12.dp)
                        .padding(8.dp)
                 ) {
                     // availableHeight is the whole widget; the outer 16dp padding and this box's
                     // own 8dp padding sit inside it, so a bar sized to it overflowed the card.
                     BarChart(data = chartData, maxHeight = (availableHeight - 48.dp).coerceAtLeast(8.dp), modifier = GlanceModifier.fillMaxSize())
                 }
            }
        }
    }

    @Composable
    private fun LargeWidgetContent(prefs: Preferences, availableHeight: androidx.compose.ui.unit.Dp) {
        val totalTime = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_HOURS)]?.let { "${it}h" } ?: "--"
        val growth = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_GROWTH)] ?: "--"
        val topArtist = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_NAME)] ?: "No Data"
        val artistImagePath = prefs[stringPreferencesKey(WidgetHub.PREF_TOP_ARTIST_IMAGE)]?.takeIf { it.isNotEmpty() }
        
        val chartDataStr = prefs[stringPreferencesKey(WidgetHub.PREF_WEEKLY_CHART_DATA)] ?: ""
        val chartData = if (chartDataStr.isNotEmpty()) {
            chartDataStr.split(",").map { it.toFloatOrNull() ?: 0f }
        } else emptyList()

        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Column(modifier = GlanceModifier.defaultWeight()) {
                     Text("This Week's Listening", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                     Text(totalTime, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold))
                 }
                 
                 // Growth percentage
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Text(
                         text = growth,
                         style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                     )
                     Spacer(modifier = GlanceModifier.width(4.dp))
                     // Icon instead of an emoji: emoji rendering depends on the launcher font.
                     Image(
                         provider = ImageProvider(R.drawable.ic_widget_trending_up),
                         contentDescription = null,
                         modifier = GlanceModifier.size(18.dp),
                     )
                 }
            }
            
            Spacer(modifier = GlanceModifier.height(16.dp))
            
            // Artist Card (Hero section)
            Box(
                 modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(80.dp) // Fixed height for hero
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(12.dp),
                 contentAlignment = Alignment.CenterStart
            ) {
                // Background Image (if avail)
                 if (artistImagePath != null) {
                     val safeBitmap = me.avinas.tempo.widget.utils.BitmapGenerator.decodeBitmapSafe(artistImagePath, 512)
                     if (safeBitmap != null) {
                         Image(
                             provider = ImageProvider(safeBitmap),
                             contentDescription = null,
                             contentScale = androidx.glance.layout.ContentScale.Crop,
                             modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp)
                         )
                     }
                     // Dark overlay for text readability
                     Box(
                         modifier = GlanceModifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .cornerRadius(12.dp)
                     ) {}
                 }
                 
                 Row(
                     modifier = GlanceModifier.padding(12.dp).fillMaxWidth(),
                     verticalAlignment = Alignment.CenterVertically
                 ) {
                     Column {
                         Text(
                             "Top Artist",
                             style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.7f)), fontSize = 11.sp)
                         )
                         Text(
                             topArtist,
                             style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                             maxLines = 1
                         )
                     }
                 }
            }

            Spacer(modifier = GlanceModifier.defaultWeight())
            
            // Chart at bottom
            if (chartData.isNotEmpty()) {
                Text("This Week", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
                Spacer(modifier = GlanceModifier.height(4.dp))
                Box(modifier = GlanceModifier.fillMaxWidth().height(60.dp)) {
                     BarChart(data = chartData, maxHeight = 60.dp, modifier = GlanceModifier.fillMaxSize())
                }
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
                            .height(barHeight) // Use height with calculated Dp
                            .background(GlanceTheme.colors.primary)
                            .cornerRadius(4.dp)
                    ) {}
                }
                // Spacing between bars
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
        }
    }

    companion object {
        private val SMALL_SIZE = DpSize(100.dp, 40.dp) // Adjusted slightly
        private val MEDIUM_SIZE = DpSize(200.dp, 100.dp)
        private val LARGE_SIZE = DpSize(200.dp, 200.dp)
    }
}
