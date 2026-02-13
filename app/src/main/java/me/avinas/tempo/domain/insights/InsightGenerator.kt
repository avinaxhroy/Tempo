package me.avinas.tempo.domain.insights

import me.avinas.tempo.data.stats.*
import me.avinas.tempo.R
import javax.inject.Inject
import java.util.Calendar

/**
 * Generates human-readable insights from raw statistics.
 */
class InsightGenerator @Inject constructor() {

    fun generateInsights(
        moodStats: MoodAggregates?,
        bingeSessions: List<BingeSession>,
        discoveryTrends: List<DiscoveryTrend>,
        hourlyDistribution: List<HourlyDistribution>,
        dayOfWeekDistribution: List<DayOfWeekDistribution>,
        listeningStreak: ListeningStreak?,
        topGenres: List<TopGenre>,
        engagementStats: EngagementStats?,
        timeRange: TimeRange
    ): List<InsightCardData> {
        val insights = mutableListOf<InsightCardData>()

        // 1. Mood Insights
        moodStats?.let { mood ->
            val valence = mood.avg_valence ?: return@let
            val energy = mood.avg_energy ?: return@let
            
            // Subtle indicator for estimated data - only show if >30% is estimated
            val estimatedPercent = if (mood.sample_size > 0) (mood.estimated_sample_size * 100) / mood.sample_size else 0
            val estimatedNote = if (estimatedPercent > 30) " • via genre" else ""
            
            val moodTitle = me.avinas.tempo.utils.TempoCopyEngine.getMoodTitle(valence, energy)
            val description = me.avinas.tempo.utils.TempoCopyEngine.getMoodDescription(energy, valence) + estimatedNote
            
            insights.add(InsightCardData(
                title = moodTitle,
                description = description,
                type = InsightType.MOOD,
                score = 0.9
            ))

            // Extra Mood Insights
            if (energy >= 0.7f) {
                insights.add(InsightCardData(
                    title = "High Energy Mode! ⚡",
                    description = "Average energy level: ${(energy * 100).toInt()}%$estimatedNote",
                    type = InsightType.ENERGY,
                    score = 0.65
                ))
            }

            val danceability = mood.avg_danceability ?: 0f
            if (danceability > 0.5f) {
                insights.add(InsightCardData(
                    title = "Ready to Dance! 💃",
                    description = "Your playlist is ${(danceability * 100).toInt()}% danceable$estimatedNote",
                    type = InsightType.DANCEABILITY,
                    score = 0.65
                ))
            }

            val tempo = mood.avg_tempo
            // Only show Tempo insight if we have real audio data (not estimated)
            if (tempo != null && tempo > 0 && mood.isFullyVerified) {
                 val tempoTitle = when {
                    tempo >= 140 -> "Fast & furious 🏎️"
                    tempo >= 120 -> "Upbeat tempo 🎶"
                    tempo >= 100 -> "Moderate groove 🎵"
                    tempo >= 80 -> "Relaxed pace 🎧"
                    else -> "Slow & steady 🐢"
                }
                insights.add(InsightCardData(
                    title = tempoTitle,
                    description = "Average tempo: ${tempo.toInt()} BPM",
                    type = InsightType.TEMPO,
                    score = 0.6
                ))
            }

            val acousticness = mood.avg_acousticness ?: 0f
            if (acousticness > 0.5f) {
                 insights.add(InsightCardData(
                    title = "Acoustic Soul 🎸",
                    description = "${(acousticness * 100).toInt()}% of your music is acoustic$estimatedNote",
                    type = InsightType.ACOUSTICNESS,
                    score = 0.65
                ))
            }
        }

        // 2. Binge Listening
        if (bingeSessions.isNotEmpty()) {
            val topBinge = bingeSessions.first()
            insights.add(InsightCardData(
                title = me.avinas.tempo.utils.TempoCopyEngine.getBingeTitle(topBinge.artist),
                description = me.avinas.tempo.utils.TempoCopyEngine.getBingeDescription(topBinge.artist, topBinge.session_play_count),
                type = InsightType.BINGE,
                score = 0.85
            ))
        }

        // 3. Discovery Trend
        if (discoveryTrends.isNotEmpty()) {
            val currentMonth = discoveryTrends.first()
            insights.add(InsightCardData(
                title = me.avinas.tempo.utils.TempoCopyEngine.getDiscoveryTitle(),
                description = me.avinas.tempo.utils.TempoCopyEngine.getDiscoveryDescription(currentMonth.new_artists_count),
                type = InsightType.DISCOVERY,
                score = 0.7
            ))
        }

        // 4. Peak Listening Time
        val peakHour = hourlyDistribution.maxByOrNull { it.playCount }
        peakHour?.let {
            val hour = it.hour
            val timeOfDay = when(hour) {
                in 5..11 -> "Morning Person"
                in 12..16 -> "Afternoon Listener"
                in 17..21 -> "Evening Vibe"
                else -> "Night Owl"
            }
            
            val hourFormatted = if (hour > 12) "${hour-12} PM" else if (hour == 0 || hour == 24) "12 AM" else "$hour AM"
            
            insights.add(InsightCardData(
                title = me.avinas.tempo.utils.TempoCopyEngine.getPeakTimeTitle(timeOfDay),
                description = me.avinas.tempo.utils.TempoCopyEngine.getPeakTimeDescription(hourFormatted),
                type = InsightType.PEAK_TIME,
                score = 0.6
            ))
        }

        // 5. Listening Streak
        listeningStreak?.let { streak ->
            if (streak.currentStreakDays >= 3) {
                 insights.add(InsightCardData(
                    title = me.avinas.tempo.utils.TempoCopyEngine.getStreakTitle(streak.currentStreakDays),
                    description = me.avinas.tempo.utils.TempoCopyEngine.getStreakDescription(streak.currentStreakDays),
                    type = InsightType.STREAK,
                    score = 0.8
                ))
            }
        }

        // 6. Top Genre
        if (topGenres.isNotEmpty()) {
            val topGenre = topGenres.first()
            insights.add(InsightCardData(
                title = me.avinas.tempo.utils.TempoCopyEngine.getGenreTitle(topGenre.genre),
                description = me.avinas.tempo.utils.TempoCopyEngine.getGenreDescription(topGenre.genre),
                type = InsightType.GENRE,
                score = 0.75
            ))
        }

        // 7. Engagement Style
        engagementStats?.let { stats ->
            // Completionist?
            if (stats.averageCompletionRate > 80.0) {
                 insights.add(InsightCardData(
                    title = me.avinas.tempo.utils.TempoCopyEngine.getEngagementTitle("Completionist"),
                    description = me.avinas.tempo.utils.TempoCopyEngine.getEngagementDescription("Completionist", stats.averageCompletionRate.toInt()),
                    type = InsightType.ENGAGEMENT,
                    score = 0.7
                ))
            } else if (stats.skipRate > 0.3) {
                 insights.add(InsightCardData(
                    title = me.avinas.tempo.utils.TempoCopyEngine.getEngagementTitle("Skipper"),
                    description = me.avinas.tempo.utils.TempoCopyEngine.getEngagementDescription("Skipper", (stats.skipRate * 100).toInt()),
                    type = InsightType.ENGAGEMENT,
                    score = 0.7
                ))
            }
        }

        return insights.sortedByDescending { it.score }
    }
}
