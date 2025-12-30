package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.AlbumDao
import me.avinas.tempo.data.local.entities.Album
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAlbumRepository @Inject constructor(private val dao: AlbumDao) : AlbumRepository {
    override fun getById(id: Long): Flow<Album?> = dao.getById(id)
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = dao.albumsForArtist(artistId)
    override suspend fun insert(album: Album): Long = dao.insert(album)
    override suspend fun update(album: Album) = dao.update(album)
}
