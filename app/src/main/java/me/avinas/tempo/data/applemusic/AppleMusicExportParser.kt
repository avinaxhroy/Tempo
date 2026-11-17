package me.avinas.tempo.data.applemusic

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reader for the Apple Music privacy export (privacy.apple.com → "Apple Media Services information").
 *
 * Layout Apple actually ships, verified against real exports and independent importers:
 *
 * ```
 * Apple Media Services information.zip          (outer; may appear as "- Part 1 of N.zip")
 * └── Apple_Media_Services.zip
 *     └── Apple Music Activity/
 *         ├── Apple Music Play Activity.csv                    <- the playback log
 *         ├── Apple Music - Track Play History.csv             <- "Artist - Track" strings
 *         └── Apple Music Library Tracks.json                  <- Title/Artist/Album objects
 * ```
 *
 * Nesting depth and part count both vary, so [extractSources] walks ZIPs recursively and every
 * matching leaf is parsed and concatenated.
 *
 * ## Why artist recovery is a join, not a column
 *
 * `Container Artist Name` is present in the play-activity header but is ALWAYS empty in real
 * exports (confirmed by Gudsfile/tracksy, which hardcodes `'Unknown Artist'`, and by
 * srevinsaju/apple-music-scrobble-importer, which joins sibling files to recover ~97%).
 * So an artist is recovered from the sibling files when possible and left as
 * [UNKNOWN_ARTIST] otherwise, for the enrichment pipeline to fill in by play count.
 */
class AppleMusicExportParser {

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"

        /** Titles are compared case- and whitespace-insensitively across Apple's own files. */
        private fun normaliseKey(title: String): String =
            title.trim().lowercase().replace(Regex("\\s+"), " ")

        // Real file names inside "Apple Music Activity/".
        internal const val PLAY_ACTIVITY_CSV = "Apple Music Play Activity.csv"
        internal const val TRACK_HISTORY_CSV = "Apple Music - Track Play History.csv"
        internal const val LIBRARY_JSON = "Apple Music Library Tracks.json"

        // Column headers as Apple emits them. Matched case/space-insensitively because the
        // header is localised in some regions.
        private const val COL_SONG = "song name"
        private const val COL_ALBUM = "album name"
        private const val COL_ARTIST = "container artist name"
        private const val COL_MEDIA_TYPE = "media type"
        private const val COL_PLAY_DURATION = "play duration milliseconds"
        private const val COL_MEDIA_DURATION = "media duration in milliseconds"
        private const val COL_START = "event start timestamp"
        private const val COL_ORIGIN = "container origin type"
        private const val COL_TRACK_NAME = "track name"

        private const val AUDIO_MEDIA_TYPE = "AUDIO"
        private const val STREAM_RADIO_ORIGIN = "STREAM_RADIO_STATION"

        private const val MAX_ZIP_DEPTH = 4
        private const val MAX_NESTED_ZIP_BYTES = 256L * 1024 * 1024
        private const val MAX_LEAF_BYTES = 512L * 1024 * 1024

        /** Guards against a hostile timestamp reaching cross-source reconciliation. */
        private const val MAX_FUTURE_SLACK_MS = 365L * 24 * 60 * 60 * 1000

