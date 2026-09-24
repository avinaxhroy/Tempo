package me.avinas.tempo.data.deezer

import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming-friendly reader for the official Deezer personal-data XLSX export.
 *
 * Deezer stores listening history in the worksheet named "10_listeningHistory".
 * Only the fields required by Tempo are read; IP address and device/platform
 * information are deliberately ignored.
 */
object DeezerXlsxParser {
    private const val HISTORY_SHEET = "10_listeningHistory"
    private const val MAX_XML_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_SHARED_STRINGS = 2_000_000
    private const val MAX_HISTORY_ROWS = 2_000_000
    private const val MAX_STRING_LENGTH = 500
    private const val MAX_CELL_TEXT_LENGTH = 100_000
    private const val MAX_COLUMNS_PER_ROW = 128
    private const val MIN_PARSE_MEMORY_BUDGET_BYTES = 16L * 1024 * 1024
    private const val MAX_PARSE_MEMORY_BUDGET_BYTES = 160L * 1024 * 1024
    private const val EXCEL_1900_EPOCH_MILLIS = -2209161600000L // 1899-12-30 00:00:00 UTC (Excel 1900 leap-year bug compensation)
    private const val EXCEL_1904_EPOCH_MILLIS = -2082844800000L // 1904-01-01 00:00:00 UTC
    private const val MILLIS_PER_DAY = 86_400_000.0

    private val deezerDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val isrcRegex = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

