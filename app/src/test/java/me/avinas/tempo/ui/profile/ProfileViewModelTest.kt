package me.avinas.tempo.ui.profile

import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
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
            progress = 8, maxProgress = 10, isEarned = false
        ) // 80% -> Keep
        
        val badge2 = Badge(
            badgeId = "2", name = "B2", description = "D2", iconName = "star", category = "TIME",
            progress = 3, maxProgress = 10, isEarned = false
        ) // 30% -> Filter out
        
        val badge3 = Badge(
            badgeId = "3", name = "B3", description = "D3", iconName = "star", category = "TIME",
            progress = 10, maxProgress = 10, isEarned = true
        ) // Earned -> Filter out

        val state = ProfileUiState(allBadges = listOf(badge1, badge2, badge3))

        // When
        val result = state.almostUnlockedBadges

        // Then
        assertEquals(1, result.size)
        assertEquals("B1", result[0].name)
    }
}
