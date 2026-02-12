package me.avinas.tempo.utils

import android.util.Log
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced track matching utilities for robust deduplication.
 * 
 * Handles common variations in track metadata:
 * - Different punctuation ("Don't" vs "Dont" vs "Don't")
 * - Case variations
 * - Feature artist formats ("feat.", "ft.", "featuring", "with", "&", "x")
 * - Remix/Version suffixes
 * - Special characters and Unicode normalization
 * - Remaster indicators
 * - Parenthetical content (explicit tags, year, version info)
 */
object TrackMatcher {
    
    private const val TAG = "TrackMatcher"
    
    // Similarity thresholds
    private const val EXACT_MATCH_THRESHOLD = 0.95
    private const val HIGH_MATCH_THRESHOLD = 0.85
    private const val MEDIUM_MATCH_THRESHOLD = 0.70
    
    // Patterns to normalize
    // Tier 1: "Junk" suffixes that should ALWAYS be removed
    // These describe media format, not musical content
    private val JUNK_SUFFIXES = listOf(
        Regex("""\s*[\(\[]?\s*(?:official\s+)?(?:music\s+)?(?:video|audio|lyric\s+video|visualizer|hq|hd|4k|uhd|8k)\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*official\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*with\s+lyrics\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*(?:explicit|clean|censored)\s+version\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*(?:from|off)\s+(?:the\s+)?(?:album|ep|single)\s*[^\)\]]*[\)\]]?""", RegexOption.IGNORE_CASE)
    )
    
