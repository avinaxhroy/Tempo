package me.avinas.tempo.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistent storage for active playback sessions.
 * 
 * This allows session recovery after:
 * - Service restarts
 * - App crashes
 * - Device reboots (if sessions weren't properly closed)
 * 
 * Uses SharedPreferences for fast access and JSON serialization for session data.
 */
class SessionPersistence(private val context: Context) {
    
    companion object {
        private const val TAG = "SessionPersistence"
        private const val PREFS_NAME = "tempo_session_persistence"
        private const val KEY_ACTIVE_SESSIONS = "active_sessions"
        private const val KEY_LAST_SAVE_TIMESTAMP = "last_save_timestamp"
        private const val KEY_SERVICE_LAST_ACTIVE = "service_last_active"
        
        // Stale session threshold (sessions older than this are discarded on recovery)
        private const val SESSION_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        // Auto-save interval
        private const val AUTO_SAVE_INTERVAL_MS = 30_000L // 30 seconds
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        
    private val sessionListType = Types.newParameterizedType(List::class.java, SerializableSession::class.java)
    private val jsonAdapter = moshi.adapter<List<SerializableSession>>(sessionListType)
    
    /**
     * Save all active sessions to persistent storage.
     */
    suspend fun saveSessions(sessions: Map<String, SessionState>) = withContext(Dispatchers.IO) {
        try {
            if (sessions.isEmpty()) {
                prefs.edit()
                    .remove(KEY_ACTIVE_SESSIONS)
                    .putLong(KEY_LAST_SAVE_TIMESTAMP, System.currentTimeMillis())
                    .apply()
                return@withContext
            }
            
            val sessionsList = sessions.values.map { it.toSerializable() }
            val json = jsonAdapter.toJson(sessionsList)
            
            prefs.edit()
                .putString(KEY_ACTIVE_SESSIONS, json)
                .putLong(KEY_LAST_SAVE_TIMESTAMP, System.currentTimeMillis())
                .apply()
            
            Log.d(TAG, "Saved ${sessions.size} sessions to persistent storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions", e)
        }
    }
    
    /**
     * Load previously active sessions from persistent storage.
     * Filters out stale sessions that are too old.
     */
    suspend fun loadSessions(): List<SessionState> = withContext(Dispatchers.IO) {
        try {
            val json = prefs.getString(KEY_ACTIVE_SESSIONS, null) ?: return@withContext emptyList()
            
            val serializedSessions: List<SerializableSession> = jsonAdapter.fromJson(json) ?: emptyList()
            
            val now = System.currentTimeMillis()
            val validSessions = serializedSessions
                .filter { session ->
                    // Filter out stale sessions
                    val age = now - session.startTimestamp
                    age < SESSION_STALE_THRESHOLD_MS
                }
                .map { it.toSessionState() }
            
            val discardedCount = serializedSessions.size - validSessions.size
            if (discardedCount > 0) {
                Log.i(TAG, "Discarded $discardedCount stale sessions")
            }
            
            Log.d(TAG, "Loaded ${validSessions.size} sessions from persistent storage")
            
            validSessions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }
    
    /**
     * Clear all persisted sessions.
     */
    fun clearSessions() {
        prefs.edit()
            .remove(KEY_ACTIVE_SESSIONS)
            .putLong(KEY_LAST_SAVE_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Cleared all persisted sessions")
    }
    
    /**
     * Record that the service is currently active.
     * Used to detect unclean shutdowns.
     */
    fun markServiceActive() {
        prefs.edit()
            .putLong(KEY_SERVICE_LAST_ACTIVE, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Record that the service is shutting down cleanly.
     */
    fun markServiceInactive() {
        prefs.edit()
            .putLong(KEY_SERVICE_LAST_ACTIVE, 0)
            .apply()
    }
    
    /**
     * Check if there was an unclean shutdown (sessions may need recovery).
     */
    fun wasUncleanShutdown(): Boolean {
        val lastActive = prefs.getLong(KEY_SERVICE_LAST_ACTIVE, 0)
        return lastActive > 0
    }
    
    /**
     * Get time since last session save.
     */
    fun getTimeSinceLastSave(): Long {
        val lastSave = prefs.getLong(KEY_LAST_SAVE_TIMESTAMP, 0)
        return System.currentTimeMillis() - lastSave
    }
    
    /**
     * Check if auto-save is needed based on time elapsed.
     */
    fun needsAutoSave(): Boolean {
        return getTimeSinceLastSave() > AUTO_SAVE_INTERVAL_MS
    }
}

/**
 * Serializable version of SessionState for JSON storage.
 */
data class SerializableSession(
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
    fun toSessionState(): SessionState = SessionState(
        sessionId = sessionId,
        packageName = packageName,
        trackId = trackId,
        trackTitle = trackTitle,
        trackArtist = trackArtist,
        trackAlbum = trackAlbum,
        startTimestamp = startTimestamp,
        lastResumeTimestamp = lastResumeTimestamp,
        totalPlayedMs = totalPlayedMs,
        isPlaying = isPlaying,
        pauseCount = pauseCount,
        estimatedDurationMs = estimatedDurationMs
    )
}

fun SessionState.toSerializable(): SerializableSession = SerializableSession(
    sessionId = sessionId,
    packageName = packageName,
    trackId = trackId,
    trackTitle = trackTitle,
    trackArtist = trackArtist,
    trackAlbum = trackAlbum,
    startTimestamp = startTimestamp,
    lastResumeTimestamp = lastResumeTimestamp,
    totalPlayedMs = totalPlayedMs,
    isPlaying = isPlaying,
    pauseCount = pauseCount,
    estimatedDurationMs = estimatedDurationMs
)
