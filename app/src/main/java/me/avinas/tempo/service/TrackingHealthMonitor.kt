package me.avinas.tempo.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive monitoring and health tracking for the music tracking system.
 * 
 * Provides:
 * - Real-time health status
 * - Performance metrics
 * - Error tracking and alerting
 * - Diagnostics for debugging
 */
class TrackingHealthMonitor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "TrackingHealthMonitor"
        
        // Health thresholds
        private const val MAX_EVENT_QUEUE_SIZE = 100
        private const val MAX_ERROR_RATE_PERCENT = 5
        private const val MAX_SAVE_LATENCY_MS = 5000L
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L // 1 minute
        
        // Error window for rate calculation
        private const val ERROR_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    // Health state
    private val _healthStatus = MutableStateFlow(HealthStatus())
    val healthStatus: StateFlow<HealthStatus> = _healthStatus.asStateFlow()
    
    // Counters
    private val eventsProcessed = AtomicLong(0)
    private val eventsSaved = AtomicLong(0)
    private val eventsDropped = AtomicLong(0)
    private val errorCount = AtomicInteger(0)
    
    // Timing
    private val lastEventTime = AtomicLong(0)
    private val lastSaveTime = AtomicLong(0)
    private val startTime = System.currentTimeMillis()
    
    // Error tracking
    private val recentErrors = mutableListOf<ErrorRecord>()
    private val maxRecentErrors = 50
    
    // Performance metrics
    private val saveTimes = mutableListOf<Long>()
    private val maxSaveTimes = 100
    
    private var healthCheckJob: Job? = null
    
    init {
        startHealthChecks()
    }
    
    /**
     * Record that an event was processed.
     */
    fun recordEventProcessed() {
        eventsProcessed.incrementAndGet()
        lastEventTime.set(System.currentTimeMillis())
        updateHealthStatus()
    }
    
    /**
     * Record that an event was successfully saved.
     */
    fun recordEventSaved(saveTimeMs: Long) {
        eventsSaved.incrementAndGet()
        lastSaveTime.set(System.currentTimeMillis())
        
        synchronized(saveTimes) {
            saveTimes.add(saveTimeMs)
            if (saveTimes.size > maxSaveTimes) {
                saveTimes.removeAt(0)
            }
        }
        
        if (saveTimeMs > MAX_SAVE_LATENCY_MS) {
            Log.w(TAG, "Slow event save: ${saveTimeMs}ms")
        }
        
        updateHealthStatus()
    }
    
    /**
     * Record that an event was dropped.
     */
    fun recordEventDropped(reason: String) {
        eventsDropped.incrementAndGet()
        Log.w(TAG, "Event dropped: $reason")
        updateHealthStatus()
    }
    
    /**
     * Record an error.
     */
    fun recordError(error: Throwable, context: String) {
        errorCount.incrementAndGet()
        
        synchronized(recentErrors) {
            recentErrors.add(ErrorRecord(
                timestamp = System.currentTimeMillis(),
                error = error,
                context = context
            ))
            if (recentErrors.size > maxRecentErrors) {
                recentErrors.removeAt(0)
            }
        }
        
        Log.e(TAG, "Error recorded: $context", error)
        updateHealthStatus()
    }
    
    /**
     * Get current health status.
     */
    fun getHealthStatus(): HealthStatus = _healthStatus.value
    
    /**
     * Get detailed diagnostics.
     */
    fun getDiagnostics(): TrackingDiagnostics {
        val avgSaveTime = synchronized(saveTimes) {
            if (saveTimes.isEmpty()) 0L else saveTimes.average().toLong()
        }
        
        val recentErrorsCopy = synchronized(recentErrors) {
            recentErrors.toList()
        }
        
        val uptime = System.currentTimeMillis() - startTime
        
        return TrackingDiagnostics(
            eventsProcessed = eventsProcessed.get(),
            eventsSaved = eventsSaved.get(),
            eventsDropped = eventsDropped.get(),
            errorCount = errorCount.get(),
            averageSaveTimeMs = avgSaveTime,
            uptimeMs = uptime,
            lastEventTime = lastEventTime.get(),
            lastSaveTime = lastSaveTime.get(),
            recentErrors = recentErrorsCopy
        )
    }
    
    /**
     * Reset all counters (for testing).
     */
    fun reset() {
        eventsProcessed.set(0)
        eventsSaved.set(0)
        eventsDropped.set(0)
        errorCount.set(0)
        lastEventTime.set(0)
        lastSaveTime.set(0)
        
        synchronized(recentErrors) {
            recentErrors.clear()
        }
        synchronized(saveTimes) {
            saveTimes.clear()
        }
        
        updateHealthStatus()
    }
    
    /**
     * Shutdown the monitor.
     */
    fun shutdown() {
        healthCheckJob?.cancel()
        scope.cancel()
    }
    
    // =====================
    // Internal
    // =====================
    
    private fun startHealthChecks() {
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
    }
    
    private fun performHealthCheck() {
        val status = calculateHealthStatus()
        _healthStatus.value = status
        
        if (!status.isHealthy) {
            Log.w(TAG, "Health check failed: ${status.issues.joinToString(", ")}")
        }
    }
    
    private fun updateHealthStatus() {
        _healthStatus.value = calculateHealthStatus()
    }
    
    private fun calculateHealthStatus(): HealthStatus {
        val issues = mutableListOf<String>()
        
        // Check error rate
        val recentErrorCount = synchronized(recentErrors) {
            val cutoff = System.currentTimeMillis() - ERROR_WINDOW_MS
            recentErrors.count { it.timestamp > cutoff }
        }
        
        val recentEventsProcessed = eventsProcessed.get()
        val errorRate = if (recentEventsProcessed > 0) {
            (recentErrorCount.toDouble() / recentEventsProcessed) * 100
        } else 0.0
        
        if (errorRate > MAX_ERROR_RATE_PERCENT) {
            issues.add("High error rate: ${String.format(java.util.Locale.US, "%.1f", errorRate)}%")
        }
        
        // Check drop rate
        val totalEvents = eventsProcessed.get() + eventsDropped.get()
        val dropRate = if (totalEvents > 0) {
            (eventsDropped.get().toDouble() / totalEvents) * 100
        } else 0.0
        
        if (dropRate > MAX_ERROR_RATE_PERCENT) {
            issues.add("High drop rate: ${String.format(java.util.Locale.US, "%.1f", dropRate)}%")
        }
        
        // Check save latency
        val avgSaveTime = synchronized(saveTimes) {
            if (saveTimes.isEmpty()) 0L else saveTimes.average().toLong()
        }
        
        if (avgSaveTime > MAX_SAVE_LATENCY_MS) {
            issues.add("High save latency: ${avgSaveTime}ms")
        }
        
        // Check for stale data (no events in a while when service should be active)
        val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime.get()
        val timeSinceLastSave = System.currentTimeMillis() - lastSaveTime.get()
        
        return HealthStatus(
            isHealthy = issues.isEmpty(),
            issues = issues,
            eventsProcessed = eventsProcessed.get(),
            eventsSaved = eventsSaved.get(),
            eventsDropped = eventsDropped.get(),
            errorRate = errorRate,
            dropRate = dropRate,
            averageSaveTimeMs = avgSaveTime,
            lastUpdated = System.currentTimeMillis()
        )
    }
}

