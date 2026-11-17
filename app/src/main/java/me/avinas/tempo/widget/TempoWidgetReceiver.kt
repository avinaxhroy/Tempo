package me.avinas.tempo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import dagger.hilt.android.EntryPointAccessors
import me.avinas.tempo.data.analytics.FeatureFirstUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.di.AnalyticsEntryPoint
import me.avinas.tempo.widget.utils.TempoGlanceReceiver

class TempoWidgetReceiver : TempoGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoAppWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        reportWidgetPlaced(context)
    }

    /**
     * `onEnabled` fires the first time a widget is placed on a home screen — the only reliable
     * signal that a user actually adopted the widget, as opposed to merely opening the picker.
     * A broadcast receiver has no injection point, so the tracker is resolved through an entry
     * point, and a failure to do so must never break widget registration.
     */
    private fun reportWidgetPlaced(context: Context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, AnalyticsEntryPoint::class.java)
                .analyticsTracker()
                .track(FeatureFirstUsed(TempoFeature.WIDGET))
        }
    }
}
