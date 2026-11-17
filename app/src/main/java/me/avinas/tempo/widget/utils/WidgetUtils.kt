package me.avinas.tempo.widget.utils

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import me.avinas.tempo.MainActivity
import me.avinas.tempo.widget.RhythmRefreshWorker
import me.avinas.tempo.widget.TempoArtistWidgetReceiver
import me.avinas.tempo.widget.TempoDashboardWidgetReceiver
import me.avinas.tempo.widget.TempoDiscoveryWidgetReceiver
import me.avinas.tempo.widget.TempoHeatmapWidgetReceiver
import me.avinas.tempo.widget.TempoListeningRhythmWidgetReceiver
import me.avinas.tempo.widget.TempoMilestoneWidgetReceiver
import me.avinas.tempo.widget.TempoMixWidgetReceiver
import me.avinas.tempo.widget.TempoTodayTotalWidgetReceiver
import me.avinas.tempo.widget.TempoWidgetReceiver
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

    // Today cards, ported to the home screen. Kept as display strings rather than
    // re-deriving them in the widget: the renderers run inside Glance's composition
    // on the launcher's process budget, and the worker already has the raw models.
    const val PREF_TODAY_HERO_TIME = "today_hero_time" // "1h 3m"
    const val PREF_TODAY_PLAY_COUNT = "today_play_count"
    const val PREF_TODAY_TRACK_COUNT = "today_track_count"
    const val PREF_TODAY_ALBUM_COUNT = "today_album_count"
    const val PREF_TODAY_ARTIST_COUNT = "today_artist_count"
    const val PREF_TODAY_AVG_SESSION = "today_avg_session" // "1.1h"
    const val PREF_TODAY_SHORT_TIME = "today_short_time" // "1.1h"
    const val PREF_TODAY_TOTAL_MS = "today_total_ms"
    const val PREF_YESTERDAY_SHORT_TIME = "yesterday_short_time" // "8m"
    const val PREF_YESTERDAY_TOTAL_MS = "yesterday_total_ms"
    const val PREF_TODAY_GROWTH_PERCENT = "today_growth_percent" // raw, for the trend arrow
    const val PREF_TODAY_HOURLY_PLAYS = "today_hourly_plays" // 24 comma-separated ints
}

fun GlanceModifier.appWidgetBackground(): GlanceModifier {
    return this.fillMaxSize()
        .cornerRadius(WidgetHub.CornerRadius)
}

private const val PERIODIC_WORK_NAME = "TempoWidgetPeriodicWork"

/**
 * One-time refresh work, coalesced under a single name.
 *
 * Every widget receiver calls [scheduleImmediateWidgetUpdate] from `onUpdate`, and Android
 * delivers `APPWIDGET_UPDATE` to all placed widgets at once (boot, config change, launcher
 * refresh). With the old fire-and-forget `enqueue`, a user with several widgets placed started
 * that many independent workers, each of which runs the same stats queries and then calls
 * `updateAll` on *all seven* widget types. `KEEP` on a unique name collapses such a burst into
 * a single run that still renders every widget.
 */
private const val IMMEDIATE_WORK_NAME = "TempoWidgetImmediateWork"

/** Unique name of the rhythm card's one-query refresh; see [scheduleRhythmRefresh]. */
private const val RHYTHM_WORK_NAME = "TempoRhythmRefreshWork"

/**
 * Ceiling for a widget bitmap edge. The app-wide MAX_SAFE_BITMAP_EDGE_PX (4096) allows a 64 MB
 * ARGB card if a host ever reports a nonsense size; 2048 keeps the worst case at ~16 MB while
 * staying above a real 3x-density large widget (~2000 px), so normal cards are never downscaled.
 */
private const val MAX_WIDGET_BITMAP_EDGE_PX = 2048

/**
 * Every widget provider this app registers, used to answer "is any Tempo widget still placed?".
 *
 * These must be the *receiver* classes: [AppWidgetManager.getAppWidgetIds] takes a component
 * name, and a [androidx.glance.appwidget.GlanceAppWidget] subclass matches nothing, so listing
 * the composable classes made this list answer "none placed" no matter what was on the home
 * screen.
 *
 * Kept as one list because the periodic work is shared by all of them (a single
 * [PERIODIC_WORK_NAME]); a helper that only knew about its own provider could not tell whether
 * removing one widget should cancel the shared work.
 */
