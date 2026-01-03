package me.avinas.tempo.data.stats

import androidx.room.ColumnInfo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Time range for filtering statistics.
 */
enum class TimeRange {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    THIS_YEAR,
    ALL_TIME;

    /**
     * Get the start timestamp for this time range.
     */
    fun getStartTimestamp(): Long {
        val now = LocalDateTime.now()
        val startOfDay = now.toLocalDate().atStartOfDay()

        return when (this) {
            TODAY -> startOfDay
            THIS_WEEK -> startOfDay.minusDays(now.dayOfWeek.value.toLong() - 1)
            THIS_MONTH -> now.toLocalDate().withDayOfMonth(1).atStartOfDay()
            THIS_YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay()
            ALL_TIME -> LocalDateTime.of(1970, 1, 1, 0, 0)
        }.toEpochSecond(java.time.ZoneOffset.UTC) * 1000
    }

    /**
     * Get the end timestamp for this time range (now).
     */
    fun getEndTimestamp(): Long = System.currentTimeMillis()
}

// =====================
// Combined Stats Models (for optimized single-query fetches)
// =====================

/**
 * Combined basic stats from a single query.
 * Reduces database round trips for overview stats.
 */
data class CombinedBasicStats(
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int,
    @ColumnInfo(name = "unique_artists") val uniqueArtists: Int,
    @ColumnInfo(name = "unique_albums") val uniqueAlbums: Int
)

// =====================
// Basic Stats Models
// =====================

/**
 * Overview statistics for a time period.
 */
data class ListeningOverview(
    val totalListeningTimeMs: Long,
    val totalPlayCount: Int,
    val uniqueTracksCount: Int,
    val uniqueArtistsCount: Int,
    val uniqueAlbumsCount: Int,
    val averageSessionDurationMs: Long,
    val longestSessionMs: Long,
    val timeRange: TimeRange
) {
    val totalListeningTimeMinutes: Long get() = totalListeningTimeMs / 60_000
    val totalListeningTimeHours: Double get() = totalListeningTimeMs / 3_600_000.0
    val averageTracksPerDay: Double get() = if (timeRange == TimeRange.ALL_TIME) 0.0 else totalPlayCount.toDouble() / timeRange.getDaysInRange()
}

private fun TimeRange.getDaysInRange(): Int {
    return when (this) {
        TimeRange.TODAY -> 1
        TimeRange.THIS_WEEK -> 7
        TimeRange.THIS_MONTH -> LocalDate.now().lengthOfMonth()
        TimeRange.THIS_YEAR -> LocalDate.now().lengthOfYear()
        TimeRange.ALL_TIME -> 365 // Approximate
    }
}

// =====================
// Top Charts Models
// =====================

/**
 * Top track with play statistics.
 */
data class TopTrack(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "first_played") val firstPlayed: Long,
    @ColumnInfo(name = "last_played") val lastPlayed: Long,
    @ColumnInfo(name = "preview_url") val previewUrl: String? = null, // From enriched_metadata
    @ColumnInfo(name = "combined_score") val combinedScore: Double? = null // For combined ranking, not stored in DB
) {
    val totalTimeMinutes: Long get() = totalTimeMs / 60_000
    val averagePlayDurationMs: Long get() = if (playCount > 0) totalTimeMs / playCount else 0
}

/**
 * Raw artist stats from database before splitting multi-artist entries.
 */
data class RawArtistStats(
    val artist: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int,
    @ColumnInfo(name = "first_played") val firstPlayed: Long,
    @ColumnInfo(name = "last_played") val lastPlayed: Long
)

/**
 * Top artist with play statistics.
 */
data class TopArtist(
    @ColumnInfo(name = "artist_id") val artistId: Long? = null,
    val artist: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int,
    @ColumnInfo(name = "first_played") val firstPlayed: Long,
    @ColumnInfo(name = "last_played") val lastPlayed: Long,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "country") val country: String? = null
) {
    val totalTimeMinutes: Long get() = totalTimeMs / 60_000
    val totalTimeHours: Double get() = totalTimeMs / 3_600_000.0
}

