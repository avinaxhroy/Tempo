package me.avinas.tempo.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Test

class NearestPlaybackMatchTest {
    private data class Candidate(
        val timestamp: Long,
        val claimedByProducer: Boolean = false,
    )

    @Test
    fun `assigns secondary producer to nearest of two rapid replays`() {
        val candidates = listOf(
            Candidate(1_700_000_000_000L),
            Candidate(1_700_000_008_000L),
        )
        val target = nearestEligiblePlaybackIndex(
            candidates, 1_700_000_007_850L,
            timestampOf = { it.timestamp },
            eligible = { true },
        )
        assertEquals("The later replay, not the first database row, owns the origin", 1, target)
    }

    @Test
    fun `already claimed playback is not eligible even when closer`() {
        val candidates = listOf(
            Candidate(1_700_000_000_000L),
            Candidate(1_700_000_008_000L, claimedByProducer = true),
        )
        assertEquals(0, nearestEligiblePlaybackIndex(
            candidates, 1_700_000_007_850L,
            timestampOf = { it.timestamp },
            eligible = { !it.claimedByProducer },
        ))
    }

    @Test
    fun `no eligible playback results in a new event`() {
        val candidates = listOf(Candidate(100L, claimedByProducer = true))
        assertEquals(-1, nearestEligiblePlaybackIndex(
            candidates, 100L,
            timestampOf = { it.timestamp },
            eligible = { !it.claimedByProducer },
        ))
    }

    @Test
    fun `Drive imports preserve independent seven-second replays`() {
        val origin = 1_700_000_000_000L
        val slots = listOf(Candidate(origin))
        val withinWindow = nearestEligiblePlaybackIndex(
            slots, origin + 1_000L,
            timestampOf = { it.timestamp },
            eligible = { kotlin.math.abs(it.timestamp - (origin + 1_000L)) <=
                ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS },
        )
        assertEquals(0, withinWindow)

        val independentReplay = nearestEligiblePlaybackIndex(
            slots, origin + 7_000L,
            timestampOf = { it.timestamp },
            eligible = { kotlin.math.abs(it.timestamp - (origin + 7_000L)) <=
                ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS },
        )
        assertEquals(-1, independentReplay)
    }

    @Test
    fun `Drive origin is not assigned when two rapid replays are plausible`() {
        val first = Candidate(1_700_000_000_000L)
        val replay = Candidate(1_700_000_001_000L)
        val slots = listOf(first, replay)
        val incoming = 1_700_000_000_500L
        assertEquals(-1, uniqueEligiblePlaybackIndex(slots) {
            kotlin.math.abs(it.timestamp - incoming) <= ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS
        })
        assertEquals(1, uniqueEligiblePlaybackIndex(slots) { it.timestamp == replay.timestamp })
        assertEquals(-1, uniqueEligiblePlaybackIndex(slots) { false })
    }

    @Test
    fun `equidistant candidates deterministically keep first ordering`() {
        assertEquals(0, nearestEligiblePlaybackIndex(
            listOf(Candidate(1_000L), Candidate(3_000L)), 2_000L,
            timestampOf = { it.timestamp },
            eligible = { true },
        ))
    }

    @Test
    fun `Drive windows never join sparse decades of the same song`() {
        val firstTimestamp = 1_451_606_400_000L
        val historical = (0 until 100_000).map {
            firstTimestamp + it.toLong() * 3_153_600L
        }
        val windows = clusterDrivePlaybackWindows(
            historical, ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS,
            timestampOf = { it },
        )
        assertEquals(100_000, windows.size)
        assertEquals(100_000, windows.sumOf { it.size })
        assertEquals(firstTimestamp, windows.first().single())
        assertEquals(historical.last(), windows.last().single())

        val overlap = clusterDrivePlaybackWindows(
            listOf(0L, 1_500L, 4_500L, 20_000L), 2_000L,
            timestampOf = { it },
        )
        assertEquals(listOf(listOf(0L, 1_500L, 4_500L), listOf(20_000L)), overlap)
    }

    @Test
    fun `playback match rejects candidates when duration differs by more than 10 seconds`() {
        data class PlaybackCandidate(val timestamp: Long, val durationMs: Long)
        val candidates = listOf(PlaybackCandidate(1_700_000_000_000L, 180_000L))
        val targetDifferentDuration = nearestEligiblePlaybackIndex(
            candidates, 1_700_000_001_000L,
            timestampOf = { it.timestamp },
            eligible = {
                kotlin.math.abs(it.timestamp - 1_700_000_001_000L) <= ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS &&
                    kotlin.math.abs(it.durationMs - 200_000L) <= 10_000L
            },
        )
        assertEquals(-1, targetDifferentDuration)

        val targetMatchingDuration = nearestEligiblePlaybackIndex(
            candidates, 1_700_000_001_000L,
            timestampOf = { it.timestamp },
            eligible = {
                kotlin.math.abs(it.timestamp - 1_700_000_001_000L) <= ListeningEventDao.DRIVE_RECONCILIATION_WINDOW_MS &&
                    kotlin.math.abs(it.durationMs - 185_000L) <= 10_000L
            },
        )
        assertEquals(0, targetMatchingDuration)
    }
}
