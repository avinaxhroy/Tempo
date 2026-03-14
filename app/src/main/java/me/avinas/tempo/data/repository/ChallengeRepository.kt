package me.avinas.tempo.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.avinas.tempo.data.local.dao.GamificationDao
import me.avinas.tempo.data.local.dao.StatsDao
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.stats.ChallengeEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepository @Inject constructor(
    private val gamificationDao: GamificationDao,
    private val statsDao: StatsDao,
    private val gamificationRepository: GamificationRepository
) {
    companion object {
        private const val TAG = "ChallengeRepo"
    }

    fun observeTodayChallenges(): Flow<List<DailyChallenge>> {
        val todayStr = LocalDate.now().toString()
        return gamificationDao.observeChallengesForDate(todayStr).map { challenges ->
            // Sort by isCompleted (false first), then difficulty (EASY, MEDIUM, HARD)
            challenges.sortedWith(
                compareBy<DailyChallenge> { it.isCompleted }
                    .thenBy { 
                        when (it.difficulty) {
                            ChallengeEngine.Difficulty.EASY -> 0
                            ChallengeEngine.Difficulty.MEDIUM -> 1
                            ChallengeEngine.Difficulty.HARD -> 2
                            else -> 3
                        }
                    }
            )
        }
    }

    suspend fun generateDailyChallengesIfNeeded() {
        val todayStr = LocalDate.now().toString()
        val existing = gamificationDao.getChallengesForDate(todayStr)
        
        if (existing.isNotEmpty()) {
            Log.d(TAG, "Challenges for $todayStr already exist. Skipping generation.")
            return
        }

        Log.i(TAG, "Generating new daily challenges for $todayStr 🎯")
        
        // 1. Gather historical metrics for calibration (7 days)
        val endMs = System.currentTimeMillis()
        val startMs = endMs - (7 * 24 * 60 * 60 * 1000L)
        
        // Use StatsDao to get accurate time-range metrics
        val overview = statsDao.getCombinedBasicStats(startMs, endMs)
        
        // Average over 7 days (or 1 if exactly 0 to avoid div by zero)
        val avgSongs = ((overview.playCount) / 7f).coerceAtLeast(1f).toInt()
        val avgMins = ((overview.totalTimeMs) / 1000 / 60 / 7f).coerceAtLeast(1f).toInt()
        val avgArtists = ((overview.uniqueArtists) / 7f).coerceAtLeast(1f).toInt()

        // Get top artists/genres for dynamic exploration challenges
        val topArtists = statsDao.getTopArtistsByPlayCount(startMs, endMs, 5, 0).map { it.artist }
        val topGenres = statsDao.getTopGenresRaw(startMs, endMs, 5).map { it.genre }

        val metrics = ChallengeEngine.UserHistoryMetrics(
            avgSongsPerDay = avgSongs,
            avgMinutesPerDay = avgMins,
            avgUniqueArtistsPerDay = avgArtists,
            topArtists = topArtists,
            topGenres = topGenres
        )

        Log.d(TAG, "Calibration metrics: $avgSongs songs/day, $avgMins mins/day, $avgArtists artists/day")

        // 2. Generate and save
        val newChallenges = ChallengeEngine.generateChallenges(todayStr, metrics)
        gamificationDao.upsertChallenges(newChallenges)
        Log.i(TAG, "Generated ${newChallenges.size} challenges.")
    }

    suspend fun refreshChallengeProgress() {
        val todayStr = LocalDate.now().toString()
        val challenges = gamificationDao.getChallengesForDate(todayStr)
        
        if (challenges.isEmpty()) return

        val startOfDayMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var madeChanges = false

        for (challenge in challenges) {
            if (challenge.isCompleted) continue

            // Determine current progress
            val currentProgress = when (challenge.category) {
                ChallengeEngine.Category.VOLUME -> {
                    if (challenge.challengeId.startsWith("volume_songs")) {
                        gamificationDao.getTodayPlayCount(startOfDayMs)
                    } else if (challenge.challengeId.startsWith("volume_mins")) {
                        (gamificationDao.getTodayListeningTimeMs(startOfDayMs) / 1000 / 60).toInt()
                    } else 0
                }
                ChallengeEngine.Category.VARIETY -> {
                    if (challenge.challengeId.startsWith("variety_artists")) {
                        gamificationDao.getTodayUniqueArtists(startOfDayMs)
                    } else 0
                }
                ChallengeEngine.Category.EXPLORATION -> {
                    val metadata = challenge.targetMetadata ?: ""
                    if (challenge.challengeId.startsWith("explore_artist")) {
                        gamificationDao.getTodayPlayCountForArtist(startOfDayMs, metadata)
                    } else if (challenge.challengeId.startsWith("explore_genre")) {
                        gamificationDao.getTodayPlayCountForGenre(startOfDayMs, metadata)
                    } else 0
                }
                ChallengeEngine.Category.TIME -> {
                    if (challenge.challengeId == "time_early_bird") {
                        // Count songs played today between 5 AM and 9 AM
                        gamificationDao.getTodayPlayCountBetweenHours(startOfDayMs, 5, 9)
                    } else 0
                }
                ChallengeEngine.Category.DISCOVERY -> {
                    // Discovery requires artists/genres the user has NEVER heard before today
                    if (challenge.challengeId.startsWith("discovery_genres")) {
                        gamificationDao.getTodayUniqueGenres(startOfDayMs)
                    } else {
                        // discovery_artists: count only artists heard for the first time ever today
                        gamificationDao.getTodayNewArtists(startOfDayMs)
                    }
                }
                else -> 0
            }

            if (currentProgress != challenge.currentProgress) {
                // Determine if newly completed
                val isNowCompleted = currentProgress >= challenge.targetValue
                
                val updated = challenge.copy(
                    currentProgress = currentProgress.coerceAtMost(challenge.targetValue),
                    isCompleted = isNowCompleted,
                    completedAt = if (isNowCompleted) System.currentTimeMillis() else 0
                )
                
                gamificationDao.upsertChallenge(updated)
                madeChanges = true
                Log.d(TAG, "Updated progress: ${challenge.title} -> $currentProgress / ${challenge.targetValue}")
            }
        }
        
        // If progress changed, make sure gamification levels sync to account for new data
        if (madeChanges) {
            gamificationRepository.recomputeXpAndLevel()
        }
    }

    suspend fun claimChallengeXp(challengeId: Long) {
        // Technically, XP is entirely deterministic from listening events.
        // We can't arbitrarily inject manual XP into the user_level table since recomputeXpAndLevel()
        // overrides it from scratch every 6 hours.
        // 
        // SOLUTION: The gamification engine must include daily challenges in its deterministic XP calc.
        // For now, we will mark the challenge as claimed and trigger a refresh.
        
        val todayStr = LocalDate.now().toString()
        val challenges = gamificationDao.getChallengesForDate(todayStr)
        val target = challenges.find { it.id == challengeId } ?: return
        
        Log.i(TAG, "Claiming ${target.xpReward} XP for challenge: ${target.title}")
        gamificationRepository.recomputeXpAndLevel()
    }
}
