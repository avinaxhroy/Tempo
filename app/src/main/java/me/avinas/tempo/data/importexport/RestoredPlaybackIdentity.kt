package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.ListeningEventOrigin

/** An immutable producer event ID is unique only within its Google owner. */
internal data class RestoredOriginKey(val accountSubject: String, val originEventId: String)

internal fun ListeningEventOrigin.restoredKey(): RestoredOriginKey =
    RestoredOriginKey(accountSubject, originEventId)

/**
 * Offline archives may contain the same origin ID from distinct Google
 * accounts. Replay only if none of this playback's **account-qualified** aliases
 * is already present, and reject conflicting claims within that account.
 */
internal fun shouldImportOfflinePlayback(
    aliases: List<ListeningEventOrigin>,
    existingByOrigin: Map<RestoredOriginKey, Long>,
): Boolean {
    val targets = aliases.mapNotNull { existingByOrigin[it.restoredKey()] }.distinct()
    check(targets.size <= 1) {
        "Cannot safely restore aliases: producer IDs point to different listening events"
    }
    return targets.isEmpty()
}
