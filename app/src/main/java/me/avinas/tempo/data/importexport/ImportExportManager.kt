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
import me.avinas.tempo.worker.PostRestoreCacheWorker
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
 * - data.json: All entities serialized as JSON
 * - images/: Optional folder containing local album art (file:// URLs only)
 *
 * Import reads the ZIP, remaps IDs, restores local images, and restores data.
 * Hotlinked images are pre-cached after restore for better UX.
 */
@Singleton
class ImportExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "ImportExportManager"
        private const val ALBUM_ART_DIR = "album_art"
        private const val IMAGES_DIR = "images/"
    }
    
    private val moshi = buildImportExportMoshi()
    
    private val _progress = MutableStateFlow<ImportExportProgress?>(null)
    val progress: StateFlow<ImportExportProgress?> = _progress.asStateFlow()
    
    /**
     * Export all data to a ZIP file at the given URI.
     * 
     * @param uri The URI to write the ZIP file to
     * @param includeLocalImages If true, bundle local album art files in the ZIP
     */
    suspend fun exportData(
        uri: Uri, 
        includeLocalImages: Boolean = true
    ): ImportExportResult = withContext(Dispatchers.IO) {
        try {
            _progress.value = ImportExportProgress("Collecting data...", 0, 100, true)
            
            // Collect all data
            val tracks = database.trackDao().getAllSync()
            val artists = database.artistDao().getAllArtistsSync()
            val albums = database.albumDao().getAllSync()
            val trackArtists = database.trackArtistDao().getAllSync()
            val listeningEvents = database.listeningEventDao().getAllEventsSync()
            val enrichedMetadata = database.enrichedMetadataDao().getAllSync()
            val userPrefs = database.userPreferencesDao().getSync()
            
            _progress.value = ImportExportProgress("Analyzing images...", 20, 100)
            
            // Classify image URLs
            val (localPaths, hotlinkUrls) = collectImageUrls(tracks, artists, albums, enrichedMetadata)
            
            Log.i(TAG, "Found ${localPaths.size} local images, ${hotlinkUrls.size} hotlinks")
            
            // Build local image manifest (only if including local images)
            val localImageManifest = mutableMapOf<String, String>()
            
            _progress.value = ImportExportProgress("Creating backup...", 40, 100)
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    
                    // Bundle local images if enabled
                    var bundledCount = 0
                    if (includeLocalImages && localPaths.isNotEmpty()) {
                        _progress.value = ImportExportProgress("Bundling ${localPaths.size} images...", 50, 100)
                        
                        localPaths.forEachIndexed { index, filePath ->
                            try {
                                val originalPath = filePath.removePrefix("file://")
                                val file = File(originalPath)
                                if (file.exists()) {
                                    val bundledName = "img_${index}_${file.name}"
                                    zipOut.putNextEntry(ZipEntry("$IMAGES_DIR$bundledName"))
                                    file.inputStream().use { it.copyTo(zipOut) }
                                    zipOut.closeEntry()
                                    localImageManifest[bundledName] = filePath
                                    bundledCount++
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to bundle image: $filePath", e)
                            }
                        }
                    }
                    
                    _progress.value = ImportExportProgress("Writing data...", 80, 100)
                    
                    // Create export data using current database schema version
                    val exportData = TempoExportData(
                        appVersion = BuildConfig.VERSION_NAME,
                        schemaVersion = 18, // Keep in sync with AppDatabase version
                        tracks = tracks,
                        artists = artists,
                        albums = albums,
                        trackArtists = trackArtists,
                        listeningEvents = listeningEvents,
                        enrichedMetadata = enrichedMetadata,
                        userPreferences = userPrefs,
                        localImageManifest = localImageManifest,
                        hotlinkedUrls = hotlinkUrls
                    )
                    
                    // Serialize and write data.json
                    val adapter = moshi.adapter(TempoExportData::class.java)
                    val json = adapter.toJson(exportData)
                    
                    zipOut.putNextEntry(ZipEntry(TempoExportData.DATA_FILENAME))
                    zipOut.write(json.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                    
                    _progress.value = ImportExportProgress("Export complete!", 100, 100)
                    
                    ImportExportResult.Success(
                        tracksCount = tracks.size,
                        artistsCount = artists.size,
                        albumsCount = albums.size,
                        eventsCount = listeningEvents.size,
                        imagesCount = bundledCount
                    )
                }
            } ?: ImportExportResult.Error("Could not open file for writing")
            
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            ImportExportResult.Error("Export failed: ${e.message}", e)
        } finally {
            delay(1000)
            _progress.value = null
        }
    }
    
    /**
     * Import data from a ZIP file at the given URI.
     * 
     * Restores local images, imports data with chronological ordering,
     * and triggers pre-caching of hotlinked images.
     */
    suspend fun importData(
        uri: Uri,
        conflictStrategy: ImportConflictStrategy
    ): ImportExportResult = withContext(Dispatchers.IO) {
        try {
            _progress.value = ImportExportProgress("Reading backup file...", 0, 100, true)
            
            // First pass: read data.json to get manifest
            var exportData: TempoExportData? = null
            val extractedImages = mutableMapOf<String, String>() // bundledName -> newPath
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == TempoExportData.DATA_FILENAME -> {
                                val json = zipIn.readBytes().toString(Charsets.UTF_8)
                                val adapter = moshi.adapter(TempoExportData::class.java)
                                exportData = adapter.fromJson(json)
                            }
                            entry.name.startsWith(IMAGES_DIR) && !entry.isDirectory -> {
                                // Extract image to local storage
                                val bundledName = entry.name.removePrefix(IMAGES_DIR)
                                val newPath = extractImage(zipIn, bundledName)
                                if (newPath != null) {
                                    extractedImages[bundledName] = newPath
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
            
            val data = exportData ?: return@withContext ImportExportResult.Error("Invalid backup file: no data found")
            
            // Validate version
            if (data.version > TempoExportData.CURRENT_VERSION) {
                return@withContext ImportExportResult.Error(
                    "Backup is from a newer app version. Please update Tempo."
                )
            }
            
            Log.i(TAG, "Extracted ${extractedImages.size} images")
            
            // Build path mapping: old file:// path -> new file:// path
            val pathMapping = data.localImageManifest.mapNotNull { (bundledName, originalPath) ->
                extractedImages[bundledName]?.let { newPath -> originalPath to newPath }
            }.toMap()
            
            _progress.value = ImportExportProgress("Importing artists...", 15, 100)
            
            // ID mappings for foreign keys
            val artistIdMap = mutableMapOf<Long, Long>()
            val trackIdMap = mutableMapOf<Long, Long>()
            val albumIdMap = mutableMapOf<Long, Long>()
            
            // Import Artists
            var importedArtists = 0
            for (artist in data.artists) {
                val remappedArtist = remapImagePath(artist, pathMapping)
                val existingArtist = database.artistDao().getArtistByNormalizedName(remappedArtist.normalizedName)
                
                if (existingArtist != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.artistDao().update(remappedArtist.copy(id = existingArtist.id))
                    }
                    artistIdMap[artist.id] = existingArtist.id
                } else {
                    val newId = database.artistDao().insert(remappedArtist.copy(id = 0))
                    if (newId > 0) {
                        artistIdMap[artist.id] = newId
                        importedArtists++
                    }
                }
            }
            
            _progress.value = ImportExportProgress("Importing albums...", 30, 100)
            
            // Import Albums
            var importedAlbums = 0
            for (album in data.albums) {
                val newArtistId = artistIdMap[album.artistId] ?: continue
                val remappedAlbum = remapImagePath(album, pathMapping).copy(artistId = newArtistId)
            val artistName = data.artists.find { it.id == album.artistId }?.name
                if (artistName == null) {
                    Log.w(TAG, "Skipping album '${album.title}': artist ID ${album.artistId} not found in export")
                    continue
                }
                val existingAlbum = database.albumDao().getAlbumByTitleAndArtist(
                    album.title,
                    artistName
                )
                
                if (existingAlbum != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.albumDao().update(remappedAlbum.copy(id = existingAlbum.id))
                    }
                    albumIdMap[album.id] = existingAlbum.id
                } else {
                    val newId = database.albumDao().insert(remappedAlbum.copy(id = 0))
                    if (newId > 0) {
                        albumIdMap[album.id] = newId
                        importedAlbums++
                    }
                }
            }
            
            _progress.value = ImportExportProgress("Importing tracks...", 45, 100)
            
            // Import Tracks
            var importedTracks = 0
            for (track in data.tracks) {
                val newPrimaryArtistId = track.primaryArtistId?.let { artistIdMap[it] }
                val remappedTrack = remapImagePath(track, pathMapping).copy(primaryArtistId = newPrimaryArtistId)
                
                val existingTrack = track.spotifyId?.let { database.trackDao().findBySpotifyId(it) }
                    ?: track.musicbrainzId?.let { database.trackDao().findByMusicBrainzId(it) }
                    ?: database.trackDao().findByTitleAndArtist(track.title, track.artist)
                
                if (existingTrack != null) {
                    if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                        database.trackDao().update(remappedTrack.copy(id = existingTrack.id))
                    }
                    trackIdMap[track.id] = existingTrack.id
                } else {
                    val newId = database.trackDao().insert(remappedTrack.copy(id = 0))
                    if (newId > 0) {
                        trackIdMap[track.id] = newId
                        importedTracks++
                    }
                }
            }
            
            _progress.value = ImportExportProgress("Importing track-artist links...", 55, 100)
            
            // Import TrackArtists
            val remappedTrackArtists = data.trackArtists.mapNotNull { ta ->
                val newTrackId = trackIdMap[ta.trackId] ?: return@mapNotNull null
                val newArtistId = artistIdMap[ta.artistId] ?: return@mapNotNull null
                ta.copy(trackId = newTrackId, artistId = newArtistId)
            }
            database.trackArtistDao().insertAllBatched(remappedTrackArtists)
            
            _progress.value = ImportExportProgress("Importing listening history...", 65, 100)
            
            // Import ListeningEvents - SORTED CHRONOLOGICALLY
            val sortedEvents = data.listeningEvents.sortedBy { it.timestamp }
            val remappedEvents = sortedEvents.mapNotNull { event ->
                val newTrackId = trackIdMap[event.track_id] ?: return@mapNotNull null
                event.copy(id = 0, track_id = newTrackId)
            }
            
            val insertedEventIds = database.listeningEventDao().insertAllBatched(remappedEvents)
            val importedEvents = insertedEventIds.count { it > 0 }
            
            _progress.value = ImportExportProgress("Importing metadata...", 80, 100)
            
            // Import EnrichedMetadata
            for (meta in data.enrichedMetadata) {
                val newTrackId = trackIdMap[meta.trackId] ?: continue
                val remappedMeta = remapImagePath(meta, pathMapping).copy(trackId = newTrackId)
                val existingMeta = database.enrichedMetadataDao().forTrackSync(newTrackId)
                
                if (existingMeta == null) {
                    database.enrichedMetadataDao().upsert(remappedMeta.copy(id = 0))
                } else if (conflictStrategy == ImportConflictStrategy.REPLACE) {
                    database.enrichedMetadataDao().update(remappedMeta.copy(id = existingMeta.id))
                }
            }
            
            _progress.value = ImportExportProgress("Importing preferences...", 90, 100)
            
            // Import UserPreferences
            data.userPreferences?.let { prefs ->
                database.userPreferencesDao().upsert(prefs)
            }
            
            // Schedule pre-caching of hotlinked images
            if (data.hotlinkedUrls.isNotEmpty()) {
                _progress.value = ImportExportProgress("Scheduling image cache...", 95, 100)
                PostRestoreCacheWorker.schedule(context, data.hotlinkedUrls)
            }
            
            _progress.value = ImportExportProgress("Import complete!", 100, 100)
            
            ImportExportResult.Success(
                tracksCount = importedTracks,
                artistsCount = importedArtists,
                albumsCount = importedAlbums,
                eventsCount = importedEvents,
                imagesCount = extractedImages.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportExportResult.Error("Import failed: ${e.message}", e)
        } finally {
            delay(1000)
            _progress.value = null
        }
    }
    
    /**
     * Collect all image URLs and classify as local (file://) or hotlink (http/https).
     */
    private fun collectImageUrls(
        tracks: List<Track>,
        artists: List<Artist>,
        albums: List<Album>,
        enrichedMetadata: List<EnrichedMetadata>
    ): Pair<List<String>, List<String>> {
        val localPaths = mutableSetOf<String>()
        val hotlinkUrls = mutableSetOf<String>()
        
        fun classify(url: String?) {
            url?.let {
                when {
                    it.startsWith("file://") -> localPaths.add(it)
                    it.startsWith("http://") || it.startsWith("https://") -> hotlinkUrls.add(it)
                }
            }
        }
        
        tracks.forEach { classify(it.albumArtUrl) }
        artists.forEach { classify(it.imageUrl) }
        albums.forEach { classify(it.artworkUrl) }
        
        enrichedMetadata.forEach { meta ->
            classify(meta.albumArtUrl)
            classify(meta.albumArtUrlSmall)
            classify(meta.albumArtUrlLarge)
            classify(meta.spotifyArtistImageUrl)
            classify(meta.iTunesArtistImageUrl)
            classify(meta.deezerArtistImageUrl)
            classify(meta.lastFmArtistImageUrl)
        }
        
        return localPaths.toList() to hotlinkUrls.toList()
    }
    
    /**
     * Extract an image from the ZIP to local storage.
     */
    private fun extractImage(zipIn: ZipInputStream, bundledName: String): String? {
        return try {
            val albumArtDir = File(context.filesDir, ALBUM_ART_DIR)
            if (!albumArtDir.exists()) albumArtDir.mkdirs()
            
            val newFile = File(albumArtDir, bundledName)
            newFile.outputStream().use { zipIn.copyTo(it) }
            
            "file://${newFile.absolutePath}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract image: $bundledName", e)
            null
        }
    }
    
    // Path remapping functions for different entity types
    
    private fun remapImagePath(track: Track, pathMapping: Map<String, String>): Track {
        val newArtUrl = track.albumArtUrl?.let { pathMapping[it] ?: it }
        return track.copy(albumArtUrl = newArtUrl)
    }
    
    private fun remapImagePath(artist: Artist, pathMapping: Map<String, String>): Artist {
        val newImageUrl = artist.imageUrl?.let { pathMapping[it] ?: it }
        return artist.copy(imageUrl = newImageUrl)
    }
    
    private fun remapImagePath(album: Album, pathMapping: Map<String, String>): Album {
        val newArtworkUrl = album.artworkUrl?.let { pathMapping[it] ?: it }
        return album.copy(artworkUrl = newArtworkUrl)
    }
    
    private fun remapImagePath(meta: EnrichedMetadata, pathMapping: Map<String, String>): EnrichedMetadata {
        return meta.copy(
            albumArtUrl = meta.albumArtUrl?.let { pathMapping[it] ?: it },
            albumArtUrlSmall = meta.albumArtUrlSmall?.let { pathMapping[it] ?: it },
            albumArtUrlLarge = meta.albumArtUrlLarge?.let { pathMapping[it] ?: it }
        )
    }
}
