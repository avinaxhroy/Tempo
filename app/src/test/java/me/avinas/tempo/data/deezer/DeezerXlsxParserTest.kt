package me.avinas.tempo.data.deezer

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerXlsxParserTest {

    @Test
    fun parsesOfficialListeningHistoryShape() {
        val file = createWorkbook(includeHistory = true)
        try {
            val result = DeezerXlsxParser.parse(file)

            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)

            val entry = result.entries.single()
            assertEquals("Never Gonna Give You Up", entry.trackName)
            assertEquals("Rick Astley", entry.artistName)
            assertEquals("Whenever You Need Somebody", entry.albumName)
            assertEquals("GBAYE8800243", entry.isrc)
            assertEquals(213_000L, entry.msPlayed)
            assertEquals(
                Instant.parse("2024-10-24T23:00:00Z").toEpochMilli(),
                entry.listenedAtMillis,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun findsListeningHistoryBySheetNameNotPosition() {
        val file = createWorkbook(includeHistory = true, putHistorySecond = true)
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsRenumberedListeningHistorySheet() {
        val file = createWorkbook(
            includeHistory = true,
            historySheetName = "11_listeningHistory",
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsSheetThatOnlyContainsListeningHistoryInItsName() {
        val file = createWorkbook(
            includeHistory = true,
            historySheetName = "10_listeningHistory_backup",
        )
        try {
            val error = runCatching { DeezerXlsxParser.parse(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("10_listeningHistory"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsFrenchListeningHistoryHeaders() {
        val file = createWorkbook(
            includeHistory = true,
            frenchHeaders = true,
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals("Never Gonna Give You Up", result.entries.single().trackName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesActualDeezerInlineStringsAtSheet10Path() {
        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            absoluteWorksheetTargets = true,
            officialLayout = true,
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            val entry = result.entries.single()
            assertEquals("Never Gonna Give You Up", entry.trackName)
            assertEquals("Rick Astley", entry.artistName)
            assertEquals("GBAYE8800243", entry.isrc)
            assertEquals(213_000L, entry.msPlayed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsOfficialNegativeListeningTimeSentinel() {
        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            absoluteWorksheetTargets = true,
            officialLayout = true,
            listeningTime = "-1",
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)
            assertEquals(0L, result.entries.single().msPlayed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsUnaccentedFrenchListeningHistoryHeaders() {
        val file = createWorkbook(
            includeHistory = true,
            frenchHeaders = true,
            unaccentedHeaders = true,
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)
            assertEquals("Never Gonna Give You Up", result.entries.single().trackName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesColonDurationWithCommaDecimals() {
        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            listeningTime = "03:33,5",
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)
            assertEquals(213_500L, result.entries.single().msPlayed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalizesIsrcFormats() {
        assertEquals("GBAYE8800243", DeezerXlsxParser.normalizeIsrc("GB-AYE-88-00243"))
        assertEquals("GBAYE8800243", DeezerXlsxParser.normalizeIsrc("  gbaye8800243  "))
        assertEquals("GBAYE8800243", DeezerXlsxParser.normalizeIsrc("gb aye 8800243"))
        assertEquals(null, DeezerXlsxParser.normalizeIsrc("INVALID_ISRC"))
        assertEquals(null, DeezerXlsxParser.normalizeIsrc(""))
    }

    @Test
    fun parsesExcel1900NumericSerialDate() {
        val expected = Instant.parse("2024-10-24T23:00:00Z").toEpochMilli()
        assertEquals(expected, DeezerXlsxParser.parseDate("45589.958333333336", is1904DateSystem = false))

        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            dateValue = "45589.958333333336",
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(expected, result.entries.single().listenedAtMillis)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesExcel1904NumericSerialDate() {
        val expected = Instant.parse("2024-10-24T23:00:00Z").toEpochMilli()
        assertEquals(expected, DeezerXlsxParser.parseDate("44127.958333333336", is1904DateSystem = true))

        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            date1904 = true,
            dateValue = "44127.958333333336",
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(expected, result.entries.single().listenedAtMillis)
        } finally {
            file.delete()
        }
    }

    @Test
    fun ignoresTrailingBlankRowsWithoutIncrementingMalformedCount() {
        val file = createWorkbook(
            includeHistory = true,
            inlineStrings = true,
            includeEmptyRow = true,
        )
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)
        } finally {
            file.delete()
        }
    }

    @Test
    fun propagatesCancellationCheck() {
        val file = createWorkbook(includeHistory = true)
        try {
            val error = runCatching {
                DeezerXlsxParser.parse(file) {
                    throw CancellationException("cancelled")
                }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsWorkbookWithoutDeezerListeningHistorySheet() {
        val file = createWorkbook(includeHistory = false)
        try {
            val error = runCatching { DeezerXlsxParser.parse(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("10_listeningHistory"))
        } finally {
            file.delete()
        }
    }

    private fun createWorkbook(
        includeHistory: Boolean,
        putHistorySecond: Boolean = false,
        historySheetName: String = "10_listeningHistory",
        frenchHeaders: Boolean = false,
        unaccentedHeaders: Boolean = false,
        inlineStrings: Boolean = false,
        absoluteWorksheetTargets: Boolean = false,
        officialLayout: Boolean = false,
        listeningTime: String = "213",
        includeEmptyRow: Boolean = false,
        date1904: Boolean = false,
        dateValue: String = "2024-10-24 23:00:00",
    ): File {
        val file = kotlin.io.path.createTempFile("deezer-test-", ".xlsx").toFile()

        val sheets =
            if (officialLayout && includeHistory) {
                listOf(
                    "1_creationData",
                    "2_customizationData",
                    "3_setupData",
                    "4_favoriteArtist",
                    "5_favoriteAlbum",
                    "6_favoritePodcast",
                    "7_favoritePlaylist",
                    "8_favoriteSong",
                    "9_dislikedTracks",
                    historySheetName,
                    "11_playlistCreated",
                    "12_businessData",
                    "13_navigationData",
                    "14_tracking",
                ).mapIndexed { index, name -> name to "rId" + (index + 1) }
            } else if (includeHistory && putHistorySecond) {
                listOf("00_userProfile" to "rId1", historySheetName to "rId2")
            } else if (includeHistory) {
                listOf(historySheetName to "rId1")
            } else {
                listOf("00_userProfile" to "rId1")
            }

        val workbookSheets = sheets.mapIndexed { index, pair ->
            "<sheet name=\"" + pair.first + "\" sheetId=\"" + (index + 1) + "\" r:id=\"" + pair.second + "\"/>"
        }.joinToString("")

        val relationships = sheets.mapIndexed { index, pair ->
            val targetPrefix = if (absoluteWorksheetTargets) "/xl/worksheets/" else "worksheets/"
            "<Relationship Id=\"" + pair.second + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"" +
                targetPrefix + "sheet" + (index + 1) + ".xml\"/>"
        }.joinToString("")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val workbookPr = if (date1904) """<workbookPr date1904="1"/>""" else ""
            writeEntry(
                zip,
                "xl/workbook.xml",
                """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    $workbookPr
                    <sheets>""" + workbookSheets + """</sheets>
                </workbook>
                """.trimIndent(),
            )
            writeEntry(
                zip,
                "xl/_rels/workbook.xml.rels",
                """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    """ + relationships + """
                </Relationships>
                """.trimIndent(),
            )

            val headers =
                if (frenchHeaders && unaccentedHeaders) {
                    listOf(
                        "Titre du morceau",
                        "Artiste",
                        "ISRC",
                        "Titre de l'album",
                        "Adresse IP",
                        "Duree d'ecoute",
                        "Plateforme",
                        "Modele",
                        "Date d'ecoute",
                    )
                } else if (frenchHeaders) {
                    listOf(
                        "Titre du morceau",
                        "Artiste",
                        "ISRC",
                        "Titre de l'album",
                        "Adresse IP",
                        "Durée d'écoute",
                        "Plateforme",
                        "Modèle",
                        "Date d'écoute",
                    )
                } else {
                    listOf(
                        "Song Title",
                        "Artist",
                        "ISRC",
                        "Album Title",
                        "IP Address",
                        "Listening Time",
                        "Platform Name",
                        "Platform Model",
                        "Date",
                    )
                }
            val shared = headers + listOf(
                "Never Gonna Give You Up",
                "Rick Astley",
                "GBAYE8800243",
                "Whenever You Need Somebody",
                "2024-10-24 23:00:00",
            )
            if (!inlineStrings) {
                writeEntry(
                    zip,
                    "xl/sharedStrings.xml",
                    buildString {
                        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                        shared.forEach { value ->
                            append("<si><t>")
                            append(value)
                            append("</t></si>")
                        }
                        append("</sst>")
                    },
                )
            }

            sheets.forEachIndexed { index, pair ->
                val xml =
                    if (pair.first.contains("listeningHistory", ignoreCase = true)) {
                        val emptyRowXml = if (includeEmptyRow) """<row r="3"><c r="A3"/></row>""" else ""
                        if (inlineStrings) {
                            """
                            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                              <sheetData>
                                <row r="1">
                                  <c r="A1" t="inlineStr"><is><t>${headers[0]}</t></is></c>
                                  <c r="B1" t="inlineStr"><is><t>${headers[1]}</t></is></c>
                                  <c r="C1" t="inlineStr"><is><t>${headers[2]}</t></is></c>
                                  <c r="D1" t="inlineStr"><is><t>${headers[3]}</t></is></c>
                                  <c r="E1" t="inlineStr"><is><t>${headers[4]}</t></is></c>
                                  <c r="F1" t="inlineStr"><is><t>${headers[5]}</t></is></c>
                                  <c r="G1" t="inlineStr"><is><t>${headers[6]}</t></is></c>
                                  <c r="H1" t="inlineStr"><is><t>${headers[7]}</t></is></c>
                                  <c r="I1" t="inlineStr"><is><t>${headers[8]}</t></is></c>
                                </row>
                                <row r="2">
                                  <c r="A2" t="inlineStr"><is><t>Never Gonna Give You Up</t></is></c>
                                  <c r="B2" t="inlineStr"><is><t>Rick Astley</t></is></c>
                                  <c r="C2" t="inlineStr"><is><t>GBAYE8800243</t></is></c>
                                  <c r="D2" t="inlineStr"><is><t>Whenever You Need Somebody</t></is></c>
                                  <c r="F2" t="inlineStr"><is><t>$listeningTime</t></is></c>
                                  <c r="I2" t="inlineStr"><is><t>$dateValue</t></is></c>
                                </row>
                                $emptyRowXml
                              </sheetData>
                            </worksheet>
                            """.trimIndent()
                        } else {
                            """
                            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                              <sheetData>
                                <row r="1">
                                  <c r="A1" t="s"><v>0</v></c>
                                  <c r="B1" t="s"><v>1</v></c>
                                  <c r="C1" t="s"><v>2</v></c>
                                  <c r="D1" t="s"><v>3</v></c>
                                  <c r="E1" t="s"><v>4</v></c>
                                  <c r="F1" t="s"><v>5</v></c>
                                  <c r="G1" t="s"><v>6</v></c>
                                  <c r="H1" t="s"><v>7</v></c>
                                  <c r="I1" t="s"><v>8</v></c>
                                </row>
                                <row r="2">
                                  <c r="A2" t="s"><v>9</v></c>
                                  <c r="B2" t="s"><v>10</v></c>
                                  <c r="C2" t="s"><v>11</v></c>
                                  <c r="D2" t="s"><v>12</v></c>
                                  <c r="F2"><v>$listeningTime</v></c>
                                  <c r="I2" t="s"><v>13</v></c>
                                </row>
                                $emptyRowXml
                              </sheetData>
                            </worksheet>
                            """.trimIndent()
                        }
                    } else {
                        """
                                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData/>
                        </worksheet>
                        """.trimIndent()
                    }
                writeEntry(zip, "xl/worksheets/sheet" + (index + 1) + ".xml", xml)
            }
        }

        return file
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
