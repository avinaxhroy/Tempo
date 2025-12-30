package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    fun getById(id: Long): Flow<Track?>
    suspend fun findBySpotifyId(spotifyId: String): Track?
    suspend fun insert(track: Track): Long
    suspend fun update(track: Track)
    fun all(): Flow<List<Track>>
}
