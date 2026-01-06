package me.avinas.tempo.utils

import android.util.Log

/**
 * Utility class for parsing and normalizing artist names from various formats.
 * 
 * Music apps and metadata sources use different conventions for multiple artists:
 * - "Artist1, Artist2" (comma-separated)
 * - "Artist1 & Artist2" (ampersand)
 * - "Artist1 feat. Artist2" or "Artist1 ft. Artist2" (featuring)
 * - "Artist1 x Artist2" (collaboration)
 * - "Artist1 / Artist2" (slash-separated)
 * - "Artist1 and Artist2"
 * - "Artist1 with Artist2"
 * - "Artist1 vs. Artist2" or "Artist1 vs Artist2"
 * - "Artist1 + Artist2"
 * 
 * This parser handles all these formats and provides utilities for:
 * - Extracting all artists from a string
 * - Getting the primary (main) artist
 * - Getting featured artists
 * - Normalizing artist names for search/comparison
 */
object ArtistParser {

    /**
     * Result of parsing an artist string.
     */
    data class ParsedArtists(
        /** The primary/main artist(s) - before any featuring notation */
        val primaryArtists: List<String>,
        /** Featured artists (after feat., ft., etc.) */
        val featuredArtists: List<String>,
        /** All artists combined */
        val allArtists: List<String>,
        /** Original raw string */
        val original: String
    ) {
        /** Get the first/main artist name */
        val primaryArtist: String
            get() = primaryArtists.firstOrNull() ?: original

        /** Get all artists as a formatted string */
        fun formatAll(separator: String = ", "): String =
            allArtists.joinToString(separator)

        /** Get primary artists as a formatted string */
        fun formatPrimary(separator: String = ", "): String =
            primaryArtists.joinToString(separator)

        /** Check if this has multiple artists */
        val hasMultipleArtists: Boolean
            get() = allArtists.size > 1

        /** Check if this has featured artists */
        val hasFeaturedArtists: Boolean
            get() = featuredArtists.isNotEmpty()
    }

    // Regex patterns for different artist separators
    private val FEATURING_PATTERNS = listOf(
        Regex("\\s+feat\\.?\\s+", RegexOption.IGNORE_CASE),
        Regex("\\s+ft\\.?\\s+", RegexOption.IGNORE_CASE),
        Regex("\\s+featuring\\s+", RegexOption.IGNORE_CASE),
        Regex("\\s+with\\s+", RegexOption.IGNORE_CASE),
        Regex("\\(feat\\.?\\s*", RegexOption.IGNORE_CASE),
        Regex("\\(ft\\.?\\s*", RegexOption.IGNORE_CASE),
        Regex("\\(featuring\\s*", RegexOption.IGNORE_CASE),
        Regex("\\(with\\s*", RegexOption.IGNORE_CASE),
        Regex("\\[feat\\.?\\s*", RegexOption.IGNORE_CASE),
        Regex("\\[ft\\.?\\s*", RegexOption.IGNORE_CASE)
    )

    // Known bands with ampersands in their name (Option 3: Known Band Database)
    // This list can be expanded as more bands are discovered
    private val KNOWN_AMPERSAND_BANDS = setOf(
        "dead & company",
        "derek & the dominos",
        "belle & sebastian",
        "iron & wine",
        "simon & garfunkel",
        "hall & oates",
        "brooks & dunn",
        "big & rich",
        "florida georgia line",  // Sometimes written as "Florida Georgia & Line"
        "the mamas & the papas",
        "peter paul & mary",
        "crosby stills nash & young",
        "emerson lake & palmer",
        "blood sweat & tears",
        "earth wind & fire",
        "kool & the gang",
        "rob base & dj ez rock",
        "eric b & rakim",
        "salt n pepa",  // Sometimes written with &
        "outkast",  // Sometimes written as "Andre 3000 & Big Boi"
        "tears for fears",  // Context: Sometimes listed as "Curt Smith & Roland Orzabal"
        "tom petty & the heartbreakers",
        "bob seger & the silver bullet band",
        "bruce springsteen & the e street band",
        "hootie & the blowfish",
        "kid cudi & eminem",  // Collaboration duo that performs together
        "lil nas x & billy ray cyrus"  // Known collaboration
    )
    
    // Patterns that indicate the ampersand is part of a band name (Option 1: Pattern-Based Whitelist)
    // These patterns help identify when & is part of the artist name, not a separator
    private val AMPERSAND_BAND_PATTERNS = listOf(
        Regex("\\s*&\\s*the\\s+", RegexOption.IGNORE_CASE),      // "& The ..." (e.g., Derek & The Dominos)
        Regex("\\s*&\\s*company\\b", RegexOption.IGNORE_CASE),   // "& Company" (e.g., Dead & Company)
        Regex("\\s*&\\s*friends\\b", RegexOption.IGNORE_CASE),   // "& Friends"
        Regex("\\s*&\\s*associates\\b", RegexOption.IGNORE_CASE) // "& Associates"
        // REMOVED: Short-form pattern was too aggressive and caught real collaborations
    )

