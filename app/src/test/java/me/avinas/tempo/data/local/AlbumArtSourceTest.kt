package me.avinas.tempo.data.local

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtSourceTest {

    @Test
    fun userSelectedArtworkCannotBeReplacedByAutomaticSources() {
        val manual = AlbumArtSource.USER_SELECTED

        assertFalse(manual.shouldBeReplacedBy(AlbumArtSource.SPOTIFY))
        assertFalse(manual.shouldBeReplacedBy(AlbumArtSource.MUSICBRAINZ))
        assertFalse(manual.shouldBeReplacedBy(AlbumArtSource.ITUNES))
        assertFalse(manual.shouldBeReplacedBy(AlbumArtSource.DEEZER))
        assertFalse(manual.shouldBeReplacedBy(AlbumArtSource.LOCAL))
    }

    @Test
    fun explicitUserSelectionCanReplaceEveryAutomaticSource() {
        val automaticSources = listOf(
            AlbumArtSource.NONE,
            AlbumArtSource.USER_RESET,
            AlbumArtSource.LOCAL,
            AlbumArtSource.DEEZER,
            AlbumArtSource.ITUNES,
            AlbumArtSource.MUSICBRAINZ,
            AlbumArtSource.SPOTIFY,
        )

        automaticSources.forEach { source ->
            assertTrue(source.shouldBeReplacedBy(AlbumArtSource.USER_SELECTED))
        }
    }

    @Test
    fun userSelectedArtworkIsNotClassifiedAsApiArtwork() {
        assertTrue(AlbumArtSource.USER_SELECTED.isUserSelected())
        assertFalse(AlbumArtSource.USER_SELECTED.isApiSource())
    }

    @Test
    fun userResetCanBeReplacedByRealAutomaticArtwork() {
        val reset = AlbumArtSource.USER_RESET

        assertTrue(reset.shouldBeReplacedBy(AlbumArtSource.LOCAL))
        assertTrue(reset.shouldBeReplacedBy(AlbumArtSource.DEEZER))
        assertTrue(reset.shouldBeReplacedBy(AlbumArtSource.ITUNES))
        assertTrue(reset.shouldBeReplacedBy(AlbumArtSource.MUSICBRAINZ))
        assertTrue(reset.shouldBeReplacedBy(AlbumArtSource.SPOTIFY))
        assertFalse(reset.isApiSource())
        assertFalse(reset.isUserSelected())
    }

    @Test
    fun userResetIsNeverAnAutomaticReplacementCandidate() {
        AlbumArtSource.values().forEach { source ->
            assertFalse(source.shouldBeReplacedBy(AlbumArtSource.USER_RESET))
        }
    }
}
