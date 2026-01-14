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
