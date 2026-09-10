package me.avinas.tempo.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.ListeningRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages event batching, offline queueing with retries, deduplication,
 * and playback session persistence for music tracking.
 */
class MusicTrackingManager(
    private val listeningRepository: ListeningRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val offlineEventStore: OfflineEventStore? = null
) {
    companion object {
        private const val TAG = "MusicTrackingManager"
        
        // Batching configuration
        private const val BATCH_SIZE = 10
        private const val BATCH_TIMEOUT_MS = 30_000L // 30 seconds
        
        // Retry configuration
        private const val MAX_RETRIES = 5
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        
        // Deduplication window
        private const val DEDUP_WINDOW_MS = 5_000L // 5 seconds
        
        // Offline queue limits
        private const val MAX_OFFLINE_QUEUE_SIZE = 1000
    }
    
    // Event channel for batching
    private val eventChannel = Channel<PendingEvent>(Channel.BUFFERED)
    
    // Offline queue for events that couldn't be saved
    private val offlineQueue = ConcurrentHashMap<String, PendingEvent>()
    
    // Deduplication: recent event hashes
    private val recentEventHashes = ConcurrentHashMap<String, Long>()
    
    // Active sessions for recovery
    private val activeSessions = ConcurrentHashMap<String, SessionState>()
    
    // Metrics
    private val _metrics = MutableStateFlow(TrackingMetrics())
    val metrics: StateFlow<TrackingMetrics> = _metrics.asStateFlow()
    
    // Processing state
    private val isProcessing = MutableStateFlow(false)
    private val processingMutex = Mutex()
    private var batchJob: Job? = null
    
    init {
        // Restore events that failed to persist in a previous process lifetime.
        // An OEM kill or reboot must not discard plays that were already recorded.
        offlineEventStore?.let { store ->
            try {
                val restored = store.load()
                if (restored.isNotEmpty()) {
                    restored.forEach { pending -> offlineQueue[pending.key()] = pending }
                    Log.i(TAG, "Restored ${restored.size} offline events from durable storage")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore offline events", e)
            }
        }
        startBatchProcessor()
        startOfflineQueueProcessor()
    }
    
    /**
     * Queues a listening event for batched saving.
     * Returns true if the event was accepted, false if it was deduplicated or rejected.
     */
    suspend fun queueEvent(event: ListeningEvent, sessionId: String): Boolean {
        // Fast rejection at queue time. The same rule is checked again at the actual
        // persistence boundary because a manual classification can change while queued.
        if (!shouldPersistEvent(event)) {
            Log.d(TAG, "Event rejected by current persistence rules: trackId=${event.track_id}")
            return false
        }

        // Check for duplicates atomically using compute
        val eventHash = generateEventHash(event)
        val now = System.currentTimeMillis()
        
        var isDuplicate = false
        recentEventHashes.compute(eventHash) { _, lastSeen ->
            if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                isDuplicate = true
                lastSeen // Keep existing timestamp
            } else {
                now // Update/Insert new timestamp
            }
        }
        
        if (isDuplicate) {
            Log.d(TAG, "Duplicate event detected (atomic), skipping: trackId=${event.track_id} for session $sessionId")
            updateMetrics { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1) }
            return false
        }
        
        cleanupOldDeduplicationEntries(now)
        
        // Queue the event
        val pendingEvent = PendingEvent(
            event = event,
            sessionId = sessionId,
            queuedAt = now,
            retryCount = 0
        )
        
        eventChannel.send(pendingEvent)
        updateMetrics { it.copy(eventsQueued = it.eventsQueued + 1) }
        
        return true
    }
    
    /**
     * Saves an event immediately with retry logic.
     * Use this for critical events that need immediate persistence.
     */
    suspend fun saveEventImmediate(event: ListeningEvent): Result<Long> {
        if (!shouldPersistEvent(event)) return Result.success(0L)

        val result = withRetry(MAX_RETRIES, INITIAL_RETRY_DELAY_MS) {
            listeningRepository.insert(event)
        }
        if (result.isFailure) return result

        val id = result.getOrThrow()
        if (id > 0L && !shouldPersistEvent(event)) {
            // Covers a rule being committed while the insert itself was in flight.
            listeningRepository.deleteById(id)
            Log.d(TAG, "Removed event rejected immediately after persistence: trackId=${event.track_id}")
            return Result.success(0L)
        }

        if (id > 0L) {
            updateMetrics { it.copy(eventsSaved = it.eventsSaved + 1) }
        }
        return Result.success(id)
    }
    
    /**
     * Register an active playback session for recovery.
     */
    fun registerSession(sessionId: String, state: SessionState) {
        activeSessions[sessionId] = state
        Log.d(TAG, "Session registered: $sessionId, track=${state.trackTitle}")
    }
    
    /**
     * Update session state (e.g., on pause/resume).
     */
    fun updateSession(sessionId: String, updater: (SessionState) -> SessionState) {
        activeSessions.computeIfPresent(sessionId) { _, old -> updater(old) }
    }
    
    /**
     * End and unregister a session.
     */
    fun endSession(sessionId: String): SessionState? {
        return activeSessions.remove(sessionId)
    }
    
    /**
     * Get all active sessions (for recovery on service restart).
     */
    fun getActiveSessions(): Map<String, SessionState> = activeSessions.toMap()
    
    /**
     * Recover sessions from persistent storage.
     */
    suspend fun recoverSessions(sessions: List<SessionState>) {
        sessions.forEach { state ->
            activeSessions[state.sessionId] = state
            Log.i(TAG, "Recovered session: ${state.sessionId}, track=${state.trackTitle}")
        }
        updateMetrics { it.copy(sessionsRecovered = it.sessionsRecovered + sessions.size) }
    }
    
    /**
     * Force flush all pending events (call on service shutdown).
     */
    suspend fun flushAll() {
        Log.i(TAG, "Flushing all pending events...")

        // Close channel to stop accepting new events
        eventChannel.close()

        // Wait for batch processor to finish
        batchJob?.join()

        // Process any remaining offline queue
        processOfflineQueue()

        // Persist whatever could still not be saved so it survives process death
        persistOfflineQueue()

        Log.i(TAG, "Flush complete. Metrics: ${_metrics.value}")
    }
    
    /**
     * Get current tracking metrics.
     */
    fun getMetrics(): TrackingMetrics = _metrics.value
    
    /**
     * Reset metrics (for testing/debugging).
     */
    fun resetMetrics() {
        _metrics.value = TrackingMetrics()
    }
    
    private fun startBatchProcessor() {
        batchJob = scope.launch {
            val batch = mutableListOf<PendingEvent>()
            var lastFlushTime = System.currentTimeMillis()

            try {
                while (true) {
                    // Wait for the next event, but never longer than the batch
                    // timeout. The previous implementation only flushed when a NEW
                    // event arrived, so the last events of a quiet period could sit
                    // in memory indefinitely (and be lost on a process kill).
                    val remaining = BATCH_TIMEOUT_MS - (System.currentTimeMillis() - lastFlushTime)
                    val result = if (remaining > 0) {
                        withTimeoutOrNull(remaining) { eventChannel.receiveCatching() }
                    } else {
                        null
                    }

                    if (result == null) {
                        // Timeout with no new event — flush what we have
                        if (batch.isNotEmpty()) {
                            processBatch(batch.toList())
                            batch.clear()
                        }
                        lastFlushTime = System.currentTimeMillis()
                        continue
                    }

                    val event = result.getOrNull() ?: break // channel closed
                    batch.add(event)

                    val now = System.currentTimeMillis()
                    val shouldFlush = batch.size >= BATCH_SIZE ||
                            (now - lastFlushTime) >= BATCH_TIMEOUT_MS

                    if (shouldFlush) {
                        processBatch(batch.toList())
                        batch.clear()
                        lastFlushTime = now
                    }
                }

                // Process remaining events when channel closes
                if (batch.isNotEmpty()) {
                    processBatch(batch.toList())
                }
            } catch (e: CancellationException) {
                // Process remaining on cancellation
                if (batch.isNotEmpty()) {
                    processBatch(batch.toList())
                }
                throw e
            }
        }
    }
    
    private suspend fun processBatch(batch: List<PendingEvent>) {
        if (batch.isEmpty()) return
        
        processingMutex.withLock {
            isProcessing.value = true
            try {
                // Events can spend up to BATCH_TIMEOUT_MS in memory. Re-read the current
                // Room rule before writing so a newly added NON_MUSIC mark takes effect.
                val candidates = batch.filter { shouldPersistEvent(it.event) }
                if (candidates.isEmpty()) {
                    updateMetrics { it.copy(batchesProcessed = it.batchesProcessed + 1) }
                    Log.d(TAG, "Batch contained no events allowed by current rules")
                    return@withLock
                }

                val ids = try {
                    listeningRepository.insertAll(candidates.map { it.event })
                } catch (e: Exception) {
                    Log.e(TAG, "Batch save failed, moving valid events to offline queue", e)
                    for (pending in candidates) {
                        if (shouldPersistEvent(pending.event)) handleFailedEvent(pending)
                    }
                    updateMetrics { it.copy(batchErrors = it.batchErrors + 1) }
                    return@withLock
                }

                var successCount = 0
                candidates.forEachIndexed { index, pending ->
                    val id = ids.getOrNull(index) ?: -1L
                    if (id <= 0L) {
                        if (shouldPersistEvent(pending.event)) {
                            handleFailedEvent(pending)
                        }
                    } else if (shouldPersistEvent(pending.event)) {
                        successCount++
                    } else {
                        // Complements the pre-insert check. If NON_MUSIC was committed
                        // during insertAll(), remove the row that was just created.
                        listeningRepository.deleteById(id)
                        Log.d(TAG, "Removed event rejected after batch persistence: trackId=${pending.event.track_id}")
                    }
                }

                updateMetrics { 
                    it.copy(
                        eventsSaved = it.eventsSaved + successCount,
                        batchesProcessed = it.batchesProcessed + 1
                    ) 
                }
                Log.d(TAG, "Batch saved: $successCount/${candidates.size} valid events")
            } finally {
                isProcessing.value = false
            }
        }
    }
    
    private fun handleFailedEvent(pending: PendingEvent) {
        if (pending.retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Event exceeded max retries, discarding: trackId=${pending.event.track_id}")
            updateMetrics { it.copy(eventsDropped = it.eventsDropped + 1) }
            persistOfflineQueue()
            return
        }

        if (offlineQueue.size >= MAX_OFFLINE_QUEUE_SIZE) {
            // Remove oldest event to make room
            offlineQueue.entries.minByOrNull { it.value.queuedAt }?.key?.let {
                offlineQueue.remove(it)
                updateMetrics { m -> m.copy(eventsDropped = m.eventsDropped + 1) }
            }
        }

        offlineQueue[pending.key()] = pending.copy(retryCount = pending.retryCount + 1)
        updateMetrics { it.copy(eventsInOfflineQueue = offlineQueue.size) }
        persistOfflineQueue()
    }

    /** Mirror the in-memory offline queue to durable storage (best effort). */
    private fun persistOfflineQueue() {
        try {
            offlineEventStore?.save(offlineQueue.values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist offline queue", e)
        }
    }
    
    private fun startOfflineQueueProcessor() {
        scope.launch {
            var consecutiveEmptyChecks = 0
            while (isActive) {
                // Adaptive delay: check more frequently when we have items, less when empty
                val checkInterval = if (offlineQueue.isEmpty()) {
                    consecutiveEmptyChecks++
                    // Exponential backoff up to 5 minutes when queue is persistently empty
                    (60_000L * (1 shl consecutiveEmptyChecks.coerceAtMost(3))).coerceAtMost(300_000L)
                } else {
                    consecutiveEmptyChecks = 0
                    60_000L // Check every minute when we have items
                }
                
                delay(checkInterval)
                
                // Only process if there are items
                if (offlineQueue.isNotEmpty()) {
                    processOfflineQueue()
                }
            }
        }
    }
    
    private suspend fun processOfflineQueue() {
        if (offlineQueue.isEmpty()) return

        Log.d(TAG, "Processing offline queue: ${offlineQueue.size} events")

        // Crash-safe: remove each event from the queue (and durable storage) only
        // AFTER it has been successfully inserted. A durable retry may outlive a later
        // manual classification, so current persistence rules are re-checked as well.
        val toProcess = offlineQueue.values.toList()

        for (pending in toProcess) {
            try {
                val delay = calculateRetryDelay(pending.retryCount)
                delay(delay)

                if (!shouldPersistEvent(pending.event)) {
                    offlineQueue.remove(pending.key())
                    Log.d(TAG, "Discarded offline event rejected by current rules: trackId=${pending.event.track_id}")
                    continue
                }

                val id = listeningRepository.insert(pending.event)
                if (id > 0L) {
                    if (!shouldPersistEvent(pending.event)) {
                        listeningRepository.deleteById(id)
                        Log.d(TAG, "Removed offline event rejected after persistence: trackId=${pending.event.track_id}")
                    } else {
                        updateMetrics { it.copy(eventsSaved = it.eventsSaved + 1) }
                    }
                    offlineQueue.remove(pending.key())
                } else if (!shouldPersistEvent(pending.event)) {
                    offlineQueue.remove(pending.key())
                } else {
                    handleFailedEvent(pending)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline queue retry failed", e)
                if (!shouldPersistEvent(pending.event)) {
                    offlineQueue.remove(pending.key())
                } else {
                    handleFailedEvent(pending)
                }
            }
        }

        updateMetrics { it.copy(eventsInOfflineQueue = offlineQueue.size) }
        persistOfflineQueue()
    }

    /**
     * Ask the repository for the current persistence rule. Validation deliberately fails
     * open on infrastructure errors so a transient Room failure cannot silently lose music.
     */
    private suspend fun shouldPersistEvent(event: ListeningEvent): Boolean {
        return try {
            listeningRepository.shouldPersist(event)
        } catch (e: Exception) {
            Log.w(TAG, "Persistence rule lookup failed; keeping event", e)
            true
        }
    }
    
    private suspend fun <T> withRetry(
        maxRetries: Int,
        initialDelay: Long,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1}/$maxRetries failed", e)
                
                if (attempt < maxRetries - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
        
        return Result.failure(lastException ?: RuntimeException("Unknown error"))
    }
    
    private fun calculateRetryDelay(retryCount: Int): Long {
        return (INITIAL_RETRY_DELAY_MS * (1 shl retryCount.coerceAtMost(6)))
            .coerceAtMost(MAX_RETRY_DELAY_MS)
    }
    
    private fun generateEventHash(event: ListeningEvent): String {
        return "${event.track_id}_${event.timestamp / 1000}_${event.source}"
    }
    
    private fun cleanupOldDeduplicationEntries(now: Long) {
        val threshold = now - (DEDUP_WINDOW_MS * 2)
        recentEventHashes.entries.removeAll { it.value < threshold }
    }
    
    private fun updateMetrics(updater: (TrackingMetrics) -> TrackingMetrics) {
        _metrics.value = updater(_metrics.value)
    }
    
    fun shutdown() {
        scope.cancel()
    }
}

/**
 * Pending event wrapper with retry metadata.
 */
data class PendingEvent(
    val event: ListeningEvent,
    val sessionId: String,
    val queuedAt: Long,
    val retryCount: Int
) {
    /** Stable identity for the offline queue map and durable storage. */
    fun key(): String = "${event.track_id}_${event.timestamp}"
}

/**
 * Session state for recovery.
 */
data class SessionState(
    val sessionId: String,
    val packageName: String,
    val trackId: Long?,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String?,
    val startTimestamp: Long,
    val lastResumeTimestamp: Long,
    val totalPlayedMs: Long,
    val isPlaying: Boolean,
    val pauseCount: Int,
    val estimatedDurationMs: Long?
) {
    companion object {
        fun fromPlaybackSession(session: Any): SessionState {
            // This would be called with the actual PlaybackSession from MusicTrackingService
            throw NotImplementedError("Use MusicTrackingService.PlaybackSession directly")
        }
    }
}

/**
 * Tracking metrics for monitoring and debugging.
 */
data class TrackingMetrics(
    val eventsQueued: Int = 0,
    val eventsSaved: Int = 0,
    val eventsDropped: Int = 0,
    val eventsInOfflineQueue: Int = 0,
    val duplicatesSkipped: Int = 0,
    val batchesProcessed: Int = 0,
    val batchErrors: Int = 0,
    val sessionsRecovered: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        return "TrackingMetrics(queued=$eventsQueued, saved=$eventsSaved, dropped=$eventsDropped, " +
                "offline=$eventsInOfflineQueue, duplicates=$duplicatesSkipped, batches=$batchesProcessed, " +
                "errors=$batchErrors, recovered=$sessionsRecovered)"
    }
}
