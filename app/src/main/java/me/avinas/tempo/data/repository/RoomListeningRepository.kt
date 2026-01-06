package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomListeningRepository @Inject constructor(private val dao: ListeningEventDao) : ListeningRepository {
    override fun eventsForTrack(trackId: Long): Flow<List<ListeningEvent>> = dao.eventsForTrack(trackId)
    override fun all(): Flow<List<ListeningEvent>> = dao.all()
    override fun recentEvents(limit: Int): Flow<List<ListeningEvent>> = dao.recentEvents(limit)
    override fun eventsInRange(startTime: Long, endTime: Long): Flow<List<ListeningEvent>> = dao.eventsInRange(startTime, endTime)
    
    override suspend fun insert(event: ListeningEvent): Long = dao.insert(event)
    override suspend fun insertAll(events: List<ListeningEvent>): List<Long> = dao.insertAll(events)
    override suspend fun delete(event: ListeningEvent) = dao.delete(event)
    override suspend fun deleteById(id: Long): Int = dao.deleteById(id)
    override suspend fun deleteByTrackId(trackId: Long) = dao.deleteByTrackId(trackId)
    override suspend fun deleteByArtist(artistName: String): Int = dao.deleteByArtist(artistName)
    override suspend fun getEventsForTrack(trackId: Long): List<ListeningEvent> = dao.getEventsForTrack(trackId)
    override suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent> = dao.getEventsInRange(startTime, endTime)
    
    // Enhanced engagement queries
    override suspend fun getSkipCountForTrack(trackId: Long): Int = dao.getSkipCountForTrack(trackId)
    override suspend fun getReplayCountForTrack(trackId: Long): Int = dao.getReplayCountForTrack(trackId)
    override suspend fun getAverageCompletionForTrack(trackId: Long): Float? = dao.getAverageCompletionForTrack(trackId)
    override suspend fun getFullPlayCountForTrack(trackId: Long): Int = dao.getFullPlayCountForTrack(trackId)
    override suspend fun getLastPlayTimestampForTrack(trackId: Long): Long? = dao.getLastPlayTimestampForTrack(trackId)
    override suspend fun getFirstPlayTimestampForTrack(trackId: Long): Long? = dao.getFirstPlayTimestampForTrack(trackId)
    override suspend fun wasRecentlyPlayed(trackId: Long, sinceTimestamp: Long): Boolean = dao.wasRecentlyPlayed(trackId, sinceTimestamp)
    override suspend fun getEventsBySessionId(sessionId: String): List<ListeningEvent> = dao.getEventsBySessionId(sessionId)
    override suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long = dao.getTotalListeningTime(startTime, endTime)
    override suspend fun getSkipRate(startTime: Long, endTime: Long): Float? = dao.getSkipRate(startTime, endTime)
    override suspend fun getAverageCompletion(startTime: Long, endTime: Long): Float? = dao.getAverageCompletion(startTime, endTime)
}
