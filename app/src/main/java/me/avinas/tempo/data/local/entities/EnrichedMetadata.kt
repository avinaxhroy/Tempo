package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached enriched metadata from MusicBrainz and optionally Spotify.
 * 
 * This table stores API response data to avoid repeated API calls.
 * Metadata older than 6 months can be refreshed if needed.
 */
@Entity(
    tableName = "enriched_metadata",
    foreignKeys = [ForeignKey(
        entity = Track::class, 
        parentColumns = ["id"], 
        childColumns = ["track_id"], 
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["track_id"], unique = true),
        Index(value = ["musicbrainz_recording_id"]),
        Index(value = ["enrichment_status"])
    ]
)
data class EnrichedMetadata(
    @PrimaryKey(autoGenerate = true) 
    val id: Long = 0,
    
    @ColumnInfo(name = "track_id") 
    val trackId: Long,
    
    // MusicBrainz IDs
    @ColumnInfo(name = "musicbrainz_recording_id")
    val musicbrainzRecordingId: String? = null,
    
    @ColumnInfo(name = "musicbrainz_artist_id")
    val musicbrainzArtistId: String? = null,
    
    @ColumnInfo(name = "musicbrainz_release_id")
    val musicbrainzReleaseId: String? = null,
    
    @ColumnInfo(name = "musicbrainz_release_group_id")
    val musicbrainzReleaseGroupId: String? = null,
    
    // Album/Release info
    @ColumnInfo(name = "album_title")
    val albumTitle: String? = null,
    
    @ColumnInfo(name = "release_date")
    val releaseDate: String? = null,
    
    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,
    
    @ColumnInfo(name = "release_type")
    val releaseType: String? = null, // Album, Single, EP, etc.
    
    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,
    
    @ColumnInfo(name = "album_art_url_small")
    val albumArtUrlSmall: String? = null,
    
    @ColumnInfo(name = "album_art_url_large")
    val albumArtUrlLarge: String? = null,
    
    // Artist info
    @ColumnInfo(name = "artist_name")
    val artistName: String? = null,
    
    @ColumnInfo(name = "artist_country")
    val artistCountry: String? = null,
    
    @ColumnInfo(name = "artist_type")
    val artistType: String? = null, // Person, Group, etc.
    
    // Track info
    @ColumnInfo(name = "track_duration_ms")
    val trackDurationMs: Long? = null,
    
    @ColumnInfo(name = "isrc")
    val isrc: String? = null,
    
    // Tags & Genres (stored as delimited string)
    val tags: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    
    // Genre source tracking for priority-based replacement
    // Higher priority sources can replace lower priority ones
    // Priority: MUSICBRAINZ > LASTFM > ITUNES > RECCOBEATS > SPOTIFY_ARTIST
    @ColumnInfo(name = "genre_source")
    val genreSource: GenreSource = GenreSource.NONE,
    
    // Label info
    @ColumnInfo(name = "record_label")
    val recordLabel: String? = null,
    
    // Spotify data (optional, stored as JSON)
    // Audio features include: danceability, energy, valence, tempo, acousticness,
    // instrumentalness, loudness, speechiness, liveness, key, mode, time_signature
    @ColumnInfo(name = "audio_features_json") 
    val audioFeaturesJson: String? = null,
    
    @ColumnInfo(name = "spotify_id")
    val spotifyId: String? = null,
    
    @ColumnInfo(name = "spotify_artist_id")
    val spotifyArtistId: String? = null,
    
    // All artist IDs for collab tracks (comma-separated)
    @ColumnInfo(name = "spotify_artist_ids")
    val spotifyArtistIds: String? = null,
    
    // Verified artist name from Spotify
    @ColumnInfo(name = "spotify_verified_artist")
    val spotifyVerifiedArtist: String? = null,
    
    @ColumnInfo(name = "spotify_artist_image_url")
    val spotifyArtistImageUrl: String? = null,
    
    // Spotify 30-second preview URL for audio analysis fallback
    // Used by ReccoBeats audio analysis when database lookup fails
    @ColumnInfo(name = "spotify_preview_url")
    val spotifyPreviewUrl: String? = null,
    
    // ReccoBeats data (fallback for audio features when Spotify unavailable)
    // ReccoBeats provides FREE audio features API compatible with Spotify format
    @ColumnInfo(name = "reccobeats_id")
    val reccoBeatsId: String? = null,
    
    // Audio features source tracking
    // Helps identify where audio features came from (spotify, reccobeats, analysis)
    @ColumnInfo(name = "audio_features_source")
    val audioFeaturesSource: AudioFeaturesSource = AudioFeaturesSource.NONE,
    
    // Spotify enrichment status (separate from MusicBrainz enrichment)
    @ColumnInfo(name = "spotify_enrichment_status")
    val spotifyEnrichmentStatus: SpotifyEnrichmentStatus = SpotifyEnrichmentStatus.NOT_ATTEMPTED,
    
    @ColumnInfo(name = "spotify_enrichment_error")
    val spotifyEnrichmentError: String? = null,
    
    @ColumnInfo(name = "spotify_last_attempt")
    val spotifyLastAttempt: Long? = null,
    
    // Enrichment status
    @ColumnInfo(name = "enrichment_status")
    val enrichmentStatus: EnrichmentStatus = EnrichmentStatus.PENDING,
    
    @ColumnInfo(name = "enrichment_error")
    val enrichmentError: String? = null,
    
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    
    @ColumnInfo(name = "last_enrichment_attempt")
    val lastEnrichmentAttempt: Long? = null,
    
    // iTunes data
    @ColumnInfo(name = "itunes_artist_image_url")
    val iTunesArtistImageUrl: String? = null,
    
    @ColumnInfo(name = "apple_music_url")
    val appleMusicUrl: String? = null,
    
    @ColumnInfo(name = "release_date_full")
    val releaseDateFull: String? = null, // ISO 8601 format (YYYY-MM-DD)
    
    // Deezer artist image (fallback for artist images)
    @ColumnInfo(name = "deezer_artist_image_url")
    val deezerArtistImageUrl: String? = null,
    
    // Last.fm artist image (fallback for artist images)
    @ColumnInfo(name = "lastfm_artist_image_url")
    val lastFmArtistImageUrl: String? = null,
    
    // Spotify track URL for "Open in Spotify" feature
    @ColumnInfo(name = "spotify_track_url")
    val spotifyTrackUrl: String? = null,

    // Audio Preview URL (Direct .m4a/.mp3 stream from iTunes or Spotify)
    @ColumnInfo(name = "preview_url")
    val previewUrl: String? = null,
    
    // Cache management
    @ColumnInfo(name = "cache_timestamp") 
    val cacheTimestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "cache_version")
    val cacheVersion: Int = 1
) {
    companion object {
        // Cache is valid for 6 months
        const val CACHE_VALIDITY_MS = 180L * 24 * 60 * 60 * 1000
        
        // Max retries before giving up
        const val MAX_RETRY_COUNT = 5
    }
    
    fun isCacheValid(): Boolean {
        val age = System.currentTimeMillis() - cacheTimestamp
        return age < CACHE_VALIDITY_MS && enrichmentStatus == EnrichmentStatus.ENRICHED
    }
    
    fun shouldRetry(): Boolean {
        return enrichmentStatus == EnrichmentStatus.FAILED && retryCount < MAX_RETRY_COUNT
    }
    
    /**
     * Check if this track has Spotify audio features.
     */
    fun hasSpotifyFeatures(): Boolean {
        return spotifyId != null && audioFeaturesJson != null
    }
    
    /**
     * Check if this track has audio features from any source.
     */
    fun hasAudioFeatures(): Boolean {
        return audioFeaturesJson != null
    }
    
    /**
     * Check if Spotify enrichment should be attempted.
     */
    fun shouldAttemptSpotifyEnrichment(): Boolean {
        return spotifyEnrichmentStatus == SpotifyEnrichmentStatus.NOT_ATTEMPTED ||
               spotifyEnrichmentStatus == SpotifyEnrichmentStatus.PENDING
    }
    
    /**
     * Check if audio features enrichment should be attempted via fallback (ReccoBeats).
     * Should be tried when Spotify didn't provide audio features.
     */
    fun shouldAttemptAudioFeaturessFallback(): Boolean {
        return audioFeaturesJson == null && 
               (spotifyEnrichmentStatus == SpotifyEnrichmentStatus.UNAVAILABLE ||
                spotifyEnrichmentStatus == SpotifyEnrichmentStatus.NOT_ATTEMPTED)
    }
    
    /**
     * Get the best available artist image URL from all sources.
     * Priority: Spotify > iTunes > Last.fm > Deezer
     * 
     * @return The best artist image URL, or null if none available
     */
    fun getBestArtistImageUrl(): String? {
        return spotifyArtistImageUrl 
            ?: iTunesArtistImageUrl 
            ?: lastFmArtistImageUrl 
            ?: deezerArtistImageUrl
    }
    
    /**
     * Check if any artist image is available from any source.
     */
    fun hasArtistImage(): Boolean {
        return getBestArtistImageUrl() != null
    }

    /**
     * Identify missing metadata gaps.
     */
    fun identifyGap(): me.avinas.tempo.data.enrichment.EnrichmentGap {
        return me.avinas.tempo.data.enrichment.EnrichmentGap(
            missingAlbumArt = albumArtUrl.isNullOrBlank(),
            missingGenres = genres.isEmpty(),
            missingAudioFeatures = audioFeaturesJson == null,
            missingArtistImage = !hasArtistImage(),
            missingPreviewUrl = previewUrl == null
        )
    }
}