/**
 * Top album with play statistics.
 */
data class TopAlbum(
    val album: String,
    val artist: String,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int
) {
    val totalTimeMinutes: Long get() = totalTimeMs / 60_000
}

/**
 * Top genre with play statistics.
 */
data class TopGenre(
    val genre: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_artists") val uniqueArtists: Int
)

// =====================
// Temporal Analysis Models
// =====================

/**
 * Hourly listening distribution.
 */
data class HourlyDistribution(
    val hour: Int, // 0-23
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long
) {
    val hourLabel: String get() = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}

/**
 * Daily listening distribution.
 */
data class DayOfWeekDistribution(
    @ColumnInfo(name = "day_of_week") val dayOfWeek: Int, // 1-7 (Monday-Sunday)
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long
) {
    val dayName: String get() = DayOfWeek.of(dayOfWeek).name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Daily aggregated listening data.
 */
data class DailyListening(
    val date: String, // YYYY-MM-DD format
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int,
    @ColumnInfo(name = "unique_artists") val uniqueArtists: Int
)

/**
 * Weekly aggregated listening data.
 */
data class WeeklyListening(
    val year: Int,
    val week: Int,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long
)

/**
 * Monthly aggregated listening data.
 */
data class MonthlyListening(
    val year: Int,
    val month: Int, // 1-12
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "unique_tracks") val uniqueTracks: Int,
    @ColumnInfo(name = "unique_artists") val uniqueArtists: Int
) {
    val monthName: String get() = java.time.Month.of(month).name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Listening streak information.
 */
data class ListeningStreak(
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val currentStreakStartDate: String?,
    val longestStreakStartDate: String?,
    val longestStreakEndDate: String?,
    val totalActiveDays: Int
)

// =====================
// Discovery Metrics Models
// =====================

/**
 * Discovery statistics for a period.
 */
data class DiscoveryStats(
    val newArtistsCount: Int,
    val newTracksCount: Int,
    val repeatListensCount: Int,
    val newVsRepeatRatio: Double,
    val varietyScore: Double, // Shannon entropy
    val topNewArtist: String?,
    val topNewTrack: String?
)

/**
 * Artist loyalty metrics.
 */
data class ArtistLoyalty(
    val artist: String,
    @ColumnInfo(name = "total_plays") val totalPlays: Int,
    @ColumnInfo(name = "repeat_plays") val repeatPlays: Int,
    @ColumnInfo(name = "unique_tracks_played") val uniqueTracksPlayed: Int,
    @ColumnInfo(name = "first_listen") val firstListen: Long,
    @ColumnInfo(name = "days_since_first_listen") val daysSinceFirstListen: Int
) {
    val loyaltyScore: Double get() = if (totalPlays > 0) repeatPlays.toDouble() / totalPlays else 0.0
}

/**
 * First listen record for an artist or track.
 */
data class FirstListen(
    val name: String,
    @ColumnInfo(name = "first_listen_timestamp") val firstListenTimestamp: Long,
    val type: String // "artist" or "track"
) {
    val firstListenDate: LocalDate get() = LocalDateTime.ofEpochSecond(
        firstListenTimestamp / 1000, 0, java.time.ZoneOffset.UTC
    ).toLocalDate()
}

// =====================
// Engagement Metrics Models
// =====================

/**
 * Engagement statistics.
 */
data class EngagementStats(
    val averageCompletionRate: Double,
    val fullListensCount: Int, // >90% completion
    val partialListensCount: Int, // 30-90% completion
    val skipsCount: Int, // <30% completion
    val skipRate: Double,
    val bingeSessions: Int, // Same artist 3+ times in a row
    val longestBingeArtist: String?,
    val longestBingeCount: Int
)

/**
 * Track completion statistics.
 */
data class TrackCompletion(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "average_completion") val averageCompletion: Double,
    @ColumnInfo(name = "full_listens") val fullListens: Int,
    @ColumnInfo(name = "skips") val skips: Int,
    @ColumnInfo(name = "total_plays") val totalPlays: Int
) {
    val completionRate: Double get() = if (totalPlays > 0) fullListens.toDouble() / totalPlays else 0.0
    val skipRate: Double get() = if (totalPlays > 0) skips.toDouble() / totalPlays else 0.0
}

