package me.avinas.tempo.data.repository

import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.entities.TrackAlias
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackAliasRepository @Inject constructor(
    private val trackAliasDao: TrackAliasDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val database: AppDatabase
) {
    /**
     * finding an alias for the given title and artist.
     * Returns the alias if one exists, or null.
     */
    suspend fun findAlias(title: String, artist: String): TrackAlias? {
        return trackAliasDao.findAlias(title, artist)
    }

    /**
     * Create a new alias mapping (originalTitle, originalArtist) -> targetTrackId.
     */
    suspend fun createAlias(targetTrackId: Long, originalTitle: String, originalArtist: String) {
        val alias = TrackAlias(
            targetTrackId = targetTrackId,
            originalTitle = originalTitle,
            originalArtist = originalArtist
        )
        trackAliasDao.insertAlias(alias)
    }
    
    /**
     * Get all aliases that point to a specific track.
     */
    suspend fun getAliasesForTrack(trackId: Long): List<TrackAlias> {
        return trackAliasDao.getAliasesForTrack(trackId)
    }
    
    /**
     * Merges source track into target track:
     * 1. Creates alias Source -> Target
     * 2. Moves history from Source to Target
     * 3. Deletes Source Track
     */
    suspend fun mergeTracks(sourceTrackId: Long, targetTrackId: Long) {
        if (sourceTrackId == targetTrackId) return
        
        // Find source details first for the alias
        val sourceTrack = trackDao.getTrackById(sourceTrackId) ?: return
        
        database.withTransaction {
            // 1. Create Alias
            val alias = TrackAlias(
                targetTrackId = targetTrackId,
                originalTitle = sourceTrack.title,
                originalArtist = sourceTrack.artist
            )
            trackAliasDao.insertAlias(alias)
            
            // 2. Move history
            listeningEventDao.reattributeEvents(sourceTrackId, targetTrackId)
            
            // 3. Delete source track
            trackDao.deleteById(sourceTrackId)
        }
    }
}
