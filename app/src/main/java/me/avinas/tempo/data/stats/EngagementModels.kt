package me.avinas.tempo.data.stats

import androidx.room.ColumnInfo

/**
 * User behavior and engagement metrics.
 * 
 * Since Spotify's audio-features API is deprecated, we derive engagement
 * insights from user behavior patterns instead:
 * - Completion rate: How much of each song is listened to
 * - Skip patterns: Early skips vs full plays
 * - Replay behavior: Immediate replays indicate high engagement
 * - Time-of-day preferences: When user listens to certain genres/moods
 * - Pause patterns: Frequent pauses may indicate distraction or less engagement
 * 
 * These metrics often provide MORE valuable insights than audio features
 * because they reflect actual user preferences rather than song characteristics.
 */

// =====================
// Engagement Thresholds
// =====================
object EngagementThresholds {
    const val FULL_PLAY_PERCENT = 80       // >80% = full play
    const val PARTIAL_PLAY_MIN = 30        // 30-80% = partial play
    const val SKIP_THRESHOLD = 30          // <30% = skip
    const val REPLAY_WINDOW_MS = 5 * 60 * 1000L  // 5 minutes for replay detection
    
    // Engagement score weights (must sum to 100)
    const val COMPLETION_WEIGHT = 35       // Completion rate importance
    const val FULL_PLAY_WEIGHT = 25        // Full plays ratio importance
    const val REPLAY_WEIGHT = 20           // Replay behavior importance
    const val ANTI_SKIP_WEIGHT = 15        // Low skip rate importance
    const val CONSISTENCY_WEIGHT = 5       // Consistent listening importance
}

/**
 * Engagement metrics for a track based on user behavior.
 */
data class TrackEngagement(
    val trackId: Long,
    val title: String,
    val artist: String,
    val playCount: Int,
    val totalListeningTimeMs: Long,
    val averageCompletionPercent: Float,
    val fullPlaysCount: Int,      // Plays with >80% completion
    val partialPlaysCount: Int,   // Plays with 30-80% completion  
    val skipsCount: Int,          // Plays with <30% completion
    val replayCount: Int,         // Back-to-back plays within 5 minutes
    val lastPlayedTimestamp: Long,
    // Enhanced metrics
    val averagePauseCount: Float = 0f,    // Average pauses per play
    val totalPauseCount: Int = 0,         // Total pauses across all plays
    val longestPlayDurationMs: Long = 0,  // Longest single play duration
    val shortestPlayDurationMs: Long = 0, // Shortest single play duration (non-skip)
    val firstPlayedTimestamp: Long = 0,   // When the track was first played
    val uniqueSessionsCount: Int = 0      // Number of unique listening sessions
) {
    /**
     * Engagement score from 0-100 based on behavior patterns.
     * High score = user really enjoys this track.
     * 
     * Improved algorithm:
     * - Completion rate (35%): How much of the track is typically listened to
     * - Full plays ratio (25%): Percentage of plays that complete
     * - Replay behavior (20%): Replaying indicates high engagement
     * - Anti-skip bonus (15%): Not skipping indicates preference
     * - Consistency (5%): Low variance in play behavior
     */
    val engagementScore: Int get() {
        if (playCount == 0) return 0
        
        // 1. Completion rate (35% weight) - normalized to 0-35
        val completionScore = (averageCompletionPercent / 100f * EngagementThresholds.COMPLETION_WEIGHT).toInt()
        
        // 2. Full plays ratio (25% weight) - normalized to 0-25
        val fullPlayRatio = fullPlaysCount.toFloat() / playCount
        val fullPlayScore = (fullPlayRatio * EngagementThresholds.FULL_PLAY_WEIGHT).toInt()
        
        // 3. Replay behavior (20% weight) - diminishing returns after 4 replays
        // Each replay adds 5 points, capped at 20
        val replayScore = minOf(EngagementThresholds.REPLAY_WEIGHT, replayCount * 5)
        
        // 4. Anti-skip bonus (15% weight) - inverse of skip rate
        val skipRatio = skipsCount.toFloat() / playCount
        val antiSkipScore = ((1 - skipRatio) * EngagementThresholds.ANTI_SKIP_WEIGHT).toInt()
        
        // 5. Consistency bonus (5% weight) - low pause count indicates focused listening
        val avgPauses = if (playCount > 0) totalPauseCount.toFloat() / playCount else 0f
        val consistencyScore = when {
            avgPauses <= 0.5f -> EngagementThresholds.CONSISTENCY_WEIGHT // Very focused
            avgPauses <= 1.5f -> 3  // Somewhat focused
            avgPauses <= 3f -> 1    // Distracted
            else -> 0               // Very distracted
        }
        
        return minOf(100, completionScore + fullPlayScore + replayScore + antiSkipScore + consistencyScore)
    }
    
    /**
     * Engagement level with more granular categories.
     */
    val engagementLevel: String get() = when {
        engagementScore >= 90 -> "Obsessed"
        engagementScore >= 80 -> "Favorite"
        engagementScore >= 70 -> "Loved"
        engagementScore >= 55 -> "Enjoyed"
        engagementScore >= 40 -> "Liked"
        engagementScore >= 25 -> "Casual"
        engagementScore >= 10 -> "Background"
        else -> "Skipped"
    }
    
    val engagementEmoji: String get() = when {
        engagementScore >= 90 -> "💎"
        engagementScore >= 80 -> "❤️"
        engagementScore >= 70 -> "🔥"
        engagementScore >= 55 -> "⭐"
        engagementScore >= 40 -> "👍"
        engagementScore >= 25 -> "🎵"
        engagementScore >= 10 -> "🎧"
        else -> "⏭️"
    }
    
    /**
     * Completion pattern description with more detail.
     */
    val completionPattern: String get() = when {
        averageCompletionPercent >= 95 -> "Always completes"
        averageCompletionPercent >= 85 -> "Usually completes"
        averageCompletionPercent >= 70 -> "Often completes"
        averageCompletionPercent >= 50 -> "Sometimes skips midway"
        averageCompletionPercent >= 30 -> "Often skips early"
        else -> "Usually skips quickly"
    }
    
    /**
     * Listening behavior description based on patterns.
     */
    val listeningBehavior: String get() = when {
        replayCount >= playCount * 0.3 && fullPlaysCount >= playCount * 0.8 -> "On repeat"
        fullPlaysCount >= playCount * 0.9 -> "Dedicated listener"
        skipsCount >= playCount * 0.5 -> "Selective listener"
        averagePauseCount >= 2 -> "Interrupted listener"
        partialPlaysCount >= playCount * 0.5 -> "Partial listener"
        else -> "Regular listener"
    }
    
    /**
     * Calculate days since first play.
     */
    val daysSinceFirstPlay: Int get() {
        if (firstPlayedTimestamp == 0L) return 0
        val now = System.currentTimeMillis()
        return ((now - firstPlayedTimestamp) / (24 * 60 * 60 * 1000)).toInt()
    }
    
    /**
     * Average plays per day since discovery.
     */
    val playsPerDay: Float get() {
        val days = daysSinceFirstPlay
        return if (days > 0) playCount.toFloat() / days else playCount.toFloat()
    }
}

