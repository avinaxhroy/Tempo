package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Compressed archive storage for long-tail scrobbles.
 * 
 * For users with large Last.fm histories (200K+ scrobbles), most tracks
 * are played only 1-5 times and never affect leaderboard rankings.
 * 
 * Instead of creating full ListeningEvent records for these rare plays
 * (which would slow down all stats queries), we store them in this
 * compressed archive format:
 * 
 * - One row per unique track
 * - Timestamps stored as delta-encoded, compressed blob
 * - Play count denormalized for quick lookups
 * - Full history accessible when explicitly requested
 * 
 * This preserves complete history while keeping the hot path fast.
 */
@Entity(
    tableName = "scrobbles_archive",
    indices = [
        Index(value = ["track_hash"], unique = true),
        Index(value = ["artist_name_normalized"]),
        Index(value = ["first_scrobble"]),
        Index(value = ["last_scrobble"]),
        Index(value = ["play_count"])
    ]
)
data class ScrobbleArchive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /**
     * SHA-256 hash of normalized "artist|title" for fast lookups.
     * This allows efficient duplicate detection without full string comparison.
     */
    @ColumnInfo(name = "track_hash")
    val trackHash: String,
    
    /**
     * Original track title (for display and search).
     */
    @ColumnInfo(name = "track_title")
    val trackTitle: String,
    
    /**
     * Original artist name (for display and search).
     */
    @ColumnInfo(name = "artist_name")
    val artistName: String,
    
    /**
     * Normalized artist name (lowercase, trimmed) for search.
     */
    @ColumnInfo(name = "artist_name_normalized")
    val artistNameNormalized: String,
    
    /**
     * Album name if available.
     */
    @ColumnInfo(name = "album_name")
    val albumName: String? = null,
    
    /**
     * MusicBrainz Recording ID if available.
     */
    @ColumnInfo(name = "musicbrainz_id")
    val musicbrainzId: String? = null,
    
    /**
     * Compressed blob containing all scrobble timestamps.
     * 
     * Format: Delta-encoded, GZIP compressed array of Unix timestamps (seconds).
     * - First 8 bytes: base timestamp (Long)
     * - Remaining: delta values (variable-length encoded)
     * 
     * This achieves ~60% compression compared to storing raw timestamps.
     */
    @ColumnInfo(name = "timestamps_blob", typeAffinity = ColumnInfo.BLOB)
    val timestampsBlob: ByteArray,
    
    /**
     * Total play count (denormalized for quick queries).
     */
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    
    /**
     * Timestamp of first scrobble (milliseconds, for date range queries).
     */
    @ColumnInfo(name = "first_scrobble")
    val firstScrobble: Long,
    
    /**
     * Timestamp of last scrobble (milliseconds, for date range queries).
     */
    @ColumnInfo(name = "last_scrobble")
    val lastScrobble: Long,
    
    /**
     * Album art URL from Last.fm (captured during import).
     * 
     * NOTE: Archived tracks are NOT enriched by EnrichmentWorker.
     * This is the only album art they will ever have.
     * This is intentional to save API quota for rarely-viewed tracks.
     */
    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,
    
    /**
     * Whether this track was ever loved on Last.fm.
     */
    @ColumnInfo(name = "was_loved")
    val wasLoved: Boolean = false,
    
    /**
     * Import session ID that created this archive entry.
     */
    @ColumnInfo(name = "import_id")
    val importId: Long,
    
    /**
     * When this archive entry was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * When this archive entry was last updated (for incremental syncs).
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if arrays are equal (for data class equals).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ScrobbleArchive

        if (id != other.id) return false
        if (trackHash != other.trackHash) return false
        if (trackTitle != other.trackTitle) return false
        if (artistName != other.artistName) return false
        if (!timestampsBlob.contentEquals(other.timestampsBlob)) return false
        if (playCount != other.playCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + trackHash.hashCode()
        result = 31 * result + trackTitle.hashCode()
        result = 31 * result + artistName.hashCode()
        result = 31 * result + timestampsBlob.contentHashCode()
        result = 31 * result + playCount
        return result
    }
    
    companion object {
        /**
         * Generate a track hash from artist and title.
         */
        fun generateTrackHash(artistName: String, trackTitle: String): String {
            val normalized = "${artistName.lowercase().trim()}|${trackTitle.lowercase().trim()}"
            return normalized.hashCode().toString(16) + "_" + 
                   java.security.MessageDigest.getInstance("SHA-256")
                       .digest(normalized.toByteArray())
                       .take(8)
                       .joinToString("") { "%02x".format(it) }
        }
    }
}
