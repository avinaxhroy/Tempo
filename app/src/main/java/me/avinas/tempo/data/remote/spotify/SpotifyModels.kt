package me.avinas.tempo.data.remote.spotify

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Spotify Web API response models.
 * 
 * Documentation: https://developer.spotify.com/documentation/web-api/reference
 */

// =====================
// Search Response
// =====================

@JsonClass(generateAdapter = true)
data class SpotifySearchResponse(
    val tracks: SpotifyTracksPaging?
)

/**
 * Response for artist search (GET /search?type=artist)
 */
@JsonClass(generateAdapter = true)
data class SpotifyArtistSearchResponse(
    val artists: SpotifyArtistsPaging?
)

@JsonClass(generateAdapter = true)
data class SpotifyTracksPaging(
    val items: List<SpotifyTrack>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val href: String,
    val next: String?,
    val previous: String?
)

/**
 * Paging object for artist search results.
 * Note: Artist search returns SpotifyFullArtist (with images), not SpotifyArtist.
 */
@JsonClass(generateAdapter = true)
data class SpotifyArtistsPaging(
    val items: List<SpotifyFullArtist>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val href: String,
    val next: String?,
    val previous: String?
)

/**
 * Response for batch tracks request (GET /tracks?ids=...)
 */
@JsonClass(generateAdapter = true)
data class SpotifyMultipleTracksResponse(
    val tracks: List<SpotifyTrack?>
)

// =====================
// Track
// =====================

@JsonClass(generateAdapter = true)
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum,
    @Json(name = "duration_ms") val durationMs: Long,
    val popularity: Int,
    @Json(name = "explicit") val isExplicit: Boolean,
    @Json(name = "external_ids") val externalIds: SpotifyExternalIds?,
    @Json(name = "external_urls") val externalUrls: SpotifyExternalUrls,
    val href: String,
    val uri: String,
    @Json(name = "preview_url") val previewUrl: String?,
    @Json(name = "track_number") val trackNumber: Int,
    @Json(name = "disc_number") val discNumber: Int,
    @Json(name = "is_local") val isLocal: Boolean = false
) {
    /**
     * Get primary artist name.
     */
    val primaryArtistName: String
        get() = artists.firstOrNull()?.name ?: "Unknown Artist"

    /**
     * Get all artist names joined.
     */
    val allArtistNames: String
        get() = artists.joinToString(", ") { it.name }
}

@JsonClass(generateAdapter = true)
data class SpotifyArtist(
    val id: String,
    val name: String,
    @Json(name = "external_urls") val externalUrls: SpotifyExternalUrls,
    val href: String,
    val uri: String,
    val type: String = "artist"
)

/**
 * Full artist details from the Spotify artist endpoint.
 * Contains images and additional metadata not available in track search results.
 */
@JsonClass(generateAdapter = true)
data class SpotifyFullArtist(
    val id: String,
    val name: String,
    val genres: List<String>,
    val images: List<SpotifyImage>,
    val popularity: Int,
    val followers: SpotifyFollowers?,
    @Json(name = "external_urls") val externalUrls: SpotifyExternalUrls,
    val href: String,
    val uri: String,
    val type: String = "artist"
) {
    /**
     * Get the best quality artist image URL.
     */
    val largeImageUrl: String?
        get() = images.maxByOrNull { it.height ?: 0 }?.url

    /**
     * Get medium quality artist image URL (around 300x300).
     */
    val mediumImageUrl: String?
        get() = images.find { (it.height ?: 0) in 200..400 }?.url
            ?: images.getOrNull(1)?.url

    /**
     * Get small/thumbnail artist image URL.
     */
    val smallImageUrl: String?
        get() = images.minByOrNull { it.height ?: Int.MAX_VALUE }?.url
}

