package me.avinas.tempo.data.local.entities

/** Shared exact matching for live tracking, persistence and history corrections. */
object ManualContentRuleResolver {
    fun matches(mark: ManualContentMark, title: String, artist: String): Boolean {
        val titleMatches = mark.originalTitle.trim().equals(title.trim(), ignoreCase = true)
        val artistMatches = mark.originalArtist.trim().equals(artist.trim(), ignoreCase = true)
        return when (mark.patternType.uppercase()) {
            "TITLE_ARTIST" -> title.isNotBlank() && artist.isNotBlank() && titleMatches && artistMatches
            "TITLE" -> title.isNotBlank() && titleMatches
            "ARTIST" -> artist.isNotBlank() && artistMatches
            else -> false
        }
    }

    fun resolve(marks: Iterable<ManualContentMark>, title: String, artist: String): ManualContentMark? {
        var best: ManualContentMark? = null
        var bestSpecificity = 0
        for (mark in marks) {
            if (!matches(mark, title, artist)) continue
            val specificity = when (mark.patternType.uppercase()) {
                "TITLE_ARTIST" -> 3
                "TITLE" -> 2
                else -> 1
            }
            val previous = best
            if (previous == null || specificity > bestSpecificity ||
                (specificity == bestSpecificity && (mark.markedAt > previous.markedAt ||
                    (mark.markedAt == previous.markedAt && mark.id > previous.id)))) {
                best = mark
                bestSpecificity = specificity
            }
        }
        return best
    }
}
