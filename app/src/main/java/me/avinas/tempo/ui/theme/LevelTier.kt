package me.avinas.tempo.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import me.avinas.tempo.R

/**
 * A listener's rank tier — the one place the level thresholds live.
 *
 * The thresholds were written out three times: as colours in the profile screen,
 * as tier names on the milestone share card, and as an English "MYTHIC TIER"
 * pill in the level-up overlay. Those three did agree, but only because nobody
 * had added a tier yet; the next one (a 150, say) would have needed three edits
 * and would have shipped wrong in whichever file was forgotten. The colour, the
 * name, and the level that opens the tier are one fact about a tier, so they are
 * one entry here.
 *
 * @param minLevel the lowest level at which this tier is reached. Tiers are
 *   listed from the bottom up and [of] picks the last one the level clears.
 * @param label the tier's name, shared by the milestone card and the level-up
 *   overlay so the two never disagree about what to call a rank.
 * @param accent the tier's colour, used for the pill, the crest, and the
 *   milestone card's chrome.
 */
enum class LevelTier(
    val minLevel: Int,
    @param:StringRes val label: Int,
    val accent: Color,
) {
    /** Studio Teal. */
    STUDIO(0, R.string.level_tier_studio, TempoPrimary),

    /** Emerald. */
    EMERALD(5, R.string.level_tier_emerald, TempoSuccessBright),

    /** Cyan. */
    CYAN(10, R.string.level_tier_cyan, TempoAccent),

    /** Electric Blue. */
    ELECTRIC(20, R.string.level_tier_electric, TempoInfo),

    /** Purple. */
    PURPLE(35, R.string.level_tier_purple, Color(0xFFA855F7)),

    /** Gold. */
    GOLD(50, R.string.level_tier_gold, TempoWarningBright),

    /** Rose. */
    ROSE(75, R.string.level_tier_rose, Color(0xFFF43F5E)),

    /** Mythic Pink. */
    MYTHIC(100, R.string.level_tier_mythic, Color(0xFFEC4899)),
    ;

    companion object {
        /** The tier [level] reaches. Levels below [STUDIO] cannot exist, so this never fails. */
        fun of(level: Int): LevelTier = entries.last { level >= it.minLevel }
    }
}

/** The accent colour of the tier [level] reaches. */
internal fun getLevelTierAccent(level: Int): Color = LevelTier.of(level).accent
