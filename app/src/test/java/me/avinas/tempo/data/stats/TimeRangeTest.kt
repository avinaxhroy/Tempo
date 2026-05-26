package me.avinas.tempo.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeRangeTest {

    private val zone = ZoneId.systemDefault()

    private fun toMillis(dateTime: LocalDateTime): Long {
        return dateTime.atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun testThisWeekNormalPeriod() {
        // Thursday, May 21, 2026 at 15:30:00
        val mockNow = LocalDateTime.of(2026, 5, 21, 15, 30, 0)
        
        // Expected start: Monday, May 18, 2026 at 00:00:00
        val expectedStart = LocalDateTime.of(2026, 5, 18, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.THIS_WEEK.getStartTimestamp(mockNow, zone))
        
        // Expected end: Thursday, May 21, 2026 at 15:30:00
        assertEquals(toMillis(mockNow), TimeRange.THIS_WEEK.getEndTimestamp(mockNow, zone))
    }

    @Test
    fun testThisWeekGracePeriod() {
        // Monday, May 25, 2026 at 10:00:00 (dayOfWeek = 1)
        val mockNow = LocalDateTime.of(2026, 5, 25, 10, 0, 0)
        
        // Expected start: Monday, May 18, 2026 at 00:00:00 (previous week)
        val expectedStart = LocalDateTime.of(2026, 5, 18, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.THIS_WEEK.getStartTimestamp(mockNow, zone))
        
        // Expected end: Sunday, May 24, 2026 at 23:59:59.999 (end of previous week)
        // Which is Monday, May 25, 2026 at 00:00:00 minus 1 millisecond
        val expectedEnd = toMillis(LocalDateTime.of(2026, 5, 25, 0, 0, 0)) - 1
        assertEquals(expectedEnd, TimeRange.THIS_WEEK.getEndTimestamp(mockNow, zone))
    }

    @Test
    fun testThisMonthNormalPeriod() {
        // May 15, 2026 at 15:30:00 (dayOfMonth = 15)
        val mockNow = LocalDateTime.of(2026, 5, 15, 15, 30, 0)
        
        // Expected start: May 1, 2026 at 00:00:00
        val expectedStart = LocalDateTime.of(2026, 5, 1, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.THIS_MONTH.getStartTimestamp(mockNow, zone))
        
        // Expected end: May 15, 2026 at 15:30:00
        assertEquals(toMillis(mockNow), TimeRange.THIS_MONTH.getEndTimestamp(mockNow, zone))
    }

    @Test
    fun testThisMonthGracePeriod() {
        // June 2, 2026 at 10:00:00 (dayOfMonth = 2)
        val mockNow = LocalDateTime.of(2026, 6, 2, 10, 0, 0)
        
        // Expected start: May 1, 2026 at 00:00:00 (previous month)
        val expectedStart = LocalDateTime.of(2026, 5, 1, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.THIS_MONTH.getStartTimestamp(mockNow, zone))
        
        // Expected end: May 31, 2026 at 23:59:59.999 (end of previous month)
        // Which is June 1, 2026 at 00:00:00 minus 1 millisecond
        val expectedEnd = toMillis(LocalDateTime.of(2026, 6, 1, 0, 0, 0)) - 1
        assertEquals(expectedEnd, TimeRange.THIS_MONTH.getEndTimestamp(mockNow, zone))
    }

    @Test
    fun testTodayTimeRange() {
        val mockNow = LocalDateTime.of(2026, 5, 25, 12, 0, 0)
        
        val expectedStart = LocalDateTime.of(2026, 5, 25, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.TODAY.getStartTimestamp(mockNow, zone))
        assertEquals(toMillis(mockNow), TimeRange.TODAY.getEndTimestamp(mockNow, zone))
    }

    @Test
    fun testThisYearTimeRange() {
        val mockNow = LocalDateTime.of(2026, 5, 25, 12, 0, 0)
        
        val expectedStart = LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        assertEquals(toMillis(expectedStart), TimeRange.THIS_YEAR.getStartTimestamp(mockNow, zone))
        assertEquals(toMillis(mockNow), TimeRange.THIS_YEAR.getEndTimestamp(mockNow, zone))
    }
}
