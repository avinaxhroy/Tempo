package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentCoverCandidateTest {

    @Test
    fun currentCandidatePreservesMatchingSizeVariants() {
        val track = track(albumArtUrl = "https://images.example/medium.jpg")
        val metadata = EnrichedMetadata(
            trackId = track.id,
            albumArtUrl = "https://images.example/medium.jpg",
            albumArtUrlSmall = "https://images.example/small.jpg",
            albumArtUrlLarge = "https://images.example/large.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
            albumTitle = "Album",
        )

        val candidate = buildCurrentCoverArtCandidate(track, metadata)

        assertEquals("https://images.example/medium.jpg", candidate?.albumArtUrl)
        assertEquals("https://images.example/small.jpg", candidate?.albumArtUrlSmall)
        assertEquals("https://images.example/large.jpg", candidate?.albumArtUrlLarge)
        assertEquals("Album", candidate?.albumTitle)
    }

    @Test
    fun staleMetadataVariantsAreNotAttachedToDifferentCurrentArtwork() {
        val track = track(albumArtUrl = "https://current.example/cover.jpg")
        val metadata = EnrichedMetadata(
            trackId = track.id,
            albumArtUrl = "https://stale.example/medium.jpg",
            albumArtUrlSmall = "https://stale.example/small.jpg",
            albumArtUrlLarge = "https://stale.example/large.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        val candidate = buildCurrentCoverArtCandidate(track, metadata)

        assertNull(candidate?.albumArtUrlSmall)
        assertEquals("https://current.example/cover.jpg", candidate?.albumArtUrlLarge)
    }

    private fun track(albumArtUrl: String?) =
        Track(
            id = 42L,
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = null,
            albumArtUrl = albumArtUrl,
            spotifyId = null,
            musicbrainzId = null,
        )
}
