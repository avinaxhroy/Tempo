package me.avinas.tempo.data.repository

import android.util.Log
import me.avinas.tempo.data.local.dao.GamificationDao
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the gamification system.
 * 
 * Manages XP computation, level tracking, and badge evaluation.
 * XP is always deterministic: recomputed from listening history, never incremental.
 */
@Singleton
class GamificationRepository @Inject constructor(
    private val gamificationDao: GamificationDao
) {
    companion object {
        private const val TAG = "GamificationRepo"
    }
    
    // =====================
    // Level & XP
    // =====================
    
    fun observeUserLevel(): Flow<UserLevel?> = gamificationDao.observeUserLevel()
    
    suspend fun getUserLevel(): UserLevel {
        return gamificationDao.getUserLevel() ?: UserLevel()
    }
    
    /**
     * Recompute XP and level from listening history and update the database.
     * Returns the previous level for level-up detection.
     */
    suspend fun recomputeXpAndLevel(): LevelUpResult {
        val previousLevel = gamificationDao.getUserLevel()
        val previousLevelNum = previousLevel?.currentLevel ?: 0
        
        // Calculate XP deterministically from listening events
        val fullPlays = gamificationDao.getFullPlayCount()
        val partialPlays = gamificationDao.getPartialPlayCount()
        val totalXp = GamificationEngine.calculateXp(fullPlays, partialPlays)
        
        // Compute level from XP
        val levelState = GamificationEngine.computeLevelState(totalXp)
        
        // Compute streak
        val dates = gamificationDao.getDistinctListeningDates()
        val streakInfo = GamificationEngine.calculateStreak(dates)
        
        val updatedLevel = UserLevel(
            id = 1,
            totalXp = totalXp,
            currentLevel = levelState.level,
            xpForCurrentLevel = levelState.xpForCurrentLevel,
            xpForNextLevel = levelState.xpForNextLevel,
            lastXpAwardedAt = System.currentTimeMillis(),
            currentStreak = streakInfo.currentStreak,
            longestStreak = maxOf(streakInfo.longestStreak, previousLevel?.longestStreak ?: 0),
            lastStreakDate = dates.firstOrNull() ?: ""
        )
        
        gamificationDao.upsertUserLevel(updatedLevel)
        
        Log.d(TAG, "XP recomputed: $totalXp XP, Level ${levelState.level} " +
                "(full=$fullPlays, partial=$partialPlays, streak=${streakInfo.currentStreak})")
        
        return LevelUpResult(
            previousLevel = previousLevelNum,
            newLevel = levelState.level,
            totalXp = totalXp,
            didLevelUp = levelState.level > previousLevelNum
        )
    }
    
    // =====================
    // Badges
    // =====================
    
    fun observeAllBadges(): Flow<List<Badge>> = gamificationDao.observeAllBadges()
    
    fun observeEarnedBadges(): Flow<List<Badge>> = gamificationDao.observeEarnedBadges()
    
    fun observeRecentBadges(limit: Int = 3): Flow<List<Badge>> = gamificationDao.observeRecentBadges(limit)
    
    suspend fun getEarnedBadgeCount(): Int = gamificationDao.getEarnedBadgeCount()

    suspend fun getNextEarnableBadge(): Badge? {
        val allBadges = gamificationDao.getAllBadges()
        return allBadges
            .filter { !it.isEarned }
            .maxByOrNull { it.progressFraction }
    }
    
    /**
     * Evaluate all badge conditions and update the database.
     * Returns list of newly earned badge IDs.
     */
    suspend fun evaluateAllBadges(): List<String> {
        val userLevel = gamificationDao.getUserLevel() ?: UserLevel()
        val totalPlays = gamificationDao.getTotalPlayCount()
        val totalTimeMs = gamificationDao.getTotalListeningTimeMs()
        val totalTimeHours = (totalTimeMs / 3_600_000).toInt()
        val uniqueArtists = gamificationDao.getUniqueArtistCount()
        val uniqueGenres = gamificationDao.getUniqueGenreCount()
        val nightPlays = gamificationDao.getPlayCountBetweenHours(0, 5)
        val morningPlays = gamificationDao.getPlayCountBetweenHours(5, 8)
        val longestSessionMs = gamificationDao.getLongestSessionMs()
        val longestSessionHours = (longestSessionMs / 3_600_000).toInt()
        
        val newlyEarned = mutableListOf<String>()
        
        for (def in GamificationEngine.ALL_BADGE_DEFINITIONS) {
            val existing = gamificationDao.getBadgeById(def.badgeId)
            
            // Compute current progress for this badge
            val (currentProgress, maxProgress) = when {
                // Milestones
                def.category == "MILESTONE" -> Pair(totalPlays.coerceAtMost(def.threshold), def.threshold)
                
                // Time
                def.category == "TIME" -> Pair(totalTimeHours.coerceAtMost(def.threshold), def.threshold)
                
                // Streaks
                def.category == "STREAK" -> {
                    val longestStreak = userLevel.longestStreak
                    Pair(longestStreak.coerceAtMost(def.threshold), def.threshold)
                }
                
                // Discovery
                def.badgeId.startsWith("artists_") -> Pair(uniqueArtists.coerceAtMost(def.threshold), def.threshold)
                def.badgeId.startsWith("genres_") -> Pair(uniqueGenres.coerceAtMost(def.threshold), def.threshold)
                
                // Engagement
                def.badgeId == "night_owl" -> Pair(nightPlays.coerceAtMost(def.threshold), def.threshold)
                def.badgeId == "early_bird" -> Pair(morningPlays.coerceAtMost(def.threshold), def.threshold)
                def.badgeId == "marathon" -> Pair(
                    if (longestSessionHours >= 3) 1 else 0,
                    1
                )
                
                // Level milestones
                def.category == "LEVEL" -> Pair(
                    userLevel.currentLevel.coerceAtMost(def.threshold),
                    def.threshold
                )
                
                else -> Pair(0, def.threshold)
            }
            
            val isNowEarned = currentProgress >= maxProgress
            val wasAlreadyEarned = existing?.isEarned == true
            
            val badge = Badge(
                id = existing?.id ?: 0,
                badgeId = def.badgeId,
                name = def.name,
                description = def.description,
                iconName = def.iconName,
                category = def.category,
                earnedAt = when {
                    isNowEarned && wasAlreadyEarned -> existing!!.earnedAt
                    isNowEarned -> System.currentTimeMillis()
                    else -> 0
                },
                progress = currentProgress,
                maxProgress = maxProgress,
                isEarned = isNowEarned
            )
            
            gamificationDao.upsertBadge(badge)
            
            if (isNowEarned && !wasAlreadyEarned) {
                newlyEarned.add(def.badgeId)
                Log.i(TAG, "🏆 Badge earned: ${def.name}")
            }
        }
        
        Log.d(TAG, "Badge evaluation complete: ${newlyEarned.size} new badges earned")
        return newlyEarned
    }
    
    /**
     * Full refresh: recompute XP + evaluate all badges.
     */
    suspend fun fullRefresh(): GamificationRefreshResult {
        val levelUpResult = recomputeXpAndLevel()
        val newBadges = evaluateAllBadges()
        return GamificationRefreshResult(
            levelUpResult = levelUpResult,
            newlyEarnedBadgeIds = newBadges
        )
    }
}

data class LevelUpResult(
    val previousLevel: Int,
    val newLevel: Int,
    val totalXp: Long,
    val didLevelUp: Boolean
)

data class GamificationRefreshResult(
    val levelUpResult: LevelUpResult,
    val newlyEarnedBadgeIds: List<String>
)