    data class Entry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val isrc: String?,
        val listenedAtMillis: Long,
        val msPlayed: Long,
    )

    data class ParseResult(
        val entries: List<Entry>,
        val malformedRows: Int,
    )

    private data class SharedStringsResult(
        val values: List<String>,
        val estimatedBytes: Long,
    )

    private data class WorkbookMetadata(
        val historySheetPath: String,
        val is1904DateSystem: Boolean,
    )

    fun parse(
        file: File,
        cancellationCheck: (() -> Unit)? = null,
    ): ParseResult {
        ZipFile(file).use { zip ->
            cancellationCheck?.invoke()
            val memoryBudgetBytes = parseMemoryBudgetBytes()
            val workbookMetadata = findWorkbookMetadata(zip)
            val sharedStrings = readSharedStrings(zip, cancellationCheck, memoryBudgetBytes)
            val sheetEntry = zip.getEntry(workbookMetadata.historySheetPath)
                ?: throw IllegalArgumentException("Deezer listening-history worksheet is missing")
            validateEntrySize(sheetEntry.size, workbookMetadata.historySheetPath)

            zip.getInputStream(sheetEntry).use { raw ->
                return parseHistorySheet(
                    LimitedInputStream(raw, MAX_XML_ENTRY_BYTES),
                    sharedStrings.values,
                    cancellationCheck,
                    memoryBudgetBytes,
                    sharedStrings.estimatedBytes,
                    workbookMetadata.is1904DateSystem,
                )
            }
        }
    }

    private fun findWorkbookMetadata(zip: ZipFile): WorkbookMetadata {
        val workbook = zip.getEntry("xl/workbook.xml")
            ?: throw IllegalArgumentException("Invalid XLSX: workbook.xml is missing")
        validateEntrySize(workbook.size, workbook.name)

        var exactRelationshipId: String? = null
        var fallbackRelationshipId: String? = null
        var is1904DateSystem = false
        zip.getInputStream(workbook).use { raw ->
            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    val name = localName ?: qName
                    if (name == "workbookPr") {
                        val date1904Attr = attributes.getValue("date1904")
                        if (date1904Attr == "1" || date1904Attr.equals("true", ignoreCase = true)) {
                            is1904DateSystem = true
                        }
                    } else if (name == "sheet") {
                        val sheetName = attributes.getValue("name")?.trim().orEmpty()
                        val id =
                            attributes.getValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id",
                            ) ?: attributes.getValue("r:id")

                        if (sheetName.equals(HISTORY_SHEET, ignoreCase = true)) {
                            exactRelationshipId = id
                        } else if (
                            fallbackRelationshipId == null &&
                            sheetName.endsWith("_listeningHistory", ignoreCase = true)
                        ) {
                            // Deezer currently calls the sheet 10_listeningHistory. Match the
                            // semantic suffix too so a future sheet-order change (e.g. 11_) does
                            // not break otherwise identical official exports.
                            fallbackRelationshipId = id
                        }
                    }
                }
            })
        }

        val targetId = exactRelationshipId ?: fallbackRelationshipId
            ?: throw IllegalArgumentException(
                "This XLSX does not contain Deezer's $HISTORY_SHEET listening-history sheet",
            )

        val rels = zip.getEntry("xl/_rels/workbook.xml.rels")
            ?: throw IllegalArgumentException("Invalid XLSX: workbook relationships are missing")
        validateEntrySize(rels.size, rels.name)

        var target: String? = null
        zip.getInputStream(rels).use { raw ->
            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if ((localName == "Relationship" || qName == "Relationship") &&
                        attributes.getValue("Id") == targetId
                    ) {
                        target = attributes.getValue("Target")
                    }
                }
            })
        }

        val rawTarget = target
            ?: throw IllegalArgumentException("Invalid XLSX: listening-history relationship is missing")
        val normalized = rawTarget.replace('\\', '/').removePrefix("/")
        if (normalized.split('/').any { it == ".." }) {
            throw IllegalArgumentException("Invalid XLSX worksheet path")
        }
        val sheetPath = if (normalized.startsWith("xl/")) normalized else "xl/$normalized"
        return WorkbookMetadata(sheetPath, is1904DateSystem)
    }

    private fun readSharedStrings(
        zip: ZipFile,
        cancellationCheck: (() -> Unit)?,
        memoryBudgetBytes: Long,
    ): SharedStringsResult {
        val entry = zip.getEntry("xl/sharedStrings.xml")
            ?: return SharedStringsResult(emptyList(), 0L)
        validateEntrySize(entry.size, entry.name)

        val strings = ArrayList<String>()
        var estimatedBytes = 0L
        zip.getInputStream(entry).use { raw ->
            var insideItem = false
            var insideText = false
            var builder: StringBuilder? = null

            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    when (localName ?: qName) {
                        "si" -> {
                            if (strings.size % 1_000 == 0) cancellationCheck?.invoke()
                            if (strings.size >= MAX_SHARED_STRINGS) {
                                throw IllegalArgumentException("Deezer XLSX contains too many shared strings")
                            }
                            insideItem = true
                            builder = StringBuilder()
                        }
                        "t" -> if (insideItem) insideText = true
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (insideItem && insideText) {
                        val target = builder ?: return
                        if (target.length + length > MAX_CELL_TEXT_LENGTH) {
                            throw IllegalArgumentException("XLSX text cell exceeds safe size limit")
                        }
                        target.append(ch, start, length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    when (localName ?: qName) {
                        "t" -> insideText = false
                        "si" -> {
                            val value = builder?.toString().orEmpty()
                            estimatedBytes += estimateStringMemoryBytes(value)
                            if (estimatedBytes > memoryBudgetBytes) {
                                throw IllegalArgumentException(
                                    "Deezer export is too large to parse safely on this device",
                                )
                            }
                            strings.add(value)
                            builder = null
                            insideItem = false
                        }
                    }
                }
            })
        }
        return SharedStringsResult(strings, estimatedBytes)
    }

    private fun parseHistorySheet(
        input: InputStream,
        sharedStrings: List<String>,
        cancellationCheck: (() -> Unit)?,
        memoryBudgetBytes: Long,
        initialEstimatedBytes: Long,
        is1904DateSystem: Boolean = false,
    ): ParseResult {
        val entries = ArrayList<Entry>()
        var estimatedBytes = initialEstimatedBytes
        var malformedRows = 0
        var headers: Map<String, Int>? = null
        var rowCount = 0

        var currentRow = LinkedHashMap<Int, String>()
        var currentCellColumn = -1
        var currentCellType: String? = null
        var collectingValue = false
        var cellValue = StringBuilder()

        parseXml(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (localName ?: qName) {
                    "row" -> {
                        rowCount++
                        if (rowCount % 100 == 0) cancellationCheck?.invoke()
                        if (rowCount > MAX_HISTORY_ROWS) {
                            throw IllegalArgumentException("Deezer listening history is too large")
                        }
                        currentRow = LinkedHashMap()
                    }
                    "c" -> {
                        val column = columnIndex(attributes.getValue("r").orEmpty())
                        currentCellColumn =
                            if (column in 0 until MAX_COLUMNS_PER_ROW) column else -1
                        currentCellType = attributes.getValue("t")
                        cellValue = StringBuilder()
                    }
                    "v", "t" -> collectingValue = currentCellColumn >= 0
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collectingValue) {
                    if (cellValue.length + length > MAX_CELL_TEXT_LENGTH) {
                        throw IllegalArgumentException("XLSX text cell exceeds safe size limit")
                    }
                    cellValue.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName ?: qName) {
                    "v", "t" -> collectingValue = false
                    "c" -> {
                        if (currentCellColumn >= 0) {
                            currentRow[currentCellColumn] = decodeCell(
                                cellValue.toString(),
                                currentCellType,
                                sharedStrings,
                            )
                        }
                        currentCellColumn = -1
                        currentCellType = null
                    }
                    "row" -> {
                        val activeHeaders = headers
                        if (activeHeaders == null) {
                            val candidate = currentRow.entries
                                .associate { normalizeHeader(it.value) to it.key }
                            if (REQUIRED_HEADER_GROUPS.all { aliases ->
                                    aliases.any { normalizeHeader(it) in candidate }
                                }
                            ) {
                                headers = candidate
                            }
                        } else if (currentRow.isNotEmpty() && currentRow.values.any { it.isNotBlank() }) {
                            val parsed = parseDataRow(currentRow, activeHeaders, is1904DateSystem)
                            if (parsed != null) {
                                estimatedBytes += estimateEntryMemoryBytes(parsed)
                                if (estimatedBytes > memoryBudgetBytes) {
                                    throw IllegalArgumentException(
                                        "Deezer listening history is too large to import safely on this device",
                                    )
                                }
                                entries.add(parsed)
                            } else {
                                malformedRows++
                            }
                        }
                    }
                }
            }
        })

        if (headers == null) {
            throw IllegalArgumentException(
                "Deezer listening-history columns were not found in $HISTORY_SHEET",
            )
        }
        return ParseResult(entries, malformedRows)
    }

    private fun parseDataRow(
        row: Map<Int, String>,
        headers: Map<String, Int>,
        is1904DateSystem: Boolean = false,
    ): Entry? {
        fun value(vararg aliases: String): String {
            val column = aliases
                .asSequence()
                .map(::normalizeHeader)
                .mapNotNull(headers::get)
                .firstOrNull()
            return column?.let { row[it] }.orEmpty().trim()
        }

        val title = sanitize(value("Song Title", "Titre", "Titre du morceau", "Titre de la chanson"))
        val artist = sanitize(value("Artist", "Artiste", "Artists", "Artistes"))
        if (title.isBlank() || artist.isBlank()) return null

        val dateValue = value("Date", "Date d'écoute", "Date de l'écoute", "Date d'ecoute", "Date de l'ecoute")
        val timestamp = parseDate(dateValue, is1904DateSystem)
        if (timestamp <= 0L) return null

        val listeningSeconds = parseListeningSeconds(
            value("Listening Time", "Temps d'écoute", "Durée d'écoute", "Duree d'ecoute", "Écoute"),
        ) ?: return null
        if (!listeningSeconds.isFinite()) return null
        val normalizedListeningSeconds =
            if (listeningSeconds == -1.0) {
                // A real official Deezer export contains -1 as a listening-time
                // sentinel. It cannot represent a usable positive play duration,
                // so keep the row structurally valid and let the import's <30s
                // rule ignore it instead of reporting the official row as malformed.
                0.0
            } else {
                listeningSeconds
            }
        if (normalizedListeningSeconds < 0.0) return null
        val msPlayed = (normalizedListeningSeconds * 1000.0)
            .coerceAtMost(24.0 * 60.0 * 60.0 * 1000.0)
            .toLong()

        return Entry(
            trackName = title,
            artistName = artist,
            albumName = sanitize(value("Album Title", "Album", "Titre de l'album")).takeIf { it.isNotBlank() },
            isrc = normalizeIsrc(value("ISRC")),
            listenedAtMillis = timestamp,
            msPlayed = msPlayed,
        )
    }

    private fun parseMemoryBudgetBytes(): Long {
        val heapFraction = Runtime.getRuntime().maxMemory() * 2L / 5L
        return heapFraction.coerceIn(
            MIN_PARSE_MEMORY_BUDGET_BYTES,
            MAX_PARSE_MEMORY_BUDGET_BYTES,
        )
    }

    private fun estimateStringMemoryBytes(value: String): Long =
        48L + value.length.toLong() * 2L

    private fun estimateEntryMemoryBytes(entry: Entry): Long {
        val stringChars =
            entry.trackName.length +
                entry.artistName.length +
                (entry.albumName?.length ?: 0) +
                (entry.isrc?.length ?: 0)
        // Conservative estimate for the Entry object, ArrayList reference and
        // potentially materialized String slices.
        return 112L + stringChars.toLong() * 2L
    }

    private fun parseListeningSeconds(value: String): Double? {
        val clean = value.trim().replace(',', '.')
        clean.toDoubleOrNull()?.let { return it }

        val parts = clean.split(':')
        if (parts.size in 2..3) {
            val numbers = parts.map { it.toDoubleOrNull() ?: return null }
            return if (numbers.size == 3) {
                numbers[0] * 3600.0 + numbers[1] * 60.0 + numbers[2]
            } else {
                numbers[0] * 60.0 + numbers[1]
            }
        }
        return null
    }

    internal fun parseDate(value: String, is1904DateSystem: Boolean = false): Long {
        if (value.isBlank()) return 0L

        runCatching {
            return LocalDateTime.parse(value.trim(), deezerDateFormatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
        runCatching { return Instant.parse(value.trim()).toEpochMilli() }
        runCatching {
            return java.time.OffsetDateTime.parse(value.trim())
                .toInstant()
                .toEpochMilli()
        }

        // Some spreadsheet writers store dates as Excel serial numbers.
        val serial = value.trim().toDoubleOrNull()
        if (serial != null && serial > 0.0) {
            val epochMillis = if (is1904DateSystem) {
                EXCEL_1904_EPOCH_MILLIS
            } else if (serial >= 61.0) {
                EXCEL_1900_EPOCH_MILLIS
            } else {
                -2209075200000L // 1899-12-31 00:00:00 UTC for pre-March 1900
            }
            return (epochMillis + serial * MILLIS_PER_DAY).toLong()
        }
        return 0L
    }

    private fun decodeCell(
        rawValue: String,
        cellType: String?,
        sharedStrings: List<String>,
    ): String = when (cellType) {
        "s" -> rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
        else -> rawValue
    }

    private fun columnIndex(reference: String): Int {
        if (reference.isBlank()) return -1
        var result = 0
        var letters = 0
        for (char in reference) {
            if (!char.isLetter()) break
            letters++
            if (letters > 3) return -1 // XLSX maximum column is XFD.
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return if (letters == 0) -1 else result - 1
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace("\u00a0", " ").replace(Regex("\\s+"), " ")

    private fun sanitize(value: String): String = value.trim().take(MAX_STRING_LENGTH)

    internal fun normalizeIsrc(value: String): String? {
        val normalized = value
            .trim()
            .uppercase()
            .replace("-", "")
            .replace(" ", "")
        return normalized.takeIf(isrcRegex::matches)
    }

    private fun validateEntrySize(size: Long, name: String) {
        if (size > MAX_XML_ENTRY_BYTES) {
            throw IllegalArgumentException("XLSX entry is too large: $name")
        }
    }

    private fun parseXml(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        // Defense in depth: never resolve a system/public entity from a user-selected XLSX.
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
            InputSource(StringReader(""))
        }
        reader.parse(InputSource(input))
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var readBytes = 0L

        private fun account(count: Int) {
            if (count <= 0) return
            readBytes += count
            if (readBytes > maxBytes) {
                throw IllegalArgumentException("XLSX XML entry exceeds safe size limit")
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) account(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also(::account)
    }

    private val REQUIRED_HEADER_GROUPS = listOf(
        listOf("Song Title", "Titre", "Titre du morceau", "Titre de la chanson"),
        listOf("Artist", "Artiste", "Artists", "Artistes"),
        listOf("Listening Time", "Temps d'écoute", "Durée d'écoute", "Duree d'ecoute", "Écoute"),
        listOf("Date", "Date d'écoute", "Date de l'écoute", "Date d'ecoute", "Date de l'ecoute"),
    )
}
