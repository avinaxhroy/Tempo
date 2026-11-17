package me.avinas.tempo.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
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
import me.avinas.tempo.widget.utils.WidgetHub

/**
 * The 30-minute system tick behind a placed rhythm card: one hourly query, one pref, one render.
 *
 * The rhythm card is the only widget left on a short tick (its current-hour bar has to advance as
 * the day does). Routing that tick through [WidgetWorker] rebuilt all nine widgets and ran the
 * full aggregate set forty-eight times a day for a single bar.
 */
@HiltWorker
class RhythmRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val statsRepository: StatsRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            try {
                val hourlyStats = statsRepository.getHourlyDistribution(TimeRange.TODAY)
                val hourlyPlays =
                    (0..23).joinToString(",") { hour ->
                        (hourlyStats.find { it.hour == hour }?.playCount ?: 0).toString()
                    }

                // Same key WidgetWorker writes; the rhythm card reads nothing else.
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceIds = manager.getGlanceIds(TempoListeningRhythmWidget::class.java)
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(applicationContext, glanceId) { prefs ->
                        prefs[stringPreferencesKey(WidgetHub.PREF_TODAY_HOURLY_PLAYS)] = hourlyPlays
                    }
                }
                if (glanceIds.isNotEmpty()) TempoListeningRhythmWidget().updateAll(applicationContext)

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Rhythm refresh failed", e)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

        companion object {
            private const val TAG = "RhythmRefreshWorker"
        }
    }
