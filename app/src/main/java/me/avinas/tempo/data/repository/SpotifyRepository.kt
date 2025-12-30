package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus
import me.avinas.tempo.data.remote.spotify.SpotifyAudioFeatures
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Spotify-related data operations.
 * Provides access to Spotify connection status and audio feature statistics.
 */
interface SpotifyRepository {
    /**
     * Check if Spotify is connected.
     */
    fun isConnected(): Boolean
    
    /**
     * Get the connected user's display name.
     */
    fun getUserDisplayName(): String?
    
    /**
     * Get count of tracks with Spotify audio features.
     */
    suspend fun getEnrichedTracksCount(): Int
    
    /**
     * Get count of tracks pending Spotify enrichment.
     */
    suspend fun getPendingTracksCount(): Int
    
    /**
     * Get aggregated audio features stats for all enriched tracks.
     */
    suspend fun getAggregatedAudioFeatures(): AggregatedAudioStats?
    
    /**
     * Get Spotify enrichment statistics.
     */
    suspend fun getEnrichmentStats(): Map<SpotifyEnrichmentStatus, Int>
    
    /**
     * Queue all eligible tracks for Spotify enrichment.
     */
    suspend fun queueAllForEnrichment()
    
    /**
     * Clear all Spotify data (for disconnect).
     */
    suspend fun clearAllData()
}

/**
 * Aggregated audio stats across all enriched tracks.
 */
data class AggregatedAudioStats(
    val averageEnergy: Float,
    val averageValence: Float,
    val averageDanceability: Float,
    val averageTempo: Float,
    val averageAcousticness: Float,
    val averageInstrumentalness: Float,
    val averageSpeechiness: Float,
    val averageLiveness: Float,
    val trackCount: Int
) {
    val energyPercentage: Int get() = (averageEnergy * 100).toInt()
    val moodPercentage: Int get() = (averageValence * 100).toInt()
    val danceabilityPercentage: Int get() = (averageDanceability * 100).toInt()
}

@Singleton
class RoomSpotifyRepository @Inject constructor(
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val authManager: SpotifyAuthManager,
    private val tokenStorage: SpotifyTokenStorage,
    private val moshi: Moshi
) : SpotifyRepository {

    override fun isConnected(): Boolean {
        return authManager.isConnected()
    }

    override fun getUserDisplayName(): String? {
        return tokenStorage.getUserDisplayName()
    }

    override suspend fun getEnrichedTracksCount(): Int {
        return enrichedMetadataDao.countTracksWithSpotifyFeatures()
    }

    override suspend fun getPendingTracksCount(): Int {
        return enrichedMetadataDao.countTracksPendingSpotifyEnrichment()
    }

    override suspend fun getAggregatedAudioFeatures(): AggregatedAudioStats? {
        // Get all tracks with Spotify features
        val tracksWithFeatures = enrichedMetadataDao.getTracksNeedingSpotifyEnrichment(1000)
            .filter { it.audioFeaturesJson != null }
        
        if (tracksWithFeatures.isEmpty()) {
            return null
        }

        val adapter = moshi.adapter(SpotifyAudioFeatures::class.java)
        
        var totalEnergy = 0f
        var totalValence = 0f
        var totalDanceability = 0f
        var totalTempo = 0f
        var totalAcousticness = 0f
        var totalInstrumentalness = 0f
        var totalSpeechiness = 0f
        var totalLiveness = 0f
        var count = 0

        for (track in tracksWithFeatures) {
            try {
                val features = adapter.fromJson(track.audioFeaturesJson!!)
                if (features != null) {
                    totalEnergy += features.energy
                    totalValence += features.valence
                    totalDanceability += features.danceability
                    totalTempo += features.tempo
                    totalAcousticness += features.acousticness
                    totalInstrumentalness += features.instrumentalness
                    totalSpeechiness += features.speechiness
                    totalLiveness += features.liveness
                    count++
                }
            } catch (e: Exception) {
                // Skip invalid JSON
            }
        }

        if (count == 0) {
            return null
        }

        return AggregatedAudioStats(
            averageEnergy = totalEnergy / count,
            averageValence = totalValence / count,
            averageDanceability = totalDanceability / count,
            averageTempo = totalTempo / count,
            averageAcousticness = totalAcousticness / count,
            averageInstrumentalness = totalInstrumentalness / count,
            averageSpeechiness = totalSpeechiness / count,
            averageLiveness = totalLiveness / count,
            trackCount = count
        )
    }

    override suspend fun getEnrichmentStats(): Map<SpotifyEnrichmentStatus, Int> {
        return enrichedMetadataDao.getSpotifyEnrichmentStats()
            .associate { it.status to it.count }
    }

    override suspend fun queueAllForEnrichment() {
        enrichedMetadataDao.queueAllForSpotifyEnrichment()
    }

    override suspend fun clearAllData() {
        enrichedMetadataDao.clearAllSpotifyData()
    }
}
