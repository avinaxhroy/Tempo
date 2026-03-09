package me.avinas.tempo.data.stats

import me.avinas.tempo.data.local.entities.DailyChallenge
import java.util.Calendar

/**
 * Engine for generating smart, personalized daily challenges.
 * 
 * Challenges auto-calibrate based on the user's recent listening history
 * and have strict maximum limits to ensure they remain practical.
 */
object ChallengeEngine {

    object Category {
        const val VOLUME = "VOLUME"
        const val DISCOVERY = "DISCOVERY"
        const val EXPLORATION = "EXPLORATION"
        const val TIME = "TIME"
        const val VARIETY = "VARIETY"
    }

    object Difficulty {
        const val EASY = "EASY"
        const val MEDIUM = "MEDIUM"
        const val HARD = "HARD"
    }

    // =============================================
    // Smart Limits (Safety Caps)
    // =============================================
    
    // Even if a user listens to 500 songs a day, we won't ask them to listen to 750.
    private const val MAX_SONGS_PER_DAY = 50
    private const val MAX_MINUTES_PER_DAY = 180
    private const val MAX_UNIQUE_ARTISTS = 20
    private const val MAX_NEW_ARTISTS = 5
    private const val MAX_NEW_GENRES = 2
    private const val MAX_EXPLORATION_SONGS = 10

    // =============================================
    // Fallback Defaults (New Users)
    // =============================================
    
    private const val DEFAULT_SONGS_EASY = 5
    private const val DEFAULT_SONGS_MEDIUM = 15
    private const val DEFAULT_SONGS_HARD = 25
    
    private const val DEFAULT_MINS_EASY = 15
    private const val DEFAULT_MINS_MEDIUM = 45
    private const val DEFAULT_MINS_HARD = 90

    /**
     * Data class to hold user's recent listening stats for calibration
     */
    data class UserHistoryMetrics(
        val avgSongsPerDay: Int,
        val avgMinutesPerDay: Int,
        val avgUniqueArtistsPerDay: Int,
        val topGenres: List<String>,
        val topArtists: List<String>
    )

    /**
     * Generate 3-5 challenges for today.
     * @param dateString YYYY-MM-DD
     * @param metrics The user's recent 7-day average metrics
     */
    fun generateChallenges(dateString: String, metrics: UserHistoryMetrics?): List<DailyChallenge> {
        val challenges = mutableListOf<DailyChallenge>()
        
        // Base metrics (use defaults if history is null/empty)
        val baseSongs = if (metrics != null && metrics.avgSongsPerDay > 0) metrics.avgSongsPerDay else DEFAULT_SONGS_MEDIUM
        val baseMins = if (metrics != null && metrics.avgMinutesPerDay > 0) metrics.avgMinutesPerDay else DEFAULT_MINS_MEDIUM
        val baseArtists = if (metrics != null && metrics.avgUniqueArtistsPerDay > 0) metrics.avgUniqueArtistsPerDay else 5

        // Calculate targets natively
        val easySongsTarget = calibrate(baseSongs, 0.8f, MAX_SONGS_PER_DAY).coerceAtLeast(3)
        val medSongsTarget = calibrate(baseSongs, 1.2f, MAX_SONGS_PER_DAY).coerceAtLeast(10)
        val hardSongsTarget = calibrate(baseSongs, 1.5f, MAX_SONGS_PER_DAY).coerceAtLeast(20)

        val easyMinsTarget = calibrate(baseMins, 0.8f, MAX_MINUTES_PER_DAY).coerceAtLeast(10)
        val medMinsTarget = calibrate(baseMins, 1.2f, MAX_MINUTES_PER_DAY).coerceAtLeast(30)
        val hardMinsTarget = calibrate(baseMins, 1.5f, MAX_MINUTES_PER_DAY).coerceAtLeast(60)
        
        val medArtistsTarget = calibrate(baseArtists, 1.2f, MAX_UNIQUE_ARTISTS).coerceAtLeast(5)

        // Generate 1 EASY, 2 MEDIUM, 1 HARD (4 challenges total)
        
        // --- EASY Challenge ---
        // Alternate between songs and minutes based on day of year
        val dayOfYear = getDayOfYear(dateString)
        if (dayOfYear % 2 == 0) {
            challenges.add(createVolumeSongsChallenge(dateString, Difficulty.EASY, easySongsTarget))
        } else {
            challenges.add(createVolumeMinsChallenge(dateString, Difficulty.EASY, easyMinsTarget))
        }

        // --- MEDIUM Challenge 1 (Variety/Time) ---
        if (dayOfYear % 3 == 0) {
            challenges.add(createTimeEarlyBirdChallenge(dateString))
        } else if (dayOfYear % 3 == 1) {
            challenges.add(createVarietyArtistsChallenge(dateString, Difficulty.MEDIUM, medArtistsTarget))
        } else {
            // Give the opposite of what Easy got
            if (dayOfYear % 2 == 0) {
                challenges.add(createVolumeMinsChallenge(dateString, Difficulty.MEDIUM, medMinsTarget))
            } else {
                challenges.add(createVolumeSongsChallenge(dateString, Difficulty.MEDIUM, medSongsTarget))
            }
        }

        // --- MEDIUM Challenge 2 (Exploration - Dynamic) ---
        // Dynamically pick a top artist or genre
        if (metrics != null && metrics.topArtists.isNotEmpty() && dayOfYear % 2 == 0) {
            val idx = dayOfYear % metrics.topArtists.size
            val artist = metrics.topArtists[idx]
            val count = calibrate(baseSongs, 0.5f, MAX_EXPLORATION_SONGS).coerceAtLeast(3)
            challenges.add(createExplorationArtistChallenge(dateString, Difficulty.MEDIUM, count, artist))
        } else if (metrics != null && metrics.topGenres.isNotEmpty()) {
            val idx = (dayOfYear / 2) % metrics.topGenres.size
            val genre = metrics.topGenres[idx]
            val count = calibrate(baseSongs, 0.5f, MAX_EXPLORATION_SONGS).coerceAtLeast(3)
            challenges.add(createExplorationGenreChallenge(dateString, Difficulty.MEDIUM, count, genre))
        } else {
            // Fallback to Discovery
            challenges.add(createDiscoveryGenresChallenge(dateString, Difficulty.MEDIUM, 2.coerceAtMost(MAX_NEW_GENRES)))
        }

        // --- HARD Challenge ---
        if (dayOfYear % 3 == 0) {
            challenges.add(createVolumeSongsChallenge(dateString, Difficulty.HARD, hardSongsTarget))
        } else if (dayOfYear % 3 == 1) {
            challenges.add(createVolumeMinsChallenge(dateString, Difficulty.HARD, hardMinsTarget))
        } else {
            challenges.add(createDiscoveryArtistsChallenge(dateString, Difficulty.HARD, 5.coerceAtMost(MAX_NEW_ARTISTS)))
        }

        return challenges
    }

