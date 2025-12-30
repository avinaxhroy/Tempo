package me.avinas.tempo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TempoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoAppWidget()

    private val periodicWorkName = "TempoWidgetPeriodicWork"

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Trigger immediate update when widgets are updated (e.g. resized or added)
        val workRequest = OneTimeWorkRequest.Builder(WidgetWorker::class.java).build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Schedule periodic update every 1 hour
        val periodicWork = PeriodicWorkRequest.Builder(
            WidgetWorker::class.java,
            1, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName)
    }
}
