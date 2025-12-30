package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Album
import kotlinx.coroutines.flow.Flow

interface AlbumRepository {
    fun getById(id: Long): Flow<Album?>
    fun albumsForArtist(artistId: Long): Flow<List<Album>>
    suspend fun insert(album: Album): Long
    suspend fun update(album: Album)
}
