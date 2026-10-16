package me.avinas.tempo.data.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing a database may reassign every Room event ID, for all accounts. */
class DriveRestoreCursorInvalidationTest {
    @Test fun `all account progress and invalid IDs must be invalidated after restore`() {
        val oldKeys = listOf(
            "upload_cursor:google-a",
            "upload_cursor:google-b",
            "invalid_drive_export_ids:google-a",
            "invalid_drive_export_ids:google-b",
        )
        for (key in oldKeys) {
            assertTrue("$key must be invalidated after database restore",
                DriveHistorySyncManager.isPerAccountRestoreStateKey(key))
        }
    }

    @Test fun `restore preserves global and per-account cloud deletion checkpoints`() {
        for (key in listOf(
            "google_account_subject",
            "google_account_email",
            "accepted_disable_marker_version",
            "accepted_disable_marker_version:google-a",
            "accepted_disable_marker_version:google-b",
            "device_id",
            "download_created_cursor",
        )) {
            assertFalse("$key is not a per-account old row-ID reference",
                DriveHistorySyncManager.isPerAccountRestoreStateKey(key))
        }
    }
}
