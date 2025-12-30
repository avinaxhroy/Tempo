package me.avinas.tempo.service

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Smart duration estimator for tracks when duration is not available from metadata.
 * 
 * Uses multiple strategies to estimate track duration:
 * 1. Learning from previous plays of the same track
 * 2. Learning from similar tracks (same artist/album)
 * 3. Genre-based averages
 * 4. Statistical fallbacks based on observed patterns
 */
class SmartDurationEstimator {
    
    companion object {
        private const val TAG = "DurationEstimator"
        
        // Default duration constants (in milliseconds)
        private const val DEFAULT_DURATION_MS = 210_000L // 3.5 minutes
        private const val MIN_REASONABLE_DURATION_MS = 30_000L // 30 seconds
        private const val MAX_REASONABLE_DURATION_MS = 900_000L // 15 minutes
        
        // Cache settings
        private const val TRACK_CACHE_SIZE = 500
        private const val ARTIST_CACHE_SIZE = 200
        
        // Minimum samples for statistical estimation
        private const val MIN_SAMPLES_FOR_AVERAGE = 3
    }
    
    // Track duration cache: trackKey -> DurationEstimate
    private val trackDurationCache = LruCache<String, DurationEstimate>(TRACK_CACHE_SIZE)
    
    // Artist average duration cache: normalizedArtist -> AverageStats
    private val artistAverageCache = LruCache<String, AverageStats>(ARTIST_CACHE_SIZE)
    
    // Global statistics
    private var globalStats = GlobalDurationStats()
    private val statsMutex = Mutex()
    
    /**
     * Get estimated duration for a track.
     */
    suspend fun estimateDuration(
        title: String,
        artist: String,
        album: String? = null,
        genre: String? = null
    ): DurationEstimate {
        val trackKey = generateTrackKey(title, artist)
        
        // Check track-specific cache
        trackDurationCache.get(trackKey)?.let { cached ->
            if (cached.isReliable) {
                Log.d(TAG, "Using cached duration for '$title': ${cached.durationMs}ms (confidence: ${cached.confidence})")
                return cached
            }
        }
        
        // Try artist average
        val normalizedArtist = artist.trim().lowercase()
        artistAverageCache.get(normalizedArtist)?.let { artistStats ->
            if (artistStats.sampleCount >= MIN_SAMPLES_FOR_AVERAGE) {
                val estimate = DurationEstimate(
                    durationMs = artistStats.averageDurationMs.toLong(),
                    confidence = calculateArtistConfidence(artistStats),
                    source = EstimationSource.ARTIST_AVERAGE
                )
                Log.d(TAG, "Using artist average for '$title' by '$artist': ${estimate.durationMs}ms")
                return estimate
            }
        }
        
        // Use genre-based or global average
        return statsMutex.withLock {
            val genreEstimate = genre?.let { getGenreBasedEstimate(it) }
            if (genreEstimate != null && genreEstimate.confidence >= 0.5f) {
                genreEstimate
            } else if (globalStats.sampleCount >= MIN_SAMPLES_FOR_AVERAGE) {
                DurationEstimate(
                    durationMs = globalStats.averageDurationMs.toLong(),
                    confidence = calculateGlobalConfidence(),
                    source = EstimationSource.GLOBAL_AVERAGE
                )
            } else {
                DurationEstimate(
                    durationMs = DEFAULT_DURATION_MS,
                    confidence = 0.2f,
                    source = EstimationSource.DEFAULT
                )
            }
        }
    }
    
