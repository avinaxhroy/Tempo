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
        val uniqueArtists = gamificationDao.getUniqueArtistCount()
        var totalXp = GamificationEngine.calculateXp(fullPlays, partialPlays, uniqueArtists)
        
        // Add bonus XP from completed challenges
        // Since challenges aren't bound to specific play events, we query all completed challenges
        // Note: For a true deterministic calc, we might need a separate table or query, but
        // counting completed challenges in daily_challenges is sufficient.
        val completedChallenges = gamificationDao.getAllCompletedChallenges()
        val challengeBonusXp = completedChallenges.sumOf { it.xpReward.toLong() }
        totalXp += challengeBonusXp
        
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
    
    suspend fun getUniqueArtistCount(): Int = gamificationDao.getUniqueArtistCount()

    suspend fun getNextEarnableBadge(): Badge? {
        val allBadges = gamificationDao.getAllBadges()
        return allBadges
            .filter { !it.isMaxed } // Include locked badges and earned badges not yet at 5 stars
            .maxByOrNull { it.progressFraction }
    }
    
    /**
     * Evaluate all badge conditions and update the database.
     * Supports star tiers: each badge has 5 star levels at 1x, 2x, 3x, 5x, 10x the base threshold.
     * Returns list of newly earned badge IDs (first-time unlocks only).
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
            
            // Get raw (uncapped) progress for this badge
            val rawProgress = when {
                def.category == "MILESTONE" -> totalPlays
                def.category == "TIME" -> totalTimeHours
                def.category == "STREAK" -> userLevel.longestStreak
                def.badgeId.startsWith("artists_") -> uniqueArtists
                def.badgeId.startsWith("genres_") -> uniqueGenres
                def.badgeId == "night_owl" -> nightPlays
                def.badgeId == "early_bird" -> morningPlays
                def.badgeId == "marathon" -> if (longestSessionHours >= 3) longestSessionHours / 3 else 0
                def.category == "LEVEL" -> userLevel.currentLevel
                else -> 0
            }
            
            // Compute star tier
            // Beginner badges cap at 1 star (shown as "Unlocked" in UI)
            val currentStars = if (def.badgeId in GamificationEngine.BEGINNER_BADGES) {
                if (rawProgress >= def.threshold) 1 else 0
            } else {
                GamificationEngine.computeStars(rawProgress, def.threshold)
            }
            val previousStars = existing?.stars ?: 0
            val isNowEarned = currentStars >= 1
            val wasAlreadyEarned = existing?.isEarned == true
            
            // Progress toward next star (or show maxed state)
            val nextStarThreshold = GamificationEngine.getNextStarThreshold(def.threshold, currentStars)
            
            // For the progress bar: show progress within current tier toward next star
            val displayProgress: Int
            val displayMaxProgress: Int
            if (currentStars >= GamificationEngine.MAX_STARS) {
                // Maxed out — show full bar
                displayProgress = nextStarThreshold
                displayMaxProgress = nextStarThreshold
            } else {
                // Show progress toward next star
                displayProgress = rawProgress.coerceAtMost(nextStarThreshold)
                displayMaxProgress = nextStarThreshold
            }
            
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
                progress = displayProgress,
                maxProgress = displayMaxProgress,
                isEarned = isNowEarned,
                stars = currentStars
            )
            
            gamificationDao.upsertBadge(badge)
            
            if (isNowEarned && !wasAlreadyEarned) {
                newlyEarned.add(def.badgeId)
                Log.i(TAG, "🏆 Badge earned: ${def.name} (★$currentStars)")
            } else if (currentStars > previousStars && wasAlreadyEarned) {
                Log.i(TAG, "⭐ Badge upgraded: ${def.name} → ★$currentStars")
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
