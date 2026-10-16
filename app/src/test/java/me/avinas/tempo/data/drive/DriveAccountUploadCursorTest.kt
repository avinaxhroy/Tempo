package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** Account-scoped upload progress must survive A -> B -> A switching. */
class DriveAccountUploadCursorTest {
    @Test
    fun pendingOldAccountHistorySurvivesRoundTrip() {
        val select = { scoped: Long?, legacy: Long?, switched: Boolean, current: Long, max: Long ->
            DriveHistorySyncManager.chooseAccountUploadCursor(scoped, legacy, switched, current, max)
        }

        // A has only uploaded rows <=90, but has 100 local rows.
        val newB = select(null, null, true, 90L, 100L)
        assertEquals(100L, newB) // B may not publish A's old rows.

        // B records and uploads additional history, but A still needs 91..100.
        val returnA = select(90L, null, true, 110L, 110L)
        assertEquals(90L, returnA)
        assertEquals(110L, select(110L, null, true, 101L, 120L))
    }

    @Test
    fun legacyEmailCursorCanBeRecoveredAfterStableIdMigration() {
        val select = { scoped: Long?, legacy: Long?, switched: Boolean, current: Long, max: Long ->
            DriveHistorySyncManager.chooseAccountUploadCursor(scoped, legacy, switched, current, max)
        }
        assertEquals(90L, select(null, 90L, true, 130L, 135L))
        assertEquals(75L, select(75L, 90L, true, 130L, 135L))
    }

    @Test
    fun deletionSuppressesOldRecordsButAllowsLaterOfflineCaptures() {
        val deletedThrough = 500L
        val cloudDeletionUtc = 1_700_000_000_000L
        assertEquals(true, DriveHistorySyncManager.isCloudSuppressedRow(
            1L, deletedThrough, cloudDeletionUtc - 1_000, cloudDeletionUtc, 0L,
        ))
        assertEquals(true, DriveHistorySyncManager.isCloudSuppressedRow(
            500L, deletedThrough, cloudDeletionUtc, cloudDeletionUtc, 0L,
        ))
        assertEquals(false, DriveHistorySyncManager.isCloudSuppressedRow(
            501L, deletedThrough, cloudDeletionUtc - 1_000, cloudDeletionUtc, 0L,
        ))
        assertEquals(false, DriveHistorySyncManager.isCloudSuppressedRow(
            250L, deletedThrough, cloudDeletionUtc + 1_000, cloudDeletionUtc, 0L,
        ))
        assertEquals(false, DriveHistorySyncManager.isCloudSuppressedRow(
            1L, 0L, cloudDeletionUtc - 1_000, cloudDeletionUtc, 0L,
        ))
        assertEquals(true, DriveHistorySyncManager.isCloudSuppressedRow(
            10L, deletedThrough, cloudDeletionUtc + 5_000, cloudDeletionUtc, 100L,
        )) // Already uploaded before delete: do not resurrect clock-skewed tracks.
    }

    @Test
    fun lanRelayNeverChangesGoogleOwnerWithoutExplicitConsent() {
        val permits = DriveHistorySyncManager::canRelayLanToCloud
        val marker = 1_700_000_000_000L
        val oldPlay = marker - 5_000L
        val newPlay = marker + 5_000L

        // A device pairing proves the sender, not the account that owns its music.
        assertEquals(false, permits("lan-unverified", "google-b", oldPlay, 501L, 0L, 0L))
        assertEquals(false, permits("google-a", "google-b", oldPlay, 501L, marker, 0L))
        assertEquals(false, permits(null, "google-b", newPlay, 501L, marker, 0L))

        // A late LAN delivery created before cloud deletion must not resurrect it.
        assertEquals(false, permits("google-a", "google-a", oldPlay, 501L, marker, 0L))
        assertEquals(true, permits("google-a", "google-a", newPlay, 501L, marker, 0L))

        // The user may authorize only records present in the approved snapshot.
        assertEquals(true, permits("google-a", "google-a", oldPlay, 500L, marker, 500L))
        assertEquals(false, permits("google-a", "google-a", oldPlay, 501L, marker, 500L))
        assertEquals(true, permits("google-a", "google-a", oldPlay, 10L, 0L, 0L))
    }

    @Test
    fun firstExplicitOptInDoesNotDiscardExistingLocalHistory() {
        assertEquals(0L, DriveHistorySyncManager.chooseAccountUploadCursor(
            null, null, false, 0L, 5000L,
        ))
    }

    @Test
    fun deletionResetRestartsOnlySelectedAccountsCursor() {
        assertEquals(0L, DriveHistorySyncManager.chooseAccountUploadCursor(
            null, null, false, 0L, 100L,
        ))
    }
}
