package me.avinas.tempo.ui.profile

import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileViewModelTest {

    @Test
    fun `test streakAtRisk returns true when streak positive and last streak date not today`() {
        // Given
        val yesterday = LocalDate.now().minusDays(1).toString()
        val userLevel = UserLevel(
            currentStreak = 5,
            lastStreakDate = yesterday
        )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertTrue(state.streakAtRisk)
    }

    @Test
    fun `test streakAtRisk returns false when streak is 0`() {
        // Given
        val userLevel = UserLevel(
            currentStreak = 0,
            lastStreakDate = "2023-01-01"
        )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertFalse(state.streakAtRisk)
    }

    @Test
    fun `test streakAtRisk returns false when last streak date is today`() {
        // Given
        val today = LocalDate.now().toString()
        val userLevel = UserLevel(
            currentStreak = 10,
            lastStreakDate = today
        )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertFalse(state.streakAtRisk)
    }

    @Test
    fun `test almostUnlockedBadges filters correctly`() {
        // Given
        val badge1 = Badge(
            badgeId = "1", name = "B1", description = "D1", iconName = "star", category = "TIME",
            progress = 8, maxProgress = 10, isEarned = false, stars = 0
        ) // 80% -> Keep (not earned, close to unlock)
        
        val badge2 = Badge(
            badgeId = "2", name = "B2", description = "D2", iconName = "star", category = "TIME",
            progress = 3, maxProgress = 10, isEarned = false, stars = 0
        ) // 30% -> Filter out (too far)
        
        val badge3 = Badge(
            badgeId = "3", name = "B3", description = "D3", iconName = "star", category = "TIME",
            progress = 10, maxProgress = 10, isEarned = true, stars = 5
        ) // Maxed at 5 stars -> Filter out

        val badge4 = Badge(
            badgeId = "4", name = "B4", description = "D4", iconName = "star", category = "TIME",
            progress = 18, maxProgress = 20, isEarned = true, stars = 3
        ) // Earned, 90% toward next star -> Keep

        val state = ProfileUiState(allBadges = listOf(badge1, badge2, badge3, badge4))

        // When
        val result = state.almostUnlockedBadges

        // Then
        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "B1" })
        assertTrue(result.any { it.name == "B4" })
    }

    @Test
    fun `test totalStars and maxPossibleStars exclude beginner badges`() {
        val badges = listOf(
            Badge(badgeId = "1", name = "B1", description = "D1", iconName = "star", category = "TIME", stars = 3, isEarned = true),
            Badge(badgeId = "first_play", name = "First Note", description = "D2", iconName = "star", category = "MILESTONE", stars = 1, isEarned = true),
            Badge(badgeId = "time_1h", name = "First Hour", description = "D3", iconName = "star", category = "TIME", stars = 1, isEarned = true),
            Badge(badgeId = "3", name = "B3", description = "D4", iconName = "star", category = "TIME", stars = 5, isEarned = true)
        )
        val state = ProfileUiState(allBadges = badges)

        // first_play and time_1h are beginner badges, excluded from totals
        assertEquals(8, state.totalStars) // 3 + 5 (excludes first_play=1, time_1h=1)
        assertEquals(10, state.maxPossibleStars) // 2 non-beginner badges × 5
    }

    @Test
    fun `test steeper star multipliers produce correct thresholds`() {
        // With new multipliers [1, 3, 8, 20, 50]:
        // Explorer badge (base 10 artists): stars at 10, 30, 80, 200, 500
        assertEquals(0, GamificationEngine.computeStars(9, 10))   // Below threshold
        assertEquals(1, GamificationEngine.computeStars(10, 10))  // At base = ★1
        assertEquals(1, GamificationEngine.computeStars(29, 10))  // Below ★2
        assertEquals(2, GamificationEngine.computeStars(30, 10))  // 3x = ★2
        assertEquals(3, GamificationEngine.computeStars(80, 10))  // 8x = ★3
        assertEquals(4, GamificationEngine.computeStars(200, 10)) // 20x = ★4
        assertEquals(5, GamificationEngine.computeStars(500, 10)) // 50x = ★5
    }
}
