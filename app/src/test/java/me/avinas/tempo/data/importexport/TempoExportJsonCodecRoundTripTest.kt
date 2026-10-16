package me.avinas.tempo.data.importexport

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import kotlinx.coroutines.runBlocking
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistRole
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackArtist
import me.avinas.tempo.data.local.entities.UserPreferences
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trip integrity tests for the streaming backup codec — the format that
 * carries a user's entire listening history across devices and reinstalls.
 *
 * The wire contract under test:
 *  - every bounded table survives write→read byte-for-byte (structural equality),
 *  - the two unbounded tables (listening events, scrobble archive) stream through
 *    the [TempoExportJsonCodec.StreamHandlers] row by row without loss,
 *  - documents written by NEWER app versions (higher version field, unknown
 *    top-level fields) parse gracefully instead of failing the restore,
 *  - truncated/corrupt documents fail loudly instead of yielding partial data
 *    that a restore would silently apply.
 */
class TempoExportJsonCodecRoundTripTest {

    private val moshi = buildImportExportMoshi()
    private val codec = TempoExportJsonCodec(moshi)

    private fun sampleShell() = TempoExportData(
        appVersion = "4.8.4",
        schemaVersion = 52,
        userName = "Avinash",
        userProfileImagePath = null,
        tracks = listOf(
            Track(
                id = 1, title = "Song A", artist = "Artist One", album = "Album X",
                duration = 200_000L, albumArtUrl = "https://example.com/a.jpg",
                spotifyId = "sp1", musicbrainzId = null
            ),
            Track(
                id = 2, title = "Song B", artist = "Artist Two", album = null,
                duration = null, albumArtUrl = null,
                spotifyId = null, youtubeId = "yt2", musicbrainzId = "mb2"
            )
        ),
        artists = listOf(
            Artist(
                name = "Artist One", imageUrl = null, genres = listOf("jazz"),
                musicbrainzId = null, spotifyId = null
            ),
            Artist(
                name = "Artist Two", imageUrl = "file:///data/art.jpg",
                genres = emptyList(), musicbrainzId = "mb-artist", spotifyId = "sp-artist"
            )
        ),
        albums = listOf(
            Album(id = 5, title = "Album X", artistId = 1, releaseYear = 2020, artworkUrl = null)
        ),
        trackArtists = listOf(
            TrackArtist(trackId = 1, artistId = 1),
            TrackArtist(trackId = 1, artistId = 2, role = ArtistRole.FEATURED, creditOrder = 1)
        ),
        userPreferences = UserPreferences(theme = "dark", lastfmUsername = "avinash"),
        localImageManifest = mapOf("img_0_art.jpg" to "file:///data/art.jpg"),
        hotlinkedUrls = listOf("https://example.com/a.jpg")
    )

    private fun event(page: Int, index: Int) = ListeningEvent(
        id = (page * 10 + index).toLong(),
        track_id = 1L,
        timestamp = 1_700_000_000_000L + page * 100_000L + index,
        playDuration = 180_000L,
        completionPercentage = 95,
        source = "com.spotify.music",
        endTimestamp = 1_700_000_180_000L
    )

    private fun archiveRow(page: Int, index: Int) = ScrobbleArchive(
        id = (page * 10 + index).toLong(),
        trackHash = "hash-$page-$index",
        trackTitle = "Archived $page-$index",
        artistName = "Old Artist",
        artistNameNormalized = "old artist",
        timestampsBlob = ByteArray(8) { it.toByte() },
        playCount = page * 10 + index,
        firstScrobble = 1_600_000_000_000L,
        lastScrobble = 1_650_000_000_000L,
        importId = 7L
    )

    /** Writes a full document: shell + 2 pages of events (17 rows) + 1 page of archive (3 rows). */
    private suspend fun writeDocument(): Buffer {
        var eventPage = 0
        var archivePage = 0
        var originPage = 0
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        codec.write(
            writer = writer,
            shell = sampleShell(),
            eventPages = {
                when (eventPage++) {
                    0 -> List(10) { event(0, it) }
                    1 -> List(7) { event(1, it) }
                    else -> null
                }
            },
            archivePages = {
                when (archivePage++) {
                    0 -> List(3) { archiveRow(0, it) }
                    else -> null
                }
            },
            originPages = {
                when (originPage++) {
                    0 -> listOf(
                        ListeningEventOrigin("a".repeat(64), 1L, "desktop-one"),
                        ListeningEventOrigin("b".repeat(64), 1L, "browser-two")
                    )
                    1 -> listOf(ListeningEventOrigin("c".repeat(64), 13L, "desktop-one"))
                    else -> null
                }
            }
        )
        writer.close()
        return buffer
    }

