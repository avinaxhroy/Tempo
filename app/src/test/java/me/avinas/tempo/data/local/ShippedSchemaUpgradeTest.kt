package me.avinas.tempo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * The upgrade every existing user is about to take, from every schema that ever shipped to the
 * one this build compiles.
 *
 * Release 4.8.15 skips three unreleased schema steps: 55 is what v4.8.11 shipped, and 56, 57 and
 * 58 were only ever built locally, so they arrive as one squashed migration out of 55. That
 * squash is the reason this test exists at all -- it is the only migration in the chain that
 * three releases' worth of work lands in at once -- but it is not the whole risk. Anyone still on
 * 4.8.3, 4.8.7 or 4.8.10 upgrades through the entire chain in one open, so the sweep starts at
 * schema 43 and walks every shipped schema up to the current one, carrying a real library.
 *
 * Each case builds its database from the schema Room itself exported for that version
 * ([ShippedSchemas]), so the "before" state is the one a real install of that release has rather
 * than a hand-typed approximation that can drift. It then seeds a library with the shapes that
 * break careless migrations -- duplicate re-saves, replays under one session, plays either side
 * of a date line, NULL session ids, unicode and apostrophes, a podcast beside the music -- runs
 * the real migration objects through the same SQLite the app runs, and checks four things:
 *
 * 1. **Nothing is missing.** Every row of every table survives the upgrade, except the exact
 *    rows three migrations are documented to remove (the 53->54 challenge dedupe, the 54->55
 *    listening-event dedupe) and those removals are verified one by one, not counted.
 * 2. **Nothing is silently rewritten.** Every surviving row still carries the same values in
 *    every column it had before, and every column the upgrade adds lands on the default the
 *    schema declares -- so an upgrade cannot quietly reset a preference or a watch count.
 * 3. **The past still renders as it did.** The per-play timezone offset backfilled by 55->58 must
 *    reproduce, for every row, the local day and hour the old `'localtime'` queries produced.
 *    Different day boundaries would change streaks, Night Owl and Early Bird *because* of the
 *    upgrade.
 * 4. **Room will accept it.** The end state is compared table by table, column by column, index
 *    by index and foreign key by foreign key against the exported current schema -- the same
 *    comparison Room's `onValidateSchema` makes at open time. A mismatch here is the crash on
 *    launch that a destructive fallback would have papered over with an empty library.
 */
