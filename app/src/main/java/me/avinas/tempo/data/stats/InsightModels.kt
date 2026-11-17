package me.avinas.tempo.data.stats

import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.domain.insights.MusicalPersonality

data class BingeSession(
    val artist: String,
    val session_play_count: Int,
    val session_duration_ms: Long,
    val session_start: Long
)

data class MoodAggregates(
    val avg_valence: Float?,
    val avg_energy: Float?,
    val avg_danceability: Float?,
    val avg_tempo: Float?,
    val avg_acousticness: Float?,
    val sample_size: Int,
    /** Number of tracks with actual audio features (Spotify/ReccoBeats) */
    val real_sample_size: Int = sample_size,
    /** Number of tracks with estimated values from tags/genres */
    val estimated_sample_size: Int = 0
) {
    /** True if all data is from actual audio analysis, not genre-based estimation */
    val isFullyVerified: Boolean get() = estimated_sample_size == 0
    
    /** Percentage of data that is real (vs estimated) */
    val realDataPercent: Int get() = if (sample_size > 0) (real_sample_size * 100) / sample_size else 0
}

data class DiscoveryTrend(
    val month: String,
    val new_artists_count: Int
)

data class InsightCardData(
    val title: String,
    val description: String, // Main text or "fallback" text
    val iconRes: Int? = null,
    val type: InsightType,
    val score: Double = 0.0,
    val payload: InsightPayload = InsightPayload.TextOnly
)

/**
 * The rendered shape of an insight is decided by its payload, not its type: each payload carries
 * the measurements its card needs, and exactly one card layout claims each payload variant.
 */
sealed class InsightPayload {
    object TextOnly : InsightPayload()
    
    data class GamificationProgress(
        val level: UserLevel,
        val nextBadge: Badge?,
        val recentBadges: List<Badge> = emptyList()
    ) : InsightPayload()

    /** Average valence (positivity) and energy, both 0f..1f. Plotted as a point in a 2-axis field. */
    data class MoodData(
        val valence: Float,
        val energy: Float
    ) : InsightPayload()

    /** Plays per clock hour, used to draw the 24-column listening distribution. */
    data class PeakTimeData(
        val peakHour: Int,
        val hourlyDistribution: List<HourlyDistribution>
    ) : InsightPayload()

    data class BingeData(
        val artist: String,
        val playCount: Int,
        val durationMs: Long
    ) : InsightPayload()

    data class DiscoveryData(
        val newArtistsCount: Int,
        val trends: List<DiscoveryTrend>
    ) : InsightPayload()

    /**
     * Streak length plus the days actually listened to, so the card can draw a real calendar
     * strip instead of repeating the number in seven identical pips.
     *
     * @param recentDays one entry per day, oldest first, true when that day had a play.
     */
    data class StreakData(
        val days: Int,
        val longestDays: Int = 0,
        val totalActiveDays: Int = 0,
        val recentDays: List<Boolean> = emptyList()
    ) : InsightPayload()

    /** The most-played artist for the range, with artwork when the library has any. */
    data class TopArtistData(
        val artist: String,
        val playCount: Int,
        val totalTimeMs: Long,
        val imageUrl: String? = null
    ) : InsightPayload()

    /** One row per audio feature that has a measured value. */
    data class AudioProfileData(
        val traits: List<AudioTrait>,
        val tempoBpm: Float? = null,
        /** True when some values are inferred from genre/tags rather than measured. */
        val estimated: Boolean = false
    ) : InsightPayload()

    data class PersonalityData(
        val personality: MusicalPersonality,
        val genre: String? = null,
        val genreSharePercent: Int? = null
    ) : InsightPayload()

    data class EngagementData(
        val completionRate: Double,
        val fullListens: Int,
        val skips: Int,
        val skipRate: Double
    ) : InsightPayload()
}

/** One measured audio attribute. [value] is 0f..1f for percentage traits. */
data class AudioTrait(
    val key: AudioTraitKey,
    val value: Float
)

enum class AudioTraitKey { ENERGY, DANCEABILITY, ACOUSTICNESS }

enum class InsightType {
    MOOD, DISCOVERY, BINGE, GENRE, PEAK_TIME, LOYALTY, ENERGY, DANCEABILITY, TEMPO, ACOUSTICNESS,
    STREAK, ENGAGEMENT, RATE_APP, TOP_ARTIST, AUDIO_PROFILE, PERSONALITY
}
