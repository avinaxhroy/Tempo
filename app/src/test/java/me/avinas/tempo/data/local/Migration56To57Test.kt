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

/** Test the actual v56 -> v57 migration with a SQLite file-shaped fixture. */
class Migration56To57Test {
    private lateinit var conn: Connection

    @Before fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use {
            it.execute("CREATE TABLE listening_events (id INTEGER PRIMARY KEY, source TEXT NOT NULL)")
            it.execute("INSERT INTO listening_events VALUES (1, 'drive:producer-a:desktop:spotify')")
            it.execute("INSERT INTO listening_events VALUES (2, 'local:spotify')")
            it.execute("INSERT INTO listening_events VALUES (3, 'drive:producer-b:browser')")
        }
    }

    @After fun tearDown() = conn.close()

    private fun proxy(): SupportSQLiteDatabase {
        val handler = InvocationHandler { _, method, args ->
            if (method.name == "execSQL") {
                conn.createStatement().use { it.execute(args[0] as String) }
                null
            } else throw UnsupportedOperationException("Unexpected migration method: ${method.name}")
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java), handler
        ) as SupportSQLiteDatabase
    }

    @Test fun `legacy cloud imports are quarantined but local history is preserved`() {
        AppDatabase.MIGRATION_56_57.migrate(proxy())
        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id, drive_account_subject FROM listening_events ORDER BY id"
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(1L, rows.getLong(1))
                assertEquals("legacy-unverified", rows.getString(2))
                assertTrue(rows.next())
                assertEquals(2L, rows.getLong(1))
                assertEquals(null, rows.getString(2))
                assertTrue(rows.next())
                assertEquals(3L, rows.getLong(1))
                assertEquals("legacy-unverified", rows.getString(2))
            }
            statement.executeQuery("SELECT COUNT(*) FROM listening_events").use { rows ->
                assertTrue(rows.next())
                assertEquals(3, rows.getInt(1))
            }
            statement.executeQuery("PRAGMA index_list(listening_events)").use { rows ->
                val indices = mutableListOf<String>()
                while (rows.next()) indices += rows.getString("name")
                assertTrue(indices.contains("index_listening_events_drive_account_subject"))
            }
        }
    }
}
