package me.avinas.tempo.data.stats

import kotlin.math.floor
import kotlin.math.pow

/**
 * Core engine for the gamification system.
 * 
 * Handles XP calculation, level computation, and badge definitions.
 * All logic is deterministic. XP can always be recomputed from listening history.
 * 
 * Level formula: xpForLevel(n) = floor(100 * n^1.5)
 * This creates an exponential curve with no cap:
 *   Level 10 = 3,162 XP       Level 50 = 35,355 XP
 *   Level 25 = 12,500 XP      Level 100 = 100,000 XP
 */
object GamificationEngine {

    // =====================
    // XP Constants
    // =====================
    
    const val XP_FULL_PLAY = 10L    // ≥80% completion
    const val XP_PARTIAL_PLAY = 3L  // 30-79% completion
    const val XP_SKIPPED = 0L       // <30% completion
    
    // =====================
    // Level Computation
    // =====================
    
    /**
     * Cumulative XP required to reach a given level.
     * Uses formula: floor(100 * level^1.5)
     */
    fun cumulativeXpForLevel(level: Int): Long {
        if (level <= 0) return 0
        return floor(100.0 * level.toDouble().pow(1.5)).toLong()
    }
    
    /**
     * Compute the current level for a given total XP.
     * Returns level number (0 = haven't reached level 1 yet).
     */
    fun computeLevel(totalXp: Long): Int {
        if (totalXp <= 0) return 0
        var level = 0
        while (cumulativeXpForLevel(level + 1) <= totalXp) {
            level++
        }
        return level
    }
    
    /**
     * Given total XP, compute full level state.
     */
    fun computeLevelState(totalXp: Long): LevelState {
        val level = computeLevel(totalXp)
        val xpForCurrent = cumulativeXpForLevel(level)
        val xpForNext = cumulativeXpForLevel(level + 1)
        return LevelState(
            level = level,
            totalXp = totalXp,
            xpForCurrentLevel = xpForCurrent,
            xpForNextLevel = xpForNext
        )
    }
    
    /**
     * Calculate total XP from play counts.
     */
    fun calculateXp(fullPlayCount: Int, partialPlayCount: Int): Long {
        return (fullPlayCount * XP_FULL_PLAY) + (partialPlayCount * XP_PARTIAL_PLAY)
    }
    
