package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.ListeningEvent
import kotlinx.coroutines.flow.Flow

interface ListeningRepository {
    fun eventsForTrack(trackId: Long): Flow<List<ListeningEvent>>
    fun all(): Flow<List<ListeningEvent>>
    fun recentEvents(limit: Int): Flow<List<ListeningEvent>>
    fun eventsInRange(startTime: Long, endTime: Long): Flow<List<ListeningEvent>>
    
    suspend fun insert(event: ListeningEvent): Long
    suspend fun insertAll(events: List<ListeningEvent>): List<Long>
    suspend fun delete(event: ListeningEvent)
    suspend fun deleteById(id: Long): Int
    suspend fun deleteByTrackId(trackId: Long)
    suspend fun deleteByArtist(artistName: String): Int
    suspend fun getEventsForTrack(trackId: Long): List<ListeningEvent>
    suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent>
    
    // Enhanced engagement queries
    suspend fun getSkipCountForTrack(trackId: Long): Int
    suspend fun getReplayCountForTrack(trackId: Long): Int
    suspend fun getAverageCompletionForTrack(trackId: Long): Float?
    suspend fun getFullPlayCountForTrack(trackId: Long): Int
    suspend fun getLastPlayTimestampForTrack(trackId: Long): Long?
    suspend fun getFirstPlayTimestampForTrack(trackId: Long): Long?
    suspend fun wasRecentlyPlayed(trackId: Long, sinceTimestamp: Long): Boolean
    suspend fun getEventsBySessionId(sessionId: String): List<ListeningEvent>
    suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long
    suspend fun getSkipRate(startTime: Long, endTime: Long): Float?
    suspend fun getAverageCompletion(startTime: Long, endTime: Long): Float?
}