    @Test
    fun `round trip preserves bounded tables and streamed rows`() = runBlocking {
        val expectedShell = sampleShell()
        val events = mutableListOf<ListeningEvent>()
        val archive = mutableListOf<ScrobbleArchive>()
        val origins = mutableListOf<ListeningEventOrigin>()

        val data = codec.read(
            JsonReader.of(writeDocument()),
            TempoExportJsonCodec.StreamHandlers(
                onListeningEvent = { events.add(it) },
                onScrobbleArchiveRow = { archive.add(it) },
                onListeningEventOrigin = { origins.add(it) }
            )
        )

        assertEquals(expectedShell.tracks, data.tracks)
        assertEquals(expectedShell.artists, data.artists)
        assertEquals(expectedShell.albums, data.albums)
        assertEquals(expectedShell.trackArtists, data.trackArtists)
        assertEquals(expectedShell.userPreferences, data.userPreferences)
        assertEquals(expectedShell.localImageManifest, data.localImageManifest)
        assertEquals(expectedShell.hotlinkedUrls, data.hotlinkedUrls)

        // Streamed rows arrive complete and in order.
        assertEquals(17, events.size)
        assertEquals(event(0, 0), events.first())
        assertEquals(event(1, 6), events.last())

        assertEquals(3, origins.size)
        assertEquals(ListeningEventOrigin("a".repeat(64), 1L, "desktop-one"), origins[0])
        assertEquals(ListeningEventOrigin("b".repeat(64), 1L, "browser-two"), origins[1])
        assertEquals(ListeningEventOrigin("c".repeat(64), 13L, "desktop-one"), origins[2])

        assertEquals(3, archive.size)
        assertEquals("hash-0-0", archive.first().trackHash)
        assertEquals("hash-0-2", archive.last().trackHash)
        assertTrue(
            "timestamps blob must survive the round trip byte-for-byte",
            archive.last().timestampsBlob.contentEquals(archiveRow(0, 2).timestampsBlob)
        )

        // The shell never materializes the unbounded tables.
        assertTrue(data.listeningEvents.isEmpty())
        assertTrue(data.scrobbleArchive.isEmpty())
        assertTrue(data.listeningEventOrigins.isEmpty())
    }

    @Test
    fun `old backups without origin claims remain readable`() = runBlocking {
        val raw = writeDocument().readUtf8()
        val originsStart = raw.indexOf(",\"listeningEventOrigins\":")
        val nextField = raw.indexOf(",\"enrichedMetadata\":", originsStart)
        assertTrue(originsStart > 0 && nextField > originsStart)
        val legacy = raw.removeRange(originsStart, nextField)
            .replaceFirst("\"version\":${TempoExportData.CURRENT_VERSION}",
                "\"version\":9")
        val rows = mutableListOf<ListeningEvent>()
        val data = codec.read(JsonReader.of(Buffer().writeUtf8(legacy)),
            TempoExportJsonCodec.StreamHandlers(onListeningEvent = { rows.add(it) }))
        assertEquals(9, data.version)
        assertEquals(17, rows.size)
        assertTrue(data.listeningEventOrigins.isEmpty())
    }

    @Test
    fun `document from a newer app version parses with unknown fields skipped`() = runBlocking {
        val raw = writeDocument().readUtf8()
        val mutated = raw.replaceFirst(
            "\"version\":${TempoExportData.CURRENT_VERSION}",
            "\"version\":${TempoExportData.CURRENT_VERSION + 3},\"someFutureField\":{\"nested\":[1,2]}"
        )
        assertTrue("mutation target not found in serialized document", mutated != raw)

        val events = mutableListOf<ListeningEvent>()
        val data = codec.read(
            JsonReader.of(Buffer().writeUtf8(mutated)),
            TempoExportJsonCodec.StreamHandlers(onListeningEvent = { events.add(it) })
        )

        assertEquals(TempoExportData.CURRENT_VERSION + 3, data.version)
        assertEquals(2, data.tracks.size)
        assertEquals(17, events.size)
    }

    @Test
    fun `truncated document fails loudly instead of yielding partial data`() {
        val raw = runBlocking { writeDocument().readUtf8() }
        val truncated = raw.substring(0, raw.length / 2)

        try {
            runBlocking {
                codec.read(
                    JsonReader.of(Buffer().writeUtf8(truncated)),
                    TempoExportJsonCodec.StreamHandlers()
                )
            }
        } catch (expected: Exception) {
            return
        }
        fail("Reading a truncated backup document must throw, not return partial data")
    }

    @Test
    fun `null handlers skip streamed arrays so the shell alone can be inspected`() = runBlocking {
        val data = codec.read(
            JsonReader.of(writeDocument()),
            TempoExportJsonCodec.StreamHandlers()
        )
        assertEquals(2, data.tracks.size)
        assertTrue(data.listeningEvents.isEmpty())
        assertTrue(data.scrobbleArchive.isEmpty())
    }

    @Test
    fun `legacy double-wrapped hotlinkedUrls arrays are flattened on read`() = runBlocking {
        // Backups from older versions nested URLs as [["a", "b"], ["c"]] instead of flat strings.
        val legacy = """
            {"version":${TempoExportData.CURRENT_VERSION},
             "hotlinkedUrls":[["https://a.jpg","https://b.jpg"],["https://c.jpg"]]}
        """.trimIndent().replace(System.lineSeparator(), "")
        val data = codec.read(
            JsonReader.of(Buffer().writeUtf8(legacy)),
            TempoExportJsonCodec.StreamHandlers()
        )
        assertEquals(
            listOf("https://a.jpg", "https://b.jpg", "https://c.jpg"),
            data.hotlinkedUrls
        )
    }

    @Test
    fun `flat and null hotlinkedUrls still parse after the legacy fix`() = runBlocking {
        val flat = """{"version":${TempoExportData.CURRENT_VERSION},"hotlinkedUrls":["https://a.jpg"]}"""
        val flatData = codec.read(
            JsonReader.of(Buffer().writeUtf8(flat)),
            TempoExportJsonCodec.StreamHandlers()
        )
        assertEquals(listOf("https://a.jpg"), flatData.hotlinkedUrls)

        val nullUrls = """{"version":${TempoExportData.CURRENT_VERSION},"hotlinkedUrls":null}"""
        val nullData = codec.read(
            JsonReader.of(Buffer().writeUtf8(nullUrls)),
            TempoExportJsonCodec.StreamHandlers()
        )
        assertTrue(nullData.hotlinkedUrls.isEmpty())
    }
}
