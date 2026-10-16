package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    
    // Basic CRUD Operations
    
    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getById(id: Long): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun findBySpotifyId(spotifyId: String): Track?

    @Query("SELECT * FROM tracks WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): Track?
    
    @Query("SELECT * FROM tracks WHERE musicbrainz_id = :musicbrainzId LIMIT 1")
    suspend fun findByMusicBrainzId(musicbrainzId: String): Track?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: Track): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<Track>): List<Long>

    @Update
    suspend fun update(track: Track)

    /**
     * Full-row Track updates are used for artist, album, duration and identifier
     * maintenance. Artwork is deliberately NOT part of that contract: preserving
     * the currently stored URL prevents a stale Track snapshot from resurrecting an
     * old cover after the user changed or reset it.
     */
    @Query("""
        SELECT album_art_url FROM enriched_metadata
        WHERE track_id = :trackId
        AND album_art_source = 'USER_SELECTED'
        AND album_art_url IS NOT NULL
        AND album_art_url != ''
        LIMIT 1
    """)
    suspend fun getManualAlbumArtUrl(trackId: Long): String?

    @Query("SELECT album_art_source FROM enriched_metadata WHERE track_id = :trackId LIMIT 1")
    suspend fun getAlbumArtSource(trackId: Long): AlbumArtSource?

    @Query("SELECT album_art_url FROM enriched_metadata WHERE track_id = :trackId LIMIT 1")
    suspend fun getMetadataAlbumArtUrl(trackId: Long): String?

    @Query("SELECT album_art_url FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getCurrentTrackAlbumArtUrl(trackId: Long): String?

    @Transaction
    suspend fun updatePreservingManualArtwork(track: Track) {
        val source = getAlbumArtSource(track.id)
        val manualArt = if (source == AlbumArtSource.USER_SELECTED) {
            getManualAlbumArtUrl(track.id)
        } else {
            null
        }
        val currentTrackArt = getCurrentTrackAlbumArtUrl(track.id)
        val preservedArtwork = resolveProtectedTrackArtwork(
            source = source,
            manualArtUrl = manualArt,
            currentTrackArtUrl = currentTrackArt,
            incomingArtUrl = currentTrackArt,
        )
        update(track.copy(albumArtUrl = preservedArtwork))
    }

    /**
     * The only automatic path allowed to change Track.album_art_url.
     *
     * Re-check the authoritative metadata decision in the same transaction. Once
     * enriched_metadata contains an accepted automatic cover, callers with stale
     * provider results must mirror that canonical URL instead of diverging the
     * Track row. USER_SELECTED and USER_RESET retain their stronger protections.
     */
    @Transaction
    suspend fun updateAutomaticAlbumArtUrl(
        trackId: Long,
        albumArtUrl: String?,
    ): String? {
        val source = getAlbumArtSource(trackId)
        val manualArt = if (source == AlbumArtSource.USER_SELECTED) {
            getManualAlbumArtUrl(trackId)
        } else {
            null
        }
        val canonicalAutomaticArt =
            if (source != null &&
                source != AlbumArtSource.NONE &&
                source != AlbumArtSource.USER_RESET &&
                source != AlbumArtSource.USER_SELECTED
            ) {
                getMetadataAlbumArtUrl(trackId)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        val currentTrackArt = getCurrentTrackAlbumArtUrl(trackId)
        val resolvedArtwork = resolveAutomaticTrackArtwork(
            source = source,
            manualArtUrl = manualArt,
            currentTrackArtUrl = currentTrackArt,
            canonicalAutomaticArtUrl = canonicalAutomaticArt,
            incomingArtUrl = albumArtUrl,
        )

        updateAlbumArtUrl(
            trackId = trackId,
            albumArtUrl = resolvedArtwork,
        )
        return resolvedArtwork
    }

    /**
     * Update only the title of a track.
     * Targeted update avoids overwriting other columns (e.g. enriched art URLs).
     */
    @Query("UPDATE tracks SET title = :title WHERE id = :trackId")
    suspend fun updateTitle(trackId: Long, title: String)

    /** Update only content classification without overwriting concurrently refreshed metadata. */
    @Query("UPDATE tracks SET content_type = :contentType WHERE id = :trackId")
    suspend fun updateContentType(trackId: Long, contentType: String): Int

    /** Update only the artwork URL without overwriting concurrently refreshed track metadata. */
    @Query("UPDATE tracks SET album_art_url = :albumArtUrl WHERE id = :trackId")
    suspend fun updateAlbumArtUrl(trackId: Long, albumArtUrl: String?)

    @Query("""
        UPDATE tracks
        SET album_art_url = :replacementUrl
        WHERE id = :trackId
        AND album_art_url = :expectedLocalUrl
        AND (album_art_url LIKE 'file://%' OR album_art_url LIKE 'content://%')
    """)
    suspend fun replaceLocalAlbumArtUrlIfMatches(
        trackId: Long,
        expectedLocalUrl: String,
        replacementUrl: String,
    ): Int

    /**
     * Promote a consumed local fallback back to the authoritative remote artwork.
     *
     * The replacement is allowed only while automatic metadata still owns a real
     * remote/API cover and Track still points at the exact local fallback that the
     * UI just consumed.
     */
    @Transaction
    suspend fun promoteLocalAlbumArtToCanonicalIfMatches(
        trackId: Long,
        expectedLocalUrl: String,
    ): String? {
        val source = getAlbumArtSource(trackId)
        if (source?.isApiSource() != true) return null

        val canonicalRemote =
            getMetadataAlbumArtUrl(trackId)
                ?.takeIf { isRemoteArtwork(it) }
                ?: return null

        val updated =
            replaceLocalAlbumArtUrlIfMatches(
                trackId = trackId,
                expectedLocalUrl = expectedLocalUrl,
                replacementUrl = canonicalRemote,
            )

        return canonicalRemote.takeIf { updated > 0 }
    }

    @Query("UPDATE tracks SET youtube_id = :youtubeId WHERE id = :trackId AND (youtube_id IS NULL OR youtube_id = '')")
    suspend fun updateYoutubeIdIfMissing(trackId: Long, youtubeId: String): Int

    // ponytail: album membership is denormalized via the `album` string column, matched
    // against (album title, artist name) in StatsDao. Null = not on any album.
    @Query("UPDATE tracks SET album = :albumTitle WHERE id = :trackId")
    suspend fun setTrackAlbum(trackId: Long, albumTitle: String?)

    // ponytail: match on primary_artist_id (feat. tracks keep the main artist as
    // primary) with exact-string fallback for legacy unlinked tracks.
    @Query("""
        SELECT * FROM tracks
        WHERE (primary_artist_id = :artistId OR LOWER(artist) = LOWER(:artistName))
        AND (album IS NULL OR album != :albumTitle)
        AND (:query = '' OR INSTR(LOWER(title), LOWER(:query)) > 0)
        ORDER BY title ASC
        LIMIT 100
    """)
    suspend fun getCandidateTracksForAlbum(
        artistName: String,
        albumTitle: String,
        query: String,
        artistId: Long
    ): List<Track>

    /**
     * Get all tracks on a specific album by artist (matching primary_artist_id or artist string).
     */
    @Query("""
        SELECT * FROM tracks
        WHERE album = :albumTitle
        AND (
            primary_artist_id = :artistId
            OR LOWER(artist) = LOWER(:artistName)
        )
        ORDER BY title ASC
    """)
    suspend fun getTracksForAlbumByArtist(
        albumTitle: String,
        artistId: Long,
        artistName: String
    ): List<Track>

    /**
     * Reassign all tracks with sourceAlbumTitle to targetAlbumTitle.
     */
    @Query("""
        UPDATE tracks
        SET album = :targetAlbumTitle
        WHERE album = :sourceAlbumTitle
        AND (
            primary_artist_id = :artistId
            OR LOWER(artist) = LOWER(:artistName)
        )
    """)
    suspend fun reassignAlbumTracks(
        sourceAlbumTitle: String,
        targetAlbumTitle: String,
        artistId: Long,
        artistName: String
    ): Int
    
    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun all(): Flow<List<Track>>
    
    @Query("SELECT * FROM tracks ORDER BY title ASC")
    suspend fun getAllSync(): List<Track>
    
    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getCount(): Int

    @Query("SELECT album_art_url FROM tracks WHERE album_art_url LIKE 'file://%'")
    suspend fun getLocalImageUrls(): List<String>

    @Query("""
        SELECT
            (SELECT COUNT(*) FROM tracks WHERE album_art_url = :albumArtUrl) +
            (SELECT COUNT(*) FROM albums WHERE artwork_url = :albumArtUrl) +
            (SELECT COUNT(*) FROM artists WHERE image_url = :albumArtUrl)
    """)
    suspend fun countAlbumArtUrlReferences(albumArtUrl: String): Int
    
    // Find by Title and Artist
    
    /**
     * Find track by exact title and artist match (case-insensitive).
     * COLLATE NOCASE allows SQLite to utilize existing title/artist indexes.
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE title = :title COLLATE NOCASE
        AND artist = :artist COLLATE NOCASE
        LIMIT 1
    """)
    suspend fun findByTitleAndArtist(title: String, artist: String): Track?
    
    /**
     * Find track by title with fuzzy artist match.
     * Uses INSTR instead of LIKE to avoid SQL wildcard matching on '%' or '_'.
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE title = :title COLLATE NOCASE
        AND (
            artist = :artist COLLATE NOCASE
            OR INSTR(LOWER(artist), LOWER(:artist)) > 0
            OR INSTR(LOWER(:artist), LOWER(artist)) > 0
        )
        LIMIT 1
    """)
    suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track?

    /**
     * Return all tracks whose title matches exactly (case-insensitive).
     * Used for any-artist intersection matching when the strict/fuzzy queries miss.
     */
    @Query("SELECT * FROM tracks WHERE title = :title COLLATE NOCASE")
    suspend fun findCandidatesByTitle(title: String): List<Track>

    /**
     * Return a bounded set of tracks where artist name partially matches.
     * Used for fuzzy matching without loading the entire table into memory.
     * INSTR keeps '%'/'_' in names literal (no wildcard surprises).
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE INSTR(LOWER(artist), LOWER(:artist)) > 0
        OR INSTR(LOWER(:artist), LOWER(artist)) > 0
        LIMIT 200
    """)
    suspend fun findCandidatesByArtist(artist: String): List<Track>

    /**
     * Return a bounded set of tracks for fuzzy matching.
     * Combines title and artist partial matches.
     * INSTR keeps '%'/'_' in names literal (no wildcard surprises).
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE INSTR(LOWER(title), LOWER(:title)) > 0
        OR INSTR(LOWER(artist), LOWER(:artist)) > 0
        LIMIT 200
    """)
    suspend fun findFuzzyCandidates(title: String, artist: String): List<Track>
    
    // Queries by Artist ID
    
    /**
     * Get tracks by primary artist ID.
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id = :artistId ORDER BY title ASC")
    suspend fun getTracksByPrimaryArtist(artistId: Long): List<Track>
    
    /**
     * Get tracks by primary artist ID as Flow.
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id = :artistId ORDER BY title ASC")
    fun observeTracksByPrimaryArtist(artistId: Long): Flow<List<Track>>
    
    /**
     * Get track count by primary artist.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE primary_artist_id = :artistId")
    suspend fun getTrackCountByPrimaryArtist(artistId: Long): Int
    
    // Queries for Linking
    
    /**
     * Get tracks without primary artist ID (need migration).
     */
    @Query("SELECT * FROM tracks WHERE primary_artist_id IS NULL LIMIT :limit")
    suspend fun getTracksWithoutPrimaryArtist(limit: Int = 100): List<Track>
    
    /**
     * Update primary artist ID for a track.
     */
    @Query("UPDATE tracks SET primary_artist_id = :artistId WHERE id = :trackId")
    suspend fun updatePrimaryArtistId(trackId: Long, artistId: Long?)
    
    /**
     * Get the primary artist for a track.
     */
    @Query("""
        SELECT a.* FROM artists a
        INNER JOIN tracks t ON t.primary_artist_id = a.id
        WHERE t.id = :trackId
    """)
    suspend fun getPrimaryArtistForTrack(trackId: Long): Artist?
    
    // Search Operations
    
    /**
     * Search tracks by title.
     * Limited to 50 results to prevent memory issues with large libraries.
     * INSTR keeps '%'/'_' in the query literal (no wildcard surprises).
     */
    @Query("""
        SELECT t.id, t.title, t.artist, t.album, t.duration,
               COALESCE(
                   NULLIF(t.album_art_url, ''),
                   NULLIF(em.album_art_url, ''),
                   (SELECT a.artwork_url FROM albums a WHERE a.title = t.album LIMIT 1)
               ) as album_art_url,
               t.spotify_id, t.youtube_id, t.musicbrainz_id, t.primary_artist_id, t.content_type
        FROM tracks t
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE INSTR(LOWER(t.title), LOWER(:query)) > 0
        ORDER BY t.title ASC
        LIMIT 50
    """)
    suspend fun searchByTitle(query: String): List<Track>
    
    /**
     * Search tracks by artist name.
     * Limited to 50 results to prevent memory issues with large libraries.
     * INSTR keeps '%'/'_' in the query literal (no wildcard surprises).
     */
    @Query("""
        SELECT t.id, t.title, t.artist, t.album, t.duration,
               COALESCE(
                   NULLIF(t.album_art_url, ''),
                   NULLIF(em.album_art_url, ''),
                   (SELECT a.artwork_url FROM albums a WHERE a.title = t.album LIMIT 1)
               ) as album_art_url,
               t.spotify_id, t.youtube_id, t.musicbrainz_id, t.primary_artist_id, t.content_type
        FROM tracks t
        LEFT JOIN enriched_metadata em ON t.id = em.track_id
        WHERE INSTR(LOWER(t.artist), LOWER(:query)) > 0
        ORDER BY t.title ASC
        LIMIT 50
    """)
    suspend fun searchByArtist(query: String): List<Track>
    
    // Content Type Operations
    
    /**
     * Update content type for all tracks from a specific artist.
     * Used when user marks an entire artist as podcast/audiobook.
     */
    @Query("UPDATE tracks SET content_type = :contentType WHERE LOWER(artist) = LOWER(:artistName)")
    suspend fun updateContentTypeByArtist(artistName: String, contentType: String): Int
    
    /**
     * Get all track IDs for a specific artist.
     * Used when deleting all content from an artist.
     */
    @Query("SELECT id FROM tracks WHERE LOWER(artist) = LOWER(:artistName)")
    suspend fun getTrackIdsByArtist(artistName: String): List<Long>
    
    /**
     * Delete all tracks from a specific artist.
     * Returns the number of deleted rows.
     */
    @Query("DELETE FROM tracks WHERE LOWER(artist) = LOWER(:artistName)")
    suspend fun deleteByArtist(artistName: String): Int
    
    // Artist Merge Operations
    
    /**
     * Replace old artist name with new artist name in the artist column.
     * Used during artist merge to update the raw artist string.
     * Handles exact matches only.
     */
    @Query("UPDATE tracks SET artist = :newArtistName WHERE LOWER(artist) = LOWER(:oldArtistName)")
    suspend fun replaceArtistName(oldArtistName: String, newArtistName: String): Int
    
    /**
     * Tracks whose artist string contains the given name anywhere
     * (case-insensitive). Uses INSTR instead of LIKE so names containing
     * '%' or '_' are matched literally, not as wildcards. Used during
     * artist merge to find multi-artist strings needing segment
     * replacement; the actual replacement happens in Kotlin.
     */
    @Query("SELECT * FROM tracks WHERE INSTR(LOWER(artist), LOWER(:name)) > 0")
    suspend fun getTracksContainingArtistName(name: String): List<Track>

    /**
     * Update the raw artist string of a single track.
     * Used during artist merge after Kotlin-side segment replacement.
     */
    @Query("UPDATE tracks SET artist = :artist WHERE id = :trackId")
    suspend fun updateArtistString(trackId: Long, artist: String): Int
}


internal fun resolveProtectedTrackArtwork(
    source: AlbumArtSource?,
    manualArtUrl: String?,
    currentTrackArtUrl: String?,
    incomingArtUrl: String?,
): String? =
    when (source) {
        AlbumArtSource.USER_SELECTED -> manualArtUrl ?: currentTrackArtUrl ?: incomingArtUrl
        AlbumArtSource.USER_RESET -> null
        else -> incomingArtUrl
    }

/**
 * Resolve a Track-table artwork write produced by automatic tracking/enrichment.
 *
 * When enriched_metadata already owns a canonical automatic remote cover,
 * Track.album_art_url may intentionally contain a file:// image as an offline
 * fallback. Keep (or refresh) that local backup instead of replacing it with the
 * remote URL. Remote/stale provider writes still collapse to the canonical
 * metadata URL, while USER_SELECTED and USER_RESET remain authoritative.
 */
internal fun resolveAutomaticTrackArtwork(
    source: AlbumArtSource?,
    manualArtUrl: String?,
    currentTrackArtUrl: String?,
    canonicalAutomaticArtUrl: String?,
    incomingArtUrl: String?,
): String? {
    val hasCanonicalRemoteArtwork =
        source?.isApiSource() == true &&
            isRemoteArtwork(canonicalAutomaticArtUrl)

    val automaticCandidate =
        if (hasCanonicalRemoteArtwork) {
            incomingArtUrl?.takeIf(::isLocalBackupArtwork)
                ?: currentTrackArtUrl?.takeIf(::isLocalBackupArtwork)
                ?: canonicalAutomaticArtUrl
        } else {
            incomingArtUrl
        }

    return resolveProtectedTrackArtwork(
        source = source,
        manualArtUrl = manualArtUrl,
        currentTrackArtUrl = currentTrackArtUrl,
        incomingArtUrl = automaticCandidate,
    )
}

internal fun isLocalBackupArtwork(url: String?): Boolean =
    url?.startsWith("file://") == true ||
        url?.startsWith("content://") == true

internal fun isManagedLocalArtworkFile(url: String?): Boolean =
    url?.startsWith("file://") == true

internal fun isRemoteArtwork(url: String?): Boolean =
    url?.startsWith("https://", ignoreCase = true) == true ||
        url?.startsWith("http://", ignoreCase = true) == true

internal fun shouldPreserveLocalTrackBackup(
    source: AlbumArtSource,
    canonicalArtworkUrl: String?,
    currentTrackArtworkUrl: String?,
): Boolean =
    source.isApiSource() &&
        isRemoteArtwork(canonicalArtworkUrl) &&
        isLocalBackupArtwork(currentTrackArtworkUrl)
