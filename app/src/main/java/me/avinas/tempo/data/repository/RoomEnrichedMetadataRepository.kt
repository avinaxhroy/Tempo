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
    
    // Core Read Operations (for UI)
    
    override fun forTrack(trackId: Long): Flow<EnrichedMetadata?> = dao.forTrack(trackId)
    
    override suspend fun forTrackSync(trackId: Long): EnrichedMetadata? = dao.forTrackSync(trackId)
    
    // Write Operations (for Enrichment Services)
    
    override suspend fun upsert(metadata: EnrichedMetadata): Long = dao.upsert(metadata)

    override suspend fun upsertFromAutomaticEnrichment(metadata: EnrichedMetadata): Long =
        dao.upsertFromAutomaticEnrichment(metadata)

    override suspend fun setUserSelectedArtwork(
        trackId: Long,
        albumArtUrl: String,
        albumArtUrlSmall: String,
        albumArtUrlLarge: String,
    ): Long =
        dao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = albumArtUrl,
            albumArtUrlSmall = albumArtUrlSmall,
            albumArtUrlLarge = albumArtUrlLarge,
            timestamp = System.currentTimeMillis(),
        )

    override suspend fun resetArtworkToAutomatic(trackId: Long): Long =
        dao.resetArtworkToAutomatic(
            trackId = trackId,
            timestamp = System.currentTimeMillis(),
        )
    
    override suspend fun createPendingIfNotExists(trackId: Long) {
        val pending = EnrichedMetadata(
            trackId = trackId,
            enrichmentStatus = EnrichmentStatus.PENDING,
            cacheTimestamp = System.currentTimeMillis()
        )
        // One INSERT OR IGNORE is atomic. A manual selection or enrichment row
        // that wins the race is preserved instead of being REPLACEd by PENDING.
        dao.insertIfAbsent(pending)
    }
    
    override suspend fun markForReEnrichment(trackId: Long) {
        dao.markForReEnrichment(trackId)
    }
    
    // Stats Operations (for UI)
    
    override suspend fun getEnrichmentStats(): Map<EnrichmentStatus, Int> {
        return dao.getEnrichmentStats().associate { it.status to it.count }
    }

    override suspend fun countByStatus(status: EnrichmentStatus): Int = dao.countByStatus(status)

    override suspend fun countAllTracks(): Int = dao.countAllTracks()

    override suspend fun countTracksWithAlbumArt(): Int = dao.countTracksWithAlbumArt()

    override suspend fun requeueAllForEnrichment(): Int {
        val notFoundAttemptedBefore =
            System.currentTimeMillis() - EnrichedMetadata.NOT_FOUND_RETRY_BLOCK_MS
        return dao.requeueAllForEnrichment(notFoundAttemptedBefore)
    }

    override suspend fun countNotFoundBlockedFromRequeue(): Int {
        val notFoundAttemptedBefore =
            System.currentTimeMillis() - EnrichedMetadata.NOT_FOUND_RETRY_BLOCK_MS
        return dao.countNotFoundBlockedFromRequeue(notFoundAttemptedBefore)
    }

    override suspend fun countTracksWithoutEnrichedMetadata(): Int =
        dao.countTracksWithoutEnrichedMetadata()

    override suspend fun ensurePendingForAllTracks(): Int {
        val missing = dao.countTracksWithoutEnrichedMetadata()
        if (missing == 0) return 0
        dao.insertPendingForUnqueuedTracks(System.currentTimeMillis())
        return missing
    }

    override suspend fun markLowPlayPendingAsSkipped(minPlayCount: Int): Int =
        dao.markLowPlayPendingAsSkipped(minPlayCount)
    
    override suspend fun countTracksWithSpotifyFeatures(): Int {
        return dao.countTracksWithSpotifyFeatures()
    }
    
    override suspend fun countTracksPendingSpotifyEnrichment(): Int {
        return dao.countTracksPendingSpotifyEnrichment()
    }
    
    // Spotify Integration
    
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
    
    // Lookup Operations
    
    override suspend fun findByMusicBrainzId(mbid: String): EnrichedMetadata? {
        return dao.findByMusicBrainzId(mbid)
    }
    
    override suspend fun findBySpotifyId(spotifyId: String): EnrichedMetadata? {
        return dao.findBySpotifyId(spotifyId)
    }
}
