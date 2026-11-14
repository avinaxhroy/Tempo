package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository for enriched metadata, following the "Enrichment → Database → UI"
 * pattern: MusicBrainz/Spotify enrichment writes here, and the UI reads cached
 * rows rather than calling the APIs itself.
 */
interface EnrichedMetadataRepository {
    
    // Core Read Operations (for UI)
    
    /** Reactive read: re-emits as enrichment writes land. Prefer this for UI. */
    fun forTrack(trackId: Long): Flow<EnrichedMetadata?>
    
    /** One-shot read, for a ViewModel load. */
    suspend fun forTrackSync(trackId: Long): EnrichedMetadata?
    
    // Write Operations (for Enrichment Services)
    
    /** Insert or update; called by enrichment services after an API fetch. */
    suspend fun upsert(metadata: EnrichedMetadata): Long

    /**
     * Persist metadata produced automatically while preserving the user's artwork lock
     * against in-flight enrichment races.
     *
     * [preferred] is the user's preferred cover provider, when one is set. It must be
     * threaded through to the merge so that a cover from the preferred provider can
     * displace one from a higher-priority-but-not-preferred provider.
     */
    suspend fun upsertFromAutomaticEnrichment(
        metadata: EnrichedMetadata,
        preferred: AlbumArtSource? = null,
    ): Long

    /** Atomically persist a user-selected cover and its Track-table mirror. */
    suspend fun setUserSelectedArtwork(
        trackId: Long,
        albumArtUrl: String,
        albumArtUrlSmall: String,
        albumArtUrlLarge: String,
    ): Long

    /** Atomically clear manual artwork and mark the track for automatic selection. */
    suspend fun resetArtworkToAutomatic(trackId: Long): Long
    
    suspend fun createPendingIfNotExists(trackId: Long)
    
    /** Mark for re-enrichment (manual refresh). */
    suspend fun markForReEnrichment(trackId: Long)
    
    // Stats Operations (for UI)
    
    suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int>

    suspend fun countByStatus(status: EnrichmentStatus): Int

    suspend fun countAllTracks(): Int

    suspend fun countTracksWithAlbumArt(): Int

    /**
     * Re-queue all non-enriched tracks to PENDING (for "Enrich All"). Returns rows updated.
     *
     * Tracks that came back NOT_FOUND are blocked for
     * [EnrichedMetadata.NOT_FOUND_RETRY_BLOCK_MS] (7 days) after their last attempt:
     * they were already searched across every source, so re-running the sweep must
     * not re-query the external APIs for them.
     */
    suspend fun requeueAllForEnrichment(): Int

    /**
     * Count NOT_FOUND tracks still inside the 7-day re-enrichment block — i.e. songs
     * that "Enrich All" will deliberately skip this time. For UI feedback.
     */
    suspend fun countNotFoundBlockedFromRequeue(): Int

    /** Tracks that have no enriched_metadata row at all (never queued for enrichment). */
    suspend fun countTracksWithoutEnrichedMetadata(): Int

    /** Include never-queued tracks in the "Enrich All" sweep. */
    suspend fun ensurePendingForAllTracks(): Int

    /** Defer low-play PENDING tracks to SKIPPED (large-import cap). Returns rows updated. */
    suspend fun markLowPlayPendingAsSkipped(minPlayCount: Int = 2): Int
    
    suspend fun countTracksWithSpotifyFeatures(): Int
    
    suspend fun countTracksPendingSpotifyEnrichment(): Int
    
    // Spotify Integration (for Enrichment & UI)
    
    /** Run when the user first connects Spotify. */
    suspend fun queueAllForSpotifyEnrichment()
    
    suspend fun clearAllSpotifyData()
    
    suspend fun updateSpotifyEnrichmentStatus(
        trackId: Long,
        status: SpotifyEnrichmentStatus,
        error: String? = null
    )
    
    // Lookup Operations
    
    /** By MusicBrainz recording ID. */
    suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata?
    
    suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata?
}
