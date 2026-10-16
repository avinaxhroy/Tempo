package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzManualArtworkTest {

    @Test
    fun manualArtworkSurvivesCrossTrackMetadataReuse() {
        val current = EnrichedMetadata(
            id = 10L,
            trackId = 1L,
            albumArtUrl = "https://manual.example/cover.jpg",
            albumArtUrlSmall = "https://manual.example/cover-small.jpg",
            albumArtUrlLarge = "https://manual.example/cover-large.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val replacement = EnrichedMetadata(
            id = 10L,
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/cover.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
            genres = listOf("Rock"),
        )

        val merged = preserveUserSelectedArtwork(current, replacement)

        assertEquals(AlbumArtSource.USER_SELECTED, merged.albumArtSource)
        assertEquals("https://manual.example/cover.jpg", merged.albumArtUrl)
        assertEquals("https://manual.example/cover-small.jpg", merged.albumArtUrlSmall)
        assertEquals("https://manual.example/cover-large.jpg", merged.albumArtUrlLarge)
        assertEquals(listOf("Rock"), merged.genres)
    }

    @Test
    fun donorManualArtworkIsNeverTransferredToTargetWithoutArtwork() {
        val donorSnapshot = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://manual.example/donor.jpg",
            albumArtUrlSmall = "https://manual.example/donor-small.jpg",
            albumArtUrlLarge = "https://manual.example/donor-large.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
            genres = listOf("Rock"),
        )

        val resolved = preserveUserSelectedArtwork(
            current = null,
            replacement = donorSnapshot.copy(trackId = 2L),
        )

        assertEquals(AlbumArtSource.NONE, resolved.albumArtSource)
        assertEquals(null, resolved.albumArtUrl)
        assertEquals(null, resolved.albumArtUrlSmall)
        assertEquals(null, resolved.albumArtUrlLarge)
        assertEquals(listOf("Rock"), resolved.genres)
    }

    @Test
    fun donorManualArtworkCannotReplaceTargetsAutomaticArtwork() {
        val current = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = "https://itunes.example/target.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )
        val donorSnapshot = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = "https://manual.example/donor.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
            genres = listOf("Rock"),
        )

        val resolved = preserveUserSelectedArtwork(current, donorSnapshot)

        assertEquals(AlbumArtSource.ITUNES, resolved.albumArtSource)
        assertEquals("https://itunes.example/target.jpg", resolved.albumArtUrl)
        assertEquals(listOf("Rock"), resolved.genres)
    }

    @Test
    fun donorResetTombstoneIsNeverTransferredToTarget() {
        val donorSnapshot = EnrichedMetadata(
            trackId = 2L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.USER_RESET,
            genres = listOf("Rock"),
        )

        val resolved = preserveUserSelectedArtwork(
            current = null,
            replacement = donorSnapshot,
        )

        assertEquals(AlbumArtSource.NONE, resolved.albumArtSource)
        assertEquals(null, resolved.albumArtUrl)
        assertEquals(listOf("Rock"), resolved.genres)
    }

    @Test
    fun automaticArtworkCanBeReplacedNormally() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/cover.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )
        val replacement = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/cover.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        assertEquals(replacement, preserveUserSelectedArtwork(current, replacement))
    }
}
