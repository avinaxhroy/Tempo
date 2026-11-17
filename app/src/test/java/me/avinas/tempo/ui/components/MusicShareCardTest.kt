package me.avinas.tempo.ui.components

import me.avinas.tempo.R
import me.avinas.tempo.data.stats.GamificationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The cards are rasterized into a PNG, so their text has to be identical in every language
 * the app ships. That is why the resolvers return resource ids (plus format arguments)
 * instead of English sentences: asserting on the id keeps these tests locale-independent,
 * and any hardcoded English that creeps back in shows up as a missing id rather than a
 * passing test in one language only.
 */
class MusicShareCardTest {

    @Test
    fun `resolveSongStandingKicker prioritizes peak rank #1`() {
        val kicker = resolveSongStandingKicker(
            peakRank = 1,
            playCount = 500,
            completionRate = 0.95f,
            skipRate = 0.01f
        )
        assertEquals(R.string.share_kicker_all_time_track, kicker?.id)
        assertTrue("Rank #1 kicker takes no arguments", kicker?.args?.isEmpty() == true)
    }

    @Test
    fun `resolveSongStandingKicker handles top 5 and top 20 rankings`() {
        val top3 = resolveSongStandingKicker(peakRank = 3, playCount = 80, completionRate = 0.8f, skipRate = 0.1f)
        assertEquals(R.string.share_kicker_top_track_library, top3?.id)
        assertEquals(listOf(3), top3?.args)

        val top15 = resolveSongStandingKicker(peakRank = 15, playCount = 30, completionRate = 0.7f, skipRate = 0.15f)
        assertEquals(R.string.share_kicker_top_track_rotation, top15?.id)
        assertEquals(listOf(15), top15?.args)
    }

    @Test
    fun `resolveSongStandingKicker recognizes Century Club and Heavy Rotation`() {
        val century = resolveSongStandingKicker(peakRank = null, playCount = 120, completionRate = 0.6f, skipRate = 0.2f)
        assertEquals(R.string.share_kicker_century_club_track, century?.id)

        val heavy = resolveSongStandingKicker(peakRank = null, playCount = 65, completionRate = 0.6f, skipRate = 0.2f)
        assertEquals(R.string.share_kicker_heavy_rotation, heavy?.id)
    }

    @Test
    fun `resolveSongStandingKicker recognizes zero skips loyalty`() {
        val loyal = resolveSongStandingKicker(peakRank = null, playCount = 25, completionRate = 0.98f, skipRate = 0.02f)
        assertEquals(R.string.share_kicker_zero_skips, loyal?.id)
    }

    @Test
    fun `resolveSongStandingKicker says nothing about a casual track`() {
        // Nothing special to state, so nothing is stated. Printing a generic
        // marketing kicker here is what the card was told to stop doing.
        val casual = resolveSongStandingKicker(peakRank = null, playCount = 4, completionRate = 0.5f, skipRate = 0.3f)
        assertNull(casual)
    }

