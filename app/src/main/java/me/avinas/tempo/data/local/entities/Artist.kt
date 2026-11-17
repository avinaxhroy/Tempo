package me.avinas.tempo.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.Normalizer

/**
 * Represents an artist in the database.
 * 
 * Artists are linked to tracks via:
 * - Track.primary_artist_id (for the main artist)
 * - TrackArtist junction table (for all artists including features)
 * 
 * Artists are also linked to albums via Album.artist_id
 *
 * `@Immutable`: the Compose compiler infers `List<String>` as unstable, so `genres` alone made
 * this entity unstable — and because it is a field of `ArtistDetails`, that instability propagated
 * to the whole artist detail screen. The promise is honest: `genres` is assigned from
 * `split`/`filter`/`emptyList` and every update goes through `copy` (verified: there is no
 * in-place mutation of it anywhere in the project). This annotation affects only Compose's
 * stability inference, not Room.
 */
@Immutable
@Entity(
    tableName = "artists", 
    indices = [
        Index(value = ["musicbrainz_id"], unique = true),
        Index(value = ["spotify_id"]),
        Index(value = ["name"]), // For name lookups
        Index(value = ["normalized_name"], unique = true) // For deduplication
    ]
)
data class Artist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /**
     * Display name of the artist as it appears in credits.
     */
    val name: String,
    
    /**
     * Normalized name for deduplication (lowercase, trimmed, special chars removed).
     * This helps prevent duplicate entries like "KR$NA" vs "Krsna" vs "KRSNA".
     */
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String = normalizeName(name),
    
    @ColumnInfo(name = "image_url") 
    val imageUrl: String?,

    /**
     * Where [imageUrl] came from.
     *
     * Reuses [AlbumArtSource] so artist and track artwork obey one arbitration
     * rule set: [AlbumArtSource.USER_SELECTED] locks the image against every
     * automatic writer, [AlbumArtSource.USER_RESET] records "the user chose to go
     * back to automatic" so a stale in-memory copy cannot restore the manual
     * portrait, and the numbered sources keep their normal priority order.
     *
     * Every automatic writer goes through [me.avinas.tempo.data.local.dao.ArtistDao.updateImageUrl],
     * which refuses to touch a locked row.
     */
    @ColumnInfo(name = "image_art_source", defaultValue = "NONE")
    val imageArtSource: AlbumArtSource = AlbumArtSource.NONE,
    
    val genres: List<String> = emptyList(),
    
    @ColumnInfo(name = "musicbrainz_id") 
    val musicbrainzId: String? = null,
    
    @ColumnInfo(name = "spotify_id") 
    val spotifyId: String? = null,
    
    /**
     * Country/region of the artist (e.g., "IN", "US", "GB")
     */
    val country: String? = null,
    
    /**
     * Type of artist: Person, Group, Orchestra, etc.
     */
    @ColumnInfo(name = "artist_type")
    val artistType: String? = null
) {
    companion object {
        // Pre-compiled regex patterns to avoid repeated native memory allocation
        // Unicode-aware: keeps letters (\p{L}) and numbers (\p{N}) from ANY script
        // (Latin, Japanese, Korean, Cyrillic, Arabic, ...) and only strips
        // punctuation/symbols. The old [^a-z0-9\s] pattern erased every non-ASCII
        // character, collapsing all CJK artist names into the same "" key.
        // \p{M} keeps combining marks of non-Latin scripts (Devanagari matras,
        // Thai vowels, Arabic harakat) so distinct names keep distinct keys.
        private val SPECIAL_CHARS_PATTERN = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
        private val WHITESPACE_PATTERN = Regex("\\s+")

        /**
         * Normalizes an artist name for comparison and deduplication:
         * 1. NFKC normalization (folds full-width Latin "ＡＢＣ" -> "ABC",
         *    half-width katakana "ｶ" -> "カ", and compatibility chars).
         * 2. Lowercase.
         * 3. Strip punctuation and symbols, retaining script letters and numbers.
         * 4. Collapse whitespace.
         *
         * If all characters are stripped (symbol/emoji-only names), falls back
         * to the NFKC-lowercased original to prevent empty dedup keys.
         */
        fun normalizeName(name: String): String {
            val nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC)
            val normalized = nfkc
                .lowercase()
                .trim()
                .replace(SPECIAL_CHARS_PATTERN, "") // Remove special chars (any-script aware)
                .replace(WHITESPACE_PATTERN, " ") // Normalize whitespace
                .trim()
            return normalized.ifBlank { nfkc.lowercase().trim() }
        }
    }
}
