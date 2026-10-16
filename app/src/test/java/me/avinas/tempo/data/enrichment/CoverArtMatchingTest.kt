package me.avinas.tempo.data.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtMatchingTest {

    @Test
    fun exactShortTitleIsAllowed() {
        assertTrue(isSafeCoverTrackTitleMatch("XO", "XO"))
    }

    @Test
    fun shortTitleCannotMatchBySubstring() {
        assertFalse(isSafeCoverTrackTitleMatch("XO", "XOXO"))
    }

    @Test
    fun ordinaryLongerTitleIsRejected() {
        assertFalse(isSafeCoverTrackTitleMatch("Stay", "Stay High"))
        assertFalse(isSafeCoverTrackTitleMatch("Home", "Homecoming"))
        assertFalse(isSafeCoverTrackTitleMatch("Stay", "Stay Live Forever"))
        assertFalse(isSafeCoverTrackTitleMatch("Song", "Song Mix Tape"))
    }

    @Test
    fun recognizedVersionSuffixCanMatch() {
        assertTrue(isSafeCoverTrackTitleMatch("Dreams", "Dreams 2011 Remaster"))
        assertTrue(isSafeCoverTrackTitleMatch("Song", "Song Live"))
        assertTrue(isSafeCoverTrackTitleMatch("Song", "Song Radio Edit"))
    }

    @Test
    fun explicitVersionDoesNotDowngradeToStudioVersion() {
        assertFalse(isSafeCoverTrackTitleMatch("Song Live", "Song"))
        assertFalse(isSafeCoverTrackTitleMatch("Song Remix", "Song"))
        assertFalse(isSafeCoverTrackTitleMatch("Dreams (Remastered)", "Dreams"))
    }

    @Test
    fun equivalentExplicitVersionMarkersStillMatch() {
        assertTrue(
            isSafeCoverTrackTitleMatch(
                "Dreams (Remastered)",
                "Dreams 2011 Remaster",
            )
        )
    }

    @Test
    fun explicitVersionRejectsAdditionalDifferentVersionKind() {
        assertFalse(isSafeCoverTrackTitleMatch("Song Live", "Song Live Remix"))
        assertFalse(isSafeCoverTrackTitleMatch("Song Remix", "Song Remix Live"))
        assertTrue(isSafeCoverTrackTitleMatch("Song Live", "Song Live Version"))
        assertTrue(isSafeCoverTrackTitleMatch("Album Deluxe", "Album Deluxe Edition"))
    }

    @Test
    fun explicitVersionSubtypesCannotCrossMatch() {
        assertFalse(isSafeCoverTrackTitleMatch("Song (Single Version)", "Song (Album Version)"))
        assertFalse(isSafeCoverTrackTitleMatch("Song Radio Edit", "Song Extended Edit"))
        assertFalse(isSafeCoverTrackTitleMatch("Song Original Mix", "Song Club Mix"))
        assertTrue(isSafeCoverTrackTitleMatch("Song Radio Edit", "Song (Radio Edit)"))
    }

    @Test
    fun cleanAndExplicitVersionsDoNotCrossMatch() {
        assertFalse(isSafeCoverTrackTitleMatch("Song (Clean)", "Song (Explicit)"))
        assertFalse(isSafeCoverTrackTitleMatch("Song (Explicit)", "Song (Clean)"))
        assertFalse(isSafeCoverTrackTitleMatch("Song (Clean Version)", "Song (Explicit Version)"))
        assertTrue(isSafeCoverTrackTitleMatch("Song (Clean)", "Song (Clean Version)"))
        assertTrue(isSafeCoverTrackTitleMatch("Song (Explicit)", "Song (Explicit Version)"))
    }

    @Test
    fun vipAndDubMixVersionsDoNotCrossMatch() {
        assertFalse(isSafeCoverTrackTitleMatch("Song (VIP Mix)", "Song (Dub Mix)"))
        assertFalse(isSafeCoverTrackTitleMatch("Song (VIP)", "Song (Club Mix)"))
        assertTrue(isSafeCoverTrackTitleMatch("Song (VIP)", "Song (VIP Mix)"))
        assertTrue(isSafeCoverTrackTitleMatch("Song (Dub)", "Song (Dub Mix)"))
    }

    @Test
    fun versionAwareSearchKeepsExplicitTitleBeforeCleanFallback() {
        assertEquals(
            listOf("Dreams (Remastered)", "Dreams"),
            coverSearchTitleVariants("Dreams (Remastered)"),
        )
        assertEquals(listOf("Dreams"), coverSearchTitleVariants("Dreams"))
    }

    @Test
    fun remasterSuffixDoesNotBreakARealMatch() {
        assertTrue(
            isSafeCoverTrackTitleMatch(
                "Dreams",
                "Dreams (Remastered)",
            )
        )
    }

    @Test
    fun featuredArtistSuffixDoesNotBreakARealMatch() {
        assertTrue(
            isSafeCoverTrackTitleMatch(
                "Stay",
                "Stay (feat. Guest Artist)",
            )
        )
    }

    @Test
    fun unrelatedTitlesAreRejected() {
        assertFalse(isSafeCoverTrackTitleMatch("Paracetamol", "Paradise"))
    }

    @Test
    fun strictArtistMatchAllowsSameArtistAndThePrefix() {
        assertTrue(isSafeCoverArtistMatch("The Weeknd", "Weeknd"))
        assertTrue(isSafeCoverArtistMatch("Dua Lipa feat. DaBaby", "Dua Lipa"))
    }

    @Test
    fun strictArtistMatchRejectsContainedDifferentArtist() {
        assertFalse(isSafeCoverArtistMatch("Drake", "Drake Bell"))
        assertFalse(isSafeCoverArtistMatch("Queen", "Queen Latifah"))
    }

    @Test
    fun featuredArtistAloneCannotIdentifyCollaborativeTrack() {
        assertFalse(isSafeCoverArtistMatch("Dua Lipa feat. DaBaby", "DaBaby"))
        assertTrue(isSafeCoverArtistMatch("Dua Lipa feat. DaBaby", "Dua Lipa"))
    }

    @Test
    fun allCoBilledPrimaryArtistsAreRequired() {
        assertTrue(isSafeCoverArtistMatch("Artist A & Artist B", "Artist A & Artist B"))
        assertFalse(isSafeCoverArtistMatch("Artist A & Artist B", "Artist A"))
        assertFalse(isSafeCoverArtistMatch("Artist A & Artist B", "Artist A feat. Artist B"))
    }

    @Test
    fun cachedIdentityRequiresBothTitleAndPrimaryArtists() {
        assertTrue(
            isSafeCoverIdentityMatch(
                expectedTitle = "Song Live",
                expectedArtist = "Artist A & Artist B",
                candidateTitle = "Song Live Version",
                candidateArtists = listOf("Artist A", "Artist B"),
            )
        )
        assertFalse(
            isSafeCoverIdentityMatch(
                expectedTitle = "Song Live",
                expectedArtist = "Artist A & Artist B",
                candidateTitle = "Song",
                candidateArtists = listOf("Artist A", "Artist B"),
            )
        )
        assertFalse(
            isSafeCoverIdentityMatch(
                expectedTitle = "Song Live",
                expectedArtist = "Artist A & Artist B",
                candidateTitle = "Song Live",
                candidateArtists = listOf("Artist A"),
            )
        )
    }

    @Test
    fun strictArtistMatchRejectsUnknownArtist() {
        assertFalse(isSafeCoverArtistMatch("Unknown Artist", "Drake"))
    }
}
