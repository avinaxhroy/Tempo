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
 *   70 — first-party Spotify JSON / Deezer XLSX data exports
 *   65 — YouTube Music data-export JSON
 *   60 — other structured data exports
 *   55 — API "recently played" imports
 *   50 — Last.fm scrobbles / generic imports
 *   40 — reconstructed history (synthesized, lowest fidelity)
 *
 * An incoming event NEVER replaces an existing event of equal or higher rank,
 * so real listening data can never be overwritten or deleted by an import.
 */
object SourceAuthority {

    fun rank(source: String): Int = when {
        source.startsWith("desktop:") -> 90
        source.contains("import.reconstructed") -> 40
        source.contains("fm.last.import") -> 50
        source.contains("import.json") && source.contains("spotify") -> 70
        source.contains("import.xlsx") && source.contains("deezer") -> 70
        source.contains("import.json") && source.contains("youtube") -> 65
        source.contains("import.json") -> 60
        source.contains(".import") -> 55
        source.contains("import") -> 50
        else -> 100
    }
}
