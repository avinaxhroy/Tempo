package me.avinas.tempo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.DriverManager

/** Real SQLite rebuild: preserves old producer claims and allows account reuse. */
class Migration57To58Test {
    @Test fun `producer alias belongs to its Google account not the entire device`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("CREATE TABLE listening_events " +
                    "(id INTEGER PRIMARY KEY, drive_account_subject TEXT)")
                stmt.execute("INSERT INTO listening_events VALUES (1, 'google-a')")
                stmt.execute("INSERT INTO listening_events VALUES (2, 'google-b')")
                stmt.execute("INSERT INTO listening_events VALUES (3, NULL)")
                stmt.execute("CREATE TABLE listening_event_origins (" +
                    "originEventId TEXT NOT NULL PRIMARY KEY, " +
                    "listeningEventId INTEGER NOT NULL, sourceDeviceId TEXT NOT NULL, " +
                    "FOREIGN KEY(listeningEventId) REFERENCES listening_events(id) ON DELETE CASCADE)")
                stmt.execute("CREATE INDEX index_listening_event_origins_listeningEventId " +
                    "ON listening_event_origins(listeningEventId)")
                stmt.execute("CREATE UNIQUE INDEX index_listening_event_origins_listeningEventId_sourceDeviceId " +
                    "ON listening_event_origins(listeningEventId, sourceDeviceId)")
                stmt.execute("INSERT INTO listening_event_origins VALUES ('same-origin', 1, 'source-a')")
                stmt.execute("INSERT INTO listening_event_origins VALUES ('unknown-origin', 3, 'source-z')")
            }
            val proxy = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "execSQL") {
                        conn.createStatement().use { it.execute(args[0] as String) }
                        null
                    } else throw UnsupportedOperationException(method.name)
                },
            ) as SupportSQLiteDatabase
            AppDatabase.MIGRATION_57_58.migrate(proxy)
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT accountSubject FROM listening_event_origins " +
                    "WHERE originEventId = 'same-origin'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("google-a", rows.getString(1))
                }
                stmt.executeQuery("SELECT accountSubject FROM listening_event_origins " +
                    "WHERE originEventId = 'unknown-origin'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("legacy-unverified", rows.getString(1))
                }
                // Same producer event can independently exist in B.
                stmt.execute("INSERT INTO listening_event_origins " +
                    "(accountSubject, originEventId, listeningEventId, sourceDeviceId) " +
                    "VALUES ('google-b', 'same-origin', 2, 'source-a')")
                stmt.executeQuery("SELECT COUNT(*) FROM listening_event_origins " +
                    "WHERE originEventId = 'same-origin'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                }
                stmt.execute("DELETE FROM listening_events WHERE id=1")
                stmt.executeQuery("SELECT COUNT(*) FROM listening_event_origins " +
                    "WHERE accountSubject = 'google-a'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }
}
