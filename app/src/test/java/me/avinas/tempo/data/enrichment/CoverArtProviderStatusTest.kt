package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtProviderStatusTest {

    @Test
    fun iTunesAllFailuresRemainErrors() {
        val result = resolveITunesCoverSearchTerminalResult(
            hadSuccessfulResponse = false,
            lastProviderError = "network down",
        )

        assertTrue(result is ITunesEnrichmentService.iTunesResult.Error)
        assertEquals(
            "network down",
            (result as ITunesEnrichmentService.iTunesResult.Error).message,
        )
    }

    @Test
    fun iTunesSuccessfulLookupWithoutMatchIsNotFound() {
        val result = resolveITunesCoverSearchTerminalResult(
            hadSuccessfulResponse = true,
            lastProviderError = "later transient error",
        )

        assertSame(ITunesEnrichmentService.iTunesResult.NotFound, result)
    }

    @Test
    fun musicBrainzProviderErrorMapsToErrorState() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.Error("provider unavailable")
        )

        assertEquals(CoverArtProvider.MUSICBRAINZ, result.provider)
        assertEquals(CoverArtLookupStatus.ERROR, result.status)
        assertEquals("provider unavailable", result.message)
    }

    @Test
    fun musicBrainzNoArtworkMapsToNotFoundState() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.NotFound
        )

        assertEquals(CoverArtLookupStatus.NOT_FOUND, result.status)
        assertEquals(null, result.candidate)
    }

    @Test
    fun musicBrainzSuccessMapsArtworkCandidate() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.Success(
                MusicBrainzEnrichmentService.CoverArtLookupResult(
                    albumArtUrl = "https://example.test/base.jpg",
                    albumArtUrlSmall = "https://example.test/small.jpg",
                    albumArtUrlLarge = "https://example.test/large.jpg",
                    albumTitle = "Album",
                )
            )
        )

        assertEquals(CoverArtLookupStatus.FOUND, result.status)
        assertNotNull(result.candidate)
        assertEquals("https://example.test/large.jpg", result.candidate?.albumArtUrl)
        assertEquals("Album", result.candidate?.albumTitle)
    }
    @Test
    fun spotifyCachedIdentityRejectsEditedTitleAndArtist() {
        val track = Track(
            id = 1L,
            title = "Correct Song",
            artist = "Correct Artist",
            album = null,
            duration = null,
            albumArtUrl = null,
            spotifyId = "stale-id",
            musicbrainzId = null,
        )
        val metadata = EnrichedMetadata(
            trackId = track.id,
            spotifyVerifiedArtist = "Old Artist",
        )

        assertTrue(
            !isSpotifyPickerIdentityCompatible(
                track = track,
                existingMetadata = metadata,
                providerTitle = "Old Song",
            )
        )
        assertTrue(
            isSpotifyPickerIdentityCompatible(
                track = track,
                existingMetadata = metadata.copy(spotifyVerifiedArtist = "Correct Artist"),
                providerTitle = "Correct Song",
            )
        )

        val collaboration = track.copy(artist = "Correct Artist & Guest Artist")
        assertTrue(
            isSpotifyPickerIdentityCompatible(
                track = collaboration,
                existingMetadata = metadata.copy(spotifyVerifiedArtist = "Correct Artist"),
                providerTitle = "Correct Song",
            )
        )
        assertTrue(
            !isSpotifyPickerIdentityCompatible(
                track = track,
                existingMetadata = metadata.copy(spotifyVerifiedArtist = "Correct Artist"),
                providerTitle = null,
            )
        )
    }

    @Test
    fun spotifyTrackIdExtractionHandlesPlainIdUriAndWebUrl() {
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", extractSpotifyTrackId("4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", extractSpotifyTrackId("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals(
            "4cOdK2wGLETKBW3PvgPWqT",
            extractSpotifyTrackId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123xyz"),
        )
        assertEquals(
            "4cOdK2wGLETKBW3PvgPWqT",
            extractSpotifyTrackId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT/"),
        )
        assertEquals(null, extractSpotifyTrackId(null))
        assertEquals(null, extractSpotifyTrackId(""))
        assertEquals(null, extractSpotifyTrackId("   "))
    }

    @Test
    fun lastFmMismatchedAutocorrectedIdentityIsRejected() {
        val result = mapLastFmCoverSearchResult(
            result = LastFmEnrichmentService.LastFmResult.Success(
                tags = emptyList(),
                genres = emptyList(),
                albumArtUrl = "https://lastfm.example/wrong.jpg",
                albumTitle = "Wrong Album",
                trackTitle = "Stay Live Forever",
                artistName = "Drake Bell",
            ),
            expectedTitle = "Stay",
            expectedArtist = "Drake",
        )

        assertEquals(CoverArtLookupStatus.NOT_FOUND, result.status)
        assertEquals(null, result.candidate)
    }

    @Test
    fun lastFmExactIdentityMapsArtworkCandidate() {
        val result = mapLastFmCoverSearchResult(
            result = LastFmEnrichmentService.LastFmResult.Success(
                tags = emptyList(),
                genres = emptyList(),
                albumArtUrl = "https://lastfm.example/right.jpg",
                albumTitle = "Album",
                trackTitle = "Stay",
                artistName = "Drake",
            ),
            expectedTitle = "Stay",
            expectedArtist = "Drake",
        )

        assertEquals(CoverArtLookupStatus.FOUND, result.status)
        assertEquals("https://lastfm.example/right.jpg", result.candidate?.albumArtUrl)
    }

}
