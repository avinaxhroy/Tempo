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
import me.avinas.tempo.widget.utils.BitmapGenerator
import me.avinas.tempo.widget.utils.TempoGlanceReceiver
import me.avinas.tempo.widget.utils.WidgetCard
import me.avinas.tempo.widget.utils.WidgetHub
import me.avinas.tempo.widget.utils.getPixelSize

/**
 * "Total listening time" — the Today screen's masthead card, on the home screen.
 *
 * The card keeps its hero numeral and day-over-day comparison, but drops from three
 * stat columns to a list when the widget is landscape-wide, because three columns at
 * four cells wide clip "AVG SESSION" down to something unreadable.
 */
class TempoTodayTotalWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                fun value(key: String) = prefs[stringPreferencesKey(key)]

                val heroTime = value(WidgetHub.PREF_TODAY_HERO_TIME) ?: "0m"
                val playCount = value(WidgetHub.PREF_TODAY_PLAY_COUNT)?.toIntOrNull() ?: 0
                val trackCount = value(WidgetHub.PREF_TODAY_TRACK_COUNT)?.toIntOrNull() ?: 0
                val albumCount = value(WidgetHub.PREF_TODAY_ALBUM_COUNT)?.toIntOrNull() ?: 0
                val artistCount = value(WidgetHub.PREF_TODAY_ARTIST_COUNT)?.toIntOrNull() ?: 0
                val avgSession = value(WidgetHub.PREF_TODAY_AVG_SESSION) ?: "0m"
                val todayShort = value(WidgetHub.PREF_TODAY_SHORT_TIME) ?: "0m"
                val yesterdayShort = value(WidgetHub.PREF_YESTERDAY_SHORT_TIME) ?: "0m"
                val growthPercent = value(WidgetHub.PREF_TODAY_GROWTH_PERCENT)?.toFloatOrNull() ?: 0f
                val todayMs = value(WidgetHub.PREF_TODAY_TOTAL_MS)?.toLongOrNull() ?: 0L
                val yesterdayMs = value(WidgetHub.PREF_YESTERDAY_TOTAL_MS)?.toLongOrNull() ?: 0L

                val pixelSize = getPixelSize(context, LocalSize.current)
                val card = BitmapGenerator.generateTodayTotalCard(
                    context = context,
                    width = pixelSize.width,
                    height = pixelSize.height,
                    heroTime = heroTime,
                    playCount = playCount,
                    trackCount = trackCount,
                    albumCount = albumCount,
                    artistCount = artistCount,
                    avgSession = avgSession,
                    todayShort = todayShort,
                    yesterdayShort = yesterdayShort,
                    growthPercent = growthPercent,
                    todayMs = todayMs,
                    yesterdayMs = yesterdayMs,
                )

                WidgetCard(
                    bitmap = card,
                    description = "Total listening time today",
                )
            }
        }
    }
}

class TempoTodayTotalWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoTodayTotalWidget()
}
