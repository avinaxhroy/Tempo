package me.avinas.tempo.data.stats

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
    val description: String,
    val iconRes: Int? = null,
    val type: InsightType,
    val score: Double = 0.0, // For relevance sorting
    val data: Any? = null
)

enum class InsightType {
    MOOD, DISCOVERY, BINGE, GENRE, PEAK_TIME, LOYALTY, ENERGY, DANCEABILITY, TEMPO, ACOUSTICNESS, STREAK, ENGAGEMENT, RATE_APP
}
