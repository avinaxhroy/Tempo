package me.avinas.tempo.data.importexport

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.room.withTransaction
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.*
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.worker.PostRestoreCacheWorker
import okio.buffer
import okio.source
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
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val profileIdentityManager: ProfileIdentityManager,
    private val statsRepository: me.avinas.tempo.data.repository.StatsRepository
) {
    companion object {
        private const val TAG = "ImportExportManager"
        private const val ALBUM_ART_DIR = "album_art"
        private const val IMAGES_DIR = "images/"
        private const val MAX_BUNDLED_IMAGE_BYTES = 10L * 1024L * 1024L
        private const val MAX_TOTAL_IMAGE_BYTES = 50L * 1024L * 1024L
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
            val artistAliases = database.artistAliasDao().getAllSync()
            
            // v5: Collect new entities
            val trackAliases = database.trackAliasDao().getAllSync()
            val manualContentMarks = database.manualContentMarkDao().getAllSync()
            val appPreferences = database.appPreferenceDao().getAllSync()
            val scrobbleArchive = database.scrobbleArchiveDao().getAll()
            val lastFmImportMetadata = database.lastFmImportMetadataDao().getAll()
            
            // v6: Collect gamification data
            val userLevel = database.gamificationDao().getUserLevel()
            val badges = database.gamificationDao().getAllBadges()

            // v7+: Collect profile identity from DataStore
            val profileIdentity = profileIdentityManager.getProfileIdentity()

            // v8: Collect user-known artists and daily challenge history (completed only)
            val userKnownArtists = database.userKnownArtistDao().getAll()
            val dailyChallenges = database.gamificationDao().getAllCompletedChallenges()
            
            _progress.value = ImportExportProgress("Analyzing images...", 20, 100)
            
            // Classify image URLs
            val (localPaths, hotlinkUrls) = collectImageUrls(tracks, artists, albums, enrichedMetadata)
            val profileImagePath = profileIdentity.profileImagePath
            val backupImagePaths = buildSet {
                addAll(localPaths)
                profileImagePath?.takeIf { it.startsWith("file://") }?.let(::add)
            }
            
            Log.i(TAG, "Found ${backupImagePaths.size} local images, ${hotlinkUrls.size} hotlinks")
            
            // Build local image manifest (only if including local images)
            val localImageManifest = mutableMapOf<String, String>()
            
            _progress.value = ImportExportProgress("Creating backup...", 40, 100)
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    
                    // Bundle local images if enabled
                    var bundledCount = 0
                    if (backupImagePaths.isNotEmpty()) {
                        val bundleCount = if (includeLocalImages) backupImagePaths.size else if (profileImagePath != null) 1 else 0
                        _progress.value = ImportExportProgress("Bundling $bundleCount images...", 50, 100)
                        
                        backupImagePaths.forEachIndexed { index, filePath ->
                            if (!includeLocalImages && filePath != profileImagePath) return@forEachIndexed
                            try {
                                val file = resolveExportableLocalImage(filePath)
                                if (file != null) {
                                    val bundledName = "img_${index}_${file.name}"
                                    zipOut.putNextEntry(ZipEntry("$IMAGES_DIR$bundledName"))
                                    file.inputStream().use { it.copyTo(zipOut) }
                                    zipOut.closeEntry()
                                    localImageManifest[bundledName] = filePath
                                    bundledCount++
                                } else {
                                    Log.w(TAG, "Skipping non-app-local image path in export")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to bundle image", e)
                            }
                        }
                    }
                    
                    _progress.value = ImportExportProgress("Writing data...", 80, 100)
                    
                    // Create export data using current database schema version
                    val exportData = TempoExportData(
                        appVersion = BuildConfig.VERSION_NAME,
                        schemaVersion = AppDatabase.VERSION,
                        userName = profileIdentity.userName,
                        userProfileImagePath = profileImagePath,
                        tracks = tracks,
                        artists = artists,
                        albums = albums,
                        trackArtists = trackArtists,
                        listeningEvents = listeningEvents,
                        enrichedMetadata = enrichedMetadata,
                        userPreferences = userPrefs,
                        userLevel = userLevel,
                        badges = badges,
                        userKnownArtists = userKnownArtists,
                        dailyChallenges = dailyChallenges,
                        artistAliases = artistAliases,
                        trackAliases = trackAliases,
                        manualContentMarks = manualContentMarks,
                        appPreferences = appPreferences,
                        scrobbleArchive = scrobbleArchive,
                        lastFmImportMetadata = lastFmImportMetadata,
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
            var totalExtractedImageBytes = 0L
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == TempoExportData.DATA_FILENAME -> {
                                // Use streaming to avoid loading entire JSON into memory
                                val adapter = moshi.adapter(TempoExportData::class.java)
                                val bufferedSource = zipIn.source().buffer()
                                exportData = adapter.fromJson(bufferedSource)
                            }
                            entry.name.startsWith(IMAGES_DIR) && !entry.isDirectory -> {
                                // Extract image to local storage
                                val bundledName = entry.name.removePrefix(IMAGES_DIR)
                                val safeBundledName = sanitizeBundledImageName(bundledName)
                                if (safeBundledName == null) {
                                    Log.w(TAG, "Skipping unsafe bundled image name in import")
                                } else {
                                    val remainingBudget = MAX_TOTAL_IMAGE_BYTES - totalExtractedImageBytes
                                    val extracted = extractImage(zipIn, safeBundledName, remainingBudget)
                                    if (extracted != null) {
                                        extractedImages[safeBundledName] = extracted.path
                                        totalExtractedImageBytes += extracted.bytesWritten
                                    }
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
            
            // ID mappings for foreign keys (mutable maps modified inside the transaction)
            val artistIdMap = mutableMapOf<Long, Long>()
            val trackIdMap = mutableMapOf<Long, Long>()
            val albumIdMap = mutableMapOf<Long, Long>()
            
            // Counters collected inside the transaction
            var importedArtists = 0
            var importedAlbums = 0
            var importedTracks = 0
            var importedEvents = 0
            
            // Wrap all Room DB writes in a single transaction so partial failures are rolled back
            database.withTransaction {
            
            // Import Artists
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
            
            // Import ListeningEvents - SORTED CHRONOLOGICALLY, deduplicated against existing rows
            val sortedEvents = data.listeningEvents.sortedBy { it.timestamp }
            val remappedEvents = sortedEvents.mapNotNull { event ->
                val newTrackId = trackIdMap[event.track_id] ?: return@mapNotNull null
                event.copy(id = 0, track_id = newTrackId)
            }
            val dedupResult = database.listeningEventDao().insertAllBatchedWithDedup(remappedEvents)
            importedEvents = dedupResult.inserted
            Log.i(TAG, "Imported $importedEvents listening events (${dedupResult.skipped} skipped as duplicates)")
            
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

            // v6: Import UserLevel (gamification data)
            data.userLevel?.let { level ->
                database.gamificationDao().upsertUserLevel(level)
                Log.i(TAG, "Imported user level: ${level.currentLevel} (${level.totalXp} XP)")
            }
            
            // v6: Import Badges
            if (data.badges.isNotEmpty()) {
                database.gamificationDao().upsertBadges(data.badges)
                val earnedCount = data.badges.count { it.isEarned }
                Log.i(TAG, "Imported ${data.badges.size} badges ($earnedCount earned)")
            }

            // v8: Import UserKnownArtists
            if (data.userKnownArtists.isNotEmpty()) {
                for (known in data.userKnownArtists) {
                    database.userKnownArtistDao().insert(known.copy(id = 0))
                }
                Log.i(TAG, "Imported ${data.userKnownArtists.size} user-known artists")
            }

            // v8: Import DailyChallenges - deduplicate by (date, challengeId)
            if (data.dailyChallenges.isNotEmpty()) {
                val existingKeys = database.gamificationDao().getAllCompletedChallenges()
                    .map { it.date to it.challengeId }.toSet()
                val toInsert = data.dailyChallenges.filter { it.date to it.challengeId !in existingKeys }
                if (toInsert.isNotEmpty()) {
                    database.gamificationDao().upsertChallenges(toInsert.map { it.copy(id = 0) })
                }
                val completedCount = toInsert.count { it.isCompleted }
                Log.i(TAG, "Imported ${toInsert.size} daily challenges ($completedCount completed, ${data.dailyChallenges.size - toInsert.size} skipped as duplicates)")
            }
            
            // Import Artist Aliases (for merged artists)
            var importedAliases = 0
            for (alias in data.artistAliases) {
                val newTargetId = artistIdMap[alias.targetArtistId]
                if (newTargetId != null) {
                    val remappedAlias = alias.copy(id = 0, targetArtistId = newTargetId)
                    val existingAlias = database.artistAliasDao().findAlias(alias.originalNameNormalized)
                    if (existingAlias == null) {
                        database.artistAliasDao().insertAlias(remappedAlias)
                        importedAliases++
                    }
                }
            }
            Log.i(TAG, "Imported $importedAliases artist aliases")
            
            // v5: Import Track Aliases (for merged tracks)
            var importedTrackAliases = 0
            for (alias in data.trackAliases) {
                val newTargetTrackId = trackIdMap[alias.targetTrackId]
                if (newTargetTrackId != null) {
                    val remappedAlias = alias.copy(id = 0, targetTrackId = newTargetTrackId)
                    val existingAlias = database.trackAliasDao().findAlias(alias.originalTitle, alias.originalArtist)
                    if (existingAlias == null) {
                        database.trackAliasDao().insertAlias(remappedAlias)
                        importedTrackAliases++
                    }
                }
            }
            Log.i(TAG, "Imported $importedTrackAliases track aliases")
            
            // v5: Import Manual Content Marks (podcast/audiobook filters)
            var importedContentMarks = 0
            for (mark in data.manualContentMarks) {
                // Remap track ID - skip if target track wasn't imported
                val newTrackId = trackIdMap[mark.targetTrackId] ?: continue
                val remappedMark = mark.copy(id = 0, targetTrackId = newTrackId)
                // Check if same pattern already exists
                val existingMark = database.manualContentMarkDao().findMatchingMark(
                    remappedMark.originalTitle,
                    remappedMark.originalArtist
                )
                if (existingMark == null) {
                    database.manualContentMarkDao().insertMark(remappedMark)
                    importedContentMarks++
                }
            }
            Log.i(TAG, "Imported $importedContentMarks manual content marks")
            
            // v5: Import App Preferences (use IGNORE to not overwrite existing)
            if (data.appPreferences.isNotEmpty()) {
                database.appPreferenceDao().insertAll(data.appPreferences)
                Log.i(TAG, "Imported ${data.appPreferences.size} app preferences")
            }
            
            // v5: Import Scrobble Archive (Last.fm compressed history)
            if (data.scrobbleArchive.isNotEmpty()) {
                val remappedArchive = data.scrobbleArchive.map { it.copy(id = 0) }
                database.scrobbleArchiveDao().insertAll(remappedArchive)
                Log.i(TAG, "Imported ${data.scrobbleArchive.size} archived scrobble entries")
            }
            
            // v5: Import Last.fm Import Metadata
            if (data.lastFmImportMetadata.isNotEmpty()) {
                for (metadata in data.lastFmImportMetadata) {
                    database.lastFmImportMetadataDao().insert(metadata.copy(id = 0))
                }
                Log.i(TAG, "Imported ${data.lastFmImportMetadata.size} Last.fm import metadata records")
            }
            
            } // end database.withTransaction
            
            // v7+: Restore profile identity to DataStore (outside transaction — independent system)
            data.userName?.takeIf { it.isNotBlank() }?.let { name ->
                profileIdentityManager.updateUserName(name)
                Log.i(TAG, "Restored user name: $name")
            }
            val restoredProfileImagePath = resolveRestoredProfileImagePath(
                exportedProfileImagePath = data.userProfileImagePath,
                pathMapping = pathMapping
            )
            profileIdentityManager.restoreProfileImagePath(restoredProfileImagePath)
            Log.i(TAG, "Restored profile image path present=${!restoredProfileImagePath.isNullOrBlank()}")
            
            // Schedule pre-caching of hotlinked images
            if (data.hotlinkedUrls.isNotEmpty()) {
                _progress.value = ImportExportProgress("Scheduling image cache...", 95, 100)
                PostRestoreCacheWorker.schedule(context, data.hotlinkedUrls)
            }
            
            // Invalidate stats cache to force UI refresh (fixes "New User" state persisting)
            statsRepository.invalidateCache()
            
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
    private fun extractImage(
        zipIn: ZipInputStream,
        bundledName: String,
        remainingTotalBudgetBytes: Long
    ): ExtractedImage? {
        return try {
            if (remainingTotalBudgetBytes <= 0L) {
                Log.w(TAG, "Skipping image extraction: total image budget exhausted")
                return null
            }

            val albumArtDir = File(context.filesDir, ALBUM_ART_DIR)
            if (!albumArtDir.exists()) albumArtDir.mkdirs()
            
            val newFile = File(albumArtDir, bundledName)
            val bytesWritten = copyLimited(
                input = zipIn,
                outputFile = newFile,
                byteLimit = minOf(MAX_BUNDLED_IMAGE_BYTES, remainingTotalBudgetBytes)
            )

            ExtractedImage(
                path = "file://${newFile.absolutePath}",
                bytesWritten = bytesWritten
            )
        } catch (e: Exception) {
            if (e is SecurityException) throw e
            Log.w(TAG, "Failed to extract image: $bundledName", e)
            null
        }
    }

    private fun copyLimited(
        input: InputStream,
        outputFile: File,
        byteLimit: Long
    ): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        outputFile.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > byteLimit) {
                    outputFile.delete()
                    throw SecurityException("Image entry exceeds import size limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun sanitizeBundledImageName(bundledName: String): String? {
        val candidate = bundledName.trim()
        if (candidate.isBlank() || candidate.length > 128) return null
        if (candidate.contains('/') || candidate.contains('\\') || candidate == "." || candidate == "..") return null
        if (candidate.contains("..")) return null
        return candidate
    }

    private fun resolveExportableLocalImage(fileUri: String): File? {
        if (!fileUri.startsWith("file://")) return null

        val file = File(fileUri.removePrefix("file://"))
        if (!file.exists() || !file.isFile) return null

        val canonicalFile = file.canonicalFile
        val allowedRoots = listOf(context.filesDir, context.cacheDir).map { it.canonicalFile }
        val isAllowed = allowedRoots.any { root ->
            canonicalFile.path == root.path || canonicalFile.path.startsWith(root.path + File.separator)
        }
        if (!isAllowed) return null
        if (canonicalFile.length() > MAX_BUNDLED_IMAGE_BYTES) return null
        return canonicalFile
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

private data class ExtractedImage(
    val path: String,
    val bytesWritten: Long
)

internal fun resolveRestoredProfileImagePath(
    exportedProfileImagePath: String?,
    pathMapping: Map<String, String>
): String? {
    if (exportedProfileImagePath.isNullOrBlank()) return null
    return if (exportedProfileImagePath.startsWith("file://")) {
        pathMapping[exportedProfileImagePath]
    } else {
        exportedProfileImagePath
    }
}
