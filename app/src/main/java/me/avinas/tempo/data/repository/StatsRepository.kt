package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.stats.ArtistLoyalty
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.DayOfWeekDistribution
import me.avinas.tempo.data.stats.DiscoveryStats
import me.avinas.tempo.data.stats.EngagementOverview
import me.avinas.tempo.data.stats.EngagementStats
import me.avinas.tempo.data.stats.FirstListen
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.HourlyEngagement
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.ListeningStreak
import me.avinas.tempo.data.stats.MonthlyListening
import me.avinas.tempo.data.stats.PaginatedResult
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopGenre
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.data.stats.TrackCompletion
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.data.stats.YearOverYearComparison
import me.avinas.tempo.data.stats.PeriodComparison
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.AudioFeaturesStats
import me.avinas.tempo.data.stats.MoodTrend
import me.avinas.tempo.data.stats.TempoBucket
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for accessing listening statistics.
 * Provides cached, on-demand computation of all metrics.
 * 
 * =====================================================
 * DATA FLOW PATTERN: Enrichment → Database → UI
 * =====================================================
 * 
 * This repository is the primary data access layer for UI components.
 * It follows a strict pattern to ensure efficient data management:
 * 
 * 1. UI (ViewModels) → StatsRepository → Database
 *    - All data comes from locally cached database
 *    - No direct API calls are made
 *    - Queries are optimized with proper indices
 *    
 * 2. Background Enrichment → Database
 *    - EnrichmentWorker fetches data from APIs
 *    - Data is stored in database via DAOs
 *    - Happens in background, never blocks UI
 * 
 * Benefits:
 * - Fast, offline-first user experience
 * - API rate limits are respected in background
 * - Stats are computed efficiently using SQL
 * - Cache invalidation is handled properly
 * - UI never waits for API responses
 * 
 * Note: Spotify audio-features API was deprecated in Nov 2024.
 * We now use MusicBrainz tags for mood/genre categorization
 * and user behavior patterns for engagement metrics.
 * 
 * =====================================================
 */
interface StatsRepository {
    
    // =====================
    // Overview Stats
    // =====================
    
    /**
     * Get listening overview for a time range.
     */
    suspend fun getListeningOverview(timeRange: TimeRange): ListeningOverview
    
    /**
     * Get listening overview as a Flow for real-time updates.
     */
    fun observeListeningOverview(timeRange: TimeRange): Flow<ListeningOverview>
    
    /**
     * Observe metadata updates (e.g., album art from enrichment).
     * Emits a Unit when any track metadata is updated.
     */
    fun observeMetadataUpdates(): Flow<Unit>
    
    // =====================
    // Top Charts
    // =====================
    