/**
 * Replayed track statistics.
 */
data class ReplayedTrackStats(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "replay_count") val replayCount: Int,
    @ColumnInfo(name = "total_plays") val totalPlays: Int,
    @ColumnInfo(name = "average_completion") val averageCompletion: Double
) {
    val replayRate: Double get() = if (totalPlays > 0) replayCount.toDouble() / totalPlays else 0.0
}

/**
 * Track engagement statistics from optimized single database query.
 * Uses indexed columns (was_skipped, is_replay, pause_count) for efficiency.
 */
data class TrackEngagementStats(
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_listening_time_ms") val totalListeningTimeMs: Long,
    @ColumnInfo(name = "average_completion_percent") val averageCompletionPercent: Double?,
    @ColumnInfo(name = "full_plays_count") val fullPlaysCount: Int,
    @ColumnInfo(name = "partial_plays_count") val partialPlaysCount: Int,
    @ColumnInfo(name = "skips_count") val skipsCount: Int,
    @ColumnInfo(name = "replay_count") val replayCount: Int,
    @ColumnInfo(name = "last_played_timestamp") val lastPlayedTimestamp: Long?,
    @ColumnInfo(name = "first_played_timestamp") val firstPlayedTimestamp: Long?,
    @ColumnInfo(name = "average_pause_count") val averagePauseCount: Float?,
    @ColumnInfo(name = "total_pause_count") val totalPauseCount: Int,
    @ColumnInfo(name = "unique_sessions_count") val uniqueSessionsCount: Int
)

// =====================
// Spotify Audio Features Stats
// =====================

/**
 * Aggregated audio features statistics (requires Spotify connection).
 */
data class AudioFeaturesStats(
    val averageEnergy: Float,
    val averageDanceability: Float,
    val averageValence: Float,
    val averageTempo: Float,
    val averageAcousticness: Float,
    val averageInstrumentalness: Float,
    val averageSpeechiness: Float,
    val averageLoudness: Float,
    val tracksWithFeatures: Int,
    val dominantMood: String,
    val energyTrend: String // "increasing", "decreasing", "stable"
) {
    val moodDescription: String get() = when {
        averageValence >= 0.7 -> "Very Happy"
        averageValence >= 0.5 -> "Positive"
        averageValence >= 0.3 -> "Balanced"
        else -> "Melancholic"
    }

    val energyDescription: String get() = when {
        averageEnergy >= 0.7 -> "High Energy"
        averageEnergy >= 0.5 -> "Moderate"
        averageEnergy >= 0.3 -> "Relaxed"
        else -> "Calm"
    }
}

/**
 * Mood trend over time.
 */
data class MoodTrend(
    val date: String,
    @ColumnInfo(name = "avg_valence") val avgValence: Float,
    @ColumnInfo(name = "avg_energy") val avgEnergy: Float,
    @ColumnInfo(name = "avg_danceability") val avgDanceability: Float,
    @ColumnInfo(name = "track_count") val trackCount: Int
)

/**
 * Tempo distribution bucket.
 */
data class TempoBucket(
    val bucketLabel: String, // e.g., "60-80 BPM", "80-100 BPM"
    val minTempo: Int,
    val maxTempo: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "total_plays") val totalPlays: Int
)

// =====================
// Comparison Models
// =====================

/**
 * Year-over-year comparison.
 */
