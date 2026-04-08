package me.avinas.tempo.ui.spotlight

import android.content.Context
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object SpotlightPeriodFormatter {

    fun effectivePeriodStart(
        timeRange: TimeRange,
        now: LocalDate = LocalDate.now()
    ): LocalDate = when (timeRange) {
        TimeRange.THIS_WEEK -> {
            val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (now.dayOfWeek.value <= 3) weekStart.minusWeeks(1) else weekStart
        }
        TimeRange.THIS_MONTH -> {
            if (now.dayOfMonth <= 3) now.minusMonths(1).withDayOfMonth(1)
            else now.withDayOfMonth(1)
        }
        TimeRange.THIS_YEAR -> now.withDayOfYear(1)
        TimeRange.TODAY -> now
        TimeRange.ALL_TIME -> now
    }

    fun periodLabel(
        timeRange: TimeRange,
        now: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault()
    ): String {
        val start = effectivePeriodStart(timeRange, now)

        return when (timeRange) {
            TimeRange.THIS_MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM", locale))
            TimeRange.THIS_WEEK -> formatWeekLabel(start, locale)
            TimeRange.THIS_YEAR -> start.year.toString()
            TimeRange.ALL_TIME -> "All Time"
            TimeRange.TODAY -> start.format(DateTimeFormatter.ofPattern("MMM d", locale))
        }
    }

    fun viewStoryText(
        context: Context,
        timeRange: TimeRange,
        now: LocalDate = LocalDate.now()
    ): String = when (timeRange) {
        TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR ->
            context.getString(R.string.spotlight_view_story_for, periodLabel(timeRange, now))
        else -> context.getString(R.string.spotlight_view_story)
    }

    fun storyTitle(
        context: Context,
        timeRange: TimeRange,
        year: Int? = null,
        now: LocalDate = LocalDate.now()
    ): String = when (timeRange) {
        TimeRange.THIS_WEEK, TimeRange.THIS_MONTH -> periodLabel(timeRange, now)
        TimeRange.THIS_YEAR -> context.getString(R.string.spotlight_this_year_title, year ?: now.year)
        TimeRange.ALL_TIME -> context.getString(R.string.spotlight_all_time_title)
        else -> context.getString(R.string.spotlight_default_title)
    }

    private fun formatWeekLabel(start: LocalDate, locale: Locale): String {
        val end = start.plusDays(6)
        val shortMonth = DateTimeFormatter.ofPattern("MMM", locale)

        return when {
            start.year != end.year ->
                "${start.format(shortMonth)} ${start.dayOfMonth}, ${start.year} - ${end.format(shortMonth)} ${end.dayOfMonth}, ${end.year}"
            start.month == end.month ->
                "${start.format(shortMonth)} ${start.dayOfMonth}-${end.dayOfMonth}"
            else ->
                "${start.format(shortMonth)} ${start.dayOfMonth} - ${end.format(shortMonth)} ${end.dayOfMonth}"
        }
    }
}
