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
    
    // =====================
    // Core Read Operations (for UI)
    // =====================
    
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
    
    // =====================
    // Write Operations (for Enrichment Services)
    // =====================
    
    /**
     * Insert or update enriched metadata.
     * Called by enrichment services after fetching data from APIs.
     */
    suspend fun upsert(metadata: EnrichedMetadata): Long
    
    /**
     * Create a pending enrichment record if one doesn't exist.
     * Called when a new track is detected to queue it for enrichment.
     */
    suspend fun createPendingIfNotExists(trackId: Long)
    
    /**
     * Mark a track for re-enrichment (manual refresh).
     */
    suspend fun markForReEnrichment(trackId: Long)
    
    // =====================
    // Stats Operations (for UI)
    // =====================
    
    /**
     * Get count of tracks by enrichment status.
     */
    suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int>
    
    /**
     * Get count of tracks with Spotify audio features.
     */
    suspend fun countTracksWithSpotifyFeatures(): Int
    
    /**
     * Get count of tracks pending Spotify enrichment.
     */
    suspend fun countTracksPendingSpotifyEnrichment(): Int
    
    // =====================
    // Spotify Integration (for Enrichment & UI)
    // =====================
    
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
    
    // =====================
    // Lookup Operations
    // =====================
    
    /**
     * Find metadata by MusicBrainz recording ID.
     */
    suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata?
    
    /**
     * Find metadata by Spotify track ID.
     */
    suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata?
}
