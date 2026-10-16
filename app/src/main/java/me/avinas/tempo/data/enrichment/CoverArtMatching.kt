package me.avinas.tempo.data.enrichment

import me.avinas.tempo.utils.ArtistParser

private val COVER_VERSION_MARKERS = setOf(
    "remaster",
    "remastered",
    "remix",
    "mix",
    "edit",
    "version",
    "live",
    "acoustic",
    "instrumental",
    "mono",
    "stereo",
    "deluxe",
    "edition",
    "clean",
    "explicit",
    "vip",
    "dub",
)

private val COVER_VERSION_ALLOWED_TOKENS = COVER_VERSION_MARKERS + setOf(
    "radio",
    "single",
    "album",
    "extended",
    "club",
    "original",
    "anniversary",
    "bonus",
    "digital",
    "intro",
    "outro",
)

private fun explicitCoverVersionKinds(title: String): Set<String> {
    val tokens = ArtistParser.normalizeForSearch(title)
        .split(" ")
        .filter { it.isNotBlank() }
    val kinds = tokens
        .mapNotNull { token ->
            when (token) {
                "remaster", "remastered" -> "remaster"
                "remix" -> "remix"
                "mix" -> "mix"
                "edit" -> "edit"
                "version" -> "version"
                "live" -> "live"
                "acoustic" -> "acoustic"
                "instrumental" -> "instrumental"
                "mono" -> "mono"
                "stereo" -> "stereo"
                "deluxe" -> "deluxe"
                "edition" -> "edition"
                "clean" -> "clean"
                "explicit" -> "explicit"
                "vip" -> "vip"
                "dub" -> "dub"
                else -> null
            }
        }
        .toMutableSet()

    val compoundKinds = tokens.zipWithNext().mapNotNull { (first, second) ->
        when ("$first $second") {
            "radio edit" -> "radio-edit"
            "extended edit" -> "extended-edit"
            "club edit" -> "club-edit"
            "single version" -> "single-version"
            "album version" -> "album-version"
            "extended version" -> "extended-version"
            "original version" -> "original-version"
            "original mix" -> "original-mix"
            "extended mix" -> "extended-mix"
            "club mix" -> "club-mix"
            "anniversary edition" -> "anniversary-edition"
            "deluxe edition" -> "deluxe"
            "clean version" -> "clean"
            "clean edit" -> "clean"
            "explicit version" -> "explicit"
            "vip mix" -> "vip"
            "dub mix" -> "dub"
            else -> null
        }
    }.toSet()

    if (compoundKinds.isNotEmpty()) {
        kinds.addAll(compoundKinds)
        if (compoundKinds.any { it.endsWith("-edit") || it == "clean" }) kinds.remove("edit")
        if (compoundKinds.any { it.endsWith("-version") || it == "clean" || it == "explicit" }) kinds.remove("version")
        if (compoundKinds.any { it.endsWith("-mix") || it == "vip" || it == "dub" }) kinds.remove("mix")
        if (compoundKinds.any { it.endsWith("-edition") || it == "deluxe" }) kinds.remove("edition")
    }

    // "Live Version" and similar generic wording do not describe two independent
    // recording variants. Ignore generic qualifiers when a specific kind remains.
    if (kinds.size > 1) kinds.remove("version")
    if (kinds.size > 1) kinds.remove("edition")
    return kinds
}

/**
 * Conservative title matching for user-facing cover candidates.
 *
 * Exact normalized titles always match. A prefix match is accepted only when the
 * extra suffix clearly describes a version of the same recording (for example
 * "2011 remaster", "live", or "radio edit"). Ordinary longer titles such as
 * "Stay High" or "Homecoming" are deliberately rejected.
 */
