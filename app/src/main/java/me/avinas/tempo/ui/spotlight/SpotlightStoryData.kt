package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.data.stats.TimeRange

sealed interface SpotlightStoryPage {
    val id: String
    val conversationalText: String
    val previewUrl: String?

    data class ListeningMinutes(
        override val id: String = "listening_minutes",
        override val conversationalText: String,
        val totalMinutes: Int,
        val userName: String = "User", // Placeholder, ideally fetch from user profile
        val year: Int = 2025,
        val timeRange: TimeRange = TimeRange.THIS_YEAR,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

    data class TopArtist(
        override val id: String = "top_artist",
        override val conversationalText: String,
        val topArtistName: String,
        val topArtistImageUrl: String?,
        val topArtistPercentage: Int, // e.g., top 1%
        val topArtists: List<ArtistEntry>,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        data class ArtistEntry(
            val rank: Int,
            val name: String,
            val hoursListened: Int,
            val imageUrl: String? = null
        )
    }
    
    data class TopTrackSetup(
        override val id: String = "top_track_setup",
        override val conversationalText: String,
        val topSongTitle: String,
        val topSongArtist: String,
        val topSongImageUrl: String?,
        val timeRange: TimeRange,
        override val previewUrl: String?
    ) : SpotlightStoryPage

    data class TopSongs(
        override val id: String = "top_songs",
        override val conversationalText: String,
        val topSongTitle: String,
        val topSongArtist: String,
        val topSongImageUrl: String?,
        val playCount: Int,
        val topSongs: List<SongEntry>,
        override val previewUrl: String?
    ) : SpotlightStoryPage {
        data class SongEntry(
            val rank: Int,
            val title: String,
            val artist: String,
            val playCount: Int,
            val imageUrl: String? = null
        )
    }

    data class TopGenres(
        override val id: String = "top_genres",
        override val conversationalText: String,
        val topGenre: String,
        val topGenrePercentage: Int,
        val genres: List<GenreEntry>,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage {
        data class GenreEntry(
            val rank: Int,
            val name: String,
            val percentage: Int
        )
    }

    data class Personality(
        override val id: String = "personality",
        override val conversationalText: String,
        val personalityType: String,
        val description: String,
        override val previewUrl: String? = null
    ) : SpotlightStoryPage

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
        data class ArtistEntry(val name: String, val imageUrl: String?)
        data class SongEntry(val title: String, val imageUrl: String?)
    }
}
