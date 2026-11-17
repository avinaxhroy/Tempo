package me.avinas.tempo.domain.insights

import me.avinas.tempo.data.stats.BingeSession
import me.avinas.tempo.data.stats.DiscoveryTrend
import me.avinas.tempo.data.stats.EngagementStats
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.AudioTrait
import me.avinas.tempo.data.stats.AudioTraitKey
import me.avinas.tempo.data.stats.ListeningStreak
import me.avinas.tempo.data.stats.MoodAggregates
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopGenre
import me.avinas.tempo.utils.TempoCopyEngine
import javax.inject.Inject

/**
 * Everything the insight cards may draw on, gathered in one place because the cards no longer
 * share a single numeric field: the mood plot needs two axes, the timeline needs the days
 * themselves, the artist card needs artwork.
 */
data class InsightInputs(
    val moodStats: MoodAggregates?,
    val bingeSessions: List<BingeSession>,
    val discoveryTrends: List<DiscoveryTrend>,
    val hourlyDistribution: List<HourlyDistribution>,
    val listeningStreak: ListeningStreak?,
    /** One entry per day, oldest first, true when that day had a play. */
    val recentListeningDays: List<Boolean> = emptyList(),
    val topGenres: List<TopGenre> = emptyList(),
    val topArtist: TopArtist? = null,
    val engagementStats: EngagementStats? = null,
    val totalPlayCount: Int = 0,
    val varietyScore: Double = 0.0,
    val newArtistCount: Int = 0,
)

/**
 * Turns aggregates into cards.
 *
 * One card per payload shape, so the feed can pick a distinct layout for each: the generator's
 * job is to decide *which* measurements are worth showing and hand each a payload the renderer
 * can draw without inventing anything. Insights whose data is missing are not emitted at all
 * rather than padded with placeholders.
 */
class InsightGenerator @Inject constructor() {

