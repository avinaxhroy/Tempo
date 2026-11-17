package me.avinas.tempo.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.RoomWarnings
import me.avinas.tempo.data.local.LocalTimeSql
import me.avinas.tempo.data.stats.*

/**
 * Listening statistics, aggregated in SQL rather than in Kotlin.
 *
 * QUERY_MISMATCH is suppressed for the whole DAO: every row type here (`TopTrack`,
 * `TopArtist`, `TopAlbum`) carries a `rank` property that no query returns. Rank is a
 * search-only, in-memory value assigned with `copy()` after the query runs, so the
 * projection warning is structural, not a mistake. `@Ignore` on the property is not an
 * option — Room's KSP processor rejects a constructor `val` marked ignored ("Entities and
 * data classes must have a usable public constructor"), which is why the warning is
 * silenced here instead.
 */
@SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
@Dao
interface StatsDao {
    // Combined Stats Query (Single Round Trip)

    /**
     * Total time, play count and distinct track/artist/album counts in one round trip.
     */
    @Query(
        """
        SELECT 
            COALESCE(SUM(le.playDuration), 0) as total_time_ms,
            COUNT(le.id) as play_count,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists,
            COUNT(DISTINCT CASE WHEN t.album IS NOT NULL THEN t.album END) as unique_albums
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """,
    )
    suspend fun getCombinedBasicStats(
        startTime: Long,
        endTime: Long,
    ): CombinedBasicStats

    // Basic Stats Queries