/**
 * Source of audio features data.
 * Helps track where the audio analysis came from and its reliability.
 */
enum class AudioFeaturesSource {
    NONE,                    // No audio features yet
    SPOTIFY,                 // From Spotify audio-features API (most accurate)
    SPOTIFY_HISTORY,         // From user's Spotify listening history (accurate)
    SPOTIFY_ARTIST_DERIVED,  // Derived from artist's top tracks (approximate)
    RECCOBEATS,              // From ReccoBeats API database lookup
    RECCOBEATS_ANALYSIS,     // From ReccoBeats audio file analysis (30s preview)
    LOCAL_ANALYSIS           // From local audio analysis (future)
}

/**
 * Status of Spotify audio features enrichment.
 * This is separate from MusicBrainz enrichment as it's optional.
 */
enum class SpotifyEnrichmentStatus {
    NOT_ATTEMPTED,  // User hasn't connected Spotify or enrichment not run
    PENDING,        // Queued for enrichment
    ENRICHED,       // Successfully fetched audio features
    NOT_FOUND,      // Track not found on Spotify
    UNAVAILABLE,    // Track found but audio features not available
    FAILED          // API error, may retry
}

enum class EnrichmentStatus {
    PENDING,    // Not yet attempted
    ENRICHED,   // Successfully enriched
    FAILED,     // Failed, may retry
    NOT_FOUND,  // Track not found in MusicBrainz
    SKIPPED     // User skipped or track ineligible
}

