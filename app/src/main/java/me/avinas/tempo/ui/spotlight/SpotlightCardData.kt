package me.avinas.tempo.ui.spotlight

import androidx.compose.ui.graphics.Color

sealed class SpotlightCardData {
    abstract val id: String
    
    data class TimeDevotion(
        override val id: String = "time_devotion",
        val genre: String,
        val percentage: Int,
        val timeSpentString: String
    ) : SpotlightCardData()

    data class EarlyAdopter(
        override val id: String = "early_adopter",
        val artistName: String,
        val artistImageUrl: String?,
        val discoveryDate: String
    ) : SpotlightCardData()

    data class SeasonalAnthem(
        override val id: String = "seasonal_anthem",
        val seasonName: String,
        val songTitle: String,
        val artistName: String,
        val albumArtUrl: String?
    ) : SpotlightCardData()

    data class ListeningPeak(
        override val id: String = "listening_peak",
        val peakDate: String,
        val peakTime: String,
        val topSongTitle: String,
        val topSongArtist: String
    ) : SpotlightCardData()

    data class RepeatOffender(
        override val id: String = "repeat_offender",
        val songTitle: String,
        val artistName: String,
        val playCount: Int,
        val frequencyString: String // e.g., "once every 4 hours"
    ) : SpotlightCardData()
    
    data class DiscoveryMilestone(
        override val id: String = "discovery_milestone",
        val newArtistCount: Int
    ) : SpotlightCardData()

    data class ListeningStreak(
        override val id: String = "listening_streak",
        val streakDays: Int
    ) : SpotlightCardData()

    // Audio Feature Cards
    data class MoodAnalysis(
        override val id: String = "mood_analysis",
        val moodDescription: String,
        val valencePercentage: Int,
        val dominantMood: String
    ) : SpotlightCardData()

    data class EnergyProfile(
        override val id: String = "energy_profile",
        val energyDescription: String,
        val energyPercentage: Int,
        val trend: String // "increasing", "decreasing", "stable"
    ) : SpotlightCardData()

    data class DanceFloorReady(
        override val id: String = "dance_floor_ready",
        val danceabilityPercentage: Int,
        val tracksAnalyzed: Int
    ) : SpotlightCardData()

    data class TempoProfile(
        override val id: String = "tempo_profile",
        val averageTempo: Int,
        val tempoDescription: String,
        val dominantRange: String // e.g., "100-120 BPM"
    ) : SpotlightCardData()

    data class AcousticVsElectronic(
        override val id: String = "acoustic_vs_electronic",
        val acousticPercentage: Int,
        val preference: String // "acoustic", "electronic", "balanced"
    ) : SpotlightCardData()

    data class MusicalPersonality(
        override val id: String = "musical_personality",
        val personalityType: String, // e.g., "Energetic Explorer", "Mellow Soul"
        val description: String,
        val energyLevel: Int,
        val moodLevel: Int,
        val danceLevel: Int
    ) : SpotlightCardData()
}
