package me.avinas.tempo.data.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.worker.EnrichmentWorker
import okio.buffer
import okio.source
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class YouTubeMusicImportService
    @Inject
    constructor(
        private val trackResolver: TrackResolver,
        private val listeningEventDao: ListeningEventDao,
        private val artistLinkingService: ArtistLinkingService,
        private val enrichedMetadataDao: EnrichedMetadataDao,
        private val statsRepository: StatsRepository,
        private val trackDao: TrackDao,
        private val trackAliasRepository: TrackAliasRepository,
    ) {
        companion object {
            private const val TAG = "YouTubeMusicImport"
            private const val BATCH_SIZE = 50
            private const val DEFAULT_COMPLETION_PERCENTAGE = 80
            private const val MAX_STRING_LENGTH = 500
            private const val CANCELLATION_CHECK_INTERVAL = 100
            private const val ESTIMATED_DURATION_MS = 210_000L
            private const val YOUTUBE_LAUNCH_EPOCH = 1112620800000L
            private const val MAX_FUTURE_MS = 365L * 24 * 60 * 60 * 1000

            // ZIP hardening: entries stream (never readBytes()), so memory stays
            // flat no matter how large a legitimate export is. Only HTML
            // materializes (its parser needs the full text) and is capped.
            private const val MAX_ZIP_ENTRIES = 5_000
            private const val MAX_HTML_BYTES = 80L * 1024 * 1024
            private const val SNIFF_BYTES = 64 * 1024

            /** DB flush window: bounds import memory to one window, not the export. */
            private const val IMPORT_FLUSH_BATCH = 1_000

            // Abuse guard: a crafted file with millions of entries would flood the
            // DB/storage (each entry = track resolution + event + enrichment row).
            // 300k/file ≈ 100 plays/day for 8 years — far beyond plausible use.
            private const val MAX_PARSED_ENTRIES_PER_FILE = 300_000
            private const val MAX_PARSED_ENTRIES_TOTAL = 500_000

            /** Error list cap: per-entry failures must not grow the list unbounded
             * (memory + gigantic notifications + WorkManager's 10KB result limit). */
            private const val MAX_STORED_ERRORS = 50

            /** Zip-name sampling cap: entry names are attacker-controlled (up to
             * 64KB each); only samples are retained for the "not found" message. */
            private const val MAX_SAMPLED_ENTRY_NAMES = 50
            private const val MAX_ENTRY_NAME_CHARS = 200
            const val IMPORT_SOURCE = "com.google.android.apps.youtube.music.import.json"

            // Google Takeout localizes the watch-history file/folder names to the
            // account's language (e.g. Portuguese "histórico de músicas ouvidas.json",
            // French "historique des vidéos regardées.json"). Matching is done on a
            // diacritics-stripped, lowercased form so "histórico" matches "historico".
            private val HISTORY_ENTRY_KEYWORDS =
                listOf(
                    "watch-history",
                    "watch_history",
                    "watchhistory",
                    "myactivity",
                    "my-activity",
                    "history",
                    "historico",
                    "historique",
                    "historial",
                    "historia",
                    "historie",
                    "historiek",
                    "istoric",
                    "история",
                    "履歴",
                    "历史",
                    "기록",
                )

            // Entries with these names are never treated as watch history, even
            // if they mention "YouTube Music": search-history.json lives in the
            // same "history/" folder and its name matches the "history" keyword,
            // but its entries are searches ("Searched for ...", /results? URLs),
            // never plays.
            private val NON_HISTORY_ENTRY_KEYWORDS =
                listOf(
                    "search",
                    "playlist",
                    "subscription",
                    "comment",
                    "likes",
                    "favorite",
                    "message",
                    "chat",
                    "music-uploads",
                    "album",
                    "video-info",
                )

            // Localized equivalents of the "Watched " title prefix used by Takeout
            // exports in non-English languages. The prefix is only stripped when it is
            // followed by whitespace or a quote so real song titles are never mangled.
            private val LOCALIZED_WATCHED_PREFIXES =
                listOf(
                    "watched", // English
                    "assisti", // Portuguese
                    "ví", // Spanish
                    "visionné", // French
                    "angesehen", // German
                    "guardato", // Italian
                    "bekeken", // Dutch
                    "obejrzano", // Polish
                    "просмотрен", // Russian
                    "視聴", // Japanese
                )

            /**
             * True if a ZIP entry name looks like a watch-history / activity file.
             * Accents are normalized so localized names (histórico, histórique) match.
             */
            internal fun isWatchHistoryEntryName(name: String): Boolean {
                val normalized = normalizeEntryName(name)
                return HISTORY_ENTRY_KEYWORDS.any { normalized.contains(it) }
            }

            internal fun normalizeEntryName(name: String): String {
                val lower = name.lowercase(Locale.ROOT)
                val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
                return Normalizer.normalize(
                    decomposed.replace(Regex("\\p{Mn}+"), ""),
                    Normalizer.Form.NFC,
                )
            }

            /**
             * Content sniff for a watch-history JSON file: Takeout watch-history files
             * are a JSON array of objects carrying "header" plus "time"/"titleUrl"/
             * "subtitles" keys. Works regardless of the export's language because the
             * JSON keys themselves are never localized.
             */
            internal fun looksLikeWatchHistoryJson(head: String): Boolean {
                if (!head.trimStart().startsWith("[")) return false
                if (!head.contains("\"header\"")) return false
                return head.contains("\"time\"") ||
                    head.contains("\"titleUrl\"") ||
                    head.contains("\"subtitles\"")
            }

            /**
             * Content sniff for a watch-history HTML file: contains the "YouTube Music"
             * product label and links to watched videos.
             */
            internal fun looksLikeWatchHistoryHtml(head: String): Boolean {
                if (!head.contains("YouTube Music", ignoreCase = true)) return false
                return head.contains("watch?v=") ||
                    head.contains("youtu.be/") ||
                    head.contains("music.youtube.com")
            }

            internal fun isBlockedNonHistoryEntryName(name: String): Boolean {
                val normalized = normalizeEntryName(name)
                return NON_HISTORY_ENTRY_KEYWORDS.any { normalized.contains(it) }
            }
        }

        private val moshi =
            Moshi
                .Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

        private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
        val importState: StateFlow<ImportState> = _importState.asStateFlow()

        sealed class ImportState {
            object Idle : ImportState()

            data class Parsing(
                val fileName: String,
                val filesProcessed: Int,
                val totalFiles: Int,
            ) : ImportState()

            data class Importing(
                val current: Int,
                val total: Int,
                val tracksImported: Int,
                val eventsCreated: Int,
            ) : ImportState()

            data class Completed(
                val result: ImportResult,
            ) : ImportState()

            data class Error(
                val message: String,
            ) : ImportState()
        }

        data class ImportResult(
            val tracksImported: Int,
            val eventsCreated: Int,
            val duplicatesSkipped: Int,
            val podcastsSkipped: Int,
            val nonMusicSkipped: Int,
            val filesProcessed: Int,
            val totalEntries: Int,
            val errors: List<String>,
            /** Existing tracks whose placeholder artist (e.g. "Release") was repaired. */
            val tracksRepaired: Int = 0,
        ) {
            val isSuccess: Boolean get() = errors.isEmpty() || tracksImported > 0
        }

        data class ParsedEntry(
            val trackName: String,
            val artistName: String,
            val albumName: String?,
            val youtubeVideoId: String?,
            val endTimeMillis: Long,
            val isPodcast: Boolean = false,
            val msPlayed: Long? = null,
        )

        suspend fun importFromUris(
            context: Context,
            uris: List<Uri>,
        ): ImportResult =
            withContext(Dispatchers.IO) {
                _importState.value = ImportState.Parsing("", 0, uris.size)

                val errors = mutableListOf<String>()
                var filesProcessed = 0
                var totalEntriesAll = 0
                var podcastsSkippedTotal = 0
                var nonMusicSkippedTotal = 0
                var tracksImported = 0
                var eventsCreated = 0
                var duplicatesSkipped = 0
                var tracksRepaired = 0
                var suppressedErrorCount = 0

                // Bounded error reporting: per-entry failures on a hostile file must
                // not grow this list (and downstream notifications/WorkManager data)
                // without limit. Overflow is counted and summarized at the end.
                fun addError(message: String) {
                    if (errors.size < MAX_STORED_ERRORS) {
                        errors.add(message)
                    } else {
                        suppressedErrorCount++
                    }
                }

                // Shared across files so re-imported tracks resolve identically.
                val trackCache = mutableMapOf<String, Long>()
                val pendingEvents = mutableListOf<ListeningEvent>()
                val pendingEnrichedMetadata = mutableListOf<Pair<Long, ParsedEntry>>()
                val existingTrackIds = mutableSetOf<Long>()

                // Batch-inserts whatever has accumulated and folds the actual DB
                // inserted/skipped counts into the running totals. Flushing
                // incrementally bounds memory to one window no matter the export size.
                suspend fun flushPendingEvents() {
                    if (pendingEvents.isEmpty()) return
                    val batch = pendingEvents.toList()
                    pendingEvents.clear()
                    try {
                        val insertResult = listeningEventDao.insertAllBatchedWithDedup(batch)
                        eventsCreated += insertResult.inserted
                        duplicatesSkipped += insertResult.skipped
                        Log.i(
                            TAG,
                            "Batch inserted ${insertResult.inserted} events, skipped ${insertResult.skipped} duplicates, replaced ${insertResult.replaced} lower-authority",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Batch insert failed, falling back to individual inserts", e)
                        for (event in batch) {
                            try {
                                val count =
                                    listeningEventDao.countEventsNearTimestamp(
                                        event.track_id,
                                        event.timestamp - ListeningEventDao.DUPLICATE_TOLERANCE_MS,
                                        event.timestamp + ListeningEventDao.DUPLICATE_TOLERANCE_MS,
                                    )
                                if (count == 0) {
                                    listeningEventDao.insert(event)
                                    eventsCreated++
                                } else {
                                    duplicatesSkipped++
                                }
                            } catch (e2: Exception) {
                                Log.e(TAG, "Failed to insert event for track ${event.track_id}", e2)
                            }
                        }
                    }
                }

                // Imports one file's parsed entries immediately instead of
                // accumulating every file in memory: peak usage is bounded by the
                // largest single file, and already-imported files survive a later
                // file failing. Cross-file order is irrelevant — track resolution
                // and timestamp-window dedup are order-independent.
                suspend fun importParsedEntries(entries: List<ParsedEntry>) {
                    podcastsSkippedTotal += entries.count { it.isPodcast }
                    val musicEntries =
                        entries
                            .filter { !it.isPodcast }
                            .sortedBy { it.endTimeMillis }
                    val total = musicEntries.size
                    musicEntries.forEachIndexed { index, entry ->
                        if (index % CANCELLATION_CHECK_INTERVAL == 0) {
                            coroutineContext.ensureActive()
                        }

                        if (index % BATCH_SIZE == 0) {
                            _importState.value = ImportState.Importing(index, total, tracksImported, eventsCreated)
                        }

                        try {
                            when (processEntry(entry, trackCache, pendingEvents, pendingEnrichedMetadata, existingTrackIds)) {
                                ProcessResult.NewTrack -> tracksImported++
                                ProcessResult.ExistingTrack -> Unit
                                ProcessResult.Duplicate -> duplicatesSkipped++
                                ProcessResult.Repaired -> tracksRepaired++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to import: ${entry.trackName}", e)
                            addError("Failed: ${entry.trackName}")
                        }

                        if (pendingEvents.size >= IMPORT_FLUSH_BATCH) {
                            flushPendingEvents()
                        }
                    }
                    flushPendingEvents()
                }

                for ((index, uri) in uris.withIndex()) {
                    coroutineContext.ensureActive()

                    val fileName = getFileName(context, uri) ?: "file_${index + 1}"
                    _importState.value = ImportState.Parsing(fileName, index, uris.size)

                    try {
                        // No file-size reject: zips stream entry-by-entry and JSON
                        // parses incrementally with periodic DB flushes, so
                        // legitimate multi-hundred-MB Takeout parts import fine.
                        val entries =
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val buffered = BufferedInputStream(stream, 8192)
                                buffered.mark(8192)
                                val magic = ByteArray(4)
                                val bytesRead = buffered.read(magic)
                                buffered.reset()

                                val isZipMagic =
                                    bytesRead >= 4 &&
                                        magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                                val isZipByName = fileName.endsWith(".zip", ignoreCase = true)

                                if (isZipMagic || isZipByName) {
                                    parseZipStream(buffered, fileName)
                                } else {
                                    parseJsonStream(buffered, fileName)
                                }
                            } ?: run {
                                addError("Could not open: $fileName")
                                ParseResult()
                            }

                        if (entries.parsed.isEmpty()) {
                            entries.errors.forEach { addError(it) }
                            if (entries.errors.isEmpty()) {
                                addError(
                                    "No valid YouTube Music entries in: $fileName. " +
                                        "If this is search-history.json, select watch-history.json " +
                                        "or the whole ZIP instead.",
                                )
                            }
                            continue
                        }

                        entries.errors.forEach { addError(it) }
                        totalEntriesAll += entries.parsed.size
                        nonMusicSkippedTotal += entries.nonMusicSkipped
                        filesProcessed++
                        Log.i(
                            TAG,
                            "Parsed ${entries.parsed.size} YouTube Music entries from $fileName (skipped ${entries.nonMusicSkipped} non-music)",
                        )
                        importParsedEntries(entries.parsed)
                        if (totalEntriesAll >= MAX_PARSED_ENTRIES_TOTAL) {
                            addError("Stopped after $MAX_PARSED_ENTRIES_TOTAL entries across files; exceeds plausible history size.")
                            break
                        }
                    } catch (e: StackOverflowError) {
                        // Crafted files with extreme JSON nesting recurse in Moshi's
                        // skipValue; contain it like OOM — earlier files are safe.
                        Log.e(TAG, "Stack overflow parsing $fileName (malformed nesting?)", e)
                        addError("$fileName is malformed and could not be parsed.")
                    } catch (e: OutOfMemoryError) {
                        // Earlier files are already flushed to the DB, so partial
                        // progress survives; only this file's tail is lost.
                        Log.e(TAG, "Out of memory importing $fileName", e)
                        addError(
                            "$fileName could not be finished on this device " +
                                "($tracksImported tracks imported so far). " +
                                "Re-export from Takeout choosing JSON format.",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse $fileName", e)
                        addError("Failed to parse $fileName: ${e.message}")
                    }
                }

                if (suppressedErrorCount > 0) {
                    errors.add("...and $suppressedErrorCount more errors omitted.")
                }

                if (totalEntriesAll == 0) {
                    val result =
                        ImportResult(0, 0, 0, 0, 0, filesProcessed, 0, errors.ifEmpty { listOf("No YouTube Music entries found in files") })
                    _importState.value = ImportState.Completed(result)
                    return@withContext result
                }

                Log.i(TAG, "All files parsed: $totalEntriesAll entries, $tracksImported tracks, $eventsCreated events so far...")

                // Safety net: importParsedEntries flushes per file and per window, so
                // this is normally a no-op.
                flushPendingEvents()

                if (eventsCreated > 0 || tracksRepaired > 0) {
                    statsRepository.invalidateCache()
                }

                for ((trackId, entry) in pendingEnrichedMetadata) {
                    createEnrichedMetadata(trackId, entry)
                }

                val requeuedCount = requeueExistingTracksForEnrichment(existingTrackIds)

                val result =
                    ImportResult(
                        tracksImported = tracksImported,
                        eventsCreated = eventsCreated,
                        duplicatesSkipped = duplicatesSkipped,
                        podcastsSkipped = podcastsSkippedTotal,
                        nonMusicSkipped = nonMusicSkippedTotal,
                        filesProcessed = filesProcessed,
                        totalEntries = totalEntriesAll,
                        errors = errors,
                        tracksRepaired = tracksRepaired,
                    )

                _importState.value = ImportState.Completed(result)
                Log.i(
                    TAG,
                    "Import complete: $tracksImported tracks, $eventsCreated events, $duplicatesSkipped duplicates, $tracksRepaired repaired, $podcastsSkippedTotal podcasts skipped",
                )

                if (tracksImported > 0 || tracksRepaired > 0 || requeuedCount > 0) {
                    try {
                        EnrichmentWorker.schedulePostImportEnrichment(context, (tracksImported + tracksRepaired + requeuedCount).toLong())
                        Log.i(
                            TAG,
                            "Scheduled post-import enrichment for $tracksImported new + $tracksRepaired repaired + $requeuedCount re-queued tracks",
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to schedule post-import enrichment", e)
                    }
                }

                result
            }

        /** Decodes only the first bytes of a (potentially huge) entry for cheap sniffing. */
        private fun decodeHead(
            bytes: ByteArray,
            maxBytes: Int = 64 * 1024,
        ): String {
            val limit = minOf(bytes.size, maxBytes)
            return String(bytes, 0, limit, Charsets.UTF_8)
        }

        /**
         * Display-safe form of a zip entry name. Names are attacker-controlled
         * (up to 64KB, arbitrary bytes): truncate for retention and strip control
         * characters so they can't inject log lines or break UI/notifications.
         * Matching always uses the raw name; only display goes through this.
         */
        private fun safeEntryName(name: String): String = name.take(MAX_ENTRY_NAME_CHARS).replace(Regex("[\\p{Cntrl}]"), "_")

        /** Reads at most SNIFF_BYTES of the current zip entry for content sniffing. */
        private fun readHead(zis: ZipInputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = SNIFF_BYTES
            while (remaining > 0) {
                val n = zis.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }

        /**
         * Reads the remainder of the current zip entry up to [capBytes].
         * Returns null when the entry exceeds the cap; the unread remainder is
         * skipped by closeEntry(), so oversized files are skipped, not loaded.
         */
        private fun readEntryCapped(
            zis: ZipInputStream,
            capBytes: Long,
        ): ByteArray? {
            if (capBytes <= 0) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = zis.read(buf)
                if (n == -1) break
                total += n
                if (total > capBytes) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** Wrapper that ignores close() so Okio sources can't close the shared ZipInputStream. */
        private class NonClosingInputStream(
            delegate: InputStream,
        ) : java.io.FilterInputStream(delegate) {
            override fun close() { /* shared ZipInputStream stays open across entries */ }
        }

        internal data class ParseResult(
            val parsed: List<ParsedEntry> = emptyList(),
            val errors: List<String> = emptyList(),
            val htmlDetected: Boolean = false,
            val nonMusicSkipped: Int = 0,
        )

        private fun parseJsonStream(
            inputStream: InputStream,
            fileName: String,
        ): ParseResult = parseJsonFromSourceWithHtmlCheck(inputStream.source().buffer(), fileName)

        private fun parseJsonFromSourceWithHtmlCheck(
            source: okio.BufferedSource,
            fileName: String,
        ): ParseResult {
            val peekSource = source.peek()
            val firstChars = readFirstNonWhitespaceChars(peekSource, 20)

            if (firstChars.startsWith("<!DOCTYPE", ignoreCase = true) ||
                firstChars.startsWith("<html", ignoreCase = true)
            ) {
                val htmlContent = source.readUtf8()
                return parseHtmlString(htmlContent, fileName)
            }

            return parseJsonFromSource(source, fileName)
        }

        internal fun parseZipStream(
            inputStream: InputStream,
            fileName: String,
        ): ParseResult {
            val allEntries = mutableListOf<ParsedEntry>()
            val allErrors = mutableListOf<String>()
            var nonMusicSkipped = 0
            var jsonFilesFound = 0
            var htmlFilesFound = 0
            val allZipEntryNames = mutableListOf<String>()
            var zipEntryTotal = 0
            var entryCount = 0

            try {
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            allErrors.add("ZIP $fileName has too many files, stopped after $MAX_ZIP_ENTRIES.")
                            break
                        }
                        if (!entry.isDirectory) {
                            val rawName = entry.name
                            zipEntryTotal++
                            // Sample, don't retain: names are attacker-controlled.
                            if (allZipEntryNames.size < MAX_SAMPLED_ENTRY_NAMES) {
                                allZipEntryNames.add(safeEntryName(rawName))
                            }
                            // Match on the raw name; display only the safe form.
                            val entryName = rawName.lowercase()
                            val safeName = safeEntryName(rawName)
                            val entryLabel = "$fileName!/$safeName"

                            // Blocked names (search-history.json shares the
                            // "history" folder) are never watch history, not even
                            // via the keyword match — otherwise search entries
                            // would be parsed as plays.
                            val isWatchHistory =
                                isWatchHistoryEntryName(rawName) &&
                                    !isBlockedNonHistoryEntryName(rawName)
                            val isHistoryJson = isWatchHistory && entryName.endsWith(".json")
                            val isHistoryHtml = isWatchHistory && entryName.endsWith(".html")

                            try {
                                if (isHistoryJson) {
                                    // Streamed straight off the zip into Moshi's
                                    // JsonReader with no size limit: legitimate
                                    // exports of any size import with flat memory.
                                    jsonFilesFound++
                                    val source = NonClosingInputStream(zis).source().buffer()
                                    val result = parseJsonFromSourceWithHtmlCheck(source, entryLabel)
                                    allEntries.addAll(result.parsed)
                                    allErrors.addAll(result.errors)
                                    nonMusicSkipped += result.nonMusicSkipped
                                    if (result.htmlDetected) {
                                        allErrors.add(
                                            "HTML format detected in $safeName inside $fileName. Re-export from Takeout choosing JSON format.",
                                        )
                                    }
                                } else if (isHistoryHtml) {
                                    htmlFilesFound++
                                    // HTML parsing needs the full text, so this is
                                    // the one bounded path. JSON (the recommended
                                    // Takeout format) streams unbounded above.
                                    val bytes = readEntryCapped(zis, MAX_HTML_BYTES)
                                    if (bytes == null) {
                                        allErrors.add(
                                            "$safeName exceeds the 80MB HTML limit, skipped. Re-export from Takeout choosing JSON format.",
                                        )
                                    } else {
                                        val result = parseHtmlString(String(bytes, Charsets.UTF_8), entryLabel)
                                        allEntries.addAll(result.parsed)
                                        allErrors.addAll(result.errors)
                                        nonMusicSkipped += result.nonMusicSkipped
                                        Log.i(TAG, "Parsed ${result.parsed.size} entries from HTML file $safeName in ZIP")
                                    }
                                } else if ((entryName.endsWith(".json") || entryName.endsWith(".html")) &&
                                    !isBlockedNonHistoryEntryName(rawName)
                                ) {
                                    // Fallback for localized exports: sniff only the
                                    // head, then stream the rest.
                                    val head = readHead(zis)
                                    val headStr = decodeHead(head)
                                    if (entryName.endsWith(".json") && looksLikeWatchHistoryJson(headStr)) {
                                        jsonFilesFound++
                                        val combined =
                                            java.io.SequenceInputStream(
                                                java.io.ByteArrayInputStream(head),
                                                NonClosingInputStream(zis),
                                            )
                                        val result = parseJsonFromSourceWithHtmlCheck(combined.source().buffer(), entryLabel)
                                        allEntries.addAll(result.parsed)
                                        allErrors.addAll(result.errors)
                                        nonMusicSkipped += result.nonMusicSkipped
                                        if (result.htmlDetected) {
                                            allErrors.add(
                                                "HTML format detected in $safeName inside $fileName. Re-export from Takeout choosing JSON format.",
                                            )
                                        }
                                        Log.i(TAG, "Detected watch-history JSON by content sniffing: $safeName in ZIP $fileName")
                                    } else if (entryName.endsWith(".html") && looksLikeWatchHistoryHtml(headStr)) {
                                        htmlFilesFound++
                                        val rest = readEntryCapped(zis, MAX_HTML_BYTES - head.size)
                                        if (rest == null) {
                                            allErrors.add(
                                                "$safeName exceeds the 80MB HTML limit, skipped. Re-export from Takeout choosing JSON format.",
                                            )
                                        } else {
                                            val bytes = head + rest
                                            val result = parseHtmlString(String(bytes, Charsets.UTF_8), entryLabel)
                                            allEntries.addAll(result.parsed)
                                            allErrors.addAll(result.errors)
                                            nonMusicSkipped += result.nonMusicSkipped
                                            Log.i(
                                                TAG,
                                                "Detected watch-history HTML by content sniffing: $safeName in ZIP $fileName (parsed ${result.parsed.size} entries)",
                                            )
                                        }
                                    }
                                    // else: not history. closeEntry() below skips
                                    // the remainder without materializing it.
                                }
                                // else: playlists, subscriptions, media, ...:
                                // closeEntry() skips the remainder without
                                // materializing (old code readBytes() EVERY entry).
                            } catch (e: StackOverflowError) {
                                // Crafted files with extreme JSON nesting recurse in
                                // Moshi's skipValue; contain like OOM — kept entries
                                // from earlier in this zip survive.
                                Log.e(TAG, "Stack overflow parsing $safeName in ZIP $fileName (malformed nesting?)", e)
                                allErrors.add("$safeName is malformed and could not be parsed.")
                                break
                            } catch (e: StackOverflowError) {
                                Log.e(TAG, "Stack overflow reading ZIP $fileName", e)
                                return ParseResult(
                                    parsed = allEntries,
                                    errors = allErrors + "$fileName is malformed and could not be parsed.",
                                    nonMusicSkipped = nonMusicSkipped,
                                )
                            } catch (e: OutOfMemoryError) {
                                Log.e(TAG, "Out of memory parsing $safeName in ZIP $fileName", e)
                                allErrors.add("Ran out of memory on $safeName; entries parsed so far are kept.")
                                break
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory reading ZIP $fileName", e)
                return ParseResult(
                    parsed = allEntries,
                    errors = allErrors + "Not enough memory to finish $fileName; try a smaller export.",
                    nonMusicSkipped = nonMusicSkipped,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read ZIP $fileName", e)
                return ParseResult(errors = listOf("Failed to read ZIP $fileName: ${e.message}"))
            }

            if (jsonFilesFound == 0 && htmlFilesFound == 0) {
                Log.i(TAG, "ZIP $fileName contains $zipEntryTotal entries: ${allZipEntryNames.joinToString(", ")}")
                val error =
                    when {
                        zipEntryTotal == 0 -> {
                            "ZIP $fileName appears to be empty or is another part of a split archive. " +
                                "Select the ZIP that contains the 'history' folder (watch-history.json), or select all ZIP parts at once."
                        }

                        else -> {
                            val sampleNames = allZipEntryNames.take(15).joinToString(", ")
                            val ellipsis = if (zipEntryTotal > 15) "..." else ""
                            "No watch-history file found in ZIP $fileName. Found $zipEntryTotal files: $sampleNames$ellipsis " +
                                "If your export was split into multiple ZIPs, select the one containing the 'history' folder — or select all of them at once."
                        }
                    }
                return ParseResult(errors = listOf(error))
            }

            Log.i(
                TAG,
                "Found $jsonFilesFound JSON and $htmlFilesFound HTML watch-history file(s) in ZIP $fileName, parsed ${allEntries.size} entries",
            )
            return ParseResult(parsed = allEntries, errors = allErrors, nonMusicSkipped = nonMusicSkipped)
        }

        private fun parseJsonFromSource(
            source: okio.BufferedSource,
            fileName: String,
        ): ParseResult {
            return try {
                val reader = JsonReader.of(source)
                val entries = mutableListOf<ParsedEntry>()
                val errors = mutableListOf<String>()
                var nonMusicSkipped = 0

                reader.beginArray()
                while (reader.hasNext()) {
                    if (entries.size >= MAX_PARSED_ENTRIES_PER_FILE) {
                        errors.add("Stopped after $MAX_PARSED_ENTRIES_PER_FILE entries in $fileName; file exceeds plausible history size.")
                        return ParseResult(parsed = entries, errors = errors, nonMusicSkipped = nonMusicSkipped)
                    }
                    try {
                        val entry = readWatchHistoryEntry(reader)
                        if (entry == null) {
                            nonMusicSkipped++
                        } else if (entry.isPodcast) {
                            entries.add(entry)
                        } else {
                            entries.add(entry)
                        }
                    } catch (e: Exception) {
                        try {
                            reader.skipValue()
                        } catch (_: Exception) {
                        }
                    }
                }
                reader.endArray()

                ParseResult(parsed = entries, errors = errors, nonMusicSkipped = nonMusicSkipped)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON array in $fileName", e)
                ParseResult(errors = listOf("Failed to parse $fileName: ${e.message}"))
            }
        }

        private fun readFirstNonWhitespaceChars(
            source: okio.BufferedSource,
            count: Int,
        ): String {
            val sb = StringBuilder()
            while (!source.exhausted() && sb.length < count) {
                val b = source.readByte().toInt()
                if (!b.toChar().isWhitespace()) {
                    sb.append(b.toChar())
                }
            }
            return sb.toString()
        }

        private fun readWatchHistoryEntry(reader: JsonReader): ParsedEntry? {
            var header: String? = null
            var title: String? = null
            var titleUrl: String? = null
            var time: String? = null
            var description: String? = null
            val subtitles = mutableListOf<Pair<String?, String?>>()
            val products = mutableListOf<String>()
            val details = mutableListOf<Pair<String?, String?>>()
            var hasContentDetails = false

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "header" -> {
                        header = reader.nextStringOrNull()
                    }

                    "title" -> {
                        title = reader.nextStringOrNull()
                    }

                    "titleUrl" -> {
                        titleUrl = reader.nextStringOrNull()
                    }

                    "time" -> {
                        time = reader.nextStringOrNull()
                    }

                    "description" -> {
                        description = reader.nextStringOrNull()
                    }

                    "products" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            products.add(reader.nextStringOrNull() ?: "")
                        }
                        reader.endArray()
                    }

                    "subtitles" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var subName: String? = null
                            var subUrl: String? = null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> subName = reader.nextStringOrNull()
                                    "url" -> subUrl = reader.nextStringOrNull()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            subtitles.add(subName to subUrl)
                        }
                        reader.endArray()
                    }

                    "details" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var detailName: String? = null
                            var detailUrl: String? = null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> detailName = reader.nextStringOrNull()
                                    "url" -> detailUrl = reader.nextStringOrNull()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            details.add(detailName to detailUrl)
                        }
                        reader.endArray()
                    }

                    "contentDetails" -> {
                        hasContentDetails = true
                        reader.skipValue()
                    }

                    else -> {
                        reader.skipValue()
                    }
                }
            }
            reader.endObject()

            if (hasContentDetails && header == null && title == null) return null

            val isYTM =
                header?.contains("YouTube Music", ignoreCase = true) == true ||
                    products.any { it.contains("YouTube Music", ignoreCase = true) } ||
                    titleUrl?.contains("music.youtube.com") == true

            if (!isYTM) return null

            if (title.isNullOrBlank()) return null

            val titleTrimmed = title.trim()
            if (titleTrimmed == "Viewed Ads On YouTube Homepage") return null
            if (titleTrimmed.startsWith("Visited ")) return null
            if (titleUrl?.contains("youtube.com/post/") == true) return null
            // Search-history entries ("Searched for X") point at a results page,
            // not a video. The URL is the reliable signal: it is never localized,
            // unlike the "Searched for" title prefix. Without this guard a query
            // like "Artist - Song" would be imported as a fabricated play
            // (title "Searched for Artist", artist "Song").
            if (titleUrl?.contains("/results?") == true) return null

            val rawTitle = stripWatchedPrefix(title)
            if (rawTitle.isBlank()) return null

            if (rawTitle.startsWith("http://", ignoreCase = true) ||
                rawTitle.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }

            val videoId = extractVideoId(titleUrl)

            val timestamp = parseTimestamp(time)
            if (timestamp == 0L) return null
            if (timestamp < YOUTUBE_LAUNCH_EPOCH) return null
            if (timestamp > System.currentTimeMillis() + MAX_FUTURE_MS) return null

            // Role-aware extraction: newer Takeout layouts no longer guarantee that
            // subtitles[0] is the artist and subtitles[1] is the album — the first
            // subtitle can be the release link literally named "Release", with the
            // artist moved to another subtitle or into `details`.
            val (artistFromMetadata, albumFromMetadata) = extractArtistAndAlbum(subtitles, details)
            val (cleanTitle, artistFromTitle) =
                if (artistFromMetadata != null) {
                    rawTitle to null
                } else {
                    parseTitleAndArtist(rawTitle)
                }
            val artist = (artistFromMetadata ?: artistFromTitle)?.let { sanitizeString(it) }

            if (artist.isNullOrBlank()) return null

            val finalTitle = sanitizeString(cleanTitle)
            if (finalTitle.isBlank()) return null

            if (isAdOrNonMusic(finalTitle, artist)) return null
            if (isAdFromDetails(details)) return null

            val album = albumFromMetadata ?: extractAlbumFromDescription(description)
            val isPodcast = isPodcast(description, subtitles)

            return ParsedEntry(
                trackName = finalTitle,
                artistName = artist,
                albumName = album?.let { sanitizeString(it) },
                youtubeVideoId = videoId,
                endTimeMillis = timestamp,
                isPodcast = isPodcast,
            )
        }

        private fun stripWatchedPrefix(title: String): String {
            var cleaned = title.trim()
            for (prefix in LOCALIZED_WATCHED_PREFIXES) {
                if (cleaned.startsWith(prefix, ignoreCase = true)) {
                    val rest = cleaned.substring(prefix.length)
                    if (rest.isEmpty() || rest[0].isWhitespace() ||
                        rest[0] == '"' || rest[0] == '“' || rest[0] == '«'
                    ) {
                        cleaned = rest.trim()
                        break
                    }
                }
            }
            // Localized exports often wrap the video title in quotes, e.g. the
            // Portuguese title `Assisti "Song Name"`.
            if (cleaned.length >= 2) {
                val first = cleaned.first()
                val last = cleaned.last()
                if ((first == '"' && last == '"') ||
                    (first == '“' && last == '”') ||
                    (first == '«' && last == '»')
                ) {
                    cleaned = cleaned.substring(1, cleaned.length - 1).trim()
                }
            }
            return cleaned
        }

        private fun parseTitleAndArtist(title: String): Pair<String, String?> {
            val parts = title.split(" - ")
            return when {
                parts.size >= 2 -> parts.first() to parts.drop(1).joinToString(" - ")
                else -> title to null
            }
        }

        private fun cleanArtistName(name: String): String {
            var cleaned = name.trim()
            if (cleaned.endsWith(" - Topic")) {
                cleaned = cleaned.removeSuffix(" - Topic").trim()
            }
            if (cleaned.endsWith(" - Topic.")) {
                cleaned = cleaned.removeSuffix(" - Topic.").trim()
            }
            return cleaned
        }

        /**
         * Role-aware extraction of artist and album from a watch-history entry.
         *
         * Classic Takeout layout (pre-2024):
         *   subtitles[0] = artist channel ("Artist - Topic" -> /channel/UC...)
         *   subtitles[1] = album (playlist / release link)
         *
         * Newer Takeout layouts changed the roles: the first subtitle can be the
         * release link literally named "Release" (a structural label, not an
         * artist), while the artist channel moved further down the subtitle list
         * or into the `details` array. The old positional logic imported the label
         * "Release" as the artist for every such entry — pooling hundreds of
         * unrelated songs under one bogus artist, losing the album, and splitting
         * play counts across duplicate track rows.
         *
         * This classifier assigns roles by URL shape and label text instead of by
         * position, with progressively weaker fallbacks so every known layout —
         * classic and new — resolves correctly.
         */
        private fun extractArtistAndAlbum(
            subtitles: List<Pair<String?, String?>>,
            details: List<Pair<String?, String?>>,
        ): Pair<String?, String?> {
            val subtitleCandidates = subtitles.filter { !it.first.isNullOrBlank() }
            val detailCandidates = details.filter { !it.first.isNullOrBlank() }

            fun isArtistChannelEntry(
                name: String?,
                url: String?,
            ): Boolean =
                url?.let { isChannelUrl(it) } == true ||
                    name?.endsWith(" - Topic", ignoreCase = true) == true ||
                    name?.endsWith(" - Topic.", ignoreCase = true) == true

            fun isReleaseLinkEntry(url: String?): Boolean = url?.let { isAlbumUrl(it) } == true

            fun usableArtistName(name: String?): String? {
                val cleaned = cleanArtistName(name?.trim() ?: return null)
                if (cleaned.isBlank()) return null
                if (ArtistParser.isPlaceholderArtistName(cleaned)) return null
                if (ArtistParser.isUnknownArtist(cleaned)) return null
                return cleaned
            }

            // Artist — strongest signal first:
            // 1. A subtitle pointing at an artist channel (classic subtitles[0]).
            // 2. A subtitle that is neither a release link nor an artist channel
            //    (older exports sometimes carry plain-name subtitles with no URLs).
            // 3. A `details` entry pointing at an artist channel (newer layout).
            // 4. Any other `details` entry with a usable name.
            val artistEntry =
                subtitleCandidates.firstOrNull {
                    isArtistChannelEntry(it.first, it.second) && usableArtistName(it.first) != null
                } ?: subtitleCandidates.firstOrNull {
                    !isReleaseLinkEntry(it.second) && !isArtistChannelEntry(it.first, it.second) &&
                        usableArtistName(it.first) != null
                } ?: detailCandidates.firstOrNull {
                    isArtistChannelEntry(it.first, it.second) && usableArtistName(it.first) != null
                } ?: detailCandidates.firstOrNull {
                    !isReleaseLinkEntry(it.second) && usableArtistName(it.first) != null
                }

            val artist = artistEntry?.let { usableArtistName(it.first) }

            // Album — from the remaining subtitles, never the artist entry, never
            // an artist channel (featured-artist links) and never a placeholder
            // label. The classic positional subtitles[1] stays as the final
            // fallback for old exports whose album links carry no distinguishing URL.
            val album =
                subtitleCandidates
                    .filter { it !== artistEntry }
                    .let { remaining ->
                        remaining
                            .firstOrNull {
                                isReleaseLinkEntry(it.second) &&
                                    !isArtistChannelEntry(it.first, it.second) &&
                                    usableArtistName(it.first) != null
                            }?.first
                            ?.trim()
                            ?: remaining
                                .firstOrNull {
                                    !isArtistChannelEntry(it.first, it.second) &&
                                        usableArtistName(it.first) != null
                                }?.first
                                ?.trim()
                    }

            return artist to album
        }

        /** Artist channel links look like https://(www.|music.)youtube.com/channel/UC... */
        private fun isChannelUrl(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("/channel/")
        }

        /**
         * Release/album links are playlists or browse pages:
         * https://www.youtube.com/playlist?list=...,
         * https://music.youtube.com/browse/..., https://music.youtube.com/album/...
         */
        private fun isAlbumUrl(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("playlist") || u.contains("/browse/") || u.contains("/album/")
        }

        private fun extractAlbumFromDescription(description: String?): String? {
            if (description == null) return null
            val albumMatch = Regex("from album[:\\s]+(.+)", RegexOption.IGNORE_CASE).find(description)
            if (albumMatch != null) {
                return albumMatch.groupValues[1].trim()
            }
            return null
        }

        private fun isAdOrNonMusic(
            title: String,
            artist: String,
        ): Boolean {
            val titleLower = title.lowercase().trim()
            val artistLower = artist.lowercase().trim()

            val nonMusicNames = setOf("youtube", "youtube music", "advertisement", "ad", "video unavailable")
            if (titleLower in nonMusicNames) return true
            if (artistLower in nonMusicNames) return true
            if (artistLower.contains("youtube music")) return true
            // Structural placeholder labels ("Release", "Song", "Playlist", ...) must
            // never be stored as an artist — if the entry resolves to one, skip it
            // rather than pool it under a bogus artist.
            if (ArtistParser.isPlaceholderArtistName(artist)) return true

            return false
        }

        private fun isAdFromDetails(details: List<Pair<String?, String?>>): Boolean =
            details.any { it.first?.lowercase()?.contains("ads") == true }

        private fun isPodcast(
            description: String?,
            subtitles: List<Pair<String?, String?>>,
        ): Boolean {
            if (description?.lowercase()?.contains("podcast") == true) return true
            if (subtitles.any { it.first?.lowercase()?.contains("podcast") == true }) return true
            return false
        }

        private fun extractVideoId(url: String?): String? {
            if (url.isNullOrBlank()) return null

            try {
                val uri = Uri.parse(url)
                val vParam = uri.getQueryParameter("v")
                if (!vParam.isNullOrBlank() && vParam.length == 11) return vParam

                if (url.contains("youtu.be/")) {
                    val path = uri.lastPathSegment
                    if (!path.isNullOrBlank() && path.length == 11) return path
                }

                val pattern = Regex("[?&]v=([^&]+)")
                pattern.find(url)?.let {
                    val id = it.groupValues[1]
                    if (id.length == 11) return id
                }
            } catch (_: Exception) {
            }

            return null
        }

        private sealed class ProcessResult {
            object NewTrack : ProcessResult()

            object ExistingTrack : ProcessResult()

            object Duplicate : ProcessResult()

            /** An existing track with a placeholder artist (e.g. "Release") was repaired. */
            object Repaired : ProcessResult()
        }

        private suspend fun processEntry(
            entry: ParsedEntry,
            trackCache: MutableMap<String, Long>,
            pendingEvents: MutableList<ListeningEvent>,
            pendingEnrichedMetadata: MutableList<Pair<Long, ParsedEntry>>,
            existingTrackIds: MutableSet<Long>,
        ): ProcessResult {
            if (entry.endTimeMillis == 0L) return ProcessResult.Duplicate

            val cacheKey = entry.youtubeVideoId ?: "${entry.trackName}|${entry.artistName}"
            val cachedTrackId = trackCache[cacheKey]

            var trackId: Long
            val isNewTrack: Boolean
            var repaired = false

            if (cachedTrackId != null) {
                trackId = cachedTrackId
                isNewTrack = false
            } else {
                val resolution =
                    trackResolver.resolve(
                        TrackResolver.Query(
                            title = entry.trackName,
                            artist = entry.artistName,
                            album = entry.albumName,
                            youtubeId = entry.youtubeVideoId,
                        ),
                    )
                trackId = resolution.trackId
                isNewTrack = resolution.isNewTrack

                if (isNewTrack) {
                    try {
                        artistLinkingService.linkArtistsForTrack(resolution.track)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to link artists for track $trackId", e)
                    }
                    pendingEnrichedMetadata.add(trackId to entry)
                } else if (isRepairableImportArtifact(resolution.track, entry)) {
                    // Older builds imported newer Takeout exports positionally and
                    // stored the "Release" label as the artist. Re-importing the
                    // same ZIP now detects those rows and fixes them in place (or
                    // consolidates them into the correct-artist twin row), while
                    // the already-imported plays are deduplicated by fingerprint.
                    val repairedTrackId = repairImportedArtistArtifact(resolution.track, entry)
                    if (repairedTrackId != null) {
                        trackId = repairedTrackId
                        repaired = true
                    }
                }
                trackCache[cacheKey] = trackId
            }

            if (!isNewTrack) {
                existingTrackIds.add(trackId)
            }

            val event = createListeningEvent(trackId, entry)
            pendingEvents.add(event)
            return when {
                repaired -> ProcessResult.Repaired
                isNewTrack -> ProcessResult.NewTrack
                else -> ProcessResult.ExistingTrack
            }
        }

        /**
         * True when [track] is an existing row whose artist is one of the structural
         * placeholder labels the old positional parser mistook for an artist
         * (e.g. "Release"), while [entry] — parsed with the role-aware logic —
         * carries a real artist for the same recording.
         */
        private fun isRepairableImportArtifact(
            track: Track,
            entry: ParsedEntry,
        ): Boolean {
            if (!ArtistParser.isPlaceholderArtistName(track.artist)) return false
            if (ArtistParser.isPlaceholderArtistName(entry.artistName)) return false
            if (entry.artistName.equals(track.artist, ignoreCase = true)) return false
            // Same recording only: the artifact row must not contradict the
            // incoming entry's video id (both set and different means a different song).
            if (track.youtubeId != null && entry.youtubeVideoId != null &&
                track.youtubeId != entry.youtubeVideoId
            ) {
                return false
            }
            return true
        }

        /**
         * Repair one placeholder-artist track row using the freshly parsed entry.
         *
         * 1. If a track with the same title and the *correct* artist already exists
         *    (typically the live-tracked copy), the artifact row is merged into it
         *    so all history lands on one canonical track row.
         * 2. Otherwise the artifact row's artist (and missing album) is fixed in
         *    place and re-linked.
         *
         * Returns the track id plays should attach to from now on, or null when
         * the repair failed (the untouched row is used as-is in that case).
         */
        private suspend fun repairImportedArtistArtifact(
            existing: Track,
            entry: ParsedEntry,
        ): Long? {
            val goodArtist = entry.artistName

            val twin = trackDao.findByTitleAndArtist(entry.trackName, goodArtist)
            if (twin != null && twin.id != existing.id) {
                val merged =
                    try {
                        trackAliasRepository.mergeTracks(existing.id, twin.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to merge artifact track ${existing.id} into ${twin.id}", e)
                        false
                    }
                if (merged) {
                    requeueRepairedTrackForEnrichment(twin.id, entry)
                    Log.i(
                        TAG,
                        "Repaired import artifact: merged '${entry.trackName}' (placeholder artist " +
                            "'${existing.artist}') into '$goodArtist' (track ${twin.id})",
                    )
                    return twin.id
                }
            }

            return try {
                trackDao.updateArtistString(existing.id, goodArtist)
                var updated = existing.copy(artist = goodArtist)
                if (existing.album.isNullOrBlank() && !entry.albumName.isNullOrBlank()) {
                    trackDao.setTrackAlbum(existing.id, entry.albumName)
                    updated = updated.copy(album = entry.albumName)
                }
                artistLinkingService.linkArtistsForTrack(updated)
                requeueRepairedTrackForEnrichment(existing.id, entry)
                Log.i(
                    TAG,
                    "Repaired import artifact: '${entry.trackName}' artist " +
                        "'${existing.artist}' -> '$goodArtist' (track ${existing.id})",
                )
                existing.id
            } catch (e: Exception) {
                Log.w(TAG, "Failed to repair artifact track ${existing.id} ('${entry.trackName}')", e)
                null
            }
        }

        /**
         * Point a repaired track's enrichment metadata at the corrected artist and
         * re-queue it, so the enrichment workers can finally resolve the album,
         * artwork, and genres the placeholder artist made unfindable.
         */
        private suspend fun requeueRepairedTrackForEnrichment(
            trackId: Long,
            entry: ParsedEntry,
        ) {
            try {
                val current = enrichedMetadataDao.forTrackSync(trackId)
                val repaired =
                    (current ?: EnrichedMetadata(trackId = trackId)).copy(
                        artistName = entry.artistName,
                        albumTitle =
                            entry.albumName
                                ?: current?.albumTitle?.takeIf { !ArtistParser.isPlaceholderArtistName(it) },
                        enrichmentStatus = EnrichmentStatus.PENDING,
                        retryCount = 0,
                        lastEnrichmentAttempt = null,
                        cacheTimestamp = System.currentTimeMillis(),
                    )
                enrichedMetadataDao.upsertFromAutomaticEnrichment(repaired)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-queue repaired track $trackId for enrichment", e)
            }
        }

        private fun estimateDuration(entry: ParsedEntry): Long = entry.msPlayed?.takeIf { it > 0 } ?: ESTIMATED_DURATION_MS

        private fun createListeningEvent(
            trackId: Long,
            entry: ParsedEntry,
        ): ListeningEvent {
            val estimatedDuration = estimateDuration(entry)
            val completionPercentage = DEFAULT_COMPLETION_PERCENTAGE

            return ListeningEvent(
                track_id = trackId,
                timestamp = entry.endTimeMillis - estimatedDuration,
                playDuration = estimatedDuration,
                completionPercentage = completionPercentage,
                source = IMPORT_SOURCE,
                wasSkipped = false,
                isReplay = false,
                estimatedDurationMs = estimatedDuration,
                pauseCount = 0,
                sessionId = null,
                endTimestamp = entry.endTimeMillis,
            )
        }

        private suspend fun createEnrichedMetadata(
            trackId: Long,
            entry: ParsedEntry,
        ) {
            try {
                val metadata =
                    EnrichedMetadata(
                        trackId = trackId,
                        spotifyId = null,
                        artistName = entry.artistName,
                        albumTitle = entry.albumName,
                        enrichmentStatus = EnrichmentStatus.PENDING,
                        cacheTimestamp = System.currentTimeMillis(),
                        lastEnrichmentAttempt = System.currentTimeMillis(),
                    )
                enrichedMetadataDao.upsertFromAutomaticEnrichment(metadata)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create enriched metadata for track $trackId", e)
            }
        }

        /**
         * Re-queue existing tracks for enrichment after import.
         * - Re-queues FAILED tracks and ENRICHED tracks missing album art as PENDING
         * - Creates PENDING entries for existing tracks that have no enriched_metadata row
         * Returns the total number of tracks queued for enrichment.
         */
        private suspend fun requeueExistingTracksForEnrichment(existingTrackIds: Set<Long>): Int {
            if (existingTrackIds.isEmpty()) return 0

            var requeued = 0
            existingTrackIds.chunked(BATCH_SIZE).forEach { batch ->
                try {
                    requeued += enrichedMetadataDao.requeueTracksForEnrichment(batch)

                    val missingIds = enrichedMetadataDao.findTrackIdsWithoutEnrichedMetadata(batch)
                    for (trackId in missingIds) {
                        createEnrichedMetadata(
                            trackId,
                            ParsedEntry(
                                trackName = "",
                                artistName = "",
                                albumName = null,
                                youtubeVideoId = null,
                                endTimeMillis = 0L,
                            ),
                        )
                        requeued++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-queue batch of ${batch.size} tracks for enrichment", e)
                }
            }

            if (requeued > 0) {
                Log.i(TAG, "Re-queued $requeued existing tracks for enrichment")
            }
            return requeued
        }

        private fun parseTimestamp(value: String?): Long = parseYouTubeTimestamp(value)

        private fun parseHtmlString(
            htmlContent: String,
            fileName: String,
        ): ParseResult {
            val entries = mutableListOf<ParsedEntry>()
            val errors = mutableListOf<String>()
            var nonMusicSkipped = 0

            try {
                val linkPattern =
                    Pattern.compile(
                        "<a\\s+href=\"([^\"]*(?:watch\\?v=|youtu\\.be/)([A-Za-z0-9_-]{11}))\"[^>]*>([^<]*)</a>",
                    )
                val allLinkPattern = Pattern.compile("<a\\s+href=\"[^\"]*\"[^>]*>([^<]*)</a>")

                // ponytail: lazy sequence avoids OOM — split() materialized every chunk as a copied String at once
                val chunks = htmlContent.splitToSequence("<div class=\"outer-cell")

                for (chunk in chunks) {
                    if (entries.size >= MAX_PARSED_ENTRIES_PER_FILE) {
                        errors.add("Stopped after $MAX_PARSED_ENTRIES_PER_FILE entries in $fileName; file exceeds plausible history size.")
                        break
                    }
                    if (chunk.isBlank()) continue

                    val isYTM = chunk.contains("YouTube Music", ignoreCase = true)
                    if (!isYTM) {
                        if (chunk.contains("watch?v=") || chunk.contains("youtu.be/")) {
                            nonMusicSkipped++
                        }
                        continue
                    }

                    val linkMatcher = linkPattern.matcher(chunk)
                    if (!linkMatcher.find()) continue

                    val videoUrl = linkMatcher.group(1)
                    val videoId = linkMatcher.group(2)
                    val rawTitle = linkMatcher.group(3)?.trim() ?: continue

                    val title = stripWatchedPrefix(rawTitle)
                    if (title.isBlank()) continue
                    if (title.startsWith("http://", ignoreCase = true) ||
                        title.startsWith("https://", ignoreCase = true)
                    ) {
                        continue
                    }

                    val artistMatcher = allLinkPattern.matcher(chunk)
                    var artistName: String? = null
                    var firstLinkSkipped = false
                    while (artistMatcher.find()) {
                        if (!firstLinkSkipped) {
                            firstLinkSkipped = true
                            continue
                        }
                        val name = artistMatcher.group(1)?.trim()
                        if (!name.isNullOrBlank()) {
                            val cleaned = cleanArtistName(name)
                            // Skip structural labels ("Release", "Playlist", ...) so a
                            // release link is never mistaken for the artist channel.
                            if (ArtistParser.isPlaceholderArtistName(cleaned)) continue
                            artistName = cleaned
                            break
                        }
                    }

                    if (artistName.isNullOrBlank()) {
                        val (titlePart, artistPart) = parseTitleAndArtist(title)
                        if (artistPart != null) {
                            artistName = artistPart
                        }
                    }

                    val finalTitle = sanitizeString(title)
                    val finalArtist = artistName?.let { sanitizeString(it) }

                    if (finalTitle.isBlank() || finalArtist.isNullOrBlank()) continue
                    if (isAdOrNonMusic(finalTitle, finalArtist)) continue

                    val timestamp = parseHtmlTimestamp(chunk)
                    if (timestamp == 0L) continue
                    if (timestamp < YOUTUBE_LAUNCH_EPOCH) continue
                    if (timestamp > System.currentTimeMillis() + MAX_FUTURE_MS) continue

                    val watchTime = parseWatchTime(chunk)
                    val isPodcast = chunk.lowercase().contains("podcast")

                    entries.add(
                        ParsedEntry(
                            trackName = finalTitle,
                            artistName = finalArtist,
                            albumName = null,
                            youtubeVideoId = videoId,
                            endTimeMillis = timestamp,
                            isPodcast = isPodcast,
                            msPlayed = watchTime,
                        ),
                    )
                }

                Log.i(TAG, "Parsed ${entries.size} YouTube Music entries from HTML $fileName (skipped $nonMusicSkipped non-music)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HTML $fileName", e)
                errors.add("Failed to parse HTML $fileName: ${e.message}")
            }

            return ParseResult(parsed = entries, errors = errors, nonMusicSkipped = nonMusicSkipped)
        }

        private fun parseHtmlTimestamp(text: String): Long {
            val cleanText = text.replace(Regex("<[^>]+>"), "\n").trim()
            val lines = cleanText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val timestampText = lines.lastOrNull { it.matches(Regex(".*\\d{4}.*")) } ?: return 0L

            var cleanedTs = timestampText.trim().replace(Regex("\\s+"), " ")

            // Strip trailing timezone abbreviation (e.g. "IST", "PST", "EST", "CET", "CEST", "GMT")
            // YouTube Takeout HTML timestamps can include locale-specific TZ abbreviations
            cleanedTs = cleanedTs.replace(Regex("\\s+[A-Z]{2,5}$"), "")

            val formats =
                listOf(
                    "MMM d, yyyy, h:mm:ss a" to Locale.US,
                    "MMM d, yyyy, H:mm:ss" to Locale.US,
                    "MMM d, yyyy, h:mm a" to Locale.US,
                    "MMM d, yyyy" to Locale.US,
                    "d MMM yyyy, H:mm:ss" to Locale.US,
                    "d MMM yyyy, HH:mm:ss" to Locale.US,
                    "d MMM. yyyy, H:mm:ss" to Locale.US,
                    "d MMM. yyyy, HH:mm:ss" to Locale.US,
                    "d. MMM yyyy, HH:mm:ss" to Locale.GERMANY,
                    "d MMM yyyy, HH:mm:ss" to Locale.FRANCE,
                    "yyyy-MM-dd HH:mm:ss" to Locale.US,
                    "yyyy-MM-dd HH:mm" to Locale.US,
                    "yyyy-MM-dd" to Locale.US,
                )

            for ((pattern, locale) in formats) {
                try {
                    val format = DateTimeFormatter.ofPattern(pattern, locale)
                    val ldt = LocalDateTime.parse(cleanedTs, format)
                    return ldt.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                }
            }

            Log.w(TAG, "Could not parse HTML timestamp: $timestampText")
            return 0L
        }

        private fun parseWatchTime(text: String): Long? {
            val match =
                Regex(
                    "Watch time:\\s*(?:(\\d+)\\s*(?:minutes?|mins?))?\\s*(?:(\\d+)\\s*(?:seconds?|secs?))?",
                    RegexOption.IGNORE_CASE,
                ).find(text)
            if (match == null) return null

            val minutes = match.groupValues[1].toIntOrNull() ?: 0
            val seconds = match.groupValues[2].toIntOrNull() ?: 0

            if (minutes == 0 && seconds == 0) return null

            return (minutes * 60L + seconds) * 1000L
        }

        private fun sanitizeString(value: String): String = value.trim().take(MAX_STRING_LENGTH)

        private fun getFileName(
            context: Context,
            uri: Uri,
        ): String? =
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get file name", e)
                null
            }

        fun resetState() {
            _importState.value = ImportState.Idle
        }
    }

private fun JsonReader.nextStringOrNull(): String? =
    if (peek() == JsonReader.Token.NULL) {
        nextNull<Unit>()
        null
    } else {
        nextString()
    }
