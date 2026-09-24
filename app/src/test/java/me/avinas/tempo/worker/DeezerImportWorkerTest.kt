package me.avinas.tempo.worker

import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeezerImportWorkerTest {

    @Test
    fun workerInputAndOutputKeysAreConsistent() {
        val inputData = workDataOf(
            DeezerImportWorker.KEY_FILE_URI to "file:///data/user/0/me.avinas.tempo/files/deezer_imports/deezer-import-1234.xlsx",
        )
        assertEquals(
            "file:///data/user/0/me.avinas.tempo/files/deezer_imports/deezer-import-1234.xlsx",
            inputData.getString(DeezerImportWorker.KEY_FILE_URI),
        )

        val enqueueResult =
            DeezerImportWorker.EnqueueResult(
                workId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000004"),
                requestAccepted = false,
            )
        assertEquals(false, enqueueResult.requestAccepted)

        val outputSuccess = workDataOf(
            DeezerImportWorker.KEY_SUCCESS to true,
            DeezerImportWorker.KEY_TRACKS_IMPORTED to 15,
            DeezerImportWorker.KEY_EVENTS_CREATED to 100,
            DeezerImportWorker.KEY_DUPLICATES_SKIPPED to 2,
            DeezerImportWorker.KEY_SHORT_PLAYS_SKIPPED to 5,
            DeezerImportWorker.KEY_MALFORMED_ROWS to 0,
            DeezerImportWorker.KEY_TOTAL_ENTRIES to 107,
            DeezerImportWorker.KEY_WARNINGS to arrayOf("Metadata warning", "Artist warning"),
        )
        assertEquals(true, outputSuccess.getBoolean(DeezerImportWorker.KEY_SUCCESS, false))
        assertEquals(15, outputSuccess.getInt(DeezerImportWorker.KEY_TRACKS_IMPORTED, -1))
        assertEquals(100, outputSuccess.getInt(DeezerImportWorker.KEY_EVENTS_CREATED, -1))
        assertEquals(2, outputSuccess.getInt(DeezerImportWorker.KEY_DUPLICATES_SKIPPED, -1))
        assertEquals(5, outputSuccess.getInt(DeezerImportWorker.KEY_SHORT_PLAYS_SKIPPED, -1))
        assertEquals(0, outputSuccess.getInt(DeezerImportWorker.KEY_MALFORMED_ROWS, -1))
        assertEquals(107, outputSuccess.getInt(DeezerImportWorker.KEY_TOTAL_ENTRIES, -1))
        assertEquals(
            listOf("Metadata warning", "Artist warning"),
            outputSuccess.getStringArray(DeezerImportWorker.KEY_WARNINGS)?.toList(),
        )

        val outputFailure = workDataOf(
            DeezerImportWorker.KEY_SUCCESS to false,
            DeezerImportWorker.KEY_ERROR_MESSAGE to "No file selected",
        )
        assertEquals(false, outputFailure.getBoolean(DeezerImportWorker.KEY_SUCCESS, true))
        assertEquals("No file selected", outputFailure.getString(DeezerImportWorker.KEY_ERROR_MESSAGE))
    }

    @Test
    fun calculatesProgressCorrectlyForVariousTotals() {
        // Zero or unknown total is indeterminate
        val unknown = DeezerImportWorker.calculateProgress(current = 0, total = 0)
        assertEquals(0, unknown.max)
        assertEquals(0, unknown.progress)
        assertEquals(true, unknown.indeterminate)

        // Small export (e.g. 20 items) uses actual total as max so progress scales to 100%
        val smallFinished = DeezerImportWorker.calculateProgress(current = 20, total = 20)
        assertEquals(20, smallFinished.max)
        assertEquals(20, smallFinished.progress)
        assertEquals(false, smallFinished.indeterminate)

        // Mid-progress on small export
        val smallMid = DeezerImportWorker.calculateProgress(current = 5, total = 20)
        assertEquals(20, smallMid.max)
        assertEquals(5, smallMid.progress)
        assertEquals(false, smallMid.indeterminate)

        // Large export
        val large = DeezerImportWorker.calculateProgress(current = 500, total = 1000)
        assertEquals(1000, large.max)
        assertEquals(500, large.progress)
        assertEquals(false, large.indeterminate)

        val overflow = DeezerImportWorker.calculateProgress(current = 25, total = 20)
        assertEquals(20, overflow.max)
        assertEquals(20, overflow.progress)
        assertEquals(false, overflow.indeterminate)

        val negative = DeezerImportWorker.calculateProgress(current = -5, total = 20)
        assertEquals(20, negative.max)
        assertEquals(0, negative.progress)
        assertEquals(false, negative.indeterminate)
    }
}
