package me.avinas.tempo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange

@HiltWorker
class WidgetWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val statsRepository: StatsRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Fetch Data
            val todayOverview = statsRepository.getListeningOverview(TimeRange.TODAY)
            val streak = statsRepository.getListeningStreak()
            val topArtists = statsRepository.getTopArtists(TimeRange.THIS_WEEK, pageSize = 1)
            // Get last 7 days of listening data for the chart
            val dailyStats = statsRepository.getDailyListening(TimeRange.THIS_WEEK, limit = 7)
            
            // Format time string
            val totalMinutes = todayOverview.totalListeningTimeMs / 1000 / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            val timeString = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
            
            // Prepare Chart Data (Chronological order)
            // dailyStats is usually DESC (Today first), so we reverse it
            val chartData = dailyStats.reversed().map { it.totalTimeMs.toFloat() }.joinToString(",")

            // Update Widgets
            val manager = GlanceAppWidgetManager(context)
            val widgetIds = manager.getGlanceIds(TempoAppWidget::class.java)
            
            widgetIds.forEach { glanceId ->
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[TempoAppWidget.PREF_TOTAL_TIME_TODAY] = timeString
                    prefs[TempoAppWidget.PREF_PLAY_COUNT_TODAY] = todayOverview.totalPlayCount
                    prefs[TempoAppWidget.PREF_STREAK_DAYS] = streak.currentStreakDays
                    prefs[TempoAppWidget.PREF_TOP_ARTIST_NAME] = topArtists.items.firstOrNull()?.artist ?: "No Data"
                    prefs[TempoAppWidget.PREF_DAILY_CHART_DATA] = chartData
                }
            }
            TempoAppWidget().updateAll(context)
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