/**
 * Aggregated engagement stats for a time period.
 */
data class EngagementOverview(
    val timeRange: TimeRange,
    val totalPlays: Int,
    val totalListeningTimeMs: Long,
    val averageCompletionPercent: Float,
    val totalFullPlays: Int,
    val totalSkips: Int,
    val totalReplays: Int,
    val skipRate: Float,           // Percentage of plays that were skips
    val completionRate: Float,     // Percentage of plays that were full completions
    val mostEngagedHour: Int?,     // Hour with highest engagement scores
    val mostSkippedHour: Int?,     // Hour with most skips
    val bingeSessionsCount: Int,   // Sessions with 3+ tracks in a row from same artist
    // Enhanced metrics
    val totalPartialPlays: Int = 0,          // Plays with 30-80% completion
    val averageSessionLengthMs: Long = 0,    // Average continuous listening session
    val longestSessionMs: Long = 0,          // Longest continuous listening session
    val uniqueTracksPlayed: Int = 0,         // Number of unique tracks in period
    val newTracksDiscovered: Int = 0,        // Tracks played for the first time
    val repeatListenRate: Float = 0f,        // Percentage of plays that were repeats
    val averagePauseCount: Float = 0f,       // Average pauses per play
    val uniqueSessionsCount: Int = 0         // Number of unique listening sessions
) {
    /**
     * Overall engagement score with improved algorithm.
     */
    val overallEngagementScore: Int get() {
        if (totalPlays == 0) return 0
        
        // Completion contribution (40%)
        val completionScore = (averageCompletionPercent / 100f * 40).toInt()
        
        // Full play ratio contribution (25%)
        val fullPlayRatio = totalFullPlays.toFloat() / totalPlays
        val fullPlayScore = (fullPlayRatio * 25).toInt()
        
        // Replay engagement contribution (15%) - indicates high engagement
        val replayRatio = totalReplays.toFloat() / totalPlays
        val replayScore = minOf(15, (replayRatio * 30).toInt())
        
        // Anti-skip contribution (15%)
        val antiSkipScore = ((1 - skipRate) * 15).toInt()
        
        // Discovery bonus (5%) - balanced between exploration and loyalty
        val discoveryRatio = if (totalPlays > 0) newTracksDiscovered.toFloat() / totalPlays else 0f
        val discoveryScore = when {
            discoveryRatio in 0.1f..0.4f -> 5  // Healthy discovery rate
            discoveryRatio in 0.05f..0.5f -> 3 // Acceptable
            else -> 1
        }
        
        return minOf(100, completionScore + fullPlayScore + replayScore + antiSkipScore + discoveryScore)
    }
    
    /**
     * Enhanced listening pattern detection.
     */
    val listeningPattern: String get() = when {
        completionRate >= 0.85f && skipRate <= 0.05f -> "Dedicated Listener"
        completionRate >= 0.75f && skipRate <= 0.1f -> "Focused Listener"
        skipRate >= 0.6f -> "Music Explorer"
        skipRate >= 0.4f -> "Selective Listener"
        bingeSessionsCount >= totalPlays * 0.1 -> "Artist Deep Diver"
        repeatListenRate >= 0.3f -> "Replay Enthusiast"
        newTracksDiscovered.toFloat() / maxOf(1, totalPlays) >= 0.5f -> "Discovery Mode"
        averageCompletionPercent >= 70 -> "Completionist"
        averageSessionLengthMs >= 60 * 60 * 1000 -> "Marathon Listener"
        else -> "Casual Listener"
    }
    
    val patternEmoji: String get() = when (listeningPattern) {
        "Dedicated Listener" -> "🎯"
        "Focused Listener" -> "🎧"
        "Music Explorer" -> "🔍"
        "Selective Listener" -> "👆"
        "Artist Deep Diver" -> "🔄"
        "Replay Enthusiast" -> "🔁"
        "Discovery Mode" -> "✨"
        "Completionist" -> "✅"
        "Marathon Listener" -> "⏱️"
        else -> "🎵"
    }
    
    /**
     * Get formatted listening time string.
     */
    val formattedListeningTime: String get() {
        val hours = totalListeningTimeMs / (60 * 60 * 1000)
        val minutes = (totalListeningTimeMs % (60 * 60 * 1000)) / (60 * 1000)
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
    
    /**
     * Engagement trend indicator.
     */
    val engagementTrend: String get() = when {
        overallEngagementScore >= 80 -> "🔥 Highly Engaged"
        overallEngagementScore >= 60 -> "📈 Above Average"
        overallEngagementScore >= 40 -> "➡️ Steady"
        overallEngagementScore >= 20 -> "📉 Below Average"
        else -> "💤 Low Activity"
    }
}

/**
 * Listening session analysis.
 */
data class ListeningSession(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val trackCount: Int,
    val totalDurationMs: Long,
    val uniqueArtists: Int,
    val averageCompletion: Float,
    val dominantGenre: String?,
    val dominantMood: TagBasedMoodAnalyzer.MoodCategory?,
    val isBingeSession: Boolean    // 3+ tracks from same artist
) {
    val durationMinutes: Int get() = (totalDurationMs / 60_000).toInt()
    
    val sessionType: String get() = when {
        isBingeSession -> "Artist Deep Dive"
        uniqueArtists == trackCount -> "Discovery Session"
        averageCompletion >= 80 -> "Focused Listening"
        averageCompletion < 40 -> "Browsing Session"
        else -> "Mixed Session"
    }
}

/**
 * Raw engagement data from database query.
 */
data class RawTrackEngagement(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long,
    @ColumnInfo(name = "avg_completion") val avgCompletion: Float,
    @ColumnInfo(name = "full_plays") val fullPlays: Int,
    @ColumnInfo(name = "partial_plays") val partialPlays: Int,
    @ColumnInfo(name = "skips") val skips: Int,
    @ColumnInfo(name = "last_played") val lastPlayed: Long
)

/**
 * Hourly engagement breakdown.
 */
data class HourlyEngagement(
    val hour: Int,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "avg_completion") val avgCompletion: Float,
    @ColumnInfo(name = "skip_count") val skipCount: Int
) {
    val hourLabel: String get() = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
    
    val engagementScore: Int get() {
        if (playCount == 0) return 0
        val skipRate = skipCount.toFloat() / playCount
        return ((avgCompletion * 0.7f) + ((1 - skipRate) * 30)).toInt()
    }
}