    fun generateInsights(inputs: InsightInputs): List<InsightCardData> {
        val insights = mutableListOf<InsightCardData>()
        val mood = inputs.moodStats

        // 1. Listening personality — the summary card, expressed as type rather than a chart.
        val genres = inputs.topGenres.map { it.genre }
        val hasPersonalityData = mood?.avg_energy != null || genres.isNotEmpty()
        if (hasPersonalityData) {
            val personality = MusicalPersonality.classify(
                energy = mood?.avg_energy ?: 0.5f,
                valence = mood?.avg_valence ?: 0.5f,
                danceability = mood?.avg_danceability ?: 0.5f,
                topGenres = genres,
                newArtistCount = inputs.newArtistCount,
                varietyScore = inputs.varietyScore,
            )
            val topGenre = inputs.topGenres.firstOrNull()
            val share = if (topGenre != null && inputs.totalPlayCount > 0) {
                (topGenre.playCount * 100) / inputs.totalPlayCount
            } else {
                null
            }
            insights.add(
                InsightCardData(
                    title = personality.stableKey,
                    description = "",
                    type = InsightType.PERSONALITY,
                    score = 0.95,
                    payload = InsightPayload.PersonalityData(
                        personality = personality,
                        genre = topGenre?.genre,
                        genreSharePercent = share,
                    ),
                ),
            )
        }

        // 2. Mood — a point in a two-axis field (valence against energy).
        if (mood?.avg_valence != null && mood.avg_energy != null) {
            val valence = mood.avg_valence
            val energy = mood.avg_energy
            insights.add(
                InsightCardData(
                    title = TempoCopyEngine.getMoodTitle(valence, energy),
                    description = TempoCopyEngine.getMoodDescription(energy, valence),
                    type = InsightType.MOOD,
                    score = 0.9,
                    payload = InsightPayload.MoodData(valence, energy),
                ),
            )
        }

        // 3. Most-played artist — artwork leads, numbers support.
        inputs.topArtist?.let { artist ->
            if (!artist.imageUrl.isNullOrBlank() && artist.playCount > 0) {
                insights.add(
                    InsightCardData(
                        title = artist.artist,
                        description = "",
                        type = InsightType.TOP_ARTIST,
                        score = 0.88,
                        payload = InsightPayload.TopArtistData(
                            artist = artist.artist,
                            playCount = artist.playCount,
                            totalTimeMs = artist.totalTimeMs,
                            imageUrl = artist.imageUrl,
                        ),
                    ),
                )
            }
        }

        // 4. Binge session — numbers large enough to read as a headline.
        inputs.bingeSessions.firstOrNull()?.let { session ->
            if (session.session_play_count > 1) {
                insights.add(
                    InsightCardData(
                        title = TempoCopyEngine.getBingeTitle(session.artist),
                        description = "",
                        type = InsightType.BINGE,
                        score = 0.85,
                        payload = InsightPayload.BingeData(
                            artist = session.artist,
                            playCount = session.session_play_count,
                            durationMs = session.session_duration_ms,
                        ),
                    ),
                )
            }
        }

        // 5. Streak — the days themselves, drawn as a calendar strip.
        inputs.listeningStreak?.let { streak ->
            if (streak.currentStreakDays >= 3) {
                insights.add(
                    InsightCardData(
                        title = TempoCopyEngine.getStreakTitle(streak.currentStreakDays),
                        description = "",
                        type = InsightType.STREAK,
                        score = 0.8,
                        payload = InsightPayload.StreakData(
                            days = streak.currentStreakDays,
                            longestDays = streak.longestStreakDays,
                            totalActiveDays = streak.totalActiveDays,
                            recentDays = inputs.recentListeningDays,
                        ),
                    ),
                )
            }
        }

        // 6. Sound profile — every measured audio attribute, one row each.
        val traits = buildList {
            mood?.avg_energy?.let { add(AudioTrait(AudioTraitKey.ENERGY, it)) }
            mood?.avg_danceability?.let { add(AudioTrait(AudioTraitKey.DANCEABILITY, it)) }
            mood?.avg_acousticness?.let { add(AudioTrait(AudioTraitKey.ACOUSTICNESS, it)) }
        }
        val tempo = mood?.avg_tempo?.takeIf { it > 0f }
        if (traits.isNotEmpty() || tempo != null) {
            insights.add(
                InsightCardData(
                    title = "Sound profile",
                    description = "",
                    type = InsightType.AUDIO_PROFILE,
                    score = 0.72,
                    payload = InsightPayload.AudioProfileData(
                        traits = traits,
                        tempoBpm = tempo?.takeIf { mood.isFullyVerified },
                        estimated = mood != null && !mood.isFullyVerified,
                    ),
                ),
            )
        }

        // 7. Discovery — new artists per month. One month is a number, not a trend, and the
        // numbers card already owns single figures, so two months is the minimum.
        if (inputs.discoveryTrends.size >= 2) {
            val currentMonth = inputs.discoveryTrends.first()
            insights.add(
                InsightCardData(
                    title = TempoCopyEngine.getDiscoveryTitle(),
                    description = TempoCopyEngine.getDiscoveryDescription(currentMonth.new_artists_count),
                    type = InsightType.DISCOVERY,
                    score = 0.7,
                    payload = InsightPayload.DiscoveryData(
                        newArtistsCount = currentMonth.new_artists_count,
                        trends = inputs.discoveryTrends,
                    ),
                ),
            )
        }

        // 8. Engagement — how completely tracks get played.
        inputs.engagementStats?.let { stats ->
            if (stats.averageCompletionRate > 80.0 || stats.skipRate > 0.3) {
                val completionist = stats.averageCompletionRate > 80.0
                insights.add(
                    InsightCardData(
                        title = TempoCopyEngine.getEngagementTitle(if (completionist) "Completionist" else "Skipper"),
                        description = "",
                        type = InsightType.ENGAGEMENT,
                        score = 0.68,
                        payload = InsightPayload.EngagementData(
                            completionRate = stats.averageCompletionRate,
                            fullListens = stats.fullListensCount,
                            skips = stats.skipsCount,
                            skipRate = stats.skipRate,
                        ),
                    ),
                )
            }
        }

        // 9. Peak hour — the shape of the listening day.
        inputs.hourlyDistribution.maxByOrNull { it.playCount }?.let { peak ->
            val hour = peak.hour
            val timeOfDay = when (hour) {
                in 5..11 -> "Morning Person"
                in 12..16 -> "Afternoon Listener"
                in 17..21 -> "Evening Vibe"
                else -> "Night Owl"
            }
            insights.add(
                InsightCardData(
                    title = TempoCopyEngine.getPeakTimeTitle(timeOfDay),
                    description = "",
                    type = InsightType.PEAK_TIME,
                    score = 0.65,
                    payload = InsightPayload.PeakTimeData(hour, inputs.hourlyDistribution),
                ),
            )
        }

        return insights.sortedByDescending { it.score }
    }
}
