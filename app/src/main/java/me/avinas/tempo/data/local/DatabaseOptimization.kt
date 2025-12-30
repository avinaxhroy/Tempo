package me.avinas.tempo.data.local

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Database optimization utilities for maintaining optimal performance.
 * 
 * Features:
 * - VACUUM to reclaim disk space and defragment
 * - ANALYZE to update query planner statistics
 * - Integrity check
 * - WAL checkpoint management
 * - Automatic cleanup of old data
 * - Query performance monitoring
 * - Health diagnostics
 */
@Singleton
class DatabaseOptimization @Inject constructor(
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "DatabaseOptimization"
        private const val VACUUM_THRESHOLD_BYTES = 10 * 1024 * 1024 // 10MB
        private const val VACUUM_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        // Data retention settings
        private const val DEFAULT_RETENTION_DAYS = 365 // 1 year
        private const val MIN_EVENTS_TO_KEEP = 1000 // Always keep at least this many events
        
        // Health check thresholds
        private const val SLOW_QUERY_THRESHOLD_MS = 500L
        private const val HIGH_FREE_SPACE_PERCENT = 20
        private const val LARGE_WAL_SIZE_BYTES = 5 * 1024 * 1024 // 5MB
    }
    
    @Volatile
    private var lastVacuumTime: Long = 0
    
    // Database health state
    private val _healthState = MutableStateFlow(DatabaseHealth())
    val healthState: StateFlow<DatabaseHealth> = _healthState.asStateFlow()
    
    // Query performance tracking
    private val queryTimes = mutableListOf<QueryMetric>()
    private val maxQueryMetrics = 100
    
    /**
     * Run ANALYZE to update SQLite statistics for better query planning.
     * This is lightweight and can be run more frequently.
     */
    suspend fun analyzeDatabase() = withContext(Dispatchers.IO) {
        try {
            database.openHelper.writableDatabase.execSQL("ANALYZE")
            Log.i(TAG, "Database ANALYZE completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze database", e)
        }
    }
    
    /**
     * Run VACUUM to reclaim disk space and defragment the database.
     * This is expensive and should be run infrequently (e.g., once per day).
     */
    suspend fun vacuumDatabase(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        // Skip if recently vacuumed (unless forced)
        if (!force && (now - lastVacuumTime) < VACUUM_COOLDOWN_MS) {
            Log.d(TAG, "Skipping VACUUM - cooldown not elapsed")
            return@withContext
        }
        
        try {
            // Checkpoint WAL first
            database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            
            // Run VACUUM
            database.openHelper.writableDatabase.execSQL("VACUUM")
            lastVacuumTime = now
            
            Log.i(TAG, "Database VACUUM completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vacuum database", e)
        }
    }
    
    /**
     * Run incremental VACUUM if supported (SQLite 3.25.0+).
     * This is less disruptive than full VACUUM.
     */
    suspend fun incrementalVacuum(pages: Int = 100) = withContext(Dispatchers.IO) {
        try {
            database.openHelper.writableDatabase.execSQL("PRAGMA incremental_vacuum($pages)")
            Log.d(TAG, "Incremental vacuum of $pages pages completed")
        } catch (e: Exception) {
            Log.w(TAG, "Incremental vacuum not supported or failed", e)
        }
    }
    
    /**
     * Checkpoint the WAL file to sync changes to the main database.
     */
    suspend fun checkpointWal() = withContext(Dispatchers.IO) {
        try {
            database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(PASSIVE)")
            Log.d(TAG, "WAL checkpoint completed")
        } catch (e: Exception) {
            Log.w(TAG, "WAL checkpoint failed", e)
        }
    }
    
    /**
     * Get database statistics.
     */
    suspend fun getDatabaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        
        var pageCount = 0L
        var pageSize = 0L
        var freePageCount = 0L
        var walPages = 0L
        
        try {
            db.query("PRAGMA page_count").use { cursor ->
                if (cursor.moveToFirst()) pageCount = cursor.getLong(0)
            }
            db.query("PRAGMA page_size").use { cursor ->
                if (cursor.moveToFirst()) pageSize = cursor.getLong(0)
            }
            db.query("PRAGMA freelist_count").use { cursor ->
                if (cursor.moveToFirst()) freePageCount = cursor.getLong(0)
            }
            db.query("PRAGMA wal_checkpoint").use { cursor ->
                if (cursor.moveToFirst()) walPages = cursor.getLong(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get database stats", e)
        }
        
        DatabaseStats(
            totalSize = pageCount * pageSize,
            usedSize = (pageCount - freePageCount) * pageSize,
            freeSpace = freePageCount * pageSize,
            walSize = walPages * pageSize,
            pageSize = pageSize.toInt()
        )
    }
    
    /**
     * Perform integrity check on the database.
     */
    suspend fun integrityCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    val isOk = result == "ok"
                    if (!isOk) {
                        Log.e(TAG, "Database integrity check failed: $result")
                    }
                    return@withContext isOk
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check failed", e)
            false
        }
    }
    
    /**
     * Optimize database during idle time.
     * Call this when the app is in background or during startup.
     */
    suspend fun optimizeIfNeeded() = withContext(Dispatchers.IO) {
        val stats = getDatabaseStats()
        
        // Log database size
        Log.d(TAG, "Database stats: size=${stats.totalSize / 1024}KB, " +
                "free=${stats.freeSpace / 1024}KB, wal=${stats.walSize / 1024}KB")
        
        // Always run ANALYZE (lightweight)
        analyzeDatabase()
        
        // Checkpoint WAL if it's getting large
        if (stats.walSize > 1024 * 1024) { // > 1MB
            checkpointWal()
        }
        
        // Run VACUUM if there's significant free space (>10% of database)
        val freePercent = if (stats.totalSize > 0) {
            (stats.freeSpace * 100) / stats.totalSize
        } else 0
        
        if (freePercent > 10 && stats.freeSpace > VACUUM_THRESHOLD_BYTES) {
            Log.i(TAG, "Running VACUUM due to ${freePercent}% free space")
            vacuumDatabase()
        }
        
        // Update health state
        updateHealthState(stats)
    }
    
    /**
     * Perform data cleanup to remove old events while preserving important data.
     * 
     * @param retentionDays Number of days of data to keep
     * @return Number of events deleted
     */
    suspend fun cleanupOldData(retentionDays: Int = DEFAULT_RETENTION_DAYS): Int = withContext(Dispatchers.IO) {
        try {
            val cutoffTimestamp = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            
            // Count total events
            val totalEvents = countEvents()
            
            // Don't delete if we'd go below minimum threshold
            val eventsOlderThanCutoff = countEventsOlderThan(cutoffTimestamp)
            val eventsAfterDelete = totalEvents - eventsOlderThanCutoff
            
            if (eventsAfterDelete < MIN_EVENTS_TO_KEEP) {
                Log.i(TAG, "Skipping cleanup: would leave only $eventsAfterDelete events (minimum: $MIN_EVENTS_TO_KEEP)")
                return@withContext 0
            }
            
            // Delete old events
            val deleted = deleteEventsOlderThan(cutoffTimestamp)
            Log.i(TAG, "Cleaned up $deleted old events (cutoff: ${formatDate(cutoffTimestamp)})")
            
            // Run analyze after cleanup
            analyzeDatabase()
            
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old data", e)
            0
        }
    }
    
    /**
     * Get comprehensive database diagnostics.
     */
    suspend fun getDiagnostics(): DatabaseDiagnostics = withContext(Dispatchers.IO) {
        val stats = getDatabaseStats()
        val integrityOk = integrityCheck()
        val tableStats = getTableStats()
        val indexStats = getIndexStats()
        
        DatabaseDiagnostics(
            stats = stats,
            integrityOk = integrityOk,
            tableStats = tableStats,
            indexStats = indexStats,
            queryMetrics = queryTimes.toList(),
            lastVacuumTime = lastVacuumTime,
            lastOptimizeTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Record a query execution time for monitoring.
     */
    fun recordQueryTime(queryName: String, durationMs: Long) {
        synchronized(queryTimes) {
            queryTimes.add(QueryMetric(queryName, durationMs, System.currentTimeMillis()))
            if (queryTimes.size > maxQueryMetrics) {
                queryTimes.removeAt(0)
            }
        }
        
        if (durationMs > SLOW_QUERY_THRESHOLD_MS) {
            Log.w(TAG, "Slow query detected: $queryName took ${durationMs}ms")
        }
    }
    
    /**
     * Get slow queries for debugging.
     */
    fun getSlowQueries(): List<QueryMetric> {
        return synchronized(queryTimes) {
            queryTimes.filter { it.durationMs > SLOW_QUERY_THRESHOLD_MS }
        }
    }
    
    /**
     * Perform full database maintenance.
     */
    suspend fun performFullMaintenance(): MaintenanceResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting full database maintenance...")
        val startTime = System.currentTimeMillis()
        
        val integrityOk = integrityCheck()
        if (!integrityOk) {
            Log.e(TAG, "Database integrity check failed!")
            return@withContext MaintenanceResult(
                success = false,
                message = "Integrity check failed",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        // Checkpoint WAL
        checkpointWal()
        
        // Analyze for query optimization
        analyzeDatabase()
        
        // Cleanup old data
        val deletedEvents = cleanupOldData()
        
        // Vacuum if needed
        val statsBeforeVacuum = getDatabaseStats()
        val freePercent = if (statsBeforeVacuum.totalSize > 0) {
            (statsBeforeVacuum.freeSpace * 100) / statsBeforeVacuum.totalSize
        } else 0
        
        if (freePercent > HIGH_FREE_SPACE_PERCENT) {
            vacuumDatabase(force = true)
        }
        
        val statsAfterMaintenance = getDatabaseStats()
        val spaceSaved = statsBeforeVacuum.totalSize - statsAfterMaintenance.totalSize
        
        val result = MaintenanceResult(
            success = true,
            message = "Maintenance completed",
            durationMs = System.currentTimeMillis() - startTime,
            eventsDeleted = deletedEvents,
            spaceSavedBytes = spaceSaved
        )
        
        Log.i(TAG, "Full maintenance completed: $result")
        result
    }
    
    // =====================
    // Private Helpers
    // =====================
    
    private suspend fun countEvents(): Int = withContext(Dispatchers.IO) {
        try {
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM listening_events").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count events", e)
            0
        }
    }
    
    private suspend fun countEventsOlderThan(timestamp: Long): Int = withContext(Dispatchers.IO) {
        try {
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM listening_events WHERE timestamp < ?",
                arrayOf(timestamp.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count old events", e)
            0
        }
    }
    
    private suspend fun deleteEventsOlderThan(timestamp: Long): Int = withContext(Dispatchers.IO) {
        try {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM listening_events WHERE timestamp < ?",
                arrayOf(timestamp)
            )
            // Get affected rows count
            database.openHelper.readableDatabase.query("SELECT changes()").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete old events", e)
            0
        }
    }
    
    private suspend fun getTableStats(): List<TableStats> = withContext(Dispatchers.IO) {
        val tables = listOf("tracks", "listening_events", "artists", "albums", "enriched_metadata", "track_artists")
        tables.mapNotNull { tableName ->
            try {
                val count = database.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM $tableName"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                TableStats(tableName, count)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get stats for table $tableName", e)
                null
            }
        }
    }
    
    private suspend fun getIndexStats(): List<IndexStats> = withContext(Dispatchers.IO) {
        try {
            val indices = mutableListOf<IndexStats>()
            database.openHelper.readableDatabase.query(
                "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    indices.add(IndexStats(
                        name = cursor.getString(0),
                        tableName = cursor.getString(1)
                    ))
                }
            }
            indices
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get index stats", e)
            emptyList()
        }
    }
    
    private fun updateHealthState(stats: DatabaseStats) {
        val warnings = mutableListOf<String>()
        
        val freePercent = if (stats.totalSize > 0) {
            (stats.freeSpace * 100) / stats.totalSize
        } else 0
        
        if (freePercent > HIGH_FREE_SPACE_PERCENT) {
            warnings.add("High free space (${freePercent}%) - consider running VACUUM")
        }
        
        if (stats.walSize > LARGE_WAL_SIZE_BYTES) {
            warnings.add("Large WAL file (${stats.walSize / 1024}KB) - consider checkpoint")
        }
        
        _healthState.value = DatabaseHealth(
            isHealthy = warnings.isEmpty(),
            warnings = warnings,
            stats = stats,
            lastCheckTime = System.currentTimeMillis()
        )
    }
    
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
}

