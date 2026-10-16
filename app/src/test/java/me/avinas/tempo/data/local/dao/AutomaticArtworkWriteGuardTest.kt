package me.avinas.tempo.data.local.dao

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticArtworkWriteGuardTest {

    @Test
    fun automaticWriteCannotOverwriteCurrentManualArtwork() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://manual.example/new.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val staleAutomatic = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/old.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
            genres = listOf("Pop"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(current, staleAutomatic)

        assertEquals(AlbumArtSource.USER_SELECTED, merged.albumArtSource)
        assertEquals("https://manual.example/new.jpg", merged.albumArtUrl)
        assertEquals(listOf("Pop"), merged.genres)
    }

    @Test
    fun staleManualSnapshotCannotResurrectAfterAutomaticReset() {
        val currentAfterReset = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.USER_RESET,
        )
        val staleManual = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://manual.example/old.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
            genres = listOf("Rock"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(currentAfterReset, staleManual)

        assertEquals(AlbumArtSource.USER_RESET, merged.albumArtSource)
        assertEquals(null, merged.albumArtUrl)
        assertEquals(listOf("Rock"), merged.genres)
    }

    @Test
    fun automaticArtworkStillUpdatesWhenNoManualChoiceExists() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://deezer.example/old.jpg",
            albumArtSource = AlbumArtSource.DEEZER,
        )
        val incoming = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/new.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        assertEquals(incoming, mergeAutomaticEnrichmentArtwork(current, incoming))
    }
    @Test
    fun resetTombstoneClearsOnlyWhenRealAutomaticArtworkArrives() {
        val currentAfterReset = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.USER_RESET,
        )
        val incomingWithoutArtwork = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.NONE,
            genres = listOf("Pop"),
        )
        val incomingWithArtwork = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/new.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )

        val stillReset = mergeAutomaticEnrichmentArtwork(currentAfterReset, incomingWithoutArtwork)
        val replaced = mergeAutomaticEnrichmentArtwork(currentAfterReset, incomingWithArtwork)

        assertEquals(AlbumArtSource.USER_RESET, stillReset.albumArtSource)
        assertEquals(null, stillReset.albumArtUrl)
        assertEquals(listOf("Pop"), stillReset.genres)
        assertEquals(AlbumArtSource.ITUNES, replaced.albumArtSource)
        assertEquals("https://itunes.example/new.jpg", replaced.albumArtUrl)
    }

    @Test
    fun staleLowerPriorityAutomaticArtworkCannotOverwriteCurrentWinner() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://spotify.example/winner.jpg",
            albumArtUrlSmall = "https://spotify.example/winner-small.jpg",
            albumArtUrlLarge = "https://spotify.example/winner-large.jpg",
            albumArtSource = AlbumArtSource.SPOTIFY,
            genres = listOf("Old"),
        )
        val staleLowerPriority = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://deezer.example/stale.jpg",
            albumArtSource = AlbumArtSource.DEEZER,
            genres = listOf("Pop"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(current, staleLowerPriority)

        assertEquals(AlbumArtSource.SPOTIFY, merged.albumArtSource)
        assertEquals("https://spotify.example/winner.jpg", merged.albumArtUrl)
        assertEquals("https://spotify.example/winner-small.jpg", merged.albumArtUrlSmall)
        assertEquals("https://spotify.example/winner-large.jpg", merged.albumArtUrlLarge)
        assertEquals(listOf("Pop"), merged.genres)
    }

    @Test
    fun metadataOnlyAutomaticWriteCannotEraseExistingArtwork() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/cover.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )
        val incomingWithoutArtwork = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.NONE,
            tags = listOf("favorite"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(current, incomingWithoutArtwork)

        assertEquals(AlbumArtSource.ITUNES, merged.albumArtSource)
        assertEquals("https://itunes.example/cover.jpg", merged.albumArtUrl)
        assertEquals(listOf("favorite"), merged.tags)
    }

    @Test
    fun samePriorityAutomaticSourceCanRefreshArtwork() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/old.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )
        val refreshed = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/new.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )

        assertEquals(refreshed, mergeAutomaticEnrichmentArtwork(current, refreshed))
    }

}
