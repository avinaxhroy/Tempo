package me.avinas.tempo.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.entities.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrackResolverArtworkTest {

    @Test
    fun existingTrackPersistsAutomaticArtworkThroughGuardedWriter() = runTest {
        val existing = track(albumArtUrl = null)
        val repository = FakeTrackRepository(existing)
        val resolver = TrackResolver(repository)

        val result = resolver.resolve(
            TrackResolver.Query(
                title = existing.title,
                artist = existing.artist,
                albumArtUrl = "https://automatic.example/cover.jpg",
            )
        )

        assertFalse(result.isNewTrack)
        assertEquals(1, repository.automaticArtworkUpdates)
        assertEquals("https://automatic.example/cover.jpg", repository.storedTrack.albumArtUrl)
        assertEquals(repository.storedTrack.albumArtUrl, result.track.albumArtUrl)
    }

    @Test
    fun resolverReturnsArtworkActuallyAcceptedByGuardedWriter() = runTest {
        val existing = track(albumArtUrl = null)
        val repository = FakeTrackRepository(
            initialTrack = existing,
            resolveAutomaticArtwork = { "https://manual.example/cover.jpg" },
        )
        val resolver = TrackResolver(repository)

        val result = resolver.resolve(
            TrackResolver.Query(
                title = existing.title,
                artist = existing.artist,
                albumArtUrl = "https://automatic.example/cover.jpg",
            )
        )

        assertEquals("https://manual.example/cover.jpg", repository.storedTrack.albumArtUrl)
        assertEquals(repository.storedTrack.albumArtUrl, result.track.albumArtUrl)
    }

    @Test
    fun resolverKeepsResetArtworkEmptyWhenGuardRejectsAutomaticCandidate() = runTest {
        val existing = track(albumArtUrl = null)
        val repository = FakeTrackRepository(
            initialTrack = existing,
            resolveAutomaticArtwork = { null },
        )
        val resolver = TrackResolver(repository)

        val result = resolver.resolve(
            TrackResolver.Query(
                title = existing.title,
                artist = existing.artist,
                albumArtUrl = "https://automatic.example/cover.jpg",
            )
        )

        assertEquals(1, repository.automaticArtworkUpdates)
        assertNull(repository.storedTrack.albumArtUrl)
        assertNull(result.track.albumArtUrl)
    }

    @Test
    fun metadataBackfillAndArtworkBackfillRemainSeparated() = runTest {
        val existing = track(albumArtUrl = null)
        val repository = FakeTrackRepository(existing)
        val resolver = TrackResolver(repository)

        val result = resolver.resolve(
            TrackResolver.Query(
                title = existing.title,
                artist = existing.artist,
                album = "Resolved Album",
                duration = 215_000L,
                spotifyId = "spotify-id",
                albumArtUrl = "https://automatic.example/cover.jpg",
            )
        )

        assertEquals("Resolved Album", repository.storedTrack.album)
        assertEquals(215_000L, repository.storedTrack.duration)
        assertEquals("spotify-id", repository.storedTrack.spotifyId)
        assertEquals("https://automatic.example/cover.jpg", repository.storedTrack.albumArtUrl)
        assertEquals(repository.storedTrack, result.track)
    }

    @Test
    fun blankArtworkIsNotPersisted() = runTest {
        val existing = track(albumArtUrl = null)
        val repository = FakeTrackRepository(existing)
        val resolver = TrackResolver(repository)

        val result = resolver.resolve(
            TrackResolver.Query(
                title = existing.title,
                artist = existing.artist,
                albumArtUrl = "   ",
            )
        )

        assertEquals(0, repository.automaticArtworkUpdates)
        assertNull(repository.storedTrack.albumArtUrl)
        assertNull(result.track.albumArtUrl)
    }

    private fun track(albumArtUrl: String?): Track =
        Track(
            id = 42L,
            title = "Song",
            artist = "Artist",
            album = null,
            duration = null,
            albumArtUrl = albumArtUrl,
            spotifyId = null,
            youtubeId = null,
            musicbrainzId = null,
        )

    private class FakeTrackRepository(
        initialTrack: Track,
        private val resolveAutomaticArtwork: (String?) -> String? = { it },
    ) : TrackRepository {
        var storedTrack: Track = initialTrack
            private set

        var automaticArtworkUpdates: Int = 0
            private set

        override fun getById(id: Long): Flow<Track?> =
            flowOf(storedTrack.takeIf { it.id == id })

        override suspend fun findBySpotifyId(spotifyId: String): Track? = null
        override suspend fun findByYoutubeId(youtubeId: String): Track? = null
        override suspend fun findByMusicBrainzId(musicbrainzId: String): Track? = null

        override suspend fun findByTitleAndArtist(title: String, artist: String): Track? =
            storedTrack.takeIf { it.title == title && it.artist == artist }

        override suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track? = null
        override suspend fun findCandidatesByTitle(title: String): List<Track> = emptyList()
        override suspend fun findCandidatesByArtist(artist: String): List<Track> = emptyList()
        override suspend fun findFuzzyCandidates(title: String, artist: String): List<Track> = emptyList()

        override suspend fun insert(track: Track): Long {
            storedTrack = track.copy(id = storedTrack.id)
            return storedTrack.id
        }

        override suspend fun insertAll(tracks: List<Track>): List<Long> = emptyList()

        override suspend fun update(track: Track) {
            // Mirrors RoomTrackRepository.update(): generic row updates preserve artwork.
            storedTrack = track.copy(albumArtUrl = storedTrack.albumArtUrl)
        }

        override suspend fun updateTitle(trackId: Long, title: String) {
            if (storedTrack.id == trackId) storedTrack = storedTrack.copy(title = title)
        }

        override suspend fun updateAutomaticAlbumArtUrl(
            trackId: Long,
            albumArtUrl: String?,
        ): String? {
            automaticArtworkUpdates++
            val resolved = resolveAutomaticArtwork(albumArtUrl)
            if (storedTrack.id == trackId) storedTrack = storedTrack.copy(albumArtUrl = resolved)
            return resolved
        }

        override suspend fun updateYoutubeIdIfMissing(trackId: Long, youtubeId: String): Int {
            if (storedTrack.id != trackId || storedTrack.youtubeId != null) return 0
            storedTrack = storedTrack.copy(youtubeId = youtubeId)
            return 1
        }

        override fun all(): Flow<List<Track>> = flowOf(listOf(storedTrack))
        override suspend fun searchTracks(query: String): List<Track> = emptyList()
        override suspend fun deleteById(id: Long): Int = 0
        override suspend fun updateContentTypeByArtist(artistName: String, contentType: String): Int = 0
        override suspend fun getTrackIdsByArtist(artistName: String): List<Long> = emptyList()
        override suspend fun deleteByArtist(artistName: String): Int = 0
        override suspend fun deleteTrackWithAllData(trackId: Long): DeleteResult = DeleteResult(success = true)
    }
}
