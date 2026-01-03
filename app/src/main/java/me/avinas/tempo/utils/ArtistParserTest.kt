package me.avinas.tempo.utils

/**
 * Quick test to verify smart ampersand handling works correctly.
 * Run this to test that band names are preserved while featured artists are split.
 */
fun main() {
    println("=== Testing Smart Ampersand Handling ===\n")
    
    // Test cases
    val testCases = listOf(
        // Band names that should NOT be split
        "Dead & Company",
        "Derek & The Dominos",
        "Simon & Garfunkel",
        "Hall & Oates",
        "Tom Petty & The Heartbreakers",
        "Earth Wind & Fire",
        "Kool & The Gang",
        
        // Featured artists that SHOULD be split
        "Drake & The Weeknd",
        "Ariana Grande & Justin Bieber",
        "Post Malone & Swae Lee",
        "Lady Gaga & Bradley Cooper",
        
        // CRITICAL: Pipe separator tests (the bug we're fixing)
        "KR\$NA | Supspace",
        "Artist1 | Artist2",
        "Emiway | KR\$NA | DIVINE",
        
        // Comma separator tests
        "KR\$NA, Supspace",
        "Artist1, Artist2, Artist3",
        
        // Mixed separators
        "Artist1 & Artist2, Artist3",
        "Artist1 | Artist2 & Artist3",
        
        // Edge cases
        "Artist1 & Artist2 feat. Artist3",
        "Dead & Company, Widespread Panic"
    )
    
    testCases.forEach { testCase ->
        val parsed = ArtistParser.parse(testCase)
        val allArtists = ArtistParser.getAllArtists(testCase)
        
        println("Input: \"$testCase\"")
        println("  All Artists: ${allArtists.joinToString(" | ")}")
        println("  Count: ${allArtists.size}")
        println()
    }
    
    println("\n=== Expected Behavior ===")
    println("Band names (with 'The', 'Company', etc.): Should have 1 artist")
    println("Featured collaborations: Should split into multiple artists")
}
