package me.avinas.tempo.data.importexport

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages import and export of all Tempo data.
 * 
 * Export creates a ZIP file containing:
 * - data.json: All entities serialized as JSON (image URLs preserved, not bundled)
 *
 * Import reads the ZIP, remaps IDs, and restores data.
 * Images are fetched on-demand by Coil from preserved URLs.
 */
@Singleton
class ImportExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "ImportExportManager"
    }
    
    private val moshi = buildImportExportMoshi()
    
    private val _progress = MutableStateFlow<ImportExportProgress?>(null)
    val progress: StateFlow<ImportExportProgress?> = _progress.asStateFlow()
    
    /**
     * Export all data to a ZIP file at the given URI.
     * 
     * Images are NOT bundled - URLs are preserved in the JSON data,
     * and Coil will fetch/cache images on-demand when displayed.
     * This keeps exports small (~1-5MB instead of ~65MB).
     */
    suspend fun exportData(uri: Uri): ImportExportResult = withContext(Dispatchers.IO) {
        try {
            _progress.value = ImportExportProgress("Preparing export...", 0, 100, true)
            
            // Collect all data
            val tracks = database.trackDao().getAllSync()
            val artists = database.artistDao().getAllArtistsSync()
            val albums = database.albumDao().getAllSync()
            val trackArtists = database.trackArtistDao().getAllSync()
            val listeningEvents = database.listeningEventDao().getAllEventsSync()
            val enrichedMetadata = database.enrichedMetadataDao().getAllSync()
            val userPrefs = database.userPreferencesDao().getSync()
            
            _progress.value = ImportExportProgress("Collected data", 30, 100)
            
            // Create export data (URLs preserved, no image bundling needed)
            val exportData = TempoExportData(
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 14, // Current Room schema version
                tracks = tracks,
                artists = artists,
                albums = albums,
                trackArtists = trackArtists,
                listeningEvents = listeningEvents,
                enrichedMetadata = enrichedMetadata,
                userPreferences = userPrefs
            )
            
            // Serialize to JSON
            val adapter = moshi.adapter(TempoExportData::class.java)
            val json = adapter.toJson(exportData)
            
            _progress.value = ImportExportProgress("Writing export file...", 70, 100)
            
            // Write ZIP file (just data.json, no images folder)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    zipOut.putNextEntry(ZipEntry(TempoExportData.DATA_FILENAME))
                    zipOut.write(json.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
            }
            
            _progress.value = ImportExportProgress("Export complete!", 100, 100)
            
            ImportExportResult.Success(
                tracksCount = tracks.size,
                artistsCount = artists.size,
                albumsCount = albums.size,
                eventsCount = listeningEvents.size,
                imagesCount = 0 // No images bundled - URLs preserved in data
            )
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            ImportExportResult.Error("Export failed: ${e.message}", e)
        } finally {
            delay(1000) // Let user see completion
            _progress.value = null
        }
    }
    
    /**
     * Import data from a ZIP file at the given URI.
     * 
     * Image URLs are preserved in the imported data.
     * Coil will fetch and cache images on-demand when displayed.
     */
    suspend fun importData(
        uri: Uri,
        conflictStrategy: ImportConflictStrategy
    ): ImportExportResult = withContext(Dispatchers.IO) {
        try {
            _progress.value = ImportExportProgress("Reading export file...", 0, 100, true)
            
            // Read ZIP file - only extract data.json
            var exportData: TempoExportData? = null
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == TempoExportData.DATA_FILENAME) {
                            val json = zipIn.readBytes().toString(Charsets.UTF_8)
                            val adapter = moshi.adapter(TempoExportData::class.java)
                            exportData = adapter.fromJson(json)
                            break // Found data.json, no need to continue
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
            
            val data = exportData ?: return@withContext ImportExportResult.Error("Invalid export file: no data found")
            
            // Validate version
            if (data.version > TempoExportData.CURRENT_VERSION) {
                return@withContext ImportExportResult.Error(
                    "Export file is from a newer version of Tempo. Please update the app."
                )
            }
            
            _progress.value = ImportExportProgress("Importing data...", 10, 100)
            
            // ID mappings for remapping foreign keys
            val artistIdMap = mutableMapOf<Long, Long>()
            val trackIdMap = mutableMapOf<Long, Long>()
            val albumIdMap = mutableMapOf<Long, Long>()
            
            // Import Artists first (no dependencies)
            _progress.value = ImportExportProgress("Importing artists...", 20, 100)
            var importedArtists = 0
            
            for (artist in data.artists) {
                val existingArtist = database.artistDao().getArtistByNormalizedName(artist.normalizedName)
                
                if (existingArtist != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.artistDao().update(artist.copy(id = existingArtist.id))
                    }
                    artistIdMap[artist.id] = existingArtist.id
                } else {
                    val newId = database.artistDao().insert(artist.copy(id = 0))
                    if (newId > 0) {
                        artistIdMap[artist.id] = newId
                        importedArtists++
                    }
                }
            }
            
            // Import Albums (depends on Artists)
            _progress.value = ImportExportProgress("Importing albums...", 35, 100)
            var importedAlbums = 0
            
            for (album in data.albums) {
                val newArtistId = artistIdMap[album.artistId] ?: continue
                val existingAlbum = database.albumDao().getAlbumByTitleAndArtist(
                    album.title,
                    data.artists.find { it.id == album.artistId }?.name ?: continue
                )
                
                if (existingAlbum != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.albumDao().update(album.copy(id = existingAlbum.id, artistId = newArtistId))
                    }
                    albumIdMap[album.id] = existingAlbum.id
                } else {
                    val newId = database.albumDao().insert(album.copy(id = 0, artistId = newArtistId))
                    if (newId > 0) {
                        albumIdMap[album.id] = newId
                        importedAlbums++
                    }
                }
            }
            
            // Import Tracks (depends on Artists)
            _progress.value = ImportExportProgress("Importing tracks...", 50, 100)
            var importedTracks = 0
            
            for (track in data.tracks) {
                val newPrimaryArtistId = track.primaryArtistId?.let { artistIdMap[it] }
                
                // Check for existing track
                val existingTrack = track.spotifyId?.let { database.trackDao().findBySpotifyId(it) }
                    ?: track.musicbrainzId?.let { database.trackDao().findByMusicBrainzId(it) }
                    ?: database.trackDao().findByTitleAndArtist(track.title, track.artist)
                
                if (existingTrack != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.trackDao().update(track.copy(
                            id = existingTrack.id,
                            primaryArtistId = newPrimaryArtistId
                        ))
                    }
                    trackIdMap[track.id] = existingTrack.id
                } else {
                    val newId = database.trackDao().insert(track.copy(
                        id = 0,
                        primaryArtistId = newPrimaryArtistId
                    ))
                    if (newId > 0) {
                        trackIdMap[track.id] = newId
                        importedTracks++
                    }
                }
            }
            
            // Import TrackArtists (junction table, depends on Tracks and Artists)
            _progress.value = ImportExportProgress("Importing track-artist links...", 65, 100)
            
            val remappedTrackArtists = data.trackArtists.mapNotNull { ta ->
                val newTrackId = trackIdMap[ta.trackId] ?: return@mapNotNull null
                val newArtistId = artistIdMap[ta.artistId] ?: return@mapNotNull null
                ta.copy(trackId = newTrackId, artistId = newArtistId)
            }
            database.trackArtistDao().insertAllBatched(remappedTrackArtists)
            
            // Import ListeningEvents (depends on Tracks)
            _progress.value = ImportExportProgress("Importing listening history...", 75, 100)
            var importedEvents = 0
            
            val remappedEvents = data.listeningEvents.mapNotNull { event ->
                val newTrackId = trackIdMap[event.track_id] ?: return@mapNotNull null
                event.copy(id = 0, track_id = newTrackId)
            }
            
            // Batch insert events
            val insertedEventIds = database.listeningEventDao().insertAllBatched(remappedEvents)
            importedEvents = insertedEventIds.count { it > 0 }
            
            // Import EnrichedMetadata (depends on Tracks)
            _progress.value = ImportExportProgress("Importing enriched metadata...", 90, 100)
            
            for (meta in data.enrichedMetadata) {
                val newTrackId = trackIdMap[meta.trackId] ?: continue
                val existingMeta = database.enrichedMetadataDao().forTrackSync(newTrackId)
                
                if (existingMeta == null) {
                    database.enrichedMetadataDao().upsert(meta.copy(id = 0, trackId = newTrackId))
                } else if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                    database.enrichedMetadataDao().update(meta.copy(id = existingMeta.id, trackId = newTrackId))
                }
            }
            
            // Import UserPreferences
            _progress.value = ImportExportProgress("Importing preferences...", 95, 100)
            data.userPreferences?.let { prefs ->
                database.userPreferencesDao().upsert(prefs)
            }
            
            _progress.value = ImportExportProgress("Import complete!", 100, 100)
            
            ImportExportResult.Success(
                tracksCount = importedTracks,
                artistsCount = importedArtists,
                albumsCount = importedAlbums,
                eventsCount = importedEvents,
                imagesCount = 0 // Images not bundled - URLs preserved
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportExportResult.Error("Import failed: ${e.message}", e)
        } finally {
            delay(1000) // Let user see completion
            _progress.value = null
        }
    }
}
