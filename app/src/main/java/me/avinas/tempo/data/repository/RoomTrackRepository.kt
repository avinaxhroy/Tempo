package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTrackRepository @Inject constructor(private val dao: TrackDao) : TrackRepository {
    override fun getById(id: Long): Flow<Track?> = dao.getById(id)
    override suspend fun findBySpotifyId(spotifyId: String): Track? = dao.findBySpotifyId(spotifyId)
    override suspend fun findByTitleAndArtist(title: String, artist: String): Track? = 
        dao.findByTitleAndArtist(title, artist)
    override suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track? = 
        dao.findByTitleAndArtistFuzzy(title, artist)
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
}
