package me.avinas.tempo.data.remote.reccobeats

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * ReccoBeats API Models
 * 
 * ReccoBeats provides FREE audio features API similar to Spotify's deprecated endpoint.
 * Documentation: https://reccobeats.com/docs/documentation/introduction
 * 
 * Key benefits:
 * - No authentication required
 * - Supports both ReccoBeats UUIDs and Spotify IDs
 * - Audio features compatible with Spotify's format
 * - Can analyze uploaded audio files (30s max)
 */

// =====================
// Track Search
// =====================

/**
 * Response from /v1/track/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsSearchResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList(),
    val total: Int = 0
)

/**
 * Track object from search results
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsTrack(
    val id: String, // ReccoBeats UUID
    val name: String,
    val artists: List<ReccoBeatsArtist> = emptyList(),
    val album: ReccoBeatsAlbum? = null,
    @field:Json(name = "duration_ms") val durationMs: Long? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null,
    val isrc: String? = null,
    @field:Json(name = "preview_url") val previewUrl: String? = null,
    val popularity: Int? = null
)

/**
 * Artist reference in track
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsArtist(
    val id: String,
    val name: String,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
)

/**
 * Album reference in track
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAlbum(
    val id: String,
    val name: String,
    @field:Json(name = "release_date") val releaseDate: String? = null,
    val images: List<ReccoBeatsImage>? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
) {
    val smallImageUrl: String?
        get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
    
    val mediumImageUrl: String?
        get() = images?.sortedBy { it.width ?: 0 }?.getOrNull(images.size / 2)?.url
    
    val largeImageUrl: String?
        get() = images?.maxByOrNull { it.width ?: 0 }?.url
}

/**
 * Image object
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

// =====================
// Audio Features
// =====================

/**
 * Audio features response from /v1/track/:id/audio-features
 * 
 * All values match Spotify's audio features format for compatibility.
 * Range for most features: 0.0 to 1.0
 * 
 * @property acousticness Confidence track is acoustic (0.0-1.0)
 * @property danceability How suitable for dancing (0.0-1.0)
 * @property energy Intensity and activity level (0.0-1.0)
 * @property instrumentalness Predicts no vocals (>0.5 = instrumental)
 * @property liveness Presence of live audience (0.0-1.0)
 * @property speechiness Presence of spoken words (0.0-1.0)
 * @property valence Musical positiveness (0=sad, 1=happy)
 * @property tempo BPM (beats per minute)
 * @property loudness Overall volume in dB (typically -60 to 0)
 * @property key Musical key (0=C, 1=C#, 2=D, etc., -1 if unknown)
 * @property mode 0=minor, 1=major
 * @property timeSignature Time signature (e.g., 4 = 4/4)
 * @property durationMs Track duration in milliseconds
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAudioFeatures(
    val acousticness: Float = 0f,
    val danceability: Float = 0f,
    val energy: Float = 0f,
    val instrumentalness: Float = 0f,
    val liveness: Float = 0f,
    val speechiness: Float = 0f,
    val valence: Float = 0f,
    val tempo: Float = 0f,
    val loudness: Float = 0f,
    val key: Int = -1,
    val mode: Int = 0,
    @field:Json(name = "time_signature") val timeSignature: Int = 4,
    @field:Json(name = "duration_ms") val durationMs: Long? = null
) {
    /**
     * Derive mood from audio features using valence and energy.
     * 
     * Mood matrix:
     * - High valence + High energy = Happy/Energetic
     * - High valence + Low energy = Peaceful/Content
     * - Low valence + High energy = Angry/Intense
     * - Low valence + Low energy = Sad/Melancholic
     */
    fun deriveMood(): String {
        return when {
            valence >= 0.7 && energy >= 0.7 -> "Energetic"
            valence >= 0.7 && energy >= 0.5 -> "Happy"
            valence >= 0.7 && energy < 0.5 -> "Peaceful"
            valence >= 0.5 && energy >= 0.7 -> "Upbeat"
            valence >= 0.5 && energy >= 0.5 -> "Neutral"
            valence >= 0.5 && energy < 0.5 -> "Calm"
            valence >= 0.3 && energy >= 0.7 -> "Intense"
            valence >= 0.3 && energy >= 0.5 -> "Moody"
            valence >= 0.3 && energy < 0.5 -> "Contemplative"
            energy >= 0.7 -> "Aggressive"
            energy >= 0.5 -> "Dark"
            else -> "Melancholic"
        }
    }
    
    /**
     * Derive energy level description
     */
    fun deriveEnergyLevel(): String {
        return when {
            energy >= 0.8 -> "Very High"
            energy >= 0.6 -> "High"
            energy >= 0.4 -> "Medium"
            energy >= 0.2 -> "Low"
            else -> "Very Low"
        }
    }
    
    /**
     * Derive genre hints from audio features.
     * This is a rough approximation - actual genre should come from metadata APIs.
     */
    fun deriveGenreHints(): List<String> {
        val hints = mutableListOf<String>()
        
        // High speechiness suggests rap/hip-hop
        if (speechiness > 0.33) {
            hints.add("Hip-Hop")
            if (speechiness > 0.66) hints.add("Spoken Word")
        }
        
        // High acousticness suggests acoustic/folk
        if (acousticness > 0.7) {
            hints.add("Acoustic")
            if (energy < 0.4) hints.add("Folk")
        }
        
        // High instrumentalness suggests instrumental/electronic
        if (instrumentalness > 0.5) {
            hints.add("Instrumental")
            if (energy > 0.7) hints.add("Electronic")
        }
        
        // High danceability + energy suggests dance music
        if (danceability > 0.7 && energy > 0.7) {
            hints.add("Dance")
            if (tempo > 120) hints.add("EDM")
        }
        
        // High energy + low acousticness suggests rock/metal
        if (energy > 0.8 && acousticness < 0.3 && speechiness < 0.3) {
            hints.add("Rock")
            if (loudness > -5) hints.add("Metal")
        }
        
        // Low energy + high acousticness suggests ballad/ambient
        if (energy < 0.3 && acousticness > 0.5) {
            hints.add("Ballad")
            if (instrumentalness > 0.5) hints.add("Ambient")
        }
        
        return hints.distinct()
    }
    
    /**
     * Get musical key name
     */
    fun getKeyName(): String? {
        if (key < 0 || key > 11) return null
        val keyNames = listOf("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")
        val modeName = if (mode == 1) "Major" else "Minor"
        return "${keyNames[key]} $modeName"
    }
}

