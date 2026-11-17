package me.avinas.tempo.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Album charts must rank a release once, by the release's total time — not once per raw
 * artist string.
 *
 * `tracks.artist` is a display string. A collaboration is stored as
 * "Karma & Uptown Slick" on most tracks of a release and as
 * "Karma & Uptown Slick feat. Guest" on the one track with a feature, while
 * `primary_artist_id` points at the same album artist for all of them. The album queries
 * grouped by `t.album, t.artist`, so that release appeared twice with a slice of its time
 * each, and both slices sorted below genuinely less-listened albums. Users reported exactly
 * that: sorting by Total Time put albums with more time below albums with less.
 *
 * The fix groups on the canonical album-artist identity — the artist row's name when the
 * track is linked, otherwise the raw string — so one release is one row. These tests run the
 * real SQL parsed out of StatsDao.kt, so they cannot drift from the DAO.
 */
class AlbumGroupingIdentityTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE artists (id INTEGER PRIMARY KEY, name TEXT, normalized_name TEXT)")
            stmt.execute(
                "CREATE TABLE tracks (" +
                    "id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, album_art_url TEXT, " +
                    "primary_artist_id INTEGER, content_type TEXT)",
            )
            stmt.execute(
                "CREATE TABLE enriched_metadata (track_id INTEGER PRIMARY KEY, album_art_url TEXT, release_type TEXT)",
            )
            stmt.execute(
                "CREATE TABLE listening_events (id INTEGER PRIMARY KEY, track_id INTEGER, playDuration INTEGER, timestamp INTEGER)",
            )
        }
        artist(1, "Karma & Uptown Slick")

        // WDTBU: three tracks, one credited with a feature, all linked to artist 1.
        // 600s + 600s + 900s = 2100s on the release.
        track(1, "Lotus", "Karma & Uptown Slick", "WDTBU", 1)
        track(2, "Ledger", "Karma & Uptown Slick", "WDTBU", 1)
        track(3, "Session", "Karma & Uptown Slick feat. Guest", "WDTBU", 1)
        // Other Album: two tracks, unrelated artist. 900s + 900s = 1800s, less than WDTBU.
        track(4, "One", "Someone Else", "Other Album", null)
        track(5, "Two", "Someone Else", "Other Album", null)

        event(1, 1, 600_000)
        event(2, 2, 600_000)
        event(3, 3, 900_000)
        event(4, 4, 900_000)
        event(5, 5, 900_000)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `total time sort ranks the merged release above a less-listened album`() {
        val rows = albumsByTime()
        assertEquals("one row per release", 2, rows.size)
        assertEquals("WDTBU", rows[0].album)
        assertEquals(2_100_000L, rows[0].totalTimeMs)
        assertEquals(3, rows[0].playCount)
        assertEquals(3, rows[0].uniqueTracks)
        assertEquals("Other Album", rows[1].album)
        assertEquals(1_800_000L, rows[1].totalTimeMs)
    }

    @Test
    fun `grouping on the raw artist string still splits the release`() {
        // Documents the defect this test class exists to prevent, using the pre-fix grouping.
        val sql = daoQuery("getTopAlbumsByTimeFiltered").replace(GROUP_BY, "GROUP BY t.album, t.artist")
        val rows = runAlbumQuery(bind(sql))
        // The old grouping produced two rows for WDTBU too, but the single-track variant was
        // dropped by the HAVING and the surviving row kept only part of the release's time.
        assertEquals(2, rows.size)
        assertEquals("Other Album", rows[0].album)
        assertEquals("WDTBU", rows[1].album)
        assertEquals(1_200_000L, rows[1].totalTimeMs)
        assertEquals(2, rows[1].uniqueTracks)
    }

    @Test
    fun `unlinked tracks fall back to the raw artist string instead of pooling`() {
        connection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM listening_events")
            stmt.execute("DELETE FROM tracks")
        }
        // No primary_artist_id: two different raw credits must stay two albums, not NULL-pool.
        track(10, "A", "Artist One", "Split Release", null)
        track(11, "B", "Artist Two", "Split Release", null)
        track(12, "C", "Artist One", "Split Release", null)
        track(13, "D", "Artist Two", "Split Release", null)
        event(10, 10, 1000)
        event(11, 11, 1000)
        event(12, 12, 1000)
        event(13, 13, 1000)

        val rows = albumsByTime()
        assertEquals("a NULL primary artist must not merge distinct credits", 2, rows.size)
        assertEquals(setOf("Artist One", "Artist Two"), rows.map { it.artist }.toSet())
    }

    @Test
    fun `filtered album count matches the rows the list can render`() {
        // getUniqueAlbumsCountFiltered previously counted DISTINCT t.album without the
        // HAVING that drops single-track releases, so it disagreed with the list query and
        // pagination could ask for pages that render nothing.
        val count =
            connection.createStatement().executeQuery(bind(daoQuery("getUniqueAlbumsCountFiltered"))).use { rs ->
                check(rs.next()) { "count query returned no row" }
                rs.getInt(1)
            }
        assertEquals(albumsByTime().size, count)
    }

    @Test
    fun `every album chart groups by canonical artist identity`() {
        val source = File("src/main/java/me/avinas/tempo/data/local/dao/StatsDao.kt")
        check(source.isFile) { "StatsDao.kt not found at ${source.absolutePath} (unit tests run from the app module dir)" }
        val queries =
            Regex("@Query\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
                .findAll(source.readText())
                .map { it.groupValues[1].replace(Regex("\\s+"), " ") }
                .toList()
        // Charts that keep one row per release are exactly the ones grouping by album AND
        // artist; grouping by album alone already fuses credits.
        val albumAndArtist = queries.filter { it.contains("GROUP BY t.album, t.artist") }
        assertEquals(
            "Album charts still group on the raw artist string, which splits a release by credit: " +
                albumAndArtist.joinToString("\n"),
            emptyList<String>(),
            albumAndArtist,
        )
        val albumOnlyQueries = queries.count { Regex("GROUP BY t\\.album(?!,)").containsMatchIn(it) }
        assertTrue(
            "Expected the album charts to be surveyed; found only $albumOnlyQueries album-only queries",
            albumOnlyQueries >= 5,
        )
    }

    private fun albumsByTime(): List<AlbumRow> = runAlbumQuery(bind(daoQuery("getTopAlbumsByTimeFiltered")))

    private fun runAlbumQuery(sql: String): List<AlbumRow> {
        val rows = mutableListOf<AlbumRow>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) {
                rows.add(
                    AlbumRow(
                        album = rs.getString("album"),
                        artist = rs.getString("artist"),
                        playCount = rs.getInt("play_count"),
                        totalTimeMs = rs.getLong("total_time_ms"),
                        uniqueTracks = rs.getInt("unique_tracks"),
                    ),
                )
            }
        }
        return rows
    }

    private fun bind(sql: String): String =
        sql
            .replace(Regex(":startTime\\b"), "0")
            .replace(Regex(":endTime\\b"), "9999999999999")
            .replace(Regex(":filterPodcasts\\b"), "0")
            .replace(Regex(":filterAudiobooks\\b"), "0")
            .replace(Regex(":limit\\b"), "50")
            .replace(Regex(":offset\\b"), "0")

    /** Parses the real @Query SQL out of StatsDao.kt, keyed by function name. */
    private fun daoQuery(funName: String): String {
        val source = File("src/main/java/me/avinas/tempo/data/local/dao/StatsDao.kt")
        check(source.isFile) { "StatsDao.kt not found at ${source.absolutePath} (unit tests run from the app module dir)" }
        val regex =
            Regex(
                "@Query\\(\\s*\"\"\"(.*?)\"\"\"\\s*,?\\s*\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:suspend\\s+)?fun\\s+(\\w+)",
                RegexOption.DOT_MATCHES_ALL,
            )
        val queries =
            regex
                .findAll(source.readText())
                .associate { it.groupValues[2] to it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
        return queries[funName] ?: error("No @Query found for $funName; parsed: ${queries.keys}")
    }

    private fun artist(
        id: Int,
        name: String,
    ) {
        connection.prepareStatement("INSERT INTO artists VALUES (?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setString(2, name)
            ps.setString(3, name.lowercase())
            ps.executeUpdate()
        }
    }

    private fun track(
        id: Int,
        title: String,
        artist: String,
        album: String,
        primaryArtistId: Int?,
    ) {
        connection.prepareStatement("INSERT INTO tracks VALUES (?,?,?,?,NULL,?,'MUSIC')").use { ps ->
            ps.setInt(1, id)
            ps.setString(2, title)
            ps.setString(3, artist)
            ps.setString(4, album)
            if (primaryArtistId == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, primaryArtistId)
            ps.executeUpdate()
        }
    }

    private fun event(
        id: Int,
        trackId: Int,
        durationMs: Long,
    ) {
        connection.prepareStatement("INSERT INTO listening_events VALUES (?,?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setInt(2, trackId)
            ps.setLong(3, durationMs)
            ps.setLong(4, id.toLong())
            ps.executeUpdate()
        }
    }

    private data class AlbumRow(
        val album: String,
        val artist: String,
        val playCount: Int,
        val totalTimeMs: Long,
        val uniqueTracks: Int,
    )

    private companion object {
        const val GROUP_BY =
            "GROUP BY t.album, COALESCE((SELECT ar.name FROM artists ar WHERE ar.id = t.primary_artist_id), t.artist)"
    }
}
