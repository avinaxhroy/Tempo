package me.avinas.tempo.ui.spotlight

import androidx.compose.runtime.Immutable

/**
 * Sealed class representing different types of Spotlight insight cards.
 * All subclasses are marked @Immutable to optimize Compose recomposition.
 */
@Immutable
sealed class SpotlightCardData {
    abstract val id: String
    
    // 1. Cosmic Clock (Day vs Night) - Replaces basic time distribution
    @Immutable
    data class CosmicClock(
        override val id: String = "cosmic_clock",
        val dayPercentage: Int, // 6am - 6pm
        val nightPercentage: Int, // 6pm - 6am
        val dayTopGenre: String,
        val nightTopGenre: String,
        val sunListenerType: String, // "Sun Chaser" or "Moon Owl"
        val hourlyLevels: List<Int>, // 24 values representing activity level 0-100 for each hour
        val confidence: String = "MEDIUM" // Phase 3: HIGH/MEDIUM/LOW data quality
    ) : SpotlightCardData()

    // 2. Weekend Warrior (Weekday vs Weekend)
    @Immutable
    data class WeekendWarrior(
        override val id: String = "weekend_warrior",
        val weekdayAverage: Int,
        val weekendAverage: Int,
        val warriorType: String, // "Weekend Warrior", "Daily Grinder", "Consistent Vibez"
        val percentageDifference: Int,
        val confidence: String = "MEDIUM" // Phase 3: HIGH/MEDIUM/LOW data quality
    ) : SpotlightCardData()

    // 3. The Forgotten Favorite (Nostalgia)
    @Immutable
    data class ForgottenFavorite(
        override val id: String = "forgotten_favorite",
        val songTitle: String,
        val artistName: String,
        val albumArtUrl: String?,
        val peakDate: String, // "played heavily in March"
        val daysSinceLastPlay: Int
    ) : SpotlightCardData()

    // 4. The Deep Dive (Longest Session)
    @Immutable
    data class DeepDive(
        override val id: String = "deep_dive",
        val durationMinutes: Int,
        val date: String,
        val timeOfDay: String, // "Tuesday Afternoon"
        val topArtist: String? // Optional: who you were listening to
    ) : SpotlightCardData()

    // 5. New Obsession (Discovery)
    @Immutable
    data class NewObsession(
        override val id: String = "new_obsession",
        val artistName: String,
        val artistImageUrl: String?,
        val playCount: Int,
        val daysKnown: Int,
        val percentOfListening: Int, // % of total listening since discovery
        val confidence: String = "MEDIUM" // Phase 3: HIGH/MEDIUM/LOW data quality
    ) : SpotlightCardData()
    
    // --- Enhanced Existing Cards ---

    @Immutable
    data class EarlyAdopter(
        override val id: String = "early_adopter",
        val artistName: String,
        val artistImageUrl: String?,
        val discoveryDate: String,
        val daysBeforeMainstream: Int // Mock metric or real if possible
    ) : SpotlightCardData()

    @Immutable
    data class ListeningPeak(
        override val id: String = "listening_peak",
        val peakTime: String, // Keep for backward compat or single point
        val peakTimeRange: String, // "22:00 - 02:00"
        val totalMinutes: Int
    ) : SpotlightCardData()


    // 8. Artist Loyalty ("The Deep Cut") -> Replaces "The Stan Card" concept
    @Immutable
    data class ArtistLoyalty(
        override val id: String = "artist_loyalty",
        val artistName: String,
        val artistImageUrl: String?,
        val uniqueTrackCount: Int, // The core metric: Breadth/Depth
        val topTrackName: String, // Example of a deep cut
        val loyaltyScore: Int, // 0-100 score based on depth
        val confidence: String = "MEDIUM"
    ) : SpotlightCardData()

    // 9. Discovery ("The Sonic Horizon") -> Replaces "Engagement"
    @Immutable
    data class Discovery(
        override val id: String = "discovery",
        val discoveryType: String, // "Explorer", "Time Traveler", "Orbiter"
        val newContentPercentage: Int, // 0-100%
        val varietyScore: Int, // Entropy scaled 0-100
        val topNewArtist: String?,
        val description: String
    ) : SpotlightCardData()
}
