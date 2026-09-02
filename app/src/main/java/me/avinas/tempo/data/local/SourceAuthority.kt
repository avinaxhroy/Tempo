package me.avinas.tempo.data.local

/**
 * Ranks a listening-event source by how trustworthy its representation of a
 * play is. Used by Layer 2 (cross-source temporal reconciliation) to decide
 * which record survives when two sources claim the same play.
 *
 * Higher rank = wins and is kept; lower rank = dropped.
 *
 * Hierarchy (most → least authoritative):
 *  100 — live app tracking (the device was actually playing it; precise timing)
 *   90 — desktop satellite (real-time, second device)
 *   70 — Spotify data-export JSON (Spotify's own authoritative record)
 *   65 — YouTube Music data-export JSON
 *   60 — other JSON data exports
 *   55 — API "recently played" imports
 *   50 — Last.fm scrobbles / generic imports
 *   40 — reconstructed history (synthesized, lowest fidelity)
 *
 * An incoming event NEVER replaces an existing event of equal or higher rank,
 * so real listening data can never be overwritten or deleted by an import.
 */
object SourceAuthority {

    fun rank(source: String): Int {
        val original = unwrapDriveSource(source)
        return when {
            original.startsWith("desktop:") -> 90
            original.contains("import.reconstructed") -> 40
            original.contains("fm.last.import") -> 50
            original.contains("import.json") && original.contains("spotify") -> 70
            original.contains("import.json") && original.contains("youtube") -> 65
            original.contains("import.json") -> 60
            original.contains(".import") -> 55
            original.contains("import") -> 50
            else -> 100
        }
    }

    fun driveDeviceId(source: String): String? {
        if (!source.startsWith("drive:")) return null
        return source.split(':', limit = 3).takeIf { it.size == 3 }?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun unwrapDriveSource(source: String): String =
        source.split(':', limit = 3).takeIf { it.size == 3 && it[0] == "drive" }?.get(2) ?: source
}