    /**
     * Record an observed duration for a track (learning from actual plays).
     */
    suspend fun recordObservedDuration(
        title: String,
        artist: String,
        durationMs: Long,
        wasFullPlay: Boolean,
        album: String? = null,
        genre: String? = null
    ) {
        // Validate duration
        if (durationMs < MIN_REASONABLE_DURATION_MS || durationMs > MAX_REASONABLE_DURATION_MS) {
            Log.w(TAG, "Ignoring unreasonable duration: ${durationMs}ms for '$title'")
            return
        }
        
        // Only learn from full plays (more reliable)
        if (!wasFullPlay) {
            return
        }
        
        val trackKey = generateTrackKey(title, artist)
        val normalizedArtist = artist.trim().lowercase()
        
        // Update track cache
        val existingEstimate = trackDurationCache.get(trackKey)
        val newEstimate = if (existingEstimate != null) {
            // Weighted average with existing estimate
            val newDuration = ((existingEstimate.durationMs * existingEstimate.sampleCount) + durationMs) /
                    (existingEstimate.sampleCount + 1)
            existingEstimate.copy(
                durationMs = newDuration,
                confidence = minOf(1.0f, existingEstimate.confidence + 0.1f),
                source = EstimationSource.LEARNED,
                sampleCount = existingEstimate.sampleCount + 1
            )
        } else {
            DurationEstimate(
                durationMs = durationMs,
                confidence = 0.6f,
                source = EstimationSource.LEARNED,
                sampleCount = 1
            )
        }
        trackDurationCache.put(trackKey, newEstimate)
        
        // Update artist average
        val artistStats = artistAverageCache.get(normalizedArtist) ?: AverageStats()
        val newArtistStats = artistStats.addSample(durationMs)
        artistAverageCache.put(normalizedArtist, newArtistStats)
        
        // Update global stats
        statsMutex.withLock {
            globalStats = globalStats.addSample(durationMs)
            
            // Update genre stats if available
            if (genre != null) {
                updateGenreStats(genre, durationMs)
            }
        }
        
        Log.d(TAG, "Learned duration for '$title': ${durationMs}ms (samples: ${newEstimate.sampleCount})")
    }
    
    /**
     * Record a known duration from metadata (more reliable than observed).
     */
    fun recordKnownDuration(title: String, artist: String, durationMs: Long) {
        if (durationMs < MIN_REASONABLE_DURATION_MS || durationMs > MAX_REASONABLE_DURATION_MS) {
            return
        }
        
        val trackKey = generateTrackKey(title, artist)
        val estimate = DurationEstimate(
            durationMs = durationMs,
            confidence = 1.0f,
            source = EstimationSource.METADATA,
            sampleCount = 1
        )
        trackDurationCache.put(trackKey, estimate)
    }
    
    /**
     * Calculate completion percentage with intelligent estimation.
     */
    fun calculateCompletionPercent(
        playedMs: Long,
        estimatedDurationMs: Long?,
        isStillPlaying: Boolean
    ): Int {
        val duration = estimatedDurationMs ?: DEFAULT_DURATION_MS
        
        if (duration <= 0) {
            return estimateFallbackCompletion(playedMs)
        }
        
        val rawPercent = ((playedMs.toDouble() / duration) * 100).toInt()
        
        // Apply heuristics for edge cases
        return when {
            // If still playing and past 90%, cap at 90% until finished
            isStillPlaying && rawPercent > 90 -> 90
            // Cap at 100%
            rawPercent > 100 -> 100
            // Minimum 0%
            rawPercent < 0 -> 0
            else -> rawPercent
        }
    }
    
    /**
     * Get statistics about the estimator's learning.
     */
    fun getStats(): EstimatorStats {
        return EstimatorStats(
            tracksLearned = trackDurationCache.size(),
            artistsLearned = artistAverageCache.size(),
            globalSamples = globalStats.sampleCount,
            globalAverageMs = globalStats.averageDurationMs.toLong()
        )
    }
    
    /**
     * Clear all learned data (for testing/reset).
     */
    fun clear() {
        trackDurationCache.evictAll()
        artistAverageCache.evictAll()
        globalStats = GlobalDurationStats()
        genreStats.clear()
    }
    
    // =====================
    // Internal Helpers
    // =====================
    
    private fun generateTrackKey(title: String, artist: String): String {
        val normalizedTitle = title.trim().lowercase()
        val normalizedArtist = artist.trim().lowercase()
        return "$normalizedTitle|$normalizedArtist"
    }
    
