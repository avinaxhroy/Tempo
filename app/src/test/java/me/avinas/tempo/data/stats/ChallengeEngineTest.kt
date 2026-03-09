package me.avinas.tempo.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChallengeEngineTest {

    @Test
    fun `generateChallenges produces 4 challenges on active day`() {
        val dateString = "2023-10-27"
        val metrics = ChallengeEngine.UserHistoryMetrics(
            avgSongsPerDay = 20,
            avgMinutesPerDay = 65,
            avgUniqueArtistsPerDay = 10,
            topArtists = listOf("Artist A", "Artist B"),
            topGenres = listOf("Rock", "Pop")
        )
        
        val challenges = ChallengeEngine.generateChallenges(dateString, metrics)
        
        assertEquals(4, challenges.size)
        // All should belong to the same date
        assertTrue(challenges.all { it.date == dateString })
        // Difficulties should be varied (usually Easy, Medium, Hard)
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.EASY })
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.MEDIUM })
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.HARD })
    }
    
    @Test
    fun `generateChallenges produces 4 challenges with fallback metrics`() {
        val dateString = "2023-10-27"
        // Null metrics also works with defaults
        val challenges = ChallengeEngine.generateChallenges(dateString, null)
        
        assertEquals(4, challenges.size)
        // Fallback targets should be reasonable
        val volumeChallenge = challenges.find { it.category == ChallengeEngine.Category.VOLUME }
        // Default easy volume without metadata is 5-20 songs
        assertTrue((volumeChallenge?.targetValue ?: 0) >= 5)
    }

}