    /**
     * Applies a multiplier to a base value, and safely caps it.
     */
    private fun calibrate(base: Int, multiplier: Float, maxCap: Int): Int {
        return (base * multiplier).toInt().coerceAtMost(maxCap)
    }

    private fun getDayOfYear(dateString: String): Int {
        return try {
            val parts = dateString.split("-")
            if (parts.size == 3) {
                val cal = Calendar.getInstance()
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                cal.get(Calendar.DAY_OF_YEAR)
            } else 1
        } catch (e: Exception) { 1 }
    }

    // =============================================
    // Challenge Factories
    // =============================================

    private fun getReward(difficulty: String): Int = when (difficulty) {
        Difficulty.EASY -> listOf(15, 20, 25).random()
        Difficulty.MEDIUM -> listOf(30, 40, 50).random()
        Difficulty.HARD -> listOf(60, 80, 100).random()
        else -> 20
    }

    private fun createVolumeSongsChallenge(date: String, difficulty: String, target: Int): DailyChallenge {
        return DailyChallenge(
            challengeId = "volume_songs_$difficulty",
            date = date,
            title = "🎶 $target-Song Sprint",
            description = "Listen to $target songs today.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.VOLUME,
            difficulty = difficulty
        )
    }

    private fun createVolumeMinsChallenge(date: String, difficulty: String, target: Int): DailyChallenge {
        return DailyChallenge(
            challengeId = "volume_mins_$difficulty",
            date = date,
            title = "🎧 Audio Immersion",
            description = "Listen for a total of $target minutes today.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.VOLUME,
            difficulty = difficulty
        )
    }

    private fun createVarietyArtistsChallenge(date: String, difficulty: String, target: Int): DailyChallenge {
        return DailyChallenge(
            challengeId = "variety_artists_$difficulty",
            date = date,
            title = "🌍 Broad Horizons",
            description = "Listen to $target different artists today.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.VARIETY,
            difficulty = difficulty
        )
    }

    private fun createDiscoveryArtistsChallenge(date: String, difficulty: String, target: Int): DailyChallenge {
        return DailyChallenge(
            challengeId = "discovery_artists_$difficulty",
            date = date,
            title = "🔭 Talent Scout",
            description = "Discover and listen to $target new artists.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.DISCOVERY,
            difficulty = difficulty
        )
    }

    private fun createDiscoveryGenresChallenge(date: String, difficulty: String, target: Int): DailyChallenge {
        return DailyChallenge(
            challengeId = "discovery_genres_$difficulty",
            date = date,
            title = "🎵 Sound Explorer",
            description = "Explore $target different genres.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.DISCOVERY,
            difficulty = difficulty
        )
    }

    private fun createExplorationArtistChallenge(date: String, difficulty: String, target: Int, artist: String): DailyChallenge {
        return DailyChallenge(
            challengeId = "explore_artist_${artist.hashCode()}",
            date = date,
            title = "⭐ Deep Dive: $artist",
            description = "Listen to $target songs by $artist.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.EXPLORATION,
            difficulty = difficulty,
            targetMetadata = artist
        )
    }

    private fun createExplorationGenreChallenge(date: String, difficulty: String, target: Int, genre: String): DailyChallenge {
        val displayGenre = genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return DailyChallenge(
            challengeId = "explore_genre_${genre.hashCode()}",
            date = date,
            title = "🎸 Genre Focus: $displayGenre",
            description = "Vibe out to $target $displayGenre songs.",
            xpReward = getReward(difficulty),
            targetValue = target,
            category = Category.EXPLORATION,
            difficulty = difficulty,
            targetMetadata = genre
        )
    }

    private fun createTimeEarlyBirdChallenge(date: String): DailyChallenge {
        return DailyChallenge(
            challengeId = "time_early_bird",
            date = date,
            title = "🌅 Early Bird",
            description = "Listen to 5 songs before 9 AM.",
            xpReward = getReward(Difficulty.MEDIUM),
            targetValue = 5,
            category = Category.TIME,
            difficulty = Difficulty.MEDIUM
        )
    }
}