    private fun calculateArtistConfidence(stats: AverageStats): Float {
        // Higher confidence with more samples and lower variance
        val sampleFactor = minOf(1.0f, stats.sampleCount / 10.0f)
        val varianceFactor = if (stats.variance > 0) {
            maxOf(0.3f, 1.0f - (stats.variance / stats.averageDurationMs).toFloat())
        } else 0.5f
        return sampleFactor * varianceFactor
    }
    
    private fun calculateGlobalConfidence(): Float {
        val sampleFactor = minOf(0.6f, globalStats.sampleCount / 50.0f)
        return sampleFactor
    }
    
    // Genre-based statistics
    private val genreStats = mutableMapOf<String, AverageStats>()
    
    private fun updateGenreStats(genre: String, durationMs: Long) {
        val normalizedGenre = genre.trim().lowercase()
        val existing = genreStats[normalizedGenre] ?: AverageStats()
        genreStats[normalizedGenre] = existing.addSample(durationMs)
    }
    
    private fun getGenreBasedEstimate(genre: String): DurationEstimate? {
        val normalizedGenre = genre.trim().lowercase()
        val stats = genreStats[normalizedGenre] ?: return null
        
        if (stats.sampleCount < MIN_SAMPLES_FOR_AVERAGE) return null
        
        return DurationEstimate(
            durationMs = stats.averageDurationMs.toLong(),
            confidence = minOf(0.7f, stats.sampleCount / 20.0f),
            source = EstimationSource.GENRE_AVERAGE
        )
    }
    
    private fun estimateFallbackCompletion(playedMs: Long): Int {
        return when {
            playedMs >= 240_000 -> 100 // 4+ minutes = full play
            playedMs >= 180_000 -> 90  // 3+ minutes = likely full
            playedMs >= 120_000 -> 70  // 2+ minutes = partial
            playedMs >= 60_000 -> 50   // 1+ minute = half
            playedMs >= 30_000 -> 25   // 30+ seconds = started
            else -> 10
        }
    }
}

/**
 * Duration estimate with confidence and source information.
 */
data class DurationEstimate(
    val durationMs: Long,
    val confidence: Float,
    val source: EstimationSource,
    val sampleCount: Int = 0
) {
    val isReliable: Boolean get() = confidence >= 0.7f
}

/**
 * Source of the duration estimate.
 */
enum class EstimationSource {
    METADATA,       // From actual track metadata
    LEARNED,        // Learned from previous plays
    ARTIST_AVERAGE, // Average for this artist
    GENRE_AVERAGE,  // Average for this genre
    GLOBAL_AVERAGE, // Global average across all tracks
    DEFAULT         // Static default value
}

/**
 * Running average statistics.
 */
data class AverageStats(
    val sampleCount: Int = 0,
    val averageDurationMs: Double = 0.0,
    val variance: Double = 0.0,
    private val sumSquares: Double = 0.0
) {
    fun addSample(durationMs: Long): AverageStats {
        val n = sampleCount + 1
        val delta = durationMs - averageDurationMs
        val newAverage = averageDurationMs + (delta / n)
        val delta2 = durationMs - newAverage
        val newSumSquares = sumSquares + delta * delta2
        val newVariance = if (n > 1) newSumSquares / (n - 1) else 0.0
        
        return AverageStats(
            sampleCount = n,
            averageDurationMs = newAverage,
            variance = newVariance,
            sumSquares = newSumSquares
        )
    }
}

/**
 * Global duration statistics.
 */
data class GlobalDurationStats(
    val sampleCount: Int = 0,
    val averageDurationMs: Double = 210_000.0, // Start with 3.5 min default
    private val sumSquares: Double = 0.0
) {
    fun addSample(durationMs: Long): GlobalDurationStats {
        val n = sampleCount + 1
        val delta = durationMs - averageDurationMs
        val newAverage = averageDurationMs + (delta / n)
        val delta2 = durationMs - newAverage
        
        return GlobalDurationStats(
            sampleCount = n,
            averageDurationMs = newAverage,
            sumSquares = sumSquares + delta * delta2
        )
    }
}

/**
 * Statistics about the estimator.
 */
data class EstimatorStats(
    val tracksLearned: Int,
    val artistsLearned: Int,
    val globalSamples: Int,
    val globalAverageMs: Long
)
