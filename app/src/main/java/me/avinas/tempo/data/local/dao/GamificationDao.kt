package me.avinas.tempo.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel

@Dao
interface GamificationDao {

    // =====================
    // UserLevel
    // =====================
    
    @Query("SELECT * FROM user_level WHERE id = 1")
    suspend fun getUserLevel(): UserLevel?
    
    @Query("SELECT * FROM user_level WHERE id = 1")
    fun observeUserLevel(): Flow<UserLevel?>
    
    @Upsert
    suspend fun upsertUserLevel(userLevel: UserLevel)

    // =====================
    // Badges
    // =====================
    
    @Query("SELECT * FROM badges ORDER BY earned_at DESC")
    suspend fun getAllBadges(): List<Badge>
    
    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC")
    suspend fun getEarnedBadges(): List<Badge>
    
    @Query("SELECT * FROM badges ORDER BY is_earned DESC, category ASC, max_progress ASC")
    fun observeAllBadges(): Flow<List<Badge>>
    
    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC")
    fun observeEarnedBadges(): Flow<List<Badge>>
    
    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC LIMIT :limit")
    fun observeRecentBadges(limit: Int = 3): Flow<List<Badge>>
    
    @Query("SELECT * FROM badges WHERE badge_id = :badgeId")
    suspend fun getBadgeById(badgeId: String): Badge?
    
    @Upsert
    suspend fun upsertBadge(badge: Badge)
    
    @Upsert
    suspend fun upsertBadges(badges: List<Badge>)
    
    @Query("SELECT COUNT(*) FROM badges WHERE is_earned = 1")
    suspend fun getEarnedBadgeCount(): Int
    
    @Query("SELECT COUNT(*) FROM badges")
    suspend fun getTotalBadgeCount(): Int
    
    // =====================
    // XP Calculation helpers (queries against listening_events)
    // =====================
    
    /** Count full plays (≥80% completion) */
    @Query("SELECT COUNT(*) FROM listening_events WHERE completionPercentage >= 80")
    suspend fun getFullPlayCount(): Int
    
    /** Count partial plays (30-79% completion) */
    @Query("SELECT COUNT(*) FROM listening_events WHERE completionPercentage >= 30 AND completionPercentage < 80")
    suspend fun getPartialPlayCount(): Int
    
    /** Total listening time in milliseconds */
    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events")
    suspend fun getTotalListeningTimeMs(): Long
    
    /** Total play count */
    @Query("SELECT COUNT(*) FROM listening_events")
    suspend fun getTotalPlayCount(): Int
    
    /** Count unique artists listened to */
    @Query("""
        SELECT COUNT(DISTINCT t.artist) 
        FROM listening_events le 
        JOIN tracks t ON le.track_id = t.id
    """)
    suspend fun getUniqueArtistCount(): Int
    
    /** Count unique genres listened to */
    @Query("""
        SELECT COUNT(DISTINCT em.genres) 
        FROM listening_events le 
        JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON em.track_id = t.id
        WHERE em.genres IS NOT NULL AND em.genres != ''
    """)
    suspend fun getUniqueGenreCount(): Int
    
    /** Count plays between specific hours (for Night Owl / Early Bird) using local time */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) >= :startHour 
        AND CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) < :endHour
    """)
    suspend fun getPlayCountBetweenHours(startHour: Int, endHour: Int): Int
    
    /** Get all distinct listening dates for streak calculation */
    @Query("""
        SELECT DISTINCT date(timestamp / 1000, 'unixepoch', 'localtime') as listen_date 
        FROM listening_events 
        ORDER BY listen_date DESC
    """)
    suspend fun getDistinctListeningDates(): List<String>
    
    /** Get longest listening session duration in milliseconds */
    @Query("""
        SELECT COALESCE(MAX(session_total), 0) FROM (
            SELECT session_id, SUM(playDuration) as session_total 
            FROM listening_events 
            WHERE session_id IS NOT NULL 
            GROUP BY session_id
        )
    """)
    suspend fun getLongestSessionMs(): Long
}
