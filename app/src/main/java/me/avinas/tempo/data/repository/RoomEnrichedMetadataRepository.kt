package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room implementation of EnrichedMetadataRepository.
 * 
 * This repository is the single access point for enriched metadata:
 * - Enrichment services (workers) write enriched data here
 * - UI components read cached data from here (never make API calls)
 * 
 * Pattern: Enrichment → Database → UI
 * - EnrichmentWorker and SpotifyEnrichmentService call upsert() to save API data
 * - ViewModels call forTrack()/forTrackSync() to read cached data
 * - UI always displays from database, background enrichment keeps it fresh
 */
@Singleton
class RoomEnrichedMetadataRepository @Inject constructor(
    private val dao: EnrichedMetadataDao
) : EnrichedMetadataRepository {
    
    // =====================
    // Core Read Operations (for UI)
    // =====================
    
    override fun forTrack(trackId: Long): Flow<EnrichedMetadata?> = dao.forTrack(trackId)
    
    override suspend fun forTrackSync(trackId: Long): EnrichedMetadata? = dao.forTrackSync(trackId)
    
    // =====================
    // Write Operations (for Enrichment Services)
    // =====================
    
    override suspend fun upsert(metadata: EnrichedMetadata): Long = dao.upsert(metadata)
    
    override suspend fun createPendingIfNotExists(trackId: Long) {
        val existing = dao.forTrackSync(trackId)
        if (existing == null) {
            val pending = EnrichedMetadata(
                trackId = trackId,
                enrichmentStatus = EnrichmentStatus.PENDING,
                cacheTimestamp = System.currentTimeMillis()
            )
            dao.upsert(pending)
        }
    }
    
    override suspend fun markForReEnrichment(trackId: Long) {
        dao.markForReEnrichment(trackId)
    }
    
    // =====================
    // Stats Operations (for UI)
    // =====================
    
    override suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int> {
        return dao.getEnrichmentStats().associate { it.status to it.count }
    }
    
    override suspend fun countTracksWithSpotifyFeatures(): Int {
        return dao.countTracksWithSpotifyFeatures()
    }
    
    override suspend fun countTracksPendingSpotifyEnrichment(): Int {
        return dao.countTracksPendingSpotifyEnrichment()
    }
    
    // =====================
    // Spotify Integration
    // =====================
    
    override suspend fun queueAllForSpotifyEnrichment() {
        dao.queueAllForSpotifyEnrichment()
    }
    
    override suspend fun clearAllSpotifyData() {
        dao.clearAllSpotifyData()
    }
    
    override suspend fun updateSpotifyEnrichmentStatus(
        trackId: Long,
        status: SpotifyEnrichmentStatus,
        error: String?
    ) {
        dao.updateSpotifyEnrichmentStatus(trackId, status, error)
    }
    
    // =====================
    // Lookup Operations
    // =====================
    
    override suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata? {
        return dao.findByMusicBrainzId(mbid)
    }
    
    override suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata? {
        return dao.findBySpotifyId(spotifyId)
    }
}