internal fun isSafeCoverTrackTitleMatch(
    expectedTitle: String,
    candidateTitle: String,
): Boolean {
    val expectedVersions = explicitCoverVersionKinds(expectedTitle)
    val candidateVersions = explicitCoverVersionKinds(candidateTitle)

    // When Tempo's own title explicitly identifies a version, never silently
    // downgrade it to a different/studio version. A plain expected title may
    // still accept a provider's explicit version suffix as a conservative
    // fallback (the existing picker behavior).
    if (expectedVersions.isNotEmpty() && expectedVersions != candidateVersions) {
        return false
    }

    val expected = ArtistParser.normalizeForSearch(
        ArtistParser.cleanTrackTitle(expectedTitle)
    )
    val candidate = ArtistParser.normalizeForSearch(
        ArtistParser.cleanTrackTitle(candidateTitle)
    )

    if (expected.isBlank() || candidate.isBlank()) return false
    if (expected == candidate) return true

    fun isRecognizedVersionSuffix(base: String, longer: String): Boolean {
        if (!longer.startsWith("$base ")) return false
        val suffixTokens = longer
            .removePrefix(base)
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }

        val hasVersionMarker = suffixTokens.any { token -> token in COVER_VERSION_MARKERS }
        val containsOnlyVersionTokens = suffixTokens.all { token ->
            token in COVER_VERSION_ALLOWED_TOKENS ||
                token.all(Char::isDigit)
        }

        return hasVersionMarker && containsOnlyVersionTokens
    }

    return isRecognizedVersionSuffix(expected, candidate) ||
        isRecognizedVersionSuffix(candidate, expected)
}

/**
 * Strict artist validation for artwork providers.
 *
 * Provider candidates must contain at least one parsed artist that is an exact
 * normalized match (allowing only the existing "The X" vs "X" normalization).
 * This intentionally avoids the broader fuzzy/Jaccard matcher used elsewhere in
 * Tempo, so "Drake" cannot validate "Drake Bell".
 */
internal fun isSafeCoverArtistMatch(
    expectedArtist: String,
    candidateArtist: String,
): Boolean {
    if (ArtistParser.isUnknownArtist(expectedArtist) ||
        ArtistParser.isUnknownArtist(candidateArtist)
    ) {
        return false
    }

    val expectedPrimaryArtists = ArtistParser.getPrimaryArtists(expectedArtist)
    val candidatePrimaryArtists = ArtistParser.getPrimaryArtists(candidateArtist)

    // Every co-billed primary artist must still be present. Featured guests are
    // intentionally excluded from this requirement, but a solo track from one
    // member of an "A & B" collaboration can no longer validate the artwork.
    return expectedPrimaryArtists.all { expected ->
        candidatePrimaryArtists.any { candidate ->
            ArtistParser.isStrictSameArtist(expected, candidate)
        }
    }
}

internal fun coverSearchTitleVariants(title: String): List<String> {
    val raw = title.trim()
    val cleaned = ArtistParser.cleanTrackTitle(raw)
    if (cleaned.isBlank()) return listOf(raw).filter { it.isNotBlank() }

    val rawHasExplicitVersion = explicitCoverVersionKinds(raw).isNotEmpty()
    return buildList {
        if (rawHasExplicitVersion &&
            ArtistParser.normalizeForSearch(raw) != ArtistParser.normalizeForSearch(cleaned)
        ) {
            add(raw)
        }
        add(cleaned)
    }.distinct()
}

internal fun isSafeCoverIdentityMatch(
    expectedTitle: String,
    expectedArtist: String,
    candidateTitle: String,
    candidateArtists: List<String>,
): Boolean {
    if (!isSafeCoverTrackTitleMatch(expectedTitle, candidateTitle) ||
        candidateArtists.isEmpty()
    ) {
        return false
    }

    val expectedPrimaryArtists = ArtistParser.getPrimaryArtists(expectedArtist)
    val candidatePrimaryArtists = candidateArtists.flatMap(ArtistParser::getPrimaryArtists)

    return expectedPrimaryArtists.all { expected ->
        candidatePrimaryArtists.any { candidate ->
            ArtistParser.isStrictSameArtist(expected, candidate)
        }
    }
}
