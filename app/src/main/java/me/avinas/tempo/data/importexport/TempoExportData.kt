package me.avinas.tempo.data.importexport

import com.squareup.moshi.JsonClass
import me.avinas.tempo.data.local.entities.*

/**
 * Complete export data model for Tempo backup.
 * 
 * Includes all entity types and metadata for a complete backup/restore.
 * Images are NOT bundled - URLs are preserved and Coil fetches on-demand.
 * 
 * Version history:
 * - v1: Initial format
 * - v2: Added image bundling (deprecated)
 * - v3: Removed image bundling for lightweight exports
 */
@JsonClass(generateAdapter = true)
data class TempoExportData(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String,
    val schemaVersion: Int,
    
    // Core data
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val trackArtists: List<TrackArtist> = emptyList(),
    val listeningEvents: List<ListeningEvent> = emptyList(),
    val enrichedMetadata: List<EnrichedMetadata> = emptyList(),
    val userPreferences: UserPreferences? = null,
    
    // Deprecated: kept for backward compatibility when importing old exports
    @Deprecated("Image bundling removed in v3 - URLs preserved in entity data")
    val imageManifest: Map<String, String> = emptyMap()
) {
    companion object {
        const val CURRENT_VERSION = 3
        const val DATA_FILENAME = "data.json"
    }
}

/**
 * Conflict resolution strategy for imports.
 */
enum class ImportConflictStrategy {
    SKIP,    // Keep existing data, skip duplicates
    REPLACE  // Replace existing data with imported data
}

/**
 * Result of an import/export operation.
 */
sealed class ImportExportResult {
    data class Success(
        val tracksCount: Int = 0,
        val artistsCount: Int = 0,
        val albumsCount: Int = 0,
        val eventsCount: Int = 0,
        val imagesCount: Int = 0
    ) : ImportExportResult() {
        val totalRecords: Int get() = tracksCount + artistsCount + albumsCount + eventsCount
    }
    
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ImportExportResult()
}

/**
 * Progress update during import/export operations.
 */
data class ImportExportProgress(
    val phase: String,
    val current: Int,
    val total: Int,
    val isIndeterminate: Boolean = false
) {
    val percentage: Float get() = if (total > 0) current.toFloat() / total else 0f
}
