package me.avinas.tempo.data.repository

import android.util.Log
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTrackRepository @Inject constructor(
    private val dao: TrackDao,
    private val trackArtistDao: TrackArtistDao,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val enrichedMetadataDao: EnrichedMetadataDao
) : TrackRepository {

    companion object {
        private const val TAG = "TrackRepository"
    }

    override fun getById(id: Long): Flow<Track?> = dao.getById(id)
    override suspend fun findBySpotifyId(spotifyId: String): Track? = dao.findBySpotifyId(spotifyId)
    override suspend fun findByTitleAndArtist(title: String, artist: String): Track? = 
        dao.findByTitleAndArtist(title, artist)
    override suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track? = 
        dao.findByTitleAndArtistFuzzy(title, artist)
    override suspend fun findCandidatesByTitle(title: String): List<Track> =
        dao.findCandidatesByTitle(title)
    override suspend fun insert(track: Track): Long = dao.insert(track)
    override suspend fun insertAll(tracks: List<Track>): List<Long> = dao.insertAll(tracks)
    override suspend fun update(track: Track) = dao.update(track)
    override fun all(): Flow<List<Track>> = dao.all()
    
    override suspend fun searchTracks(query: String): List<Track> {
        val byTitle = dao.searchByTitle(query)
        val byArtist = dao.searchByArtist(query)
        return (byTitle + byArtist).distinctBy { it.id }
    }
    
    override suspend fun deleteById(id: Long): Int = dao.deleteById(id)
    
    override suspend fun updateContentTypeByArtist(artistName: String, contentType: String): Int =
        dao.updateContentTypeByArtist(artistName, contentType)
    
    override suspend fun getTrackIdsByArtist(artistName: String): List<Long> =
        dao.getTrackIdsByArtist(artistName)
    
    override suspend fun deleteByArtist(artistName: String): Int =
        dao.deleteByArtist(artistName)

    override suspend fun deleteTrackWithAllData(trackId: Long): DeleteResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Deleting track $trackId and all associated data")
            
            // 1. Delete track_artists junction entries
            trackArtistDao.deleteAllForTrack(trackId)
            
            // 2. Delete manual content marks
            manualContentMarkDao.deleteMarksByTrackId(trackId)
            
            // 3. Delete enriched metadata
            enrichedMetadataDao.deleteByTrackId(trackId)
            
            // 4. Delete the track itself
            // (listening_events will be cascade-deleted by FK constraint)
            val deleted = dao.deleteById(trackId)
            
            if (deleted > 0) {
                Log.d(TAG, "Successfully deleted track $trackId and all associated data")
                DeleteResult(success = true)
            } else {
                Log.w(TAG, "Track $trackId not found for deletion")
                DeleteResult(success = false, error = "Track not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete track $trackId", e)
            DeleteResult(success = false, error = e.message ?: "Failed to delete track")
        }
    }
}
