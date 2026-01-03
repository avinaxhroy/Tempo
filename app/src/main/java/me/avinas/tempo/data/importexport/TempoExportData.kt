package me.avinas.tempo.data.importexport

import com.squareup.moshi.JsonClass
import me.avinas.tempo.data.local.entities.*

/**
 * Complete export data model for Tempo backup.
 * 
 * Includes all entity types and metadata for a complete backup/restore.
 * 
 * Version history:
 * - v1: Initial format
 * - v2: Added image bundling (deprecated)
 * - v3: Removed image bundling for lightweight exports
 * - v4: Smart image bundling (local only) + hotlink pre-caching
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
    
    // v4: Local images bundled in ZIP (bundledFilename -> originalFilePath)
    val localImageManifest: Map<String, String> = emptyMap(),
    
    // v4: Hotlinked URLs to pre-cache after restore
    val hotlinkedUrls: List<String> = emptyList(),
    
    // Deprecated: kept for backward compatibility with v2 exports
    @Deprecated("Replaced by localImageManifest in v4")
    val imageManifest: Map<String, String> = emptyMap()
) {
    companion object {
        const val CURRENT_VERSION = 4
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
