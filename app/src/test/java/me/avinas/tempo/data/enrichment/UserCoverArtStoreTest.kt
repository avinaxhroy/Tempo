package me.avinas.tempo.data.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCoverArtStoreTest {

    @Test
    fun contentHashIsStableAndShortHex() {
        val bytes = "cover-bytes".toByteArray()

        val hash = contentHash(bytes)

        assertEquals(hash, contentHash("cover-bytes".toByteArray()))
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun contentHashDistinguishesDifferentImages() {
        assertNotEquals(
            contentHash(byteArrayOf(1, 2, 3)),
            contentHash(byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    fun fileNamesAreScopedToTheTrack() {
        val hash = contentHash(byteArrayOf(9, 9))

        assertEquals("user_cover_42_$hash.jpg", userCoverFileName(42L, hash))
        assertNotEquals(
            userCoverFileName(42L, hash),
            userCoverFileName(43L, hash),
        )
    }

    @Test
    fun recognizesOnlyThisTracksManagedCover() {
        val ownCover = "file:///data/user/0/me.avinas.tempo/files/album_art/user_cover_7_abc123.jpg"

        assertTrue(isUserCoverArtUrl(ownCover, trackId = 7L))
        assertFalse("another track's cover must not match", isUserCoverArtUrl(ownCover, trackId = 8L))
    }

    @Test
    fun rejectsAutoExtractedArtworkAndRemoteUrls() {
        val extracted = "file:///data/user/0/me.avinas.tempo/files/album_art/art_deadbeef.jpg"

        assertFalse(isUserCoverArtUrl(extracted, trackId = 7L))
        assertFalse(isUserCoverArtUrl("https://coverartarchive.org/release/x/front", trackId = 7L))
        assertFalse(isUserCoverArtUrl("content://media/external/images/1", trackId = 7L))
        assertFalse(isUserCoverArtUrl(null, trackId = 7L))
        assertFalse(isUserCoverArtUrl("", trackId = 7L))
    }

    @Test
    fun aTrackIdPrefixDoesNotLeakAcrossTracks() {
        // "user_cover_1_..." must not be claimed by track 10, and vice versa.
        val trackOne = "file:///files/album_art/user_cover_1_abc.jpg"
        val trackTen = "file:///files/album_art/user_cover_10_abc.jpg"

        assertFalse(isUserCoverArtUrl(trackTen, trackId = 1L))
        assertFalse(isUserCoverArtUrl(trackOne, trackId = 10L))
    }

    @Test
    fun artistFileNamesAreScopedToTheArtist() {
        val hash = contentHash(byteArrayOf(4, 2))

        assertEquals("user_artist_cover_42_$hash.jpg", artistCoverFileName(42L, hash))
        assertNotEquals(
            artistCoverFileName(42L, hash),
            artistCoverFileName(43L, hash),
        )
    }

    @Test
    fun recognizesOnlyThisArtistsManagedPortrait() {
        val ownPortrait = "file:///data/user/0/me.avinas.tempo/files/album_art/user_artist_cover_7_abc123.jpg"

        assertTrue(isUserArtistCoverArtUrl(ownPortrait, artistId = 7L))
        assertFalse("another artist's portrait must not match", isUserArtistCoverArtUrl(ownPortrait, artistId = 8L))
        assertFalse(isUserArtistCoverArtUrl(ownPortrait, artistId = 70L))
    }

    @Test
    fun anArtistIdNeverClaimsATrackCoverOfTheSameNumber() {
        // The two flows share one directory, so identical numeric ids are normal.
        val trackCover = "file:///files/album_art/user_cover_1_abc.jpg"
        val artistPortrait = "file:///files/album_art/user_artist_cover_1_abc.jpg"

        assertFalse(isUserArtistCoverArtUrl(trackCover, artistId = 1L))
        assertFalse(isUserCoverArtUrl(artistPortrait, trackId = 1L))
        assertTrue(isUserCoverArtUrl(trackCover, trackId = 1L))
        assertTrue(isUserArtistCoverArtUrl(artistPortrait, artistId = 1L))
    }

    @Test
    fun artistPortraitsRejectRemoteAndAutomaticArtwork() {
        val extracted = "file:///files/album_art/art_deadbeef.jpg"

        assertFalse(isUserArtistCoverArtUrl(extracted, artistId = 7L))
        assertFalse(isUserArtistCoverArtUrl("https://i.scdn.co/image/abc", artistId = 7L))
        assertFalse(isUserArtistCoverArtUrl(null, artistId = 7L))
        assertFalse(isUserArtistCoverArtUrl("", artistId = 7L))
    }
}
