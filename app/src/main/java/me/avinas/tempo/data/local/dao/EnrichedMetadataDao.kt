package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface EnrichedMetadataDao {
    
    @Query("SELECT * FROM enriched_metadata WHERE track_id = :trackId LIMIT 1")
    fun forTrack(trackId: Long): Flow<EnrichedMetadata?>
    
    @Query("SELECT * FROM enriched_metadata WHERE track_id = :trackId LIMIT 1")
    suspend fun forTrackSync(trackId: Long): EnrichedMetadata?

    @Query("SELECT * FROM enriched_metadata WHERE track_id IN (:trackIds)")
    suspend fun forTracksSync(trackIds: List<Long>): List<EnrichedMetadata>
    
    @Query("SELECT * FROM enriched_metadata WHERE musicbrainz_recording_id = :mbid LIMIT 1")
    suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: EnrichedMetadata): Long

    /**
     * Insert metadata only when no row for the track exists.
     *
     * The unique track_id index makes this a single atomic SQLite decision, so a
     * concurrent manual artwork selection can never be replaced by a stale
     * "create pending" check-then-upsert sequence.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(metadata: EnrichedMetadata): Long

    /**
     * Persist metadata produced by automatic enrichment without allowing a stale
     * in-flight request to overwrite or resurrect a user's explicit artwork choice.
     *
     * The current row is read inside the same Room transaction immediately before
     * the write. This closes both races:
     * - user selects manual art while an automatic lookup is already running;
     * - user resets to automatic while an older request still carries USER_SELECTED.
     */
    @Transaction
    suspend fun upsertFromAutomaticEnrichment(metadata: EnrichedMetadata): Long {
        val current = forTrackSync(metadata.trackId)
        val resolved = mergeAutomaticEnrichmentArtwork(current, metadata)
        val rowId = upsert(resolved)

        // Track.album_art_url is normally a denormalized UI mirror, but it may
        // intentionally contain a file:// backup while enriched_metadata keeps the
        // preferred remote URL. Preserve that backup across automatic refreshes.
        val currentTrackArtwork = getTrackAlbumArtUrlForArtwork(resolved.trackId)
        when {
            resolved.albumArtSource == AlbumArtSource.USER_RESET ->
                updateTrackAlbumArtUrlForArtwork(resolved.trackId, null)
            resolved.albumArtSource == AlbumArtSource.USER_SELECTED &&
                !resolved.albumArtUrl.isNullOrBlank() ->
                updateTrackAlbumArtUrlForArtwork(resolved.trackId, resolved.albumArtUrl)
            shouldPreserveLocalTrackBackup(
                source = resolved.albumArtSource,
                canonicalArtworkUrl = resolved.albumArtUrl,
                currentTrackArtworkUrl = currentTrackArtwork,
            ) -> Unit
            !resolved.albumArtUrl.isNullOrBlank() ->
                updateTrackAlbumArtUrlForArtwork(resolved.trackId, resolved.albumArtUrl)
        }

        return rowId
    }

    @Query("SELECT album_art_url FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackAlbumArtUrlForArtwork(trackId: Long): String?

    @Query("UPDATE tracks SET album_art_url = :albumArtUrl WHERE id = :trackId")
    suspend fun updateTrackAlbumArtUrlForArtwork(trackId: Long, albumArtUrl: String?)

    /**
     * Save an explicit artwork choice and mirror it to tracks atomically.
     *
     * The current metadata row is read inside the transaction so a concurrent
     * enrichment result cannot be lost when the user changes only the artwork.
     */
    @Transaction
    suspend fun setUserSelectedArtwork(
        trackId: Long,
        albumArtUrl: String,
        albumArtUrlSmall: String,
        albumArtUrlLarge: String,
        timestamp: Long,
    ): Long {
        val current = forTrackSync(trackId) ?: EnrichedMetadata(trackId = trackId)
        val updated = current.copy(
            albumArtUrl = albumArtUrl,
            albumArtUrlSmall = albumArtUrlSmall,
            albumArtUrlLarge = albumArtUrlLarge,
            albumArtSource = AlbumArtSource.USER_SELECTED,
            cacheTimestamp = timestamp,
        )
        val rowId = upsert(updated)
        updateTrackAlbumArtUrlForArtwork(trackId, albumArtUrl)
        return rowId
    }

    /**
     * Remove the explicit artwork choice and clear the Track mirror atomically.
     *
     * USER_RESET is intentionally kept until a real automatic artwork source wins.
     * It acts as a tombstone against stale Track snapshots that still carry the old
     * manual URL.
     */
    @Transaction
    suspend fun resetArtworkToAutomatic(
        trackId: Long,
        timestamp: Long,
    ): Long {
        val current = forTrackSync(trackId) ?: EnrichedMetadata(trackId = trackId)
        val reset = current.copy(
            albumArtUrl = null,
            albumArtUrlSmall = null,
            albumArtUrlLarge = null,
            albumArtSource = AlbumArtSource.USER_RESET,
            enrichmentStatus = EnrichmentStatus.PENDING,
            retryCount = 0,
            cacheTimestamp = timestamp,
        )
        val rowId = upsert(reset)
        updateTrackAlbumArtUrlForArtwork(trackId, null)
        return rowId
    }
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(metadata: List<EnrichedMetadata>): List<Long>

    /**
     * Batch counterpart of [upsertFromAutomaticEnrichment].
     * Process rows sequentially inside one transaction so duplicate track IDs in a
     * batch also see the preceding protected write.
     */
    @Transaction
    suspend fun upsertAllFromAutomaticEnrichment(
        metadata: List<EnrichedMetadata>,
    ): List<Long> {
        if (metadata.isEmpty()) return emptyList()

        // Last.fm currently flushes at 500 rows, safely below Android SQLite's
        // conservative bind-variable limit. Read existing rows once instead of
        // issuing one SELECT per imported metadata row.
        val currentByTrackId = forTracksSync(metadata.map { it.trackId }.distinct())
            .associateBy { it.trackId }
            .toMutableMap()
        val merged = ArrayList<EnrichedMetadata>(metadata.size)

        for (item in metadata) {
            val resolved = mergeAutomaticEnrichmentArtwork(
                current = currentByTrackId[item.trackId],
                incoming = item,
            )
            currentByTrackId[item.trackId] = resolved
            merged += resolved
        }

        val rowIds = upsertAll(merged)

        // Keep Track mirrors aligned for batch imports too, without discarding a
        // local file:// fallback that is deliberately stored outside metadata.
        for (resolved in merged) {
            val currentTrackArtwork = getTrackAlbumArtUrlForArtwork(resolved.trackId)
            when {
                resolved.albumArtSource == AlbumArtSource.USER_RESET ->
                    updateTrackAlbumArtUrlForArtwork(resolved.trackId, null)
                resolved.albumArtSource == AlbumArtSource.USER_SELECTED &&
                    !resolved.albumArtUrl.isNullOrBlank() ->
                    updateTrackAlbumArtUrlForArtwork(resolved.trackId, resolved.albumArtUrl)
                shouldPreserveLocalTrackBackup(
                    source = resolved.albumArtSource,
                    canonicalArtworkUrl = resolved.albumArtUrl,
                    currentTrackArtworkUrl = currentTrackArtwork,
                ) -> Unit
                !resolved.albumArtUrl.isNullOrBlank() ->
                    updateTrackAlbumArtUrlForArtwork(resolved.trackId, resolved.albumArtUrl)
            }
        }

        return rowIds
    }
    
    @Update
    suspend fun update(metadata: EnrichedMetadata)

    @Query("DELETE FROM enriched_metadata WHERE cache_timestamp < :expiry")
    suspend fun deleteOlderThan(expiry: Long)

    @Query("DELETE FROM enriched_metadata WHERE track_id = :trackId")
    suspend fun deleteByTrackId(trackId: Long)
    
    /**
     * Get tracks that need enrichment, prioritized by play count then recency.
     * Joins with listening_events to get play count and most recent play for prioritization.
     * Most-played and most-recently-played tracks are enriched first.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        LEFT JOIN (
            SELECT track_id, COUNT(*) as play_count, MAX(timestamp) as last_played
            FROM listening_events
            GROUP BY track_id
        ) le ON em.track_id = le.track_id
        WHERE em.enrichment_status = :status
        ORDER BY COALESCE(le.play_count, 0) DESC, COALESCE(le.last_played, 0) DESC, em.id ASC
        LIMIT :limit
    """)
    suspend fun getTracksNeedingEnrichment(
        status: EnrichmentStatus = EnrichmentStatus.PENDING,
        limit: Int = 10
    ): List<EnrichedMetadata>

    /**
     * Get top-played tracks needing enrichment, filtered by minimum play count.
     * Used for post-import enrichment of large backlogs (e.g. YouTube Music imports
     * with thousands of tracks) to prioritize frequently-played songs and defer
     * rarely-played ones to the regular periodic worker.
     *
     * Orders by play_count DESC, then most recent play DESC.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        INNER JOIN (
            SELECT track_id, COUNT(*) as play_count, MAX(timestamp) as last_played
            FROM listening_events
            GROUP BY track_id
            HAVING COUNT(*) >= :minPlayCount
        ) le ON em.track_id = le.track_id
        WHERE em.enrichment_status = :status
        ORDER BY le.play_count DESC, le.last_played DESC, em.id ASC
        LIMIT :limit
    """)
    suspend fun getTopPlayedTracksNeedingEnrichment(
        status: EnrichmentStatus = EnrichmentStatus.PENDING,
        minPlayCount: Int = 2,
        limit: Int = 50
    ): List<EnrichedMetadata>
    
    /**
     * Get tracks that failed enrichment and should be retried.
     */
    @Query("""
        SELECT * FROM enriched_metadata 
        WHERE enrichment_status = :status 
        AND retry_count < :maxRetries
        AND (last_enrichment_attempt IS NULL OR last_enrichment_attempt < :retryAfter)
        ORDER BY retry_count ASC, id ASC
        LIMIT :limit
    """)
    suspend fun getTracksToRetry(
        status: EnrichmentStatus = EnrichmentStatus.FAILED,
        maxRetries: Int = 5,
        retryAfter: Long = System.currentTimeMillis() - 3600000, // 1 hour ago
        limit: Int = 5
    ): List<EnrichedMetadata>
    
    /**
     * Get tracks with stale cache that should be refreshed.
     */
    @Query("""
        SELECT * FROM enriched_metadata 
        WHERE enrichment_status = :status 
        AND cache_timestamp < :staleThreshold
        ORDER BY cache_timestamp ASC
        LIMIT :limit
    """)
    suspend fun getStaleMetadata(
        status: EnrichmentStatus = EnrichmentStatus.ENRICHED,
        staleThreshold: Long,
        limit: Int = 5
    ): List<EnrichedMetadata>
    
    /**
     * Mark track for re-enrichment (manual refresh).
     */
    @Query("""
        UPDATE enriched_metadata 
        SET enrichment_status = :newStatus, retry_count = 0
        WHERE track_id = :trackId
    """)
    suspend fun markForReEnrichment(
        trackId: Long, 
        newStatus: EnrichmentStatus = EnrichmentStatus.PENDING
    )
    
    /**
     * Get count of tracks by enrichment status.
     */
    @Query("SELECT COUNT(*) FROM enriched_metadata WHERE enrichment_status = :status")
    suspend fun countByStatus(status: EnrichmentStatus): Int
    
    /**
     * Get all enrichment stats.
     */
    @Query("""
        SELECT enrichment_status, COUNT(*) as count 
        FROM enriched_metadata 
        GROUP BY enrichment_status
    """)
    suspend fun getEnrichmentStats(): List<EnrichmentStatusCount>
    
    // Spotify Enrichment Queries
    
    /**
     * Get tracks that need Spotify enrichment, prioritized by play count.
     * Only returns tracks that have been successfully enriched with MusicBrainz data
     * and haven't been attempted for Spotify enrichment yet.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        LEFT JOIN (
            SELECT track_id, COUNT(*) as play_count 
            FROM listening_events 
            GROUP BY track_id
        ) le ON em.track_id = le.track_id
        WHERE em.enrichment_status = 'ENRICHED'
        AND (em.spotify_enrichment_status = 'NOT_ATTEMPTED' OR em.spotify_enrichment_status = 'PENDING')
        AND em.spotify_id IS NULL
        ORDER BY COALESCE(le.play_count, 0) DESC, em.id ASC
        LIMIT :limit
    """)
    suspend fun getTracksNeedingSpotifyEnrichment(limit: Int = 10): List<EnrichedMetadata>
    
    /**
     * Get count of tracks with Spotify audio features.
     */
    @Query("SELECT COUNT(*) FROM enriched_metadata WHERE spotify_id IS NOT NULL AND audio_features_json IS NOT NULL")
    suspend fun countTracksWithSpotifyFeatures(): Int
    
    /**
     * Get count of tracks pending Spotify enrichment.
     */
    @Query("""
        SELECT COUNT(*) FROM enriched_metadata 
        WHERE enrichment_status = 'ENRICHED'
        AND (spotify_enrichment_status = 'NOT_ATTEMPTED' OR spotify_enrichment_status = 'PENDING')
    """)
    suspend fun countTracksPendingSpotifyEnrichment(): Int
    
    /**
     * Mark all enriched tracks as pending Spotify enrichment.
     * Called when user first connects Spotify to queue historical tracks.
     */
    @Query("""
        UPDATE enriched_metadata 
        SET spotify_enrichment_status = 'PENDING'
        WHERE enrichment_status = 'ENRICHED'
        AND spotify_enrichment_status = 'NOT_ATTEMPTED'
    """)
    suspend fun queueAllForSpotifyEnrichment()
    
    /**
     * Update Spotify enrichment status for a track.
     */
    @Query("""
        UPDATE enriched_metadata 
        SET spotify_enrichment_status = :status,
            spotify_enrichment_error = :error,
            spotify_last_attempt = :timestamp
        WHERE track_id = :trackId
    """)
    suspend fun updateSpotifyEnrichmentStatus(
        trackId: Long,
        status: SpotifyEnrichmentStatus,
        error: String? = null,
        timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Clear all Spotify data (for disconnect).
     */
    @Query("""
        UPDATE enriched_metadata 
        SET spotify_id = NULL,
            audio_features_json = NULL,
            spotify_enrichment_status = 'NOT_ATTEMPTED',
            spotify_enrichment_error = NULL,
            spotify_last_attempt = NULL
    """)
    suspend fun clearAllSpotifyData()
    
    /**
     * Get Spotify enrichment stats.
     */
    @Query("""
        SELECT spotify_enrichment_status, COUNT(*) as count 
        FROM enriched_metadata 
        WHERE enrichment_status = 'ENRICHED'
        GROUP BY spotify_enrichment_status
    """)
    suspend fun getSpotifyEnrichmentStats(): List<SpotifyEnrichmentStatusCount>

    /**
     * Find by Spotify ID (for deduplication).
     */
    @Query("SELECT * FROM enriched_metadata WHERE spotify_id = :spotifyId LIMIT 1")
    suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata?

    /**
     * Lightweight ISRC index used by bulk imports.
     * Loading track_id + isrc once avoids one full enriched_metadata scan per unique ISRC.
     */
    @Query(
        """
        SELECT track_id, isrc
        FROM enriched_metadata
        WHERE isrc IS NOT NULL AND TRIM(isrc) != ''
        """,
    )
    suspend fun getTrackIsrcRefs(): List<TrackIsrcRef>
    
    /**
     * Get tracks that are enriched but tracks table is missing album art.
     * Used to backfill album art URLs to tracks table.
     * Checks for both NULL and empty string.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE em.enrichment_status = 'ENRICHED'
        AND em.album_art_url IS NOT NULL AND em.album_art_url != ''
        AND (t.album_art_url IS NULL OR t.album_art_url = '')
        LIMIT :limit
    """)
    suspend fun getEnrichedTracksWithMissingAlbumArt(limit: Int = 50): List<EnrichedMetadata>
    
    /**
     * Clear all artist image URLs for tracks associated with a specific artist.
     * This is used to force re-enrichment of artist images.
     * Clears images from all sources (Spotify, iTunes, Deezer, Last.fm).
     */
    @Query("""
        UPDATE enriched_metadata 
        SET spotify_artist_image_url = NULL,
            itunes_artist_image_url = NULL,
            deezer_artist_image_url = NULL,
            lastfm_artist_image_url = NULL
        WHERE track_id IN (
            SELECT track_id FROM track_artists WHERE artist_id = :artistId
        )
    """)
    suspend fun clearArtistImagesForArtist(artistId: Long)
    
    /**
     * Update the Spotify artist image URL for all tracks with a given Spotify artist ID.
     * This is used to cache artist images once fetched from Spotify.
     */
    @Query("""
        UPDATE enriched_metadata 
        SET spotify_artist_image_url = :imageUrl
        WHERE spotify_artist_id = :spotifyArtistId
    """)
    suspend fun updateArtistImageUrl(spotifyArtistId: String, imageUrl: String)
    
    /**
     * Get tracks that have Spotify artist ID but no cached artist image.
     * Used to fetch missing artist images on demand.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        WHERE em.spotify_artist_id IS NOT NULL
        AND em.spotify_artist_image_url IS NULL
        GROUP BY em.spotify_artist_id
        LIMIT :limit
    """)
    suspend fun getTracksNeedingArtistImage(limit: Int = 10): List<EnrichedMetadata>
    
    /**
     * Get enriched tracks that are missing cover art or genres.
     * These tracks were successfully enriched but external APIs didn't provide complete data.
     * We should retry enrichment for these tracks periodically.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        LEFT JOIN (
            SELECT track_id, COUNT(*) as play_count 
            FROM listening_events 
            GROUP BY track_id
        ) le ON em.track_id = le.track_id
        WHERE em.enrichment_status = 'ENRICHED'
        AND (
            em.album_art_url IS NULL 
            OR (em.genres IS NULL OR em.genres = '[]' OR LENGTH(em.genres) < 5)
            OR em.preview_url IS NULL
        )
        AND (em.last_enrichment_attempt IS NULL OR em.last_enrichment_attempt < :retryAfter)
        ORDER BY COALESCE(le.play_count, 0) DESC, em.id ASC
        LIMIT :limit
    """)
    suspend fun getEnrichedTracksWithIncompleteData(
        retryAfter: Long = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L), // 7 days ago
        limit: Int = 5
    ): List<EnrichedMetadata>
    
    /**
     * Get genres from other tracks by the same artist.
     * Used as a fallback when external sources don't provide genres for a track.
     * Returns distinct genre strings from successfully enriched tracks.
     */
    @Query("""
        SELECT em.genres FROM enriched_metadata em
        INNER JOIN tracks t ON em.track_id = t.id
        WHERE t.artist LIKE '%' || :artistName || '%'
        AND em.genres IS NOT NULL 
        AND em.genres != '[]' 
        AND LENGTH(em.genres) > 5
        AND em.track_id != :excludeTrackId
        LIMIT :limit
    """)
    suspend fun getGenresFromArtistOtherTracks(
        artistName: String,
        excludeTrackId: Long,
        limit: Int = 5
    ): List<String>
    
    /**
     * Get all enriched metadata for export.
     */
    @Query("SELECT * FROM enriched_metadata")
    suspend fun getAllSync(): List<EnrichedMetadata>

    @Query("SELECT * FROM enriched_metadata WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EnrichedMetadata?

    /**
     * IDs of enriched_metadata rows whose tags or genres column still holds the
     * legacy JSON-array format (e.g. `["pop", "rock"]`) instead of the
     * `|||`-delimited format. Used by the one-time list column repair.
     */
    @Query("""
        SELECT id FROM enriched_metadata
        WHERE (tags LIKE '[%' AND tags LIKE '%]')
           OR (genres LIKE '[%' AND genres LIKE '%]')
    """)
    suspend fun getIdsWithLegacyListFormat(): List<Long>

    @Query("SELECT album_art_url FROM enriched_metadata WHERE album_art_url LIKE 'file://%' UNION SELECT spotify_artist_image_url FROM enriched_metadata WHERE spotify_artist_image_url LIKE 'file://%' UNION SELECT album_art_url_small FROM enriched_metadata WHERE album_art_url_small LIKE 'file://%' UNION SELECT album_art_url_large FROM enriched_metadata WHERE album_art_url_large LIKE 'file://%' UNION SELECT itunes_artist_image_url FROM enriched_metadata WHERE itunes_artist_image_url LIKE 'file://%' UNION SELECT deezer_artist_image_url FROM enriched_metadata WHERE deezer_artist_image_url LIKE 'file://%' UNION SELECT lastfm_artist_image_url FROM enriched_metadata WHERE lastfm_artist_image_url LIKE 'file://%'")
    suspend fun getLocalImageUrls(): List<String>

    @Query("""
        SELECT COUNT(*) FROM enriched_metadata
        WHERE album_art_url = :url
           OR album_art_url_small = :url
           OR album_art_url_large = :url
           OR spotify_artist_image_url = :url
           OR itunes_artist_image_url = :url
           OR deezer_artist_image_url = :url
           OR lastfm_artist_image_url = :url
    """)
    suspend fun countImageUrlReferences(url: String): Int
    
    /**
     * Get count of tracks pending enrichment from Last.fm imports.
     * Joins with listening_events to identify tracks that came from Last.fm import.
     */
    @Query("""
        SELECT COUNT(DISTINCT em.track_id) FROM enriched_metadata em
        INNER JOIN listening_events le ON em.track_id = le.track_id
        WHERE em.enrichment_status = :status
        AND le.source = 'fm.last.import'
    """)
    suspend fun countPendingFromLastFmImport(
        status: EnrichmentStatus = EnrichmentStatus.PENDING
    ): Int
    
    /**
     * Get count of successfully enriched tracks from Last.fm imports.
     */
    @Query("""
        SELECT COUNT(DISTINCT em.track_id) FROM enriched_metadata em
        INNER JOIN listening_events le ON em.track_id = le.track_id
        WHERE em.enrichment_status = :status
        AND le.source = 'fm.last.import'
    """)
    suspend fun countEnrichedFromLastFmImport(
        status: EnrichmentStatus = EnrichmentStatus.ENRICHED
    ): Int

    /**
     * Re-queue tracks for enrichment that are FAILED or ENRICHED but missing album art.
     * Used after YouTube Music import to retry enrichment for existing tracks.
     * Returns the number of rows updated.
     */
    @Query("""
        UPDATE enriched_metadata
        SET enrichment_status = 'PENDING', retry_count = 0
        WHERE track_id IN (:trackIds)
        AND (enrichment_status = 'FAILED'
             OR (enrichment_status = 'ENRICHED' AND (album_art_url IS NULL OR album_art_url = '')))
    """)
    suspend fun requeueTracksForEnrichment(trackIds: List<Long>): Int

    /**
     * Find track IDs from the given list that have no enriched_metadata entry.
     * Used to create PENDING entries for existing tracks that were never queued for enrichment.
     */
    @Query("""
        SELECT t.id FROM tracks t
        WHERE t.id IN (:trackIds)
        AND t.id NOT IN (SELECT track_id FROM enriched_metadata)
    """)
    suspend fun findTrackIdsWithoutEnrichedMetadata(trackIds: List<Long>): List<Long>

    /**
     * Re-queue every non-enriched track (FAILED, SKIPPED, NOT_FOUND) back to PENDING.
     * Used by the "Enrich All" action so the bulk worker can sweep the full backlog.
     *
     * NOT_FOUND tracks are rate-limited: only those whose last enrichment attempt
     * ([notFoundAttemptedBeforeMs] should be now - [EnrichedMetadata.NOT_FOUND_RETRY_BLOCK_MS])
     * is older than the cutoff (or NULL for legacy rows) are requeued. Tracks searched
     * everywhere recently stay NOT_FOUND so repeated taps on "Enrich All" cannot
     * re-query every external API for lookups that already failed. FAILED (transient
     * errors) and SKIPPED (deferred) tracks are always requeued.
     * Returns the number of rows updated.
     */
    @Query("""
        UPDATE enriched_metadata
        SET enrichment_status = 'PENDING', retry_count = 0
        WHERE enrichment_status IN ('FAILED', 'SKIPPED')
           OR (enrichment_status = 'NOT_FOUND'
               AND (last_enrichment_attempt IS NULL OR last_enrichment_attempt <= :notFoundAttemptedBeforeMs))
    """)
    suspend fun requeueAllForEnrichment(notFoundAttemptedBeforeMs: Long): Int

    /**
     * Count NOT_FOUND tracks still inside the 7-day re-enrichment block
     * (last_enrichment_attempt newer than [notFoundAttemptedBeforeMs]).
     * Surfaced on the Enrichment Report screen so the user understands why
     * those songs are not part of the current sweep.
     */
    @Query("""
        SELECT COUNT(*) FROM enriched_metadata
        WHERE enrichment_status = 'NOT_FOUND'
        AND last_enrichment_attempt > :notFoundAttemptedBeforeMs
    """)
    suspend fun countNotFoundBlockedFromRequeue(notFoundAttemptedBeforeMs: Long): Int

    /**
     * Defer low-play PENDING tracks (play count < minPlayCount, including never-played)
     * by marking them SKIPPED. Used after very large imports so post-import enrichment
     * stops churning through thousands of one-play wonders; those are instead enriched
     * on-demand (when opened) or via "Enrich All". Returns rows updated.
     * ponytail: play_count threshold is a practical proxy for "top per filter"; exact
     * per-filter cap would need a persisted priority set / new status.
     */
    @Query("""
        UPDATE enriched_metadata
        SET enrichment_status = 'SKIPPED'
        WHERE enrichment_status = 'PENDING'
        AND track_id NOT IN (
            SELECT track_id FROM listening_events
            GROUP BY track_id
            HAVING COUNT(*) >= :minPlayCount
        )
    """)
    suspend fun markLowPlayPendingAsSkipped(minPlayCount: Int = 2): Int

    /** Total number of tracks in the library (source of truth: tracks table). */
    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countAllTracks(): Int

    /** Tracks that currently have a non-empty album art URL. */
    @Query("SELECT COUNT(*) FROM tracks WHERE album_art_url IS NOT NULL AND album_art_url != ''")
    suspend fun countTracksWithAlbumArt(): Int

    /** Tracks that have no enriched_metadata row at all (never queued for enrichment). */
    @Query("SELECT COUNT(*) FROM tracks WHERE id NOT IN (SELECT track_id FROM enriched_metadata)")
    suspend fun countTracksWithoutEnrichedMetadata(): Int

    /**
     * Create PENDING rows for every track that has no enriched_metadata row yet.
     * Used by "Enrich All" so tracks that were never queued (e.g. inserted while an
     * import was running, or created before metadata rows existed) are included in
     * the sweep. Returns the rowid of the last inserted row (Room limitation:
     * INSERT queries cannot return the affected-row count).
     */
    @Query("""
        INSERT INTO enriched_metadata (
            track_id, tags, genres, genre_source, audio_features_source,
            spotify_enrichment_status, enrichment_status, retry_count,
            album_art_source, cache_timestamp, cache_version
        )
        SELECT id, '', '', 'NONE', 'NONE', 'NOT_ATTEMPTED', 'PENDING', 0, 'NONE', :now, 1
        FROM tracks
        WHERE id NOT IN (SELECT track_id FROM enriched_metadata)
    """)
    suspend fun insertPendingForUnqueuedTracks(now: Long): Long
}

data class TrackIsrcRef(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val isrc: String,
)

data class EnrichmentStatusCount(
    @ColumnInfo(name = "enrichment_status") val status: EnrichmentStatus,
    val count: Int
)

data class SpotifyEnrichmentStatusCount(
    @ColumnInfo(name = "spotify_enrichment_status") val status: SpotifyEnrichmentStatus,
    val count: Int
)


/**
 * Artwork conflict resolver for automatic enrichment writes.
 *
 * Manual artwork is controlled exclusively by explicit UI actions:
 * automatic writes may preserve an existing manual choice, but may never create,
 * replace, or resurrect USER_SELECTED from a stale snapshot.
 */
internal fun mergeAutomaticEnrichmentArtwork(
    current: EnrichedMetadata?,
    incoming: EnrichedMetadata,
): EnrichedMetadata {
    fun preserveCurrentArtwork(): EnrichedMetadata =
        incoming.copy(
            albumArtUrl = current?.albumArtUrl,
            albumArtUrlSmall = current?.albumArtUrlSmall,
            albumArtUrlLarge = current?.albumArtUrlLarge,
            albumArtSource = current?.albumArtSource ?: AlbumArtSource.NONE,
        )

    val currentManual = current?.takeIf {
        it.albumArtSource == AlbumArtSource.USER_SELECTED &&
            !it.albumArtUrl.isNullOrBlank()
    }

    if (currentManual != null) {
        val selectedUrl = currentManual.albumArtUrl!!
        return incoming.copy(
            albumArtUrl = selectedUrl,
            albumArtUrlSmall = currentManual.albumArtUrlSmall ?: selectedUrl,
            albumArtUrlLarge = currentManual.albumArtUrlLarge ?: selectedUrl,
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
    }

    val incomingHasRealAutomaticArtwork =
        incoming.albumArtSource != AlbumArtSource.NONE &&
            incoming.albumArtSource != AlbumArtSource.USER_RESET &&
            incoming.albumArtSource != AlbumArtSource.USER_SELECTED &&
            !incoming.albumArtUrl.isNullOrBlank()

    // After an explicit reset, keep the tombstone until a real automatic source
    // provides non-empty artwork. This prevents stale full-row snapshots from
    // resurrecting the old manual URL or prematurely erasing the reset marker.
    if (current?.albumArtSource == AlbumArtSource.USER_RESET &&
        !incomingHasRealAutomaticArtwork
    ) {
        return incoming.copy(
            albumArtUrl = null,
            albumArtUrlSmall = null,
            albumArtUrlLarge = null,
            albumArtSource = AlbumArtSource.USER_RESET,
        )
    }

    // An automatic task can hold a stale copy of USER_SELECTED after the user has
    // deliberately returned to automatic selection. Never let that stale snapshot
    // re-create the manual lock.
    if (incoming.albumArtSource == AlbumArtSource.USER_SELECTED) {
        return preserveCurrentArtwork()
    }

    val currentHasArtwork = !current?.albumArtUrl.isNullOrBlank()
    if (currentHasArtwork && !incomingHasRealAutomaticArtwork) {
        // Status/genre/etc. writes without artwork must not erase a cover selected
        // by a previous automatic source.
        return preserveCurrentArtwork()
    }

    val currentHasRealAutomaticArtwork =
        currentHasArtwork &&
            current?.albumArtSource != null &&
            current.albumArtSource != AlbumArtSource.NONE &&
            current.albumArtSource != AlbumArtSource.USER_RESET &&
            current.albumArtSource != AlbumArtSource.USER_SELECTED

    if (currentHasRealAutomaticArtwork &&
        incomingHasRealAutomaticArtwork &&
        !current!!.albumArtSource.shouldBeReplacedBy(incoming.albumArtSource)
    ) {
        // The incoming request may have started before a higher-priority provider
        // finished. Preserve the winner while still accepting all non-art metadata
        // from the stale request.
        return preserveCurrentArtwork()
    }

    return incoming
}
