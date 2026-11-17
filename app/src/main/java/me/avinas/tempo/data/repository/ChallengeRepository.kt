package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.Converters
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
class ChallengeRepository
    @Inject
    constructor(
        private val gamificationDao: GamificationDao,
        private val statsDao: StatsDao,
        private val appDatabase: AppDatabase,
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
                        },
                )
            }
        }

        suspend fun generateDailyChallengesIfNeeded() {
            val today = LocalDate.now()
            val todayStr = today.toString()

            // Gather metrics OUTSIDE the transaction (read-only, no lock needed).
            val endMs = System.currentTimeMillis()
            val startMs = endMs - (7 * 24 * 60 * 60 * 1000L)

            val overview = statsDao.getCombinedBasicStats(startMs, endMs)

            // Average over 7 days (or 1 if exactly 0 to avoid div by zero)
            val avgSongs = ((overview.playCount) / 7f).coerceAtLeast(1f).toInt()
            val avgMins = ((overview.totalTimeMs) / 1000 / 60 / 7f).coerceAtLeast(1f).toInt()
            val avgArtists = ((overview.uniqueArtists) / 7f).coerceAtLeast(1f).toInt()

            // Candidate artists and genres for exploration challenges
            val topArtists = statsDao.getTopArtistsByPlayCount(startMs, endMs, 5, 0).map { it.artist }
            val topGenres =
                statsDao
                    .getTopGenresRaw(startMs, endMs, 5)
                    .flatMap { Converters.repairListColumnValue(it.genre) }
                    .distinctBy { it.lowercase() }

            // Typical first-listen hour personalizes the time-window challenge (28-day window).
            val typicalStartHour: Int? =
                try {
                    statsDao
                        .getTypicalStartHour(endMs - (28L * 24 * 60 * 60 * 1000L), endMs)
                        ?.hour
                } catch (e: Exception) {
                    Log.w(TAG, "Could not query typical start hour for challenges", e)
                    null
                }

            val metrics =
                ChallengeEngine.UserHistoryMetrics(
                    avgSongsPerDay = avgSongs,
                    avgMinutesPerDay = avgMins,
                    avgUniqueArtistsPerDay = avgArtists,
                    topArtists = topArtists,
                    topGenres = topGenres,
                    typicalStartHour = typicalStartHour,
                )

            Log.d(TAG, "Calibration metrics: $avgSongs songs/day, $avgMins mins/day, $avgArtists artists/day, startHour=$typicalStartHour")

            // Yesterday's challenge-id prefixes, used to avoid serving the same family twice.
            val yesterdayTypes =
                gamificationDao
                    .getChallengesForDate(today.minusDays(1).toString())
                    .map { it.challengeId.substringBefore('_') }
                    .toSet()

            // Check-then-insert MUST be atomic: ChallengeWorker (12:05 AM) and the Profile
            // screen can both reach here simultaneously. The unique (challenge_id, date) index
            // plus INSERT OR IGNORE makes generation idempotent even under that race.
            appDatabase.withTransaction {
                val existing = gamificationDao.getChallengesForDate(todayStr)
                if (existing.isNotEmpty()) {
                    Log.d(TAG, "Challenges for $todayStr already exist. Skipping generation.")
                    return@withTransaction
                }

                Log.i(TAG, "Generating new daily challenges for $todayStr ")
                val newChallenges = ChallengeEngine.generateChallenges(todayStr, metrics, yesterdayTypes)
                gamificationDao.insertChallengesIgnore(newChallenges)
                Log.i(TAG, "Generated ${newChallenges.size} challenges (yesterdayTypes=$yesterdayTypes).")
            }
        }

        suspend fun refreshChallengeProgress() {
            val todayStr = LocalDate.now().toString()
            val challenges = gamificationDao.getChallengesForDate(todayStr)

            if (challenges.isEmpty()) return

            val startOfDayMs =
                LocalDate
                    .now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            // Wrap the whole refresh in a transaction so per-challenge updates are atomic
            // and a mid-loop failure can't leave a half-updated set.
            appDatabase.withTransaction {
                for (challenge in challenges) {
                    if (challenge.isCompleted) continue

                    // Determine current progress
                    val currentProgress =
                        when (challenge.category) {
                            ChallengeEngine.Category.VOLUME -> {
                                if (challenge.challengeId.startsWith("volume_songs")) {
                                    gamificationDao.getTodayPlayCount(startOfDayMs)
                                } else if (challenge.challengeId.startsWith("volume_mins")) {
                                    (gamificationDao.getTodayListeningTimeMs(startOfDayMs) / 1000 / 60).toInt()
                                } else {
                                    0
                                }
                            }

                            ChallengeEngine.Category.VARIETY -> {
                                if (challenge.challengeId.startsWith("variety_artists")) {
                                    gamificationDao.getTodayUniqueArtists(startOfDayMs)
                                } else {
                                    0
                                }
                            }

                            ChallengeEngine.Category.EXPLORATION -> {
                                val metadata = challenge.targetMetadata ?: ""
                                if (challenge.challengeId.startsWith("explore_artist")) {
                                    gamificationDao.getTodayPlayCountForArtist(startOfDayMs, metadata)
                                } else if (challenge.challengeId.startsWith("explore_genre")) {
                                    gamificationDao.getTodayPlayCountForGenre(startOfDayMs, metadata)
                                } else {
                                    0
                                }
                            }

                            ChallengeEngine.Category.TIME -> {
                                // The window is stored in targetMetadata as "start,end" so a
                                // personalized challenge is queried with its own window. Legacy
                                // rows without metadata default to the classic 5-9 AM window.
                                val (winStart, winEnd) = parseTimeWindow(challenge.targetMetadata)
                                gamificationDao.getTodayPlayCountBetweenHours(startOfDayMs, winStart, winEnd)
                            }

                            ChallengeEngine.Category.DISCOVERY -> {
                                // Discovery requires artists/genres the user has NEVER heard before today
                                if (challenge.challengeId.startsWith("discovery_genres")) {
                                    gamificationDao.getTodayNewGenres(startOfDayMs)
                                } else {
                                    // discovery_artists: count only artists heard for the first time ever today
                                    gamificationDao.getTodayNewArtists(startOfDayMs)
                                }
                            }

                            else -> {
                                0
                            }
                        }

                    if (currentProgress != challenge.currentProgress) {
                        // Determine if newly completed
                        val isNowCompleted = currentProgress >= challenge.targetValue

                        val updated =
                            challenge.copy(
                                currentProgress = currentProgress.coerceAtMost(challenge.targetValue),
                                isCompleted = isNowCompleted,
                                completedAt = if (isNowCompleted) System.currentTimeMillis() else 0,
                            )

                        gamificationDao.upsertChallenge(updated)
                        Log.d(TAG, "Updated progress: ${challenge.title} -> $currentProgress / ${challenge.targetValue}")
                    }
                }
            }

            // XP/level sync is left to the caller: every current call site follows this
            // with GamificationRepository.fullRefresh() (which recomputes XP once). Recomputing here
            // too meant the same four full-history aggregates ran twice per refresh.
        }

        /**
         * Parse the "start,end" hour window stored in a TIME challenge's targetMetadata.
         * Defaults to the legacy Early Bird window (5-9 AM) when absent or malformed.
         */
        private fun parseTimeWindow(metadata: String?): Pair<Int, Int> {
            if (metadata.isNullOrBlank()) return 5 to 9
            return try {
                val parts = metadata.split(",")
                val start = parts[0].trim().toInt().coerceIn(0, 23)
                val end = parts[1].trim().toInt().coerceIn(0, 23)
                if (end > start) start to end else 5 to 9
            } catch (e: Exception) {
                5 to 9
            }
        }
    }
