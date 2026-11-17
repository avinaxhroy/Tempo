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
import me.avinas.tempo.widget.utils.scheduleRhythmRefresh
import java.time.LocalTime

/**
 * "Listening rhythm" — the Today screen's 24-hour cadence card, on the home screen.
 *
 * The in-app chart shades bars by relative volume; here the peak and the current hour
 * are the two that carry meaning at a glance, so they keep their accents and the rest
 * fade into the accent ramp.
 */
class TempoListeningRhythmWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TempoWidgetTheme {
                val prefs = currentState<Preferences>()
                val hourlyPlays = prefs[stringPreferencesKey(WidgetHub.PREF_TODAY_HOURLY_PLAYS)]
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    .orEmpty()

                val pixelSize = getPixelSize(context, LocalSize.current)
                val card = BitmapGenerator.generateListeningRhythmCard(
                    context = context,
                    width = pixelSize.width,
                    height = pixelSize.height,
                    hourlyPlays = hourlyPlays,
                    // Read at draw time rather than from the worker: the current-hour bar
                    // has to move as the day does, and a 3-hour refresh would leave it
                    // highlighting the wrong hour most of the time.
                    currentHour = LocalTime.now().hour,
                )

                WidgetCard(
                    bitmap = card,
                    description = "Today's listening rhythm",
                )
            }
        }
    }
}

class TempoListeningRhythmWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoListeningRhythmWidget()

    /** This is the only widget still on a 30-minute tick; keep its refresh to one query. */
    override fun refreshOnUpdate(context: Context) = scheduleRhythmRefresh(context)
}