/**
 * Source of genre data with priority levels.
 * Higher priority sources provide more accurate track-specific genres.
 * 
 * Priority order (highest to lowest):
 * 1. MUSICBRAINZ (5) - Community curated, track-specific
 * 2. LASTFM (4) - User tags, track-specific
 * 3. ITUNES (3) - Apple's genre taxonomy
 * 4. RECCOBEATS (2) - Audio analysis based
 * 5. SPOTIFY_ARTIST (1) - Artist-level genres (not track-specific)
 * 6. NONE (0) - No genres yet
 */
enum class GenreSource(val priority: Int) {
    NONE(0),
    SPOTIFY_ARTIST(1),  // Artist-level genres from Spotify (lowest priority)
    RECCOBEATS(2),      // Audio analysis based genres
    ITUNES(3),          // iTunes/Apple Music genre
    LASTFM(4),          // Last.fm user tags
    MUSICBRAINZ(5);     // MusicBrainz community curated (highest priority)
    
    /**
     * Check if this source should be replaced by another.
     * Returns true if the other source has higher priority.
     */
    fun shouldBeReplacedBy(other: GenreSource): Boolean {
        return other.priority > this.priority
    }
    
    companion object {
        /**
         * Calculate genre specificity score.
         * More specific genres (like "desi hip hop") score higher than generic ones (like "Hip-Hop/Rap").
         * 
         * Scoring factors:
         * - Word count (more words = more specific)
         * - No slashes/generic separators (slashes often indicate generic categorization)
         * - Lowercase indicates community-derived (more authentic)
         */
        fun calculateSpecificity(genres: List<String>): Int {
            if (genres.isEmpty()) return 0
            
            return genres.sumOf { genre ->
                var score = 0
                
                // Word count - more words = more specific
                val words = genre.split(" ", "-").filter { it.isNotBlank() }
                score += words.size * 2
                
                // Penalty for generic separators (slashes indicate broad categories like "Hip-Hop/Rap")
                if (genre.contains("/")) score -= 2
                
                // Bonus for lowercase (community-derived, more authentic)
                if (genre == genre.lowercase()) score += 1
                
                // Bonus for geographic/regional qualifiers (very specific)
                val regionalTerms = listOf("desi", "hindi", "indian", "korean", "japanese", "latin", "spanish")
                if (regionalTerms.any { genre.lowercase().contains(it) }) score += 3
                
                score.coerceAtLeast(0)
            }
        }
        
        /**
         * Check if new genres should replace existing ones.
         * 
         * Simple, clear priority rules:
         * 1. MusicBrainz ALWAYS wins if it has genres (community-curated, track-specific)
         * 2. Last.fm wins over iTunes/ReccoBeats/Spotify (user tags, track-specific)
         * 3. Otherwise, keep existing genres (Spotify artist genres are already specific)
         * 
         * @return true if newGenres should replace existingGenres
         */
        fun shouldReplace(
            existingGenres: List<String>,
            existingSource: GenreSource,
            newGenres: List<String>,
            newSource: GenreSource
        ): Boolean {
            // If no existing genres, always replace
            if (existingGenres.isEmpty()) return true
            
            // If new genres are empty, don't replace
            if (newGenres.isEmpty()) return false
            
            // MusicBrainz ALWAYS wins - it's community-curated and track-specific
            if (newSource == MUSICBRAINZ) {
                android.util.Log.d("GenreSource", "MusicBrainz found, replacing ${existingSource.name} genres")
                return true
            }
            
            // Last.fm wins over lower priority sources (iTunes, ReccoBeats, Spotify)
            if (newSource == LASTFM && existingSource.priority < LASTFM.priority) {
                android.util.Log.d("GenreSource", "Last.fm found, replacing ${existingSource.name} genres")
                return true
            }
            
            // For all other cases, keep existing genres
            // Spotify artist genres like "desi hip hop" are specific enough
            // iTunes/ReccoBeats genres are often generic
            android.util.Log.d("GenreSource", "Keeping existing ${existingSource.name} genres over ${newSource.name}")
            return false
        }
    }
}

