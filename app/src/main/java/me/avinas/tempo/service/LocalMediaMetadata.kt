package me.avinas.tempo.service

import android.graphics.Bitmap
import android.media.MediaMetadata

/**
 * Data class holding all metadata extracted from local MediaSession.
 * 
 * This serves as the ultimate fallback when:
 * 1. MusicBrainz doesn't have the track
 * 2. User hasn't connected Spotify
 * 
 * Music players like YouTube Music, Spotify, Apple Music, local players
 * all provide this metadata through MediaSession API.
 */
data class LocalMediaMetadata(
    // Required fields
    val title: String,
    val artist: String,
    
    // Album info
    val album: String? = null,
    val albumArtist: String? = null,
    
    // Track info
    val durationMs: Long? = null,
    val trackNumber: Long? = null,
    val discNumber: Long? = null,
    val numTracks: Long? = null,
    
    // Date/Year
    val year: Long? = null,
    val releaseDate: String? = null,
    
    // Credits
    val composer: String? = null,
    val writer: String? = null,
    val author: String? = null,
    
    // Genre
    val genre: String? = null,
    
    // Album art (bitmap or URI)
    val albumArtBitmap: Bitmap? = null,
    val albumArtUri: String? = null,
    val artUri: String? = null,
    
    // Display metadata (may differ from actual metadata)
    val displayTitle: String? = null,
    val displaySubtitle: String? = null,
    val displayDescription: String? = null,
    
    // IDs
    val mediaId: String? = null,
    val mediaUri: String? = null,
    
    // Compilation flag
    val isCompilation: Boolean = false,
    
    // Source package
    val sourcePackage: String? = null
) {
    /**
     * Check if this metadata has meaningful content beyond just title/artist.
     */
    fun hasRichMetadata(): Boolean {
        return album != null || 
               durationMs != null ||
               year != null ||
               genre != null ||
               albumArtBitmap != null ||
               albumArtUri != null
    }
    
    /**
     * Check if this appears to be an advertisement rather than music.
     * 
     * Common patterns:
     * - Very short duration (< 10 seconds or > 90 seconds for typical ads)
     * - Title contains "ad", "advertisement", "sponsored"
     * - Artist is the app/brand name
     * - No album art (ads often don't have cover art)
     * - Title matches common ad patterns
     */
    fun isLikelyAdvertisement(): Boolean {
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()
        
        // Common ad title patterns
        val adTitlePatterns = listOf(
            "advertisement", "sponsored", "ad break",
            "premium", "upgrade", "subscribe",
            "commercial", "promo", "promotion"
        )
        
        // Check title for ad keywords
        if (adTitlePatterns.any { lowerTitle.contains(it) }) {
            return true
        }
        
        // Spotify-specific ad detection
        if (sourcePackage == "com.spotify.music") {
            // Spotify ads typically have "Spotify" as artist or specific patterns
            if (lowerArtist == "spotify" || lowerArtist.contains("spotify")) {
                return true
            }
            // Spotify ads often have no album or "Spotify" as album
            if (album?.lowercase()?.contains("spotify") == true) {
                return true
            }
        }
        
        // YouTube Music ad detection
        if (sourcePackage == "com.google.android.apps.youtube.music") {
            // YTM ads typically have no album art and very short duration
            if (albumArtBitmap == null && albumArtUri == null && 
                durationMs != null && durationMs < 30_000) {
                return true
            }
        }
        
        // Generic ad detection: very short content with no album info
        // Be careful not to filter out short intros/interludes
        if (durationMs != null && durationMs < 10_000 && album == null) {
            // Likely an ad if it's under 10 seconds with no album
            return true
        }
        
        // Artist name matches common ad sources
        val adArtists = listOf(
            "advertisement", "ad", "spotify", "youtube", 
            "google", "amazon", "apple", "commercial"
        )
        if (adArtists.any { lowerArtist == it || lowerArtist.startsWith("$it ") }) {
            return true
        }
        
        return false
    }
    
    /**
     * Get the best available album art source.
     */
    fun getBestAlbumArtSource(): String? {
        return albumArtUri ?: artUri
    }
    
    /**
     * Get release year as Int if available.
     */
    fun getReleaseYear(): Int? {
        return year?.toInt() ?: releaseDate?.take(4)?.toIntOrNull()
    }
    
    companion object {
        // Patterns for extracting artist from title
        private val TITLE_ARTIST_PATTERNS = listOf(
            Regex("""^(.+?)\s*[-–—]\s*(.+)$"""),           // "Title - Artist"
            Regex("""^(.+?)\s*\|\s*(.+)$"""),              // "Title | Artist"
            Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE),  // "Title by Artist"
        )
        
        private val PLACEHOLDER_ARTISTS = setOf(
            "unknown", "unknown artist", "<unknown>", "various artists",
            "various", "n/a", "na", "none", "null", "", " ",
            "artist", "track", "music", "audio", "media"
        )
        
        private fun isPlaceholderArtist(artist: String): Boolean {
            val lower = artist.lowercase().trim()
            return lower in PLACEHOLDER_ARTISTS || 
                   lower.startsWith("track ") || 
                   lower.matches(Regex("^\\d+$"))
        }
        
        private fun extractArtistFromTitle(title: String): String? {
            for (pattern in TITLE_ARTIST_PATTERNS) {
                val match = pattern.find(title)
                if (match != null) {
                    val potentialArtist = match.groupValues[2].trim()
                    if (potentialArtist.length in 1..50 && 
                        !potentialArtist.all { it.isDigit() } &&
                        !isPlaceholderArtist(potentialArtist)) {
                        return potentialArtist
                    }
                }
            }
            return null
        }
        
        /**
         * Extract all available metadata from Android MediaMetadata.
         * Uses comprehensive fallback chain for artist extraction.
         */
        fun fromMediaMetadata(
            metadata: MediaMetadata,
            sourcePackage: String? = null
        ): LocalMediaMetadata? {
            val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: return null
            
            // Robust artist extraction with multiple fallbacks
            val artist = sequenceOf(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_WRITER),
                metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)
            )
                .filterNotNull()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !isPlaceholderArtist(it) }
                ?: extractArtistFromTitle(rawTitle)
                ?: "Unknown Artist"
            
            // Clean title if artist was extracted from it
            val title = if (extractArtistFromTitle(rawTitle) == artist) {
                TITLE_ARTIST_PATTERNS.fold(rawTitle) { t, pattern ->
                    pattern.find(t)?.groupValues?.get(1)?.trim() ?: t
                }
            } else {
                rawTitle.trim()
            }
            
            return LocalMediaMetadata(
                title = title,
                artist = artist.trim(),
                
                // Album info
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() },
                
                // Track info
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
                trackNumber = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).takeIf { it > 0 },
                discNumber = metadata.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER).takeIf { it > 0 },
                numTracks = metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS).takeIf { it > 0 },
                
                // Date/Year
                year = metadata.getLong(MediaMetadata.METADATA_KEY_YEAR).takeIf { it > 0 },
                releaseDate = metadata.getString(MediaMetadata.METADATA_KEY_DATE)?.takeIf { it.isNotBlank() },
                
                // Credits
                composer = metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)?.takeIf { it.isNotBlank() },
                writer = metadata.getString(MediaMetadata.METADATA_KEY_WRITER)?.takeIf { it.isNotBlank() },
                author = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.takeIf { it.isNotBlank() },
                
                // Genre
                genre = metadata.getString(MediaMetadata.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() },
                
                // Album art
                albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                albumArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.takeIf { it.isNotBlank() },
                artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.takeIf { it.isNotBlank() },
                
                // Display metadata
                displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() },
                displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() },
                displayDescription = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)?.takeIf { it.isNotBlank() },
                
                // IDs
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() },
                mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotBlank() },
                
                // Compilation
                isCompilation = metadata.getLong(MediaMetadata.METADATA_KEY_COMPILATION) == 1L,
                
                // Source
                sourcePackage = sourcePackage
            )
        }
    }
}
