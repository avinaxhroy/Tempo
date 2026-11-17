package me.avinas.tempo.data.applemusic

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the shape Apple actually ships: an archive inside an archive, with the artist column
 * empty and the artist recoverable only from sibling files.
 *
 * Robolectric is needed for the real `org.json` implementation; plain JVM tests get the stubbed
 * Android one, which silently parses every JSON document as empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleMusicExportParserTest {

    private val parser = AppleMusicExportParser()
    private val root: File = newDir("apple-parse-test")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun parsesNestedExportAndRecoversArtistFromLibrary() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Album Name,Container Artist Name,Media Type,Play Duration Milliseconds,Container Origin Type
                2025-03-11T00:40:20.139Z,Sunflower,,,AUDIO,215000,USER_LIBRARY
            """.trimIndent(),
            libraryJson = """
                [{"Title":"Sunflower","Artist":"Post Malone","Album":"Spider-Man: Into the Spider-Verse"}]
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("Sunflower", entry.trackName)
        assertEquals("Post Malone", entry.artistName)
        assertEquals("Spider-Man: Into the Spider-Verse", entry.albumName)
        assertEquals(215_000L, entry.msPlayed)
        assertEquals(Instant.parse("2025-03-11T00:40:20.139Z").toEpochMilli(), entry.playedAtMillis)
        assertEquals(1, result.artistRecoveredRows)
    }

    @Test
    fun splitsArtistFromCombinedTrackHistoryString() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Heroes,AUDIO,180000
                2025-03-11T00:44:20.000Z,Missing,AUDIO,180000
            """.trimIndent(),
            trackHistory = """
                Track Name,Last Played Date
                David Bowie - Heroes,03/11/2025 00:40
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(2, result.entries.size)
        val heroes = result.entries.first { it.trackName == "Heroes" }
        assertEquals("David Bowie", heroes.artistName)
        assertEquals(AppleMusicExportParser.UNKNOWN_ARTIST, result.entries.first { it.trackName == "Missing" }.artistName)
        assertEquals(1, result.artistRecoveredRows)
    }

    @Test
    fun filtersNonAudioAndRadioAndClampsNegativeDuration() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds,Container Origin Type
                2025-03-11T00:40:20.000Z,Scrubbed Song,AUDIO,-4500,USER_LIBRARY
                2025-03-11T00:45:20.000Z,Music Video,VIDEO,200000,USER_LIBRARY
                2025-03-11T00:50:20.000Z,Radio Song,AUDIO,120000,STREAM_RADIO_STATION
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(1, result.entries.size)
        assertEquals(0L, result.entries.single().msPlayed)
        assertEquals(2, result.skippedNonAudioRows)
        assertEquals(0, result.malformedRows)
    }

    @Test
    fun countsMalformedRowsWithoutFailingTheImport() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                not-a-timestamp,Broken,AUDIO,1000
                2025-03-11T00:40:20.000Z,,AUDIO,1000
                2025-03-11T00:41:20.000Z,Good,AUDIO,1000
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(1, result.entries.size)
        assertEquals("Good", result.entries.single().trackName)
        assertEquals(2, result.malformedRows)
    }

    @Test
    fun rejectsArchiveWithoutPlayActivity() {
        val export = createExport(
            playActivity = null,
            libraryJson = """[{"Title":"Only In Library","Artist":"Someone"}]""",
        )

        try {
            parser.parse(export, dir("temp"))
            fail("Expected the parser to reject an archive with no play activity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not contain Apple Music play activity"))
        }
    }

    @Test
    fun parsesTimestampsAppleActuallyWrites() {
        assertEquals(
            Instant.parse("2025-03-11T00:40:20.139Z").toEpochMilli(),
            parser.parseTimestamp("2025-03-11T00:40:20.139Z"),
        )
        // Some builds drop the milliseconds or the trailing Z.
        assertEquals(
            Instant.parse("2025-03-11T00:40:20Z").toEpochMilli(),
            parser.parseTimestamp("2025-03-11T00:40:20Z"),
        )
        assertNull(parser.parseTimestamp(""))
        assertNull(parser.parseTimestamp("11/03/2025 00:40"))
        // A date past the allowed future slack is junk, not a play.
        assertNull(parser.parseTimestamp("2999-01-01T00:00:00Z"))
    }

    @Test
    fun leavesAmbiguousTitlesForEnrichment() {
        // "Intro" exists on thousands of albums and the play-activity rows do not say which one.
        // Picking a winner would mislabel one song and merge both into a single track.
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Intro,AUDIO,180000
            """.trimIndent(),
            trackHistory = """
                Track Name,Last Played Date
                Artist Alpha - Intro,03/11/2025 00:40
                Artist Beta - Intro,03/11/2025 00:41
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(AppleMusicExportParser.UNKNOWN_ARTIST, result.entries.single().artistName)
        assertEquals(0, result.artistRecoveredRows)
    }

    @Test
    fun keepsSingleOwnerTitlesFromTheSameTwoSources() {
        // Disagreement is dropped, but a title both files agree on stays attributed.
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Sunflower,AUDIO,180000
            """.trimIndent(),
            trackHistory = """
                Track Name,Last Played Date
                Post Malone - Sunflower,03/11/2025 00:40
            """.trimIndent(),
            libraryJson = """[{"Title":"Sunflower","Artist":"Post Malone","Album":"Into the Spider-Verse"}]""",
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals("Post Malone", result.entries.single().artistName)
        assertEquals(1, result.artistRecoveredRows)
    }

    @Test
    fun rejectsTimestampsBeforeAppleMusicExisted() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                1970-01-01T00:00:00.000Z,Epoch,AUDIO,180000
                0001-01-01T00:00:00.000Z,Year One,AUDIO,180000
                2025-03-11T00:40:20.000Z,Real,AUDIO,180000
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(listOf("Real"), result.entries.map { it.trackName })
        assertEquals(2, result.malformedRows)
    }

    @Test
    fun ignoresAbsurdMediaDuration() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds,Media Duration In Milliseconds
                2025-03-11T00:40:20.000Z,Sane,AUDIO,180000,215000
                2025-03-11T00:45:20.000Z,Bogus,AUDIO,180000,999999999999
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(215_000L, result.entries.first { it.trackName == "Sane" }.mediaDurationMs)
        assertNull(result.entries.first { it.trackName == "Bogus" }.mediaDurationMs)
    }

    @Test
    fun treatsHeaderOnlyExportAsEmptyInsteadOfUnsupported() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
            """.trimIndent(),
        )

        // An account with no plays is empty history, not a broken export format.
        val result = parser.parse(export, dir("temp"))

        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.malformedRows)
    }

    @Test
    fun readsLibraryJsonWithWhitespaceBetweenEntries() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Sunflower,AUDIO,180000
            """.trimIndent(),
            // The library file is read one entry at a time, so the walker has to step over the
            // separators instead of expecting a single-line array.
            libraryJson = """
                [
                  {
                    "Title": "Sunflower",
                    "Artist": "Post Malone",
                    "Album": "Spider-Man: Into the Spider-Verse"
                  }
                ]
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals("Post Malone", result.entries.single().artistName)
        assertEquals("Spider-Man: Into the Spider-Verse", result.entries.single().albumName)
    }

    @Test
    fun treatsEmptyLibraryJsonAsNoArtistSource() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Sunflower,AUDIO,180000
            """.trimIndent(),
            libraryJson = "[]",
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(AppleMusicExportParser.UNKNOWN_ARTIST, result.entries.single().artistName)
        assertEquals(0, result.artistRecoveredRows)
    }

    @Test
    fun sharesOneTitleStringBetweenRepeatedPlays() {
        val export = createExport(
            playActivity = """
                Event Start Timestamp,Song Name,Media Type,Play Duration Milliseconds
                2025-03-11T00:40:20.000Z,Sunflower,AUDIO,180000
                2025-03-11T01:40:20.000Z,Sunflower,AUDIO,180000
            """.trimIndent(),
        )

        val result = parser.parse(export, dir("temp"))

        assertEquals(2, result.entries.size)
        // One copy per distinct title, not one per play: this is what keeps a large export's
        // row list from holding a duplicate string for every replay.
        assertSame(result.entries[0].trackName, result.entries[1].trackName)
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    /** Writes outer.zip -> Apple_Media_Services.zip -> "Apple Music Activity" files, like a real export. */
    private fun createExport(
        playActivity: String?,
        trackHistory: String? = null,
        libraryJson: String? = null,
    ): File {
        val activity = dir("activity")
        playActivity?.let { File(activity, AppleMusicExportParser.PLAY_ACTIVITY_CSV).writeText(it) }
        trackHistory?.let { File(activity, AppleMusicExportParser.TRACK_HISTORY_CSV).writeText(it) }
        libraryJson?.let { File(activity, AppleMusicExportParser.LIBRARY_JSON).writeText(it) }

        val inner = File(root, "Apple_Media_Services.zip")
        ZipOutputStream(FileOutputStream(inner)).use { out ->
            activity.listFiles()!!.forEach { file ->
                out.putNextEntry(ZipEntry("Apple Music Activity/${file.name}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }

        val outer = File(root, "Apple Media Services information - Part 1 of 1.zip")
        ZipOutputStream(FileOutputStream(outer)).use { out ->
            out.putNextEntry(ZipEntry("Apple_Media_Services.zip"))
            inner.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
        return outer
    }

    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

    private fun newDir(prefix: String): File =
        File.createTempFile(prefix, "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
}
