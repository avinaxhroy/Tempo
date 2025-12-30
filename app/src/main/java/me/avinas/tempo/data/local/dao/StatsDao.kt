package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import me.avinas.tempo.data.stats.*

/**
 * DAO for computing listening statistics using optimized SQLite queries.
 * 
 * All queries are designed for efficiency with proper use of:
 * - Indexed columns (timestamp, track_id, artist)
 * - Aggregations pushed to database layer
 * - Pagination for large result sets
 * - Time range filtering using parameterized timestamps
 * - Combined queries to reduce round trips
 */
@Dao
interface StatsDao {

    // =====================
    // Combined Stats Query (Single Round Trip)
    // =====================
    
    /**
     * Get all basic stats in a single query to reduce database round trips.
     * Returns total time, play count, unique tracks, unique artists, and unique albums.
     */
    @Query("""
        SELECT 
            COALESCE(SUM(le.playDuration), 0) as total_time_ms,
            COUNT(le.id) as play_count,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists,
            COUNT(DISTINCT CASE WHEN t.album IS NOT NULL THEN t.album END) as unique_albums
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """)
    suspend fun getCombinedBasicStats(startTime: Long, endTime: Long): CombinedBasicStats

    // =====================
    // Basic Stats Queries
    // =====================

    /**
     * Get total listening time in milliseconds for a time range.
     */
    @Query("""
        SELECT COALESCE(SUM(playDuration), 0) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long

    /**
     * Get total play count for a time range.
     */
    @Query("""
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getTotalPlayCount(startTime: Long, endTime: Long): Int

    /**
     * Get unique tracks count for a time range.
     */
    @Query("""
        SELECT COUNT(DISTINCT track_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getUniqueTracksCount(startTime: Long, endTime: Long): Int

    /**
     * Get unique artists count for a time range.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.artist) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """)
    suspend fun getUniqueArtistsCount(startTime: Long, endTime: Long): Int

    /**
     * Get unique albums count for a time range.
     * Excludes singles - only counts actual albums and EPs.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.album) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """)
    suspend fun getUniqueAlbumsCount(startTime: Long, endTime: Long): Int

    // =====================
    // Top Charts - Tracks
    // =====================

    /**
     * Get top tracks by play count with pagination.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopTracksByPlayCount(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopTrack>

    /**
     * Get top tracks by total listening time.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        ORDER BY total_time_ms DESC, play_count DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopTracksByTime(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopTrack>
    
    /**
     * Get top tracks by combined score (play count + time played).
     * Uses 50/50 weighting: normalized play_count + normalized total_time.
     * This provides a balanced ranking that considers both frequency and duration.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        WITH stats AS (
            SELECT 
                t.id as track_id,
                t.title,
                t.artist,
                t.album,
                COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                MIN(le.timestamp) as first_played,
                MAX(le.timestamp) as last_played,
                em.preview_url as preview_url
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            GROUP BY t.id
        ),
        max_values AS (
            SELECT 
                MAX(play_count) as max_plays,
                MAX(total_time_ms) as max_time
            FROM stats
        )
        SELECT 
            stats.*,
            -- Combined score: weighted average of normalized metrics
            -- 0.5 * (play_count / max_plays) + 0.5 * (total_time_ms / max_time)
            CASE 
                WHEN max_values.max_plays > 0 AND max_values.max_time > 0 THEN
                    (0.5 * CAST(stats.play_count AS REAL) / max_values.max_plays) + 
                    (0.5 * CAST(stats.total_time_ms AS REAL) / max_values.max_time)
                ELSE 0.0
            END as combined_score
        FROM stats
        CROSS JOIN max_values
        ORDER BY combined_score DESC, play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopTracksByCombinedScore(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopTrack>

    /**
     * Get total count of unique tracks played (for pagination).
     */
    @Query("""
        SELECT COUNT(DISTINCT track_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getUniqueTracksPlayedCount(startTime: Long, endTime: Long): Int

    // =====================
    // Top Charts - Artists
    // =====================

    /**
     * Get top artists by play count with image and country.
     * Uses proper JOIN through track_artists junction table when available,
     * falls back to string matching for unmigrated tracks.
     * Image URL comes from artists table or from spotify_artist_image_url in enriched_metadata.
     */
    @Query("""
        SELECT 
            a.id as artist_id,
            a.name as artist,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            COALESCE(a.image_url, 
                (SELECT em2.spotify_artist_image_url FROM enriched_metadata em2 
                 INNER JOIN track_artists ta2 ON ta2.track_id = em2.track_id
                 WHERE ta2.artist_id = a.id
                 AND em2.spotify_artist_image_url IS NOT NULL LIMIT 1)
            ) as image_url,
            COALESCE(a.country,
                (SELECT em.artist_country FROM enriched_metadata em 
                 INNER JOIN track_artists ta3 ON ta3.track_id = em.track_id
                 WHERE ta3.artist_id = a.id
                 AND em.artist_country IS NOT NULL LIMIT 1)
            ) as country
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY a.id
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopArtistsByPlayCount(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopArtist>

    /**
     * Get top artists by total listening time with image and country.
     * Uses proper JOIN through track_artists junction table when available.
     * Image URL comes from artists table or from spotify_artist_image_url in enriched_metadata.
     */
    @Query("""
        SELECT 
            a.id as artist_id,
            a.name as artist,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            COALESCE(a.image_url, 
                (SELECT em2.spotify_artist_image_url FROM enriched_metadata em2 
                 INNER JOIN track_artists ta2 ON ta2.track_id = em2.track_id
                 WHERE ta2.artist_id = a.id
                 AND em2.spotify_artist_image_url IS NOT NULL LIMIT 1)
            ) as image_url,
            COALESCE(a.country,
                (SELECT em.artist_country FROM enriched_metadata em 
                 INNER JOIN track_artists ta3 ON ta3.track_id = em.track_id
                 WHERE ta3.artist_id = a.id
                 AND em.artist_country IS NOT NULL LIMIT 1)
            ) as country
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY a.id
        ORDER BY total_time_ms DESC, play_count DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopArtistsByTime(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopArtist>

    /**
     * Get total count of unique artists (for pagination).
     * Uses track_artists junction table for proper count.
     */
    @Query("""
        SELECT COUNT(DISTINCT a.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """)
    suspend fun getUniqueArtistsPlayedCount(startTime: Long, endTime: Long): Int

    /**
     * Get all artist stats for post-processing (splitting multi-artist entries).
     * Returns all artist entries without pagination so they can be split and re-aggregated.
     */
    @Query("""
        SELECT 
            t.artist,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.artist
    """)
    suspend fun getAllArtistStatsRaw(startTime: Long, endTime: Long): List<RawArtistStats>
    
    /**
     * Get stats for a specific artist by ID.
     */
    @Query("""
        SELECT 
            a.id as artist_id,
            a.name as artist,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            a.image_url as image_url,
            a.country as country
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE a.id = :artistId
        AND le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY a.id
    """)
    suspend fun getArtistStatsById(artistId: Long, startTime: Long, endTime: Long): TopArtist?
    
    /**
     * Get tracks for an artist by artist ID with play stats.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId
        AND le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTracksByArtistId(artistId: Long, startTime: Long, endTime: Long, limit: Int): List<TopTrack>
    
    /**
     * Get all albums by an artist ID.
     */
    @Query("""
        SELECT DISTINCT 
            t.album,
            t.artist,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId
        AND le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        GROUP BY t.album
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getAlbumsByArtistId(artistId: Long, startTime: Long, endTime: Long, limit: Int): List<TopAlbum>

    // =====================
    // Top Charts - Albums
    // =====================

    /**
     * Get top albums by play count.
     * Excludes Singles - only includes releases marked as Album, EP, or unknown/null release types.
     */
    @Query("""
        SELECT 
            t.album,
            t.artist,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
        GROUP BY t.album, t.artist
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getTopAlbums(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TopAlbum>

    // =====================
    // Top Charts - Genres (from MusicBrainz tags)
    // =====================

    /**
     * Get top genres by play count.
     * Uses BOTH the genres field (from Spotify) and tags field (from MusicBrainz).
     * Prefers genres over tags since Spotify genre data is more reliable.
     * Both fields are stored as |||-delimited strings.
     */
    @Query("""
        SELECT 
            COALESCE(
                NULLIF(em.genres, ''),
                NULLIF(em.tags, '')
            ) as genre,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND (
            (em.genres IS NOT NULL AND em.genres != '' AND em.genres != '[]')
            OR (em.tags IS NOT NULL AND em.tags != '' AND em.tags != '[]')
        )
        GROUP BY genre
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopGenresRaw(
        startTime: Long,
        endTime: Long,
        limit: Int
    ): List<TopGenre>

    // =====================
    // Temporal Analysis - Hour of Day
    // =====================

    /**
     * Get listening distribution by hour of day.
     * Uses SQLite strftime to extract hour from timestamp.
     */
    @Query("""
        SELECT 
            CAST(strftime('%H', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY hour ASC
    """)
    suspend fun getHourlyDistribution(startTime: Long, endTime: Long): List<HourlyDistribution>

    /**
     * Get the most active hour of day.
     */
    @Query("""
        SELECT 
            CAST(strftime('%H', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY play_count DESC
        LIMIT 1
    """)
    suspend fun getMostActiveHour(startTime: Long, endTime: Long): HourlyDistribution?

    // =====================
    // Temporal Analysis - Day of Week
    // =====================

    /**
     * Get listening distribution by day of week.
     * SQLite strftime %w returns 0-6 (Sunday-Saturday), we convert to 1-7 (Monday-Sunday).
     */
    @Query("""
        SELECT 
            CASE 
                WHEN CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) = 0 THEN 7
                ELSE CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER)
            END as day_of_week,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY day_of_week
        ORDER BY day_of_week ASC
    """)
    suspend fun getDayOfWeekDistribution(startTime: Long, endTime: Long): List<DayOfWeekDistribution>

    /**
     * Get the most active day of week.
     */
    @Query("""
        SELECT 
            CASE 
                WHEN CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) = 0 THEN 7
                ELSE CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER)
            END as day_of_week,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY day_of_week
        ORDER BY play_count DESC
        LIMIT 1
    """)
    suspend fun getMostActiveDay(startTime: Long, endTime: Long): DayOfWeekDistribution?

    // =====================
    // Temporal Analysis - Daily Aggregations
    // =====================

    /**
     * Get daily listening aggregations.
     */
    @Query("""
        SELECT 
            strftime('%Y-%m-%d', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) as date,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY date
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun getDailyListening(startTime: Long, endTime: Long, limit: Int): List<DailyListening>

    // =====================
    // Temporal Analysis - Monthly Aggregations
    // =====================

    /**
     * Get monthly listening aggregations.
     */
    @Query("""
        SELECT 
            CAST(strftime('%Y', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as year,
            CAST(strftime('%m', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as month,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY year, month
        ORDER BY year DESC, month DESC
    """)
    suspend fun getMonthlyListening(startTime: Long, endTime: Long): List<MonthlyListening>

    // =====================
    // Listening Streaks
    // =====================

    /**
     * Get all unique listening dates for streak calculation.
     */
    @Query("""
        SELECT DISTINCT strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch', 'localtime')) as date
        FROM listening_events
        ORDER BY date ASC
    """)
    suspend fun getAllListeningDates(): List<String>

    /**
     * Get total number of active listening days.
     */
    @Query("""
        SELECT COUNT(DISTINCT strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch', 'localtime')))
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getActiveDaysCount(startTime: Long, endTime: Long): Int

    // =====================
    // Discovery Metrics
    // =====================

    /**
     * Get first listen timestamp for each artist.
     */
    @Query("""
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        GROUP BY t.artist
        ORDER BY first_listen_timestamp DESC
    """)
    suspend fun getArtistFirstListens(): List<FirstListen>

    /**
     * Get new artists discovered in a time range.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.artist)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.artist NOT IN (
            SELECT DISTINCT t2.artist
            FROM listening_events le2
            INNER JOIN tracks t2 ON le2.track_id = t2.id
            WHERE le2.timestamp < :startTime
        )
    """)
    suspend fun getNewArtistsCount(startTime: Long, endTime: Long): Int

    /**
     * Get new tracks discovered in a time range.
     */
    @Query("""
        SELECT COUNT(DISTINCT le.track_id)
        FROM listening_events le
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND le.track_id NOT IN (
            SELECT DISTINCT track_id
            FROM listening_events
            WHERE timestamp < :startTime
        )
    """)
    suspend fun getNewTracksCount(startTime: Long, endTime: Long): Int

    /**
     * Get repeat listens count (tracks played more than once in period).
     */
    @Query("""
        SELECT COUNT(*)
        FROM (
            SELECT track_id, COUNT(*) as cnt
            FROM listening_events
            WHERE timestamp >= :startTime AND timestamp <= :endTime
            GROUP BY track_id
            HAVING cnt > 1
        )
    """)
    suspend fun getRepeatTracksCount(startTime: Long, endTime: Long): Int

    /**
     * Get artist loyalty metrics.
     */
    @Query("""
        SELECT 
            t.artist,
            COUNT(le.id) as total_plays,
            SUM(CASE WHEN le.id > first_le.first_id THEN 1 ELSE 0 END) as repeat_plays,
            COUNT(DISTINCT t.id) as unique_tracks_played,
            MIN(le.timestamp) as first_listen,
            CAST((julianday('now') - julianday(datetime(MIN(le.timestamp)/1000, 'unixepoch'))) AS INTEGER) as days_since_first_listen
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN (
            SELECT track_id, MIN(id) as first_id
            FROM listening_events
            GROUP BY track_id
        ) first_le ON le.track_id = first_le.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.artist
        HAVING total_plays >= :minPlays
        ORDER BY total_plays DESC
        LIMIT :limit
    """)
    suspend fun getArtistLoyalty(
        startTime: Long,
        endTime: Long,
        minPlays: Int,
        limit: Int
    ): List<ArtistLoyalty>

    /**
     * Get count of artists with play count greater than the specified value.
     * Used for calculating local percentile rank.
     */
    @Query("""
        SELECT COUNT(*) 
        FROM (
            SELECT COUNT(le.id) as play_count 
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            GROUP BY t.artist
            HAVING play_count > :playCount
        )
    """)
    suspend fun countArtistsWithPlayCountMoreThan(playCount: Int, startTime: Long, endTime: Long): Int

    /**
     * Get track rank based on play count (All Time).
     * Returns the rank (1-based).
     */
    @Query("""
        SELECT COUNT(*) + 1
        FROM (
            SELECT COUNT(le.id) as play_count
            FROM listening_events le
            GROUP BY le.track_id
            HAVING play_count > :playCount
        )
    """)
    suspend fun getTrackRankAllTime(playCount: Int): Int

    /**
     * Get the exact timestamp of the first listen for a specific artist.
     */
    @Query("""
        SELECT MIN(le.timestamp)
        FROM listening_events le
        INNER JOIN track_artists ta ON le.track_id = ta.track_id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistDiscoveryDate(artistId: Long): Long?

    // =====================
    // Engagement Metrics
    // =====================

    /**
     * Get average completion rate for a time range.
     */
    @Query("""
        SELECT AVG(completionPercentage) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getAverageCompletionRate(startTime: Long, endTime: Long): Double?

    /**
     * Get count of full listens (>80% completion).
     */
    @Query("""
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND completionPercentage >= 80
    """)
    suspend fun getFullListensCount(startTime: Long, endTime: Long): Int

    /**
     * Get count of skips using the new was_skipped column (more accurate).
     * Falls back to completion < 30% for older events.
     */
    @Query("""
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND (was_skipped = 1 OR completionPercentage < 30)
    """)
    suspend fun getSkipsCount(startTime: Long, endTime: Long): Int
    
    /**
     * Get count of replays using the new is_replay column.
     */
    @Query("""
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND is_replay = 1
    """)
    suspend fun getReplayCount(startTime: Long, endTime: Long): Int
    
    // =====================
    // Insights Queries
    // =====================

    /**
     * Binge Listening: Find artists played repeatedly in a short session.
     * Groups consecutive plays of the same artist within 3 hours.
     */
    @Query("""
        SELECT 
            t.artist,
            COUNT(*) as session_play_count,
            SUM(le.playDuration) as session_duration_ms,
            MIN(le.timestamp) as session_start
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND (le.timestamp - (
            SELECT le2.timestamp 
            FROM listening_events le2 
            WHERE le2.timestamp < le.timestamp 
            ORDER BY le2.timestamp DESC LIMIT 1
        )) < 10800000 -- 3 hours gap max break
        GROUP BY t.artist
        HAVING session_play_count >= 5 -- Minimum plays to count as binge
        ORDER BY session_play_count DESC
        LIMIT 5
    """)
    suspend fun getBingeListeningSessions(startTime: Long, endTime: Long): List<BingeSession>

    /**
     * Mood Analysis: Fetches audio features JSONs for aggregation.
     * We process this in the repository because SQLite JSON support varies.
     */
    @Query("""
        SELECT em.audio_features_json
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND em.audio_features_json IS NOT NULL
    """)
    suspend fun getMoodRawData(startTime: Long, endTime: Long): List<String>

    /**
     * Discovery Rate: Calculates new artist discovery over time periods.
     * Groups by month for long-term trend.
     */
    @Query("""
        SELECT 
            strftime('%Y-%m', datetime(min_timestamps.first_listen/1000, 'unixepoch', 'localtime')) as month,
            COUNT(min_timestamps.artist) as new_artists_count
        FROM (
            SELECT t.artist, MIN(le.timestamp) as first_listen
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            GROUP BY t.artist
        ) min_timestamps
        WHERE min_timestamps.first_listen >= :startTime 
        AND min_timestamps.first_listen <= :endTime
        GROUP BY month
        ORDER BY month DESC
    """)
    suspend fun getNewArtistDiscoveryTrend(startTime: Long, endTime: Long): List<DiscoveryTrend>

    
    /**
     * Get partial plays count (30-80% completion).
     */
    @Query("""
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND completionPercentage >= 30 AND completionPercentage < 80
    """)
    suspend fun getPartialPlaysCount(startTime: Long, endTime: Long): Int
    
    /**
     * Get average pause count per play.
     */
    @Query("""
        SELECT AVG(CAST(pause_count AS FLOAT)) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getAveragePauseCount(startTime: Long, endTime: Long): Float?
    
    /**
     * Get count of unique sessions.
     */
    @Query("""
        SELECT COUNT(DISTINCT session_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND session_id IS NOT NULL
    """)
    suspend fun getUniqueSessionsCount(startTime: Long, endTime: Long): Int

    /**
     * Get track completion statistics with enhanced metrics.
     */
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            AVG(le.completionPercentage) as average_completion,
            SUM(CASE WHEN le.completionPercentage >= 80 THEN 1 ELSE 0 END) as full_listens,
            SUM(CASE WHEN le.was_skipped = 1 OR le.completionPercentage < 30 THEN 1 ELSE 0 END) as skips,
            COUNT(le.id) as total_plays
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        HAVING total_plays >= :minPlays
        ORDER BY average_completion DESC
        LIMIT :limit
    """)
    suspend fun getTrackCompletionStats(
        startTime: Long,
        endTime: Long,
        minPlays: Int,
        limit: Int
    ): List<TrackCompletion>

    /**
     * Get most skipped tracks using the new was_skipped column.
     */
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            AVG(le.completionPercentage) as average_completion,
            SUM(CASE WHEN le.completionPercentage >= 80 THEN 1 ELSE 0 END) as full_listens,
            SUM(CASE WHEN le.was_skipped = 1 OR le.completionPercentage < 30 THEN 1 ELSE 0 END) as skips,
            COUNT(le.id) as total_plays
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        HAVING skips > 0
        ORDER BY skips DESC, average_completion ASC
        LIMIT :limit
    """)
    suspend fun getMostSkippedTracks(
        startTime: Long,
        endTime: Long,
        limit: Int
    ): List<TrackCompletion>
    
    /**
     * Get most replayed tracks.
     */
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            SUM(CASE WHEN le.is_replay = 1 THEN 1 ELSE 0 END) as replay_count,
            COUNT(le.id) as total_plays,
            AVG(le.completionPercentage) as average_completion
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY t.id
        HAVING replay_count > 0
        ORDER BY replay_count DESC
        LIMIT :limit
    """)
    suspend fun getMostReplayedTracks(
        startTime: Long,
        endTime: Long,
        limit: Int
    ): List<ReplayedTrackStats>

    /**
     * Get listening events ordered for binge detection.
     */
    @Query("""
        SELECT le.id, le.track_id, le.timestamp, t.artist, t.album
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        ORDER BY le.timestamp ASC
    """)
    suspend fun getEventsForBingeDetection(startTime: Long, endTime: Long): List<BingeDetectionEvent>
    
    /**
     * Get hourly completion stats for engagement analysis.
     */
    @Query("""
        SELECT 
            CAST(strftime('%H', datetime(timestamp / 1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
            AVG(completionPercentage) as avg_completion,
            SUM(CASE WHEN was_skipped = 1 OR completionPercentage < 30 THEN 1 ELSE 0 END) as skip_count
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY hour
    """)
    suspend fun getHourlyCompletionStats(startTime: Long, endTime: Long): List<HourlyCompletionStats>

    // =====================
    // Year-over-Year Comparison
    // =====================

    /**
     * Get stats for a specific year.
     */
    @Query("""
        SELECT 
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE strftime('%Y', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) = :year
    """)
    suspend fun getYearStats(year: String): YearStatsRaw

    // =====================
    // Spotify Audio Features Stats
    // =====================

    /**
     * Get average audio features for tracks in a time range.
     */
    @Query("""
        SELECT 
            AVG(CAST(json_extract(em.audio_features_json, '$.energy') AS REAL)) as avg_energy,
            AVG(CAST(json_extract(em.audio_features_json, '$.danceability') AS REAL)) as avg_danceability,
            AVG(CAST(json_extract(em.audio_features_json, '$.valence') AS REAL)) as avg_valence,
            AVG(CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL)) as avg_tempo,
            AVG(CAST(json_extract(em.audio_features_json, '$.acousticness') AS REAL)) as avg_acousticness,
            AVG(CAST(json_extract(em.audio_features_json, '$.instrumentalness') AS REAL)) as avg_instrumentalness,
            AVG(CAST(json_extract(em.audio_features_json, '$.speechiness') AS REAL)) as avg_speechiness,
            AVG(CAST(json_extract(em.audio_features_json, '$.loudness') AS REAL)) as avg_loudness,
            COUNT(DISTINCT le.track_id) as tracks_count
        FROM listening_events le
        INNER JOIN enriched_metadata em ON le.track_id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND em.audio_features_json IS NOT NULL
    """)
    suspend fun getAverageAudioFeatures(startTime: Long, endTime: Long): AudioFeaturesRaw?

    /**
     * Get mood trends over time (daily averages).
     */
    @Query("""
        SELECT 
            strftime('%Y-%m-%d', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) as date,
            AVG(CAST(json_extract(em.audio_features_json, '$.valence') AS REAL)) as avg_valence,
            AVG(CAST(json_extract(em.audio_features_json, '$.energy') AS REAL)) as avg_energy,
            AVG(CAST(json_extract(em.audio_features_json, '$.danceability') AS REAL)) as avg_danceability,
            COUNT(DISTINCT le.track_id) as track_count
        FROM listening_events le
        INNER JOIN enriched_metadata em ON le.track_id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND em.audio_features_json IS NOT NULL
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getMoodTrends(startTime: Long, endTime: Long): List<MoodTrend>

    /**
     * Get tempo distribution.
     */
    @Query("""
        SELECT 
            CASE 
                WHEN CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL) < 80 THEN 'Slow (<80 BPM)'
                WHEN CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL) < 100 THEN 'Moderate (80-100 BPM)'
                WHEN CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL) < 120 THEN 'Upbeat (100-120 BPM)'
                WHEN CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL) < 140 THEN 'Fast (120-140 BPM)'
                ELSE 'Very Fast (140+ BPM)'
            END as bucket_label,
            COUNT(DISTINCT le.track_id) as track_count,
            COUNT(le.id) as total_plays
        FROM listening_events le
        INNER JOIN enriched_metadata em ON le.track_id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND em.audio_features_json IS NOT NULL
        GROUP BY bucket_label
        ORDER BY track_count DESC
    """)
    suspend fun getTempoDistributionRaw(startTime: Long, endTime: Long): List<TempoDistributionRaw>


    // =====================
    // History
    // =====================

    /**
     * Get listening history with track metadata.
     */
    @Query("""
        SELECT 
            le.id,
            le.track_id,
            le.timestamp,
            le.playDuration,
            le.completionPercentage,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE 
            (:startTime IS NULL OR le.timestamp >= :startTime) AND
            (:endTime IS NULL OR le.timestamp <= :endTime) AND
            (:includeSkips = 1 OR (le.was_skipped = 0 AND le.completionPercentage >= 30)) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                t.title LIKE '%' || :searchQuery || '%' OR 
                t.artist LIKE '%' || :searchQuery || '%' OR 
                t.album LIKE '%' || :searchQuery || '%')
        ORDER BY le.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getHistory(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        limit: Int,
        offset: Int
    ): List<HistoryItem>

    // =====================
    // Single Entity Stats
    // =====================

    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackPlayCount(trackId: Long): Int

    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackTotalTime(trackId: Long): Long

    @Query("SELECT MIN(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackFirstPlayed(trackId: Long): Long?

    @Query("SELECT MAX(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackLastPlayed(trackId: Long): Long?
    
    // =====================
    // Optimized Track Engagement Queries
    // =====================
    
    /**
     * Get full engagement metrics for a track in a single optimized query.
     * Uses indexed columns (was_skipped, is_replay, pause_count) for efficiency.
     */
    @Query("""
        SELECT 
            :trackId as track_id,
            COUNT(*) as play_count,
            COALESCE(SUM(playDuration), 0) as total_listening_time_ms,
            AVG(completionPercentage) as average_completion_percent,
            SUM(CASE WHEN completionPercentage >= 80 THEN 1 ELSE 0 END) as full_plays_count,
            SUM(CASE WHEN completionPercentage >= 30 AND completionPercentage < 80 THEN 1 ELSE 0 END) as partial_plays_count,
            SUM(CASE WHEN was_skipped = 1 OR completionPercentage < 30 THEN 1 ELSE 0 END) as skips_count,
            SUM(CASE WHEN is_replay = 1 THEN 1 ELSE 0 END) as replay_count,
            MAX(timestamp) as last_played_timestamp,
            MIN(timestamp) as first_played_timestamp,
            AVG(CAST(pause_count AS FLOAT)) as average_pause_count,
            SUM(pause_count) as total_pause_count,
            COUNT(DISTINCT session_id) as unique_sessions_count
        FROM listening_events 
        WHERE track_id = :trackId
    """)
    suspend fun getTrackEngagementStats(trackId: Long): TrackEngagementStats?
    
    /**
     * Get skip count for a track using the new was_skipped column.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND (was_skipped = 1 OR completionPercentage < 30)")
    suspend fun getTrackSkipCount(trackId: Long): Int
    
    /**
     * Get replay count for a track using the new is_replay column.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND is_replay = 1")
    suspend fun getTrackReplayCount(trackId: Long): Int
    
    /**
     * Get full plays count (>80% completion) for a track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND completionPercentage >= 80")
    suspend fun getTrackFullPlaysCount(trackId: Long): Int
    
    /**
     * Get average completion percentage for a track.
     */
    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackAverageCompletion(trackId: Long): Float?
    
    /**
     * Get average pause count for a track.
     */
    @Query("SELECT AVG(CAST(pause_count AS FLOAT)) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackAveragePauseCount(trackId: Long): Float?
    
    /**
     * Get total pause count for a track.
     */
    @Query("SELECT COALESCE(SUM(pause_count), 0) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackTotalPauseCount(trackId: Long): Int

    @Query("""
        SELECT 
            strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch', 'localtime')) as date,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms,
            1 as unique_tracks,
            1 as unique_artists
        FROM listening_events
        WHERE track_id = :trackId AND timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getTrackListeningHistory(trackId: Long, startTime: Long, endTime: Long): List<DailyListening>

    @Query("""
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """)
    suspend fun getArtistPlayCount(artistName: String): Int

    @Query("""
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """)
    suspend fun getArtistTotalTime(artistName: String): Long

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.artist = :artistName
        GROUP BY t.id
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopTracksForArtist(artistName: String, limit: Int): List<TopTrack>

    @Query("""
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """)
    suspend fun getArtistFirstListen(artistName: String): FirstListen?

    // =====================
    // Extended Artist Stats
    // =====================

    /**
     * Get unique albums played for an artist.
     * Excludes singles - only counts actual albums and EPs.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.album)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.artist = :artistName AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """)
    suspend fun getArtistUniqueAlbumsPlayed(artistName: String): Int

    /**
     * Get unique tracks played for an artist.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """)
    suspend fun getArtistUniqueTracksPlayed(artistName: String): Int

    /**
     * Get average audio features for an artist's tracks.
     */
    @Query("""
        SELECT 
            AVG(CAST(json_extract(em.audio_features_json, '$.energy') AS REAL)) as avg_energy,
            AVG(CAST(json_extract(em.audio_features_json, '$.danceability') AS REAL)) as avg_danceability,
            AVG(CAST(json_extract(em.audio_features_json, '$.valence') AS REAL)) as avg_valence,
            AVG(CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL)) as avg_tempo,
            AVG(CAST(json_extract(em.audio_features_json, '$.acousticness') AS REAL)) as avg_acousticness,
            AVG(CAST(json_extract(em.audio_features_json, '$.instrumentalness') AS REAL)) as avg_instrumentalness,
            AVG(CAST(json_extract(em.audio_features_json, '$.speechiness') AS REAL)) as avg_speechiness,
            AVG(CAST(json_extract(em.audio_features_json, '$.loudness') AS REAL)) as avg_loudness,
            COUNT(*) as tracks_count
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE t.artist = :artistName AND em.audio_features_json IS NOT NULL
    """)
    suspend fun getArtistAudioFeatures(artistName: String): AudioFeaturesRaw?

    /**
     * Get first and last listened dates for an artist.
     */
    @Query("""
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """)
    suspend fun getArtistListeningDates(artistName: String): ArtistListeningDates?

    /**
     * Get peak listening hour for an artist.
     */
    @Query("""
        SELECT CAST(strftime('%H', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """)
    suspend fun getArtistPeakListeningHour(artistName: String): Int?

    /**
     * Get artist country from enriched metadata.
     * Searches for the artist name within multi-artist strings using LIKE.
     * Uses case-insensitive matching.
     */
    @Query("""
        SELECT em.artist_country
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE (LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%') 
              AND em.artist_country IS NOT NULL AND em.artist_country != ''
        LIMIT 1
    """)
    suspend fun getArtistCountry(artistName: String): String?
    
    /**
     * Get artist image URL from enriched metadata for PRIMARY artist.
     * Looks for tracks where this artist is the main/solo artist (not featured).
     * Uses case-insensitive matching.
     * Checks all artist image sources in priority order: Spotify > iTunes > Last.fm > Deezer
     */
    @Query("""
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName)
              AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                   OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """)
    suspend fun getArtistImageAsPrimaryArtist(artistName: String): String?
    
    /**
     * Get artist image URL from enriched metadata where artist is listed first.
     * For multi-artist tracks, the first artist usually has their image stored.
     * Uses case-insensitive matching.
     * Checks all artist image sources in priority order: Spotify > iTunes > Last.fm > Deezer
     */
    @Query("""
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE (
            -- Artist is first, followed by a separator
            LOWER(t.artist) LIKE LOWER(:artistName) || ',%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' &%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' and %'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' feat%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' ft%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' x %'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' /%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' +%'
        )
        AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
             OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """)
    suspend fun getArtistImageAsFirstArtist(artistName: String): String?
    
    /**
     * Get artist image URL from enriched metadata (fallback - any track containing artist).
     * Checks all artist image sources in priority order: Spotify > iTunes > Last.fm > Deezer
     */
    @Query("""
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE (
            -- Match artist at start of string (most reliable for primary artist)
            LOWER(t.artist) LIKE LOWER(:artistName) || ',%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' &%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' feat%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' ft%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' x %'
            -- Exact match (solo artist)
            OR LOWER(t.artist) = LOWER(:artistName)
        )
        AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
             OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """)
    suspend fun getArtistImageFromEnrichedMetadata(artistName: String): String?
    
    /**
     * Get artist image URL by artist ID from enriched metadata.
     * Uses the track_artists junction table to find tracks linked to this artist.
     * Checks all artist image sources in priority order: Spotify > iTunes > Last.fm > Deezer
     */
    @Query("""
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN track_artists ta ON ta.track_id = em.track_id
        WHERE ta.artist_id = :artistId
              AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                   OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """)
    suspend fun getArtistImageByArtistId(artistId: Long): String?

    /**
     * Get top albums for an artist.
     * Excludes singles - only returns actual albums and EPs.
     */
    @Query("""
        SELECT 
            t.album,
            t.artist,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.artist = :artistName AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
        GROUP BY t.album
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopAlbumsForArtist(artistName: String, limit: Int): List<TopAlbum>

    @Query("""
        SELECT COUNT(le.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.album = :albumName AND t.artist = :artistName
    """)
    suspend fun getAlbumPlayCount(albumName: String, artistName: String): Int

    @Query("""
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.album = :albumName AND t.artist = :artistName
    """)
    suspend fun getAlbumTotalTime(albumName: String, artistName: String): Long

    @Query("""
        SELECT 
            t.id,
            t.title,
            t.artist,
            t.album,
            t.duration,
            t.primary_artist_id,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            t.spotify_id,
            t.musicbrainz_id,
            COUNT(le.id) as play_count,
            COALESCE(SUM(le.playDuration), 0) as total_time_ms
        FROM tracks t
        LEFT JOIN listening_events le ON t.id = le.track_id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.album = :albumName AND t.artist = :artistName
        GROUP BY t.id
        ORDER BY t.title ASC
    """)
    suspend fun getTracksForAlbumWithStats(albumName: String, artistName: String): List<TrackWithStatsRaw>

    /**
     * Get audio features for a specific track.
     */
    @Query("""
        SELECT 
            CAST(json_extract(em.audio_features_json, '$.energy') AS REAL) as energy,
            CAST(json_extract(em.audio_features_json, '$.danceability') AS REAL) as danceability,
            CAST(json_extract(em.audio_features_json, '$.valence') AS REAL) as valence,
            CAST(json_extract(em.audio_features_json, '$.tempo') AS REAL) as tempo,
            CAST(json_extract(em.audio_features_json, '$.acousticness') AS REAL) as acousticness,
            CAST(json_extract(em.audio_features_json, '$.instrumentalness') AS REAL) as instrumentalness,
            CAST(json_extract(em.audio_features_json, '$.speechiness') AS REAL) as speechiness,
            CAST(json_extract(em.audio_features_json, '$.liveness') AS REAL) as liveness,
            CAST(json_extract(em.audio_features_json, '$.loudness') AS REAL) as loudness,
            CAST(json_extract(em.audio_features_json, '$.key') AS INTEGER) as `key`,
            CAST(json_extract(em.audio_features_json, '$.mode') AS INTEGER) as mode,
            CAST(json_extract(em.audio_features_json, '$.time_signature') AS INTEGER) as timeSignature
        FROM enriched_metadata em
        WHERE em.track_id = :trackId
        AND em.audio_features_json IS NOT NULL
        LIMIT 1
    """)
    suspend fun getTrackAudioFeaturesRaw(trackId: Long): TrackAudioFeaturesRaw?
    
    // =====================
    // Partial Match Artist Queries (for split multi-artist entries)
    // =====================
    
    /**
     * Get play count for artist using partial match (LIKE).
     * Matches artists in multi-artist strings like "Artist1, Artist2".
     * Uses case-insensitive matching.
     */
    @Query("""
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """)
    suspend fun getArtistPlayCountByPartialMatch(artistName: String): Int

    @Query("""
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """)
    suspend fun getArtistTotalTimeByPartialMatch(artistName: String): Long

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
        GROUP BY t.id
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopTracksForArtistPartialMatch(artistName: String, limit: Int): List<TopTrack>

    @Query("""
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """)
    suspend fun getArtistFirstListenPartialMatch(artistName: String): FirstListen?

    /**
     * Excludes singles - only counts actual albums and EPs.
     * Uses case-insensitive matching.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.album)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE (LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%')
              AND t.album IS NOT NULL AND t.album != ''
              AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """)
    suspend fun getArtistUniqueAlbumsPlayedPartialMatch(artistName: String): Int

    @Query("""
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """)
    suspend fun getArtistUniqueTracksPlayedPartialMatch(artistName: String): Int

    @Query("""
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """)
    suspend fun getArtistListeningDatesPartialMatch(artistName: String): ArtistListeningDates?

    @Query("""
        SELECT CAST(strftime('%H', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """)
    suspend fun getArtistPeakListeningHourPartialMatch(artistName: String): Int?

    /**
     * Excludes singles - only returns actual albums and EPs.
     * Uses case-insensitive matching.
     */
    @Query("""
        SELECT 
            t.album,
            t.artist,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE (LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%')
              AND t.album IS NOT NULL AND t.album != ''
              AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
        GROUP BY t.album
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopAlbumsForArtistPartialMatch(artistName: String, limit: Int): List<TopAlbum>
    
    // =====================================================
    // ARTIST ID-BASED QUERIES (using track_artists junction table)
    // These provide proper relational lookups using artist IDs
    // =====================================================

    /**
     * Get play count for an artist by ID using track_artists junction table.
     */
    @Query("""
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistPlayCountById(artistId: Long): Int
    
    /**
     * Get total listening time for an artist by ID.
     */
    @Query("""
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistTotalTimeById(artistId: Long): Long
    
    /**
     * Get top tracks for an artist by ID.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query("""
        SELECT 
            t.id as track_id,
            t.title,
            t.artist,
            t.album,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            MIN(le.timestamp) as first_played,
            MAX(le.timestamp) as last_played,
            em.preview_url as preview_url,
            NULL as combined_score
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId
        GROUP BY t.id
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopTracksForArtistById(artistId: Long, limit: Int): List<TopTrack>
    
    /**
     * Get first listen info for an artist by ID.
     */
    @Query("""
        SELECT 
            t.title as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistFirstListenById(artistId: Long): FirstListen?
    
    /**
     * Get unique albums played for an artist by ID.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.album)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """)
    suspend fun getArtistUniqueAlbumsPlayedById(artistId: Long): Int
    
    /**
     * Get unique tracks played for an artist by ID.
     */
    @Query("""
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistUniqueTracksPlayedById(artistId: Long): Int
    
    /**
     * Get listening date range for an artist by ID.
     */
    @Query("""
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """)
    suspend fun getArtistListeningDatesById(artistId: Long): ArtistListeningDates?
    
    /**
     * Get peak listening hour for an artist by ID.
     */
    @Query("""
        SELECT CAST(strftime('%H', datetime(le.timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """)
    suspend fun getArtistPeakListeningHourById(artistId: Long): Int?
    
    /**
     * Get top albums for an artist by ID.
     */
    @Query("""
        SELECT 
            t.album,
            t.artist,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
        GROUP BY t.album
        ORDER BY play_count DESC
        LIMIT :limit
    """)
    suspend fun getTopAlbumsForArtistById(artistId: Long, limit: Int): List<TopAlbum>
}

data class TrackWithStatsRaw(
    @Embedded val track: me.avinas.tempo.data.local.entities.Track,
    val play_count: Int,
    val total_time_ms: Long
)

/**
 * Raw data class for binge detection.
 */
data class BingeDetectionEvent(
    val id: Long,
    val track_id: Long,
    val timestamp: Long,
    val artist: String,
    val album: String?
) {
    val trackId: Long get() = track_id
}

/**
 * Hourly completion stats for engagement analysis.
 */
data class HourlyCompletionStats(
    val hour: Int,
    val avg_completion: Float,
    val skip_count: Int
) {
    val avgCompletion: Float get() = avg_completion
    val skipCount: Int get() = skip_count
}

/**
 * Item for history list.
 */
data class HistoryItem(
    val id: Long,
    val track_id: Long,
    val timestamp: Long,
    val playDuration: Long,
    val completionPercentage: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val album_art_url: String?
)

/**
 * Raw year stats.
 */
data class YearStatsRaw(
    val play_count: Int,
    val total_time_ms: Long,
    val unique_artists: Int
)

/**
 * Raw audio features averages.
 */
data class AudioFeaturesRaw(
    val avg_energy: Float?,
    val avg_danceability: Float?,
    val avg_valence: Float?,
    val avg_tempo: Float?,
    val avg_acousticness: Float?,
    val avg_instrumentalness: Float?,
    val avg_speechiness: Float?,
    val avg_loudness: Float?,
    val tracks_count: Int
)

/**
 * Raw tempo distribution.
 */
data class TempoDistributionRaw(
    val bucket_label: String,
    val track_count: Int,
    val total_plays: Int
)

/**
 * Raw audio features for a single track.
 */
data class TrackAudioFeaturesRaw(
    val energy: Float?,
    val danceability: Float?,
    val valence: Float?,
    val tempo: Float?,
    val acousticness: Float?,
    val instrumentalness: Float?,
    val speechiness: Float?,
    val liveness: Float?,
    val loudness: Float?,
    val key: Int?,
    val mode: Int?,
    val timeSignature: Int?
)

/**
 * Artist listening date range.
 */
data class ArtistListeningDates(
    val first_listened: Long?,
    val last_listened: Long?
)