    @Query(
        """
        SELECT COALESCE(SUM(playDuration), 0) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getTotalListeningTime(
        startTime: Long,
        endTime: Long,
    ): Long

    @Query(
        """
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getTotalPlayCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(DISTINCT track_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getUniqueTracksCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(DISTINCT t.artist) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """,
    )
    suspend fun getUniqueArtistsCount(
        startTime: Long,
        endTime: Long,
    ): Int

    /**
     * Excludes singles - only counts actual albums and EPs.
     * Also requires an album to have 2+ distinct tracked songs, so a best-of
     * or compilation that only captured 1 of your scrobbled songs doesn't count.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT t.album
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL
            AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
            GROUP BY t.album
            HAVING COUNT(DISTINCT t.id) > 1
        )
    """,
    )
    suspend fun getUniqueAlbumsCount(
        startTime: Long,
        endTime: Long,
    ): Int

    // Top Charts - Tracks

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksByPlayCount(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksByTime(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    /** 50/50 weighting of normalized play_count and normalized total_time. */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksByCombinedScore(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT COUNT(DISTINCT track_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getUniqueTracksPlayedCount(
        startTime: Long,
        endTime: Long,
    ): Int

    // Top Charts - Artists

    /**
     * Joins through track_artists where available, falling back to string matching for
     * unmigrated tracks.
     *
     * Image URL priority:
     * 1. artists.image_url (always correct for specific artist)
     * 2. enriched_metadata fallback ONLY when artist is PRIMARY (prevents featured artist getting main artist's image)
     */
    @Query(
        """
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
                 AND ta2.role = 'PRIMARY'
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
    """,
    )
    suspend fun getTopArtistsByPlayCount(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopArtist>

    /**
     * Joins through track_artists where available.
     *
     * Image URL priority:
     * 1. artists.image_url (always correct for specific artist)
     * 2. enriched_metadata fallback ONLY when artist is PRIMARY (prevents featured artist getting main artist's image)
     */
    @Query(
        """
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
                 AND ta2.role = 'PRIMARY'
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
    """,
    )
    suspend fun getTopArtistsByTime(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopArtist>

    @Query(
        """
        SELECT COUNT(DISTINCT a.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
    """,
    )
    suspend fun getUniqueArtistsPlayedCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
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
    """,
    )
    suspend fun getAllArtistStatsRaw(
        startTime: Long,
        endTime: Long,
    ): List<RawArtistStats>

    @Query(
        """
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
    """,
    )
    suspend fun getArtistStatsById(
        artistId: Long,
        startTime: Long,
        endTime: Long,
    ): TopArtist?

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTracksByArtistId(
        artistId: Long,
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT DISTINCT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
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
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC
        LIMIT :limit
    """,
    )
    suspend fun getAlbumsByArtistId(
        artistId: Long,
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<TopAlbum>

    // Top Charts - Albums

    /**
     * Excludes Singles - only includes releases marked as Album, EP, or unknown/null release types.
     */
    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
        GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopAlbums(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int,
    ): List<TopAlbum>

    // Top Charts - Genres (from MusicBrainz tags)

    /**
     * Both the `genres` field (Spotify) and `tags` field (MusicBrainz) are read; genres win,
     * being the more reliable source. Both are `|||`-delimited.
     */
    @Query(
        """
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
    """,
    )
    suspend fun getTopGenresRaw(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<TopGenre>

    // Temporal Analysis - Hour of Day

    @Query(
        """
        SELECT 
            CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER) as hour,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY hour ASC
    """,
    )
    suspend fun getHourlyDistribution(
        startTime: Long,
        endTime: Long,
    ): List<HourlyDistribution>

    @Query(
        """
        SELECT 
            CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER) as hour,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY play_count DESC
        LIMIT 1
    """,
    )
    suspend fun getMostActiveHour(
        startTime: Long,
        endTime: Long,
    ): HourlyDistribution?

    /**
     * Average of each day's earliest listen: when the user typically *starts* listening,
     * not when they are most active overall.
     */
    @Query(
        """
        SELECT
            CAST(AVG(first_hour) AS INTEGER) as hour,
            COUNT(*) as play_count,
            0 as total_time_ms
        FROM (
            SELECT
                MIN(CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER)) as first_hour
            FROM listening_events
            WHERE timestamp >= :startTime AND timestamp <= :endTime
            GROUP BY strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME})
        )
    """,
    )
    suspend fun getTypicalStartHour(
        startTime: Long,
        endTime: Long,
    ): HourlyDistribution?

    // Temporal Analysis - Day of Week

    /**
     * SQLite strftime %w returns 0-6 (Sunday-Saturday), we convert to 1-7 (Monday-Sunday).
     */
    @Query(
        """
        SELECT 
            CASE 
                WHEN CAST(strftime('%w', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER) = 0 THEN 7
                ELSE CAST(strftime('%w', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER)
            END as day_of_week,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY day_of_week
        ORDER BY day_of_week ASC
    """,
    )
    suspend fun getDayOfWeekDistribution(
        startTime: Long,
        endTime: Long,
    ): List<DayOfWeekDistribution>

    @Query(
        """
        SELECT 
            CASE 
                WHEN CAST(strftime('%w', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER) = 0 THEN 7
                ELSE CAST(strftime('%w', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER)
            END as day_of_week,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY day_of_week
        ORDER BY play_count DESC
        LIMIT 1
    """,
    )
    suspend fun getMostActiveDay(
        startTime: Long,
        endTime: Long,
    ): DayOfWeekDistribution?

    // Temporal Analysis - Daily Aggregations

    @Query(
        """
        SELECT 
            strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME_LE}) as date,
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
    """,
    )
    suspend fun getDailyListening(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<DailyListening>

    // Temporal Analysis - Monthly Aggregations

    @Query(
        """
        SELECT 
            CAST(strftime('%Y', ${LocalTimeSql.LOCAL_DATETIME_LE}) AS INTEGER) as year,
            CAST(strftime('%m', ${LocalTimeSql.LOCAL_DATETIME_LE}) AS INTEGER) as month,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        GROUP BY year, month
        ORDER BY year DESC, month DESC
    """,
    )
    suspend fun getMonthlyListening(
        startTime: Long,
        endTime: Long,
    ): List<MonthlyListening>

    // Listening Streaks

    @Query(
        """
        SELECT DISTINCT strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME}) as date
        FROM listening_events
        ORDER BY date ASC
    """,
    )
    suspend fun getAllListeningDates(): List<String>

    /**
     * Timezone offset of the most recent play, used to anchor "today" for the streak.
     *
     * Counting against the device's date alone drops the newest day when a traveller's recorded
     * day is ahead of the phone's (flying west) and can skip one when it is behind (flying east).
     * Indexed by `index_listening_events_timestamp_track_id`, so this is a single-row lookup.
     */
    @Query("SELECT tz_offset_minutes FROM listening_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestListeningOffsetMinutes(): Int?

    @Query(
        """
        SELECT COUNT(DISTINCT strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME}))
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getActiveDaysCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT SUM(playDuration)
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME})
        ORDER BY SUM(playDuration) DESC
        LIMIT 1
    """,
    )
    suspend fun getPeakDayListeningMs(
        startTime: Long,
        endTime: Long,
    ): Long?

    // Discovery Metrics

    @Query(
        """
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        GROUP BY t.artist
        ORDER BY first_listen_timestamp DESC
    """,
    )
    suspend fun getArtistFirstListens(): List<FirstListen>

    @Query(
        """
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
    """,
    )
    suspend fun getNewArtistsCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(DISTINCT le.track_id)
        FROM listening_events le
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND le.track_id NOT IN (
            SELECT DISTINCT track_id
            FROM listening_events
            WHERE timestamp < :startTime
        )
    """,
    )
    suspend fun getNewTracksCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM (
            SELECT track_id, COUNT(*) as cnt
            FROM listening_events
            WHERE timestamp >= :startTime AND timestamp <= :endTime
            GROUP BY track_id
            HAVING cnt > 1
        )
    """,
    )
    suspend fun getRepeatTracksCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
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
    """,
    )
    suspend fun getArtistLoyalty(
        startTime: Long,
        endTime: Long,
        minPlays: Int,
        limit: Int,
    ): List<ArtistLoyalty>

    @Query(
        """
        SELECT COUNT(*) 
        FROM (
            SELECT COUNT(le.id) as play_count 
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            GROUP BY t.artist
            HAVING play_count > :playCount
        )
    """,
    )
    suspend fun countArtistsWithPlayCountMoreThan(
        playCount: Int,
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) + 1
        FROM (
            SELECT COUNT(le.id) as play_count
            FROM listening_events le
            GROUP BY le.track_id
            HAVING play_count > :playCount
        )
    """,
    )
    suspend fun getTrackRankAllTime(playCount: Int): Int

    @Query(
        """
        SELECT MIN(le.timestamp)
        FROM listening_events le
        INNER JOIN track_artists ta ON le.track_id = ta.track_id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistDiscoveryDate(artistId: Long): Long?

    // Engagement Metrics

    @Query(
        """
        SELECT AVG(completionPercentage) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getAverageCompletionRate(
        startTime: Long,
        endTime: Long,
    ): Double?

    @Query(
        """
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND completionPercentage >= 80
    """,
    )
    suspend fun getFullListensCount(
        startTime: Long,
        endTime: Long,
    ): Int

    /**
     * Falls back to completion < 30% for events that predate `was_skipped`.
     */
    @Query(
        """
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND (was_skipped = 1 OR completionPercentage < 30)
    """,
    )
    suspend fun getSkipsCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND is_replay = 1
    """,
    )
    suspend fun getReplayCount(
        startTime: Long,
        endTime: Long,
    ): Int

    // Insights Queries

    /**
     * Artists played repeatedly in a short session: consecutive plays of the same artist
     * within 3 hours.
     */
    @Query(
        """
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
    """,
    )
    suspend fun getBingeListeningSessions(
        startTime: Long,
        endTime: Long,
    ): List<BingeSession>

    /**
     * Audio features JSONs for aggregation, processed in the repository because SQLite JSON
     * support varies.
     */
    @Query(
        """
        SELECT 
            em.audio_features_json,
            em.tags,
            em.genres
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND (
            em.audio_features_json IS NOT NULL 
            OR (em.tags IS NOT NULL AND em.tags != '' AND em.tags != '[]')
            OR (em.genres IS NOT NULL AND em.genres != '' AND em.genres != '[]')
        )
    """,
    )
    suspend fun getMoodRawData(
        startTime: Long,
        endTime: Long,
    ): List<MoodRawData>

    /**
     * New artist discovery over time, grouped by month for the long-term trend.
     */
    @Query(
        """
        SELECT 
            ${LocalTimeSql.LOCAL_DISCOVERY_MONTH} as month,
            COUNT(min_timestamps.artist) as new_artists_count
        FROM (
            SELECT t.artist, MIN(le.timestamp) as first_listen, le.tz_offset_minutes
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            GROUP BY t.artist
        ) min_timestamps
        WHERE min_timestamps.first_listen >= :startTime 
        AND min_timestamps.first_listen <= :endTime
        GROUP BY month
        ORDER BY month DESC
    """,
    )
    suspend fun getNewArtistDiscoveryTrend(
        startTime: Long,
        endTime: Long,
    ): List<DiscoveryTrend>

    @Query(
        """
        SELECT COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND completionPercentage >= 30 AND completionPercentage < 80
    """,
    )
    suspend fun getPartialPlaysCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
        SELECT AVG(CAST(pause_count AS FLOAT)) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """,
    )
    suspend fun getAveragePauseCount(
        startTime: Long,
        endTime: Long,
    ): Float?

    @Query(
        """
        SELECT COUNT(DISTINCT session_id) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        AND session_id IS NOT NULL
    """,
    )
    suspend fun getUniqueSessionsCount(
        startTime: Long,
        endTime: Long,
    ): Int

    @Query(
        """
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
    """,
    )
    suspend fun getTrackCompletionStats(
        startTime: Long,
        endTime: Long,
        minPlays: Int,
        limit: Int,
    ): List<TrackCompletion>

    @Query(
        """
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
    """,
    )
    suspend fun getMostSkippedTracks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<TrackCompletion>

    @Query(
        """
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
    """,
    )
    suspend fun getMostReplayedTracks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<ReplayedTrackStats>

    @Query(
        """
        SELECT le.id, le.track_id, le.timestamp, t.artist, t.album
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        ORDER BY le.timestamp ASC
    """,
    )
    suspend fun getEventsForBingeDetection(
        startTime: Long,
        endTime: Long,
    ): List<BingeDetectionEvent>

    @Query(
        """
        SELECT 
            CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME}) AS INTEGER) as hour,
            AVG(completionPercentage) as avg_completion,
            SUM(CASE WHEN was_skipped = 1 OR completionPercentage < 30 THEN 1 ELSE 0 END) as skip_count
        FROM listening_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY hour
        ORDER BY hour
    """,
    )
    suspend fun getHourlyCompletionStats(
        startTime: Long,
        endTime: Long,
    ): List<HourlyCompletionStats>

    // Year-over-Year Comparison

    @Query(
        """
        SELECT 
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.artist) as unique_artists
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE strftime('%Y', ${LocalTimeSql.LOCAL_DATETIME_LE}) = :year
    """,
    )
    suspend fun getYearStats(year: String): YearStatsRaw

    // Spotify Audio Features Stats

    @Query(
        """
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
    """,
    )
    suspend fun getAverageAudioFeatures(
        startTime: Long,
        endTime: Long,
    ): AudioFeaturesRaw?

    @Query(
        """
        SELECT 
            strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME_LE}) as date,
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
    """,
    )
    suspend fun getMoodTrends(
        startTime: Long,
        endTime: Long,
    ): List<MoodTrend>

    @Query(
        """
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
    """,
    )
    suspend fun getTempoDistributionRaw(
        startTime: Long,
        endTime: Long,
    ): List<TempoDistributionRaw>

    // History

    /** Listening history with track metadata, optionally excluding podcast/audiobook content. */
    @Query(
        """
        SELECT 
            le.id,
            le.track_id,
            le.timestamp,
            le.playDuration,
            le.completionPercentage,
            t.title,
            t.artist,
            t.album,
            t.content_type,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            le.source
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
                t.album LIKE '%' || :searchQuery || '%') AND
            (:filterPodcasts = 0 OR t.content_type != 'PODCAST') AND
            (:filterAudiobooks = 0 OR t.content_type != 'AUDIOBOOK')
        ORDER BY le.timestamp DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getHistory(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        limit: Int,
        offset: Int,
    ): List<HistoryItem>

    /**
     * Excludes Last.fm imported events: only live activity from Spotify/notification tracking,
     * for the "Recent Activity" section when a Last.fm import exists.
     */
    @Query(
        """
        SELECT 
            le.id,
            le.track_id,
            le.timestamp,
            le.playDuration,
            le.completionPercentage,
            t.title,
            t.artist,
            t.album,
            t.content_type,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            le.source
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE 
            le.source != 'fm.last.import' AND
            (:startTime IS NULL OR le.timestamp >= :startTime) AND
            (:endTime IS NULL OR le.timestamp <= :endTime) AND
            (:includeSkips = 1 OR (le.was_skipped = 0 AND le.completionPercentage >= 30)) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                t.title LIKE '%' || :searchQuery || '%' OR 
                t.artist LIKE '%' || :searchQuery || '%' OR 
                t.album LIKE '%' || :searchQuery || '%') AND
            (:filterPodcasts = 0 OR t.content_type != 'PODCAST') AND
            (:filterAudiobooks = 0 OR t.content_type != 'AUDIOBOOK')
        ORDER BY le.timestamp DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getHistoryExcludingLastFm(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        limit: Int,
        offset: Int,
    ): List<HistoryItem>

    /** Only Last.fm imported events: the "active set" (top/loved tracks) for "Last.fm History". */
    @Query(
        """
        SELECT 
            le.id,
            le.track_id,
            le.timestamp,
            le.playDuration,
            le.completionPercentage,
            t.title,
            t.artist,
            t.album,
            t.content_type,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            le.source
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE 
            le.source = 'fm.last.import' AND
            (:startTime IS NULL OR le.timestamp >= :startTime) AND
            (:endTime IS NULL OR le.timestamp <= :endTime) AND
            (:includeSkips = 1 OR (le.was_skipped = 0 AND le.completionPercentage >= 30)) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                t.title LIKE '%' || :searchQuery || '%' OR 
                t.artist LIKE '%' || :searchQuery || '%' OR 
                t.album LIKE '%' || :searchQuery || '%') AND
            (:filterPodcasts = 0 OR t.content_type != 'PODCAST') AND
            (:filterAudiobooks = 0 OR t.content_type != 'AUDIOBOOK')
        ORDER BY le.timestamp DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getHistoryLastFmOnly(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        includeSkips: Boolean = true,
        filterPodcasts: Boolean = true,
        filterAudiobooks: Boolean = true,
        limit: Int,
        offset: Int,
    ): List<HistoryItem>

    @Query(
        """
        SELECT COUNT(*) FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE 
            le.source = 'fm.last.import' AND
            (:startTime IS NULL OR le.timestamp >= :startTime) AND
            (:endTime IS NULL OR le.timestamp <= :endTime) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                t.title LIKE '%' || :searchQuery || '%' OR 
                t.artist LIKE '%' || :searchQuery || '%' OR 
                t.album LIKE '%' || :searchQuery || '%')
    """,
    )
    suspend fun countLastFmHistory(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE 
            le.source != 'fm.last.import' AND
            (:startTime IS NULL OR le.timestamp >= :startTime) AND
            (:endTime IS NULL OR le.timestamp <= :endTime) AND
            (:searchQuery IS NULL OR :searchQuery = '' OR 
                t.title LIKE '%' || :searchQuery || '%' OR 
                t.artist LIKE '%' || :searchQuery || '%' OR 
                t.album LIKE '%' || :searchQuery || '%')
    """,
    )
    suspend fun countLiveHistory(
        searchQuery: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
    ): Int

    // Single Entity Stats

    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackPlayCount(trackId: Long): Int

    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackTotalTime(trackId: Long): Long

    @Query("SELECT MIN(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackFirstPlayed(trackId: Long): Long?

    @Query("SELECT MAX(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackLastPlayed(trackId: Long): Long?

    // Optimized Track Engagement Queries

    /**
     * Reads the indexed `was_skipped`, `is_replay` and `pause_count` columns in one pass.
     */
    @Query(
        """
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
    """,
    )
    suspend fun getTrackEngagementStats(trackId: Long): TrackEngagementStats?

    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND (was_skipped = 1 OR completionPercentage < 30)")
    suspend fun getTrackSkipCount(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND is_replay = 1")
    suspend fun getTrackReplayCount(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND completionPercentage >= 80")
    suspend fun getTrackFullPlaysCount(trackId: Long): Int

    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackAverageCompletion(trackId: Long): Float?

    @Query("SELECT AVG(CAST(pause_count AS FLOAT)) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackAveragePauseCount(trackId: Long): Float?

    @Query("SELECT COALESCE(SUM(pause_count), 0) FROM listening_events WHERE track_id = :trackId")
    suspend fun getTrackTotalPauseCount(trackId: Long): Int

    @Query(
        """
        SELECT 
            strftime('%Y-%m-%d', ${LocalTimeSql.LOCAL_DATETIME}) as date,
            COUNT(*) as play_count,
            SUM(playDuration) as total_time_ms,
            1 as unique_tracks,
            1 as unique_artists
        FROM listening_events
        WHERE track_id = :trackId AND timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY date
        ORDER BY date ASC
    """,
    )
    suspend fun getTrackListeningHistory(
        trackId: Long,
        startTime: Long,
        endTime: Long,
    ): List<DailyListening>

    @Query(
        """
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """,
    )
    suspend fun getArtistPlayCount(artistName: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """,
    )
    suspend fun getArtistTotalTime(artistName: String): Long

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksForArtist(
        artistName: String,
        limit: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """,
    )
    suspend fun getArtistFirstListen(artistName: String): FirstListen?

    // Extended Artist Stats

    /**
     * Excludes singles - only counts actual albums and EPs.
     * Also requires an album to have 2+ distinct tracked songs, so a best-of
     * or compilation that only captured 1 of your scrobbled songs doesn't count.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT t.album
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE t.artist = :artistName AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
            GROUP BY t.album
            HAVING COUNT(DISTINCT t.id) > 1
        )
    """,
    )
    suspend fun getArtistUniqueAlbumsPlayed(artistName: String): Int

    @Query(
        """
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """,
    )
    suspend fun getArtistUniqueTracksPlayed(artistName: String): Int

    @Query(
        """
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
    """,
    )
    suspend fun getArtistAudioFeatures(artistName: String): AudioFeaturesRaw?

    @Query(
        """
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
    """,
    )
    suspend fun getArtistListeningDates(artistName: String): ArtistListeningDates?

    @Query(
        """
        SELECT CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME_LE}) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.artist = :artistName
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """,
    )
    suspend fun getArtistPeakListeningHour(artistName: String): Int?

    /** Searches the artist name inside multi-artist strings with `LIKE`. */
    @Query(
        """
        SELECT em.artist_country
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE (LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%') 
              AND em.artist_country IS NOT NULL AND em.artist_country != ''
        LIMIT 1
    """,
    )
    suspend fun getArtistCountry(artistName: String): String?

    @Query(
        """
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON a.id = ta.artist_id
        WHERE LOWER(a.name) = LOWER(:artistName)
              AND ta.role = 'PRIMARY'
              AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                   OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """,
    )
    suspend fun getArtistImageAsPrimaryArtist(artistName: String): String?

    @Query(
        """
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON a.id = ta.artist_id
        WHERE (
            LOWER(a.name) = LOWER(:artistName)
            -- Artist is first, followed by a separator (legacy support)
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ',%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' &%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' and %'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' feat%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' ft%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' x %'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' /%'
            OR LOWER(t.artist) LIKE LOWER(:artistName) || ' +%'
        )
        AND ta.role = 'PRIMARY'
        AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
             OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """,
    )
    suspend fun getArtistImageAsFirstArtist(artistName: String): String?

    /**
     * Fallback artist image URL from enriched metadata for any PRIMARY artist track.
     * Checks sources in priority order: Spotify > iTunes > Last.fm > Deezer.
     */
    @Query(
        """
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON a.id = ta.artist_id
        WHERE LOWER(a.name) = LOWER(:artistName)
              AND ta.role = 'PRIMARY'
              AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                   OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """,
    )
    suspend fun getArtistImageFromEnrichedMetadata(artistName: String): String?

    /**
     * Only returns images from tracks where this artist is PRIMARY and the row's
     * `spotify_artist_id` matches this artist's `spotify_id`, which stops a mismatch such as
     * Spotify reporting KR$NA as primary for a KARMA track. Sources are checked in priority
     * order: Spotify > iTunes > Last.fm > Deezer.
     */
    @Query(
        """
        SELECT COALESCE(
            NULLIF(em.spotify_artist_image_url, ''),
            NULLIF(em.itunes_artist_image_url, ''),
            NULLIF(em.lastfm_artist_image_url, ''),
            NULLIF(em.deezer_artist_image_url, '')
        )
        FROM enriched_metadata em
        INNER JOIN track_artists ta ON ta.track_id = em.track_id
        INNER JOIN artists a ON a.id = ta.artist_id
        WHERE ta.artist_id = :artistId
              AND ta.role = 'PRIMARY'
              AND (
                  -- Verify: enriched_metadata's Spotify artist ID matches this artist's Spotify ID
                  -- This prevents returning another artist's image from a collab/mismatched track
                  (a.spotify_id IS NOT NULL AND em.spotify_artist_id IS NOT NULL AND em.spotify_artist_id = a.spotify_id)
                  -- OR: no Spotify IDs to compare, and this is the ONLY artist on the track
                  OR (a.spotify_id IS NULL AND (SELECT COUNT(*) FROM track_artists ta2 WHERE ta2.track_id = ta.track_id) = 1)
              )
              AND (em.spotify_artist_image_url IS NOT NULL OR em.itunes_artist_image_url IS NOT NULL 
                   OR em.lastfm_artist_image_url IS NOT NULL OR em.deezer_artist_image_url IS NOT NULL)
        LIMIT 1
    """,
    )
    suspend fun getArtistImageByArtistId(artistId: Long): String?

    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.artist = :artistName AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
        GROUP BY t.album
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC
        LIMIT :limit
    """,
    )
    suspend fun getTopAlbumsForArtist(
        artistName: String,
        limit: Int,
    ): List<TopAlbum>

    @Query(
        """
        SELECT COUNT(le.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.album = :albumName
        AND (t.primary_artist_id = :artistId OR LOWER(t.artist) = LOWER(:artistName))
    """,
    )
    suspend fun getAlbumPlayCount(
        albumName: String,
        artistName: String,
        artistId: Long,
    ): Int

    @Query(
        """
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE t.album = :albumName
        AND (t.primary_artist_id = :artistId OR LOWER(t.artist) = LOWER(:artistName))
    """,
    )
    suspend fun getAlbumTotalTime(
        albumName: String,
        artistName: String,
        artistId: Long,
    ): Long

    @Query(
        """
        SELECT 
            t.id,
            t.title,
            t.artist,
            t.album,
            t.duration,
            t.primary_artist_id,
            COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, '')) as album_art_url,
            t.spotify_id,
            t.youtube_id,
            t.musicbrainz_id,
            t.content_type,
            COUNT(le.id) as play_count,
            COALESCE(SUM(le.playDuration), 0) as total_time_ms
        FROM tracks t
        LEFT JOIN listening_events le ON t.id = le.track_id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE t.album = :albumName
        AND (t.primary_artist_id = :artistId OR LOWER(t.artist) = LOWER(:artistName))
        GROUP BY t.id
        ORDER BY t.title ASC
    """,
    )
    suspend fun getTracksForAlbumWithStats(
        albumName: String,
        artistName: String,
        artistId: Long,
    ): List<TrackWithStatsRaw>

    @Query(
        """
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
    """,
    )
    suspend fun getTrackAudioFeaturesRaw(trackId: Long): TrackAudioFeaturesRaw?

    // Partial Match Artist Queries (for split multi-artist entries)

    /** Partial match, so "Artist1, Artist2" strings are counted too. Case-insensitive. */
    @Query(
        """
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """,
    )
    suspend fun getArtistPlayCountByPartialMatch(artistName: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """,
    )
    suspend fun getArtistTotalTimeByPartialMatch(artistName: String): Long

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksForArtistPartialMatch(
        artistName: String,
        limit: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT 
            t.artist as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """,
    )
    suspend fun getArtistFirstListenPartialMatch(artistName: String): FirstListen?

    /**
     * Excludes singles - only counts actual albums and EPs.
     * Uses case-insensitive matching.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT t.album)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE (LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%')
              AND t.album IS NOT NULL AND t.album != ''
              AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """,
    )
    suspend fun getArtistUniqueAlbumsPlayedPartialMatch(artistName: String): Int

    @Query(
        """
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """,
    )
    suspend fun getArtistUniqueTracksPlayedPartialMatch(artistName: String): Int

    @Query(
        """
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
    """,
    )
    suspend fun getArtistListeningDatesPartialMatch(artistName: String): ArtistListeningDates?

    @Query(
        """
        SELECT CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME_LE}) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE LOWER(t.artist) = LOWER(:artistName) OR LOWER(t.artist) LIKE '%' || LOWER(:artistName) || '%'
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """,
    )
    suspend fun getArtistPeakListeningHourPartialMatch(artistName: String): Int?

    /**
     * Excludes singles - only returns actual albums and EPs.
     * Uses case-insensitive matching.
     */
    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
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
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC
        LIMIT :limit
    """,
    )
    suspend fun getTopAlbumsForArtistPartialMatch(
        artistName: String,
        limit: Int,
    ): List<TopAlbum>

    // ARTIST ID-BASED QUERIES (using track_artists junction table)
    // These provide proper relational lookups using artist IDs

    @Query(
        """
        SELECT COUNT(le.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistPlayCountById(artistId: Long): Int

    @Query(
        """
        SELECT ta.artist_id AS artistId, COUNT(le.id) AS playCount
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id IN (:artistIds)
        GROUP BY ta.artist_id
    """,
    )
    suspend fun getArtistPlayCountsByIds(artistIds: List<Long>): List<ArtistPlayCountResult>

    @Query(
        """
        SELECT COALESCE(SUM(le.playDuration), 0)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistTotalTimeById(artistId: Long): Long

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
    """,
    )
    suspend fun getTopTracksForArtistById(
        artistId: Long,
        limit: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT 
            t.title as name,
            MIN(le.timestamp) as first_listen_timestamp,
            'artist' as type
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        INNER JOIN artists a ON ta.artist_id = a.id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistFirstListenById(artistId: Long): FirstListen?

    @Query(
        """
        SELECT COUNT(DISTINCT t.album)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE ta.artist_id = :artistId AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR LOWER(em.release_type) != 'single')
    """,
    )
    suspend fun getArtistUniqueAlbumsPlayedById(artistId: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT t.id)
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistUniqueTracksPlayedById(artistId: Long): Int

    @Query(
        """
        SELECT MIN(le.timestamp) as first_listened, MAX(le.timestamp) as last_listened
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
    """,
    )
    suspend fun getArtistListeningDatesById(artistId: Long): ArtistListeningDates?

    @Query(
        """
        SELECT CAST(strftime('%H', ${LocalTimeSql.LOCAL_DATETIME_LE}) AS INTEGER) as hour
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        INNER JOIN track_artists ta ON ta.track_id = t.id
        WHERE ta.artist_id = :artistId
        GROUP BY hour
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """,
    )
    suspend fun getArtistPeakListeningHourById(artistId: Long): Int?

    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
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
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC
        LIMIT :limit
    """,
    )
    suspend fun getTopAlbumsForArtistById(
        artistId: Long,
        limit: Int,
    ): List<TopAlbum>

    // Content-Type Filtered Stats Queries
    // These exclude podcasts/audiobooks from stats when user has marked them

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.id
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopTracksByPlayCountFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.id
        ORDER BY total_time_ms DESC, play_count DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopTracksByTimeFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
                AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
                AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
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
    """,
    )
    suspend fun getTopTracksByCombinedScoreFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopTrack>

    @Query(
        """
        SELECT COUNT(DISTINCT t.id) 
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
    """,
    )
    suspend fun getUniqueTracksPlayedCountFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
    ): Int

    /**
     * Raw rows for splitting multi-artist entries and re-aggregating. Capped at the top N
     * artists by play count.
     */
    @Query(
        """
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
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.artist
        ORDER BY play_count DESC
        LIMIT :maxArtists
    """,
    )
    suspend fun getAllArtistStatsRawFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        maxArtists: Int = 500,
    ): List<RawArtistStats>

    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
        AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
        AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopAlbumsByPlayCountFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopAlbum>

    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
        AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
        AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY play_count DESC, total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopAlbumsFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopAlbum>

    @Query(
        """
        SELECT 
            t.album,
            COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
            COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
            COUNT(le.id) as play_count,
            SUM(le.playDuration) as total_time_ms,
            COUNT(DISTINCT t.id) as unique_tracks
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
        AND t.album IS NOT NULL AND t.album != ''
        AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
        AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
        AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
        GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
        HAVING COUNT(DISTINCT t.id) > 1
        ORDER BY total_time_ms DESC, play_count DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopAlbumsByTimeFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopAlbum>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
        WITH stats AS (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
                COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        ),
        max_values AS (
            SELECT 
                MAX(play_count) as max_plays,
                MAX(total_time_ms) as max_time
            FROM stats
        )
        SELECT 
            stats.*
        FROM stats
        CROSS JOIN max_values
        ORDER BY 
            (CASE 
                WHEN max_values.max_plays > 0 AND max_values.max_time > 0 THEN
                    (0.5 * CAST(stats.play_count AS REAL) / max_values.max_plays) + 
                    (0.5 * CAST(stats.total_time_ms AS REAL) / max_values.max_time)
                ELSE 0.0
            END) DESC,
            stats.play_count DESC,
            stats.total_time_ms DESC
        LIMIT :limit OFFSET :offset
    """,
    )
    suspend fun getTopAlbumsByCombinedScoreFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        limit: Int,
        offset: Int,
    ): List<TopAlbum>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        )
    """,
    )
    suspend fun getUniqueAlbumsCountFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
    ): Int

    @Query(
        """
        SELECT 
            COALESCE(SUM(le.playDuration), 0) as total_time_ms,
            COUNT(le.id) as play_count,
            COUNT(DISTINCT le.track_id) as unique_tracks,
            COUNT(DISTINCT t.artist) as unique_artists,
            COUNT(DISTINCT CASE WHEN t.album IS NOT NULL THEN t.album END) as unique_albums
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
    """,
    )
    suspend fun getCombinedBasicStatsFiltered(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
    ): CombinedBasicStats

    // Ranking Search Queries
    // Live-filter the full ranking by a text query and report each match's
    // GLOBAL rank. The stats CTE is materialized once; the match filter and
    // LIMIT are applied first, in the same tie-break order the rank counts, so
    // the correlated count only runs for the LIMIT-ed rows (limit × n, not
    // matches × n and not n²). No window functions: minSdk 26 devices
    // (API 26-28) ship SQLite older than 3.25.

    /** Rows come back ordered by rank ascending; the caller assigns rank = position + 1. */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
                AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
                AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.id
        ),
        top_matches AS (
            SELECT stats.*
            FROM stats
            WHERE stats.title LIKE '%' || :query || '%'
               OR stats.artist LIKE '%' || :query || '%'
               OR (stats.album IS NOT NULL AND stats.album LIKE '%' || :query || '%')
            ORDER BY stats.play_count DESC, stats.total_time_ms DESC, stats.track_id ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM stats s2
             WHERE s2.play_count > top_matches.play_count
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms > top_matches.total_time_ms)
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms = top_matches.total_time_ms AND s2.track_id < top_matches.track_id)
            ) + 1 as global_rank,
            NULL as combined_score
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopTracksByPlayCount(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopTrack>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
                AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
                AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.id
        ),
        top_matches AS (
            SELECT stats.*
            FROM stats
            WHERE stats.title LIKE '%' || :query || '%'
               OR stats.artist LIKE '%' || :query || '%'
               OR (stats.album IS NOT NULL AND stats.album LIKE '%' || :query || '%')
            ORDER BY stats.total_time_ms DESC, stats.play_count DESC, stats.track_id ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM stats s2
             WHERE s2.total_time_ms > top_matches.total_time_ms
                OR (s2.total_time_ms = top_matches.total_time_ms AND s2.play_count > top_matches.play_count)
                OR (s2.total_time_ms = top_matches.total_time_ms AND s2.play_count = top_matches.play_count AND s2.track_id < top_matches.track_id)
            ) + 1 as global_rank,
            NULL as combined_score
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopTracksByTime(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopTrack>

    /**
     * The combined score is normalized over the FULL ranking, same as the list view, so a
     * match's score and rank agree with what the unfiltered chart shows.
     */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
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
                AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
                AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.id
        ),
        max_values AS (
            SELECT 
                MAX(play_count) as max_plays,
                MAX(total_time_ms) as max_time
            FROM stats
        ),
        scored AS (
            SELECT 
                stats.*,
                CASE 
                    WHEN max_values.max_plays > 0 AND max_values.max_time > 0 THEN
                        (0.5 * CAST(stats.play_count AS REAL) / max_values.max_plays) + 
                        (0.5 * CAST(stats.total_time_ms AS REAL) / max_values.max_time)
                    ELSE 0.0
                END as combined_score
            FROM stats
            CROSS JOIN max_values
        ),
        top_matches AS (
            SELECT scored.*
            FROM scored
            WHERE scored.title LIKE '%' || :query || '%'
               OR scored.artist LIKE '%' || :query || '%'
               OR (scored.album IS NOT NULL AND scored.album LIKE '%' || :query || '%')
            ORDER BY scored.combined_score DESC, scored.play_count DESC, scored.total_time_ms DESC, scored.track_id ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM scored s2
             WHERE s2.combined_score > top_matches.combined_score
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count > top_matches.play_count)
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count = top_matches.play_count AND s2.total_time_ms > top_matches.total_time_ms)
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count = top_matches.play_count AND s2.total_time_ms = top_matches.total_time_ms AND s2.track_id < top_matches.track_id)
            ) + 1 as global_rank
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopTracksByCombinedScore(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopTrack>

    /** Same album eligibility rules as getTopAlbumsFiltered. */
    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
        WITH stats AS (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
                COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        ),
        top_matches AS (
            SELECT stats.*
            FROM stats
            WHERE stats.album LIKE '%' || :query || '%'
               OR stats.artist LIKE '%' || :query || '%'
            ORDER BY stats.play_count DESC, stats.total_time_ms DESC, stats.album ASC, stats.artist ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM stats s2
             WHERE s2.play_count > top_matches.play_count
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms > top_matches.total_time_ms)
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms = top_matches.total_time_ms AND (s2.album < top_matches.album OR (s2.album = top_matches.album AND s2.artist < top_matches.artist)))
            ) + 1 as global_rank
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopAlbumsByPlayCount(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopAlbum>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
        WITH stats AS (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
                COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        ),
        top_matches AS (
            SELECT stats.*
            FROM stats
            WHERE stats.album LIKE '%' || :query || '%'
               OR stats.artist LIKE '%' || :query || '%'
            ORDER BY stats.play_count DESC, stats.total_time_ms DESC, stats.album ASC, stats.artist ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM stats s2
             WHERE s2.play_count > top_matches.play_count
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms > top_matches.total_time_ms)
                OR (s2.play_count = top_matches.play_count AND s2.total_time_ms = top_matches.total_time_ms AND (s2.album < top_matches.album OR (s2.album = top_matches.album AND s2.artist < top_matches.artist)))
            ) + 1 as global_rank
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopAlbums(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopAlbum>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
        WITH stats AS (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
                COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        ),
        top_matches AS (
            SELECT stats.*
            FROM stats
            WHERE stats.album LIKE '%' || :query || '%'
               OR stats.artist LIKE '%' || :query || '%'
            ORDER BY stats.total_time_ms DESC, stats.play_count DESC, stats.album ASC, stats.artist ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.*,
            (SELECT COUNT(*) FROM stats s2
             WHERE s2.total_time_ms > top_matches.total_time_ms
                OR (s2.total_time_ms = top_matches.total_time_ms AND s2.play_count > top_matches.play_count)
                OR (s2.total_time_ms = top_matches.total_time_ms AND s2.play_count = top_matches.play_count AND (s2.album < top_matches.album OR (s2.album = top_matches.album AND s2.artist < top_matches.artist)))
            ) + 1 as global_rank
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopAlbumsByTime(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopAlbum>

    @SuppressWarnings("RoomWarnings.QUERY_MISMATCH")
    @Query(
        """
        WITH stats AS (
            SELECT 
                t.album,
                COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist) as artist,
                COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, ''))) as album_art_url,
                COUNT(le.id) as play_count,
                SUM(le.playDuration) as total_time_ms,
                COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= :startTime AND le.timestamp <= :endTime
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            AND (:filterPodcasts = 0 OR t.content_type IS NULL OR t.content_type != 'PODCAST')
            AND (:filterAudiobooks = 0 OR t.content_type IS NULL OR t.content_type != 'AUDIOBOOK')
            GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)
            HAVING COUNT(DISTINCT t.id) > 1
        ),
        max_values AS (
            SELECT 
                MAX(play_count) as max_plays,
                MAX(total_time_ms) as max_time
            FROM stats
        ),
        scored AS (
            SELECT 
                stats.*,
                CASE 
                    WHEN max_values.max_plays > 0 AND max_values.max_time > 0 THEN
                        (0.5 * CAST(stats.play_count AS REAL) / max_values.max_plays) + 
                        (0.5 * CAST(stats.total_time_ms AS REAL) / max_values.max_time)
                    ELSE 0.0
                END as combined_score
            FROM stats
            CROSS JOIN max_values
        ),
        top_matches AS (
            SELECT scored.*
            FROM scored
            WHERE scored.album LIKE '%' || :query || '%'
               OR scored.artist LIKE '%' || :query || '%'
            ORDER BY scored.combined_score DESC, scored.play_count DESC, scored.total_time_ms DESC, scored.album ASC, scored.artist ASC
            LIMIT :limit
        )
        SELECT 
            top_matches.album,
            top_matches.artist,
            top_matches.album_art_url,
            top_matches.play_count,
            top_matches.total_time_ms,
            top_matches.unique_tracks,
            (SELECT COUNT(*) FROM scored s2
             WHERE s2.combined_score > top_matches.combined_score
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count > top_matches.play_count)
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count = top_matches.play_count AND s2.total_time_ms > top_matches.total_time_ms)
                OR (s2.combined_score = top_matches.combined_score AND s2.play_count = top_matches.play_count AND s2.total_time_ms = top_matches.total_time_ms AND (s2.album < top_matches.album OR (s2.album = top_matches.album AND s2.artist < top_matches.artist)))
            ) + 1 as global_rank
        FROM top_matches
        ORDER BY global_rank
        LIMIT :limit
    """,
    )
    suspend fun searchTopAlbumsByCombinedScore(
        startTime: Long,
        endTime: Long,
        filterPodcasts: Boolean,
        filterAudiobooks: Boolean,
        query: String,
        limit: Int,
    ): List<RankedTopAlbum>
}

data class TrackWithStatsRaw(
    @Embedded val track: me.avinas.tempo.data.local.entities.Track,
    val play_count: Int,
    val total_time_ms: Long,
)

/** TopTrack plus its GLOBAL rank: the SQL-computed rank needs its own column, TopTrack.rank is @Ignore. */
data class RankedTopTrack(
    @Embedded val item: TopTrack,
    @ColumnInfo(name = "global_rank") val globalRank: Int,
)

/**
 * Search result row: a top-album entry plus its GLOBAL rank in the full ranking.
 */
data class RankedTopAlbum(
    @Embedded val item: TopAlbum,
    @ColumnInfo(name = "global_rank") val globalRank: Int,
)

data class BingeDetectionEvent(
    val id: Long,
    val track_id: Long,
    val timestamp: Long,
    val artist: String,
    val album: String?,
) {
    val trackId: Long get() = track_id
}

data class HourlyCompletionStats(
    val hour: Int,
    val avg_completion: Float,
    val skip_count: Int,
) {
    val avgCompletion: Float get() = avg_completion
    val skipCount: Int get() = skip_count
}

data class HistoryItem(
    val id: Long,
    val track_id: Long,
    val timestamp: Long,
    val playDuration: Long,
    val completionPercentage: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val content_type: String = "MUSIC",
    val album_art_url: String?,
    val source: String = "",
)

data class YearStatsRaw(
    val play_count: Int,
    val total_time_ms: Long,
    val unique_artists: Int,
)

data class AudioFeaturesRaw(
    val avg_energy: Float?,
    val avg_danceability: Float?,
    val avg_valence: Float?,
    val avg_tempo: Float?,
    val avg_acousticness: Float?,
    val avg_instrumentalness: Float?,
    val avg_speechiness: Float?,
    val avg_loudness: Float?,
    val tracks_count: Int,
)

data class TempoDistributionRaw(
    val bucket_label: String,
    val track_count: Int,
    val total_plays: Int,
)

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
    val timeSignature: Int?,
)

data class ArtistListeningDates(
    val first_listened: Long?,
    val last_listened: Long?,
)

data class ArtistPlayCountResult(
    val artistId: Long,
    val playCount: Int,
)
