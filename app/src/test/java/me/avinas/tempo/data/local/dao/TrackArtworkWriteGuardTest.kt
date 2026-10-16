package me.avinas.tempo.data.local.dao

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtworkWriteGuardTest {

    @Test
    fun manualMetadataWinsOverIncomingFullRowArtwork() {
        assertEquals(
            "https://manual.example/cover.jpg",
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.USER_SELECTED,
                manualArtUrl = "https://manual.example/cover.jpg",
                currentTrackArtUrl = "https://old.example/cover.jpg",
                incomingArtUrl = "https://automatic.example/cover.jpg",
            )
        )
    }

    @Test
    fun resetTombstoneClearsStaleTrackMirrorAndBlocksIncomingArtwork() {
        assertNull(
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.USER_RESET,
                manualArtUrl = null,
                currentTrackArtUrl = "https://stale-track.example/old-manual.jpg",
                incomingArtUrl = "https://automatic.example/new.jpg",
            )
        )
    }

    @Test
    fun normalAutomaticTrackUpdatesRemainAllowed() {
        assertEquals(
            "https://automatic.example/cover.jpg",
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.ITUNES,
                manualArtUrl = null,
                currentTrackArtUrl = null,
                incomingArtUrl = "https://automatic.example/cover.jpg",
            )
        )
    }

    @Test
    fun canonicalAutomaticArtworkKeepsExistingLocalBackup() {
        assertEquals(
            "file:///covers/song.jpg",
            resolveAutomaticTrackArtwork(
                source = AlbumArtSource.SPOTIFY,
                manualArtUrl = null,
                currentTrackArtUrl = "file:///covers/song.jpg",
                canonicalAutomaticArtUrl = "https://spotify.example/canonical.jpg",
                incomingArtUrl = "https://deezer.example/stale.jpg",
            )
        )
    }

    @Test
    fun newLocalBackupCanReplaceAnOlderLocalBackup() {
        assertEquals(
            "file:///covers/new.jpg",
            resolveAutomaticTrackArtwork(
                source = AlbumArtSource.ITUNES,
                manualArtUrl = null,
                currentTrackArtUrl = "file:///covers/old.jpg",
                canonicalAutomaticArtUrl = "https://itunes.example/canonical.jpg",
                incomingArtUrl = "file:///covers/new.jpg",
            )
        )
    }

    @Test
    fun localCanonicalSourceDoesNotFreezeAnOlderLocalTrackMirror() {
        assertEquals(
            "file:///covers/new.jpg",
            resolveAutomaticTrackArtwork(
                source = AlbumArtSource.LOCAL,
                manualArtUrl = null,
                currentTrackArtUrl = "file:///covers/old.jpg",
                canonicalAutomaticArtUrl = "file:///covers/new.jpg",
                incomingArtUrl = "file:///covers/new.jpg",
            )
        )
        assertEquals(
            false,
            shouldPreserveLocalTrackBackup(
                source = AlbumArtSource.LOCAL,
                canonicalArtworkUrl = "file:///covers/new.jpg",
                currentTrackArtworkUrl = "file:///covers/old.jpg",
            )
        )
    }

    @Test
    fun remoteAutomaticWriteStillCollapsesToCanonicalMetadataArtwork() {
        assertEquals(
            "https://spotify.example/canonical.jpg",
            resolveAutomaticTrackArtwork(
                source = AlbumArtSource.SPOTIFY,
                manualArtUrl = null,
                currentTrackArtUrl = "https://itunes.example/old.jpg",
                canonicalAutomaticArtUrl = "https://spotify.example/canonical.jpg",
                incomingArtUrl = "https://deezer.example/stale.jpg",
            )
        )
    }
}
