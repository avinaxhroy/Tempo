package me.avinas.tempo.data.applemusic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.worker.EnrichmentWorker

/**
 * Imports Apple Music listening history from a privacy export (privacy.apple.com).
 *
 * Shape mirrors [me.avinas.tempo.data.deezer.DeezerDataImportService] deliberately: same
 * staging contract, same batched insert path, same orphan cleanup. The only real differences
 * are the archive format and the fact that Apple's export carries no ISRC, so tracks are always
 * resolved from title/artist text.
 *
 * Enrichment is intentionally not attempted here. [EnrichmentWorker.schedulePostImportEnrichment]
 * is called afterwards and its queries are ordered by play count, so the most-played tracks get
 * their iTunes lookups first — that is the rate-limit strategy for Apple's ~20 req/min budget.
 */
@Singleton
class AppleMusicImportService @Inject constructor(
    private val trackResolver: TrackResolver,
    private val listeningEventDao: ListeningEventDao,
    private val trackRepository: TrackRepository,
    private val statsRepository: StatsRepository,
) {

    companion object {
        private const val TAG = "AppleMusicImportService"

        /**
         * Distinct from the live source `com.apple.android.music`, so imported history and
         * tracked playbacks stay separable while still being recognisably Apple Music.
         */
        const val IMPORT_SOURCE = "com.apple.android.music.import.csv"

        const val STAGED_IMPORT_DIR = "apple_music_imports"
        const val STAGED_IMPORT_PREFIX = "apple-music-import-"
        private const val STAGED_IMPORT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        private const val TEMP_FILE_PREFIX = "apple-music-temp-"
        private const val TEMP_DIR_NAME = "apple_music_temp"

        /** Privacy exports bundle years of activity; the outer archive is often 100-500 MB. */
        private const val MAX_FILE_SIZE_BYTES = 1024L * 1024 * 1024

        private const val FLUSH_BATCH_SIZE = 500
        private const val MAX_ERRORS = 20
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
        private const val SKIP_PLAY_DURATION_MS = 30_000L
        private const val PROGRESS_UPDATE_INTERVAL = 100
        private const val MAX_TRACK_CACHE = 20_000
        private const val MAX_SEEN_PLAYS = 20_000

        internal fun shouldImportApplePlay(
            msPlayed: Long,
            minimumPlayDurationMs: Long = TrackingRulesPreferences.DEFAULT_MIN_PLAY_DURATION_MS,
        ): Boolean = msPlayed >= minimumPlayDurationMs

        /** Same skip definition the live tracker uses, so imported and tracked skips agree. */
        internal fun isAppleSkip(msPlayed: Long, completionPercentage: Int): Boolean =
            msPlayed < SKIP_PLAY_DURATION_MS || completionPercentage < 30

        internal fun sanitizeDisplayName(value: String): String =
            value
                .replace(Regex("[\\r\\n\\t\\u0000-\\u001F\\u007F]+"), " ")
                .trim()
                .take(120)
                .ifBlank { "apple-music-export.zip" }

        /** The staged export the worker owns, when [uri] already points at readable local storage. */
        internal fun localFileOrNull(uri: Uri): File? {
            if (uri.scheme != "file") return null
            val path = uri.path ?: return null
            return File(path).takeIf { it.isFile && it.canRead() }
        }

        internal fun userFacingError(error: Exception): String {
            val message = error.message.orEmpty()
            return when {
                message.contains("does not contain Apple Music play activity", ignoreCase = true) ->
                    "This file does not contain Apple Music play activity"
                message.contains("play-activity columns", ignoreCase = true) ->
                    "The Apple Music play-activity columns in this export are not supported"
                message.contains("oversized", ignoreCase = true) ||
                    message.contains("too large", ignoreCase = true) ->
                    "This Apple Music export is too large to import safely on this device"
                error is java.util.zip.ZipException ->
                    "The selected file is not a valid Apple Music export ZIP"
                else -> "Apple Music import failed"
            }
        }
    }

    sealed class ImportState {
        object Idle : ImportState()
        data class Parsing(val fileName: String) : ImportState()
        data class Importing(
            val current: Int,
            val total: Int,
            val tracksImported: Int,
            val eventsCreated: Int,
        ) : ImportState()
        data class Completed(val result: ImportResult) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    data class ImportResult(
        val tracksImported: Int,
        val eventsCreated: Int,
        val duplicatesSkipped: Int,
        val shortPlaysSkipped: Int,
        val malformedRows: Int,
        val totalEntries: Int,
        /** Rows whose artist was recovered from the sibling files rather than a column. */
        val artistRecoveredRows: Int,
        val errors: List<String>,
    ) {
        val isSuccess: Boolean
            get() = eventsCreated > 0 || duplicatesSkipped > 0 || shortPlaysSkipped > 0
    }

    private val importMutex = Mutex()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /**
     * Copies the chosen export into app-private storage so the worker can restart after process
     * death without depending on a provider's persistable-URI support.
     */
    suspend fun stageImportFile(context: Context, uri: Uri): File =
        withContext(Dispatchers.IO) {
            val application = context.applicationContext
            val directory = File(application.filesDir, STAGED_IMPORT_DIR)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not prepare Apple Music import storage")
            }
            cleanupStaleStagedFiles(directory)

            val staged = File.createTempFile(STAGED_IMPORT_PREFIX, ".zip", directory)
            try {
                copyUriWithLimit(application, uri, staged)
                staged
            } catch (failure: Throwable) {
                runCatching { staged.delete() }
                throw failure
            }
        }

    fun deleteStagedImportFile(context: Context, filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val directory = File(context.applicationContext.filesDir, STAGED_IMPORT_DIR)
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return
        val file = runCatching { File(filePath).canonicalFile }.getOrNull() ?: return

        val owned = file.parentFile == canonicalDirectory &&
            file.name.startsWith(STAGED_IMPORT_PREFIX) &&
            file.name.endsWith(".zip", ignoreCase = true)
        if (owned && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete staged Apple Music import file")
        }
    }

    suspend fun importFromUri(context: Context, uri: Uri): ImportResult =
        importMutex.withLock {
            withContext(Dispatchers.IO) {
                val application = context.applicationContext
                val tempDir = File(application.cacheDir, TEMP_DIR_NAME)
                cleanupStaleTempDir(tempDir)

                val fileName = sanitizeDisplayName(getFileName(application, uri) ?: "apple-music-export.zip")
                _importState.value = ImportState.Parsing(fileName)

                val errors = mutableListOf<String>()
                val declaredSize = getFileSize(application, uri)
                if (declaredSize != null && declaredSize > MAX_FILE_SIZE_BYTES) {
                    val result = emptyResult(listOf("This Apple Music export is too large to import safely on this device"))
                    _importState.value = ImportState.Error(result.errors.first())
                    return@withContext result
                }

                var workingCopy: File? = null
                var importStarted = false
                try {
                    if (!tempDir.exists() && !tempDir.mkdirs()) {
                        throw IOException("Could not prepare Apple Music import workspace")
                    }

                    // The worker stages the export into app-private storage before enqueuing, so
                    // re-copying it here would double both the disk footprint and the read cost
                    // of an archive that is routinely hundreds of megabytes.
                    val sourceFile = localFileOrNull(uri)?.also { staged ->
                        if (staged.length() > MAX_FILE_SIZE_BYTES) {
                            throw IOException("This Apple Music export is too large to import safely on this device")
                        }
                    } ?: File.createTempFile(TEMP_FILE_PREFIX, ".zip", tempDir).also { copy ->
                        workingCopy = copy
                        copyUriWithLimit(application, uri, copy)
                    }

                    val parsed = AppleMusicExportParser().parse(sourceFile, tempDir) {
                        coroutineContext.ensureActive()
                    }
                    if (parsed.entries.isEmpty()) {
                        throw IllegalArgumentException("No valid Apple Music listening history entries found")
                    }

                    importStarted = true
                    val minimumPlayDurationMs = TrackingRulesPreferences(application).minimumPlayDurationMs
                    val result = importEntries(parsed, errors, minimumPlayDurationMs)

                    if (result.tracksImported > 0 || result.eventsCreated > 0 || result.duplicatesSkipped > 0) {
                        try {
                            EnrichmentWorker.schedulePostImportEnrichment(application, result.tracksImported.toLong())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to schedule post-import enrichment", e)
                        }
                    }

                    _importState.value = ImportState.Completed(result)
                    result
                } catch (e: CancellationException) {
                    _importState.value = ImportState.Idle
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Apple Music import failed", e)
                    addCappedError(errors, userFacingError(e))
                    val result = emptyResult(errors.toList())
                    _importState.value = ImportState.Error(errors.firstOrNull() ?: "Apple Music import failed")
                    result
                } finally {
                    if (importStarted) {
                        // A cancelled import may already have committed batches.
                        runCatching { statsRepository.invalidateCache() }
                            .onFailure { Log.w(TAG, "Failed to invalidate stats after Apple Music import", it) }
                    }
                    workingCopy?.let { file ->
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Could not delete temporary Apple Music import file")
                        }
                    }
                    tempDir.deleteRecursively()
                }
            }
        }

    /**
     * The only parts of a [TrackResolver.Resolution] the import reads back.
     *
     * Caching whole resolutions kept up to [MAX_TRACK_CACHE] full track entities — title,
     * album, artwork urls — alive for the length of the import, for three fields.
     */
    private class CachedTrack(
        val trackId: Long,
        val isNewTrack: Boolean,
        val durationMs: Long?,
    )

    internal suspend fun importEntries(
        parsed: AppleMusicExportParser.ParseResult,
        errors: MutableList<String>,
        minimumPlayDurationMs: Long,
    ): ImportResult {
        val trackCache = HashMap<String, CachedTrack>()
        val createdTrackIds = LinkedHashSet<Long>()
        // Apple splits a single scrub-heavy listen into several consecutive rows. Rows that
        // repeat the same play are dropped here; the DAO fingerprint then catches exact repeats
        // on a later re-import.
        val seenPlays = HashSet<String>()
        val pendingEvents = ArrayList<ListeningEvent>(FLUSH_BATCH_SIZE)
        val importContext = coroutineContext
        var tracksImported = 0
        var eventsCreated = 0
        var duplicatesSkipped = 0
        var shortPlaysSkipped = 0

        suspend fun flush() {
            if (pendingEvents.isEmpty()) return
            try {
                val inserted = listeningEventDao.insertAllBatchedWithDedup(pendingEvents)
                eventsCreated += inserted.inserted
                duplicatesSkipped += inserted.skipped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Batch insert failed, falling back to individual inserts", e)
                for (event in pendingEvents) {
                    try {
                        val single = listeningEventDao.insertAllBatchedWithDedup(listOf(event))
                        eventsCreated += single.inserted
                        duplicatesSkipped += single.skipped
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to insert individual event for track ${event.track_id}", e2)
                        addCappedError(errors, "A listening event could not be imported")
                    }
                }
            } finally {
                pendingEvents.clear()
            }
        }

        try {
            parsed.entries.forEachIndexed { index, entry ->
                if (index % PROGRESS_UPDATE_INTERVAL == 0) {
                    importContext.ensureActive()
                    _importState.value = ImportState.Importing(
                        current = index,
                        total = parsed.entries.size,
                        tracksImported = tracksImported,
                        eventsCreated = eventsCreated,
                    )
                }

                if (!shouldImportApplePlay(entry.msPlayed, minimumPlayDurationMs)) {
                    shortPlaysSkipped++
                    return@forEachIndexed
                }

                val playKey = entry.trackName.lowercase() + "|" + entry.playedAtMillis
                // ponytail: bounded window, not a full set. Apple's scrub-split rows are adjacent,
                // and the DAO fingerprint already catches exact repeats elsewhere, so this caps
                // memory instead of growing a title copy per row on million-row exports.
                if (seenPlays.size >= MAX_SEEN_PLAYS) seenPlays.clear()
                if (!seenPlays.add(playKey)) {
                    duplicatesSkipped++
                    return@forEachIndexed
                }

                try {
                    val cacheKey = entry.trackName.lowercase() + "|" +
                        entry.artistName.lowercase() + "|" + entry.albumName.orEmpty().lowercase()
                    val cachedTrack = trackCache[cacheKey] ?: trackResolver.resolve(
                        TrackResolver.Query(
                            title = entry.trackName,
                            artist = entry.artistName,
                            album = entry.albumName,
                            duration = entry.mediaDurationMs,
                        ),
                    ).let { resolved ->
                        CachedTrack(
                            trackId = resolved.trackId,
                            isNewTrack = resolved.isNewTrack,
                            durationMs = resolved.track.duration,
                        )
                    }.also { resolved ->
                        if (trackCache.size > MAX_TRACK_CACHE) trackCache.clear()
                        trackCache[cacheKey] = resolved
                    }

                    if (cachedTrack.isNewTrack) {
                        createdTrackIds.add(cachedTrack.trackId)
                        tracksImported++
                    }

                    val knownDurationMs = cachedTrack.durationMs?.takeIf { it > 0L }
                    val completionPercentage = knownDurationMs?.let { duration ->
                        ((entry.msPlayed * 100L) / duration).coerceIn(0L, 100L).toInt()
                    } ?: DEFAULT_COMPLETION_PERCENTAGE

                    // Apple logs "Event Start Timestamp", i.e. when playback BEGAN, which is the
                    // same point the live tracker stores in `timestamp`. Treating it as the end
                    // shifted every imported play backwards by its own length.
                    val startTimestamp = entry.playedAtMillis
                    pendingEvents.add(
                        ListeningEvent(
                            track_id = cachedTrack.trackId,
                            timestamp = startTimestamp,
                            playDuration = entry.msPlayed,
                            completionPercentage = completionPercentage,
                            source = IMPORT_SOURCE,
                            wasSkipped = isAppleSkip(entry.msPlayed, completionPercentage),
                            isReplay = false,
                            estimatedDurationMs = knownDurationMs,
                            pauseCount = 0,
                            sessionId = null,
                            endTimestamp = startTimestamp + entry.msPlayed,
                        ),
                    )
                    if (pendingEvents.size >= FLUSH_BATCH_SIZE) flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import an Apple Music history row", e)
                    addCappedError(errors, "A listening-history row could not be imported")
                }
            }

            flush()
        } catch (failure: Throwable) {
            // A track row can be created before its event commits; drop those orphans so a
            // cancelled import does not leave empty tracks behind.
            withContext(NonCancellable) {
                runCatching { removeOrphanedTracks(createdTrackIds) }
                    .onSuccess { removed -> tracksImported = (tracksImported - removed).coerceAtLeast(0) }
                    .onFailure { Log.w(TAG, "Failed to clean up orphaned Apple Music tracks", it) }
            }
            throw failure
        }

        if (errors.isNotEmpty() && createdTrackIds.isNotEmpty()) {
            val removed = runCatching { removeOrphanedTracks(createdTrackIds) }.getOrDefault(0)
            tracksImported = (tracksImported - removed).coerceAtLeast(0)
        }
        _importState.value = ImportState.Importing(
            current = parsed.entries.size,
            total = parsed.entries.size,
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
        )

        return ImportResult(
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
            duplicatesSkipped = duplicatesSkipped,
            shortPlaysSkipped = shortPlaysSkipped,
            malformedRows = parsed.malformedRows,
            totalEntries = parsed.entries.size + parsed.malformedRows + parsed.skippedNonAudioRows,
            artistRecoveredRows = parsed.artistRecoveredRows,
            errors = errors.toList(),
        )
    }

    private suspend fun removeOrphanedTracks(createdTrackIds: Set<Long>): Int {
        if (createdTrackIds.isEmpty()) return 0

        val trackIdsWithEvents = HashSet<Long>()
        createdTrackIds.toList().chunked(900).forEach { chunk ->
            listeningEventDao.getTimestampsForTracks(chunk).forEach { row ->
                trackIdsWithEvents.add(row.track_id)
            }
        }

        var removed = 0
        for (trackId in createdTrackIds) {
            if (trackId in trackIdsWithEvents) continue
            if (trackRepository.deleteTrackWithAllData(trackId).success) removed++
        }
        if (removed > 0) {
            Log.i(TAG, "Removed $removed orphaned tracks left by an interrupted Apple Music import")
        }
        return removed
    }

    private fun emptyResult(errors: List<String>) = ImportResult(
        tracksImported = 0,
        eventsCreated = 0,
        duplicatesSkipped = 0,
        shortPlaysSkipped = 0,
        malformedRows = 0,
        totalEntries = 0,
        artistRecoveredRows = 0,
        errors = errors,
    )

    private suspend fun copyUriWithLimit(context: Context, uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the Apple Music export")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FILE_SIZE_BYTES) {
                        throw IOException("This Apple Music export is too large to import safely on this device")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun cleanupStaleStagedFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STAGED_IMPORT_MAX_AGE_MS
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(STAGED_IMPORT_PREFIX) && it.lastModified() < cutoff }
            ?.forEach { if (!it.delete()) Log.w(TAG, "Could not delete stale staged Apple Music import") }
    }

    private fun cleanupStaleTempDir(directory: File) {
        runCatching { directory.deleteRecursively() }
            .onFailure { Log.w(TAG, "Could not clear the Apple Music import workspace", it) }
    }

    private fun getFileSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read the Apple Music export size", e)
        null
    }

    private fun getFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read the Apple Music export name", e)
        null
    }

    private fun addCappedError(errors: MutableList<String>, message: String) {
        if (errors.size < MAX_ERRORS) errors.add(message)
        else if (errors.size == MAX_ERRORS) errors.add("…and more errors")
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }
}