/**
 * Database statistics.
 */
data class DatabaseStats(
    val totalSize: Long,
    val usedSize: Long,
    val freeSpace: Long,
    val walSize: Long,
    val pageSize: Int
)

/**
 * Database health state.
 */
data class DatabaseHealth(
    val isHealthy: Boolean = true,
    val warnings: List<String> = emptyList(),
    val stats: DatabaseStats? = null,
    val lastCheckTime: Long = 0
)

/**
 * Query performance metric.
 */
data class QueryMetric(
    val queryName: String,
    val durationMs: Long,
    val timestamp: Long
)

/**
 * Table statistics.
 */
data class TableStats(
    val tableName: String,
    val rowCount: Int
)

/**
 * Index statistics.
 */
data class IndexStats(
    val name: String,
    val tableName: String
)

/**
 * Comprehensive database diagnostics.
 */
data class DatabaseDiagnostics(
    val stats: DatabaseStats,
    val integrityOk: Boolean,
    val tableStats: List<TableStats>,
    val indexStats: List<IndexStats>,
    val queryMetrics: List<QueryMetric>,
    val lastVacuumTime: Long,
    val lastOptimizeTime: Long
)

/**
 * Result of database maintenance operation.
 */
data class MaintenanceResult(
    val success: Boolean,
    val message: String,
    val durationMs: Long,
    val eventsDeleted: Int = 0,
    val spaceSavedBytes: Long = 0
)