    // Tier 2: "Version" suffixes that are removed only if merging is enabled
    // These describe distinct musical content
    private val VERSION_SUFFIXES = listOf(
        Regex("""\s*[\(\[]?\s*(?:live|acoustic|demo|remix|mix|cover|instrumental|radio\s+edit|extended|original|unplugged)\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*(?:live|acoustic|demo|remix|mix|cover|instrumental|radio\s+edit|unplugged).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\d{4}\s*(?:remaster(?:ed)?|version).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*remaster(?:ed)?\s*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*(?:mtv|bbc|live\s+at|recorded\s+at)\s+[^\)\]]+[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]?\s*(?:session|performance|concert|tour)\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    )
    
    // Feature artist patterns: removes "feat. X", "ft. X" etc. from titles
    // Used in both title and artist normalization
    private val FEATURING_PATTERNS = listOf(
        Regex("""\s*[\(\[]?\s*(?:feat\.?|ft\.?|featuring|w/)\s*[^\)\]]*[\)\]]?""", RegexOption.IGNORE_CASE)
    )
    
    // Artist-only separator patterns: converts multi-artist separators to spaces
    // Only used in artist normalization (not titles, where "&" may be part of the name)
    private val ARTIST_SEPARATOR_PATTERNS = listOf(
        Regex("""\s*[\(\[]?\s*(?:with)\s*[^\)\]]*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*&\s*"""),
        Regex("""\s+x\s+""", RegexOption.IGNORE_CASE)
    )
    
    // Explicit content markers are always removed for matching
    private val EXPLICIT_PATTERNS = listOf(
        Regex("""\s*[\(\[]?\s*(?:explicit|clean|censored)\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    )
    
    private val PUNCTUATION_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val UNICODE_MARKS_PATTERN = Regex("""\p{M}""")
    
    /**
     * Check if two tracks match, considering common variations.
     * 
     * @param title1 First track title
     * @param artist1 First artist string
     * @param title2 Second track title
     * @param artist2 Second artist string
     * @param strictMatching If true, distinct versions (Live, Remix) won't match (DEFAULT: FALSE/MERGE)
     * @return MatchResult with similarity scores and match type
     */
    fun matchTracks(
        title1: String,
        artist1: String,
        title2: String,
        artist2: String,
        strictMatching: Boolean = false // Default to merging versions (user preference default)
    ): MatchResult {
        // Safety: handle blank inputs
        if (title1.isBlank() || title2.isBlank() || artist1.isBlank() || artist2.isBlank()) {
            return MatchResult(
                isMatch = false,
                matchType = MatchType.NONE,
                titleSimilarity = 0.0,
                artistSimilarity = 0.0,
                overallScore = 0.0
            )
        }
        
        // Exact match (fast path)
        if (title1.equals(title2, ignoreCase = true) && 
            artist1.equals(artist2, ignoreCase = true)) {
            return MatchResult(
                isMatch = true,
                matchType = MatchType.EXACT,
                titleSimilarity = 1.0,
                artistSimilarity = 1.0,
                overallScore = 1.0
            )
        }
        
        // Normalize and compare
        val normTitle1 = normalizeTitle(title1, strictMatching)
        val normTitle2 = normalizeTitle(title2, strictMatching)
        val normArtist1 = normalizeArtist(artist1)
        val normArtist2 = normalizeArtist(artist2)
        
        // Calculate similarities
        val titleSimilarity = calculateSimilarity(normTitle1, normTitle2)
        val artistSimilarity = calculateArtistSimilarity(normArtist1, normArtist2, artist1, artist2)
        
        // Combined score (title weighted more heavily)
        val overallScore = (titleSimilarity * 0.6 + artistSimilarity * 0.4)
        
        val matchType = when {
            overallScore >= EXACT_MATCH_THRESHOLD -> MatchType.EXACT
            overallScore >= HIGH_MATCH_THRESHOLD -> MatchType.HIGH
            overallScore >= MEDIUM_MATCH_THRESHOLD -> MatchType.MEDIUM
            else -> MatchType.NONE
        }
        
        return MatchResult(
            isMatch = matchType != MatchType.NONE,
            matchType = matchType,
            titleSimilarity = titleSimilarity,
            artistSimilarity = artistSimilarity,
            overallScore = overallScore
        )
    }
    
    /**
     * Find the best matching track from a list.
     * Pre-normalizes the target to avoid repeated regex work per candidate.
     * Uses quick pre-filtering to reduce the number of expensive regex normalizations.
     */
    fun findBestMatch(
        targetTitle: String,
        targetArtist: String,
        candidates: List<TrackCandidate>,
        strictMatching: Boolean = false
    ): Pair<TrackCandidate, MatchResult>? {
        if (candidates.isEmpty()) return null
        if (targetTitle.isBlank() || targetArtist.isBlank()) return null
        
        val targetTitleLower = targetTitle.lowercase().trim()
        val targetArtistLower = targetArtist.lowercase().trim()
        
        // Quick check: exact case-insensitive match (no regex needed)
        for (candidate in candidates) {
            if (targetTitleLower == candidate.title.lowercase().trim() &&
                targetArtistLower == candidate.artist.lowercase().trim()) {
                return Pair(candidate, MatchResult(
                    isMatch = true,
                    matchType = MatchType.EXACT,
                    titleSimilarity = 1.0,
                    artistSimilarity = 1.0,
                    overallScore = 1.0
                ))
            }
        }
        
        // Pre-filter: only consider candidates that share at least some words with the target
        // This drastically reduces the number of expensive regex normalizations
        // Use length > 1 for titles (handles short titles like "Go", "If", "Up")
        val targetTitleWords = targetTitleLower.split(" ", "-", "_")
            .filter { it.length > 1 }.toSet()
        val targetArtistWords = targetArtistLower.split(" ", "-", "_")
            .filter { it.length > 1 }.toSet()
        
        val filteredCandidates = if (targetTitleWords.isNotEmpty()) {
            candidates.filter { candidate ->
                val candTitleLower = candidate.title.lowercase()
                val candArtistLower = candidate.artist.lowercase()
                // Must share at least one significant word in title or have artist overlap
                targetTitleWords.any { word -> word in candTitleLower } ||
                targetArtistWords.any { word -> word in candArtistLower }
            }
        } else {
            candidates
        }
        
        if (filteredCandidates.isEmpty()) return null
        
        // Pre-normalize target once to avoid repeated regex operations
        val normTargetTitle = normalizeTitle(targetTitle, strictMatching)
        val normTargetArtist = normalizeArtist(targetArtist)
        
        var bestMatch: TrackCandidate? = null
        var bestResult: MatchResult? = null
        
        for (candidate in filteredCandidates) {
            // Normalize candidate
            val normCandTitle = normalizeTitle(candidate.title, strictMatching)
            val normCandArtist = normalizeArtist(candidate.artist)
            
            // Calculate similarities using pre-normalized strings
            val titleSimilarity = calculateSimilarity(normTargetTitle, normCandTitle)
            
            // Early skip: if title similarity is too low, no point checking artist
            if (titleSimilarity < 0.4) continue
            
            val artistSimilarity = calculateArtistSimilarity(
                normTargetArtist, normCandArtist, targetArtist, candidate.artist
            )
            
            val overallScore = (titleSimilarity * 0.6 + artistSimilarity * 0.4)
            
            // Early exit on perfect match
            if (overallScore >= EXACT_MATCH_THRESHOLD) {
                return Pair(candidate, MatchResult(
                    isMatch = true,
                    matchType = MatchType.EXACT,
                    titleSimilarity = titleSimilarity,
                    artistSimilarity = artistSimilarity,
                    overallScore = overallScore
                ))
            }
            
            val matchType = when {
                overallScore >= HIGH_MATCH_THRESHOLD -> MatchType.HIGH
                overallScore >= MEDIUM_MATCH_THRESHOLD -> MatchType.MEDIUM
                else -> MatchType.NONE
            }
            
            if (matchType != MatchType.NONE) {
                if (bestResult == null || overallScore > bestResult.overallScore) {
                    bestMatch = candidate
                    bestResult = MatchResult(
                        isMatch = true,
                        matchType = matchType,
                        titleSimilarity = titleSimilarity,
                        artistSimilarity = artistSimilarity,
                        overallScore = overallScore
                    )
                }
            }
        }
        
        return if (bestMatch != null && bestResult != null) {
            Pair(bestMatch, bestResult)
        } else null
    }
    
    /**
     * Normalize a track title by removing common variations.
     * 
     * @param title The raw title
     * @param strictMatching If true, keep version suffixes (Live, Remix) to prevent merging
     */
    fun normalizeTitle(title: String, strictMatching: Boolean = false): String {
        var normalized = title.trim()
        
        // Tier 1: Always remove "Junk" suffixes (Audio, Video, etc.)
        JUNK_SUFFIXES.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Remove explicit/clean tags (always do this)
        EXPLICIT_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Tier 2: Remove "Version" suffixes ONLY if strict matching is DISABLED (i.e. we want to merge)
        // If strictMatching is TRUE, we KEEP "Live", "Remix" etc. so they remain distinct
        if (!strictMatching) {
            VERSION_SUFFIXES.forEach { pattern ->
                normalized = pattern.replace(normalized, "")
            }
        }
        
        // Remove feature artists from title (they are checked in artist match)
        // Note: only use FEATURING_PATTERNS here, not ARTIST_SEPARATOR_PATTERNS,
        // because "&" and "x" may be part of the actual title (e.g., "Jack & Diane")
        FEATURING_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Unicode normalization (NFD then remove marks)
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(UNICODE_MARKS_PATTERN, "")
        
        // Normalize punctuation and whitespace
        normalized = PUNCTUATION_REGEX.replace(normalized, " ")
        normalized = WHITESPACE_REGEX.replace(normalized, " ")
        
        return normalized.trim().lowercase()
    }
    
    /**
     * Normalize artist string.
     */
    fun normalizeArtist(artist: String): String {
        var normalized = artist.trim()
        if (normalized.isBlank()) return ""
        
        // Remove feature artists (they'll be compared separately)
        FEATURING_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        // Normalize multi-artist separators to spaces
        ARTIST_SEPARATOR_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, " ")
        }
        
