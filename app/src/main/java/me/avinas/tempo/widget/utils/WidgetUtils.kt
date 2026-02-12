package me.avinas.tempo.widget.utils

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import me.avinas.tempo.widget.WidgetWorker
import java.util.concurrent.TimeUnit

object WidgetHub {
    // Shared constants
    val CornerRadius = 24.dp
    val InnerPadding = 16.dp
    val SmallPadding = 8.dp
    
    const val PREF_WEEKLY_HOURS = "weekly_hours"
    const val PREF_WEEKLY_GROWTH = "weekly_growth" // e.g. "+9%"
    const val PREF_WEEKLY_CHART_DATA = "weekly_chart_data"
    const val PREF_WEEKLY_INSIGHT = "weekly_insight" // e.g. "Most active on Fridays"
    
    // Today's Stats
    const val PREF_TODAY_HOURS = "today_hours"
    const val PREF_TODAY_GROWTH = "today_growth" // vs yesterday
    const val PREF_TODAY_CHART_DATA = "today_chart_data" // hourly
    
    const val PREF_TOP_ARTIST_NAME = "top_artist_name"
    const val PREF_TOP_ARTIST_IMAGE = "top_artist_image"
    const val PREF_TOP_ARTIST_HOURS = "top_artist_hours"
    
    const val PREF_TOP_GENRES_JSON = "top_genres_json" // List<Pair<String, Int>>
    
    const val PREF_MILESTONE_TITLE = "milestone_title" // e.g., "100th Stream"
    const val PREF_MILESTONE_SUBTITLE = "milestone_subtitle" // e.g., "Feeling You - Raftaar"
    const val PREF_MILESTONE_IMAGE = "milestone_image"
    const val PREF_MILESTONE_TRACK_NAME = "milestone_track_name"
    const val PREF_MILESTONE_ARTIST_NAME = "milestone_artist_name"
    const val PREF_MILESTONE_PLAY_COUNT = "milestone_play_count"
    const val PREF_MILESTONE_BADGE = "milestone_badge" // e.g. "#1 Most Played This Week"
    
    const val PREF_DISCOVERY_TEXT = "discovery_text" // "You've explored King's entire catalog."
    const val PREF_DISCOVERY_ARTIST_NAME = "discovery_artist_name"
    const val PREF_DISCOVERY_TRACKS_COUNT = "discovery_tracks_count"
    const val PREF_DISCOVERY_HOURS = "discovery_hours"
    const val PREF_DISCOVERY_PERCENTILE = "discovery_percentile"

    const val PREF_MIX_TITLE = "mix_title"
    const val PREF_MIX_ARTIST = "mix_artist"
    const val PREF_MIX_CHART_DATA = "mix_chart_data" // audio bar heights
    
    const val PREF_TOP_ARTIST_TRACKS = "top_artist_tracks" // unique tracks count
}

fun GlanceModifier.appWidgetBackground(): GlanceModifier {
    return this.fillMaxSize()
        .cornerRadius(WidgetHub.CornerRadius)
}

private const val PERIODIC_WORK_NAME = "TempoWidgetPeriodicWork"

fun scheduleImmediateWidgetUpdate(context: Context) {
    val workRequest = OneTimeWorkRequest.Builder(WidgetWorker::class.java).build()
    WorkManager.getInstance(context).enqueue(workRequest)
}

fun schedulePeriodicWidgetUpdate(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    val periodicWork = PeriodicWorkRequest.Builder(
        WidgetWorker::class.java,
        3, TimeUnit.HOURS
    )
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        periodicWork
    )
}

fun cancelPeriodicWidgetUpdate(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
}

    /**
     * Calculates the optimal pixel size for generating a widget bitmap.
     * 
     * We simply convert the DP size to Pixels using the device density.
     * Previously invalidly scaled by 2.5x which caused RemoteViews memory crashes (20MB+ bitmaps).
     * Now returns exact screen-density pixels.
     */
    fun getPixelSize(context: Context, size: androidx.compose.ui.unit.DpSize): android.util.Size {
        val density = context.resources.displayMetrics.density
        // Just convert DP to PX. No extra usage of "scaleFactor" needed as density already covers it.
        val widthPx = (size.width.value * density).toInt().coerceAtLeast(100)
        val heightPx = (size.height.value * density).toInt().coerceAtLeast(100)
        return android.util.Size(widthPx, heightPx)
    }