@JsonClass(generateAdapter = true)
data class SpotifyFollowers(
    val total: Int,
    val href: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyAlbum(
    val id: String,
    val name: String,
    @Json(name = "album_type") val albumType: String, // album, single, compilation
    val artists: List<SpotifyArtist>,
    val images: List<SpotifyImage>,
    @Json(name = "release_date") val releaseDate: String,
    @Json(name = "release_date_precision") val releaseDatePrecision: String, // year, month, day
    @Json(name = "total_tracks") val totalTracks: Int,
    @Json(name = "external_urls") val externalUrls: SpotifyExternalUrls,
    val href: String,
    val uri: String
) {
    /**
     * Get the best quality album art URL.
     */
    val largeImageUrl: String?
        get() = images.maxByOrNull { it.height ?: 0 }?.url

    /**
     * Get medium quality album art URL (around 300x300).
     */
    val mediumImageUrl: String?
        get() = images.find { (it.height ?: 0) in 200..400 }?.url
            ?: images.getOrNull(1)?.url

    /**
     * Get small/thumbnail album art URL.
     */
    val smallImageUrl: String?
        get() = images.minByOrNull { it.height ?: Int.MAX_VALUE }?.url
}

@JsonClass(generateAdapter = true)
data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
)

@JsonClass(generateAdapter = true)
data class SpotifyExternalIds(
    val isrc: String?,
    val ean: String?,
    val upc: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyExternalUrls(
    val spotify: String?
)

// =====================
// Audio Features
// =====================

/**
 * Audio features for a track.
 * 
 * All features are analyzed by Spotify's audio analysis algorithm.
 */
@JsonClass(generateAdapter = true)
data class SpotifyAudioFeatures(
    val id: String,
    
    /**
     * Danceability describes how suitable a track is for dancing based on 
     * a combination of musical elements including tempo, rhythm stability, 
     * beat strength, and overall regularity. 
     * Range: 0.0 (least danceable) to 1.0 (most danceable)
     */
    val danceability: Float,
    
    /**
     * Energy is a measure from 0.0 to 1.0 and represents a perceptual measure 
     * of intensity and activity. Typically, energetic tracks feel fast, loud, 
     * and noisy. For example, death metal has high energy, while a Bach prelude 
     * scores low on the scale.
     */
    val energy: Float,
    
    /**
     * The key the track is in. Integers map to pitches using standard Pitch Class notation.
     * 0 = C, 1 = C♯/D♭, 2 = D, etc. -1 if no key detected.
     */
    val key: Int,
    
    /**
     * The overall loudness of a track in decibels (dB). 
     * Values typical range between -60 and 0 db.
     */
    val loudness: Float,
    
    /**
     * Mode indicates the modality (major or minor) of a track.
     * 1 = major, 0 = minor
     */
    val mode: Int,
    
    /**
     * Speechiness detects the presence of spoken words in a track.
     * Values above 0.66 describe tracks that are probably made entirely of spoken words.
     * Values between 0.33 and 0.66 describe tracks that may contain both music and speech.
     * Values below 0.33 most likely represent music and other non-speech-like tracks.
     */
    val speechiness: Float,
    
    /**
     * A confidence measure from 0.0 to 1.0 of whether the track is acoustic.
     * 1.0 represents high confidence the track is acoustic.
     */
    val acousticness: Float,
    
    /**
     * Predicts whether a track contains no vocals. "Ooh" and "aah" sounds are treated 
     * as instrumental in this context. Rap or spoken word tracks are clearly "vocal".
     * The closer the value is to 1.0, the greater likelihood the track contains no vocal content.
     */
    val instrumentalness: Float,
    
    /**
     * Detects the presence of an audience in the recording. Higher liveness values 
     * represent an increased probability that the track was performed live.
     * A value above 0.8 provides strong likelihood that the track is live.
     */
    val liveness: Float,
    
    /**
     * A measure from 0.0 to 1.0 describing the musical positiveness conveyed by a track.
     * Tracks with high valence sound more positive (happy, cheerful, euphoric), 
     * while tracks with low valence sound more negative (sad, depressed, angry).
     */
    val valence: Float,
    
    /**
     * The overall estimated tempo of a track in beats per minute (BPM).
     * In musical terminology, tempo is the speed or pace of a given piece 
     * and derives directly from the average beat duration.
     */
    val tempo: Float,
    
    /**
     * An estimated time signature. The time signature (meter) is a notational 
     * convention to specify how many beats are in each bar (or measure).
     * Range: 3 to 7 indicating 3/4 to 7/4 time signatures.
     */
    @Json(name = "time_signature") val timeSignature: Int,
    
    /**
     * The duration of the track in milliseconds.
     */
    @Json(name = "duration_ms") val durationMs: Long,
    
    /**
     * The Spotify URI for the track.
     */
    val uri: String,
    
    /**
     * A link to the Web API endpoint providing full details of the track.
     */
    @Json(name = "track_href") val trackHref: String,
    
    /**
     * The object type. Always "audio_features".
     */
    val type: String = "audio_features",
    
    /**
     * A link to the Web API endpoint providing analysis data.
     */
    @Json(name = "analysis_url") val analysisUrl: String
) {
    /**
     * Get a human-readable mood description based on valence.
     */
    val moodDescription: String
        get() = when {
            valence >= 0.8 -> "Very Happy"
            valence >= 0.6 -> "Happy"
            valence >= 0.4 -> "Neutral"
            valence >= 0.2 -> "Melancholic"
            else -> "Sad"
        }

    /**
     * Get a human-readable energy description.
     */
    val energyDescription: String
        get() = when {
            energy >= 0.8 -> "Very Energetic"
            energy >= 0.6 -> "Energetic"
            energy >= 0.4 -> "Moderate"
            energy >= 0.2 -> "Calm"
            else -> "Very Calm"
        }

    /**
     * Get a human-readable danceability description.
     */
    val danceabilityDescription: String
        get() = when {
            danceability >= 0.8 -> "Highly Danceable"
            danceability >= 0.6 -> "Danceable"
            danceability >= 0.4 -> "Somewhat Danceable"
            else -> "Not Very Danceable"
        }

    /**
     * Check if track is likely instrumental (>50% confidence).
     */
    val isLikelyInstrumental: Boolean
        get() = instrumentalness > 0.5

    /**
     * Check if track is likely acoustic (>50% confidence).
     */
    val isLikelyAcoustic: Boolean
        get() = acousticness > 0.5

    /**
     * Check if track is likely live (>80% confidence).
     */
    val isLikelyLive: Boolean
        get() = liveness > 0.8

    /**
     * Get key name (e.g., "C", "D♭", "F♯").
     */
    val keyName: String
        get() = when (key) {
            0 -> "C"
            1 -> "C♯/D♭"
            2 -> "D"
            3 -> "D♯/E♭"
            4 -> "E"
            5 -> "F"
            6 -> "F♯/G♭"
            7 -> "G"
            8 -> "G♯/A♭"
            9 -> "A"
            10 -> "A♯/B♭"
            11 -> "B"
            else -> "Unknown"
        }

    /**
     * Get mode name (Major or Minor).
     */
    val modeName: String
        get() = if (mode == 1) "Major" else "Minor"
}

/**
 * Response for batch audio features request.
 */
@JsonClass(generateAdapter = true)
data class SpotifyAudioFeaturesResponse(
    @Json(name = "audio_features") val audioFeatures: List<SpotifyAudioFeatures?>
)

// =====================
// User Profile
// =====================

@JsonClass(generateAdapter = true)
data class SpotifyUser(
    val id: String,
    @Json(name = "display_name") val displayName: String?,
    val email: String?,
    val country: String?,
    val product: String?, // free, premium, etc.
    val images: List<SpotifyImage>?,
    @Json(name = "external_urls") val externalUrls: SpotifyExternalUrls,
    val href: String,
    val uri: String
) {
    /**
     * Get user's profile image URL.
     */
    val profileImageUrl: String?
        get() = images?.firstOrNull()?.url

    /**
     * Check if user has premium subscription.
     */
    val isPremium: Boolean
        get() = product == "premium"
}

// =====================
// Token Response
// =====================

/**
 * OAuth token response from Spotify.
 */
@JsonClass(generateAdapter = true)
data class SpotifyTokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    val scope: String?,
    @Json(name = "expires_in") val expiresIn: Int, // seconds
    @Json(name = "refresh_token") val refreshToken: String?
)

// =====================
// Error Response
// =====================

@JsonClass(generateAdapter = true)
data class SpotifyErrorResponse(
    val error: SpotifyError
)

@JsonClass(generateAdapter = true)
data class SpotifyError(
    val status: Int,
    val message: String
)

// =====================
// Artist Top Tracks Response
// =====================

/**
 * Response from the artist top tracks endpoint.
 * Returns up to 10 top tracks for the artist.
 */
@JsonClass(generateAdapter = true)
data class SpotifyArtistTopTracksResponse(
    val tracks: List<SpotifyTrack>
)

/**
 * Response from the multiple artists endpoint.
 * Returns details for up to 50 artists in one request.
 */
@JsonClass(generateAdapter = true)
data class SpotifyMultipleArtistsResponse(
    val artists: List<SpotifyFullArtist?>
)
