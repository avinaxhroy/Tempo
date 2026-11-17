package me.avinas.tempo.ui.components.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale
import me.avinas.tempo.R

/**
 * A listening duration, in the unit that keeps it readable.
 *
 * Minutes below an hour, one decimal of hours above it: "48m" is a number a
 * reader can hold, "0.8h" is not, and "2,940m" is the same figure pretending to
 * be precise. Every card that prints listening time calls this, so the same total
 * cannot be spelled three ways on three cards.
 */
@Composable
internal fun formatShareDuration(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    return when {
        // A hundred hours is four glyphs; a decimal on top of it is not worth
        // the column, and past four digits nothing fits anyway.
        hours >= 100 -> stringResource(R.string.share_time_hours_format, hours)
        totalMinutes >= 60 ->
            stringResource(R.string.share_duration_hours_format, totalMinutes / 60.0)
        else -> stringResource(R.string.share_duration_minutes_format, totalMinutes)
    }
}

/**
 * A count in the width a summary column actually has.
 *
 * Five digits fit, so a real number prints as itself — "1247 plays" is more use
 * than "1.2K". From six digits the column clips, which is where the suffix
 * earns its keep. The K form truncates rather than rounds so 999,999 cannot
 * come back as "1000K".
 */
internal fun formatShareCount(count: Int): String =
    when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 100_000 -> "${count / 1_000}K"
        else -> count.toString()
    }