private val ALL_WIDGET_PROVIDERS =
    listOf(
        TempoWidgetReceiver::class.java,
        TempoArtistWidgetReceiver::class.java,
        TempoDashboardWidgetReceiver::class.java,
        TempoDiscoveryWidgetReceiver::class.java,
        TempoHeatmapWidgetReceiver::class.java,
        TempoMilestoneWidgetReceiver::class.java,
        TempoMixWidgetReceiver::class.java,
        TempoTodayTotalWidgetReceiver::class.java,
        TempoListeningRhythmWidgetReceiver::class.java,
    )

/**
 * Whether at least one Tempo widget is currently placed on a home screen.
 *
 * [AppWidgetManager.getAppWidgetIds] is a binder call that returns only *this* provider type's
 * placed ids, so a non-empty result for any provider means widget refresh work is still wanted.
 * Unbound widgets return an empty array rather than throwing, so this needs no try/catch.
 */
private fun hasAnyWidgetPlaced(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context) ?: return false
    return ALL_WIDGET_PROVIDERS.any { provider ->
        manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
    }
}

fun scheduleImmediateWidgetUpdate(context: Context) {
    val workRequest = OneTimeWorkRequest.Builder(WidgetWorker::class.java).build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        IMMEDIATE_WORK_NAME,
        // KEEP: a queued-but-not-yet-started refresh already covers this request, because the
        // worker rebuilds every widget from current data. REPLACE would restart the work and
        // delay it each time another widget's onUpdate arrived.
        ExistingWorkPolicy.KEEP,
        workRequest,
    )
}

/**
 * Refresh for the rhythm card's 30-minute tick.
 *
 * [RhythmRefreshWorker] runs one hourly query and rewrites one pref; [scheduleImmediateWidgetUpdate]
 * would rebuild all nine widgets and run the full aggregate set for a single bar.
 */
fun scheduleRhythmRefresh(context: Context) {
    val workRequest = OneTimeWorkRequest.Builder(RhythmRefreshWorker::class.java).build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        RHYTHM_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        workRequest,
    )
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

/**
 * Cancels the shared periodic refresh, but only once no Tempo widget is left.
 *
 * The periodic work has a single name shared by all seven providers, so cancelling it on any one
 * widget's removal would silently stop the 3-hourly refresh for widgets the user still has
 * placed. Only the last widget removed stops the work. Every receiver's `onDisabled` should call
 * this rather than `cancelUniqueWork` directly.
 */
fun cancelPeriodicWidgetUpdateIfUnused(context: Context) {
    if (hasAnyWidgetPlaced(context)) return
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
        // Clamped to the widget bitmap ceiling so a host reporting an absurd widget size cannot
        // drive BitmapGenerator's createBitmap(width, height) allocations past it.
        val widthPx = (size.width.value * density).toInt().coerceIn(100, MAX_WIDGET_BITMAP_EDGE_PX)
        val heightPx = (size.height.value * density).toInt().coerceIn(100, MAX_WIDGET_BITMAP_EDGE_PX)
        return android.util.Size(widthPx, heightPx)
    }

/**
 * The receiver behaviour every Tempo widget shares: a system update tick asks for an immediate
 * refresh, the first placement starts the shared periodic work, and removing the last widget
 * stops it. Providers declare only [glanceAppWidget] — nothing else belongs in a receiver body.
 */
abstract class TempoGlanceReceiver : GlanceAppWidgetReceiver() {
    /**
     * Work enqueued on each system update tick. The rhythm card overrides it: its tick only needs
     * its own hourly counts, not a full nine-widget rebuild.
     */
    protected open fun refreshOnUpdate(context: Context) = scheduleImmediateWidgetUpdate(context)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        refreshOnUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicWidgetUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Shared periodic work: this only stops it once the last Tempo widget is gone,
        // so removing one widget does not silently kill refresh for the others.
        cancelPeriodicWidgetUpdateIfUnused(context)
    }
}

/**
 * The card shell every card widget renders: full-bleed bitmap, rounded background, opens the app
 * on tap.
 *
 * One copy on purpose. Before it existed the eight card widgets each carried their own `Box` +
 * `Image` block, and three of them drifted to a hardcoded `3x` pixel scale instead of
 * [getPixelSize] — the exact sizing that was fixed after causing RemoteViews memory crashes.
 */
@Composable
fun WidgetCard(bitmap: Bitmap, description: String) {
    Box(
        modifier = GlanceModifier
            .appWidgetBackground()
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = description,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}
