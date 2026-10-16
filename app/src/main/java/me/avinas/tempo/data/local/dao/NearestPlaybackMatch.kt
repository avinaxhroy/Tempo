package me.avinas.tempo.data.local.dao

import kotlin.math.abs

/**
 * Choose the closest matching physical playback among the candidates allowed
 * by source and producer-origin constraints. Looking up the first matching row
 * can attach a producer identity to the wrong rapid replay, because SQLite
 * does not guarantee row order without an ORDER BY.
 *
 * Equal-distance ties retain the first eligible index, so callers can provide
 * candidates in a stable timestamp-and-id ordering.
 */
internal inline fun <T> nearestEligiblePlaybackIndex(
    slots: List<T>,
    incomingTimestamp: Long,
    timestampOf: (T) -> Long,
    eligible: (T) -> Boolean,
): Int {
    var best = -1
    var bestDistance = Long.MAX_VALUE
    for ((index, slot) in slots.withIndex()) {
        if (!eligible(slot)) continue
        val distance = abs(timestampOf(slot) - incomingTimestamp)
        if (best == -1 || distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

/**
 * A fuzzy Drive match is safe only when it identifies one eligible playback.
 * Multiple nearby plays can represent independent replays: preserve incoming
 * provenance instead of binding it to an arbitrary historical row.
 */
internal inline fun <T> uniqueEligiblePlaybackIndex(
    slots: List<T>,
    eligible: (T) -> Boolean,
): Int {
    var result = -1
    for ((index, slot) in slots.withIndex()) {
        if (!eligible(slot)) continue
        if (result != -1) return -1
        result = index
    }
    return result
}

/**
 * Partition chronological Drive imports into disjoint conflict windows.
 * A local play can match incoming events up to [radiusMs] away, so a gap
 * strictly greater than twice that radius cannot share a match. Avoid a
 * single multi-year SQL range when one Drive batch contains sparse history.
 */
internal inline fun <T> clusterDrivePlaybackWindows(
    events: List<T>,
    radiusMs: Long,
    crossinline timestampOf: (T) -> Long,
): List<List<T>> {
    if (events.isEmpty()) return emptyList()
    require(radiusMs >= 0)
    val sorted = events.sortedBy(timestampOf)
    val groups = ArrayList<MutableList<T>>()
    var previousTimestamp: Long? = null
    for (event in sorted) {
        val timestamp = timestampOf(event)
        if (previousTimestamp == null || timestamp - previousTimestamp > radiusMs * 2) {
            groups.add(ArrayList())
        }
        groups.last().add(event)
        previousTimestamp = timestamp
    }
    return groups
}
