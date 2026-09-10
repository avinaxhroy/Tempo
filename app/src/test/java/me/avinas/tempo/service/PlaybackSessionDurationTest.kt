package me.avinas.tempo.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSessionDurationTest {
    @Test fun `long allowed media counts beyond one hour`() {
        val session = MusicTrackingService.PlaybackSession(
            packageName = "player", title = "Concert", artist = "Artist", album = null,
            accumulatedPositionMs = 7_200_000L, estimatedDurationMs = 10_800_000L
        )
        assertEquals(7_200_000L, session.calculateCurrentPlayDuration())
    }
}