// =====================
// Artist Search
// =====================

/**
 * Response from /v1/artist/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsArtistSearchResponse(
    val artists: List<ReccoBeatsFullArtist> = emptyList(),
    val total: Int = 0
)

/**
 * Full artist object from artist search
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsFullArtist(
    val id: String,
    val name: String,
    @field:Json(name = "spotify_id") val spotifyId: String? = null,
    val genres: List<String>? = null,
    val images: List<ReccoBeatsImage>? = null,
    val popularity: Int? = null
) {
    val smallImageUrl: String?
        get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
    
    val largeImageUrl: String?
        get() = images?.maxByOrNull { it.width ?: 0 }?.url
}

// =====================
// Album Search
// =====================

/**
 * Response from /v1/album/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAlbumSearchResponse(
    val albums: List<ReccoBeatsFullAlbum> = emptyList(),
    val total: Int = 0
)

/**
 * Full album object from album search
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsFullAlbum(
    val id: String,
    val name: String,
    val artists: List<ReccoBeatsArtist>? = null,
    @field:Json(name = "release_date") val releaseDate: String? = null,
    @field:Json(name = "total_tracks") val totalTracks: Int? = null,
    val images: List<ReccoBeatsImage>? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
)

// =====================
// Multiple Tracks
// =====================

/**
 * Response from /v1/track?ids=... endpoint (multiple tracks)
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsMultipleTracksResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList()
)

// =====================
// Recommendations
// =====================

/**
 * Response from /v1/track/recommendation endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsRecommendationResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList()
)

// =====================
// Audio Analysis (File Upload)
// =====================

/**
 * Response from /v1/analysis/audio-features endpoint (file upload)
 * Returns audio features extracted from uploaded audio file.
 */
typealias ReccoBeatsAnalysisResponse = ReccoBeatsAudioFeatures
