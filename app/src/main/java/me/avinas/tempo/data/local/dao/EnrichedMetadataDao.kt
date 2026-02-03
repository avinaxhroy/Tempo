package me.avinas.tempo.data.local.dao

import androidx.room.*
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
    
    @Query("SELECT * FROM enriched_metadata WHERE musicbrainz_recording_id = :mbid LIMIT 1")
    suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: EnrichedMetadata): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(metadata: List<EnrichedMetadata>): List<Long>
    
    @Update
    suspend fun update(metadata: EnrichedMetadata)

    @Query("DELETE FROM enriched_metadata WHERE cache_timestamp < :expiry")
    suspend fun deleteOlderThan(expiry: Long)
    
    /**
     * Get tracks that need enrichment, prioritized by play count.
     * Joins with listening_events to get play count for prioritization.
     */
    @Query("""
        SELECT em.* FROM enriched_metadata em
        LEFT JOIN (
            SELECT track_id, COUNT(*) as play_count 
            FROM listening_events 
            GROUP BY track_id
        ) le ON em.track_id = le.track_id
        WHERE em.enrichment_status = :status
        ORDER BY COALESCE(le.play_count, 0) DESC, em.id ASC
        LIMIT :limit
    """)
    suspend fun getTracksNeedingEnrichment(
        status: EnrichmentStatus = EnrichmentStatus.PENDING,
        limit: Int = 10
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
    
    // =====================
    // Spotify Enrichment Queries
    // =====================
    
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
}

data class EnrichmentStatusCount(
    @ColumnInfo(name = "enrichment_status") val status: EnrichmentStatus,
    val count: Int
)

data class SpotifyEnrichmentStatusCount(
    @ColumnInfo(name = "spotify_enrichment_status") val status: SpotifyEnrichmentStatus,
    val count: Int
)
