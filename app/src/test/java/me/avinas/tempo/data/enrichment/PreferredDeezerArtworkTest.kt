package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.remote.deezer.DeezerAlbum
import me.avinas.tempo.data.remote.deezer.DeezerArtist
import me.avinas.tempo.data.remote.deezer.DeezerTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredDeezerArtworkTest {
    private fun track(
        title: String = "One Dance",
        artist: String = "Drake",
        album: String = "Views",
        cover: String? = "https://example.com/art.jpg",
        preview: String? = null,
    ) = DeezerTrack(
        id = 1L,
        title = title,
        titleShort = title,
        link = "",
        duration = 180,
        rank = 10,
        explicitLyrics = false,
        explicitContentLyrics = 0,
        explicitContentCover = 0,
        preview = preview,
        artist = DeezerArtist(
            id = 2L,
            name = artist,
            link = "",
            picture = null,
            pictureSmall = null,
            pictureMedium = null,
            pictureBig = null,
            pictureXl = null,
            tracklist = "",
            type = "artist",
        ),
        album = DeezerAlbum(
            id = 3L,
            title = album,
            cover = null,
            coverSmall = null,
            coverMedium = null,
            coverBig = cover,
            coverXl = null,
            md5Image = null,
            tracklist = "",
            type = "album",
        ),
        type = "track",
    )

    @Test
    fun acceptsCorrectArtworkEvenWithoutPreview() {
        val candidate = track(preview = null)
        assertEquals(
            candidate,
            findPreferredDeezerArtwork(listOf(candidate), "Drake", "One Dance", "Views"),
        )
    }

    @Test
    fun rejectsSimilarButDifferentArtists() {
        assertNull(findPreferredDeezerArtwork(listOf(track(artist = "Drake Bell")), "Drake", "One Dance", null))
    }

    @Test
    fun rejectsDifferentSongAndMissingCover() {
        assertNull(findPreferredDeezerArtwork(listOf(track(title = "Hotline Bling")), "Drake", "One Dance", null))
        assertNull(findPreferredDeezerArtwork(listOf(track(cover = null)), "Drake", "One Dance", null))
    }

    @Test
    fun prefersCorrectAlbumWhenSeveralReleasesMatch() {
        val compilation = track(album = "Greatest Hits")
        val original = track(album = "Views")
        assertEquals(
            original,
            findPreferredDeezerArtwork(listOf(compilation, original), "Drake", "One Dance", "Views"),
        )
    }

    @Test
    fun noPreviewIsNotAnArtIdentitySignal() {
        val wrong = track(title = "Hotline Bling", preview = "https://example.com/preview.mp3")
        val right = track(preview = null)
        assertEquals(right, findPreferredDeezerArtwork(listOf(wrong, right), "Drake", "One Dance", null))
    }

    @Test
    fun acceptsShortSongTitleWithCleanedVersion() {
        val candidate = track(title = "SOS", artist = "ABBA")
        assertEquals(
            candidate,
            findPreferredDeezerArtwork(listOf(candidate), "ABBA", "SOS (feat. Agnetha)", null),
        )
    }

    @Test
    fun acceptsCollabCandidateWhenPrimaryArtistMatches() {
        val candidate = track(title = "Jimmy Cooks", artist = "Drake & 21 Savage")
        assertEquals(
            candidate,
            findPreferredDeezerArtwork(listOf(candidate), "Drake", "Jimmy Cooks", null),
        )
    }
}