data class YearOverYearComparison(
    val currentYear: Int,
    val previousYear: Int,
    val currentYearPlayCount: Int,
    val previousYearPlayCount: Int,
    val currentYearTimeMs: Long,
    val previousYearTimeMs: Long,
    val currentYearUniqueArtists: Int,
    val previousYearUniqueArtists: Int
) {
    val playCountChange: Double get() = if (previousYearPlayCount > 0) {
        ((currentYearPlayCount - previousYearPlayCount).toDouble() / previousYearPlayCount) * 100
    } else 0.0

    val timeChange: Double get() = if (previousYearTimeMs > 0) {
        ((currentYearTimeMs - previousYearTimeMs).toDouble() / previousYearTimeMs) * 100
    } else 0.0
}

/**
 * Period comparison (this week vs last week, etc.)
 */
data class PeriodComparison(
    val currentPeriodPlayCount: Int,
    val previousPeriodPlayCount: Int,
    val currentPeriodTimeMs: Long,
    val previousPeriodTimeMs: Long,
    val playCountChangePercent: Double,
    val timeChangePercent: Double,
    val trending: String // "up", "down", "stable"
)

// =====================
// Paginated Results
// =====================

/**
 * Paginated result wrapper.
 */
data class PaginatedResult<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean
) {
    val totalPages: Int get() = (totalCount + pageSize - 1) / pageSize
}

// =====================
// Detail Screen Models
// =====================

/**
 * Detailed statistics for a track.
 */
data class TrackDetails(
    val track: me.avinas.tempo.data.local.entities.Track,
    val playCount: Int,
    val totalTimeMs: Long,
    val peakRank: Int?,
    val firstPlayed: Long?,
    val lastPlayed: Long?,
    val isFavorite: Boolean,
    // Links
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    // Album art fallback system
    // enrichedArtUrl in track.albumArtUrl = hotlink from API (try first)
    // localBackupArtUrl = local file saved from MediaSession (use if hotlink fails)
    val localBackupArtUrl: String? = null
) {
    val totalTimeMinutes: Long get() = totalTimeMs / 60_000
}

/**
 * Detailed statistics for an artist.
 */
data class ArtistDetails(
    val artist: me.avinas.tempo.data.local.entities.Artist,
    val listenersCount: Long?, // Global listeners from MusicBrainz/Spotify
    val personalPlayCount: Int,
    val personalTotalTimeMs: Long,
    val firstDiscovery: FirstListen?,
    val topSongs: List<TopTrack>,
    // Extended fields
    val country: String? = null,
    val uniqueAlbumsPlayed: Int = 0,
    val uniqueTracksPlayed: Int = 0,
    val moodSummary: TagBasedMoodAnalyzer.MoodSummary? = null,
    val topGenres: List<String> = emptyList(),
    val firstListenedDate: Long? = null,
    val lastListenedDate: Long? = null,
    val listeningStreakDays: Int = 0, // Days in a row listened to this artist
    val peakListeningHour: Int? = null, // Hour when most listening happens
    val topAlbums: List<TopAlbum> = emptyList()
) {
    val personalTotalTimeHours: Double get() = personalTotalTimeMs / 3_600_000.0
    val personalTotalTimeMinutes: Long get() = personalTotalTimeMs / 60_000
    
    val peakHourFormatted: String get() = when (peakListeningHour) {
        null -> "N/A"
        0 -> "12 AM"
        in 1..11 -> "$peakListeningHour AM"
        12 -> "12 PM"
        else -> "${peakListeningHour - 12} PM"
    }
}

/**
 * Detailed statistics for an album.
 */
data class AlbumDetails(
    val album: me.avinas.tempo.data.local.entities.Album,
    val artistName: String,
    val totalPlayCount: Int,
    val totalTimeMs: Long,
    val completionRate: Double,
    val tracks: List<TrackWithStats>
) {
    val totalTimeMinutes: Long get() = totalTimeMs / 60_000
}

/**
 * Track with basic stats (used in AlbumDetails).
 */
data class TrackWithStats(
    val track: me.avinas.tempo.data.local.entities.Track,
    val playCount: Int,
    val totalTimeMs: Long
)
