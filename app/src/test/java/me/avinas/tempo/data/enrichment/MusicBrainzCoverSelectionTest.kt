package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.remote.musicbrainz.MBCoverArtArchive
import me.avinas.tempo.data.remote.musicbrainz.MBRelease
import me.avinas.tempo.data.remote.musicbrainz.MBReleaseGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzCoverSelectionTest {

    @Test
    fun exactAlbumHintBeatsEarlierCompilationResult() {
        val compilation = release(
            id = "comp",
            title = "Greatest Hits",
            secondaryTypes = listOf("Compilation"),
        )
        val expectedAlbum = release(
            id = "album",
            title = "Future Nostalgia",
        )

        val ranked = rankMusicBrainzReleases(
            listOf(compilation, expectedAlbum),
            albumHint = "Future Nostalgia",
        )

        assertEquals("album", ranked.first().id)
    }

    @Test
    fun releaseGroupTitleCanResolveDeluxeEditionToKnownAlbum() {
        val wrong = release(id = "wrong", title = "Dance Collection")
        val deluxe = release(
            id = "deluxe",
            title = "Future Nostalgia (Deluxe Edition)",
            groupTitle = "Future Nostalgia",
        )

        val ranked = rankMusicBrainzReleases(
            listOf(wrong, deluxe),
            albumHint = "Future Nostalgia",
        )

        assertEquals("deluxe", ranked.first().id)
    }

    @Test
    fun frontArtworkAndOfficialStatusWinWhenAlbumIsUnknown() {
        val weak = release(
            id = "weak",
            title = "Song Release",
            status = "Bootleg",
            front = false,
            artwork = false,
        )
        val strong = release(
            id = "strong",
            title = "Song Release",
            status = "Official",
            front = true,
            artwork = true,
        )

        val ranked = rankMusicBrainzReleases(listOf(weak, strong), albumHint = null)

        assertEquals("strong", ranked.first().id)
    }

    @Test
    fun compilationIsPenalizedWithoutAlbumHint() {
        val compilation = release(
            id = "comp",
            title = "Mega Hits",
            secondaryTypes = listOf("Compilation"),
        )
        val album = release(
            id = "album",
            title = "Artist Album",
        )

        val ranked = rankMusicBrainzReleases(listOf(compilation, album), albumHint = null)

        assertEquals("album", ranked.first().id)
    }

    @Test
    fun shortAlbumHintDoesNotMatchBySubstring() {
        val unrelated = release(id = "unrelated", title = "SOS Deluxe Collection")
        val ordinary = release(id = "ordinary", title = "Another Album")

        val ranked = rankMusicBrainzReleases(
            listOf(unrelated, ordinary),
            albumHint = "SOS",
        )

        assertEquals("ordinary", ranked.first().id)
    }

    @Test
    fun exactCompilationHintStillWinsWhenUserAlbumPointsThere() {
        val album = release(id = "album", title = "Artist Album")
        val compilation = release(
            id = "comp",
            title = "Mega Hits",
            secondaryTypes = listOf("Compilation"),
        )

        val ranked = rankMusicBrainzReleases(
            listOf(album, compilation),
            albumHint = "Mega Hits",
        )

        assertEquals("comp", ranked.first().id)
    }

    private fun release(
        id: String,
        title: String,
        groupTitle: String = title,
        status: String = "Official",
        secondaryTypes: List<String>? = null,
        front: Boolean = true,
        artwork: Boolean = true,
    ) = MBRelease(
        id = id,
        title = title,
        status = status,
        date = "2024-01-01",
        releaseGroup = MBReleaseGroup(
            id = "group-$id",
            title = groupTitle,
            primaryType = "Album",
            secondaryTypes = secondaryTypes,
            firstReleaseDate = "2024-01-01",
        ),
        coverArtArchive = MBCoverArtArchive(
            artwork = artwork,
            count = if (artwork) 1 else 0,
            front = front,
            back = false,
        ),
    )
}