    /**
     * Calculate the longest streak from a sorted list of dates (most recent first).
     * Dates should be in YYYY-MM-DD format.
     */
    fun calculateStreak(dates: List<String>): StreakInfo {
        if (dates.isEmpty()) return StreakInfo(0, 0)
        
        val parsedDates = dates.mapNotNull { 
            try { java.time.LocalDate.parse(it) } catch (e: Exception) { null }
        }.sortedDescending()
        
        if (parsedDates.isEmpty()) return StreakInfo(0, 0)
        
        // Current streak (from today backwards)
        val today = java.time.LocalDate.now()
        var currentStreak = 0
        var expectedDate = today
        
        for (date in parsedDates) {
            if (date == expectedDate || date == expectedDate.minusDays(1)) {
                if (date == expectedDate) {
                    currentStreak++
                    expectedDate = date.minusDays(1)
                } else if (date == expectedDate.minusDays(1)) {
                    // Allow 1-day gap if today hasn't been listened yet
                    if (currentStreak == 0) {
                        currentStreak++
                        expectedDate = date.minusDays(1)
                    } else {
                        break
                    }
                }
            } else if (date < expectedDate.minusDays(1)) {
                break
            }
            // Skip duplicate dates
        }
        
        // Longest streak ever
        var longestStreak = 0
        var currentRun = 1
        for (i in 1 until parsedDates.size) {
            val diff = parsedDates[i - 1].toEpochDay() - parsedDates[i].toEpochDay()
            if (diff == 1L) {
                currentRun++
            } else if (diff > 1L) {
                longestStreak = maxOf(longestStreak, currentRun)
                currentRun = 1
            }
            // diff == 0 means same day (duplicate), skip
        }
        longestStreak = maxOf(longestStreak, currentRun)
        
        return StreakInfo(
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }
    
    // =====================
    // Badge Definitions
    // =====================
    
    val ALL_BADGE_DEFINITIONS: List<BadgeDefinition> = listOf(
        // === Milestones ===
        BadgeDefinition("first_play", "First Note", "Play your first song", "music_note", "MILESTONE", 1),
        BadgeDefinition("plays_100", "Century", "Play 100 songs", "century", "MILESTONE", 100),
        BadgeDefinition("plays_500", "Half Grand", "Play 500 songs", "star_half", "MILESTONE", 500),
        BadgeDefinition("plays_1000", "Grand Maestro", "Play 1,000 songs", "star", "MILESTONE", 1_000),
        BadgeDefinition("plays_5000", "Virtuoso", "Play 5,000 songs", "diamond", "MILESTONE", 5_000),
        BadgeDefinition("plays_10000", "Legendary", "Play 10,000 songs", "emoji_events", "MILESTONE", 10_000),
        
        // === Time ===
        BadgeDefinition("time_1h", "First Hour", "Listen for 1 hour total", "timer", "TIME", 1),
        BadgeDefinition("time_24h", "Day Tripper", "Listen for 24 hours total", "schedule", "TIME", 24),
        BadgeDefinition("time_100h", "Centurion", "Listen for 100 hours total", "hourglass_full", "TIME", 100),
        BadgeDefinition("time_500h", "Sound Sage", "Listen for 500 hours total", "headphones", "TIME", 500),
        
        // === Streaks ===
        BadgeDefinition("streak_7", "Week Warrior", "7-day listening streak", "local_fire_department", "STREAK", 7),
        BadgeDefinition("streak_30", "Monthly Maven", "30-day listening streak", "whatshot", "STREAK", 30),
        BadgeDefinition("streak_100", "Centenarian", "100-day listening streak", "military_tech", "STREAK", 100),
        BadgeDefinition("streak_365", "Year-Round", "365-day listening streak", "auto_awesome", "STREAK", 365),
        
        // === Discovery ===
        BadgeDefinition("artists_10", "Explorer", "Listen to 10 different artists", "explore", "DISCOVERY", 10),
        BadgeDefinition("artists_50", "Curator", "Listen to 50 different artists", "collections", "DISCOVERY", 50),
        BadgeDefinition("artists_100", "Connoisseur", "Listen to 100 different artists", "public", "DISCOVERY", 100),
        BadgeDefinition("genres_10", "Genre Hopper", "Listen to 10 different genres", "category", "DISCOVERY", 10),
        BadgeDefinition("genres_25", "Eclectic", "Listen to 25 different genres", "palette", "DISCOVERY", 25),
        
        // === Engagement ===
        BadgeDefinition("night_owl", "Night Owl", "100 plays between midnight and 5 AM", "nightlight", "ENGAGEMENT", 100),
        BadgeDefinition("early_bird", "Early Bird", "100 plays between 5 AM and 8 AM", "wb_sunny", "ENGAGEMENT", 100),
        BadgeDefinition("marathon", "Marathon", "A single session over 3 hours", "directions_run", "ENGAGEMENT", 1),
        
        // === Level Milestones ===
        BadgeDefinition("level_5", "Rising Star", "Reach Level 5", "grade", "LEVEL", 5),
        BadgeDefinition("level_10", "Double Digits", "Reach Level 10", "looks_one", "LEVEL", 10),
        BadgeDefinition("level_25", "Quarter Century", "Reach Level 25", "military_tech", "LEVEL", 25),
        BadgeDefinition("level_50", "Half Way There", "Reach Level 50", "workspace_premium", "LEVEL", 50),
        BadgeDefinition("level_75", "Elite Listener", "Reach Level 75", "shield", "LEVEL", 75),
        BadgeDefinition("level_100", "The Centennial", "Reach Level 100", "emoji_events", "LEVEL", 100)
    )
    
    data class BadgeDefinition(
        val badgeId: String,
        val name: String,
        val description: String,
        val iconName: String,
        val category: String,
        val threshold: Int
    )
    
    data class LevelState(
        val level: Int,
        val totalXp: Long,
        val xpForCurrentLevel: Long,
        val xpForNextLevel: Long
    )
    
    data class StreakInfo(
        val currentStreak: Int,
        val longestStreak: Int
    )
}
