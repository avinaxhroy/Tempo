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
        assertEquals(AlbumArtSource.ITUNES, CoverArtSourcePreference.decode("ITUNES"))
        assertEquals(AlbumArtSource.MUSICBRAINZ, CoverArtSourcePreference.decode("MUSICBRAINZ"))
    }

    @Test
    fun noArtworkSourceCanNeverReplaceRealArtwork() {
        assertFalse(CoverArtSourcePreference.shouldReplace(AlbumArtSource.DEEZER, AlbumArtSource.NONE, AlbumArtSource.DEEZER))
    }

    @Test
    fun spotifyIsNotAnOfferedPreference() {
        // Spotify art only exists for tracks already linked to a Spotify ID, so it is not
        // something a user can meaningfully prefer. A stored "SPOTIFY" value must decay to
        // Automatic rather than resurrect a provider we no longer offer.
        assertFalse(AlbumArtSource.SPOTIFY in CoverArtSourcePreference.PROVIDERS)
        assertEquals(null, CoverArtSourcePreference.decode("SPOTIFY"))
    }

    @Test
    fun providerListCoversEveryNonSpotifyApiSource() {
        // Guards the setting against silently losing a provider during refactors.
        assertEquals(
            listOf(AlbumArtSource.MUSICBRAINZ, AlbumArtSource.ITUNES, AlbumArtSource.DEEZER),
            CoverArtSourcePreference.PROVIDERS,
        )
    }
}
