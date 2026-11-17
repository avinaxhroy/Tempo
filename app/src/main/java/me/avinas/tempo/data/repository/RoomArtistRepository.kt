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

    override suspend fun getByIdNow(id: Long): Artist? = dao.getArtistById(id)

    override suspend fun setUserSelectedImage(artistId: Long, imageUrl: String): String? {
        val previousUrl = dao.getArtistById(artistId)?.imageUrl
        dao.setUserSelectedImageUrl(artistId, imageUrl)
        return previousUrl
    }

    override suspend fun resetImageToAutomatic(artistId: Long) = dao.resetImageToAutomatic(artistId)
}
