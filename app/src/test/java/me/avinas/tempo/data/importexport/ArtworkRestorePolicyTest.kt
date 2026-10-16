package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRestorePolicyTest {

    @Test
    fun userResetAlwaysClearsRestoredTrackMirror() {
        assertNull(
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.USER_RESET,
                metadataArtUrl = null,
                trackArtUrl = "https://backup.example/stale.jpg",
            ),
        )
    }

    @Test
    fun userSelectedMetadataWinsOverStaleTrackMirror() {
        assertEquals(
            "https://manual.example/current.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.USER_SELECTED,
                metadataArtUrl = "https://manual.example/current.jpg",
                trackArtUrl = "https://backup.example/stale.jpg",
            ),
        )
    }

    @Test
    fun canonicalAutomaticMetadataBeatsStaleRemoteTrackMirror() {
        assertEquals(
            "https://automatic.example/current.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.SPOTIFY,
                metadataArtUrl = "https://automatic.example/current.jpg",
                trackArtUrl = "https://automatic.example/stale.jpg",
            ),
        )
    }

    @Test
    fun localTrackBackupIsPreservedBesideCanonicalRemoteMetadata() {
        assertEquals(
            "file:///data/user/0/me.avinas.tempo/files/album_art/backup.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.MUSICBRAINZ,
                metadataArtUrl = "https://automatic.example/current.jpg",
                trackArtUrl = "file:///data/user/0/me.avinas.tempo/files/album_art/backup.jpg",
            ),
        )
    }

    @Test
    fun missingBundledLocalImageIsDroppedInsteadOfKeepingForeignDevicePath() {
        assertNull(
            remapRestoredImageUrl(
                exportedUrl = "file:///data/user/0/old.device/files/album_art/missing.jpg",
                pathMapping = emptyMap(),
            )
        )
    }

    @Test
    fun bundledLocalImageIsRemappedAndRemoteUrlIsPreserved() {
        val oldPath = "file:///data/user/0/old.device/files/album_art/cover.jpg"
        val newPath = "file:///data/user/0/me.avinas.tempo/files/album_art/cover.jpg"
        assertEquals(
            newPath,
            remapRestoredImageUrl(oldPath, mapOf(oldPath to newPath)),
        )
        assertEquals(
            "https://example.test/cover.jpg",
            remapRestoredImageUrl("https://example.test/cover.jpg", emptyMap()),
        )
        assertNull(
            remapRestoredImageUrl(
                "content://media/external/audio/albumart/42",
                emptyMap(),
            )
        )
    }

    @Test
    fun currentDeviceContentBackupCanStillBePreservedDuringReconciliation() {
        assertEquals(
            "content://media/external/audio/albumart/42",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.ITUNES,
                metadataArtUrl = "https://automatic.example/current.jpg",
                trackArtUrl = "content://media/external/audio/albumart/42",
            ),
        )
    }

    @Test
    fun automaticArtworkBackfillsTrackMirrorWhenMissing() {
        assertEquals(
            "https://automatic.example/cover.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.MUSICBRAINZ,
                metadataArtUrl = "https://automatic.example/cover.jpg",
                trackArtUrl = null,
            ),
        )
    }
}