@RunWith(Parameterized::class)
class ShippedSchemaUpgradeTest(private val startVersion: Int) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "schema {0} -> ${AppDatabase.VERSION}")
        fun shippedSchemas(): List<Array<Any>> = ShippedSchemas.shippedVersions.map { arrayOf<Any>(it) }
    }

    private lateinit var connection: Connection
    private lateinit var start: ShippedSchema
    private lateinit var current: ShippedSchema
    private lateinit var before: Snapshot

    /** Pre-upgrade rows keyed by primary key, so a removed row is told apart from a rewritten one. */
    private lateinit var beforeById: Map<String, Map<String, List<Any?>>>

    /** The re-saves the 54 -> 55 dedupe is allowed to remove, identified before it runs. */
    private lateinit var redundantSaveIds: Set<Int>

    /** The challenge rows the 53 -> 54 dedupe is allowed to remove. Empty once the index enforces it. */
    private lateinit var challengeDuplicateIds: Set<Int>

    /**
     * Whether this install's path contains the 54 -> 55 listening-event dedupe. An install that
     * already sits on 55 has had those rows removed already -- or never had them -- so its history
     * has to come through untouched.
     */
    private val dedupesListening: Boolean get() = startVersion < 55

    /** Whether this install's path contains the 53 -> 54 challenge dedupe. */
    private val dedupesChallenges: Boolean get() = startVersion < 54

    @Before
    fun setUp() {
        start = ShippedSchemas.load(startVersion)
        current = ShippedSchemas.load(AppDatabase.VERSION)
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")

        start.createIn(connection)
        // Every install that ever ran the 8 -> 9 step carries this index on `tracks`, and no
        // version of the entity can declare it: Room's @Index has no collation, so
        // `title COLLATE NOCASE` cannot be named there. Room compares the whole index set when
        // it validates, so the leftover is the "Migration didn't properly handle: tracks"
        // crash this fixture has to reproduce. Created for every start schema, because the
        // drop has to hold for an install coming from any of them.
        connection.createStatement().use {
            it.execute("CREATE INDEX IF NOT EXISTS index_tracks_title_lower ON tracks(title COLLATE NOCASE)")
        }
        seedLibrary()
        before = snapshot()
        beforeById = start.tableNames.associateWith { imagesById(it) }
        redundantSaveIds = findRedundantSaveIds()
        challengeDuplicateIds = duplicateChallengeIds()
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ---- The chain itself --------------------------------------------------

    @Test
    fun `the shipped schema has an unbroken upgrade path to this build`() {
        val steps = migrationsFrom(startVersion).map { it.startVersion to it.endVersion }

        assertEquals(
            "the path must begin at the schema the install is on",
            startVersion,
            steps.first().first,
        )
        assertEquals(
            "the path must land on the schema this build compiles",
            AppDatabase.VERSION,
            steps.last().second,
        )
        steps.zipWithNext { (_, to), (nextFrom, _) ->
            assertEquals("gap in the upgrade path after schema $to", to, nextFrom)
        }
    }

    @Test
    fun `the fixture is a real database of the version it claims`() {
        // If this fails the rest of the file is testing something that never existed: the fixture
        // must carry the identity hash Room wrote when it created a database of this version.
        assertEquals(start.identityHash, LiveDatabase(connection).identityHash())
        assertEquals(startVersion, LiveDatabase(connection).userVersion())
    }

    // ---- 1. Nothing is missing ---------------------------------------------

    @Test
    fun `no table loses a row the upgrade was not meant to remove`() {
        upgrade()

        tablesNotExpectedToShrink().forEach { table ->
            val lost = missingRows(before.rows.getValue(table), rowsOf(table, before.columns.getValue(table)))
            assertTrue(
                "$table lost ${lost.size} row(s) to the upgrade, e.g. ${lost.firstOrNull()}",
                lost.isEmpty(),
            )
        }
    }

    @Test
    fun `listening history keeps every play except redundant re-saves`() {
        upgrade()
        val columns = before.columns.getValue("listening_events")
        val dropped = if (dedupesListening) redundantSaveIds.map { it.toString() }.toSet() else emptySet()
        val survivingIds = idsOf("listening_events")

        // Every play that was not a redundant re-save has to be here afterwards, unchanged: a play
        // that quietly disappears takes its streak, its badges and its statistics with it.
        assertEquals(
            "listening history lost or rewrote plays it should have kept",
            sortedImages(beforeById.getValue("listening_events").filterKeys { it !in dropped }.values),
            rowsOf("listening_events", columns),
        )

        if (dedupesListening) {
            assertTrue("the fixture must contain redundant re-saves", redundantSaveIds.isNotEmpty())
            assertTrue(
                "expected the redundant re-saves to be removed, but ${redundantSaveIds.size} are still there",
                redundantSaveIds.none { it in survivingIds },
            )
            // A re-save is only ever dropped in favour of the first, accurate record of that session.
            firstEventIdsPerSessionGroup().forEach { (group, firstId) ->
                assertTrue("session group $group lost its earliest record", firstId in survivingIds)
            }
        } else {
            assertTrue(
                "an install whose schema already carries the dedupe must lose no play at all",
                redundantSaveIds.all { it in survivingIds },
            )
        }
    }

    @Test
    fun `plays written without a session id are never touched by the dedupe`() {
        upgrade()

        val survivors = idsOf("listening_events")
        val sessionless = idsWhere("listening_events", "session_id IS NULL")

        assertTrue("the fixture must contain session-less plays", sessionless.isNotEmpty())
        assertEquals(
            "plays with no session id must all survive: they are not re-saves of anything",
            sessionless,
            sessionless.filter { it in survivors },
        )
    }

    @Test
    fun `daily challenges keep one row per challenge and day`() {
        upgrade()

        val groups = mutableMapOf<String, MutableList<Triple<Int, Int, Int>>>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT id, challenge_id || '|' || date, is_completed, current_progress FROM daily_challenges")
                .use { rs ->
                    while (rs.next()) {
                        groups.getOrPut(rs.getString(2)) { mutableListOf() }
                            .add(Triple(rs.getInt(1), rs.getInt(3), rs.getInt(4)))
                    }
                }
        }

        groups.forEach { (group, rows) ->
            assertEquals("$group kept ${rows.size} rows after the upgrade", 1, rows.size)
        }

        // The survivor is the row with the most to show for itself: completed first, then
        // progress, then the newest id. Losing that one would drop a completed challenge.
        groups.forEach { (group, rows) ->
            val survivor = rows.single()
            val expected = bestChallengeRow(group)
            assertEquals(
                "$group kept the wrong row: $survivor instead of $expected",
                expected,
                survivor,
            )
        }
    }

    @Test
    fun `the upgrade invents no rows in tables it merely had to keep`() {
        upgrade()

        tablesNotExpectedToShrink().forEach { table ->
            val added = missingRows(rowsOf(table, before.columns.getValue(table)), before.rows.getValue(table))
            assertTrue("$table gained ${added.size} unexpected row(s), e.g. ${added.firstOrNull()}", added.isEmpty())
        }
    }

    // ---- 2. Nothing is silently rewritten ----------------------------------

    @Test
    fun `every surviving row keeps the values it had before the upgrade`() {
        upgrade()

        tablesNotExpectedToShrink().forEach { table ->
            assertEquals("$table was rewritten by the upgrade", before.rows.getValue(table), rowsOf(table, before.columns.getValue(table)))
        }

        // The deduped tables are checked value by value too: what is left has to be exactly the
        // pre-upgrade rows whose id the dedupe is allowed to drop, with every column unchanged.
        // A table the upgrade was not allowed to dedupe has to come through whole.
        val droppedEventIds = if (dedupesListening) redundantSaveIds.map { it.toString() }.toSet() else emptySet()
        val droppedChallengeIds = if (dedupesChallenges) challengeDuplicateIds.map { it.toString() }.toSet() else emptySet()

        assertEquals(
            "listening history was rewritten by the upgrade",
            sortedImages(beforeById.getValue("listening_events").filterKeys { it !in droppedEventIds }.values),
            rowsOf("listening_events", before.columns.getValue("listening_events")),
        )
        assertEquals(
            "daily challenges were rewritten by the upgrade",
            sortedImages(beforeById.getValue("daily_challenges").filterKeys { it !in droppedChallengeIds }.values),
            rowsOf("daily_challenges", before.columns.getValue("daily_challenges")),
        )
    }

    @Test
    fun `every column the upgrade adds to an existing row lands on the value the release intends`() {
        upgrade()

        val report = mutableListOf<String>()
        current.tables.forEach { table ->
            val existing = before.columns[table.name] ?: return@forEach
            val added = table.columns.filterNot { it.name in existing }
            if (added.isEmpty()) return@forEach

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT `${added.joinToString("`, `") { it.name }}` FROM `${table.name}`").use { rs ->
                    var row = 0
                    while (rs.next()) {
                        added.forEachIndexed { index, column ->
                            // The timezone offset is the one column the upgrade is meant to fill
                            // from each row's own timestamp rather than from a constant; that
                            // backfill is verified row by row by the day-and-hour test above.
                            val intended = intendedValueFor("${table.name}.${column.name}")
                            if (intended != NotChecked) {
                                val actual = rs.getObject(index + 1)
                                if (!valuesEqual(actual, intended)) {
                                    report += "${table.name}.${column.name} row ${row + 1}: '$actual', expected '$intended'"
                                }
                            }
                        }
                        row++
                    }
                }
            }
        }

        assertTrue(
            "columns the upgrade added to existing rows did not land on the intended value:\n" +
                report.take(10).joinToString("\n"),
            report.isEmpty(),
        )
    }

    @Test
    fun `no pre-existing column disappears from a table the upgrade keeps`() {
        upgrade()

        val live = LiveDatabase(connection)
        before.columns.forEach { (table, columns) ->
            val liveColumns = live.tables.getValue(table).columns.keys
            columns.forEach { column ->
                assertTrue("$table.$column was dropped by the upgrade", column in liveColumns)
            }
        }
    }

    // ---- 3. The past still renders as it did -------------------------------

    @Test
    fun `the timezone backfill reproduces the local day and hour of every existing play`() {
        val beforeDays = renderedLocalValues("date(timestamp / 1000, 'unixepoch', 'localtime')")
        val beforeHours = renderedLocalValues("strftime('%H', timestamp / 1000, 'unixepoch', 'localtime')")

        upgrade()

        // Rows the dedupe removed have no rendering afterwards, so the comparison is over the
        // plays that survived -- for those, every day and hour must be exactly what it was.
        val surviving = idsOf("listening_events").toSet()

        // LOCAL_DAY/LOCAL_HOUR read the offset stored per row; before the upgrade the same values
        // came from the device's live zone. They must agree row for row, or streaks, Night Owl and
        // every day/hour statistic shift *because* of the upgrade.
        assertEquals(
            "local days moved during the upgrade",
            beforeDays.filterKeys { it in surviving },
            renderedLocalValues(LocalTimeSql.LOCAL_DAY).filterKeys { it in surviving },
        )
        assertEquals(
            "local hours moved during the upgrade",
            beforeHours.filterKeys { it in surviving },
            renderedLocalValues("strftime('%H', ${LocalTimeSql.LOCAL_EPOCH}, 'unixepoch')").filterKeys { it in surviving },
        )

        assertEquals(
            "every play must leave the upgrade with an offset; a NULL renders through whatever " +
                "zone the phone is in now, which is the bug the column exists to fix",
            0,
            scalarLong("SELECT COUNT(*) FROM listening_events WHERE tz_offset_minutes IS NULL"),
        )
    }

    // ---- 4. Room will accept it --------------------------------------------

    @Test
    fun `the upgraded database is exactly the schema Room validates against`() {
        upgrade()

        val problems = LiveDatabase(connection).diffAgainst(current)
        assertTrue(
            "Room's open-time schema validation would fail on this database:\n" +
                problems.take(20).joinToString("\n"),
            problems.isEmpty(),
        )
        assertEquals(
            "the database must report the schema version this build compiles",
            AppDatabase.VERSION,
            LiveDatabase(connection).userVersion(),
        )
    }

    @Test
    fun `the undeclared tracks title index is gone when the upgrade is done`() {
        upgrade()

        // The entity cannot declare `title COLLATE NOCASE`, so the index is not part of the
        // schema this build compiles. Leaving it behind is exactly what fails Room's
        // validation with "Migration didn't properly handle: tracks"; see
        // docs/PERFORMANCE_AUDIT.md #1. The table-by-table comparison above catches it too,
        // but this names the culprit when it comes back.
        assertTrue(
            "index_tracks_title_lower survived the upgrade and would fail Room's validation",
            LiveDatabase(connection).tables["tracks"]?.indices.orEmpty()
                .none { it.name == "index_tracks_title_lower" },
        )
    }

    @Test
    fun `the upgraded database is intact and has no dangling references`() {
        upgrade()

        assertEquals("ok", scalarString("PRAGMA integrity_check"))
        assertEquals("", foreignKeyViolations())
        assertTrue("history must survive the upgrade", scalarLong("SELECT COUNT(*) FROM listening_events") > 100)
        assertTrue("tracks must survive the upgrade", scalarLong("SELECT COUNT(*) FROM tracks") >= 5)
    }

    // ---- Interrupted upgrades ----------------------------------------------

    @Test
    fun `an upgrade interrupted at the last step can be re-entered`() {
        upgrade()
        val afterFirstRun = endState()

        // A process killed during the last step re-enters it: the CREATE and ALTER statements
        // have to tolerate running twice, the seed must not double, and the backfill must not
        // move an offset that is already stamped. The last step is the one an interrupted open
        // actually re-runs -- Room replays the whole path, but every earlier step is guarded by
        // its own probe, so the step that failed is the one whose idempotence is in question.
        // For an install on 58 that is 58 -> 59, whose DROP INDEX IF EXISTS must stay a no-op.
        migrationsFrom(startVersion).last().migrate(proxyDatabase())

        assertEquals("re-entering the migration path changed the database", afterFirstRun, endState())
    }

    // ---- Harness -----------------------------------------------------------

    private fun migrationsFrom(version: Int) = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion >= version }

    private fun upgrade() {
        migrationsFrom(startVersion).forEach { it.migrate(proxyDatabase()) }
        // The migration objects do not stamp the version -- Room does, right after the path runs
        // and the schema validates (RoomOpenHelper.onUpgrade). Without it the database would still
        // claim the old version and every later assertion would be about a half-finished upgrade.
        connection.createStatement().use { it.execute("PRAGMA user_version = ${AppDatabase.VERSION}") }
    }

    private fun tablesNotExpectedToShrink() =
        listOf(
            "tracks",
            "artists",
            "albums",
            "track_artists",
            "enriched_metadata",
            "scrobbles_archive",
            "user_preferences",
            "app_preferences",
            "badges",
            "user_level",
            "track_aliases",
            "artist_aliases",
            "manual_content_marks",
            "lastfm_import_metadata",
            "user_known_artists",
            "desktop_pairing_sessions",
        )

    /**
     * What a column the upgrade adds to an *existing* row must hold afterwards.
     *
     * The database cannot be its own witness here: reading the value back and comparing it to the
     * default the migration just wrote would pass even if that default were wrong. Each entry is
     * the release's intent, written out by hand from the entity that declares it -- streaks stay
     * visible after the update, pausing on low battery stays on, banked challenge XP starts empty,
     * the pairing token version starts at zero, and an artist nobody has picked a portrait for
 * keeps the NONE source. Anything not listed was added as a nullable
     * column and must therefore be NULL: a stray value there is a value from nowhere.
     */
    private val intendedAddedValues =
        mapOf(
            "user_preferences.pauseTrackingOnLowBattery" to 1L,
            "user_preferences.hideStreaks" to 0L,
            "user_level.banked_challenge_xp" to 0L,
            "desktop_pairing_sessions.token_version" to 0L,
            "artists.image_art_source" to "NONE",
        )

    /** Columns whose post-upgrade value is asserted somewhere else, or by a different rule. */
    private val addedColumnsCheckedElsewhere = setOf("listening_events.tz_offset_minutes")

    private object NotChecked

    private fun intendedValueFor(column: String): Any? =
        if (column in addedColumnsCheckedElsewhere) NotChecked else intendedAddedValues[column]

    // ---- Snapshots ---------------------------------------------------------

    private data class Snapshot(
        val columns: Map<String, List<String>>,
        val rows: Map<String, List<List<Any?>>>,
    )

    private fun snapshot(): Snapshot {
        val columns = mutableMapOf<String, List<String>>()
        val rows = mutableMapOf<String, List<List<Any?>>>()
        start.tableNames.sorted().forEach { table ->
            val tableColumns = columnNames(table)
            columns[table] = tableColumns
            rows[table] = rowsOf(table, tableColumns)
        }
        return Snapshot(columns, rows)
    }

    /** Every row of [table] projected onto [columns], rendered and sorted so it can be compared. */
    private fun rowsOf(table: String, columns: List<String>): List<List<Any?>> {
        if (columns.isEmpty()) return emptyList()
        val projection = columns.joinToString(", ") { "`$it`" }
        val result = mutableListOf<List<Any?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $projection FROM `$table`").use { rs ->
                while (rs.next()) {
                    result.add(columns.indices.map { normalize(rs.getObject(it + 1)) })
                }
            }
        }
        return sortedImages(result)
    }

    /** One ordering for both sides of a comparison, so a mismatch is a mismatch and not a sort. */
    private fun sortedImages(rows: Collection<List<Any?>>): List<List<Any?>> =
        rows.sortedBy { it.joinToString("\u0001") { value -> value?.toString() ?: "<null>" } }

    private fun columnNames(table: String): List<String> {
        val names = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) names.add(rs.getString("name"))
            }
        }
        return names
    }

    /** Rows in [reference] that do not appear in [actual], as readable strings. */
    private fun missingRows(reference: List<List<Any?>>, actual: List<List<Any?>>): List<String> {
        val remaining = actual.toMutableList()
        val missing = mutableListOf<String>()
        reference.forEach { row ->
            val index = remaining.indexOf(row)
            if (index >= 0) remaining.removeAt(index) else missing.add(render(row))
        }
        return missing
    }

    private fun render(row: List<Any?>) = row.joinToString(" | ") { it?.toString() ?: "<null>" }

    private fun endState(): String =
        listOf(
            "rows=" + start.tableNames.sorted().map { "$it:${scalarLong("SELECT COUNT(*) FROM `$it`")}" },
            "events=" + scalarLong("SELECT COUNT(*) FROM listening_events"),
            "offsets=" + scalarLong("SELECT COUNT(*) FROM listening_events WHERE tz_offset_minutes IS NULL"),
            "inbox=" + scalarLong("SELECT COUNT(*) FROM detected_apps"),
            "identity=" + LiveDatabase(connection).identityHash(),
            "indices(tracks)=" +
                LiveDatabase(connection).tables["tracks"]?.indices?.map { it.name }?.sorted(),
            "columns(listening_events)=" + columnNames("listening_events"),
            "columns(user_preferences)=" + columnNames("user_preferences"),
        ).joinToString("\n")

    // ---- Dedupe expectations ----------------------------------------------

    private fun idsOf(table: String) = idsWhere(table, "1 = 1")

    private fun idsWhere(table: String, where: String): List<Int> {
        val ids = mutableListOf<Int>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM `$table` WHERE $where").use { rs ->
                while (rs.next()) ids.add(rs.getInt(1))
            }
        }
        return ids
    }

    /** Ids of rows the 54 -> 55 dedupe is allowed to remove: later saves of a session already stored. */
    private fun findRedundantSaveIds(): Set<Int> {
        val ids = mutableSetOf<Int>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT id FROM listening_events
                    WHERE session_id IS NOT NULL
                      AND id NOT IN (
                          SELECT MIN(id) FROM listening_events
                          WHERE session_id IS NOT NULL
                          GROUP BY session_id, track_id, timestamp
                      )
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) ids.add(rs.getInt(1))
                }
        }
        return ids
    }

    /**
     * Every row of [table] keyed by id, projected onto the columns the install had before the
     * upgrade -- so the id can decide whether a missing row was allowed to go, and the projection
     * can prove the rows that stayed were not rewritten.
     */
    private fun imagesById(table: String): Map<String, List<Any?>> {
        val columns = before.columns.getValue(table)
        // Not every table has an `id`: preferences are keyed by package name and the track/artist
        // junction by both of its columns, so the key comes from the schema rather than a guess.
        val key = start.table(table).primaryKey.ifEmpty { listOf("rowid") }
        val projection = columns.joinToString(", ") { "`$it`" }
        val images = linkedMapOf<String, List<Any?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT ${key.joinToString(", ") { "`$it`" }}, $projection FROM `$table`").use { rs ->
                while (rs.next()) {
                    val id = key.indices.joinToString("|") { rs.getString(it + 1) ?: "<null>" }
                    images[id] = columns.indices.map { normalize(rs.getObject(it + 1 + key.size)) }
                }
            }
        }
        return images
    }

    /** Ids of challenge rows the 53 -> 54 dedupe is allowed to remove: a worse row for the same day. */
    private fun duplicateChallengeIds(): Set<Int> {
        // id, group, completed, progress -- the three keys the dedupe ranks on, so the "best" row
        // per group can be identified independently of the migration's own SQL.
        data class ChallengeRow(val id: Int, val group: String, val completed: Int, val progress: Int)

        val rows = mutableListOf<ChallengeRow>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT id, challenge_id || '|' || date, is_completed, current_progress FROM daily_challenges")
                .use { rs ->
                    while (rs.next()) {
                        rows.add(ChallengeRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4)))
                    }
                }
        }
        return rows.groupBy { it.group }
            .filterValues { it.size > 1 }
            .values
            .flatMap { group ->
                group.sortedWith(
                    compareByDescending<ChallengeRow> { it.completed }
                        .thenByDescending { it.progress }
                        .thenByDescending { it.id },
                ).drop(1)
            }
            .map { it.id }
            .toSet()
    }

    /** The earliest id of every session group that shares a track and timestamp. */
    private fun firstEventIdsPerSessionGroup(): Map<String, Int> {
        val groups = mutableMapOf<String, Int>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT MIN(id), session_id || '|' || track_id || '|' || timestamp
                    FROM listening_events
                    WHERE session_id IS NOT NULL
                    GROUP BY session_id, track_id, timestamp
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) groups[rs.getString(2)] = rs.getInt(1)
                }
        }
        return groups
    }

    /** The row the 53 -> 54 dedupe is documented to keep: completed, then progress, then newest id. */
    private fun bestChallengeRow(group: String): Triple<Int, Int, Int> {
        val (challengeId, date) = group.split("|")
        val rows = mutableListOf<Triple<Int, Int, Int>>()
        connection.prepareStatement(
            "SELECT id, is_completed, current_progress FROM daily_challenges WHERE challenge_id = ? AND date = ?",
        ).use { ps ->
            ps.setString(1, challengeId)
            ps.setString(2, date)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
            }
        }
        return rows.minWithOrNull(
            compareByDescending<Triple<Int, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first },
        ) ?: throw AssertionError("no challenge rows for $group")
    }

    // ---- Rendered values ---------------------------------------------------

    private fun renderedLocalValues(expression: String): Map<Int, String> {
        val rendered = sortedMapOf<Int, String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, $expression FROM listening_events ORDER BY id").use { rs ->
                while (rs.next()) rendered[rs.getInt(1)] = rs.getString(2) ?: "<null>"
            }
        }
        return rendered
    }

    private fun foreignKeyViolations(): String {
        val violations = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { rs ->
                while (rs.next()) violations.add("${rs.getString(1)} -> ${rs.getString(3)}")
            }
        }
        return violations.take(5).joinToString("; ")
    }

    private fun scalarLong(sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun scalarString(sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun normalize(value: Any?): Any? =
        when (value) {
            null -> null
            is ByteArray -> value.joinToString("") { "%02x".format(it) }
            is Int -> value.toLong()
            is Long -> value
            is Double -> if (value == value.toLong().toDouble()) value.toLong() else value
            is Float -> value.toDouble()
            else -> value
        }

    private fun valuesEqual(actual: Any?, expected: Any?): Boolean {
        val left = normalize(actual)
        if (left == null) return expected == null
        return when (left) {
            is Long -> expected is Number && expected.toLong() == left
            is String -> expected is String && expected == left
            else -> left == expected
        }
    }

    private fun normalizeDefault(value: String): Any? {
        var text = value.trim()
        while (text.length > 1 && text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length - 1).trim()
        }
        text = text.removeSurrounding("'")
        if (text.equals("NULL", ignoreCase = true)) return null
        return text.toLongOrNull() ?: text
    }

    // ---- The library on board ---------------------------------------------

    private fun seedLibrary() {
        dataRows().forEach { (table, values) -> insert(table, values) }
    }

    /**
     * A library with the shapes that have historically broken migrations: duplicate re-saves of
     * one session, a genuine replay under the same session, plays either side of a date line and
     * DST, session-less imported plays, unicode and apostrophes in names, a podcast beside the
     * music, and preferences a user has actually changed.
     */
    private fun dataRows(): List<Pair<String, Map<String, Any?>>> {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        fun row(table: String, vararg values: Pair<String, Any?>) {
            rows.add(table to values.toMap())
        }

        row(
            "artists",
            "id" to 1,
            "name" to "Beyoncé",
            "normalized_name" to "beyonce",
            "genres" to "pop,r&b",
            "country" to "US",
            "artist_type" to "PERSONALITY",
        )
        row(
            "artists",
            "id" to 2,
            "name" to "坂本龍一",
            "normalized_name" to "坂本龍一",
            "genres" to "ambient,electronic",
            "country" to "JP",
            "artist_type" to "PERSONALITY",
        )
        row(
            "artists",
            "id" to 3,
            "name" to "Florence + the Machine",
            "normalized_name" to "florence + the machine",
            "genres" to "indie rock",
            "country" to "GB",
            "artist_type" to "GROUP",
        )

        row("albums", "id" to 1, "title" to "RENAISSANCE", "artist_id" to 1, "release_year" to 2022, "release_type" to "ALBUM")
        row("albums", "id" to 2, "title" to "async", "artist_id" to 2, "release_year" to 2017, "release_type" to "ALBUM")

        row(
            "tracks",
            "id" to 1,
            "title" to "CUFF IT",
            "artist" to "Beyoncé",
            "album" to "RENAISSANCE",
            "duration" to 225_000L,
            "primary_artist_id" to 1,
            "content_type" to "MUSIC",
        )
        row(
            "tracks",
            "id" to 2,
            "title" to "BREAK MY SOUL",
            "artist" to "Beyoncé",
            "album" to "RENAISSANCE",
            "duration" to 278_000L,
            "primary_artist_id" to 1,
            "content_type" to "MUSIC",
        )
        row(
            "tracks",
            "id" to 3,
            "title" to "andata",
            "artist" to "坂本龍一",
            "album" to "async",
            "duration" to 261_000L,
            "primary_artist_id" to 2,
            "content_type" to "MUSIC",
        )
        // Apostrophes and ampersands reach SQL through the real writers; a migration that
        // concatenates SQL by hand rather than binding values fails on exactly these rows.
        row(
            "tracks",
            "id" to 4,
            "title" to "Dog Days Are Over (O'Brien's Mix)",
            "artist" to "Florence + the Machine",
            "album" to "Lungs",
            "duration" to 252_000L,
            "primary_artist_id" to 3,
            "content_type" to "MUSIC",
        )
        row(
            "tracks",
            "id" to 5,
            "title" to "Episode 412: The One About Streaks",
            "artist" to "Tempo Weekly",
            "album" to null,
            "duration" to 3_600_000L,
            "content_type" to "PODCAST",
        )

        row("track_artists", "track_id" to 1, "artist_id" to 1, "role" to "PRIMARY", "credit_order" to 0)
        row("track_artists", "track_id" to 3, "artist_id" to 2, "role" to "PRIMARY", "credit_order" to 0)
        row("track_artists", "track_id" to 4, "artist_id" to 3, "role" to "PRIMARY", "credit_order" to 0)

        row(
            "enriched_metadata",
            "id" to 1,
            "track_id" to 1,
            "genres" to "pop,r&b",
            "tags" to "dance,disco",
            "genre_source" to "SPOTIFY",
            "cache_timestamp" to 1_700_000_000_000L,
            "cache_version" to 3,
            "album_art_source" to "SPOTIFY",
            "audio_features_source" to "NONE",
            "spotify_enrichment_status" to "SUCCESS",
            "enrichment_status" to "COMPLETE",
            "retry_count" to 0,
        )
        row(
            "enriched_metadata",
            "id" to 2,
            "track_id" to 3,
            "genres" to "ambient",
            "tags" to "modern classical",
            "genre_source" to "MUSICBRAINZ",
            "cache_timestamp" to 1_700_000_100_000L,
            "cache_version" to 2,
            "album_art_source" to "NONE",
            "audio_features_source" to "NONE",
            "spotify_enrichment_status" to "NONE",
            "enrichment_status" to "PARTIAL",
            "retry_count" to 1,
        )

        // 60 days of listening, three to six plays a day, across the sources a real library has.
        val firstDay = 1_735_689_600_000L // 2025-01-01T00:00Z
        val dayMs = 86_400_000L
        var eventId = 1
        var session = 0
        for (day in 0 until 60) {
            val playsToday = 3 + (day % 4)
            for (play in 0 until playsToday) {
                session++
                val trackId = 1 + ((day + play) % 4)
                val source =
                    when (day % 5) {
                        0 -> "com.spotify.music"
                        1 -> "com.example.niche.player"
                        2 -> "com.spotify.music"
                        3 -> "com.google.android.apps.youtube.music"
                        else -> "com.example.niche.player"
                    }
                row(
                    "listening_events",
                    "id" to eventId,
                    "track_id" to trackId,
                    "timestamp" to firstDay + day * dayMs + play * 3_600_000L + (play * 1_337_000L) % 3_600_000L,
                    "playDuration" to (60_000L + (play * 17_000L)),
                    "completionPercentage" to (40 + (play * 9) % 60),
                    "source" to source,
                    "was_skipped" to if (play == 2) 1 else 0,
                    "is_replay" to if (play == 3) 1 else 0,
                    "estimated_duration_ms" to 220_000L,
                    "pause_count" to play % 3,
                    "session_id" to "session-$session",
                    "end_timestamp" to firstDay + day * dayMs + play * 3_600_000L + 180_000L,
                    "total_pause_duration_ms" to play * 1_000L,
                    "seek_count" to play % 2,
                    "position_updates_count" to 10 + play,
                    "was_interrupted" to if (play == 3) 1 else 0,
                    "volume_level" to 7 + (play % 5),
                    "content_fingerprint" to "fp-$eventId",
                )
                eventId++
            }
        }

        // Imported history: no session id at all, so the session dedupe must never reach it.
        listOf("lastfm:import.json", "desktop:abc123", "spotify.import.json").forEachIndexed { index, source ->
            row(
                "listening_events",
                "id" to eventId++,
                "track_id" to 3,
                "timestamp" to firstDay - (index + 1) * dayMs,
                "playDuration" to 200_000L,
                "completionPercentage" to 90,
                "source" to source,
                "was_skipped" to 0,
                "is_replay" to 0,
                "pause_count" to 0,
                "session_id" to null,
                "total_pause_duration_ms" to 0L,
                "seek_count" to 0,
                "position_updates_count" to 0,
                "was_interrupted" to 0,
            )
        }

        // Two imported plays of one track at the same instant, neither carrying a session id --
        // exactly what an import writes when the same song appears twice in a file. The dedupe
        // groups on (session, track, timestamp), and SQLite treats NULLs as equal in a GROUP BY,
        // so these two are only kept because the statement is guarded on session_id IS NOT NULL.
        listOf(930, 931).forEachIndexed { index, id ->
            row(
                "listening_events",
                "id" to id,
                "track_id" to 3,
                "timestamp" to 1_735_720_000_000L,
                "playDuration" to 180_000L + index * 20_000L,
                "completionPercentage" to 70,
                "source" to "lastfm:import.json",
                "was_skipped" to 0,
                "is_replay" to 0,
                "pause_count" to 0,
                "session_id" to null,
                "total_pause_duration_ms" to 0L,
                "seek_count" to 0,
                "position_updates_count" to 0,
                "was_interrupted" to 0,
            )
        }

        // The same play saved three times by the old tracker bug: 54 -> 55 may remove the two
        // later saves, and must keep the first.
        repeat(3) { save ->
            row(
                "listening_events",
                "id" to 900 + save,
                "track_id" to 2,
                "timestamp" to 1_735_700_000_000L,
                "playDuration" to 240_000L + save * 30_000L,
                "completionPercentage" to 80,
                "source" to "com.spotify.music",
                "was_skipped" to 0,
                "is_replay" to 0,
                "pause_count" to 0,
                "session_id" to "session-resave",
                "total_pause_duration_ms" to 0L,
                "seek_count" to 0,
                "position_updates_count" to 0,
                "was_interrupted" to 0,
            )
        }

        // A genuine replay: same session and track, a different timestamp. Both are real plays.
        listOf(1_735_710_000_000L, 1_735_710_600_000L).forEachIndexed { index, timestamp ->
            row(
                "listening_events",
                "id" to 910 + index,
                "track_id" to 1,
                "timestamp" to timestamp,
                "playDuration" to 225_000L,
                "completionPercentage" to 100,
                "source" to "com.spotify.music",
                "was_skipped" to 0,
                "is_replay" to 1,
                "pause_count" to 0,
                "session_id" to "session-replay",
                "total_pause_duration_ms" to 0L,
                "seek_count" to 0,
                "position_updates_count" to 0,
                "was_interrupted" to 0,
            )
        }

        // Epoch and a far-future date: the two ends where a naive offset calculation breaks.
        listOf(0L, 4_102_444_800_000L).forEachIndexed { index, timestamp ->
            row(
                "listening_events",
                "id" to 920 + index,
                "track_id" to 4,
                "timestamp" to timestamp,
                "playDuration" to 1_000L,
                "completionPercentage" to 1,
                "source" to "com.example.niche.player",
                "was_skipped" to 0,
                "is_replay" to 0,
                "pause_count" to 0,
                "session_id" to "session-edge-$index",
                "total_pause_duration_ms" to 0L,
                "seek_count" to 0,
                "position_updates_count" to 0,
                "was_interrupted" to 0,
            )
        }

        row(
            "scrobbles_archive",
            "id" to 1,
            "track_hash" to "hash-1",
            "track_title" to "andata",
            "artist_name" to "坂本龍一",
            "artist_name_normalized" to "坂本龍一",
            "timestamps_blob" to byteArrayOf(1, 2, 3, 4, 5),
            "play_count" to 42,
            "first_scrobble" to 1_600_000_000_000L,
            "last_scrobble" to 1_700_000_000_000L,
            "was_loved" to 1,
            "import_id" to 1,
            "created_at" to 1_700_000_000_000L,
            "updated_at" to 1_700_000_100_000L,
        )
        row(
            "scrobbles_archive",
            "id" to 2,
            "track_hash" to "hash-2",
            "track_title" to "Dog Days Are Over (O'Brien's Mix)",
            "artist_name" to "Florence + the Machine",
            "artist_name_normalized" to "florence + the machine",
            "album_name" to "Lungs",
            "timestamps_blob" to byteArrayOf(9, 8, 7),
            "play_count" to 7,
            "first_scrobble" to 1_650_000_000_000L,
            "last_scrobble" to 1_660_000_000_000L,
            "was_loved" to 0,
            "import_id" to 1,
            "created_at" to 1_650_000_000_000L,
            "updated_at" to 1_650_000_000_000L,
        )

        row(
            "user_preferences",
            "id" to 0,
            "theme" to "dark",
            "notifications" to 1,
            "spotifyLinked" to 0,
            "extendedAudioAnalysis" to 0,
            "mergeAlternateVersions" to 1,
            "filterPodcasts" to 1,
            "filterAudiobooks" to 0,
            "hasSeenHistoryCoachMark" to 1,
            "hasSeenSpotlightTutorial" to 1,
            "hasSeenStatsSortTutorial" to 0,
            "hasSeenStatsItemClickTutorial" to 0,
            "lastWeeklyReminderShown" to "2026-01-04",
            "lastMonthlyReminderShown" to "2026-01-01",
            "lastYearlyReminderShown" to "2026-01-01",
            "lastAllTimeReminderShown" to "2025-12-01",
            "spotifyApiOnlyMode" to 0,
            "spotifyImportCursor" to "page:12",
            "lastSpotifyImportTimestamp" to 1_735_700_000_000L,
            "lastfmUsername" to "listener_42",
            "lastfmConnected" to 1,
            "lastfmSyncFrequency" to "DAILY",
            "smartChallengeNotifHour" to 9,
            "smartChallengeNotifCalcTime" to 1_735_000_000_000L,
            "isGamificationEnabled" to 1,
            "pauseTrackingOnLowBattery" to 1,
            "lastSpotlightStoryViewed" to "M2026-08",
        )

        row(
            "app_preferences",
            "packageName" to "com.spotify.music",
            "displayName" to "Spotify",
            "isEnabled" to 1,
            "isUserAdded" to 0,
            "isBlocked" to 0,
            "category" to "MUSIC",
            "addedAt" to 1_700_000_000_000L,
        )
        row(
            "app_preferences",
            "packageName" to "com.example.niche.player",
            "displayName" to "Niche Player",
            "isEnabled" to 1,
            "isUserAdded" to 1,
            "isBlocked" to 0,
            "category" to "MUSIC",
            "addedAt" to 1_700_000_500_000L,
        )
        row(
            "app_preferences",
            "packageName" to "com.viber.voip",
            "displayName" to "Viber",
            "isEnabled" to 0,
            "isUserAdded" to 0,
            "isBlocked" to 1,
            "category" to "OTHER",
            "addedAt" to 1_700_001_000_000L,
        )

        row(
            "badges",
            "id" to 1,
            "badge_id" to "first_play",
            "name" to "First Play",
            "description" to "Log your first play",
            "icon_name" to "ic_badge_first",
            "category" to "MILESTONE",
            "earned_at" to 1_700_000_000_000L,
            "progress" to 1,
            "max_progress" to 1,
            "is_earned" to 1,
            "stars" to 3,
            "is_acknowledged" to 1,
        )
        row(
            "badges",
            "id" to 2,
            "badge_id" to "night_owl",
            "name" to "Night Owl",
            "description" to "Listen after midnight",
            "icon_name" to "ic_badge_owl",
            "category" to "TIMING",
            "earned_at" to 0L,
            "progress" to 4,
            "max_progress" to 10,
            "is_earned" to 0,
            "stars" to 0,
            "is_acknowledged" to 0,
        )

        row(
            "user_level",
            "id" to 1,
            "total_xp" to 12_345,
            "current_level" to 17,
            "xp_for_current_level" to 12_000,
            "xp_for_next_level" to 13_000,
            "last_xp_awarded_at" to 1_735_700_000_000L,
            "current_streak" to 41,
            "longest_streak" to 110,
            "last_streak_date" to "2026-01-31",
        )

        // Two challenge groups for the 53 -> 54 dedupe to collapse, one where completion outranks
        // progress and one where it does not, plus a row with nothing to dedupe. The second row of
        // each pair is only planted on schemas that predate the unique index: from 54 on, a real
        // install cannot hold two rows for one challenge and day, so neither may the fixture.
        row(
            "daily_challenges",
            "id" to 10,
            "challenge_id" to "daily_plays",
            "date" to "2026-01-05",
            "title" to "Play 10 songs",
            "description" to "Listen to ten songs today",
            "xp_reward" to 50,
            "target_value" to 10,
            "current_progress" to 5,
            "is_completed" to 0,
            "completed_at" to 0L,
            "category" to "PLAYBACK",
            "difficulty" to "EASY",
            "target_metadata" to "{\"plays\":10}",
        )
        if (dedupesChallenges) {
            row(
                "daily_challenges",
                "id" to 11,
            "challenge_id" to "daily_plays",
            "date" to "2026-01-05",
            "title" to "Play 10 songs",
            "description" to "Listen to ten songs today",
            "xp_reward" to 50,
            "target_value" to 10,
            "current_progress" to 9,
            "is_completed" to 0,
            "completed_at" to 0L,
                "category" to "PLAYBACK",
                "difficulty" to "EASY",
                "target_metadata" to "{\"plays\":10}",
            )
        }
        row(
            "daily_challenges",
            "id" to 12,
            "challenge_id" to "daily_discovery",
            "date" to "2026-01-06",
            "title" to "Discover an artist",
            "description" to "Play an artist for the first time",
            "xp_reward" to 80,
            "target_value" to 1,
            "current_progress" to 1,
            "is_completed" to 1,
            "completed_at" to 1_735_700_000_000L,
            "category" to "DISCOVERY",
            "difficulty" to "MEDIUM",
            "target_metadata" to null,
        )
        if (dedupesChallenges) {
            row(
                "daily_challenges",
                "id" to 13,
            "challenge_id" to "daily_discovery",
            "date" to "2026-01-06",
            "title" to "Discover an artist",
            "description" to "Play an artist for the first time",
            "xp_reward" to 80,
            "target_value" to 1,
            "current_progress" to 1,
            "is_completed" to 0,
            "completed_at" to 0L,
                "category" to "DISCOVERY",
                "difficulty" to "MEDIUM",
                "target_metadata" to null,
            )
        }
        row(
            "daily_challenges",
            "id" to 14,
            "challenge_id" to "daily_streak",
            "date" to "2026-01-07",
            "title" to "Keep the streak",
            "description" to "Listen at least once today",
            "xp_reward" to 20,
            "target_value" to 1,
            "current_progress" to 1,
            "is_completed" to 1,
            "completed_at" to 1_735_800_000_000L,
            "category" to "STREAK",
            "difficulty" to "EASY",
            "target_metadata" to null,
        )

        row(
            "track_aliases",
            "id" to 1,
            "target_track_id" to 4,
            "original_title" to "Dog Days Are Over",
            "original_artist" to "Florence + The Machine",
            "created_at" to 1_735_000_000_000L,
        )
        row(
            "artist_aliases",
            "id" to 1,
            "target_artist_id" to 2,
            "original_name" to "Ryuichi Sakamoto",
            "original_name_normalized" to "ryuichi sakamoto",
            "created_at" to 1_735_000_000_000L,
        )
        row(
            "manual_content_marks",
            "id" to 1,
            "target_track_id" to 5,
            "pattern_type" to "TRACK",
            "original_title" to "Episode 412: The One About Streaks",
            "original_artist" to "Tempo Weekly",
            "pattern_value" to "tempo weekly",
            "content_type" to "PODCAST",
            "marked_at" to 1_735_000_000_000L,
        )
        row(
            "lastfm_import_metadata",
            "id" to 1,
            "lastfm_username" to "listener_42",
            "import_tier" to "FULL",
            "active_track_threshold" to 50,
            "recent_months_included" to 12,
            "total_scrobbles_found" to 48_213,
            "earliest_scrobble" to 1_500_000_000_000L,
            "latest_scrobble" to 1_735_000_000_000L,
            "status" to "COMPLETED",
            "current_page" to 24,
            "total_pages" to 24,
            "scrobbles_processed" to 48_213,
            "events_imported" to 12_004,
            "tracks_created" to 812,
            "artists_created" to 240,
            "scrobbles_archived" to 36_209,
            "duplicates_skipped" to 1_204,
            "last_sync_cursor" to 1_735_000_000_000L,
            "last_sync_timestamp" to 1_735_000_000_000L,
            "import_started_at" to 1_734_000_000_000L,
            "import_completed_at" to 1_735_000_000_000L,
            "error_message" to null,
        )
        row(
            "user_known_artists",
            "id" to 1,
            "name" to "Ryuichi Sakamoto",
            "normalized_name" to "ryuichi sakamoto",
            "created_at" to 1_735_000_000_000L,
        )
        row(
            "user_known_artists",
            "id" to 2,
            "name" to "Florence + The Machine",
            "normalized_name" to "florence + the machine",
            "created_at" to 1_735_000_100_000L,
        )
        row(
            "desktop_pairing_sessions",
            "id" to "pair-1",
            "auth_token" to "token-abc",
            "device_name" to "Avinash's MacBook",
            "paired_at_ms" to 1_735_000_000_000L,
            "last_seen_ms" to 1_735_500_000_000L,
            "is_active" to 1,
            "desktop_ip" to "192.168.1.24",
            "desktop_port" to 8420,
            "phone_public_key" to "phone-key",
            "desktop_public_key" to "desktop-key",
            "token_version" to 2,
        )

        return rows
    }

    /**
     * Inserts one row, restricted to the columns the start schema actually has: the fixture is
     * written in terms of the current schema, and columns that did not exist yet simply are not
     * part of the row an install of that version could hold.
     */
    private fun insert(table: String, values: Map<String, Any?>) {
        val info = tableInfo(table) ?: return
        val provided = values.filterKeys { it in info.keys }.toMutableMap()
        provideZoneOffset(table, provided)
        val missingRequired =
            info.filterValues { it.notNull && it.defaultValue == null && it.name !in provided.keys }

        val columns = provided.keys + missingRequired.keys
        if (columns.isEmpty()) return

        val sql =
            "INSERT INTO `$table` (${columns.joinToString(", ") { "`$it`" }}) " +
                "VALUES (${columns.joinToString(", ") { "?" }})"
        connection.prepareStatement(sql).use { ps ->
            columns.forEachIndexed { index, column ->
                // A column the fixture spells out as null is a NULL the user's library really
                // holds; only a column the fixture never mentions falls back to a type default.
                val value =
                    if (provided.containsKey(column)) {
                        provided[column]
                    } else {
                        typeDefault(info.getValue(column).affinity)
                    }
                when (value) {
                    null -> ps.setObject(index + 1, null)
                    is ByteArray -> ps.setBytes(index + 1, value)
                    is Long -> ps.setLong(index + 1, value)
                    is Int -> ps.setInt(index + 1, value)
                    else -> ps.setObject(index + 1, value)
                }
            }
            ps.executeUpdate()
        }
    }

    /**
     * A play written from schema 58 on carries the zone it happened in. The fixture is spelled
     * against the current schema and names no offset, so an install that already has the column
     * would otherwise be seeded with NULLs no phone ever wrote -- and the 58 -> 59 step does not
     * backfill, because a 58 install is meant to hold real offsets already. Installs below 58
     * never reach this branch: the column is not in their table, so the migration's own
     * backfill stays the thing under test for them.
     */
    private fun provideZoneOffset(table: String, values: MutableMap<String, Any?>) {
        if (table != "listening_events") return
        val columns = tableInfo(table).orEmpty()
        if ("tz_offset_minutes" !in columns || "tz_offset_minutes" in values) return
        val timestamp = values["timestamp"] as? Long ?: return
        values["tz_offset_minutes"] = zoneOffsetMinutesAt(timestamp)
    }

    /**
     * The offset `'localtime'` resolves for [timestamp], in the minutes the column stores -- the
     * same number the 55 -> 58 backfill derives from `'localtime'`, so a 58 install's rows and a
     * migrated one's rows hold the same kind of value.
     */
    private fun zoneOffsetMinutesAt(timestamp: Long): Long {
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        val offset = java.time.ZoneId.systemDefault().rules.getOffset(instant)
        return (offset.totalSeconds / 60).toLong()
    }

    private data class ColumnInfo(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private val tableInfoCache = mutableMapOf<String, Map<String, ColumnInfo>?>()

    private fun tableInfo(table: String): Map<String, ColumnInfo>? =
        tableInfoCache.getOrPut(table) {
            val info = linkedMapOf<String, ColumnInfo>()
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    var any = false
                    while (rs.next()) {
                        any = true
                        val name = rs.getString("name")
                        info[name] =
                            ColumnInfo(
                                name = name,
                                affinity = LiveDatabase.affinityOf(rs.getString("type") ?: ""),
                                notNull = rs.getInt("notnull") != 0,
                                defaultValue = rs.getString("dflt_value"),
                            )
                    }
                    if (!any) return@getOrPut null
                }
            }
            info
        }

    private fun typeDefault(affinity: String): Any? =
        when (affinity) {
            "INTEGER", "REAL", "NUMERIC" -> 0L
            "BLOB" -> ByteArray(0)
            else -> ""
        }

    // ---- JDBC-backed SupportSQLiteDatabase ---------------------------------

    private fun proxyDatabase(): SupportSQLiteDatabase = proxy { sql -> connection.createStatement().use { it.execute(sql) } }

    /** Minimal [SupportSQLiteDatabase] proxy over JDBC, as the other migration tests use. */
    private fun proxy(onExecSql: (String) -> Unit): SupportSQLiteDatabase {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        onExecSql(args[0] as String)
                        null
                    }

                    "query" -> {
                        val statement = connection.createStatement()
                        val resultSet = statement.executeQuery(args[0] as String)
                        cursorProxy(statement, resultSet)
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
        statement: Statement,
        resultSet: ResultSet,
    ): Cursor {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "moveToNext" -> resultSet.next()
                    "getString" -> resultSet.getString((args[0] as Int) + 1)
                    "getLong" -> resultSet.getLong((args[0] as Int) + 1)
                    "getInt" -> resultSet.getInt((args[0] as Int) + 1)
                    "getColumnIndex" ->
                        try {
                            resultSet.findColumn(args[0] as String) - 1
                        } catch (e: Exception) {
                            -1
                        }

                    "getColumnCount" -> resultSet.metaData.columnCount
                    "close" -> {
                        resultSet.close()
                        statement.close()
                        null
                    }

                    "isAfterLast" -> resultSet.isAfterLast
                    "isClosed" -> resultSet.isClosed
                    "getCount" -> 0
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
