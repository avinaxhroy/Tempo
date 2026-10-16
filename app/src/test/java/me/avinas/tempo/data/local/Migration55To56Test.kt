package me.avinas.tempo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Exercises the REAL Room 55->56 SQL with minSdk-26-compatible SQLite. */
class Migration55To56Test {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute(
                """CREATE TABLE listening_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source TEXT NOT NULL,
                    content_fingerprint TEXT
                )""",
            )
            it.execute(
                """INSERT INTO listening_events (id, source, content_fingerprint)
                    VALUES (1, 'drive:producer-one:desktop:Spotify',
                        'drive:v1:' || 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')""",
            )
            it.execute("INSERT INTO listening_events (id, source) VALUES (2, 'android')")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun migrate() = AppDatabase.MIGRATION_55_56.migrate(proxyDatabase())

    private fun proxyDatabase(): SupportSQLiteDatabase {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    connection.createStatement().use { it.execute(args[0] as String) }
                    null
                }
                else -> throw UnsupportedOperationException("Unexpected migration method: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    private fun count(): Int = connection.createStatement().use {
        it.executeQuery("SELECT COUNT(*) FROM listening_event_origins").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    @Test
    fun `backfill is idempotent and preserves every listening event`() {
        migrate()
        assertEquals(1, count())
        connection.createStatement().use {
            it.executeQuery(
                "SELECT listeningEventId, sourceDeviceId, originEventId FROM listening_event_origins",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong("listeningEventId"))
                assertEquals("producer-one", rs.getString("sourceDeviceId"))
                assertEquals("a".repeat(64), rs.getString("originEventId"))
            }
        }
        migrate()
        assertEquals(1, count())
        connection.createStatement().use {
            it.executeQuery("SELECT COUNT(*) FROM listening_events").use { rs ->
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    @Test
    fun `same producer cannot claim two events for one playback across batches`() {
        migrate()
        val rejected = try {
            connection.createStatement().use {
                it.execute(
                    """INSERT INTO listening_event_origins
                        (originEventId, listeningEventId, sourceDeviceId)
                        VALUES ('${"b".repeat(64)}', 1, 'producer-one')""",
                )
            }
            false
        } catch (_: SQLException) {
            true
        }
        assertTrue("One event per producer per playback is enforced in SQLite", rejected)
        connection.createStatement().use {
            it.execute(
                """INSERT INTO listening_event_origins
                    (originEventId, listeningEventId, sourceDeviceId)
                    VALUES ('${"b".repeat(64)}', 1, 'producer-two')""",
            )
        }
        assertEquals(2, count())
    }

    @Test
    fun `deleting a listening event cascades to its aliases`() {
        migrate()
        connection.createStatement().use {
            it.execute("DELETE FROM listening_events WHERE id = 1")
        }
        assertEquals(0, count())
    }
}