    // Patterns that indicate artist collaboration (split into multiple artists)
    // Note: Ampersand is now handled separately with smart detection
    private val COLLABORATION_PATTERNS = listOf(
        Regex("\\s*,\\s*"),                           // Comma
        Regex("\\s*\\|\\s*"),                         // Pipe/Vertical bar
        // Ampersand is handled separately in splitByAmpersandSmartly()
        Regex("\\s+and\\s+", RegexOption.IGNORE_CASE), // "and"
        Regex("\\s+x\\s+", RegexOption.IGNORE_CASE),  // "x" collaboration
        Regex("\\s*/\\s*"),                           // Slash
        Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE), // "vs" or "vs."
        Regex("\\s*\\+\\s*")                          // Plus sign (flexible spaces)
    )

    /**
     * Parse an artist string into its component parts.
     * 
     * @param artistString The raw artist string from metadata
     * @return ParsedArtists containing all extracted artist information
     */
    fun parse(artistString: String): ParsedArtists {
        if (artistString.isBlank()) {
            return ParsedArtists(
                primaryArtists = listOf("Unknown Artist"),
                featuredArtists = emptyList(),
                allArtists = listOf("Unknown Artist"),
                original = artistString
            )
        }

        val cleaned = artistString.trim()

        // First, separate main artists from featured artists
        var mainPart = cleaned
        var featuredPart = ""

        for (pattern in FEATURING_PATTERNS) {
            val match = pattern.find(cleaned)
            if (match != null) {
                mainPart = cleaned.substring(0, match.range.first).trim()
                // Use the original cleaned string to extract featured part
                // The index is relative to the cleaned string, not the truncated mainPart
                val endIndex = match.range.last + 1
                if (endIndex < cleaned.length) {
                    featuredPart = cleaned.substring(endIndex)
                        .replace(Regex("[)\\]]$"), "") // Remove closing brackets
                        .trim()
                }
                break
            }
        }

        // Split main part into individual artists
        val primaryArtists = splitArtists(mainPart)

        // Split featured part into individual artists
        val featuredArtists = if (featuredPart.isNotEmpty()) {
            splitArtists(featuredPart)
        } else {
            emptyList()
        }

        // Combine all artists
        val allArtists = (primaryArtists + featuredArtists).distinct()

        return ParsedArtists(
            primaryArtists = primaryArtists,
            featuredArtists = featuredArtists,
            allArtists = allArtists,
            original = artistString
        )
    }

    /**
     * Split an artist string by collaboration patterns.
     * Uses smart ampersand detection to avoid splitting band names.
     */
    private fun splitArtists(artistString: String): List<String> {
        var parts = listOf(artistString)

        // First, handle all non-ampersand collaboration patterns
        for (pattern in COLLABORATION_PATTERNS) {
            val beforeSize = parts.size
            parts = parts.flatMap { part ->
                part.split(pattern).map { it.trim() }
            }
            if (parts.size > beforeSize) {
                Log.d("ArtistParser", "Split by pattern: '$artistString' -> ${parts.size} parts")
            }
        }

        // Then, smartly handle ampersands for each part
        parts = parts.flatMap { part ->
            splitByAmpersandSmartly(part)
        }

        val result = parts
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() && it != "Unknown Artist" }
            .distinct()
            .ifEmpty { listOf(artistString.trim()) }
        
        if (result.size > 1) {
            Log.i("ArtistParser", "Final split: '$artistString' -> [${result.joinToString(", ")}]")
        }
        
        return result
    }
    
    /**
     * Smart ampersand splitting that preserves band names.
     * Checks against known bands database and pattern-based whitelist.
     */
    private fun splitByAmpersandSmartly(artistString: String): List<String> {
        // If no ampersand, return as-is
        if (!artistString.contains("&")) {
            return listOf(artistString)
        }
        
        val normalized = normalizeForSearch(artistString)
        
        // Option 3: Check against known bands database
        if (KNOWN_AMPERSAND_BANDS.any { knownBand ->
            normalized.contains(knownBand) || knownBand.contains(normalized)
        }) {
            Log.d("ArtistParser", "Preserving band name: '$artistString'")
            return listOf(artistString) // Keep as single artist
        }
        
        // Option 1: Check against pattern-based whitelist
        if (AMPERSAND_BAND_PATTERNS.any { pattern ->
            pattern.containsMatchIn(artistString)
        }) {
            Log.d("ArtistParser", "Preserving band name (pattern match): '$artistString'")
            return listOf(artistString) // Keep as single artist
        }
        
        // If none of the above match, treat ampersand as a separator
        val split = artistString.split(Regex("\\s*&\\s*")).map { it.trim() }
        Log.d("ArtistParser", "Splitting artists by &: '$artistString' -> ${split.joinToString(", ")}")
        return split
    }

    /**
     * Normalize an artist name by cleaning up common formatting issues.
     */
    fun normalizeArtistName(name: String): String {
        return name
            .trim()
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .replace(Regex("[)\\]]+$"), "") // Remove trailing brackets
            .replace(Regex("^[(&\\[]+"), "") // Remove leading brackets
            .trim()
    }

    /**
     * Get just the primary artist name for database matching.
     * This is useful when you need a single artist for lookups.
     * 
     * @param artistString The raw artist string
     * @return The primary/main artist name
     */
    fun getPrimaryArtist(artistString: String): String {
        return parse(artistString).primaryArtist
    }

    /**
     * Get all artists as a list for comprehensive matching.
     * 
     * @param artistString The raw artist string
     * @return List of all artists mentioned
     */
    fun getAllArtists(artistString: String): List<String> {
        return parse(artistString).allArtists
    }

    /**
     * Normalize an artist string for comparison/search purposes.
     * Removes special characters, lowercases, and normalizes whitespace.
     * 
     * @param artistString The artist string to normalize
     * @return Normalized string for comparison
     */
    fun normalizeForSearch(artistString: String): String {
        return artistString
            .lowercase()
            .replace("$", "s") // Handle stylized '$' as 's' (e.g. KR$NA -> krsna, Ke$ha -> kesha)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Check if two artist strings likely refer to the same artist.
     * Uses fuzzy matching to handle variations.
     * 
     * @param artist1 First artist string
     * @param artist2 Second artist string
     * @return True if the artists are likely the same
     */
    fun isSameArtist(artist1: String, artist2: String): Boolean {
        val norm1 = normalizeForSearch(artist1)
        val norm2 = normalizeForSearch(artist2)

        // Exact match after normalization
        if (norm1 == norm2) return true

        // Check if one contains the other (handles "Artist" vs "The Artist")
        if (norm1.contains(norm2) || norm2.contains(norm1)) return true

        // Check if the parsed primary artists match
        val primary1 = normalizeForSearch(getPrimaryArtist(artist1))
        val primary2 = normalizeForSearch(getPrimaryArtist(artist2))
        if (primary1 == primary2) return true

        // Check word overlap (Jaccard similarity)
        val words1 = norm1.split(" ").toSet()
        val words2 = norm2.split(" ").toSet()
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        val similarity = if (union > 0) intersection.toDouble() / union else 0.0

        return similarity >= 0.5
    }

    /**
     * Check if any artist in artists1 matches any artist in artists2.
     * Useful for matching tracks with different artist formatting.
     * 
     * @param artists1 First artist string (may contain multiple artists)
     * @param artists2 Second artist string (may contain multiple artists)
     * @return True if any artists match
     */
    fun hasAnyMatchingArtist(artists1: String, artists2: String): Boolean {
        // Handle empty/unknown artists - they should match any artist for same title
        // This handles the case where metadata arrives in stages
        val isUnknown1 = isUnknownArtist(artists1)
        val isUnknown2 = isUnknownArtist(artists2)
        
        // If either is unknown/empty, consider it a potential match
        // (the track title matching will be the primary discriminator)
        if (isUnknown1 || isUnknown2) {
            return true
        }
        
        val list1 = getAllArtists(artists1)
        val list2 = getAllArtists(artists2)

        return list1.any { a1 ->
            list2.any { a2 ->
                isSameArtist(a1, a2)
            }
        }
    }
    
    /**
     * Check if an artist string represents an unknown/empty artist.
     */
    fun isUnknownArtist(artist: String): Boolean {
        val normalized = artist.trim().lowercase()
        return normalized.isEmpty() ||
               normalized == "unknown artist" ||
               normalized == "unknown" ||
               normalized == "<unknown>" ||
               normalized == "various artists"
    }

    /**
     * Clean track title by removing artist mentions that are often embedded.
     * Some sources embed "feat. Artist" in the track title.
     * 
     * @param title The track title
     * @return Cleaned title without embedded artist info
     */
    fun cleanTrackTitle(title: String): String {
        var cleaned = title

        // Remove (feat. ...) or [feat. ...] patterns
        cleaned = cleaned.replace(Regex("\\s*[\\[(]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+[^)\\]]+[)\\]]", RegexOption.IGNORE_CASE), "")

        // Remove trailing feat. patterns without brackets
        cleaned = cleaned.replace(Regex("\\s+(?:feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE), "")

        // Remove common remix/version indicators for cleaner matching
        cleaned = cleaned.replace(Regex("\\s*[\\[(]\\s*(?:Remaster(?:ed)?|Deluxe|Radio Edit|Single Version|Album Version)\\s*[)\\]]", RegexOption.IGNORE_CASE), "")

        return cleaned.trim()
    }

    /**
     * Extract featured artists from a track title (if embedded).
     * 
     * @param title The track title that may contain feat. info
     * @return List of featured artists found in the title, or empty list
     */
    fun extractArtistsFromTitle(title: String): List<String> {
        for (pattern in FEATURING_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val afterMatch = title.substring(match.range.last + 1)
                    .replace(Regex("[)\\]]+.*$"), "") // Remove closing bracket and anything after
                    .trim()
                if (afterMatch.isNotEmpty()) {
                    return splitArtists(afterMatch)
                }
            }
        }
        return emptyList()
    }
}
