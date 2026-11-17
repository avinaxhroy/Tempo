package me.avinas.tempo.ui.spotlight

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The three hues a story page is told in.
 *
 * The story has a colour script: act one is cool indigo, act two warms through
 * pink and amber, act three is red and gold, and the last act settles into slate.
 * The script is the story's, not a page's — it lives here rather than inside any
 * one page so that the page that *draws* a slide and the sheet that *exports* it
 * cannot disagree about what colour it was.
 */
@Immutable
data class SpotlightStoryColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

/**
 * The colours for [page], falling back to the opening indigo when the story has
 * not settled on one yet.
 */
fun spotlightStoryColors(page: SpotlightStoryPage?): SpotlightStoryColors =
    when (page) {
        // ACT 1: SETUP - Cool, introspective blues/purples
        is SpotlightStoryPage.ListeningMinutes ->
            SpotlightStoryColors(Color(0xFF6366F1), Color(0xFF818CF8), Color(0xFF4F46E5))
        is SpotlightStoryPage.ListeningStreak ->
            SpotlightStoryColors(Color(0xFF7C3AED), Color(0xFF8B5CF6), Color(0xFF6D28D9))
        is SpotlightStoryPage.ListeningClock ->
            SpotlightStoryColors(Color(0xFF1E1B4B), Color(0xFF3730A3), Color(0xFF4338CA))
        // ACT 2: DISCOVERIES - Warm, exciting ambers/pinks
        is SpotlightStoryPage.TopArtist ->
            SpotlightStoryColors(Color(0xFFEC4899), Color(0xFFF472B6), Color(0xFFBE185D))
        is SpotlightStoryPage.TopAlbum ->
            SpotlightStoryColors(Color(0xFFD97706), Color(0xFFF59E0B), Color(0xFFB45309))
        is SpotlightStoryPage.TopSongs ->
            SpotlightStoryColors(Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0xFFD97706))
        is SpotlightStoryPage.TopTrackSetup ->
            SpotlightStoryColors(Color(0xFFFB923C), Color(0xFFFDBA74), Color(0xFFEA580C))
        is SpotlightStoryPage.TopGenres ->
            SpotlightStoryColors(Color(0xFF10B981), Color(0xFF34D399), Color(0xFF059669))
        is SpotlightStoryPage.DiscoveryCount ->
            SpotlightStoryColors(Color(0xFF06B6D4), Color(0xFF22D3EE), Color(0xFF0891B2))
        is SpotlightStoryPage.WeekdayVsWeekend ->
            SpotlightStoryColors(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF0369A1))
        is SpotlightStoryPage.BingeSession ->
            SpotlightStoryColors(Color(0xFFE11D48), Color(0xFFFB7185), Color(0xFFBE123C))
        is SpotlightStoryPage.TimeOfDayVibes ->
            SpotlightStoryColors(Color(0xFF8B5CF6), Color(0xFFA78BFA), Color(0xFF7C3AED))
        // ACT 3: CLIMAX - Bold, celebratory reds/golds
        is SpotlightStoryPage.AudioMood ->
            SpotlightStoryColors(Color(0xFFDC2626), Color(0xFFEF4444), Color(0xFFB91C1C))
        is SpotlightStoryPage.Personality ->
            SpotlightStoryColors(Color(0xFFEAB308), Color(0xFFFDE047), Color(0xFFCA8A04))
        is SpotlightStoryPage.BadgesEarned ->
            SpotlightStoryColors(Color(0xFFD97706), Color(0xFFF59E0B), Color(0xFFB45309))
        // ACT 4: RESOLUTION - Serene, reflective teals/slates
        is SpotlightStoryPage.LevelUp ->
            SpotlightStoryColors(Color(0xFF14B8A6), Color(0xFF2DD4BF), Color(0xFF0F766E))
        is SpotlightStoryPage.TitleEarned ->
            SpotlightStoryColors(Color(0xFF0D9488), Color(0xFF5EEAD4), Color(0xFF115E59))
        is SpotlightStoryPage.Conclusion ->
            SpotlightStoryColors(Color(0xFF64748B), Color(0xFF94A3B8), Color(0xFF475569))
        null -> SpotlightStoryColors(Color(0xFF6366F1), Color(0xFF818CF8), Color(0xFF4F46E5))
    }
