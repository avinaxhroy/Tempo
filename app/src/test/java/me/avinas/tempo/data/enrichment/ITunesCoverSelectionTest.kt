package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.remote.itunes.iTunesResult as AppleMusicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ITunesCoverSelectionTest {

    @Test
    fun exactAlbumBeatsEarlierCompilationForSameTrack() {
        val compilation = appleResult(
            collectionName = "Greatest Hits",
            artworkUrl = "https://example.test/compilation/100x100bb.jpg",
        )
        val exactAlbum = appleResult(
            collectionName = "Future Nostalgia",
            artworkUrl = "https://example.test/album/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(compilation, exactAlbum),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia", selected?.collectionName)
    }

    @Test
    fun recognizedAlbumEditionBeatsUnrelatedAlbumWhenExactIsMissing() {
        val unrelated = appleResult(
            collectionName = "Dance Hits",
            artworkUrl = "https://example.test/hits/100x100bb.jpg",
        )
        val deluxe = appleResult(
            collectionName = "Future Nostalgia Deluxe Edition",
            artworkUrl = "https://example.test/deluxe/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(unrelated, deluxe),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia Deluxe Edition", selected?.collectionName)
    }

    @Test
    fun unusableExactAlbumArtworkIsSkipped() {
        val noArtwork = appleResult(
            collectionName = "Future Nostalgia",
            artworkUrl = null,
        )
        val deluxe = appleResult(
            collectionName = "Future Nostalgia Deluxe Edition",
            artworkUrl = "https://example.test/deluxe/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(noArtwork, deluxe),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia Deluxe Edition", selected?.collectionName)
    }

    @Test
    fun explicitRemasterCannotDowngradeToStudioVersion() {
        val studio = appleResult(
            artistName = "Fleetwood Mac",
            trackName = "Dreams",
            collectionName = "Rumours",
            artworkUrl = "https://example.test/studio/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(studio),
            expectedArtist = "Fleetwood Mac",
            expectedTrack = "Dreams (Remastered)",
            expectedAlbum = "Rumours",
        )

        assertNull(selected)
    }

    @Test
    fun equivalentExplicitRemasterIsAccepted() {
        val remaster = appleResult(
            artistName = "Fleetwood Mac",
            trackName = "Dreams 2011 Remaster",
            collectionName = "Rumours",
            artworkUrl = "https://example.test/remaster/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(remaster),
            expectedArtist = "Fleetwood Mac",
            expectedTrack = "Dreams (Remastered)",
            expectedAlbum = "Rumours",
        )

        assertEquals("Dreams 2011 Remaster", selected?.trackName)
    }

    @Test
    fun explicitLiveVersionCannotDowngradeToStudioVersion() {
        val studio = appleResult(
            trackName = "Song",
            collectionName = "Studio Album",
            artworkUrl = "https://example.test/studio/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(studio),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Song Live",
            expectedAlbum = null,
        )

        assertNull(selected)
    }

    @Test
    fun wrongArtistIsRejectedEvenWithExactAlbum() {
        val wrongArtist = appleResult(
            artistName = "Dua Lipa Tribute",
            collectionName = "Future Nostalgia",
            artworkUrl = "https://example.test/wrong/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(wrongArtist),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertNull(selected)
    }

    private fun appleResult(
        artistName: String = "Dua Lipa",
        trackName: String = "Levitating",
        collectionName: String,
        artworkUrl: String?,
    ) = AppleMusicResult(
        artistName = artistName,
        trackName = trackName,
        collectionName = collectionName,
        artworkUrl100 = artworkUrl,
    )
}
