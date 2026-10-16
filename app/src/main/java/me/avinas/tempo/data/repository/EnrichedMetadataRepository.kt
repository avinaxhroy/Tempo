package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for accessing enriched metadata.
 * 
 * This repository follows the "Enrichment → Database → UI" pattern:
 * - Enrichment services (MusicBrainz, Spotify) write data via this repository
 * - UI components read cached data from this repository (never make direct API calls)
 * - All data access goes through Room database for offline-first behavior
 * 
 * Benefits:
 * - Minimizes API requests by serving cached data to UI
 * - Single source of truth for enriched metadata
 * - UI always has fast access to cached data
 * - Background enrichment keeps data fresh without blocking UI
 */
interface EnrichedMetadataRepository {
    
    // Core Read Operations (for UI)
    
    /**
     * Get enriched metadata for a track as a Flow (reactive updates).
     * UI components should prefer this for real-time updates.
     */
    fun forTrack(trackId: Long): Flow<EnrichedMetadata?>
    
    /**
     * Get enriched metadata for a track synchronously.
     * Use when you need a one-time read (e.g., in ViewModels during load).
     */
    suspend fun forTrackSync(trackId: Long): EnrichedMetadata?
    
    // Write Operations (for Enrichment Services)
    
    /**
     * Insert or update enriched metadata.
     * Called by enrichment services after fetching data from APIs.
     */
    suspend fun upsert(metadata: EnrichedMetadata): Long

    /**
     * Persist metadata produced automatically while preserving the user's artwork lock
     * against in-flight enrichment races.
     */
    suspend fun upsertFromAutomaticEnrichment(metadata: EnrichedMetadata): Long

    /** Atomically persist a user-selected cover and its Track-table mirror. */
    suspend fun setUserSelectedArtwork(
        trackId: Long,
        albumArtUrl: String,
        albumArtUrlSmall: String,
        albumArtUrlLarge: String,
    ): Long

    /** Atomically clear manual artwork and mark the track for automatic selection. */
    suspend fun resetArtworkToAutomatic(trackId: Long): Long
    
    /**
     * Create a pending enrichment record if one doesn't exist.
     * Called when a new track is detected to queue it for enrichment.
     */
    suspend fun createPendingIfNotExists(trackId: Long)
    
    /**
     * Mark a track for re-enrichment (manual refresh).
     */
    suspend fun markForReEnrichment(trackId: Long)
    
    // Stats Operations (for UI)
    
    /**
     * Get count of tracks by enrichment status.
     */
    suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int>

    /** Count tracks in a specific status. */
    suspend fun countByStatus(status: EnrichmentStatus): Int

    /** Total tracks in the library. */
    suspend fun countAllTracks(): Int

    /** Tracks that currently have album art. */
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

    /**
     * Create PENDING rows for every track that has no enriched_metadata row yet.
     * Called by "Enrich All" so never-queued tracks are included in the sweep.
     * Returns the number of rows inserted.
     */
    suspend fun ensurePendingForAllTracks(): Int

    /** Defer low-play PENDING tracks to SKIPPED (large-import cap). Returns rows updated. */
    suspend fun markLowPlayPendingAsSkipped(minPlayCount: Int = 2): Int
    
    /**
     * Get count of tracks with Spotify audio features.
     */
    suspend fun countTracksWithSpotifyFeatures(): Int
    
    /**
     * Get count of tracks pending Spotify enrichment.
     */
    suspend fun countTracksPendingSpotifyEnrichment(): Int
    
    // Spotify Integration (for Enrichment & UI)
    
    /**
     * Queue all enriched tracks for Spotify enrichment.
     * Called when user first connects Spotify account.
     */
    suspend fun queueAllForSpotifyEnrichment()
    
    /**
     * Clear all Spotify data (for disconnect).
     * Called when user disconnects their Spotify account.
     */
    suspend fun clearAllSpotifyData()
    
    /**
     * Update Spotify enrichment status for a track.
     */
    suspend fun updateSpotifyEnrichmentStatus(
        trackId: Long,
        status: SpotifyEnrichmentStatus,
        error: String? = null
    )
    
    // Lookup Operations
    
    /**
     * Find metadata by MusicBrainz recording ID.
     */
    suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata?
    
    /**
     * Find metadata by Spotify track ID.
     */
    suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata?
}
