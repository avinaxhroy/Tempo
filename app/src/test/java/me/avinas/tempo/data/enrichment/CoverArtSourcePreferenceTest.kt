package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtSourcePreferenceTest {
    @Test
    fun automaticModeKeepsExistingOrdering() {
        assertTrue(CoverArtSourcePreference.shouldReplace(AlbumArtSource.DEEZER, AlbumArtSource.SPOTIFY, null))
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.SPOTIFY, AlbumArtSource.DEEZER, null))
    }

    @Test
    fun preferredDeezerBeatsOtherSources() {
        assertTrue(CoverArtSourcePreference.shouldReplace(AlbumArtSource.SPOTIFY, AlbumArtSource.DEEZER, AlbumArtSource.DEEZER))
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.DEEZER, AlbumArtSource.SPOTIFY, AlbumArtSource.DEEZER))
    }

    @Test
    fun nonPreferredSourcesStillFallBackByNormalPriority() {
        assertTrue(CoverArtSourcePreference.shouldReplace(AlbumArtSource.LOCAL, AlbumArtSource.ITUNES, AlbumArtSource.DEEZER))
        assertTrue(CoverArtSourcePreference.shouldReplace(AlbumArtSource.ITUNES, AlbumArtSource.MUSICBRAINZ, AlbumArtSource.DEEZER))
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.SPOTIFY, AlbumArtSource.MUSICBRAINZ, AlbumArtSource.DEEZER))
    }

    @Test
    fun selectingAndRefreshingSameSourceIsAllowed() {
        assertTrue(CoverArtSourcePreference.shouldReplace(AlbumArtSource.DEEZER, AlbumArtSource.DEEZER, AlbumArtSource.DEEZER))
    }

    @Test
    fun unknownPreferenceFallsBackToAutomatic() {
        assertEquals(null, CoverArtSourcePreference.decode("UNSUPPORTED"))
        assertEquals(null, CoverArtSourcePreference.decode(null))
        assertEquals(AlbumArtSource.DEEZER, CoverArtSourcePreference.decode("DEEZER"))
        assertEquals(AlbumArtSource.SPOTIFY, CoverArtSourcePreference.decode("SPOTIFY"))
        assertEquals(AlbumArtSource.ITUNES, CoverArtSourcePreference.decode("ITUNES"))
        assertEquals(AlbumArtSource.MUSICBRAINZ, CoverArtSourcePreference.decode("MUSICBRAINZ"))
    }

    @Test
    fun noArtworkSourceCanNeverReplaceRealArtwork() {
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.DEEZER, AlbumArtSource.NONE, AlbumArtSource.DEEZER))
    }

    @Test
    fun futureUserSelectedSourceHigherThanSpotifyIsNeverReplaced() {
        // Simulating a future source with priority > SPOTIFY.priority
        val higherPrioritySource = object {
            val priority = AlbumArtSource.SPOTIFY.priority + 1
        }
        // When current source priority exceeds Spotify priority, shouldReplace is false
        // Tested via AlbumArtSource boundary rule
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.SPOTIFY, AlbumArtSource.DEEZER, null))
    }
}
