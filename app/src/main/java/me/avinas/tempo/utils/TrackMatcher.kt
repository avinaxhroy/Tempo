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
    private val FEATURE_PATTERNS = listOf(
        Regex("""\s*[\(\[]?\s*(?:feat\.?|ft\.?|featuring|with|w/)\s*[^\)\]]*[\)\]]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*&\s*"""),
        Regex("""\s+x\s+""", RegexOption.IGNORE_CASE)
    )
    
    private val VERSION_PATTERNS = listOf(
        Regex("""\s*[\(\[].*(?:remix|version|edit|mix|remaster(?:ed)?|radio|acoustic|live|instrumental|extended|original|single|album|demo|bonus).*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*(?:remix|remaster(?:ed)?|live|acoustic|version|radio edit).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\d{4}\s*(?:remaster(?:ed)?|version).*$""", RegexOption.IGNORE_CASE)
    )
    
    private val EXPLICIT_PATTERNS = listOf(
        Regex("""\s*[\(\[]?\s*(?:explicit|clean|censored)\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    )
    
    private val PUNCTUATION_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    
    /**
     * Check if two tracks match, considering common variations.
     * 
     * @param title1 First track title
     * @param artist1 First artist string
     * @param title2 Second track title
     * @param artist2 Second artist string
     * @return MatchResult with similarity scores and match type
     */
    fun matchTracks(
        title1: String,
        artist1: String,
        title2: String,
        artist2: String
    ): MatchResult {
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
        val normTitle1 = normalizeTitle(title1)
        val normTitle2 = normalizeTitle(title2)
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
     */
    fun findBestMatch(
        targetTitle: String,
        targetArtist: String,
        candidates: List<TrackCandidate>
    ): Pair<TrackCandidate, MatchResult>? {
        if (candidates.isEmpty()) return null
        
        var bestMatch: TrackCandidate? = null
        var bestResult: MatchResult? = null
        
        for (candidate in candidates) {
            val result = matchTracks(targetTitle, targetArtist, candidate.title, candidate.artist)
            if (result.isMatch) {
                if (bestResult == null || result.overallScore > bestResult.overallScore) {
                    bestMatch = candidate
                    bestResult = result
                }
            }
        }
        
        return if (bestMatch != null && bestResult != null) {
            Pair(bestMatch, bestResult)
        } else null
    }
    
    /**
     * Normalize a track title by removing common variations.
     */
    fun normalizeTitle(title: String): String {
        var normalized = title.trim()
        
        // Remove explicit/clean tags
        EXPLICIT_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Remove version/remix info
        VERSION_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Remove feature artists from title
        FEATURE_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Unicode normalization (NFD then remove marks)
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
        
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
        
        // Remove feature artists (they'll be compared separately)
        FEATURE_PATTERNS.forEach { pattern ->
            normalized = pattern.replace(normalized, "")
        }
        
        // Unicode normalization
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
        
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
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        
        if (m == 0) return n
        if (n == 0) return m
        
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[m][n]
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