    /**
     * Get top tracks with pagination.
     */
    suspend fun getTopTracks(
        timeRange: TimeRange,
        sortBy: SortBy = SortBy.PLAY_COUNT,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<TopTrack>
    
    /**
     * Get top artists with pagination.
     */
    suspend fun getTopArtists(
        timeRange: TimeRange,
        sortBy: SortBy = SortBy.PLAY_COUNT,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<TopArtist>
    
    /**
     * Get top albums with pagination.
     */
    suspend fun getTopAlbums(
        timeRange: TimeRange,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<TopAlbum>
    
    /**
     * Get top genres.
     */
    suspend fun getTopGenres(timeRange: TimeRange, limit: Int = 10): List<TopGenre>
    
    // =====================
    // Temporal Analysis
    // =====================
    
    /**
     * Get hourly listening distribution.
     */
    suspend fun getHourlyDistribution(timeRange: TimeRange): List<HourlyDistribution>
    
    /**
     * Get day of week distribution.
     */
    suspend fun getDayOfWeekDistribution(timeRange: TimeRange): List<DayOfWeekDistribution>
    
    /**
     * Get daily listening trends.
     */
    suspend fun getDailyListening(timeRange: TimeRange, limit: Int = 30): List<DailyListening>
    
    /**
     * Get monthly listening trends.
     */
    suspend fun getMonthlyListening(timeRange: TimeRange): List<MonthlyListening>
    
    /**
     * Get listening streak information.
     */
    suspend fun getListeningStreak(): ListeningStreak
    
    /**
     * Get most active hour.
     */
    suspend fun getMostActiveHour(timeRange: TimeRange): HourlyDistribution?
    
    /**
     * Get most active day.
     */
    suspend fun getMostActiveDay(timeRange: TimeRange): DayOfWeekDistribution?
    
    // =====================
    // Discovery Metrics
    // =====================
    
    /**
     * Get discovery stats for a time range.
     */
    suspend fun getDiscoveryStats(timeRange: TimeRange): DiscoveryStats
    
    /**
     * Get artist loyalty metrics.
     */
    suspend fun getArtistLoyalty(
        timeRange: TimeRange,
        minPlays: Int = 5,
        limit: Int = 10
    ): List<ArtistLoyalty>
    
    /**
     * Get first listen dates for artists.
     */
    suspend fun getArtistFirstListens(): List<FirstListen>
    
    /**
     * Calculate variety/diversity score (Shannon entropy).
     */
    suspend fun getVarietyScore(timeRange: TimeRange): Double
    
    // =====================
    // Engagement Metrics
    // =====================
    
    /**
     * Get engagement stats.
     */
    suspend fun getEngagementStats(timeRange: TimeRange): EngagementStats
    
    /**
     * Get engagement metrics for a specific track based on user behavior.
     * This replaces audio features with behavior-based insights.
     */
    suspend fun getTrackEngagement(trackId: Long): TrackEngagement?
    
    /**
     * Get engagement overview for a time period.
     */
    suspend fun getEngagementOverview(timeRange: TimeRange): EngagementOverview
    
    /**
     * Get hourly engagement breakdown.
     */
    suspend fun getHourlyEngagement(timeRange: TimeRange): List<HourlyEngagement>
    
    /**
     * Get track completion statistics.
     */
    suspend fun getTrackCompletionStats(
        timeRange: TimeRange,
        minPlays: Int = 3,
        limit: Int = 20
    ): List<TrackCompletion>
    
    /**
     * Get most skipped tracks.
     */
    suspend fun getMostSkippedTracks(timeRange: TimeRange, limit: Int = 10): List<TrackCompletion>
    
    // =====================
    // Spotify Audio Features
    // =====================
    
    /**
     * Get audio features stats (requires Spotify connection).
     */
    suspend fun getAudioFeaturesStats(timeRange: TimeRange): AudioFeaturesStats?
    
    /**
     * Get mood trends over time.
     */
    suspend fun getMoodTrends(timeRange: TimeRange): List<MoodTrend>
    
    /**
     * Get tempo distribution.
     */
    suspend fun getTempoDistribution(timeRange: TimeRange): List<TempoBucket>
    
    // =====================
    // Comparisons
    // =====================
    
    /**
     * Get year-over-year comparison.
     */
    suspend fun getYearOverYearComparison(currentYear: Int): YearOverYearComparison
    
    /**
     * Get period comparison (this week vs last week, etc.).
     */
    suspend fun getPeriodComparison(timeRange: TimeRange): PeriodComparison
    
    // =====================
    // Cache Management
    // =====================
    
    /**
     * Invalidate all cached stats.
     */
    fun invalidateCache()
    
    /**
     * Compute listening insights for a given time range.
     * @param timeRange The time period to analyze
     * @return List of generated insight cards
     */
    suspend fun getInsights(timeRange: TimeRange): List<InsightCardData>

    /**
     * Get unique tracks count for the specified time range.
     */
    fun invalidateCache(timeRange: TimeRange)

    // =====================
    // History
    // =====================

    /**
     * Get listening history with pagination.
     * 
     * @param filterPodcasts If true, exclude tracks marked as PODCAST
     * @param filterAudiobooks If true, exclude tracks marked as AUDIOBOOK
     */
    suspend fun getHistory(
        timeRange: TimeRange? = null, // Made nullable to support custom ranges if needed, or handle inside implementation
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<HistoryItem>
    
    /**
     * Get listening history EXCLUDING Last.fm imported events.
     * Shows only "live" activity from Spotify/notification tracking.
     */
    suspend fun getHistoryExcludingLastFm(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<HistoryItem>
    
    /**
     * Get listening history ONLY from Last.fm imported events (active set).
     */
    suspend fun getHistoryLastFmOnly(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        page: Int = 0,
        pageSize: Int = 20
    ): PaginatedResult<HistoryItem>

    // =====================
    // Detail Screens
    // =====================

    /**
     * Get detailed stats for a track.
     */
    suspend fun getTrackDetails(trackId: Long): TrackDetails

    /**
     * Get detailed stats for an artist by ID.
     */
    suspend fun getArtistDetails(artistId: Long): ArtistDetails
    
    /**
     * Get detailed stats for an artist by name.
     * This supports individual artists from split multi-artist entries.
     */
    suspend fun getArtistDetailsByName(artistName: String): ArtistDetails?

    /**
     * Get detailed stats for an album.
     */
    suspend fun getAlbumDetails(albumId: Long): AlbumDetails

    /**
     * Get listening history for a specific track (for trends chart).
     */
    suspend fun getTrackListeningHistory(trackId: Long, timeRange: TimeRange): List<DailyListening>

    /**
     * Get artist ID by name. Returns null if not found.
     */
    suspend fun getArtistIdByName(artistName: String): Long?

    /**
     * Get album ID by title and artist name. Returns null if not found.
     */
    suspend fun getAlbumIdByTitleAndArtist(albumTitle: String, artistName: String): Long?

    /**
     * Get artist image URL by name. Returns null if not found.
     */
    suspend fun getArtistImageUrl(artistName: String): String?

    /**
     * Get audio features for a specific track.
     * Returns null if Spotify not connected or features not available.
     */
    suspend fun getTrackAudioFeatures(trackId: Long): TrackAudioFeatures?

    /**
     * Get artist rank percentile based on play count (Local Percentile).
     * Returns a value between 0.0 (Top 0%) and 100.0 (Top 100%).
     * Lower is better (e.g. 1.0 means Top 1%).
     */
    suspend fun getArtistRankPercentile(playCount: Int, timeRange: TimeRange): Double

    /**
     * Get the exact timestamp of when an artist was first discovered (first listen).
     */
    suspend fun getArtistDiscoveryDate(artistId: Long): Long?
    
    // =====================
    // Batch Operations (Spotlight Performance)
    // =====================
    
    /**
     * Get play counts for multiple artists in a single batch query.
     * Used by Spotlight card generators to avoid N+1 query problems.
     */
    suspend fun getArtistPlayCountsBatch(artistNames: List<String>): Map<String, Int>
    
    /**
     * Get image URLs for multiple artists in a single batch query.
     * Used by Spotlight card generators to avoid N+1 query problems.
     */
    suspend fun getArtistImageUrlsBatch(artistNames: List<String>): Map<String, String?>

    /**
     * Get the timestamp of the earliest data point in the repository.
     * Used for determining if "All Time" stats are mature enough to show.
     */
    suspend fun getEarliestDataTimestamp(): Long?
}

/**
 * Audio features for a single track (from Spotify).
 */
data class TrackAudioFeatures(
    val energy: Float,
    val danceability: Float,
    val valence: Float, // Mood/happiness
    val tempo: Float, // BPM
    val acousticness: Float,
    val instrumentalness: Float,
    val speechiness: Float,
    val liveness: Float,
    val loudness: Float,
    val key: Int,
    val mode: Int, // 1 = major, 0 = minor
    val timeSignature: Int
) {
    // Human-readable descriptions
    val energyDescription: String
        get() = when {
            energy >= 0.8f -> "Very High"
            energy >= 0.6f -> "High"
            energy >= 0.4f -> "Moderate"
            energy >= 0.2f -> "Low"
            else -> "Very Low"
        }

    val moodDescription: String
        get() = when {
            valence >= 0.8f -> "Very Happy"
            valence >= 0.6f -> "Happy"
            valence >= 0.4f -> "Neutral"
            valence >= 0.2f -> "Melancholic"
            else -> "Sad"
        }

    val danceabilityDescription: String
        get() = when {
            danceability >= 0.8f -> "Very Danceable"
            danceability >= 0.6f -> "Danceable"
            danceability >= 0.4f -> "Moderate"
            else -> "Not Danceable"
        }

    val tempoDescription: String
        get() = when {
            tempo >= 140f -> "Very Fast"
            tempo >= 120f -> "Fast"
            tempo >= 100f -> "Moderate"
            tempo >= 80f -> "Slow"
            else -> "Very Slow"
        }

    val keyName: String
        get() = when (key) {
            0 -> "C"
            1 -> "C♯/D♭"
            2 -> "D"
            3 -> "D♯/E♭"
            4 -> "E"
            5 -> "F"
            6 -> "F♯/G♭"
            7 -> "G"
            8 -> "G♯/A♭"
            9 -> "A"
            10 -> "A♯/B♭"
            11 -> "B"
            else -> "Unknown"
        }

    val modeName: String
        get() = if (mode == 1) "Major" else "Minor"

    val musicalKey: String
        get() = "$keyName $modeName"

    val isAcoustic: Boolean
        get() = acousticness > 0.5f

    val isInstrumental: Boolean
        get() = instrumentalness > 0.5f

    val isLive: Boolean
        get() = liveness > 0.8f

    // Percentage values for UI display
    val energyPercent: Int get() = (energy * 100).toInt()
    val danceabilityPercent: Int get() = (danceability * 100).toInt()
    val valencePercent: Int get() = (valence * 100).toInt()
    val acousticnessPercent: Int get() = (acousticness * 100).toInt()
    val instrumentalnessPercent: Int get() = (instrumentalness * 100).toInt()
    val speechinessPercent: Int get() = (speechiness * 100).toInt()
    val livenessPercent: Int get() = (liveness * 100).toInt()
}

/**
 * Sort order for charts.
 */
enum class SortBy {
    PLAY_COUNT,
    TOTAL_TIME,
    COMBINED_SCORE // Weighted combination of play count + total time (50/50)
}
