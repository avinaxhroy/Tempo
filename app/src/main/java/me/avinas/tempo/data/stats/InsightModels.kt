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
    val sample_size: Int
)

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
    MOOD, DISCOVERY, BINGE, GENRE, PEAK_TIME, LOYALTY, ENERGY, DANCEABILITY, TEMPO, ACOUSTICNESS, STREAK, ENGAGEMENT
}
