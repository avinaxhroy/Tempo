package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.entities.Artist
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomArtistRepository @Inject constructor(private val dao: ArtistDao) : ArtistRepository {
    override fun getById(id: Long): Flow<Artist?> = dao.getById(id)
    override fun search(query: String): Flow<List<Artist>> = dao.search(query)
    override suspend fun insert(artist: Artist): Long = dao.insert(artist)
    override suspend fun update(artist: Artist) = dao.update(artist)
}