    @Test
    fun `resolveSongFlexStat prioritizes discovery date over peak rank and completion`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.OCTOBER, 15)
        }
        val flex = resolveSongFlexStat(
            firstPlayed = cal.timeInMillis,
            peakRank = 2,
            completionRate = 0.95f,
            isFavorite = true,
            dateLocale = Locale.US,
            datePattern = SHORT_MONTH_YEAR_FALLBACK
        )
        assertEquals(R.string.share_since_label, flex.label)

        val value = flex.value
        assertTrue("Discovery date must be pre-formatted, not a resource: $value", value is ShareValue.Formatted)
        assertTrue(
            "Expected Oct in since date: $value",
            (value as ShareValue.Formatted).text.contains("Oct", ignoreCase = true)
        )
    }

    @Test
    fun `resolveSongFlexStat uses peak rank when discovery date is absent`() {
        val flex = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = 4,
            completionRate = 0.9f,
            isFavorite = false,
            dateLocale = Locale.US,
            datePattern = SHORT_MONTH_YEAR_FALLBACK
        )
        assertEquals(R.string.share_peak_label, flex.label)
        val value = flex.value as ShareValue.Resource
        assertEquals(R.string.share_peak_rank_format, value.text.id)
        assertEquals(listOf(4), value.text.args)
    }

    @Test
    fun `resolveSongFlexStat uses finish rate when peak rank is absent`() {
        val flex = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = null,
            completionRate = 0.92f,
            isFavorite = false,
            dateLocale = Locale.US,
            datePattern = SHORT_MONTH_YEAR_FALLBACK
        )
        assertEquals(R.string.share_finish_label, flex.label)
        val value = flex.value as ShareValue.Resource
        assertEquals(R.string.share_percent_format, value.text.id)
        assertEquals(listOf(92), value.text.args)
    }

    @Test
    fun `resolveSongFlexStat falls back to status`() {
        val fav = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = null,
            completionRate = null,
            isFavorite = true,
            dateLocale = Locale.US,
            datePattern = SHORT_MONTH_YEAR_FALLBACK
        )
        assertEquals(R.string.share_status_label, fav.label)
        assertEquals(R.string.share_status_favorite, (fav.value as ShareValue.Resource).text.id)

        val nonFav = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = null,
            completionRate = null,
            isFavorite = false,
            dateLocale = Locale.US,
            datePattern = SHORT_MONTH_YEAR_FALLBACK
        )
        assertEquals(R.string.share_status_label, nonFav.label)
        assertEquals(R.string.share_status_spun, (nonFav.value as ShareValue.Resource).text.id)
    }

    @Test
    fun `resolveSongFlexHighlight selects best editorial flex`() {
        // Binge day takes priority
        val binge = resolveSongFlexHighlight(
            peakBingeDay = Pair("2024-02-14", 14),
            habitualHour = "Late Night",
            skipRate = 0.02f,
            playCount = 30,
            mood = "Energetic",
            genre = "Electronic"
        )
        assertNotNull(binge)
        assertEquals(R.string.share_highlight_binge, binge!!.id)
        assertEquals(listOf(14), binge.args)

        // Habitual hour when no binge
        val routine = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = "Late Night",
            skipRate = 0.1f,
            playCount = 20,
            mood = "Chill",
            genre = "Lo-Fi"
        )
        assertEquals(R.string.share_highlight_habit, routine!!.id)
        assertEquals(listOf("late night"), routine.args)

        // Loyalty when no habitual hour
        val loyalty = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.03f,
            playCount = 25,
            mood = null,
            genre = null
        )
        assertEquals(R.string.share_highlight_loyalty, loyalty!!.id)

        // Mood when loyalty doesn't qualify
        val moodHighlight = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.2f,
            playCount = 5,
            mood = "Melancholic",
            genre = "Indie"
        )
        assertEquals(R.string.share_highlight_vibe, moodHighlight!!.id)
        assertEquals(listOf("Melancholic"), moodHighlight.args)

        // Genre fallback
        val genreHighlight = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.2f,
            playCount = 5,
            mood = null,
            genre = "Synthwave"
        )
        assertEquals(R.string.share_highlight_genre, genreHighlight!!.id)
        assertEquals(listOf("Synthwave"), genreHighlight.args)

        // Null when nothing available
        val empty = resolveSongFlexHighlight(null, null, null, 1, null, null)
        assertNull(empty)
    }

    @Test
    fun `resolveArtistFlexPill selects streak then discovery date`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2022, Calendar.JUNE, 1)
        }
        val streak = resolveArtistFlexPill(
            listeningStreakDays = 7,
            firstListenedDate = cal.timeInMillis,
            firstDiscoveryTimestamp = 1609459200000L,
            dateLocale = Locale.US,
            monthYearPattern = MONTH_YEAR_FALLBACK,
            yearPattern = YEAR_FALLBACK
        )
        assertNotNull(streak)
        assertEquals(R.string.share_artist_streak_days, streak!!.id)
        assertEquals(listOf(7), streak.args)

        val discovery = resolveArtistFlexPill(
            listeningStreakDays = 1,
            firstListenedDate = cal.timeInMillis,
            firstDiscoveryTimestamp = null,
            dateLocale = Locale.US,
            monthYearPattern = MONTH_YEAR_FALLBACK,
            yearPattern = YEAR_FALLBACK
        )
        assertNotNull(discovery)
        assertEquals(R.string.share_artist_listening_since, discovery!!.id)
        assertEquals(1, discovery.args.size)
        assertTrue(
            "Listening-since date should be June 2022: ${discovery.args}",
            (discovery.args.first() as String).contains("2022"),
        )

        val discoveredIn = resolveArtistFlexPill(
            listeningStreakDays = 0,
            firstListenedDate = null,
            firstDiscoveryTimestamp = 1609459200000L,
            dateLocale = Locale.US,
            monthYearPattern = MONTH_YEAR_FALLBACK,
            yearPattern = YEAR_FALLBACK
        )
        assertEquals(R.string.share_artist_discovered_in, discoveredIn!!.id)
        assertEquals(listOf("2021"), discoveredIn.args)

        val genreOnly = resolveArtistFlexPill(
            0,
            null,
            null,
            topGenre = "Indie Rock",
            dateLocale = Locale.US,
            monthYearPattern = MONTH_YEAR_FALLBACK,
            yearPattern = YEAR_FALLBACK
        )
        assertEquals(R.string.share_artist_top_genre, genreOnly!!.id)
        assertEquals(listOf("Indie Rock"), genreOnly.args)

        val none = resolveArtistFlexPill(
            0,
            null,
            null,
            null,
            dateLocale = Locale.US,
            monthYearPattern = MONTH_YEAR_FALLBACK,
            yearPattern = YEAR_FALLBACK
        )
        assertNull(none)
    }

    @Test
    fun `resolveArtistStandingKicker handles percentile and play count tiers`() {
        val number1 = resolveArtistStandingKicker(percentile = 0.0, playCount = 300)
        assertEquals(R.string.share_kicker_all_time_artist, number1?.id)

        val top1 = resolveArtistStandingKicker(percentile = 0.8, playCount = 300)
        assertEquals(R.string.share_kicker_top_1_percent, top1?.id)

        val top5 = resolveArtistStandingKicker(percentile = 4.2, playCount = 150)
        assertEquals(R.string.share_kicker_top_5_percent, top5?.id)

        val top10 = resolveArtistStandingKicker(percentile = 9.5, playCount = 100)
        assertEquals(R.string.share_kicker_top_10_percent, top10?.id)

        // Volume guard prevents false top 1% claim with only 5 plays. With no
        // standing to report the card prints no pill at all.
        val lowVolume = resolveArtistStandingKicker(percentile = 0.5, playCount = 5)
        assertNull(lowVolume)

        val century = resolveArtistStandingKicker(percentile = null, playCount = 650)
        assertEquals(R.string.share_kicker_century_club_artist, century?.id)

        val heavy = resolveArtistStandingKicker(percentile = null, playCount = 280)
        assertEquals(R.string.share_kicker_heavy_rotation_artist, heavy?.id)

        val regular = resolveArtistStandingKicker(percentile = null, playCount = 60)
        assertEquals(R.string.share_kicker_rotation_regular, regular?.id)

        val spotlight = resolveArtistStandingKicker(percentile = null, playCount = 12)
        assertNull(spotlight)
    }

    @Test
    fun `formatShareDate honours the pattern and locale it is given`() {
        // 2023-10-15T00:00:00Z
        val timestamp = 1697328000000L

        assertEquals(
            "Oct '23",
            formatShareDate(timestamp, SHORT_MONTH_YEAR_FALLBACK, Locale.US),
        )
        assertEquals(
            "October 2023",
            formatShareDate(timestamp, "MMMM yyyy", Locale.US),
        )
        // Month names come from the locale, not from the pattern's English letters.
        assertEquals(
            "окт. 2023",
            formatShareDate(timestamp, "MMM yyyy", Locale.forLanguageTag("ru")),
        )
    }

    @Test
    fun `shareDatePattern falls back when the platform has no suggestion`() {
        // Under `unitTests.isReturnDefaultValues = true` the framework formatter stub
        // returns null, which is exactly the unsupported-skeleton path.
        assertEquals(
            MONTH_YEAR_FALLBACK,
            shareDatePattern(Locale.US, "MMMMyyyy", MONTH_YEAR_FALLBACK),
        )
    }

    @Test
    fun `badge category and rarity map onto share resources`() {
        assertEquals(R.string.share_category_milestones, badgeCategoryLabel("MILESTONE"))
        assertEquals(R.string.share_category_time, badgeCategoryLabel("TIME"))
        assertEquals(R.string.share_category_streaks, badgeCategoryLabel("STREAK"))
        assertEquals(R.string.share_category_discovery, badgeCategoryLabel("DISCOVERY"))
        assertEquals(R.string.share_category_engagement, badgeCategoryLabel("ENGAGEMENT"))
        assertEquals(R.string.share_category_levels, badgeCategoryLabel("LEVEL"))
        assertEquals(0, badgeCategoryLabel("SOMETHING_NEW"))

        assertEquals(R.string.share_rarity_common, badgeRarityLabel(GamificationEngine.BadgeRarity.COMMON))
        assertEquals(R.string.share_rarity_rare, badgeRarityLabel(GamificationEngine.BadgeRarity.RARE))
        assertEquals(R.string.share_rarity_epic, badgeRarityLabel(GamificationEngine.BadgeRarity.EPIC))
        assertEquals(
            R.string.share_rarity_legendary,
            badgeRarityLabel(GamificationEngine.BadgeRarity.LEGENDARY),
        )
        assertEquals(R.string.share_rarity_mythic, badgeRarityLabel(GamificationEngine.BadgeRarity.MYTHIC))
    }

    @Test
    fun `milestoneTierLabel names the tier the level is in`() {
        assertEquals(R.string.level_tier_mythic, milestoneTierLabel(100))
        assertEquals(R.string.level_tier_rose, milestoneTierLabel(75))
        assertEquals(R.string.level_tier_gold, milestoneTierLabel(50))
        assertEquals(R.string.level_tier_purple, milestoneTierLabel(35))
        assertEquals(R.string.level_tier_electric, milestoneTierLabel(20))
        assertEquals(R.string.level_tier_cyan, milestoneTierLabel(10))
        assertEquals(R.string.level_tier_emerald, milestoneTierLabel(5))
        assertEquals(R.string.level_tier_studio, milestoneTierLabel(4))
        assertEquals(R.string.level_tier_studio, milestoneTierLabel(1))
    }

    @Test
    fun `ShareTheme DITHER is 6th theme and prints the cover as an ordered dither`() {
        // Must be the 6th theme (index 5)
        assertEquals(ShareTheme.DITHER, ShareTheme.entries[5])
        assertEquals(6, ShareTheme.entries.size)

        val palette = ShareTheme.DITHER.palette
        // The plate is the artwork, dithered: unlike the old GRAIN theme it
        // needs the cover, because the picture is what gets quantised.
        assertTrue("DITHER builds its plate from the artwork", palette.usesArtwork)
        assertEquals(ShareBackdropStyle.DITHER_FIELD, palette.backdrop)
        // The plate is paper, not a print on black: one dark ink on a warm
        // ground, so the card's type is read the way a book's is. The first
        // cut had white type on a sepia plate and every subtitle vanished.
        assertFalse("DITHER prints dark ink on light paper", palette.isDark)
        assertEquals(3, palette.gradient.size)
        assertEquals(3, palette.overlay.size)
        assertNotNull(palette.accent)
        assertNotNull(palette.glowTop)
        assertNotNull(palette.glowBottom)
    }

    private companion object {
        /** Patterns the card falls back to when the platform offers none; see ShareCards.kt. */
        const val SHORT_MONTH_YEAR_FALLBACK = "MMM ''yy"
        const val MONTH_YEAR_FALLBACK = "MMM yyyy"
        const val YEAR_FALLBACK = "yyyy"
    }
}
