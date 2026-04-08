package me.avinas.tempo.ui.spotlight

import androidx.compose.runtime.Immutable
import me.avinas.tempo.data.stats.TimeRange

/**
 * Sealed interface representing different pages in the Spotlight story flow.
 * All implementations are marked @Immutable to optimize Compose recomposition.
 */
@Immutable
sealed interface SpotlightStoryPage {
    val id: String
    val conversationalText: String
    val previewUrl: String?

    @Immutable
    data class ListeningMinutes(
        override val id: String = "listening_minutes",
        override val conversationalText: String,
        val totalMinutes: Int,
        val userName: String = "User", // Placeholder, ideally fetch from user profile
        val year: Int = 2025,
        val timeRange: TimeRange = TimeRange.THIS_YEAR,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class TopArtist(
        override val id: String = "top_artist",
        override val conversationalText: String,
        val topArtistName: String,
        val topArtistImageUrl: String?,
        val topArtistPercentage: Int, // e.g., top 1%
        val topArtists: List<ArtistEntry>,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        @Immutable
        data class ArtistEntry(
            val rank: Int,
            val name: String,
            val hoursListened: Int,
            val imageUrl: String? = null
        )
    }
    
    @Immutable
    data class TopTrackSetup(
        override val id: String = "top_track_setup",
        override val conversationalText: String,
        val topSongTitle: String,
        val topSongArtist: String,
        val topSongImageUrl: String?,
        val timeRange: TimeRange,
        override val previewUrl: String?
    ) : SpotlightStoryPage

    @Immutable
    data class TopSongs(
        override val id: String = "top_songs",
        override val conversationalText: String,
        val topSongTitle: String,
        val topSongArtist: String,
        val topSongImageUrl: String?,
        val playCount: Int,
        val totalTimeMs: Long = 0L,
        val topSongs: List<SongEntry>,
        override val previewUrl: String?
    ) : SpotlightStoryPage {
        @Immutable
        data class SongEntry(
            val rank: Int,
            val title: String,
            val artist: String,
            val playCount: Int,
            val imageUrl: String? = null
        )
    }

    @Immutable
    data class TopGenres(
        override val id: String = "top_genres",
        override val conversationalText: String,
        val topGenre: String,
        val topGenrePercentage: Int,
        val genres: List<GenreEntry>,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        @Immutable
        data class GenreEntry(
            val rank: Int,
            val name: String,
            val percentage: Int
        )
    }

    @Immutable
    data class Personality(
        override val id: String = "personality",
        override val conversationalText: String,
        val personalityType: String,
        val description: String,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class ListeningStreak(
        override val id: String = "listening_streak",
        override val conversationalText: String,
        val currentStreakDays: Int,
        val longestStreakDays: Int,
        val totalActiveDays: Int,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class ListeningClock(
        override val id: String = "listening_clock",
        override val conversationalText: String,
        val hourlyLevels: List<Int>,    // 24 values, 0-100 intensity per hour
        val peakHour: Int,               // 0-23
        val peakHourLabel: String,       // e.g. "11 PM"
        val listenerType: String,        // e.g. "Night Owl" / "Early Bird"
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class TopAlbum(
        override val id: String = "top_album",
        override val conversationalText: String,
        val albumName: String,
        val artistName: String,
        val albumArtUrl: String?,
        val playCount: Int,
        val totalTimeMs: Long,
        val uniqueTracksPlayed: Int,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class DiscoveryCount(
        override val id: String = "discovery_count",
        override val conversationalText: String,
        val uniqueArtists: Int,
        val uniqueTracks: Int,
        val newArtistsThisPeriod: Int,
        val timeRangeLabel: String,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class AudioMood(
        override val id: String = "audio_mood",
        override val conversationalText: String,
        val energyPercent: Int,          // 0-100
        val valencePercent: Int,         // 0-100
        val danceabilityPercent: Int,    // 0-100
        val acousticnessPercent: Int,    // 0-100
        val dominantMood: String,        // e.g. "Energetic", "Melancholic", "Chill"
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class WeekdayVsWeekend(
        override val id: String = "weekday_vs_weekend",
        override val conversationalText: String,
        val weekdayAvgMinutes: Int,     // avg minutes per weekday
        val weekendAvgMinutes: Int,     // avg minutes per weekend day
        val weekdayLabel: String,       // e.g. "Weekday Warrior"
        val weekendLabel: String,       // e.g. "Weekend Binger"
        val dominantSide: String,       // "weekday" or "weekend"
        // Day breakdown: list of 7 (Mon=0..Sun=6), each 0-100 intensity
        val dailyIntensity: List<Int>,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class BingeSession(
        override val id: String = "binge_session",
        override val conversationalText: String,
        val artistName: String,
        val bingeCount: Int,            // tracks in a row
        val totalBingeMinutes: Int,     // total time in that session
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class TimeOfDayVibes(
        override val id: String = "time_of_day_vibes",
        override val conversationalText: String,
        val morningPercent: Int,        // 5-11
        val afternoonPercent: Int,      // 12-17
        val eveningPercent: Int,        // 18-21
        val nightPercent: Int,          // 22-4
        val dominantPeriod: String,     // "morning" | "afternoon" | "evening" | "night"
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class BadgesEarned(
        override val id: String = "badges_earned",
        override val conversationalText: String,
        val badges: List<BadgeEntry>,       // Recently earned / recently starred badges
        val totalEarned: Int,               // Total earned badge count
        val totalPossible: Int,             // Total badge count
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        @Immutable
        data class BadgeEntry(
            val name: String,
            val description: String,
            val iconName: String,           // Material icon name for rendering
            val category: String,
            val stars: Int,                 // 0-5
            val isNewThisPeriod: Boolean    // False = star upgrade, True = newly earned
        )
    }

    @Immutable
    data class LevelUp(
        override val id: String = "level_up",
        override val conversationalText: String,
        val currentLevel: Int,
        val currentTitle: String,
        val totalXp: Long,
        val xpForNextLevel: Long,
        val levelProgress: Float,           // 0.0-1.0
        val xpEarnedThisPeriod: Long,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class TitleEarned(
        override val id: String = "title_earned",
        override val conversationalText: String,
        val newTitle: String,
        val previousTitle: String,
        val currentLevel: Int,
        val uniqueArtists: Int,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    @Immutable
    data class Conclusion(
        override val id: String = "conclusion",
        override val conversationalText: String,
        val totalMinutes: Int,
        val personalityType: String,
        val topArtists: List<ArtistEntry>,
        val topSongs: List<SongEntry>,
        val topGenres: List<String>,
        val timeRange: TimeRange = TimeRange.THIS_YEAR,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        @Immutable
        data class ArtistEntry(val name: String, val imageUrl: String?)
        @Immutable
        data class SongEntry(val title: String, val imageUrl: String?)
    }
}
