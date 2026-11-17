package me.avinas.tempo.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.widget.utils.WidgetHub
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@HiltWorker
class WidgetWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted workerParams: WorkerParameters,
        private val statsRepository: StatsRepository,
    ) : CoroutineWorker(context, workerParams) {
        companion object {
            private const val TAG = "WidgetWorker"
        }

        override suspend fun doWork(): Result =
            try {
                // 1. Fetch Core Data
                val weeklyOverview = statsRepository.getListeningOverview(TimeRange.THIS_WEEK)
                val dailyStats = statsRepository.getDailyListening(TimeRange.THIS_WEEK, limit = 7)

                // Calculate Growth (vs Last Week)
                val lastWeekOverview = statsRepository.getPeriodComparison(TimeRange.THIS_WEEK)
                val growthPercent =
                    if (lastWeekOverview.previousPeriodTimeMs > 0) {
                        (
                            (weeklyOverview.totalListeningTimeMs - lastWeekOverview.previousPeriodTimeMs).toFloat() /
                                lastWeekOverview.previousPeriodTimeMs
                        ) *
                            100
                    } else {
                        100f
                    }
                val growthString = "${if (growthPercent > 0) "+" else ""}${growthPercent.toInt()}%"

                // Format Time
                val totalHours = weeklyOverview.totalListeningTimeMs / 1000.0 / 60.0 / 60.0
                val hoursString = String.format(Locale.US, "%.1f", totalHours)

                // Chart Data (daily listening times for bar chart)
                val chartData = dailyStats.reversed().map { it.totalTimeMs.toFloat() }.joinToString(",")

                // Insight Calculation
                val mostActiveDay = statsRepository.getMostActiveDay(TimeRange.THIS_WEEK)
                val mostActiveHour = statsRepository.getMostActiveHour(TimeRange.THIS_WEEK)

                val insightString =
                    when {
                        mostActiveDay != null && mostActiveDay.totalTimeMs > (weeklyOverview.totalListeningTimeMs / 7 * 1.5) -> {
                            "Most active on ${mostActiveDay.dayName}s"
                        }

                        mostActiveHour != null -> {
                            "Peaking at ${if (mostActiveHour.hour > 12) mostActiveHour.hour - 12 else mostActiveHour.hour} ${if (mostActiveHour.hour >= 12) "PM" else "AM"}"
                        }

                        else -> {
                            "${weeklyOverview.totalPlayCount} tracks played"
                        }
                    }

                // 1b. Fetch Today's Data (for Dashboard)
                val todayOverview = statsRepository.getListeningOverview(TimeRange.TODAY)
                val hourlyStats = statsRepository.getHourlyDistribution(TimeRange.TODAY)

                // Calculate Growth (vs Yesterday)
                val yesterdayComparison = statsRepository.getPeriodComparison(TimeRange.TODAY)
                val todayGrowthPercent =
                    if (yesterdayComparison.previousPeriodTimeMs > 0) {
                        (
                            (todayOverview.totalListeningTimeMs - yesterdayComparison.previousPeriodTimeMs).toFloat() /
                                yesterdayComparison.previousPeriodTimeMs
                        ) *
                            100
                    } else {
                        0f
                    }
                val todayGrowthString = "${if (todayGrowthPercent > 0) "+" else ""}${todayGrowthPercent.toInt()}%"

                // Format Time
                val todayHours = todayOverview.totalListeningTimeMs / 1000.0 / 60.0 / 60.0
                val todayHoursString = String.format(Locale.US, "%.1f", todayHours)

                // Strings for the Today cards. Formatted here rather than in the renderer
                // because the widget composable runs on the launcher's update budget and
                // should not be branching over millisecond edge cases per draw.
                val todayHeroTime = formatHeroTime(todayOverview.totalListeningTimeMs)
                val todayShortTime = formatShortDuration(todayOverview.totalListeningTimeMs)
                val yesterdayShortTime = formatShortDuration(yesterdayComparison.previousPeriodTimeMs)
                val todayAvgSession = formatShortDuration(todayOverview.averageSessionDurationMs)
                val hourlyPlays = (0..23).joinToString(",") { hour ->
                    (hourlyStats.find { it.hour == hour }?.playCount ?: 0).toString()
                }

                // Chart Data (Hourly)
                // Zero-fill missing hours across the 24-hour chart window
                val hourlyMap = hourlyStats.associate { it.hour to it.totalTimeMs.toFloat() }
                // Get current hour to limit the chart if desired, or show full 24h
                val currentHour =
                    java.time.LocalTime
                        .now()
                        .hour
                val todayChartData =
                    (0..23)
                        .map { hour ->
                            if (hour <= currentHour) hourlyMap[hour] ?: 0f else 0f
                        }.joinToString(",")

                // 2. Top Artist Data (for Artist Spotlight + Dashboard)
                val topArtists = statsRepository.getTopArtists(TimeRange.THIS_WEEK, pageSize = 1)
                val topArtist = topArtists.items.firstOrNull()
                var artistImagePath: String? = null
                if (topArtist?.imageUrl != null) {
                    artistImagePath = downloadImage(context, topArtist.imageUrl, "widget_artist_bg.jpg")
                }
                val artistHours = String.format("%.0f", (topArtist?.totalTimeMs ?: 0) / 3_600_000.0)

                // 3. Top Genres with Percentages (for Heatmap treemap)
                val topGenres = statsRepository.getTopGenres(TimeRange.THIS_WEEK, limit = 5)
                val totalGenrePlays = topGenres.sumOf { it.playCount }.coerceAtLeast(1)
                // org.json escapes genre names; string interpolation did not, so a genre with a
                // quote or backslash produced JSON the heatmap could not parse.
                val genreJson =
                    JSONArray()
                        .apply {
                            topGenres.forEach {
                                put(
                                    JSONObject().apply {
                                        put("genre", it.genre)
                                        put("count", it.playCount)
                                        put("percent", (it.playCount * 100) / totalGenrePlays)
                                    },
                                )
                            }
                        }.toString()

                // 4. Milestone Data (Top Track with real insights)
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
                    // The badge glyph is drawn as a real icon by BitmapGenerator; the two
                    // "Stream!" tiers used to differ only by emoji, so they are one tier now.
                    milestoneBadge =
                        when {
                            topTrack.playCount >= 50 -> "${ordinal(topTrack.playCount)} Stream!"
                            else -> "#1 Most Played This Week"
                        }

                    if (topTrack.albumArtUrl != null) {
                        milestoneImage = downloadImage(context, topTrack.albumArtUrl, "widget_milestone_art.jpg")
                    }
                }

                // 5. Discovery Data (Most-explored artist with real stats)
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
                    val artistTimeHours = String.format(Locale.US, "%.1f", mostExploredArtist.totalPlays * 3.5 / 60.0) // approx hours
                    discoveryHours = "${artistTimeHours}h"

                    // Get percentile
                    try {
                        val percentile =
                            statsRepository.getArtistRankPercentile(
                                mostExploredArtist.totalPlays,
                                TimeRange.THIS_WEEK,
                            )
                        discoveryPercentile = "Top ${percentile.toInt().coerceAtLeast(1)}%"
                    } catch (_: Exception) {
                        discoveryPercentile = "Top Fan"
                    }

                    discoveryText =
                        if (mostExploredArtist.uniqueTracksPlayed >= 10) {
                            "You've explored ${mostExploredArtist.artist}'s entire catalog."
                        } else {
                            "You've discovered ${mostExploredArtist.uniqueTracksPlayed} of ${mostExploredArtist.artist}'s tracks."
                        }
                }

                // 6. Mix Widget Data
                // Generate varying bar heights for audio visualization from daily stats
                val mixChartData =
                    if (dailyStats.isNotEmpty()) {
                        dailyStats.reversed().map { it.totalTimeMs.toFloat() }.joinToString(",")
                    } else {
                        // Fallback to some visual variety
                        "0.3,0.6,1.0,0.7,0.4,0.2,0.5,0.9,0.5,0.3,0.2,0.4"
                    }

                // 7. Update All Widget States
                val manager = GlanceAppWidgetManager(context)

                val allWidgetClasses: List<Class<out GlanceAppWidget>> =
                    listOf(
                        TempoAppWidget::class.java,
                        TempoArtistWidget::class.java,
                        TempoDashboardWidget::class.java,
                        TempoDiscoveryWidget::class.java,
                        TempoHeatmapWidget::class.java,
                        TempoMilestoneWidget::class.java,
                        TempoMixWidget::class.java,
                        TempoTodayTotalWidget::class.java,
                        TempoListeningRhythmWidget::class.java,
                    )

                for (widgetClass in allWidgetClasses) {
                    val glanceIds = manager.getGlanceIds(widgetClass)
                    glanceIds.forEach { glanceId ->
                        updateAppWidgetState(context, glanceId) { prefs ->
                            fun setStr(
                                key: String,
                                value: String,
                            ) {
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

                            // Today cards (Total listening time + Listening rhythm)
                            setStr(WidgetHub.PREF_TODAY_HERO_TIME, todayHeroTime)
                            setStr(WidgetHub.PREF_TODAY_PLAY_COUNT, todayOverview.totalPlayCount.toString())
                            setStr(WidgetHub.PREF_TODAY_TRACK_COUNT, todayOverview.uniqueTracksCount.toString())
                            setStr(WidgetHub.PREF_TODAY_ALBUM_COUNT, todayOverview.uniqueAlbumsCount.toString())
                            setStr(WidgetHub.PREF_TODAY_ARTIST_COUNT, todayOverview.uniqueArtistsCount.toString())
                            setStr(WidgetHub.PREF_TODAY_AVG_SESSION, todayAvgSession)
                            setStr(WidgetHub.PREF_TODAY_SHORT_TIME, todayShortTime)
                            setStr(WidgetHub.PREF_TODAY_TOTAL_MS, todayOverview.totalListeningTimeMs.toString())
                            setStr(WidgetHub.PREF_YESTERDAY_SHORT_TIME, yesterdayShortTime)
                            setStr(WidgetHub.PREF_YESTERDAY_TOTAL_MS, yesterdayComparison.previousPeriodTimeMs.toString())
                            setStr(WidgetHub.PREF_TODAY_GROWTH_PERCENT, todayGrowthPercent.toString())
                            setStr(WidgetHub.PREF_TODAY_HOURLY_PLAYS, hourlyPlays)

                            // Artist spotlight
                            setStr(WidgetHub.PREF_TOP_ARTIST_NAME, topArtist?.artist ?: "No Data")
                            setStr(WidgetHub.PREF_TOP_ARTIST_HOURS, "${artistHours}h")
                            setStr(WidgetHub.PREF_TOP_ARTIST_TRACKS, (topArtist?.uniqueTracks ?: 0).toString())
                            // Always written: an absent path clears the previous artwork, otherwise
                            // the card keeps showing a stale artist image forever.
                            setStr(WidgetHub.PREF_TOP_ARTIST_IMAGE, artistImagePath ?: "")

                            // Genres
                            setStr(WidgetHub.PREF_TOP_GENRES_JSON, genreJson)

                            // Milestone (real track data)
                            setStr(WidgetHub.PREF_MILESTONE_TITLE, milestoneTrackName)
                            setStr(WidgetHub.PREF_MILESTONE_SUBTITLE, milestoneArtistName)
                            setStr(WidgetHub.PREF_MILESTONE_TRACK_NAME, milestoneTrackName)
                            setStr(WidgetHub.PREF_MILESTONE_ARTIST_NAME, milestoneArtistName)
                            setStr(WidgetHub.PREF_MILESTONE_PLAY_COUNT, milestonePlayCount)
                            setStr(WidgetHub.PREF_MILESTONE_BADGE, milestoneBadge)
                            setStr(WidgetHub.PREF_MILESTONE_IMAGE, milestoneImage ?: "")

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

                // Refresh only the widget types actually on the home screen: the state written
                // above is read back by each widget's provideGlance, so an unplaced widget has
                // nothing to show. getGlanceIds maps a GlanceAppWidget class to the ids of the
                // receivers serving it; AppWidgetManager.getAppWidgetIds takes component names,
                // so passing the GlanceAppWidget classes there always returned empty and nothing
                // redrew after the stats were written.
                suspend fun refreshIfPlaced(widget: GlanceAppWidget) {
                    if (manager.getGlanceIds(widget::class.java).isNotEmpty()) widget.updateAll(context)
                }

                refreshIfPlaced(TempoAppWidget())
                refreshIfPlaced(TempoArtistWidget())
                refreshIfPlaced(TempoDashboardWidget())
                refreshIfPlaced(TempoDiscoveryWidget())
                refreshIfPlaced(TempoHeatmapWidget())
                refreshIfPlaced(TempoMilestoneWidget())
                refreshIfPlaced(TempoMixWidget())
                refreshIfPlaced(TempoTodayTotalWidget())
                refreshIfPlaced(TempoListeningRhythmWidget())

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

        /** "101st", "23rd", "13th" — the badge used to print "101th". */
        private fun ordinal(n: Int): String =
            when {
                n % 100 in 11..13 -> "${n}th"
                n % 10 == 1 -> "${n}st"
                n % 10 == 2 -> "${n}nd"
                n % 10 == 3 -> "${n}rd"
                else -> "${n}th"
            }

        /** Hero numeral form for today's total: "1h 3m", "41m", "38s". */
        private fun formatHeroTime(ms: Long): String {
            val totalMinutes = ms / 60_000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                ms <= 0L -> "0m"
                hours == 0L && minutes == 0L -> "${ms / 1000}s"
                hours == 0L -> "${minutes}m"
                minutes == 0L -> "${hours}h"
                else -> "${hours}h ${minutes}m"
            }
        }

        /** Compact form for the comparison labels: "38m", "1.1h". */
        private fun formatShortDuration(ms: Long): String {
            val minutes = ms / 60_000
            return when {
                ms <= 0L -> "0m"
                minutes < 1 -> "<1m"
                minutes < 60 -> "${minutes}m"
                else -> String.format(java.util.Locale.US, "%.1fh", minutes / 60.0)
            }
        }

        private suspend fun downloadImage(
            context: Context,
            url: String,
            fileName: String,
        ): String? {
            val file = File(context.filesDir, fileName)
            val prefs = context.getSharedPreferences("widget_image_cache", Context.MODE_PRIVATE)
            val lastUrl = prefs.getString(fileName, null)

            // Smart Cache: Only download if URL changed or file is missing
            if (file.exists() && url == lastUrl) {
                return file.absolutePath
            }

            // Shared app loader: a fresh ImageLoader per download opened its own OkHttp client
            // plus memory/disk caches, up to twice per run, and was never shut down.
            val loader = SingletonImageLoader.get(context)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(Size(512, 512))
                    .precision(Precision.EXACT)
                    .build()

            val result = (loader.execute(request) as? SuccessResult)?.image
            val bitmap = result?.toBitmap()

            return bitmap?.let {
                FileOutputStream(file).use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                // Update cache mapping
                prefs.edit().putString(fileName, url).apply()
                file.absolutePath
            }
        }
    }
