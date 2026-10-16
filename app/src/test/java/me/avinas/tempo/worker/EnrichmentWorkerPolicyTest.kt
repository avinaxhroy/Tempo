package me.avinas.tempo.worker

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrichmentWorkerPolicyTest {

    @Test
    fun ordinaryPerTrackRequestKeepsExistingWork() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            immediateEnrichmentWorkPolicy(
                trackId = 42L,
                appendAfterExisting = false,
            ),
        )
    }

    @Test
    fun postResetRequestAppendsAfterExistingWork() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            immediateEnrichmentWorkPolicy(
                trackId = 42L,
                appendAfterExisting = true,
            ),
        )
    }

    @Test
    fun genericImmediateSweepStillAppendsOrReplaces() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            immediateEnrichmentWorkPolicy(
                trackId = null,
                appendAfterExisting = false,
            ),
        )
    }
}
