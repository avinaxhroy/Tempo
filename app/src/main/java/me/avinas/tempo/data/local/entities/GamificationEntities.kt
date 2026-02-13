package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single-row table tracking the user's XP and level.
 * 
 * XP is computed deterministically from listening history:
 * - Full play (≥80% completion): 10 XP
 * - Partial play (30-79%): 3 XP
 * - Skipped (<30%): 0 XP
 * 
 * Level uses an exponential curve: xpForLevel(n) = floor(100 * n^1.5)
 * This means no hardcoded cap. Levels continue infinitely.
 */
@Entity(tableName = "user_level")
data class UserLevel(
    @PrimaryKey val id: Int = 1, // Always 1 (single-row)
    @ColumnInfo(name = "total_xp") val totalXp: Long = 0,
    @ColumnInfo(name = "current_level") val currentLevel: Int = 0,
    @ColumnInfo(name = "xp_for_current_level") val xpForCurrentLevel: Long = 0,
    @ColumnInfo(name = "xp_for_next_level") val xpForNextLevel: Long = 100,
    @ColumnInfo(name = "last_xp_awarded_at") val lastXpAwardedAt: Long = 0,
    @ColumnInfo(name = "current_streak") val currentStreak: Int = 0,
    @ColumnInfo(name = "longest_streak") val longestStreak: Int = 0,
    @ColumnInfo(name = "last_streak_date") val lastStreakDate: String = ""
) {
    /** Progress percentage toward next level (0.0 to 1.0) */
    val levelProgress: Float
        get() {
            val range = xpForNextLevel - xpForCurrentLevel
            if (range <= 0) return 0f
            val progress = totalXp - xpForCurrentLevel
            return (progress.toFloat() / range.toFloat()).coerceIn(0f, 1f)
        }
    
    /** XP remaining to reach next level */
    val xpRemaining: Long
        get() = (xpForNextLevel - totalXp).coerceAtLeast(0)
    
    /** User title based on level */
    val title: String
        get() = when {
            currentLevel < 5 -> "Newcomer"
            currentLevel < 10 -> "Casual Listener"
            currentLevel < 20 -> "Music Fan"
            currentLevel < 35 -> "Music Enthusiast"
            currentLevel < 50 -> "Dedicated Listener"
            currentLevel < 75 -> "Music Connoisseur"
            currentLevel < 100 -> "Audiophile"
            currentLevel < 150 -> "Music Legend"
            else -> "Sound God"
        }
}

/**
 * Represents a badge (earned or locked) in the gamification system.
 * 
 * Badges are awarded for milestones: play counts, listening time,
 * streaks, discovery, engagement patterns, and reaching specific levels.
 */
@Entity(
    tableName = "badges",
    indices = [
        Index(value = ["badge_id"], unique = true),
        Index(value = ["category"]),
        Index(value = ["is_earned"])
    ]
)
data class Badge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "badge_id") val badgeId: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "icon_name") val iconName: String,
    val category: String,      // MILESTONE, TIME, STREAK, DISCOVERY, ENGAGEMENT, LEVEL
    @ColumnInfo(name = "earned_at") val earnedAt: Long = 0,
    val progress: Int = 0,     // Current progress
    @ColumnInfo(name = "max_progress") val maxProgress: Int = 1,
    @ColumnInfo(name = "is_earned") val isEarned: Boolean = false
) {
    /** Progress as a fraction 0.0 to 1.0 */
    val progressFraction: Float
        get() = if (maxProgress > 0) (progress.toFloat() / maxProgress).coerceIn(0f, 1f) else 0f
}
