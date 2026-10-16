package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackAliasManualArtworkTest {

    @Test
    fun targetManualArtworkWinsOverSourceManualArtwork() {
        val source = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://source.example/cover.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val target = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = "https://target.example/cover.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )

        assertEquals(target, preferredManualArtwork(source, target))
    }

    @Test
    fun sourceManualArtworkMovesWhenTargetHasNoManualChoice() {
        val source = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://source.example/cover.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val target = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = "https://automatic.example/cover.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )

        assertEquals(source, preferredManualArtwork(source, target))
    }

    @Test
    fun invalidOrAutomaticArtworkIsNotTreatedAsManualPreference() {
        val emptyManual = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val automatic = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = "https://automatic.example/cover.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        assertNull(preferredManualArtwork(emptyManual, automatic))
    }
    @Test
    fun targetAutomaticResetBeatsSourceManualArtwork() {
        val source = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://source.example/cover.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val target = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.USER_RESET,
        )

        assertNull(preferredManualArtwork(source, target))
    }

}
