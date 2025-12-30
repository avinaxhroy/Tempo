package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Artist
import kotlinx.coroutines.flow.Flow

interface ArtistRepository {
    fun getById(id: Long): Flow<Artist?>
    fun search(query: String): Flow<List<Artist>>
    suspend fun insert(artist: Artist): Long
    suspend fun update(artist: Artist)
}