        // Unicode normalization
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(UNICODE_MARKS_PATTERN, "")
        
        // Normalize punctuation
        normalized = PUNCTUATION_REGEX.replace(normalized, " ")
        normalized = WHITESPACE_REGEX.replace(normalized, " ")
        
        return normalized.trim().lowercase()
    }
    
    /**
     * Calculate similarity between two strings using multiple algorithms.
     */
    fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        // Use combination of algorithms for robustness
        val levenshtein = levenshteinSimilarity(s1, s2)
        val jaccard = jaccardSimilarity(s1, s2)
        val longestCommonSubsequence = lcsSimilarity(s1, s2)
        
        // Weight the algorithms
        return (levenshtein * 0.4 + jaccard * 0.3 + longestCommonSubsequence * 0.3)
    }
    
    /**
     * Calculate artist similarity with special handling for multi-artist strings.
     */
    private fun calculateArtistSimilarity(
        normArtist1: String,
        normArtist2: String,
        originalArtist1: String,
        originalArtist2: String
    ): Double {
        // Simple normalized comparison
        val simpleSimilarity = calculateSimilarity(normArtist1, normArtist2)
        if (simpleSimilarity >= HIGH_MATCH_THRESHOLD) {
            return simpleSimilarity
        }
        
        // Parse individual artists and check for overlap
        val artists1 = ArtistParser.getAllArtists(originalArtist1)
        val artists2 = ArtistParser.getAllArtists(originalArtist2)
        
        if (artists1.isEmpty() || artists2.isEmpty()) {
            return simpleSimilarity
        }
        
        // Check if primary artist matches
        val primary1 = normalizeArtist(artists1.first())
        val primary2 = normalizeArtist(artists2.first())
        val primarySimilarity = calculateSimilarity(primary1, primary2)
        
        if (primarySimilarity >= HIGH_MATCH_THRESHOLD) {
            return primarySimilarity
        }
        
        // Check for any overlapping artists
        val normalizedArtists1 = artists1.map { normalizeArtist(it) }.toSet()
        val normalizedArtists2 = artists2.map { normalizeArtist(it) }.toSet()
        
        val overlapCount = normalizedArtists1.count { a1 ->
            normalizedArtists2.any { a2 -> calculateSimilarity(a1, a2) >= HIGH_MATCH_THRESHOLD }
        }
        
        val overlapSimilarity = if (overlapCount > 0) {
            overlapCount.toDouble() / max(normalizedArtists1.size, normalizedArtists2.size)
        } else 0.0
        
        return max(simpleSimilarity, max(primarySimilarity, overlapSimilarity))
    }
    
    /**
     * Levenshtein distance based similarity (0-1).
     */
    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val distance = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return if (maxLen == 0) 1.0 else 1.0 - (distance.toDouble() / maxLen)
    }
    
    /**
     * Calculate Levenshtein edit distance.
     * Uses space-optimized O(min(m,n)) memory instead of O(m*n).
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        
        if (m == 0) return n
        if (n == 0) return m
        
        // Ensure s2 is the shorter string to minimize memory
        val (short, long) = if (m < n) Pair(s1, s2) else Pair(s2, s1)
        val shortLen = short.length
        val longLen = long.length
        
        var prev = IntArray(shortLen + 1) { it }
        var curr = IntArray(shortLen + 1)
        
        for (i in 1..longLen) {
            curr[0] = i
            for (j in 1..shortLen) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // deletion
                    curr[j - 1] + 1,   // insertion
                    prev[j - 1] + cost  // substitution
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        
        return prev[shortLen]
    }
    
    /**
     * Jaccard similarity based on character n-grams.
     */
    private fun jaccardSimilarity(s1: String, s2: String, n: Int = 2): Double {
        val ngrams1 = getNGrams(s1, n)
        val ngrams2 = getNGrams(s2, n)
        
        if (ngrams1.isEmpty() && ngrams2.isEmpty()) return 1.0
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0
        
        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size
        
        return intersection.toDouble() / union
    }
    
    private fun getNGrams(s: String, n: Int): Set<String> {
        if (s.length < n) return setOf(s)
        return (0..s.length - n).map { s.substring(it, it + n) }.toSet()
    }
    
    /**
     * Longest Common Subsequence based similarity.
     */
    private fun lcsSimilarity(s1: String, s2: String): Double {
        val lcsLength = longestCommonSubsequence(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return if (maxLen == 0) 1.0 else lcsLength.toDouble() / maxLen
    }
    
    private fun longestCommonSubsequence(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        
        return dp[m][n]
    }
}

/**
 * Result of a track matching operation.
 */
data class MatchResult(
    val isMatch: Boolean,
    val matchType: MatchType,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    val overallScore: Double
)

/**
 * Type of match found.
 */
enum class MatchType {
    EXACT,   // 95%+ similarity
    HIGH,    // 85-95% similarity
    MEDIUM,  // 70-85% similarity
    NONE     // <70% similarity
}

/**
 * Track candidate for matching.
 */
data class TrackCandidate(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null
)
