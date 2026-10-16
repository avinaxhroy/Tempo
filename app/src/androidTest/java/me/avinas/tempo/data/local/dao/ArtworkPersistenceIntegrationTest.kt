package me.avinas.tempo.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.avinas.tempo.data.analytics.NoOpAnalyticsTracker
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.RoomEnrichedMetadataRepository
import me.avinas.tempo.data.repository.RoomTrackRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkPersistenceIntegrationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualSelectionResetAndAutomaticReplacementStayConsistentAcrossTables() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val manualUrl = "https://manual.example/cover.jpg"
        val automaticUrl = "https://automatic.example/cover.jpg"

        metadataDao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = manualUrl,
            albumArtUrlSmall = manualUrl,
            albumArtUrlLarge = manualUrl,
            timestamp = 1L,
        )

        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_SELECTED,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )

        val rejectedAutomatic =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = automaticUrl,
            )

        assertEquals(manualUrl, rejectedAutomatic)
        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.resetArtworkToAutomatic(trackId = trackId, timestamp = 2L)

        assertNull(trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_RESET,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )

        // Simulate an inconsistent/stale Track mirror from an old snapshot or restore.
        // Any generic row update must honor USER_RESET and clear it again.
        trackDao.updateAlbumArtUrl(trackId, manualUrl)
        val staleTrack = requireNotNull(trackDao.getTrackById(trackId))
        trackDao.updatePreservingManualArtwork(
            staleTrack.copy(
                album = "Updated Album",
                albumArtUrl = "https://stale-snapshot.example/cover.jpg",
            )
        )

        val afterGenericUpdate = requireNotNull(trackDao.getTrackById(trackId))
        assertEquals("Updated Album", afterGenericUpdate.album)
        assertNull(afterGenericUpdate.albumArtUrl)

        val resetMetadata = requireNotNull(metadataDao.forTrackSync(trackId))
        metadataDao.upsertFromAutomaticEnrichment(
            resetMetadata.copy(
                albumArtUrl = automaticUrl,
                albumArtUrlSmall = automaticUrl,
                albumArtUrlLarge = automaticUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )
        val acceptedAutomatic =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = automaticUrl,
            )

        assertEquals(automaticUrl, acceptedAutomatic)
        assertEquals(automaticUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.ITUNES,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )
    }

    @Test
    fun fullRowMaintenanceUpdateCannotOverwriteConcurrentManualArtwork() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = "https://automatic.example/old.jpg",
                    spotifyId = null,
                    musicbrainzId = null,
                    contentType = "PODCAST",
                )
            )

        // Simulate code that read the Track before the user changed the artwork.
        val staleSnapshot = requireNotNull(trackDao.getTrackById(trackId))
        val manualUrl = "https://manual.example/new.jpg"
        metadataDao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = manualUrl,
            albumArtUrlSmall = manualUrl,
            albumArtUrlLarge = manualUrl,
            timestamp = 1L,
        )

        // Maintenance code must update only content_type. A targeted write cannot
        // replay stale artwork (or any other unrelated fields) from this snapshot.
        trackDao.updateContentType(staleSnapshot.id, "MUSIC")

        val updated = requireNotNull(trackDao.getTrackById(trackId))
        assertEquals("MUSIC", updated.contentType)
        assertEquals(manualUrl, updated.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_SELECTED,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )
    }

    @Test
    fun mergeKeepsResetTargetEmptyWhenSourceHasManualArtworkAndTrackMirrorIsStale() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val sourceId =
            trackDao.insert(
                Track(
                    title = "Source Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        val targetId =
            trackDao.insert(
                Track(
                    title = "Target Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val sourceManualUrl = "https://manual.example/source.jpg"
        metadataDao.setUserSelectedArtwork(
            trackId = sourceId,
            albumArtUrl = sourceManualUrl,
            albumArtUrlSmall = sourceManualUrl,
            albumArtUrlLarge = sourceManualUrl,
            timestamp = 1L,
        )
        metadataDao.resetArtworkToAutomatic(trackId = targetId, timestamp = 2L)

        // Simulate a stale mirror that must not survive the merge.
        trackDao.updateAlbumArtUrl(targetId, "https://stale-target.example/old-manual.jpg")

        val repository =
            TrackAliasRepository(
                trackAliasDao = database.trackAliasDao(),
                enrichedMetadataDao = metadataDao,
                listeningEventDao = database.listeningEventDao(),
                trackDao = trackDao,
                scrobbleArchiveDao = database.scrobbleArchiveDao(),
                database = database,
                statsRepository = unusedStatsRepository(),
                artistLinkingService =
                    ArtistLinkingService(
                        database.trackDao(),
                        database.artistDao(),
                        database.trackArtistDao(),
                        database.artistAliasDao(),
                    ),
                tracker = NoOpAnalyticsTracker(),
            )

        assertTrue(repository.mergeTracks(sourceId, targetId))
        assertNull(trackDao.getTrackById(sourceId))
        assertNull(trackDao.getTrackById(targetId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_RESET,
            metadataDao.forTrackSync(targetId)?.albumArtSource,
        )
    }


    @Test
    fun pendingCreationCannotReplaceExistingManualArtwork() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val repository = RoomEnrichedMetadataRepository(metadataDao)
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val manualUrl = "https://manual.example/cover.jpg"
        metadataDao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = manualUrl,
            albumArtUrlSmall = manualUrl,
            albumArtUrlLarge = manualUrl,
            timestamp = 1L,
        )

        repository.createPendingIfNotExists(trackId)

        val preserved = requireNotNull(metadataDao.forTrackSync(trackId))
        assertEquals(AlbumArtSource.USER_SELECTED, preserved.albumArtSource)
        assertEquals(manualUrl, preserved.albumArtUrl)
        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        val newTrackId =
            trackDao.insert(
                Track(
                    title = "Fresh Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        repository.createPendingIfNotExists(newTrackId)
        assertEquals(
            EnrichmentStatus.PENDING,
            metadataDao.forTrackSync(newTrackId)?.enrichmentStatus,
        )
    }

    @Test
    fun automaticMetadataRefreshPreservesLocalTrackBackup() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val remoteUrl = "https://itunes.example/canonical.jpg"
        val staleRemoteUrl = "https://deezer.example/stale.jpg"
        val localBackupUrl = "file:///covers/song.jpg"

        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = remoteUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )
        assertEquals(remoteUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        val storedBackup =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = localBackupUrl,
            )
        assertEquals(localBackupUrl, storedBackup)
        assertEquals(localBackupUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        val currentMetadata = requireNotNull(metadataDao.forTrackSync(trackId))
        metadataDao.upsertFromAutomaticEnrichment(
            currentMetadata.copy(
                genres = listOf("Pop"),
                cacheTimestamp = 2L,
            )
        )

        assertEquals(remoteUrl, metadataDao.forTrackSync(trackId)?.albumArtUrl)
        assertEquals(localBackupUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.upsertAllFromAutomaticEnrichment(
            listOf(
                requireNotNull(metadataDao.forTrackSync(trackId)).copy(
                    tags = listOf("tag"),
                    cacheTimestamp = 3L,
                )
            )
        )

        val staleMirrorAttempt =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = staleRemoteUrl,
            )

        assertEquals(localBackupUrl, staleMirrorAttempt)
        assertEquals(remoteUrl, metadataDao.forTrackSync(trackId)?.albumArtUrl)
        assertEquals(listOf("tag"), metadataDao.forTrackSync(trackId)?.tags)
        assertEquals(localBackupUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        // A delayed callback for a different file must not touch the current backup.
        assertNull(
            trackDao.promoteLocalAlbumArtToCanonicalIfMatches(
                trackId = trackId,
                expectedLocalUrl = "file:///covers/older.jpg",
            )
        )
        assertEquals(localBackupUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        // Consuming the exact fallback restores Track's canonical remote mirror
        // instead of leaving the denormalized Track artwork empty.
        assertEquals(
            remoteUrl,
            trackDao.promoteLocalAlbumArtToCanonicalIfMatches(
                trackId = trackId,
                expectedLocalUrl = localBackupUrl,
            ),
        )
        assertEquals(remoteUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(remoteUrl, metadataDao.forTrackSync(trackId)?.albumArtUrl)
    }

    @Test
    fun localArtworkRefreshUpdatesTrackMirrorForSingleAndBatchWrites() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Local Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val firstLocal = "file:///covers/first.jpg"
        val secondLocal = "file:///covers/second.jpg"
        val thirdLocal = "file:///covers/third.jpg"

        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = firstLocal,
                albumArtSource = AlbumArtSource.LOCAL,
            )
        )
        assertEquals(firstLocal, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.upsertFromAutomaticEnrichment(
            requireNotNull(metadataDao.forTrackSync(trackId)).copy(
                albumArtUrl = secondLocal,
                albumArtUrlSmall = secondLocal,
                albumArtUrlLarge = secondLocal,
                albumArtSource = AlbumArtSource.LOCAL,
            )
        )
        assertEquals(secondLocal, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.upsertAllFromAutomaticEnrichment(
            listOf(
                requireNotNull(metadataDao.forTrackSync(trackId)).copy(
                    albumArtUrl = thirdLocal,
                    albumArtUrlSmall = thirdLocal,
                    albumArtUrlLarge = thirdLocal,
                    albumArtSource = AlbumArtSource.LOCAL,
                )
            )
        )

        assertEquals(thirdLocal, metadataDao.forTrackSync(trackId)?.albumArtUrl)
        assertEquals(thirdLocal, trackDao.getTrackById(trackId)?.albumArtUrl)
    }

    @Test
    fun consumedLocalBackupCannotBeResurrectedByDelayedSamePathWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val repository =
            RoomTrackRepository(
                dao = trackDao,
                trackArtistDao = database.trackArtistDao(),
                manualContentMarkDao = database.manualContentMarkDao(),
                enrichedMetadataDao = metadataDao,
            )
        val trackId =
            trackDao.insert(
                Track(
                    title = "Race Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        val remoteUrl = "https://spotify.example/canonical.jpg"
        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = remoteUrl,
                albumArtSource = AlbumArtSource.SPOTIFY,
            )
        )

        val localFile = File(context.filesDir, "album_art/race-backup.jpg")
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(byteArrayOf(1, 2, 3))
        val localUrl = "file://" + localFile.absolutePath
        trackDao.updateAlbumArtUrl(trackId, localUrl)

        assertEquals(remoteUrl, repository.consumeLocalAlbumArtBackup(trackId, localUrl))
        assertTrue(!localFile.exists())
        assertEquals(remoteUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        // Simulate the delayed writer that still holds the exact same deterministic
        // file:// path. Because cleanup removed the file under the repository lock,
        // the stale write is rejected and the canonical remote mirror survives.
        assertEquals(remoteUrl, repository.updateAutomaticAlbumArtUrl(trackId, localUrl))
        assertEquals(remoteUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
    }

    @Test
    fun discardedManualFallbackCannotBeResurrectedByDelayedWriter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val repository =
            RoomTrackRepository(
                dao = trackDao,
                trackArtistDao = database.trackArtistDao(),
                manualContentMarkDao = database.manualContentMarkDao(),
                enrichedMetadataDao = metadataDao,
            )
        val trackId =
            trackDao.insert(
                Track(
                    title = "Manual Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        val manualUrl = "https://manual.example/current.jpg"
        metadataDao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = manualUrl,
            albumArtUrlSmall = manualUrl,
            albumArtUrlLarge = manualUrl,
            timestamp = 1L,
        )

        val localFile = File(context.filesDir, "album_art/obsolete-manual-backup.jpg")
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(byteArrayOf(1, 2, 3))
        val localUrl = "file://" + localFile.absolutePath

        repository.discardLocalAlbumArtBackup(localUrl)

        assertTrue(!localFile.exists())
        assertEquals(manualUrl, repository.updateAutomaticAlbumArtUrl(trackId, localUrl))
        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
    }

    @Test
    fun sharedManagedBackupIsDeletedOnlyAfterLastTrackReleasesIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val repository =
            RoomTrackRepository(
                dao = trackDao,
                trackArtistDao = database.trackArtistDao(),
                manualContentMarkDao = database.manualContentMarkDao(),
                enrichedMetadataDao = metadataDao,
            )

        suspend fun createTrack(title: String, remoteUrl: String): Long {
            val id =
                trackDao.insert(
                    Track(
                        title = title,
                        artist = "Shared Artist",
                        album = null,
                        duration = null,
                        albumArtUrl = null,
                        spotifyId = null,
                        musicbrainzId = null,
                    )
                )
            metadataDao.upsertFromAutomaticEnrichment(
                EnrichedMetadata(
                    trackId = id,
                    albumArtUrl = remoteUrl,
                    albumArtSource = AlbumArtSource.SPOTIFY,
                )
            )
            return id
        }

        val firstId = createTrack("Shared Song A", "https://remote.example/a.jpg")
        val secondId = createTrack("Shared Song B", "https://remote.example/b.jpg")
        val localFile = File(context.filesDir, "album_art/shared-backup.jpg")
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(byteArrayOf(1, 2, 3))
        val localUrl = "file://" + localFile.absolutePath
        trackDao.updateAlbumArtUrl(firstId, localUrl)
        trackDao.updateAlbumArtUrl(secondId, localUrl)

        assertEquals(
            "https://remote.example/a.jpg",
            repository.consumeLocalAlbumArtBackup(firstId, localUrl),
        )
        assertTrue(localFile.exists())
        assertEquals(localUrl, trackDao.getTrackById(secondId)?.albumArtUrl)

        assertEquals(
            "https://remote.example/b.jpg",
            repository.consumeLocalAlbumArtBackup(secondId, localUrl),
        )
        assertTrue(!localFile.exists())
    }

    @Test
    fun managedBackupStaysWhileArtistOrAlbumStillReferencesIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val artistDao = database.artistDao()
        val albumDao = database.albumDao()
        val repository =
            RoomTrackRepository(
                dao = trackDao,
                trackArtistDao = database.trackArtistDao(),
                manualContentMarkDao = database.manualContentMarkDao(),
                enrichedMetadataDao = metadataDao,
            )

        val localFile = File(context.filesDir, "album_art/shared-entity-backup.jpg")
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(byteArrayOf(4, 5, 6))
        val localUrl = "file://" + localFile.absolutePath

        val artistId =
            artistDao.insert(
                Artist(
                    name = "Artwork Owner",
                    imageUrl = localUrl,
                )
            )
        val albumId =
            albumDao.insert(
                Album(
                    title = "Artwork Album",
                    artistId = artistId,
                    releaseYear = null,
                    artworkUrl = localUrl,
                )
            )
        val trackId =
            trackDao.insert(
                Track(
                    title = "Artwork Song",
                    artist = "Artwork Owner",
                    album = "Artwork Album",
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        val remoteUrl = "https://remote.example/entity.jpg"
        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = remoteUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )
        trackDao.updateAlbumArtUrl(trackId, localUrl)

        assertEquals(remoteUrl, repository.consumeLocalAlbumArtBackup(trackId, localUrl))
        assertTrue(localFile.exists())

        artistDao.updateImageUrl(artistId, null)
        albumDao.update(requireNotNull(albumDao.getAlbumById(albumId)).copy(artworkUrl = null))
        repository.discardLocalAlbumArtBackup(localUrl)

        assertTrue(!localFile.exists())
    }

    @Test
    fun contentUriCanActAsLocalBackupWithoutFileOwnership() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val repository =
            RoomTrackRepository(
                dao = trackDao,
                trackArtistDao = database.trackArtistDao(),
                manualContentMarkDao = database.manualContentMarkDao(),
                enrichedMetadataDao = metadataDao,
            )
        val trackId =
            trackDao.insert(
                Track(
                    title = "Content Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )
        val remoteUrl = "https://remote.example/content.jpg"
        val contentUrl = "content://media/external/audio/albumart/42"
        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = remoteUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )

        assertEquals(contentUrl, repository.updateAutomaticAlbumArtUrl(trackId, contentUrl))
        assertEquals(contentUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(remoteUrl, repository.consumeLocalAlbumArtBackup(trackId, contentUrl))
        assertEquals(remoteUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
    }

    @Test
    fun automaticPriorityAndTrackMirrorStayConsistentAcrossRacingProviders() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val itunesUrl = "https://itunes.example/cover.jpg"
        val spotifyUrl = "https://spotify.example/cover.jpg"
        val staleDeezerUrl = "https://deezer.example/stale.jpg"

        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = itunesUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )
        assertEquals(itunesUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = spotifyUrl,
                albumArtSource = AlbumArtSource.SPOTIFY,
            )
        )
        assertEquals(spotifyUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.upsertFromAutomaticEnrichment(
            EnrichedMetadata(
                trackId = trackId,
                albumArtUrl = staleDeezerUrl,
                albumArtSource = AlbumArtSource.DEEZER,
                genres = listOf("Pop"),
            )
        )

        // Even a stale direct Track write must mirror the authoritative Spotify
        // decision rather than diverging from enriched_metadata.
        val mirrored =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = staleDeezerUrl,
            )

        val finalMetadata = requireNotNull(metadataDao.forTrackSync(trackId))
        assertEquals(AlbumArtSource.SPOTIFY, finalMetadata.albumArtSource)
        assertEquals(spotifyUrl, finalMetadata.albumArtUrl)
        assertEquals(listOf("Pop"), finalMetadata.genres)
        assertEquals(spotifyUrl, mirrored)
        assertEquals(spotifyUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedStatsRepository(): StatsRepository =
        Proxy.newProxyInstance(
            StatsRepository::class.java.classLoader,
            arrayOf(StatsRepository::class.java),
        ) { _, method, _ ->
            if (method.name == "invalidateCache") {
                null
            } else {
                throw AssertionError("Unexpected StatsRepository call: ${method.name}")
            }
        } as StatsRepository

}
