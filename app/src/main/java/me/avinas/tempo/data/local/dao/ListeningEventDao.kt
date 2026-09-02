package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.EventFingerprint
import me.avinas.tempo.data.local.SourceAuthority
import me.avinas.tempo.data.local.entities.ListeningEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningEventDao {
    
    companion object {
        // SQLite variable limit is 999, ListeningEvent has ~12 columns
        const val BATCH_SIZE = 80
        
        // Timestamp tolerance for deduplication (5 seconds)
        // Two events within 5 seconds for the same track are considered duplicates
        const val DUPLICATE_TOLERANCE_MS = 5000L

        // Layer 2: minimum window for cross-source temporal reconciliation.
        // The same physical play recorded by two sources (e.g. Spotify endTime vs
        // Last.fm scrobble) can have normalized timestamps ~60s apart. Two events
        // for the same track from DIFFERENT sources within this window are treated
        // as the same play; the higher-authority one wins. Same-source events keep
        // using the tight [DUPLICATE_TOLERANCE_MS] so legitimate back-to-back plays
        // are never merged.
        const val RECONCILIATION_WINDOW_MS = 60_000L
    }
    
    @Query("SELECT * FROM listening_events WHERE id = :id")
    fun getById(id: Long): Flow<ListeningEvent?>

    @Query("SELECT * FROM listening_events WHERE track_id = :trackId ORDER BY timestamp DESC")
    fun eventsForTrack(trackId: Long): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE track_id = :trackId ORDER BY timestamp DESC")
    suspend fun getEventsForTrack(trackId: Long): List<ListeningEvent>

    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC")
    fun all(): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentEvents(limit: Int): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun eventsInRange(startTime: Long, endTime: Long): Flow<List<ListeningEvent>>
    
    @Query("SELECT * FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent>

    /**
     * Get only timestamps and durations for session calculation (memory efficient).
     */
    @Query("SELECT timestamp, playDuration FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getSessionPointsInRange(startTime: Long, endTime: Long): List<me.avinas.tempo.data.stats.SessionPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ListeningEvent): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ListeningEvent>): List<Long>
    
    /**
     * Check if an event already exists for this track within the tolerance window.
     * Used for deduplication during imports.
     */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE track_id = :trackId 
        AND timestamp BETWEEN :timestampMin AND :timestampMax
    """)
    suspend fun countEventsNearTimestamp(trackId: Long, timestampMin: Long, timestampMax: Long): Int
    
    /**
     * Get all existing timestamps for a set of tracks (for batch deduplication).
     * Returns pairs of (track_id, timestamp) for efficient lookup.
     */
    @Query("""
        SELECT track_id, timestamp FROM listening_events 
        WHERE track_id IN (:trackIds)
        ORDER BY track_id, timestamp
    """)
    suspend fun getTimestampsForTracks(trackIds: List<Long>): List<TrackTimestamp>

    /**
     * Layer 1: return the set of content fingerprints already present for the
     * given fingerprints. Used to drop exact re-import duplicates in O(1) per hit.
     */
    @Query("""
        SELECT DISTINCT content_fingerprint FROM listening_events
        WHERE content_fingerprint IN (:fingerprints)
    """)
    suspend fun getExistingFingerprints(fingerprints: List<String>): List<String>

    /**
     * Layer 2: fetch a lightweight view of existing events for one track within a
     * time range, for cross-source temporal reconciliation. Bounded by the
     * (track_id, timestamp) index so it stays cheap even for large libraries.
     */
    @Query("""
        SELECT id, track_id, timestamp, playDuration, source, content_fingerprint, end_timestamp
        FROM listening_events
        WHERE track_id = :trackId
        AND timestamp BETWEEN :tsMin AND :tsMax
    """)
    suspend fun getEventsForReconciliation(
        trackId: Long,
        tsMin: Long,
        tsMax: Long
    ): List<ExistingEventRef>

    /**
     * Delete a batch of events by id (used to remove lower-authority events that
     * a higher-authority incoming event replaces during reconciliation).
     */
    @Query("DELETE FROM listening_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int
    
    /**
     * Batch insert with automatic, source-aware deduplication. This is the single
     * chokepoint every import path (Spotify JSON, Last.fm, YouTube Music, ZIP
     * restore) routes through, so the protection lives here.
     *
     * Layer 1 — content fingerprint (idempotency): a deterministic SHA-256 of
     *   (source|track_id|timestamp|playDuration|endTimestamp) is computed for
     *   every incoming event. Any event whose fingerprint already exists in the
     *   DB (or earlier in this same batch) is dropped. This makes re-importing
     *   the same file a complete no-op, immune to timestamp drift.
     *
     * Layer 2 — cross-source temporal reconciliation: the same physical play can
     *   arrive from two sources whose normalized timestamps drift by ~60s
     *   (Spotify endTime vs Last.fm scrobble). Survivors of Layer 1 are compared
     *   against existing events on the same track within a generous window; the
     *   higher-[SourceAuthority] representation wins. A more authoritative
     *   incoming event *replaces* a less authoritative existing one (the latter
     *   is deleted); an equal-or-lower-authority incoming event is skipped, so
     *   real listening data can never be overwritten by an import.
     *
     * Same-source comparisons keep using the tight [DUPLICATE_TOLERANCE_MS] so
     * legitimate back-to-back plays of a track are never merged.
     */
    @Transaction
    suspend fun insertAllBatchedWithDedup(events: List<ListeningEvent>): InsertResult {
        if (events.isEmpty()) return InsertResult(0, 0)

        // ── Layer 1: fingerprint every incoming event ──────────────────────
        val withFp = events.map { e ->
            if (e.contentFingerprint != null) e
            else e.copy(contentFingerprint = EventFingerprint.compute(e))
        }

        val incomingFps = withFp.mapNotNull { it.contentFingerprint }.distinct()
        val existingFps: Set<String> = if (incomingFps.isEmpty()) emptySet()
            else incomingFps.chunked(900).flatMap { getExistingFingerprints(it) }.toSet()

        // Drop exact-fingerprint duplicates (DB or earlier in this batch).
        val seenFp = HashSet<String>(incomingFps.size)
        val layer1Survivors = ArrayList<ListeningEvent>(withFp.size)
        var skipped = 0
        for (e in withFp) {
            val fp = e.contentFingerprint
            if (fp != null && (fp in existingFps || !seenFp.add(fp))) {
                skipped++
            } else {
                layer1Survivors.add(e)
            }
        }

        // ── Layer 2: cross-source temporal reconciliation ──────────────────
        val toInsert = ArrayList<ListeningEvent>(layer1Survivors.size)
        val toDelete = mutableSetOf<Long>()

        // Group by track for bounded per-track queries (uses the (track_id,
        // timestamp) index). Process higher-authority events first within each
        // track so they claim the slot and lower-authority siblings are dropped.
        val byTrack = layer1Survivors.groupBy { it.track_id }
        for ((trackId, trackEvents) in byTrack) {
            val minTs = trackEvents.minOf { it.timestamp } - RECONCILIATION_WINDOW_MS
            val maxTs = trackEvents.maxOf { it.timestamp } + RECONCILIATION_WINDOW_MS
            val existing = getEventsForReconciliation(trackId, minTs, maxTs)

            // Existing refs not yet marked for deletion.
            val existingAlive = existing.filter { it.id !in toDelete }.toMutableList()

            // Incoming events for this track, most authoritative first (tie → earliest).
            val sortedIncoming = trackEvents.sortedWith(
                compareByDescending<ListeningEvent> { SourceAuthority.rank(it.source) }
                    .thenBy { it.timestamp }
            )

            // Timestamps already accepted (existing-kept + incoming-accepted) for
            // same-play conflict checks on this track.
            val acceptedSlots = ArrayList<Slot>(existingAlive.size + sortedIncoming.size)
            for (ex in existingAlive) {
                acceptedSlots.add(Slot(ex.timestamp, ex.end_timestamp, ex.playDuration, ex.source, true))
            }

            for (incoming in sortedIncoming) {
                val incomingAuth = SourceAuthority.rank(incoming.source)
                val conflictIdx = acceptedSlots.indexOfFirst { slot -> isSamePlay(slot, incoming) }

                if (conflictIdx < 0) {
                    // No conflict → accept the incoming event.
                    toInsert.add(incoming)
                    acceptedSlots.add(
                        Slot(incoming.timestamp, incoming.endTimestamp, incoming.playDuration, incoming.source, false)
                    )
                    continue
                }

                val conflict = acceptedSlots[conflictIdx]
                if (conflict.isExisting && incomingAuth > SourceAuthority.rank(conflict.source)) {
                    // Incoming is more authoritative → it replaces the existing event.
                    // Find the ExistingEventRef this slot came from and mark it for deletion.
                    val ref = existingAlive.first { ref ->
                        ref.timestamp == conflict.timestamp &&
                            ref.end_timestamp == conflict.endTimestamp &&
                            ref.source == conflict.source
                    }
                    toDelete.add(ref.id)
                    existingAlive.remove(ref)
                    // Replace the slot with the incoming event so later comparisons
                    // see the new (higher-authority) representation.
                    acceptedSlots[conflictIdx] = Slot(
                        incoming.timestamp, incoming.endTimestamp, incoming.playDuration, incoming.source, false
                    )
                    toInsert.add(incoming)
                } else {
                    // Existing/equal authority wins, or the slot was already taken
                    // by an equal-higher-authority incoming sibling → drop this one.
                    skipped++
                }
            }
        }

        // Delete the lower-authority events that were replaced.
        if (toDelete.isNotEmpty()) {
            toDelete.chunked(900).forEach { ids -> deleteByIds(ids) }
        }

        // Insert the survivors (fingerprint already set on each).
        val inserted = if (toInsert.isEmpty()) 0
            else toInsert.chunked(BATCH_SIZE).sumOf { batch -> insertAll(batch).size }

        return InsertResult(inserted = inserted, skipped = skipped, replaced = toDelete.size)
    }

    /** A previously-decided play on a track, used for same-play conflict checks. */
    private data class Slot(
        val timestamp: Long,
        val endTimestamp: Long?,
        val playDuration: Long,
        val source: String,
        val isExisting: Boolean
    )

    /**
     * Decide whether an already-decided [slot] and an [incoming] event represent
     * the same physical play. Same-source uses the tight tolerance (avoids merging
     * legitimate back-to-back plays); different sources use a generous window that
     * absorbs cross-source timestamp drift.
     */
    private fun isSamePlay(slot: Slot, incoming: ListeningEvent): Boolean {
        val slotDriveDevice = SourceAuthority.driveDeviceId(slot.source)
        val incomingDriveDevice = SourceAuthority.driveDeviceId(incoming.source)
        // Layer 1 already removed an exact Drive event id. Distinct ids from the
        // same originating device are real rapid replays, not temporal dupes.
        if (slotDriveDevice != null && slotDriveDevice == incomingDriveDevice) return false

        val sameSource = slot.source == incoming.source &&
            slotDriveDevice == null && incomingDriveDevice == null
        val window: Long = if (sameSource) {
            DUPLICATE_TOLERANCE_MS
        } else {
            val half = maxOf(slot.playDuration, incoming.playDuration) / 2L
            if (half < RECONCILIATION_WINDOW_MS) RECONCILIATION_WINDOW_MS else half
        }
        return kotlin.math.abs(slot.timestamp - incoming.timestamp) <= window
    }
    
    /**
     * Batch insert with chunking for large imports.
     */
    @Transaction
    suspend fun insertAllBatched(events: List<ListeningEvent>): List<Long> {
        val results = mutableListOf<Long>()
        events.chunked(BATCH_SIZE).forEach { batch ->
            results.addAll(insertAll(batch))
        }
        return results
    }
    
    /**
     * Result of a deduplicating insert operation.
     *
     * @param inserted  events newly written to the database
     * @param skipped   incoming events dropped because an equal/higher-authority
     *                  duplicate already existed (fingerprint or temporal match)
     * @param replaced  existing lower-authority events deleted because a more
     *                  trustworthy incoming event represented the same play
     *                   (cross-source reconciliation). Always 0 for same-source imports.
     */
    data class InsertResult(
        val inserted: Int,
        val skipped: Int,
        val replaced: Int = 0
    ) {
        val total: Int get() = inserted + skipped
    }
    
    /**
     * Simple data class for timestamp lookup.
     */
    data class TrackTimestamp(
        val track_id: Long,
        val timestamp: Long
    )

    /**
     * Lightweight view of an existing event used for cross-source reconciliation.
     * Field names match the underlying column names so Room can map them directly.
     */
    data class ExistingEventRef(
        val id: Long,
        val track_id: Long,
        val timestamp: Long,
        val playDuration: Long,
        val source: String,
        val content_fingerprint: String?,
        val end_timestamp: Long?
    )

    /** Desktop source → count breakdown. */
    data class SourceCount(
        val source: String,
        val cnt: Int
    )

    /** Artist name → count. */
    data class ArtistCount(
        val artist: String,
        val cnt: Int
    )

    /** Track title + artist → count. */
    data class TrackCount(
        val title: String,
        val artist: String,
        val cnt: Int
    )

    @Delete
    suspend fun delete(event: ListeningEvent)
    
    @Query("DELETE FROM listening_events WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    @Query("DELETE FROM listening_events WHERE track_id = :trackId")
    suspend fun deleteByTrackId(trackId: Long)
    


    /**
     * Delete all listening events for one track.
     * Used by track merge after the events have been re-inserted against
     * the target track through the dedup pipeline.
     */
    @Query("DELETE FROM listening_events WHERE track_id = :trackId")
    suspend fun deleteEventsForTrack(trackId: Long): Int
    /**
     * Keyset page of events for backup export. Rows are fetched in id order,
     * page after page, so the full history is never materialized in memory at
     * once. Pass the last seen id as [afterId], starting at 0.
     *
     * [maxId] caps the scan at the snapshot boundary captured before the export
     * began. Live tracking keeps inserting rows during a backup; rows above the
     * boundary are excluded so the archive never contains events whose track row
     * is missing from the same snapshot (those would be silently dropped on
     * restore).
     */
    @Query("SELECT * FROM listening_events WHERE id > :afterId AND id <= :maxId ORDER BY id ASC LIMIT :limit")
    suspend fun getEventsPage(afterId: Long, maxId: Long, limit: Int): List<ListeningEvent>

    /** Highest event id at the moment of the call — export snapshot boundary. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM listening_events")
    suspend fun getMaxEventId(): Long
    
    // Enhanced Engagement Queries
    
    /**
     * Get total skip count for a track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND was_skipped = 1")
    suspend fun getSkipCountForTrack(trackId: Long): Int
    
    /**
     * Get total replay count for a track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND is_replay = 1")
    suspend fun getReplayCountForTrack(trackId: Long): Int
    
    /**
     * Get average completion percentage for a track.
     */
    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE track_id = :trackId")
    suspend fun getAverageCompletionForTrack(trackId: Long): Float?
    
    /**
     * Get full play count for a track (completion >= 80%).
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId AND completionPercentage >= 80")
    suspend fun getFullPlayCountForTrack(trackId: Long): Int
    
    /**
     * Get last play timestamp for a track.
     */
    @Query("SELECT MAX(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getLastPlayTimestampForTrack(trackId: Long): Long?
    
    /**
     * Get first play timestamp for a track.
     */
    @Query("SELECT MIN(timestamp) FROM listening_events WHERE track_id = :trackId")
    suspend fun getFirstPlayTimestampForTrack(trackId: Long): Long?
    
    /**
     * Get total play count for a specific track.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE track_id = :trackId")
    suspend fun countByTrackId(trackId: Long): Int
    
    /**
     * Check if a track was recently played (within specified milliseconds).
     * Used for replay detection.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM listening_events 
            WHERE track_id = :trackId 
            AND timestamp >= :sinceTimestamp
        )
    """)
    suspend fun wasRecentlyPlayed(trackId: Long, sinceTimestamp: Long): Boolean
    
    /**
     * Get events by session ID.
     */
    @Query("SELECT * FROM listening_events WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsBySessionId(sessionId: String): List<ListeningEvent>
    
    /**
     * Get total listening time in a time range.
     */
    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long
    
    /**
     * Get skip rate for a time range.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN was_skipped = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) 
        FROM listening_events 
        WHERE timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getSkipRate(startTime: Long, endTime: Long): Float?
    
    /**
     * Get average completion for a time range.
     */
    @Query("SELECT AVG(completionPercentage) FROM listening_events WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getAverageCompletion(startTime: Long, endTime: Long): Float?
    
    @Query("SELECT COUNT(*) FROM listening_events")
    suspend fun getCount(): Int
    
    /**
     * Delete all listening events for tracks belonging to a specific artist.
     * Returns the number of deleted rows.
     */
    @Query("""
        DELETE FROM listening_events 
        WHERE track_id IN (SELECT id FROM tracks WHERE LOWER(artist) = LOWER(:artistName))
    """)
    suspend fun deleteByArtist(artistName: String): Int

    /**
     * Get the timestamp of the very first listening event (earliest data point).
     */
    @Query("SELECT MIN(timestamp) FROM listening_events")
    suspend fun getEarliestEventTimestamp(): Long?
    
    /**
     * Get total listening time excluding imported events.
     * Only counts events from actual app usage (real-time tracking).
     */
    @Query("""
        SELECT COALESCE(SUM(playDuration), 0) FROM listening_events 
        WHERE source NOT LIKE '%import%'
    """)
    suspend fun getRealListeningTimeMs(): Long
    
    /**
     * Get total play count excluding imported events.
     * Only counts events from actual app usage (real-time tracking).
     */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE source NOT LIKE '%import%'
    """)
    suspend fun getRealPlayCount(): Int

    // ─── Desktop-specific stats queries ──────────────────────────────────────

    /**
     * Total number of plays received from the desktop satellite.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE source LIKE 'desktop:%'")
    suspend fun getDesktopPlayCount(): Int

    /**
     * Total listening time (ms) from desktop sources.
     */
    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events WHERE source LIKE 'desktop:%'")
    suspend fun getDesktopListeningTimeMs(): Long

    /**
     * Breakdown of desktop plays grouped by source app (e.g., "desktop:Spotify Desktop").
     * Returns source → count pairs, ordered by count descending.
     */
    @Query("""
        SELECT source, COUNT(*) as cnt 
        FROM listening_events 
        WHERE source LIKE 'desktop:%'
        GROUP BY source 
        ORDER BY cnt DESC
    """)
    suspend fun getDesktopSourceBreakdown(): List<SourceCount>

    /**
     * Top artist played from desktop, by count.
     */
    @Query("""
        SELECT t.artist, COUNT(*) as cnt
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.source LIKE 'desktop:%'
        GROUP BY t.artist
        ORDER BY cnt DESC
        LIMIT 1
    """)
    suspend fun getDesktopTopArtist(): ArtistCount?

    /**
     * Top track played from desktop, by count.
     */
    @Query("""
        SELECT t.title, t.artist, COUNT(*) as cnt
        FROM listening_events le
        INNER JOIN tracks t ON le.track_id = t.id
        WHERE le.source LIKE 'desktop:%'
        GROUP BY t.title, t.artist
        ORDER BY cnt DESC
        LIMIT 1
    """)
    suspend fun getDesktopTopTrack(): TrackCount?

    /**
     * Count of desktop plays in a given time range.
     */
    @Query("""
        SELECT COUNT(*) FROM listening_events 
        WHERE source LIKE 'desktop:%' 
        AND timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getDesktopPlayCountInRange(startTime: Long, endTime: Long): Int

    /**
     * Total desktop listening time (ms) in a given time range.
     */
    @Query("""
        SELECT COALESCE(SUM(playDuration), 0) FROM listening_events 
        WHERE source LIKE 'desktop:%' 
        AND timestamp >= :startTime AND timestamp <= :endTime
    """)
    suspend fun getDesktopListeningTimeMsInRange(startTime: Long, endTime: Long): Long
}
