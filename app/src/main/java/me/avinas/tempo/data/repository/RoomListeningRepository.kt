package me.avinas.tempo.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ManualContentRuleResolver
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomListeningRepository @Inject constructor(
    private val dao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    @ApplicationContext context: Context
) : ListeningRepository {
    private val trackingRules = TrackingRulesPreferences(context)
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

    /**
     * Resolve the current Room-backed manual override at the final persistence boundary.
     * Events can wait in the manager's batch/offline queues, so the rule must be re-read
     * immediately before the database write rather than relying only on the service cache.
     */
    override suspend fun shouldPersist(event: ListeningEvent): Boolean {
        // A listening event cannot be valid without its parent track (foreign key). This also
        // cleanly discards an event that was queued before NON_MUSIC removed the track.
        val track = trackDao.getTrackById(event.track_id) ?: return false
        val matchingMark = ManualContentRuleResolver.resolve(
            manualContentMarkDao.getAllSync(), track.title, track.artist
        )

        val isAlwaysMusic = when (matchingMark?.contentType?.uppercase()) {
            "NON_MUSIC", "VIDEO" -> return false
            "ALWAYS_MUSIC" -> {
                // Stats/history also filter by Track.contentType. Normalize the track itself
                // so an old PODCAST/AUDIOBOOK classification cannot hide an allowed play.
                if (track.contentType != "MUSIC") {
                    trackDao.update(track.copy(contentType = "MUSIC"))
                }
                true
            }
            else -> false
        }

        // Minimum listening time is a counting rule, not a content-classification rule;
        // Always Music therefore still has to meet it.
        if (event.playDuration < trackingRules.minimumPlayDurationMs) return false

        // Only use a reliable persisted/enriched duration here. event.estimatedDurationMs may
        // come from SmartDurationEstimator and must never be treated as proof that media is long.
        // The pre/post persistence calls around batch/offline writes mean a duration discovered
        // while an insert is in flight is still caught.
        if (!isAlwaysMusic) {
            val reliableDuration = track.duration?.takeIf { it > 0L }
                ?: enrichedMetadataDao.forTrackSync(event.track_id)?.trackDurationMs?.takeIf { it > 0L }
            if (reliableDuration != null) {
                val maxDuration = trackingRules.getMaxMusicDurationMs(event.source)
                if (maxDuration != null && reliableDuration > maxDuration) return false
            }
        }

        return true
    }

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