        /** Nothing was played on Apple Music before the iTunes Store existed: older rows are junk. */
        private val MIN_TIMESTAMP_MS = LocalDate.of(2001, 1, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        /** A "song" longer than a day means the duration column is not what this build expects. */
        private const val MAX_MEDIA_DURATION_MS = 24L * 60 * 60 * 1000

        /**
         * Distinct strings worth canonicalising while reading plays. Past this the pool costs
         * more than the copies it saves, so later rows keep their own.
         */
        private const val MAX_INTERNED_STRINGS = 50_000

        private val LOCAL_TIMESTAMP_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        )
    }

    /** One play-activity row, after validation and artist recovery. */
    data class Entry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val playedAtMillis: Long,
        val msPlayed: Long,
        /** Track length reported by the export, when this build of Apple's export carries it. */
        val mediaDurationMs: Long?,
    )

    data class ParseResult(
        val entries: List<Entry>,
        val malformedRows: Int,
        val skippedNonAudioRows: Int,
        /** Rows whose artist came from a sibling file rather than the (always empty) column. */
        val artistRecoveredRows: Int,
    )

    private data class Source(val name: String, val file: File)

    /**
     * Parses [input], extracting intermediate files into [tempDir] (caller owns cleanup).
     *
     * @throws IllegalArgumentException when the archive carries no play-activity CSV at all.
     */
    fun parse(
        input: File,
        tempDir: File,
        cancellationCheck: () -> Unit = {},
    ): ParseResult {
        val sources = extractSources(input, 0, tempDir, cancellationCheck)

        val playActivityCsvs = sources.filter { it.name.equals(PLAY_ACTIVITY_CSV, ignoreCase = true) }
        // A user may hand over a bare CSV that was renamed by their browser or mail client.
        val resolvedPlayActivity =
            playActivityCsvs.ifEmpty {
                sources.filter {
                    it.name.endsWith(".csv", ignoreCase = true) &&
                        !it.name.equals(TRACK_HISTORY_CSV, ignoreCase = true)
                }
            }
        if (resolvedPlayActivity.isEmpty()) {
            throw IllegalArgumentException("This file does not contain Apple Music play activity")
        }

        val artistIndex = buildArtistIndex(sources, cancellationCheck)
        return parsePlayActivity(resolvedPlayActivity, artistIndex, cancellationCheck)
    }

    // ---------------------------------------------------------------------------------
    // Archive walking
    // ---------------------------------------------------------------------------------

    /**
     * Flattens [input] into the CSV/JSON leaves it contains, recursing through nested ZIPs.
     *
     * Everything is materialised on disk rather than held in memory: a real export can be well
     * over 100 MB, and Apple wraps a whole archive inside another one.
     */
    private fun extractSources(
        input: File,
        depth: Int,
        tempDir: File,
        cancellationCheck: () -> Unit,
    ): List<Source> {
        cancellationCheck()
        if (depth > MAX_ZIP_DEPTH) return emptyList()

        if (!isZip(input)) {
            return listOf(Source(input.name, input))
        }

        val collected = ArrayList<Source>()
        ZipFile(input).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                cancellationCheck()
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                val name = entry.name.substringAfterLast('/').ifEmpty { entry.name }
                val lower = name.lowercase()
                when {
                    lower.endsWith(".zip") -> {
                        val nested = File.createTempFile("apple_nested_", ".zip", tempDir)
                        try {
                            copyLimited(zip.getInputStream(entry), nested, MAX_NESTED_ZIP_BYTES, cancellationCheck)
                            collected += extractSources(nested, depth + 1, tempDir, cancellationCheck)
                        } finally {
                            if (!nested.delete()) nested.deleteOnExit()
                        }
                    }

                    lower.endsWith(".csv") || lower.endsWith(".json") -> {
                        // Only the files the importer can actually use are materialised, so a
                        // multi-hundred-MB export does not get copied wholesale.
                        if (!isRelevantLeaf(lower)) continue
                        val leaf = File.createTempFile("apple_leaf_", ".${
                            if (lower.endsWith(".csv")) "csv" else "json"
                        }", tempDir)
                        copyLimited(zip.getInputStream(entry), leaf, MAX_LEAF_BYTES, cancellationCheck)
                        collected += Source(name, leaf)
                    }
                }
            }
        }
        return collected
    }

    private fun isRelevantLeaf(lowerName: String): Boolean =
        lowerName == PLAY_ACTIVITY_CSV.lowercase() ||
            lowerName == TRACK_HISTORY_CSV.lowercase() ||
            lowerName == LIBRARY_JSON.lowercase()

    private fun isZip(file: File): Boolean {
        if (file.name.endsWith(".zip", ignoreCase = true)) return true
        return try {
            file.inputStream().use { stream ->
                val signature = ByteArray(4)
                if (stream.read(signature) < 4) return false
                // "PK\x03\x04" (newer archives) or "PK\x05\x06" (empty archive).
                signature[0] == 'P'.code.toByte() &&
                    signature[1] == 'K'.code.toByte() &&
                    (signature[2] == 3.toByte() || signature[2] == 5.toByte())
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun copyLimited(
        source: InputStream,
        destination: File,
        limitBytes: Long,
        cancellationCheck: () -> Unit,
    ) {
        source.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    cancellationCheck()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limitBytes) {
                        throw IOException("This Apple Music export contains an oversized file")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Artist recovery
    // ---------------------------------------------------------------------------------

    /**
     * Artist/album lookup recovered from the sibling files, keyed by normalised title.
     *
     * A title is not an identity: "Intro", "Interlude" and "Home" live on thousands of albums, and
     * the play-activity CSV never says which artist a row belongs to. When the sibling files
     * disagree about who owns a title it is dropped rather than resolved to a winner, because a
     * wrong artist both mislabels the play and makes `TrackResolver` merge two different songs
     * into one track, while [UNKNOWN_ARTIST] is repaired later by enrichment.
     */
    private class ArtistIndex {
        private val historyArtist = TitleValues()
        private val libraryArtist = TitleValues()
        private val libraryAlbum = TitleValues()

        /** Library metadata outranks the play-history strings. */
        fun artistFor(normalisedTitle: String): String? =
            libraryArtist.value(normalisedTitle) ?: historyArtist.value(normalisedTitle)

        fun albumFor(normalisedTitle: String): String? = libraryAlbum.value(normalisedTitle)

        fun addHistoryArtist(title: String, artist: String) {
            historyArtist.put(title, artist)
        }

        fun addLibraryTrack(title: String, artist: String?, album: String?) {
            if (!artist.isNullOrBlank()) libraryArtist.put(title, artist)
            if (!album.isNullOrBlank()) libraryAlbum.put(title, album)
        }
    }

    /** Remembers the first value a title maps to and forgets the title once a value differs. */
    private class TitleValues {
        private val values = HashMap<String, String>()
        private val conflicting = HashSet<String>()

        fun put(title: String, value: String) {
            val key = AppleMusicExportParser.normaliseKey(title)
            val clean = value.trim()
            if (clean.isEmpty() || key in conflicting) return
            val existing = values[key]
            when {
                existing == null -> values[key] = clean
                existing.equals(clean, ignoreCase = true) -> Unit
                else -> {
                    values.remove(key)
                    conflicting += key
                }
            }
        }

        fun value(normalisedTitle: String): String? = values[normalisedTitle]
    }

    private fun buildArtistIndex(
        sources: List<Source>,
        cancellationCheck: () -> Unit,
    ): ArtistIndex {
        val index = ArtistIndex()

        // "Artist - Track" strings. Lower priority than the library JSON but far wider
        // coverage, since it records everything played rather than everything saved.
        sources
            .filter { it.name.equals(TRACK_HISTORY_CSV, ignoreCase = true) }
            .forEach { source ->
                forEachCsvRow(source.file, cancellationCheck) { header, row ->
                    val trackColumn = header.indexOf(COL_TRACK_NAME)
                    if (trackColumn < 0) return@forEachCsvRow
                    val combined = row.getOrNull(trackColumn)?.trim().orEmpty()
                    if (combined.isEmpty()) return@forEachCsvRow
                    // Split on the FIRST " - " only: track titles legitimately contain " - ".
                    val separator = combined.indexOf(" - ")
                    if (separator <= 0) return@forEachCsvRow
                    val artist = combined.substring(0, separator).trim()
                    val title = combined.substring(separator + 3).trim()
                    if (artist.isEmpty() || title.isEmpty()) return@forEachCsvRow
                    index.addHistoryArtist(title, artist)
                }
            }

        sources
            .filter { it.name.equals(LIBRARY_JSON, ignoreCase = true) }
            .forEach { source -> readLibraryJson(source.file, index) }

        return index
    }

    /**
     * Fills [index] one library entry at a time.
     *
     * org.json has no incremental reader, so the text itself is loaded whole, but the entries
     * are not: turning a large library into live `JSONObject`s all at once costs several times
     * the text, and that spike lands right before the play-activity file is parsed.
     */
    private fun readLibraryJson(file: File, index: ArtistIndex) {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return
        }

        try {
            val tokener = JSONTokener(text)
            if (tokener.nextClean() != '[') return
            while (true) {
                val next = tokener.nextClean()
                if (next == ']' || next == '\u0000') return
                tokener.back()
                val item = tokener.nextValue() as? JSONObject ?: continue
                val title = item.optString("Title").trim()
                if (title.isEmpty()) continue
                index.addLibraryTrack(
                    title = title,
                    artist = item.optString("Artist").takeIf { it.isNotBlank() },
                    album = item.optString("Album").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            // A malformed or unexpected library file must not sink the whole import: the
            // play-activity CSV is the payload, artist recovery is best-effort.
            return
        }
    }

    private fun normaliseKey(title: String): String = Companion.normaliseKey(title)

    // ---------------------------------------------------------------------------------
    // Play activity
    // ---------------------------------------------------------------------------------

    private fun parsePlayActivity(
        sources: List<Source>,
        artistIndex: ArtistIndex,
        cancellationCheck: () -> Unit,
    ): ParseResult {
        val entries = ArrayList<Entry>()
        // Every CSV cell arrives as a fresh String, and a play list repeats the same track
        // many times over: 300k plays would otherwise hold 300k copies of a few thousand
        // titles. Keeping one copy per distinct value cuts the retained rows several-fold.
        val interned = HashMap<String, String>()
        fun intern(value: String): String =
            if (interned.size >= MAX_INTERNED_STRINGS) value else interned.getOrPut(value) { value }
        var malformedRows = 0
        var skippedNonAudio = 0
        var artistRecovered = 0
        var sawPlayActivityHeader = false

        sources.forEach { source ->
            forEachCsvRow(
                source.file,
                cancellationCheck,
                onHeader = { header ->
                    // Distinguishes an empty export (a real CSV with a real header but no plays)
                    // from a file whose columns this version cannot read.
                    if (header.indexOf(COL_SONG) >= 0 &&
                        header.indexOf(COL_START) >= 0 &&
                        header.indexOf(COL_PLAY_DURATION) >= 0
                    ) {
                        sawPlayActivityHeader = true
                    }
                },
            ) { header, row ->
                val songColumn = header.indexOf(COL_SONG)
                val startColumn = header.indexOf(COL_START)
                val durationColumn = header.indexOf(COL_PLAY_DURATION)
                if (songColumn < 0 || startColumn < 0 || durationColumn < 0) {
                    // Wrong CSV (or a localised header this version cannot read). Skip the file
                    // rather than counting every one of its rows as malformed.
                    return@forEachCsvRow
                }

                val mediaTypeColumn = header.indexOf(COL_MEDIA_TYPE)
                if (mediaTypeColumn >= 0) {
                    val mediaType = row.getOrNull(mediaTypeColumn)?.trim().orEmpty()
                    if (mediaType.isNotEmpty() && !mediaType.equals(AUDIO_MEDIA_TYPE, ignoreCase = true)) {
                        skippedNonAudio++
                        return@forEachCsvRow
                    }
                }

                val originColumn = header.indexOf(COL_ORIGIN)
                if (originColumn >= 0) {
                    val origin = row.getOrNull(originColumn)?.trim().orEmpty()
                    if (origin.equals(STREAM_RADIO_ORIGIN, ignoreCase = true)) {
                        skippedNonAudio++
                        return@forEachCsvRow
                    }
                }

                val title = row.getOrNull(songColumn)?.trim().orEmpty()
                val playedAt = row.getOrNull(startColumn)?.let(::parseTimestamp)
                if (title.isEmpty() || playedAt == null) {
                    malformedRows++
                    return@forEachCsvRow
                }

                // Apple logs a negative duration for some scrubs and interruptions.
                val msPlayed = row.getOrNull(durationColumn)
                    ?.trim()
                    ?.toDoubleOrNull()
                    ?.toLong()
                    ?.coerceAtLeast(0L)
                    ?: 0L

                val key = normaliseKey(title)
                val columnArtist = header.indexOf(COL_ARTIST)
                    .let { if (it >= 0) row.getOrNull(it)?.trim().orEmpty() else "" }
                    .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                val recoveredArtist = artistIndex.artistFor(key)
                val artist = columnArtist ?: recoveredArtist
                if (columnArtist == null && recoveredArtist != null) artistRecovered++

                val mediaDurationColumn = header.indexOf(COL_MEDIA_DURATION)
                val mediaDurationMs = mediaDurationColumn
                    .takeIf { it >= 0 }
                    ?.let { row.getOrNull(it)?.trim()?.toDoubleOrNull()?.toLong() }
                    // A bogus length would make a completed listen look like a skip and would
                    // bake a nonsense duration into the track row.
                    ?.takeIf { it in 1..MAX_MEDIA_DURATION_MS }

                val album = header.indexOf(COL_ALBUM)
                    .let { if (it >= 0) row.getOrNull(it)?.trim().orEmpty() else "" }
                    .takeIf { it.isNotEmpty() }
                    ?: artistIndex.albumFor(key)

                entries += Entry(
                    trackName = intern(title),
                    artistName = intern(artist ?: UNKNOWN_ARTIST),
                    albumName = album?.let { intern(it) },
                    playedAtMillis = playedAt,
                    msPlayed = msPlayed,
                    mediaDurationMs = mediaDurationMs,
                )
            }
        }

        if (!sawPlayActivityHeader) {
            throw IllegalArgumentException(
                "The Apple Music play-activity columns in this export are not supported",
            )
        }

        return ParseResult(
            entries = entries,
            malformedRows = malformedRows,
            skippedNonAudioRows = skippedNonAudio,
            artistRecoveredRows = artistRecovered,
        )
    }

    /** Accepts the ISO-8601 instants Apple writes (`2025-03-11T00:40:20.139Z`) and near misses. */
    internal fun parseTimestamp(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val millis = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: LOCAL_TIMESTAMP_FORMATTERS.firstNotNullOfOrNull { formatter ->
                runCatching { LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli() }
                    .getOrNull()
            }
            ?: value.toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
            ?: return null

        // Reject absurd values instead of letting them poison cross-source dedup: a row stamped
        // 1970 (or before) would otherwise become the "first play" of the whole library.
        return millis.takeIf {
            it in MIN_TIMESTAMP_MS..(System.currentTimeMillis() + MAX_FUTURE_SLACK_MS)
        }
    }

    // ---------------------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------------------

    /** Reads [file] as CSV, handing each data row its header map alongside the raw cells. */
    private fun forEachCsvRow(
        file: File,
        cancellationCheck: () -> Unit,
        onHeader: (Header) -> Unit = {},
        onRow: (Header, List<String>) -> Unit,
    ) {
        var header: Header? = null
        file.inputStream().use { stream ->
            CsvReader.read(stream, cancellationCheck) { cells ->
                val current = header
                if (current == null) {
                    // First non-blank line is the header. Empty leading columns are kept so
                    // column indexes stay aligned with the data rows.
                    Header(cells).also {
                        header = it
                        onHeader(it)
                    }
                } else {
                    onRow(current, cells)
                }
            }
        }
    }

    /** Column-name → index, with a small cache so every row does not re-normalise headers. */
    internal class Header(names: List<String>) {
        private val indexByName: Map<String, Int> = buildMap {
            names.forEachIndexed { position, raw ->
                val key = normalise(raw)
                if (key.isNotEmpty()) putIfAbsent(key, position)
            }
        }

        // Callers look up the same handful of Apple column names once per row.
        private val resolved = HashMap<String, Int>()

        fun indexOf(name: String): Int = resolved.getOrPut(name) { indexByName[normalise(name)] ?: -1 }

        private companion object {
            fun normalise(raw: String): String =
                raw.removePrefix("\uFEFF")
                    .trim()
                    .lowercase()
                    .replace(Regex("\\s+"), " ")
        }
    }
}

/**
 * Streaming RFC 4180 reader.
 *
 * Apple quotes whole fields and its titles routinely contain commas ("Hello, Goodbye"),
 * apostrophes and commas inside album names, so a naive `split(",")` would corrupt rows.
 * Quoted fields may also contain embedded newlines.
 */
internal object CsvReader {

    fun read(
        input: InputStream,
        cancellationCheck: () -> Unit,
        onRow: (List<String>) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val field = StringBuilder()
        val row = ArrayList<String>()
        var inQuotes = false
        var quotePending = false
        var skipNextLineFeed = false
        var atStart = true
        var charsSinceCheck = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.size > 1 || row[0].isNotEmpty()) {
                onRow(ArrayList(row))
            }
            row.clear()
        }

        while (true) {
            if (++charsSinceCheck >= 65_536) {
                charsSinceCheck = 0
                cancellationCheck()
            }

            val code = reader.read()
            if (code < 0) break
            val ch = code.toChar()

            if (atStart) {
                atStart = false
                if (ch == '\uFEFF') continue
            }

            if (inQuotes) {
                if (quotePending) {
                    quotePending = false
                    if (ch == '"') {
                        field.append('"')
                        continue
                    }
                    inQuotes = false
                    // Fall through: the character that closed the quote must still be handled.
                } else if (ch == '"') {
                    quotePending = true
                    continue
                } else {
                    field.append(ch)
                    continue
                }
            }

            when (ch) {
                '"' -> inQuotes = true
                ',' -> endField()
                '\r' -> {
                    endRow()
                    skipNextLineFeed = true
                }
                '\n' -> {
                    if (skipNextLineFeed) skipNextLineFeed = false else endRow()
                }
                else -> {
                    skipNextLineFeed = false
                    field.append(ch)
                }
            }
        }

        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    }
}
