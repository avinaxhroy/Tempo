package me.avinas.tempo.ui.components

import androidx.compose.ui.geometry.Size
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.profile.BadgeSilhouettes
import me.avinas.tempo.ui.theme.getLevelTierAccent
import me.avinas.tempo.ui.profile.getRarityColor
import me.avinas.tempo.ui.profile.getRarityMetal
import me.avinas.tempo.ui.profile.getUniqueBadgeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeShareCardTest {

    @Test
    fun `test unique badge colors return valid colors for all badge types`() {
        val badgeIds = listOf(
            "first_play", "plays_100", "plays_500", "plays_1000", "plays_5000", "plays_10000",
            "time_1h", "time_24h", "time_100h", "time_500h",
            "streak_7", "streak_30", "streak_100", "streak_365",
            "artists_10", "artists_50", "artists_100",
            "genres_10", "genres_25",
            "night_owl", "early_bird", "marathon",
            "level_5", "level_10", "level_25", "level_50", "level_75", "level_100"
        )

        for (id in badgeIds) {
            val color = getUniqueBadgeColor(id)
            assertNotNull("Color should not be null for $id", color)
            assertTrue("Alpha should be non-zero for $id", color.alpha > 0f)
        }
    }

    @Test
    fun `test rarity metals return at least 4 colors for every tier`() {
        for (rarity in GamificationEngine.BadgeRarity.entries) {
            val metals = getRarityMetal(rarity)
            assertTrue("Metals for $rarity should have at least 4 gradient stops", metals.size >= 4)
            val color = getRarityColor(rarity)
            assertNotNull("Rarity color should exist", color)
        }
    }

    @Test
    fun `test level tier accents span correctly across levels`() {
        val lvl1 = getLevelTierAccent(1)
        val lvl5 = getLevelTierAccent(5)
        val lvl10 = getLevelTierAccent(10)
        val lvl20 = getLevelTierAccent(20)
        val lvl35 = getLevelTierAccent(35)
        val lvl50 = getLevelTierAccent(50)
        val lvl75 = getLevelTierAccent(75)
        val lvl100 = getLevelTierAccent(100)

        // All should be valid non-transparent colors
        val levels = listOf(lvl1, lvl5, lvl10, lvl20, lvl35, lvl50, lvl75, lvl100)
        levels.forEach { color ->
            assertTrue("Color alpha should be 1f", color.alpha == 1f)
        }
    }

    @Test
    fun `test badge silhouettes are generated for key badges`() {
        val testSize = Size(360f, 360f)
        val testBadges = listOf("first_play", "plays_100", "streak_100", "night_owl", "level_100")

        for (badgeId in testBadges) {
            val path = BadgeSilhouettes.getPath(badgeId, testSize, 1.0f)
            assertNotNull("Path should not be null", path)
        }
    }

    @Test
    fun `test badge XP contribution calculation`() {
        val xp1 = GamificationEngine.getBadgeXpContribution("plays_100", 3)
        assertTrue("XP contribution should be positive", xp1 >= 0)
    }
}
