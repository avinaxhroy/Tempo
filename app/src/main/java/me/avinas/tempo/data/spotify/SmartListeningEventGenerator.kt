package me.avinas.tempo.data.spotify

import me.avinas.tempo.data.local.entities.ListeningEvent
import java.util.Calendar
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Listening Event Generator - Smart Data Recovery Approach.
 * 
 * =====================================================
 * PHILOSOPHY: RECOVER REAL DATA WHERE POSSIBLE, SMART ESTIMATION OTHERWISE
 * =====================================================
 * 
 * We prioritize REAL timing data, but don't leave users with empty stats
 * when we have strong signals about their listening patterns.
 * 
 * DATA HIERARCHY (in order of accuracy):
 * 
 * 1. RECENTLY PLAYED (Gold Standard - Exact Timestamps ★★★★★)
 *    - EXACT played_at timestamps from Spotify
 *    - Direct 1:1 mapping to listening events
 *    
 * 2. TIME MACHINE - Liked Tracks (Very Accurate ★★★★)
 *    - EXACT date when user liked a song
 *    - Strong signal they were listening around that time
 *    - Generate events clustered around the liked date
 *    
 * 3. YEARLY PLAYLISTS - "Your Top Songs 20XX" (Good Accuracy ★★★)
 *    - We KNOW they listened to these tracks during that year
 *    - Distribute events throughout that specific year
 *    
 * 4. TOP TRACKS (Estimated ★★)
 *    - Spotify's API returns tracks RANKED by actual play frequency
 *    - We DON'T have timestamps, BUT we have STRONG SIGNALS:
 *      a) Ranking indicates relative importance (higher = more played)
 *      b) Time range (short_term = recent, long_term = all-time favorites)
 *    - Use smart estimation algorithm to distribute events
 * 
 * =====================================================
 * SMART ESTIMATION ALGORITHM (For Top Tracks Without Dates)
 * =====================================================
 * 
 * Since top tracks represent REAL listening patterns (just without dates):
 * 1. Use affinity ranking to weight event count (rank #1 > rank #50)
 * 2. Use time range context:
 *    - short_term tracks → bias toward recent (last 4 weeks)
 *    - medium_term tracks → spread over last 6 months
 *    - long_term tracks → spread over longer history
 * 3. Apply natural distribution patterns (not uniform random)
 * 4. Clearly mark events as "estimated" via source tag
 */
object SmartListeningEventGenerator {
    
    // =====================================================
    // Data Classes
    // =====================================================
    
    /**
     * Represents a track with its known timing data.
     */
    data class TrackWithAffinity(
        val trackId: Long,
        val durationMs: Long,
        val affinity: Float,  // 0.0 (low) to 1.0 (high) - used for play count weighting
        val addedTimestamp: Long? = null,  // REAL: When it was liked/added (Time Machine)
        val yearContext: Int? = null,  // REAL: For yearly playlist tracks (2023, 2024, etc.)
        val timeRangeHint: TimeRangeHint = TimeRangeHint.UNKNOWN,  // Hint about recency
        val affinityRank: Int? = null  // Position in Spotify's ranking (lower = higher affinity)
    ) {
        /**
         * Whether we have REAL timing data for this track.
         * Only tracks with real data should generate events.
         */
        val hasRealTimingData: Boolean
            get() = addedTimestamp != null || yearContext != null
            
        /**
         * Whether we have enough signal to estimate timing.
         * Top tracks with ranking have implicit timing signals.
         */
        val hasEstimableTimingData: Boolean
            get() = hasRealTimingData || (affinityRank != null && timeRangeHint != TimeRangeHint.UNKNOWN)
    }
    
    /**
     * Time range hint from Spotify's API.
     * Helps estimate when songs were listened to.
     */
    enum class TimeRangeHint {
        SHORT_TERM,   // ~4 weeks - very recent listening
        MEDIUM_TERM,  // ~6 months
        LONG_TERM,    // Several years - all-time favorites
        UNKNOWN       // No time range info
    }
    
    /**
     * Configuration for event generation.
     */
    data class GenerationConfig(
        val startTime: Long,  // Earliest possible event timestamp
        val endTime: Long,    // Latest possible event timestamp (usually now)
        val totalEventsTarget: Int,  // Approximate total events to generate
        val source: String = SpotifyTopItemsService.IMPORT_SOURCE,
        val generateForTopTracksWithoutDates: Boolean = true  // Enable smart estimation
    )
    
    // =====================================================
    // Main Generation Method
    // =====================================================
    
    /**
     * Generate listening events using smart data recovery.
     * 
     * Priority:
     * 1. Tracks with exact timestamps (Time Machine) → events around that date
     * 2. Tracks with year context (Yearly Playlists) → events throughout year
     * 3. Top tracks with ranking → smart estimation based on affinity + time range
     */
    fun generateEvents(
        tracks: List<TrackWithAffinity>,
        config: GenerationConfig
    ): List<ListeningEvent> {
        if (tracks.isEmpty()) return emptyList()
        
        // DEDUPLICATION: Ensure each track only appears once
        // If same trackId appears multiple times, keep the one with best data quality
        // Priority: addedTimestamp > yearContext > topTracksOnly
        val uniqueTracks = tracks
            .groupBy { it.trackId }
            .map { (_, trackList) ->
                // Pick the best version of this track
                trackList.maxByOrNull { track ->
                    when {
                        track.addedTimestamp != null -> 3  // Highest priority
                        track.yearContext != null -> 2     // Medium priority
                        else -> 1                          // Lowest priority
                    }
                } ?: trackList.first()
            }
        
        val allEvents = mutableListOf<ListeningEvent>()
        
        // Separate tracks by data quality (mutually exclusive categories)
        val timeMachineTracks = uniqueTracks.filter { it.addedTimestamp != null }
        val yearlyTracks = uniqueTracks.filter { it.yearContext != null && it.addedTimestamp == null }
        val topTracksOnly = uniqueTracks.filter { !it.hasRealTimingData }
        
        // Log what we're doing
        android.util.Log.i("SmartEventGen", """
            Event generation (Smart Recovery Mode):
            - Input tracks: ${tracks.size}, after dedup: ${uniqueTracks.size}
            - Time Machine tracks (exact dates): ${timeMachineTracks.size} → events created
            - Yearly playlist tracks (year context): ${yearlyTracks.size} → events created
            - Top tracks for estimation: ${topTracksOnly.size} → ${if (config.generateForTopTracksWithoutDates) "smart estimation" else "skipped"}
        """.trimIndent())
        
        // 1. Generate events for Time Machine tracks (around their liked date)
        timeMachineTracks.forEach { track ->
            val events = generateTimeMachineEvents(track, config)
            allEvents.addAll(events)
        }
        
        // 2. Generate events for yearly playlist tracks (spread through that year)
        yearlyTracks.groupBy { it.yearContext }.forEach { (year, yearTracks) ->
            year?.let {
                val events = generateYearlyEvents(yearTracks, it, config)
                allEvents.addAll(events)
            }
        }
        
        // 3. Top tracks without dates - SMART ESTIMATION if enabled
        // These tracks ARE real listening data - Spotify's ranking proves they listened
        // We just don't know exactly WHEN. Use smart estimation to distribute.
        if (config.generateForTopTracksWithoutDates && topTracksOnly.isNotEmpty()) {
            val events = generateEstimatedEvents(topTracksOnly, config)
            allEvents.addAll(events)
        }
        
        // Sort by timestamp
        return allEvents.sortedBy { it.timestamp }
    }
    
    // =====================================================
    // Time Machine Events (Liked Tracks with Exact Dates)
    // =====================================================
    
    /**
     * Generate events around when the track was liked.
     * 
     * LOGIC: If they liked it on June 12, they were definitely listening around then.
     * We generate a small cluster of plays around that date.
     */
    private fun generateTimeMachineEvents(
        track: TrackWithAffinity,
        config: GenerationConfig
    ): List<ListeningEvent> {
        val likedAt = track.addedTimestamp ?: return emptyList()
        val events = mutableListOf<ListeningEvent>()
        
        // Play count based on affinity (liked songs are favorites)
        // Reduced for performance - fewer but meaningful events
        val playCount = when {
            track.affinity > 0.8f -> 5
            track.affinity > 0.6f -> 3
            track.affinity > 0.4f -> 2
            else -> 1
        }
        
        val sessionId = "tm-${track.trackId}-${UUID.randomUUID().toString().take(8)}"
        
        // Distribution around the liked date:
        // - 1 play on the day they liked it (we KNOW they listened then)
        // - Remaining plays within 1-14 days after (honeymoon period)
        
        for (i in 0 until playCount) {
            val timestamp = if (i == 0) {
                // First play: on the day they liked it
                likedAt
            } else {
                // Subsequent plays: within 2 weeks after liking
                val daysAfter = Random.nextInt(1, 15)
                likedAt + (daysAfter * 24 * 60 * 60 * 1000L)
            }
            
            // Only add if within valid range
            if (timestamp >= config.startTime && timestamp <= config.endTime) {
                events.add(createEvent(track, timestamp, sessionId, config))
            }
        }
        
        return events
    }
    
    // =====================================================
    // Yearly Playlist Events (Your Top Songs 20XX)
    // =====================================================
    
    /**
     * Generate events distributed throughout a specific year.
     * 
     * LOGIC: If it's in "Your Top Songs 2023", they listened during 2023.
     * We distribute plays throughout that year randomly.
     */
    private fun generateYearlyEvents(
        tracks: List<TrackWithAffinity>,
        year: Int,
        config: GenerationConfig
    ): List<ListeningEvent> {
        val events = mutableListOf<ListeningEvent>()
        
        // Year boundaries
        val yearStart = Calendar.getInstance().apply {
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val yearEnd = Calendar.getInstance().apply {
            set(year, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        
        // Ensure within config bounds
        val effectiveStart = max(yearStart, config.startTime)
        val effectiveEnd = minOf(yearEnd, config.endTime)
        
        if (effectiveStart >= effectiveEnd) return events
        
        val yearSessionId = "yr-$year-${UUID.randomUUID().toString().take(8)}"
        
        tracks.forEach { track ->
            // Play count based on affinity (top songs get more plays)
            // Reduced for performance - fewer but meaningful events
            val playCount = when {
                track.affinity > 0.8f -> 6
                track.affinity > 0.6f -> 4
                track.affinity > 0.4f -> 3
                else -> 2
            }
            
            for (i in 0 until playCount) {
                // Simple random distribution throughout the year
                val timestamp = effectiveStart + (Random.nextDouble() * (effectiveEnd - effectiveStart)).toLong()
                
                if (timestamp >= config.startTime && timestamp <= config.endTime) {
                    events.add(createEvent(track, timestamp, yearSessionId, config))
                }
            }
        }
        
        return events
    }
    
    // =====================================================
    // Smart Estimation Events (Top Tracks Without Dates)
    // =====================================================
    
    /**
     * Generate events for top tracks using smart estimation.
     * 
     * RATIONALE: Spotify's top tracks API proves the user ACTUALLY listened to these tracks.
     * The ranking reflects REAL listening patterns. We don't know the exact dates,
     * but we have strong signals:
     * 
     * 1. RANKING: Higher ranked = more plays (Spotify calculated this from real data)
     * 2. TIME RANGE: short_term = recent, long_term = over time
     * 3. AFFINITY: Combines ranking with time range for play distribution
     * 
     * ALGORITHM:
     * - Higher affinity → more events
     * - short_term → cluster in recent weeks
     * - medium_term → spread over recent months
     * - long_term → spread over longer period (but still realistic)
     * - Apply natural decay (more recent = higher probability)
     */
    private fun generateEstimatedEvents(
        tracks: List<TrackWithAffinity>,
        config: GenerationConfig
    ): List<ListeningEvent> {
        val events = mutableListOf<ListeningEvent>()
        val now = config.endTime
        
        // Time windows based on Spotify's time ranges
        val FOUR_WEEKS_MS = 28L * 24 * 60 * 60 * 1000
        val SIX_MONTHS_MS = 180L * 24 * 60 * 60 * 1000
        val TWO_YEARS_MS = 730L * 24 * 60 * 60 * 1000
        
        // Group by time range for efficient processing
        val shortTermTracks = tracks.filter { it.timeRangeHint == TimeRangeHint.SHORT_TERM }
        val mediumTermTracks = tracks.filter { it.timeRangeHint == TimeRangeHint.MEDIUM_TERM }
        val longTermTracks = tracks.filter { it.timeRangeHint == TimeRangeHint.LONG_TERM }
        val unknownTracks = tracks.filter { it.timeRangeHint == TimeRangeHint.UNKNOWN }
        
        android.util.Log.i("SmartEventGen", """
            Smart estimation breakdown:
            - Short term (recent 4 weeks): ${shortTermTracks.size}
            - Medium term (6 months): ${mediumTermTracks.size}
            - Long term (years): ${longTermTracks.size}
            - Unknown time range: ${unknownTracks.size}
        """.trimIndent())
        
        // Process each time range with appropriate distribution
        shortTermTracks.forEach { track ->
            val rangeStart = max(now - FOUR_WEEKS_MS, config.startTime)
            val rangeEnd = now
            events.addAll(generateEstimatedEventsForTrack(track, rangeStart, rangeEnd, config, recentBias = 0.7f))
        }
        
        mediumTermTracks.forEach { track ->
            val rangeStart = max(now - SIX_MONTHS_MS, config.startTime)
            val rangeEnd = now
            events.addAll(generateEstimatedEventsForTrack(track, rangeStart, rangeEnd, config, recentBias = 0.5f))
        }
        
        longTermTracks.forEach { track ->
            val rangeStart = max(now - TWO_YEARS_MS, config.startTime)
            val rangeEnd = now
            events.addAll(generateEstimatedEventsForTrack(track, rangeStart, rangeEnd, config, recentBias = 0.3f))
        }
        
        // Unknown time range: spread over reasonable period with mild recent bias
        unknownTracks.forEach { track ->
            val rangeStart = max(now - SIX_MONTHS_MS, config.startTime)
            val rangeEnd = now
            events.addAll(generateEstimatedEventsForTrack(track, rangeStart, rangeEnd, config, recentBias = 0.4f))
        }
        
        return events
    }
    
    /**
     * Generate events for a single track with smart time distribution.
     * 
     * @param track The track to generate events for
     * @param rangeStart Earliest possible timestamp for events
     * @param rangeEnd Latest possible timestamp for events
     * @param config Generation configuration
     * @param recentBias 0.0-1.0, higher = more events toward recent end
     */
    private fun generateEstimatedEventsForTrack(
        track: TrackWithAffinity,
        rangeStart: Long,
        rangeEnd: Long,
        config: GenerationConfig,
        recentBias: Float
    ): List<ListeningEvent> {
        val events = mutableListOf<ListeningEvent>()
        if (rangeStart >= rangeEnd) return events
        
        // Play count based on affinity AND rank
        // Higher affinity = more plays, but cap to avoid unrealistic numbers
        val basePlayCount = when {
            track.affinity > 0.9f -> 8   // Top tier favorite
            track.affinity > 0.7f -> 5   // Strong favorite
            track.affinity > 0.5f -> 3   // Regular listen
            track.affinity > 0.3f -> 2   // Occasional
            else -> 1                     // Rare
        }
        
        // Adjust based on rank if available (rank 1-10 get bonus plays)
        val rankBonus = when (track.affinityRank) {
            in 1..5 -> 3    // Top 5 tracks get extra plays
            in 6..10 -> 2   // Top 10 get some extra
            in 11..20 -> 1  // Top 20 get slight boost
            else -> 0
        }
        
        val playCount = min(basePlayCount + rankBonus, 12)  // Cap at 12 plays per track
        val sessionId = "est-${track.trackId}-${UUID.randomUUID().toString().take(8)}"
        
        for (i in 0 until playCount) {
            // Generate timestamp with bias toward more recent
            val timestamp = generateBiasedTimestamp(rangeStart, rangeEnd, recentBias)
            
            if (timestamp >= config.startTime && timestamp <= config.endTime) {
                events.add(createEstimatedEvent(track, timestamp, sessionId, config))
            }
        }
        
        return events
    }
    
    /**
     * Generate a timestamp with bias toward more recent times.
     * 
     * Uses exponential distribution to naturally bias toward recent.
     * recentBias 0.0 = uniform distribution
     * recentBias 1.0 = heavily biased toward now
     */
    private fun generateBiasedTimestamp(start: Long, end: Long, recentBias: Float): Long {
        val range = end - start
        if (range <= 0) return start
        
        // Use exponential decay to bias toward recent times
        // Higher bias = steeper decay = more recent events
        val lambda = 1.0 + (recentBias * 3.0)  // 1.0 to 4.0
        
        // Inverse transform sampling for exponential distribution
        val u = Random.nextDouble()
        val normalized = 1.0 - Math.pow(u, 1.0 / lambda)
        
        return start + (normalized * range).toLong()
    }
    
    /**
     * Create an event marked as estimated (different source tag).
     */
    private fun createEstimatedEvent(
        track: TrackWithAffinity,
        timestamp: Long,
        sessionId: String,
        config: GenerationConfig
    ): ListeningEvent {
        // Slightly lower completion for estimated events (more conservative)
        val completion = 80 + Random.nextInt(-5, 16)  // 75-95%
        val playDuration = (track.durationMs * completion / 100)
        
        // Use different source to mark as estimated (for transparency)
        val estimatedSource = "${config.source}.estimated"
        
        return ListeningEvent(
            track_id = track.trackId,
            timestamp = timestamp,
            playDuration = playDuration,
            completionPercentage = completion,
            source = estimatedSource,  // Marked as estimated
            wasSkipped = false,
            isReplay = false,
            estimatedDurationMs = track.durationMs,
            pauseCount = 0,
            sessionId = sessionId,
            endTimestamp = timestamp + playDuration
        )
    }

    // =====================================================
    // Event Creation (Simple, No Fake Patterns)
    // =====================================================
    
    /**
     * Create a simple ListeningEvent without fake patterns.
     */
    private fun createEvent(
        track: TrackWithAffinity,
        timestamp: Long,
        sessionId: String,
        config: GenerationConfig
    ): ListeningEvent {
        // Simple completion: assume full play for liked songs
        val completion = 85 + Random.nextInt(-5, 11)  // 80-95%
        val playDuration = (track.durationMs * completion / 100)
        
        return ListeningEvent(
            track_id = track.trackId,
            timestamp = timestamp,
            playDuration = playDuration,
            completionPercentage = completion,
            source = config.source,
            wasSkipped = false,
            isReplay = false,
            estimatedDurationMs = track.durationMs,
            pauseCount = 0,
            sessionId = sessionId,
            endTimestamp = timestamp + playDuration
        )
    }
}
