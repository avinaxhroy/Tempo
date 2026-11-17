package me.avinas.tempo.ui.profile

import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.theme.getLevelTierAccent
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
        val userLevel =
            UserLevel(
                currentStreak = 5,
                lastStreakDate = yesterday,
            )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertTrue(state.streakAtRisk)
    }

    @Test
    fun `test streakAtRisk returns false when streak is 0`() {
        // Given
        val userLevel =
            UserLevel(
                currentStreak = 0,
                lastStreakDate = "2023-01-01",
            )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertFalse(state.streakAtRisk)
    }

    @Test
    fun `test streakAtRisk returns false when last streak date is today`() {
        // Given
        val today = LocalDate.now().toString()
        val userLevel =
            UserLevel(
                currentStreak = 10,
                lastStreakDate = today,
            )
        val state = ProfileUiState(userLevel = userLevel)

        // Then
        assertFalse(state.streakAtRisk)
    }

    @Test
    fun `test almostUnlockedBadges filters correctly`() {
        // Given
        val badge1 =
            Badge(
                badgeId = "1",
                name = "B1",
                description = "D1",
                iconName = "star",
                category = "TIME",
                progress = 8,
                maxProgress = 10,
                isEarned = false,
                stars = 0,
            ) // 80% -> Keep (not earned, close to unlock)

        val badge2 =
            Badge(
                badgeId = "2",
                name = "B2",
                description = "D2",
                iconName = "star",
                category = "TIME",
                progress = 3,
                maxProgress = 10,
                isEarned = false,
                stars = 0,
            ) // 30% -> Filter out (too far)

        val badge3 =
            Badge(
                badgeId = "3",
                name = "B3",
                description = "D3",
                iconName = "star",
                category = "TIME",
                progress = 10,
                maxProgress = 10,
                isEarned = true,
                stars = 5,
            ) // Maxed at 5 stars -> Filter out

        val badge4 =
            Badge(
                badgeId = "4",
                name = "B4",
                description = "D4",
                iconName = "star",
                category = "TIME",
                progress = 18,
                maxProgress = 20,
                isEarned = true,
                stars = 3,
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
        val badges =
            listOf(
                Badge(badgeId = "1", name = "B1", description = "D1", iconName = "star", category = "TIME", stars = 3, isEarned = true),
                Badge(
                    badgeId = "first_play",
                    name = "First Note",
                    description = "D2",
                    iconName = "star",
                    category = "MILESTONE",
                    stars = 1,
                    isEarned = true,
                ),
                Badge(
                    badgeId = "time_1h",
                    name = "First Hour",
                    description = "D3",
                    iconName = "star",
                    category = "TIME",
                    stars = 1,
                    isEarned = true,
                ),
                Badge(badgeId = "3", name = "B3", description = "D4", iconName = "star", category = "TIME", stars = 5, isEarned = true),
            )
        val state = ProfileUiState(allBadges = badges)

        // first_play and time_1h are beginner badges, excluded from totals
        assertEquals(8, state.totalStars) // 3 + 5 (excludes first_play=1, time_1h=1)
        assertEquals(10, state.maxPossibleStars) // 2 non-beginner badges × 5
    }

    @Test
    fun `test per-badge star ladders stay reachable`() {
        // Every badge has its own explicit ★5 target; tiers rise strictly and ★5 is the target.
        // Beginner badges are exempt: they cap at one star, so their ladder is intentionally flat.
        for (def in GamificationEngine.ALL_BADGE_DEFINITIONS) {
            val thresholds = GamificationEngine.starThresholds(def)
            assertEquals(GamificationEngine.MAX_STARS, thresholds.size)
            assertEquals(def.threshold, thresholds.first())
            assertEquals(maxOf(def.fiveStarThreshold, def.threshold), thresholds.last())
            if (def.badgeId in GamificationEngine.BEGINNER_BADGES) continue
            for (i in 1 until thresholds.size) {
                assertTrue(
                    "${def.badgeId} tiers must strictly increase: ${thresholds.toList()}",
                    thresholds[i] > thresholds[i - 1],
                )
            }
        }
    }

    @Test
    fun `test rebalance never removes an already-earned star`() {
        // Old uniform ladder was 1x/3x/8x/20x/50x the base. The new per-badge targets must be
        // no stricter at any tier, so existing users can only keep or gain stars.
        val oldMultipliers = intArrayOf(1, 3, 8, 20, 50)
        for (def in GamificationEngine.ALL_BADGE_DEFINITIONS) {
            val newTiers = GamificationEngine.starThresholds(def)
            for (i in oldMultipliers.indices) {
                val oldThreshold = def.threshold * oldMultipliers[i]
                assertTrue(
                    "${def.badgeId} ★${i + 1} got harder: ${newTiers[i]} > $oldThreshold",
                    newTiers[i] <= oldThreshold,
                )
            }
        }
    }

    @Test
    fun `test no badge demands an impossible five star grind`() {
        // Regression guard for the old uniform 50x multiplier, which pushed badges like
        // plays_10000 to 500,000 plays and level_100 to level 5,000.
        val fiveStar = GamificationEngine.ALL_BADGE_DEFINITIONS.associate { it.badgeId to GamificationEngine.starThresholds(it).last() }
        assertTrue("plays_10000 ★5 must stay realistic", fiveStar.getValue("plays_10000") <= 50_000)
        assertTrue("level_100 ★5 must stay realistic", fiveStar.getValue("level_100") <= 300)
        assertTrue("streak_365 ★5 must be at most ~2 years", fiveStar.getValue("streak_365") <= 730)
        assertTrue("artists_100 ★5 must stay reachable", fiveStar.getValue("artists_100") <= 500)
    }

    @Test
    fun `test computeStars honours per-badge ladder and beginner cap`() {
        val explorer = GamificationEngine.ALL_BADGE_DEFINITIONS.first { it.badgeId == "artists_10" }
        val tiers = GamificationEngine.starThresholds(explorer)
        assertEquals(0, GamificationEngine.computeStars(tiers[0] - 1, explorer))
        assertEquals(1, GamificationEngine.computeStars(tiers[0], explorer))
        assertEquals(2, GamificationEngine.computeStars(tiers[1], explorer))
        assertEquals(3, GamificationEngine.computeStars(tiers[2], explorer))
        assertEquals(4, GamificationEngine.computeStars(tiers[3], explorer))
        assertEquals(5, GamificationEngine.computeStars(tiers[4], explorer))
        assertEquals(5, GamificationEngine.computeStars(tiers[4] * 10, explorer))

        // Beginner badges cap at a single star regardless of raw progress.
        val firstNote = GamificationEngine.ALL_BADGE_DEFINITIONS.first { it.badgeId == "first_play" }
        assertEquals(0, GamificationEngine.computeStars(0, firstNote))
        assertEquals(1, GamificationEngine.computeStars(1, firstNote))
        assertEquals(1, GamificationEngine.computeStars(10_000, firstNote))
    }

    @Test
    fun `test badge rarity and XP contribution calculation`() {
        val commonRarity = GamificationEngine.getRarity("first_play")
        assertEquals(GamificationEngine.BadgeRarity.COMMON, commonRarity)
        assertEquals(25, commonRarity.xpPerStar)

        val mythicRarity = GamificationEngine.getRarity("streak_365")
        assertEquals(GamificationEngine.BadgeRarity.MYTHIC, mythicRarity)
        assertEquals(1500, mythicRarity.xpPerStar)

        // 3 stars on mythic badge = 3 * 1500 = 4500 XP
        val mythicXp = GamificationEngine.getBadgeXpContribution("streak_365", 3)
        assertEquals(4500L, mythicXp)

        // 0 stars = 0 XP
        assertEquals(0L, GamificationEngine.getBadgeXpContribution("streak_365", 0))
    }

    @Test
    fun `test level tier accents match prestige progression`() {
        // Levels 1-4: Studio Tier
        val lvl1Color = getLevelTierAccent(1)
        // Level 5+: Emerald
        val lvl5Color = getLevelTierAccent(5)
        // Level 10+: Cyan
        val lvl10Color = getLevelTierAccent(10)
        // Level 50+: Gold
        val lvl50Color = getLevelTierAccent(50)
        // Level 100+: Mythic Pink
        val lvl100Color = getLevelTierAccent(100)

        org.junit.Assert.assertNotEquals(lvl1Color, lvl5Color)
        org.junit.Assert.assertNotEquals(lvl5Color, lvl10Color)
        org.junit.Assert.assertNotEquals(lvl10Color, lvl50Color)
        org.junit.Assert.assertNotEquals(lvl50Color, lvl100Color)
    }

    @Test
    fun `test listener title hierarchy progression`() {
        assertEquals("Newcomer", GamificationEngine.computeTitle(0, 0))
        assertEquals("Casual Listener", GamificationEngine.computeTitle(5, 10))
        assertEquals("Music Fan", GamificationEngine.computeTitle(10, 50))
        assertEquals("Music Enthusiast", GamificationEngine.computeTitle(20, 100))
        assertEquals("Dedicated Listener", GamificationEngine.computeTitle(35, 250))
        assertEquals("Music Connoisseur", GamificationEngine.computeTitle(50, 500))
        assertEquals("Music Legend", GamificationEngine.computeTitle(75, 750))
        assertEquals("Audiophile", GamificationEngine.computeTitle(100, 1000))
        assertEquals("Sound God", GamificationEngine.computeTitle(150, 2000))
    }
}
