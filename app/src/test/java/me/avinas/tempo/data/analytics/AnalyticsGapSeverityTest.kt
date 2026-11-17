package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Gap severity is the field that was silently useless before: it reused the generic duration
 * buckets, whose largest value is `30s+`, while the shortest gap the health worker can detect
 * is thirty minutes. Every value it ever produced was therefore identical.
 *
 * It now has three inhabited bands. `UNDER_30M` and `UNDER_6H` were removed because nothing can
 * produce them — the watchdog's resolution starts at thirty minutes, and its own threshold sits
 * at two hours — and a bucket nothing can fall into is a constant wearing a dimension's name.
 */
class AnalyticsGapSeverityTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `an unmeasurable gap is reported as unknown`() {
        assertEquals(TrackingGapSeverity.UNKNOWN, AnalyticsBucket.gap(null))
    }

    @Test
    fun `each boundary falls into the bucket it names`() {
        // Below the two-hour watchdog threshold: the listener recovered on its own, or the gap
        // is shorter than the detection resolution. Not an incident.
        assertEquals(TrackingGapSeverity.UNDER_2H, AnalyticsBucket.gap(0L))
        assertEquals(TrackingGapSeverity.UNDER_2H, AnalyticsBucket.gap(30 * minute))
        assertEquals(TrackingGapSeverity.UNDER_2H, AnalyticsBucket.gap(2 * hour - 1))

        assertEquals(TrackingGapSeverity.UNDER_24H, AnalyticsBucket.gap(2 * hour))
        assertEquals(TrackingGapSeverity.UNDER_24H, AnalyticsBucket.gap(24 * hour - 1))

        assertEquals(TrackingGapSeverity.OVER_24H, AnalyticsBucket.gap(24 * hour))
        assertEquals(TrackingGapSeverity.OVER_24H, AnalyticsBucket.gap(72 * hour))
    }

    /**
     * The regression this whole change exists for: the shortest gap the health worker can
     * possibly notice and a three-day outage must not report the same value.
     */
    @Test
    fun `the shortest detectable gap and a three-day outage are distinguishable`() {
        val shortestDetectable = 30 * minute

        assertNotEquals(
            AnalyticsBucket.gap(shortestDetectable),
            AnalyticsBucket.gap(72 * hour),
        )
    }
}
