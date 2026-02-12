package me.avinas.tempo.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.R
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.widget.utils.WidgetHub
import java.io.File
import java.io.FileOutputStream
import androidx.datastore.preferences.core.stringPreferencesKey

@HiltWorker
class WidgetWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val statsRepository: StatsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "widget_worker"
        private const val NOTIFICATION_ID = 3008
    }

    override suspend fun doWork(): Result {
        return try {
            // =================================================================================
            // 1. Fetch Core Data
            // =================================================================================
            val weeklyOverview = statsRepository.getListeningOverview(TimeRange.THIS_WEEK)
            val dailyStats = statsRepository.getDailyListening(TimeRange.THIS_WEEK, limit = 7)
            
            // Calculate Growth (vs Last Week)
            val lastWeekOverview = statsRepository.getPeriodComparison(TimeRange.THIS_WEEK)
            val growthPercent = if (lastWeekOverview.previousPeriodTimeMs > 0) {
                ((weeklyOverview.totalListeningTimeMs - lastWeekOverview.previousPeriodTimeMs).toFloat() / lastWeekOverview.previousPeriodTimeMs) * 100
            } else 100f
            val growthString = "${if (growthPercent > 0) "+" else ""}${growthPercent.toInt()}%"

            // Format Time
            val totalHours = weeklyOverview.totalListeningTimeMs / 1000.0 / 60.0 / 60.0
            val hoursString = String.format("%.1f", totalHours)

            // Chart Data (daily listening times for bar chart)
            val chartData = dailyStats.reversed().map { it.totalTimeMs.toFloat() }.joinToString(",")
            
            // Insight Calculation
            val mostActiveDay = statsRepository.getMostActiveDay(TimeRange.THIS_WEEK)
            val mostActiveHour = statsRepository.getMostActiveHour(TimeRange.THIS_WEEK)
            
            val insightString = when {
                mostActiveDay != null && mostActiveDay.totalTimeMs > (weeklyOverview.totalListeningTimeMs / 7 * 1.5) -> {
                     "Most active on ${mostActiveDay.dayName}s"
                }
                mostActiveHour != null -> "Peaking at ${if(mostActiveHour.hour > 12) mostActiveHour.hour - 12 else mostActiveHour.hour} ${if(mostActiveHour.hour >= 12) "PM" else "AM"}"
                else -> "${weeklyOverview.totalPlayCount} tracks played"
            }

            // =================================================================================
            // 1b. Fetch Today's Data (for Dashboard)
            // =================================================================================
            val todayOverview = statsRepository.getListeningOverview(TimeRange.TODAY)
            val hourlyStats = statsRepository.getHourlyDistribution(TimeRange.TODAY)
            
            // Calculate Growth (vs Yesterday)
            val yesterdayComparison = statsRepository.getPeriodComparison(TimeRange.TODAY)
            val todayGrowthPercent = if (yesterdayComparison.previousPeriodTimeMs > 0) {
                 ((todayOverview.totalListeningTimeMs - yesterdayComparison.previousPeriodTimeMs).toFloat() / yesterdayComparison.previousPeriodTimeMs) * 100
            } else 0f
            val todayGrowthString = "${if (todayGrowthPercent > 0) "+" else ""}${todayGrowthPercent.toInt()}%"
            
            // Format Time
            val todayHours = todayOverview.totalListeningTimeMs / 1000.0 / 60.0 / 60.0
            val todayHoursString = String.format("%.1f", todayHours)
            
            // Chart Data (Hourly)
            // Ensure we have 24 points, filling gaps with 0
            val hourlyMap = hourlyStats.associate { it.hour to it.totalTimeMs.toFloat() }
            // Get current hour to limit the chart if desired, or show full 24h
            val currentHour = java.time.LocalTime.now().hour
            val todayChartData = (0..23).map { hour -> 
                if (hour <= currentHour) hourlyMap[hour] ?: 0f else 0f 
            }.joinToString(",")
            
            // =================================================================================
            // 2. Top Artist Data (for Artist Spotlight + Dashboard)
            // =================================================================================
            val topArtists = statsRepository.getTopArtists(TimeRange.THIS_WEEK, pageSize = 1)
            val topArtist = topArtists.items.firstOrNull()
            var artistImagePath: String? = null
            if (topArtist?.imageUrl != null) {
                 artistImagePath = downloadImage(context, topArtist.imageUrl, "widget_artist_bg.jpg")
            }
            val artistHours = String.format("%.0f", (topArtist?.totalTimeMs ?: 0) / 3_600_000.0)

            // =================================================================================
            // 3. Top Genres with Percentages (for Heatmap treemap)
            // =================================================================================
            val topGenres = statsRepository.getTopGenres(TimeRange.THIS_WEEK, limit = 5)
            val totalGenrePlays = topGenres.sumOf { it.playCount }.coerceAtLeast(1)
            val genreJson = if (topGenres.isNotEmpty()) {
                topGenres.joinToString(",", "[", "]") { 
                    val percent = (it.playCount * 100) / totalGenrePlays
                    """{"genre":"${it.genre}","count":${it.playCount},"percent":$percent}"""
                }
            } else "[]"

            // =================================================================================
            // 4. Milestone Data (Top Track with real insights)
            // =================================================================================
            val topTracks = statsRepository.getTopTracks(TimeRange.THIS_WEEK, pageSize = 1)
            val topTrack = topTracks.items.firstOrNull()
            
            var milestoneTrackName = "No Data"
            var milestoneArtistName = ""
            var milestonePlayCount = "0"
            var milestoneBadge = "#1 Most Played This Week"
            var milestoneImage: String? = null

            if (topTrack != null) {
                milestoneTrackName = topTrack.title
                milestoneArtistName = topTrack.artist
                milestonePlayCount = topTrack.playCount.toString()
                
                // Compute milestone badge
                milestoneBadge = when {
                    topTrack.playCount >= 1000 -> "🏆 ${topTrack.playCount}th Stream!"
                    topTrack.playCount >= 500 -> "🏆 ${topTrack.playCount}th Stream!"
                    topTrack.playCount >= 100 -> "🏆 ${topTrack.playCount}th Stream!"
                    topTrack.playCount >= 50 -> "🔥 ${topTrack.playCount}th Stream!"
                    else -> "#1 Most Played This Week"
                }
                
                if (topTrack.albumArtUrl != null) {
                    milestoneImage = downloadImage(context, topTrack.albumArtUrl, "widget_milestone_art.jpg")
                }
            }
            
            // =================================================================================
            // 5. Discovery Data (Most-explored artist with real stats)
            // =================================================================================
            val artistLoyalties = statsRepository.getArtistLoyalty(TimeRange.THIS_WEEK, minPlays = 3, limit = 5)
            val mostExploredArtist = artistLoyalties.maxByOrNull { it.uniqueTracksPlayed }
            
            var discoveryArtistName = topArtist?.artist ?: "artists"
            var discoveryTracksCount = weeklyOverview.uniqueTracksCount.toString()
            var discoveryHours = hoursString
            var discoveryPercentile = "Top 10%"
            var discoveryText = "You've discovered $discoveryTracksCount tracks this week."
            
            if (mostExploredArtist != null) {
                discoveryArtistName = mostExploredArtist.artist
                discoveryTracksCount = mostExploredArtist.uniqueTracksPlayed.toString()
                val artistTimeHours = String.format("%.1f", mostExploredArtist.totalPlays * 3.5 / 60.0) // approx hours
                discoveryHours = "${artistTimeHours}h"
                
                // Get percentile
                try {
                    val percentile = statsRepository.getArtistRankPercentile(
                        mostExploredArtist.totalPlays, TimeRange.THIS_WEEK
                    )
                    discoveryPercentile = "Top ${percentile.toInt().coerceAtLeast(1)}%"
                } catch (_: Exception) {
                    discoveryPercentile = "Top Fan"
                }
                
                discoveryText = if (mostExploredArtist.uniqueTracksPlayed >= 10) {
                    "You've explored ${mostExploredArtist.artist}'s entire catalog."
                } else {
                    "You've discovered ${mostExploredArtist.uniqueTracksPlayed} of ${mostExploredArtist.artist}'s tracks."
                }
            }

            // =================================================================================
            // 6. Mix Widget Data
            // =================================================================================
            // Generate varying bar heights for audio visualization from daily stats
            val mixChartData = if (dailyStats.isNotEmpty()) {
                dailyStats.reversed().map { it.totalTimeMs.toFloat() }.joinToString(",")
            } else {
                // Fallback to some visual variety
                "0.3,0.6,1.0,0.7,0.4,0.2,0.5,0.9,0.5,0.3,0.2,0.4"
            }

            // =================================================================================
            // 7. Update All Widget States
            // =================================================================================
            val manager = GlanceAppWidgetManager(context)
            
            val allWidgetClasses: List<Class<out GlanceAppWidget>> = listOf(
                TempoAppWidget::class.java,
                TempoArtistWidget::class.java,
                TempoDashboardWidget::class.java,
                TempoDiscoveryWidget::class.java,
                TempoHeatmapWidget::class.java,
                TempoMilestoneWidget::class.java,
                TempoMixWidget::class.java,
            )
            
            for (widgetClass in allWidgetClasses) {
                val glanceIds = manager.getGlanceIds(widgetClass)
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        fun setStr(key: String, value: String) {
                            prefs[stringPreferencesKey(key)] = value
                        }
                        
                        // Core weekly stats
                        setStr(WidgetHub.PREF_WEEKLY_HOURS, hoursString)
                        setStr(WidgetHub.PREF_WEEKLY_GROWTH, growthString)
                        setStr(WidgetHub.PREF_WEEKLY_INSIGHT, insightString)
                        setStr(WidgetHub.PREF_WEEKLY_CHART_DATA, chartData)

                        // Today's stats
                        setStr(WidgetHub.PREF_TODAY_HOURS, todayHoursString)
                        setStr(WidgetHub.PREF_TODAY_GROWTH, todayGrowthString)
                        setStr(WidgetHub.PREF_TODAY_CHART_DATA, todayChartData)
                        
                        // Artist spotlight
                        setStr(WidgetHub.PREF_TOP_ARTIST_NAME, topArtist?.artist ?: "No Data")
                        setStr(WidgetHub.PREF_TOP_ARTIST_HOURS, "${artistHours}h")
                        setStr(WidgetHub.PREF_TOP_ARTIST_TRACKS, (topArtist?.uniqueTracks ?: 0).toString())
                        if (artistImagePath != null) setStr(WidgetHub.PREF_TOP_ARTIST_IMAGE, artistImagePath)
                        
                        // Genres
                        setStr(WidgetHub.PREF_TOP_GENRES_JSON, genreJson)
                        
                        // Milestone (real track data)
                        setStr(WidgetHub.PREF_MILESTONE_TITLE, milestoneTrackName)
                        setStr(WidgetHub.PREF_MILESTONE_SUBTITLE, milestoneArtistName)
                        setStr(WidgetHub.PREF_MILESTONE_TRACK_NAME, milestoneTrackName)
                        setStr(WidgetHub.PREF_MILESTONE_ARTIST_NAME, milestoneArtistName)
                        setStr(WidgetHub.PREF_MILESTONE_PLAY_COUNT, milestonePlayCount)
                        setStr(WidgetHub.PREF_MILESTONE_BADGE, milestoneBadge)
                        if (milestoneImage != null) setStr(WidgetHub.PREF_MILESTONE_IMAGE, milestoneImage)
                        
                        // Discovery (real insights)
                        setStr(WidgetHub.PREF_DISCOVERY_TEXT, discoveryText)
                        setStr(WidgetHub.PREF_DISCOVERY_ARTIST_NAME, discoveryArtistName)
                        setStr(WidgetHub.PREF_DISCOVERY_TRACKS_COUNT, discoveryTracksCount)
                        setStr(WidgetHub.PREF_DISCOVERY_HOURS, discoveryHours)
                        setStr(WidgetHub.PREF_DISCOVERY_PERCENTILE, discoveryPercentile)

                        // Mix
                        setStr(WidgetHub.PREF_MIX_TITLE, "Weekly Mix")
                        setStr(WidgetHub.PREF_MIX_ARTIST, "Made for you")
                        setStr(WidgetHub.PREF_MIX_CHART_DATA, mixChartData)
                    }
                }
            }
            
            // Refresh all widget types
            TempoAppWidget().updateAll(context)
            TempoArtistWidget().updateAll(context)
            TempoDashboardWidget().updateAll(context)
            TempoDiscoveryWidget().updateAll(context)
            TempoHeatmapWidget().updateAll(context)
            TempoMilestoneWidget().updateAll(context)
            TempoMixWidget().updateAll(context)
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private suspend fun downloadImage(context: Context, url: String, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000) {
            return file.absolutePath
        }
        
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        val result = (loader.execute(request) as? SuccessResult)?.image
        val bitmap = result?.toBitmap()

        return bitmap?.let {
            FileOutputStream(file).use { out ->
                it.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Widget Updates",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Tempo Widget")
            .setContentText("Updating stats...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= 34) {
             return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            return ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
