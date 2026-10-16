package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * SQLite regression fixtures for explicit LAN history consent and the
 * separate exact-origin promotion when a verified Drive batch arrives.
 */
class LanOriginPromotionTest {
    @Test fun `unverified LAN records require explicit snapshot consent before Drive relay`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE listening_events (" +
                    "id INTEGER PRIMARY KEY, source TEXT NOT NULL, " +
                    "content_fingerprint TEXT, drive_account_subject TEXT)")
                sql.execute("CREATE TABLE listening_event_origins (" +
                    "accountSubject TEXT NOT NULL, originEventId TEXT NOT NULL, " +
                    "listeningEventId INTEGER NOT NULL, sourceDeviceId TEXT NOT NULL, " +
                    "PRIMARY KEY(accountSubject, originEventId))")
                val first = "a".repeat(64)
                val other = "b".repeat(64)
                val later = "c".repeat(64)
                sql.execute("INSERT INTO listening_events VALUES " +
                    "(1, 'lan:desktop-one:desktop:spotify', 'drive:v1:$first', 'lan-unverified')," +
                    "(2, 'desktop:legacy', NULL, 'lan-unverified')," +
                    "(3, 'lan:desktop-one:desktop:spotify', 'drive:v1:$other', 'google-b')," +
                    "(4, 'lan:desktop-two:desktop:spotify', 'drive:v1:$later', 'lan-unverified')")
                sql.execute("INSERT INTO listening_event_origins VALUES " +
                    "('lan-unverified', '$first', 1, 'desktop-one')," +
                    "('google-b', '$other', 3, 'desktop-one')")
                val earliest = sql.executeQuery(
                    "SELECT MIN(id) FROM listening_events WHERE drive_account_subject = 'lan-unverified' " +
                        "AND source LIKE 'lan:%' AND content_fingerprint LIKE 'drive:v1:%'"
                ).use { rows -> if (rows.next()) rows.getLong(1) else 0L }
                assertEquals(1L, earliest)
                // User deliberately approved rows 1..3; a late row 4 must
                // remain quarantined even if it arrived before the SQL update.
                val claimed = db.prepareStatement(
                    "UPDATE listening_events SET drive_account_subject = ? " +
                        "WHERE id <= ? AND drive_account_subject = 'lan-unverified' " +
                        "AND source LIKE 'lan:%' AND content_fingerprint LIKE 'drive:v1:%'"
                ).use { stmt ->
                    stmt.setString(1, "google-a")
                    stmt.setLong(2, 3L)
                    stmt.executeUpdate()
                }
                assertEquals(1, claimed)
                val aliases = db.prepareStatement(
                    "UPDATE listening_event_origins SET accountSubject = ? " +
                        "WHERE accountSubject = 'lan-unverified' AND listeningEventId IN (" +
                        "SELECT id FROM listening_events WHERE id <= ? " +
                        "AND drive_account_subject = ? " +
                        "AND source LIKE 'lan:%' AND content_fingerprint LIKE 'drive:v1:%') " +
                        "AND NOT EXISTS (SELECT 1 FROM listening_event_origins owned " +
                        "WHERE owned.accountSubject = ? " +
                        "AND owned.originEventId = listening_event_origins.originEventId)"
                ).use { stmt ->
                    stmt.setString(1, "google-a")
                    stmt.setLong(2, 3L)
                    stmt.setString(3, "google-a")
                    stmt.setString(4, "google-a")
                    stmt.executeUpdate()
                }
                assertEquals(1, aliases)
                sql.executeQuery("SELECT id, drive_account_subject FROM listening_events ORDER BY id")
                    .use { rows ->
                        val owners = mutableListOf<String>()
                        while (rows.next()) owners += "${rows.getLong(1)}:${rows.getString(2)}"
                        assertEquals(listOf(
                            "1:google-a", "2:lan-unverified", "3:google-b", "4:lan-unverified",
                        ), owners)
                    }
                sql.executeQuery("SELECT accountSubject FROM listening_event_origins WHERE originEventId = '$first'")
                    .use { rows ->
                        assertTrue(rows.next())
                        assertEquals("google-a", rows.getString(1))
                    }
            }
        }
    }

    @Test fun `verified Drive origin adopts exact LAN capture and its alias only`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE listening_events (" +
                    "id INTEGER PRIMARY KEY, source TEXT NOT NULL, " +
                    "content_fingerprint TEXT, drive_account_subject TEXT)")
                sql.execute("CREATE TABLE listening_event_origins (" +
                    "accountSubject TEXT NOT NULL, originEventId TEXT NOT NULL, " +
                    "listeningEventId INTEGER NOT NULL, sourceDeviceId TEXT NOT NULL, " +
                    "PRIMARY KEY(accountSubject, originEventId))")
                val origin = "a".repeat(64)
                val fingerprint = "drive:v1:$origin"
                sql.execute("INSERT INTO listening_events VALUES " +
                    "(1, 'lan:desktop-one:desktop:spotify', '$fingerprint', 'lan-unverified')," +
                    "(2, 'lan:other:desktop:spotify', 'drive:v1:${"b".repeat(64)}', 'lan-unverified')," +
                    "(3, 'drive:desktop-one:desktop:spotify', '$fingerprint', 'google-a')")
                sql.execute("INSERT INTO listening_event_origins VALUES " +
                    "('lan-unverified', '$origin', 1, 'desktop-one')")
                // Same producer event in A should not prevent attribution to B,
                // nor should this operation reassign A's already verified row.
                val count = db.prepareStatement(
                    "UPDATE listening_events SET drive_account_subject = ? " +
                        "WHERE source LIKE 'lan:%' AND drive_account_subject = 'lan-unverified' " +
                        "AND content_fingerprint IN (?) AND NOT EXISTS (" +
                        "SELECT 1 FROM listening_events owned " +
                        "WHERE owned.drive_account_subject = ? " +
                        "AND owned.content_fingerprint = listening_events.content_fingerprint)"
                ).use { stmt ->
                    stmt.setString(1, "google-b")
                    stmt.setString(2, fingerprint)
                    stmt.setString(3, "google-b")
                    stmt.executeUpdate()
                }
                assertEquals(1, count)
                db.prepareStatement(
                    "UPDATE listening_event_origins SET accountSubject = ? " +
                        "WHERE accountSubject = 'lan-unverified' AND listeningEventId IN (" +
                        "SELECT id FROM listening_events WHERE drive_account_subject = ? " +
                        "AND source LIKE 'lan:%' AND content_fingerprint IN (?)) " +
                        "AND NOT EXISTS (SELECT 1 FROM listening_event_origins claimed " +
                        "WHERE claimed.accountSubject = ? " +
                        "AND claimed.originEventId = listening_event_origins.originEventId)"
                ).use { stmt ->
                    stmt.setString(1, "google-b")
                    stmt.setString(2, "google-b")
                    stmt.setString(3, fingerprint)
                    stmt.setString(4, "google-b")
                    assertEquals(1, stmt.executeUpdate())
                }
                sql.executeQuery("SELECT id, drive_account_subject FROM listening_events ORDER BY id").use { rows ->
                    val owners = mutableListOf<String>()
                    while (rows.next()) owners += "${rows.getInt(1)}:${rows.getString(2)}"
                    assertEquals(listOf("1:google-b", "2:lan-unverified", "3:google-a"), owners)
                }
                sql.executeQuery("SELECT accountSubject FROM listening_event_origins WHERE originEventId = '$origin'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("google-b", rows.getString(1))
                }
            }
        }
    }
}
