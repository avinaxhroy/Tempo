package me.avinas.tempo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * Verifies MIGRATION_55_58, the migration out of schema 55 -- the last schema release v4.8.11
 * actually shipped. Schemas 56, 57 and 58 were never released, so the three steps that would have
 * built them ship as one migration, in this order:
 *
 * 1. `55 -> 56`: the default-deny detected-apps inbox, seeded from listening history.
 * 2. `56 -> 57`: `listening_events.tz_offset_minutes`, the offset each play happened in, plus the
 *    backfill of existing history.
 * 3. `57 -> 58`: `user_preferences.hideStreaks`, the stored hide-streaks display preference.
 *
 * What matters here:
 *
 * * Every `CREATE TABLE` / `ALTER TABLE` must match the Room-exported schema 58 exactly, or the
 *   identity-hash check fails at open time and the app crashes on upgrade for every existing user.
 * * Seeding must admit real app packages from history and reject the import markers that share the
 *   `source` column, otherwise the inbox opens full of "import.json" noise.
 * * Backfill must not move a single existing play to another local day or hour. The whole point of
 *   freezing the offset is that the past keeps rendering exactly as it did; an off-by-one in the
 *   arithmetic would make streaks and timing badges change *because* of the upgrade.
 * * `hideStreaks` must default to 0. Nobody should lose their streaks because they updated the app;
 *   only turning the setting on hides them. Everything already in `user_preferences` has to come
 *   through untouched.
 * * The upgrade only ever adds. It never drops a table, an index or a row, and a second run lands
 *   on exactly the state the first run produced.
 *
 * Runs the shipped migration against an in-memory SQLite database through a reflection proxy,
 * matching the harness used by the other migration tests (SQLite on Android 8-9 cannot parse
 * window functions, UPSERT or `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, so those are also
 * asserted against). The one migration touches `listening_events`, `user_preferences` and the
 * `detected_apps` table it creates, so every test runs against a fixture carrying both
 * pre-existing tables at their schema-55 shapes.
 */
class Migration55To58Test {
    private lateinit var connection: Connection

    /** listening_events exactly as exported in 55.json. */
    private val listeningEvents55CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `listening_events` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `track_id` INTEGER NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `playDuration` INTEGER NOT NULL,
            `completionPercentage` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `was_skipped` INTEGER NOT NULL DEFAULT 0,
            `is_replay` INTEGER NOT NULL DEFAULT 0,
            `estimated_duration_ms` INTEGER DEFAULT NULL,
            `pause_count` INTEGER NOT NULL DEFAULT 0,
            `session_id` TEXT DEFAULT NULL,
            `end_timestamp` INTEGER DEFAULT NULL,
            `total_pause_duration_ms` INTEGER NOT NULL DEFAULT 0,
            `seek_count` INTEGER NOT NULL DEFAULT 0,
            `position_updates_count` INTEGER NOT NULL DEFAULT 0,
            `was_interrupted` INTEGER NOT NULL DEFAULT 0,
            `volume_level` INTEGER DEFAULT NULL,
            `content_fingerprint` TEXT DEFAULT NULL
        )
        """.trimIndent()

    /**
     * user_preferences exactly as exported in 55.json. The 57 shape this table had before
     * `hideStreaks` was added is identical -- no step between 55 and 58 touches it otherwise.
     */
    private val userPreferences55CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `user_preferences` (
            `id` INTEGER NOT NULL,
            `theme` TEXT NOT NULL,
            `notifications` INTEGER NOT NULL,
            `spotifyLinked` INTEGER NOT NULL,
            `extendedAudioAnalysis` INTEGER NOT NULL,
            `mergeAlternateVersions` INTEGER NOT NULL,
            `filterPodcasts` INTEGER NOT NULL,
            `filterAudiobooks` INTEGER NOT NULL,
            `hasSeenHistoryCoachMark` INTEGER NOT NULL,
            `hasSeenSpotlightTutorial` INTEGER NOT NULL,
            `hasSeenStatsSortTutorial` INTEGER NOT NULL,
            `hasSeenStatsItemClickTutorial` INTEGER NOT NULL,
            `lastWeeklyReminderShown` TEXT,
            `lastMonthlyReminderShown` TEXT,
            `lastYearlyReminderShown` TEXT,
            `lastAllTimeReminderShown` TEXT,
            `spotifyApiOnlyMode` INTEGER NOT NULL,
            `spotifyImportCursor` TEXT,
            `lastSpotifyImportTimestamp` INTEGER,
            `lastfmUsername` TEXT,
            `lastfmConnected` INTEGER NOT NULL,
            `lastfmSyncFrequency` TEXT NOT NULL,
            `smartChallengeNotifHour` INTEGER,
            `smartChallengeNotifCalcTime` INTEGER,
            `isGamificationEnabled` INTEGER NOT NULL,
            `pauseTrackingOnLowBattery` INTEGER NOT NULL,
            `lastSpotlightStoryViewed` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()

    /**
     * Epochs chosen so that the frozen renderings cross day and month boundaries in zones east and
     * west of the machine's own, including 22:13 UTC (already tomorrow in Asia) and a far-future
     * date that no stored DST rule covers.
     */
    private val sampleTimestamps =
        listOf(
            0L, // 1970-01-01T00:00Z
            86_400_000L,
            1_700_000_000_000L, // 2023-11-14T22:13:20Z
            1_700_003_600_000L,
            1_706_731_200_000L, // 2024-01-31T20:00:00Z -- February in +07:00
            4_102_444_800_000L, // 2100-01-01T00:00Z
        )

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = OFF")
            stmt.execute(listeningEvents55CreateSql)
            stmt.execute(userPreferences55CreateSql)
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ---- Portability -------------------------------------------------------

    @Test
    fun `migration SQL avoids features missing on API 26 SQLite`() {
        // minSdk 26 devices ship SQLite 3.18-3.22: no window functions, no UPSERT, no RETURNING.
        val unsupported = listOf(
            "over (", "row_number", "dense_rank", "ntile", "lag(", "lead(",
            "on conflict", "do update set", "returning",
        )
        val statements = capturedStatements()
        val sql = statements.joinToString("\n").lowercase()

        // The guard has to see every statement of the merged migration, not just one step's.
        assertTrue("expected the 55 -> 56 detected_apps statement", sql.contains("detected_apps"))
        assertTrue("expected the 56 -> 57 offset statement", sql.contains("tz_offset_minutes"))
        assertTrue("expected the 57 -> 58 preference statement", sql.contains("hidestreaks"))

        unsupported.forEach { token ->
            assertFalse(
                "MIGRATION_55_58 must not use '$token': API 26 SQLite cannot parse it",
                sql.contains(token),
            )
        }

        // `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` are fine on API 26, so the
        // bare token cannot be banned outright. What SQLite has never accepted is
        // `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, which is what the old 57 -> 58 guard was
        // really protecting against: the column probes have to be PRAGMA reads, not SQL syntax.
        statements
            .filter { it.trimStart().startsWith("alter table", ignoreCase = true) }
            .forEach { alter ->
                assertFalse(
                    "ALTER TABLE ... ADD COLUMN IF NOT EXISTS is not valid SQLite: $alter",
                    alter.lowercase().contains("if not exists"),
                )
            }
    }

    // ---- 55 -> 56: detected_apps inbox -------------------------------------

    @Test
    fun `creates the detected_apps table with the exported columns`() {
        runRealMigration()

        assertEquals(
            listOf(
                "packageName",
                "lastTitle",
                "lastArtist",
                "detectionCount",
                "firstSeenAt",
                "lastSeenAt",
            ),
            tableColumns("detected_apps"),
        )
    }

    @Test
    fun `creates the lastSeenAt index used for inbox ordering`() {
        runRealMigration()

        val indexes = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA index_list(detected_apps)").use { rs ->
            while (rs.next()) indexes.add(rs.getString("name"))
        }
        assertTrue(
            "Expected index_detected_apps_lastSeenAt, got $indexes",
            indexes.contains("index_detected_apps_lastSeenAt"),
        )
    }

    @Test
    fun `detectionCount defaults to one and timestamps default to zero`() {
        runRealMigration()

        // Column defaults matter: the DTO relies on them for rows seeded before a field existed.
        connection.createStatement().use { stmt ->
            stmt.execute("INSERT INTO detected_apps (packageName) VALUES ('com.example.player')")
        }

        connection
            .createStatement()
            .executeQuery("SELECT detectionCount, firstSeenAt, lastSeenAt, lastTitle FROM detected_apps")
            .use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("detectionCount"))
                assertEquals(0L, rs.getLong("firstSeenAt"))
                assertEquals(0L, rs.getLong("lastSeenAt"))
                assertEquals(null, rs.getString("lastTitle"))
            }
    }

    @Test
    fun `seeds one inbox row per live app package found in history`() {
        insertEvent(source = "com.spotify.music", timestamp = 1_000)
        insertEvent(source = "com.spotify.music", timestamp = 3_000)
        insertEvent(source = "com.example.niche.player", timestamp = 2_000)

        runRealMigration()

        assertEquals(2, rowCount("detected_apps"))
        assertTrue(seededPackages().containsAll(listOf("com.spotify.music", "com.example.niche.player")))
    }

    @Test
    fun `seeded counts and timestamps summarise the app's history`() {
        insertEvent(source = "com.example.niche.player", timestamp = 5_000)
        insertEvent(source = "com.example.niche.player", timestamp = 1_000)
        insertEvent(source = "com.example.niche.player", timestamp = 9_000)

        runRealMigration()

        connection
            .createStatement()
            .executeQuery(
                "SELECT detectionCount, firstSeenAt, lastSeenAt FROM detected_apps " +
                    "WHERE packageName = 'com.example.niche.player'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(3, rs.getInt("detectionCount"))
                assertEquals(1_000L, rs.getLong("firstSeenAt"))
                assertEquals(9_000L, rs.getLong("lastSeenAt"))
            }
    }

    @Test
    fun `import markers sharing the source column are not seeded`() {
        // These all live in listening_events.source but are not installable app packages.
        insertEvent(source = "lastfm:import.json", timestamp = 1_000)
        insertEvent(source = "import.reconstructed", timestamp = 2_000)
        insertEvent(source = "desktop:abc123", timestamp = 3_000)
        insertEvent(source = "fm.last.import", timestamp = 4_000)
        insertEvent(source = "spotify.import.json", timestamp = 5_000)
        insertEvent(source = "com.example.niche.player", timestamp = 6_000)

        runRealMigration()

        assertEquals(listOf("com.example.niche.player"), seededPackages())
    }

    @Test
    fun `undotted and blank sources are not seeded`() {
        insertEvent(source = "local", timestamp = 1_000)
        insertEvent(source = "", timestamp = 2_000)

        runRealMigration()

        assertEquals(0, rowCount("detected_apps"))
    }

    @Test
    fun `seeding is a no-op when history is empty`() {
        runRealMigration()

        assertEquals(0, rowCount("detected_apps"))
    }

    @Test
    fun `migration is idempotent`() {
        insertEvent(source = "com.example.niche.player", timestamp = 1_000)

        runRealMigration()
        val firstPass = rowCount("detected_apps")
        // Re-running must not throw and must not duplicate rows.
        AppDatabase.MIGRATION_55_58.migrate(proxyDatabase())

        assertEquals(firstPass, rowCount("detected_apps"))
    }

    // ---- 56 -> 57: per-play timezone offset --------------------------------

    @Test
    fun `adds tz_offset_minutes as the last listening_events column`() {
        runRealMigration()

        assertEquals(
            "tz_offset_minutes",
            tableColumns("listening_events").last(),
        )
    }

    @Test
    fun `tz_offset_minutes matches the exported nullable integer contract`() {
        runRealMigration()

        val info = columnInfo("listening_events", "tz_offset_minutes")
        assertEquals("INTEGER", info.type)
        assertFalse("must be nullable so legacy rows can be absent", info.notNull)
        assertEquals("must default to NULL like the exported schema", "NULL", info.defaultValue)
        assertEquals("must not be part of the primary key", 0, info.pk)
    }

    @Test
    fun `backfills every existing row and leaves none without an offset`() {
        sampleTimestamps.forEach { insertEvent(timestamp = it) }

        runRealMigration()

        assertEquals(0, rowCount("listening_events WHERE tz_offset_minutes IS NULL"))
    }

    @Test
    fun `existing rows keep the local day they rendered on before the migration`() {
        sampleTimestamps.forEach { insertEvent(timestamp = it) }
        val before = dayByTimestamp("date(timestamp / 1000, 'unixepoch', 'localtime')")

        runRealMigration()

        // LOCAL_DAY reads the frozen offset; the pre-migration expression read the device's live
        // zone. They must agree for every row, which is what makes the upgrade invisible.
        assertEquals(before, dayByTimestamp(LocalTimeSql.LOCAL_DAY))
    }

    @Test
    fun `existing rows keep the local hour they rendered on before the migration`() {
        sampleTimestamps.forEach { insertEvent(timestamp = it) }
        val before = dayByTimestamp("strftime('%H', timestamp / 1000, 'unixepoch', 'localtime')")

        runRealMigration()

        assertEquals(before, dayByTimestamp("strftime('%H', ${LocalTimeSql.LOCAL_EPOCH}, 'unixepoch')"))
    }

    @Test
    fun `backfills whole fifteen-minute offsets`() {
        sampleTimestamps.forEach { insertEvent(timestamp = it) }

        runRealMigration()

        val offsets = scalarLongs("SELECT tz_offset_minutes FROM listening_events")
        assertEquals(sampleTimestamps.size, offsets.size)
        offsets.forEach { offset ->
            assertEquals("offset $offset is not a whole 15-minute step", 0L, offset % 15)
        }
    }

    @Test
    fun `migration is re-entrant, keeps frozen offsets and finishes a NULL backfill`() {
        sampleTimestamps.forEach { insertEvent(timestamp = it) }
        runRealMigration()
        val frozen = scalarLongs("SELECT tz_offset_minutes FROM listening_events ORDER BY timestamp")

        // Re-entry is detected with a value the backfill would never produce: if the guard failed,
        // the duplicate ALTER would throw; if the UPDATE were unscoped, the stamp would be
        // overwritten. The NULL row stands in for a run killed between the ALTER and the UPDATE.
        connection.createStatement().use {
            it.execute("UPDATE listening_events SET tz_offset_minutes = 999 WHERE timestamp = 0")
            it.execute("UPDATE listening_events SET tz_offset_minutes = NULL WHERE timestamp = 86400000")
        }
        AppDatabase.MIGRATION_55_58.migrate(proxyDatabase())

        val after = scalarLongs("SELECT tz_offset_minutes FROM listening_events ORDER BY timestamp")

        // Half one: an offset that is already stamped is left exactly as it was.
        assertEquals("a stamped offset must never be recomputed", 999L, after.first())

        // Half two: a row still missing an offset is backfilled. This is why the UPDATE carries
        // `WHERE tz_offset_minutes IS NULL` instead of the old early return -- a run killed between
        // the ALTER and the UPDATE would otherwise leave every row NULL, and a NULL renders through
        // whatever zone the phone is in *now*, the exact bug this column exists to fix.
        assertEquals(
            "a row still missing an offset must be backfilled",
            0,
            rowCount("listening_events WHERE tz_offset_minutes IS NULL"),
        )
        assertEquals("every other frozen offset is left exactly as it was", frozen.drop(1), after.drop(1))
    }

    @Test
    fun `an empty history migrates cleanly`() {
        runRealMigration()

        assertEquals(0, rowCount("listening_events"))
        assertTrue(tableColumns("listening_events").contains("tz_offset_minutes"))
    }

    // ---- 57 -> 58: hide-streaks preference ---------------------------------

    @Test
    fun `adds hideStreaks as the last user_preferences column`() {
        runRealMigration()

        assertEquals(
            "hideStreaks",
            tableColumns("user_preferences").last(),
        )
    }

    @Test
    fun `hideStreaks matches the exported not-null integer contract`() {
        runRealMigration()

        val info = columnInfo("user_preferences", "hideStreaks")
        assertEquals("INTEGER", info.type)
        assertTrue("Room expects a non-null boolean column", info.notNull)
        assertEquals("must default to 0 so upgrading changes nothing", "0", info.defaultValue)
        assertEquals("must not be part of the primary key", 0, info.pk)
    }

    @Test
    fun `existing preferences survive and keep streaks visible`() {
        insertPreferences(
            id = 0,
            isGamificationEnabled = 0,
            pauseTrackingOnLowBattery = 0,
            lastSpotlightStoryViewed = "M2026-08",
            lastfmUsername = "listener",
        )

        runRealMigration()

        assertEquals(1, rowCount("user_preferences"))
        assertEquals(
            "the gamification switch must come through as the user left it",
            listOf(0L),
            scalarLongs("SELECT isGamificationEnabled FROM user_preferences"),
        )
        assertEquals(0, scalarLongs("SELECT pauseTrackingOnLowBattery FROM user_preferences").first())
        assertEquals("listener", scalarStrings("SELECT lastfmUsername FROM user_preferences").first())
        assertEquals("M2026-08", scalarStrings("SELECT lastSpotlightStoryViewed FROM user_preferences").first())
        assertEquals(
            "an existing install must find streaks visible after the upgrade",
            listOf(0L),
            scalarLongs("SELECT hideStreaks FROM user_preferences"),
        )
    }

    @Test
    fun `migration is re-entrant and keeps a choice the user already made`() {
        insertPreferences(id = 0, isGamificationEnabled = 1, pauseTrackingOnLowBattery = 1)
        runRealMigration()

        // A value the fresh migration would never produce: if the guard failed, the duplicate
        // ALTER would throw; if it silently re-added the column, this would be reset to 0.
        connection.createStatement().use {
            it.execute("UPDATE user_preferences SET hideStreaks = 1")
        }
        AppDatabase.MIGRATION_55_58.migrate(proxyDatabase())

        assertEquals(listOf(1L), scalarLongs("SELECT hideStreaks FROM user_preferences"))
    }

    @Test
    fun `an install with no preferences row migrates cleanly`() {
        runRealMigration()

        assertEquals(0, rowCount("user_preferences"))
        assertTrue(tableColumns("user_preferences").contains("hideStreaks"))
    }

    // ---- The whole squashed migration --------------------------------------

    @Test
    fun `running the merged migration twice leaves the same end state as running it once`() {
        // History that exercises all three steps at once: two real app packages to seed, history to
        // backfill, and a preferences row to carry across.
        insertEvent(source = "com.spotify.music", timestamp = 1_000)
        insertEvent(source = "com.spotify.music", timestamp = 3_000)
        insertEvent(source = "com.example.niche.player", timestamp = 2_000)
        sampleTimestamps.forEach { insertEvent(timestamp = it) }
        insertPreferences(id = 0, isGamificationEnabled = 1, pauseTrackingOnLowBattery = 0)

        runRealMigration()
        val afterOneRun = endState()

        // A second open -- an upgrade retried after an interrupted run -- must not throw on the
        // repeated CREATE / ALTER, must not duplicate the seeded inbox rows, and must not move a
        // single offset or preference.
        runRealMigration()

        assertEquals(
            "a second run must land on exactly the state the first run produced",
            afterOneRun,
            endState(),
        )
        assertEquals("the seed must not duplicate inbox rows", 2, rowCount("detected_apps"))
        assertEquals(0, rowCount("listening_events WHERE tz_offset_minutes IS NULL"))
    }

    @Test
    fun `the merged migration only adds and never drops data`() {
        val statements = capturedStatements()
        assertTrue("the migration must issue statements", statements.isNotEmpty())

        // Make sure the scan covers every step rather than a truncated capture.
        listOf("detected_apps", "listening_events", "user_preferences").forEach { table ->
            assertTrue(
                "expected a captured statement touching '$table'",
                statements.any { it.contains(table) },
            )
        }

        val destructive = listOf("drop table", "drop index", "delete from")
        statements.forEach { statement ->
            destructive.forEach { token ->
                assertFalse(
                    "MIGRATION_55_58 must only ever add; found '$token' in: $statement",
                    statement.lowercase().contains(token),
                )
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun runRealMigration() {
        AppDatabase.MIGRATION_55_58.migrate(proxyDatabase())
    }

    /**
     * One listening_events row. The default source is a dotted, non-import package so seeding
     * treats it as a real app unless a test Passes one that should be rejected.
     */
    private fun insertEvent(
        source: String = "com.spotify.music",
        timestamp: Long,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO listening_events " +
                    "(`track_id`, `timestamp`, `playDuration`, `completionPercentage`, `source`) " +
                    "VALUES (1, ?, 1000, 50, ?)",
            ).use { ps ->
                ps.setLong(1, timestamp)
                ps.setString(2, source)
                ps.executeUpdate()
            }
    }

    private fun insertPreferences(
        id: Int,
        isGamificationEnabled: Int,
        pauseTrackingOnLowBattery: Int,
        lastSpotlightStoryViewed: String? = null,
        lastfmUsername: String? = null,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO user_preferences (
                    `id`, `theme`, `notifications`, `spotifyLinked`, `extendedAudioAnalysis`,
                    `mergeAlternateVersions`, `filterPodcasts`, `filterAudiobooks`,
                    `hasSeenHistoryCoachMark`, `hasSeenSpotlightTutorial`, `hasSeenStatsSortTutorial`,
                    `hasSeenStatsItemClickTutorial`, `spotifyApiOnlyMode`, `lastfmConnected`,
                    `lastfmSyncFrequency`, `isGamificationEnabled`, `pauseTrackingOnLowBattery`,
                    `lastSpotlightStoryViewed`, `lastfmUsername`
                ) VALUES (?, 'system', 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 'NONE', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, id)
                ps.setInt(2, isGamificationEnabled)
                ps.setInt(3, pauseTrackingOnLowBattery)
                ps.setString(4, lastSpotlightStoryViewed)
                ps.setString(5, lastfmUsername)
                ps.executeUpdate()
            }
    }

    private fun seededPackages(): List<String> {
        val packages = mutableListOf<String>()
        connection
            .createStatement()
            .executeQuery("SELECT packageName FROM detected_apps ORDER BY packageName")
            .use { rs ->
                while (rs.next()) packages.add(rs.getString(1))
            }
        return packages
    }

    private fun dayByTimestamp(expression: String): Map<Long, String> {
        val rendered = linkedMapOf<Long, String>()
        connection
            .createStatement()
            .executeQuery("SELECT timestamp, $expression FROM listening_events ORDER BY timestamp")
            .use { rs ->
                while (rs.next()) rendered[rs.getLong(1)] = rs.getString(2)
            }
        return rendered
    }

    private fun scalarLongs(sql: String): List<Long> {
        val values = mutableListOf<Long>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getLong(1))
        }
        return values
    }

    private fun scalarStrings(sql: String): List<String?> {
        val values = mutableListOf<String?>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getString(1))
        }
        return values
    }

    private fun tableColumns(table: String): List<String> {
        val columns = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) columns.add(rs.getString("name"))
        }
        return columns
    }

    private data class ColumnInfo(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int,
    )

    private fun columnInfo(
        table: String,
        column: String,
    ): ColumnInfo =
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) {
                if (rs.getString("name") == column) {
                    return ColumnInfo(
                        type = rs.getString("type"),
                        notNull = rs.getInt("notnull") != 0,
                        defaultValue = rs.getString("dflt_value"),
                        pk = rs.getInt("pk"),
                    )
                }
            }
            throw AssertionError("column $column not found in $table")
        }

    private fun rowCount(fromClause: String): Int =
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM $fromClause").use { rs ->
            rs.next()
            rs.getInt(1)
        }

    /**
     * Everything the merged migration is responsible for, rendered as one string so the state after
     * one run can be compared against the state after another byte for byte.
     */
    private fun endState(): String =
        listOf(
            "listening_events.tz_offset_minutes=" +
                scalarLongs("SELECT tz_offset_minutes FROM listening_events ORDER BY timestamp"),
            "detected_apps=" +
                scalarStrings(
                    "SELECT packageName || '|' || detectionCount || '|' || firstSeenAt || '|' || " +
                        "lastSeenAt FROM detected_apps ORDER BY packageName",
                ),
            "user_preferences.hideStreaks=" +
                scalarLongs("SELECT hideStreaks FROM user_preferences ORDER BY id"),
            "columns(listening_events)=" + tableColumns("listening_events"),
            "columns(user_preferences)=" + tableColumns("user_preferences"),
            "columns(detected_apps)=" + tableColumns("detected_apps"),
            "rowCount(listening_events)=" + rowCount("listening_events"),
            "rowCount(user_preferences)=" + rowCount("user_preferences"),
        ).joinToString("\n")

    /** Every `execSQL` the merged migration issues, in order. */
    private fun capturedStatements(): List<String> {
        val captured = mutableListOf<String>()
        AppDatabase.MIGRATION_55_58.migrate(capturingDatabase(captured))
        return captured
    }

    private fun capturingDatabase(sink: MutableList<String>): SupportSQLiteDatabase =
        proxy { sql ->
            sink.add(sql)
            connection.createStatement().use { it.execute(sql) }
        }

    private fun proxyDatabase(): SupportSQLiteDatabase =
        proxy { sql -> connection.createStatement().use { it.execute(sql) } }

    /** Minimal [SupportSQLiteDatabase] proxy over JDBC. */
    private fun proxy(onExecSql: (String) -> Unit): SupportSQLiteDatabase {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        onExecSql(args[0] as String)
                        null
                    }

                    "query" -> {
                        val stmt = connection.createStatement()
                        val rs = stmt.executeQuery(args[0] as String)
                        cursorProxy(stmt, rs)
                    }

                    "inTransaction" -> false
                    "isDbLockedByCurrentThread" -> false
                    "isOpen" -> true
                    else -> throw UnsupportedOperationException(
                        "Test adapter: unexpected SupportSQLiteDatabase call '${method.name}'",
                    )
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    private fun cursorProxy(
        stmt: Statement,
        rs: ResultSet,
    ): Cursor {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "moveToNext" -> rs.next()
                    "getString" -> rs.getString((args[0] as Int) + 1)
                    "getLong" -> rs.getLong((args[0] as Int) + 1)
                    "getInt" -> rs.getInt((args[0] as Int) + 1)
                    // Real Cursor semantics: 0-based index for the named column.
                    "getColumnIndex" -> rs.findColumn(args[0] as String) - 1
                    "getColumnCount" -> rs.metaData.columnCount
                    "close" -> {
                        rs.close()
                        stmt.close()
                        null
                    }

                    "isAfterLast" -> rs.isAfterLast
                    "isClosed" -> rs.isClosed
                    else -> throw UnsupportedOperationException(
                        "Test adapter: unexpected Cursor call '${method.name}'",
                    )
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cursor::class.java),
            handler,
        ) as Cursor
    }
}