/**
 * Current health status of the tracking system.
 */
data class HealthStatus(
    val isHealthy: Boolean = true,
    val issues: List<String> = emptyList(),
    val eventsProcessed: Long = 0,
    val eventsSaved: Long = 0,
    val eventsDropped: Long = 0,
    val errorRate: Double = 0.0,
    val dropRate: Double = 0.0,
    val averageSaveTimeMs: Long = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        return if (isHealthy) {
            "Healthy: processed=$eventsProcessed, saved=$eventsSaved, avgLatency=${averageSaveTimeMs}ms"
        } else {
            "Unhealthy: ${issues.joinToString("; ")} (processed=$eventsProcessed, saved=$eventsSaved)"
        }
    }
}

/**
 * Detailed diagnostics for debugging.
 */
data class TrackingDiagnostics(
    val eventsProcessed: Long,
    val eventsSaved: Long,
    val eventsDropped: Long,
    val errorCount: Int,
    val averageSaveTimeMs: Long,
    val uptimeMs: Long,
    val lastEventTime: Long,
    val lastSaveTime: Long,
    val recentErrors: List<ErrorRecord>
) {
    fun toFormattedString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("=== Tracking Diagnostics ===")
            appendLine("Events: processed=$eventsProcessed, saved=$eventsSaved, dropped=$eventsDropped")
            appendLine("Errors: $errorCount")
            appendLine("Avg Save Time: ${averageSaveTimeMs}ms")
            appendLine("Uptime: ${uptimeMs / 1000}s")
            if (lastEventTime > 0) {
                appendLine("Last Event: ${sdf.format(Date(lastEventTime))}")
            }
            if (lastSaveTime > 0) {
                appendLine("Last Save: ${sdf.format(Date(lastSaveTime))}")
            }
            if (recentErrors.isNotEmpty()) {
                appendLine("Recent Errors:")
                recentErrors.takeLast(5).forEach { error ->
                    appendLine("  - ${sdf.format(Date(error.timestamp))}: ${error.context} - ${error.error.message}")
                }
            }
        }
    }
}

/**
 * Record of a tracking error.
 */
data class ErrorRecord(
    val timestamp: Long,
    val error: Throwable,
    val context: String
)
