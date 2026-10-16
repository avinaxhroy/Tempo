package me.avinas.tempo.data.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAccountSwitchingTest {

    @Test
    fun `rejected batch record serializes and parses properly`() {
        val record = DriveHistorySyncManager.Companion.RejectedBatchRecord(
            fileId = "file-123",
            fileName = "tempo_history_device_1_0.json.gz",
            reason = "Malformed\tpayload with\nnewline",
            recordedAt = 1700000000000L
        )
        val serialized = record.serialize()
        val parsed = DriveHistorySyncManager.Companion.RejectedBatchRecord.parse(serialized)

        assertNotNull(parsed)
        assertEquals("file-123", parsed?.fileId)
        assertEquals("tempo_history_device_1_0.json.gz", parsed?.fileName)
        // Tabs and newlines should be sanitized to single spaces
        assertEquals("Malformed payload with newline", parsed?.reason)
        assertEquals(1700000000000L, parsed?.recordedAt)
    }

    @Test
    fun `rejected batch record handles corrupt raw strings gracefully`() {
        assertNull(DriveHistorySyncManager.Companion.RejectedBatchRecord.parse("invalid"))
        assertNull(DriveHistorySyncManager.Companion.RejectedBatchRecord.parse("id\tname"))
    }

    @Test
    fun `incremental listing never clears older rejected file warnings`() {
        val older = "file-older\tcorrupt.json.gz\tBad metadata\t1700000000000"
        val recent = "file-recent\tbroken.json.gz\tBad payload\t1700100000000"
        val records = setOf(older, recent)

        assertEquals(records, DriveHistorySyncManager.retainedRejectedRecords(
            records, setOf("file-recent"), completeListing = false))
        assertEquals(setOf(recent), DriveHistorySyncManager.retainedRejectedRecords(
            records, setOf("file-recent"), completeListing = true))
        assertTrue(DriveHistorySyncManager.retainedRejectedRecords(
            records, emptySet(), completeListing = true).isEmpty())
    }

    @Test
    fun `restore clears row-id progress but preserves accepted cloud deletion generations`() {
        val resetKeys = listOf(
            "download_created_cursor:google-a",
            "drive_rejected_files:google-a",
            "upload_cursor:google-a",
            "invalid_drive_export_ids:google-a",
        )
        for (key in resetKeys) {
            assertTrue("Key $key must be reset after a local database restore",
                DriveHistorySyncManager.isPerAccountRestoreStateKey(key))
        }

        for (key in listOf(
            "accepted_disable_marker_version:google-a",
            "accepted_disable_marker_version:google-b",
        )) {
            assertFalse("Key $key must survive restores to prevent stale re-deletion",
                DriveHistorySyncManager.isPerAccountRestoreStateKey(key))
        }
    }
    @Test
    fun `cleanup best effort does not block history recovery on failed Drive DELETE`() = runBlocking {
        val failures = mutableListOf<Exception>()
        val cleaned = DriveHistorySyncManager.cleanupStaleBatchBestEffort(
            delete = { Unit },
            onError = { failure -> failures.add(failure); Unit },
        )
        assertTrue(cleaned)
        assertTrue(failures.isEmpty())

        val retryable = IllegalStateException("temporary Drive DELETE failure")
        val ignored = DriveHistorySyncManager.cleanupStaleBatchBestEffort(
            delete = { throw retryable },
            onError = { failure -> failures.add(failure); Unit },
        )
        assertFalse(ignored)
        assertEquals(listOf(retryable), failures)
    }

    @Test
    fun `cleanup best effort never swallows coroutine cancellation`() = runBlocking {
        val cancellation = CancellationException("worker stopped")
        var failureReported = false
        try {
            DriveHistorySyncManager.cleanupStaleBatchBestEffort(
                delete = { throw cancellation },
                onError = { failureReported = true },
            )
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
            assertFalse(failureReported)
        }
    }

}
