package me.avinas.tempo.data.repository

import me.avinas.tempo.data.analytics.NoOpAnalyticsTracker

import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.AlbumDao
import me.avinas.tempo.data.local.dao.AlbumSearchResult
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AlbumMergeRepositoryTest {

    private inline fun <reified T : Any> createProxy(
        crossinline handler: (methodName: String, args: Array<Any?>?) -> Any?
    ): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val result = handler(method.name, args)
            if (result != null) {
                result
            } else if (method.returnType == List::class.java) {
                emptyList<Any>()
            } else if (method.returnType == java.lang.Boolean.TYPE) {
                false
            } else if (method.returnType == java.lang.Integer.TYPE) {
                0
            } else if (method.returnType == java.lang.Long.TYPE) {
                0L
            } else {
                null
            }
        } as T
    }

    private fun <T> allocateInstance(clazz: Class<T>): T {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(clazz) as T
    }

    private fun createFakeDatabase(): AppDatabase {
        val clazz = Class.forName("me.avinas.tempo.data.local.AppDatabase_Impl")
        @Suppress("UNCHECKED_CAST")
        return allocateInstance(clazz as Class<AppDatabase>)
    }

    @Test
    fun `cannot merge album into itself returns false`() = runTest {
        val albumDao = createProxy<AlbumDao> { _, _ -> null }
        val artistDao = createProxy<ArtistDao> { _, _ -> null }
        val trackDao = createProxy<TrackDao> { _, _ -> null }
        val trackAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        val scrobbleDao = createProxy<ScrobbleArchiveDao> { _, _ -> null }
        val database = createFakeDatabase()
        val statsRepo = createProxy<StatsRepository> { _, _ -> null }

        val repo = AlbumMergeRepository(
            albumDao, artistDao, trackDao, trackAliasRepo, scrobbleDao, database, statsRepo
        ).apply {
            transactionRunner = { it() }
        }

        val result = repo.mergeAlbums(10L, 10L)
        assertFalse("Self-merge must be rejected", result)
    }

    @Test
    fun `source album not found returns false`() = runTest {
        val albumDao = createProxy<AlbumDao> { method, args ->
            when (method) {
                "getAlbumById" -> if (args?.get(0) == 2L) Album(id = 2L, title = "Target", artistId = 1L, releaseYear = null, artworkUrl = null) else null
                else -> null
            }
        }
        val artistDao = createProxy<ArtistDao> { _, _ -> null }
        val trackDao = createProxy<TrackDao> { _, _ -> null }
        val trackAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        val scrobbleDao = createProxy<ScrobbleArchiveDao> { _, _ -> null }
        val database = createFakeDatabase()
        val statsRepo = createProxy<StatsRepository> { _, _ -> null }

        val repo = AlbumMergeRepository(
            albumDao, artistDao, trackDao, trackAliasRepo, scrobbleDao, database, statsRepo
        ).apply {
            transactionRunner = { it() }
        }

        val result = repo.mergeAlbums(1L, 2L)
        assertFalse("Merge with missing source must fail", result)
    }

    @Test
    fun `target album not found returns false`() = runTest {
        val albumDao = createProxy<AlbumDao> { method, args ->
            when (method) {
                "getAlbumById" -> if (args?.get(0) == 1L) Album(id = 1L, title = "Source", artistId = 1L, releaseYear = null, artworkUrl = null) else null
                else -> null
            }
        }
        val artistDao = createProxy<ArtistDao> { _, _ -> null }
        val trackDao = createProxy<TrackDao> { _, _ -> null }
        val trackAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        val scrobbleDao = createProxy<ScrobbleArchiveDao> { _, _ -> null }
        val database = createFakeDatabase()
        val statsRepo = createProxy<StatsRepository> { _, _ -> null }

        val repo = AlbumMergeRepository(
            albumDao, artistDao, trackDao, trackAliasRepo, scrobbleDao, database, statsRepo
        ).apply {
            transactionRunner = { it() }
        }

        val result = repo.mergeAlbums(1L, 2L)
        assertFalse("Merge with missing target must fail", result)
    }

    @Test
    fun `successful album merge moves unique tracks, merges duplicate tracks, copies metadata, and deletes source`() = runTest {
        val sourceAlbum = Album(
            id = 1L,
            title = "Abbey Road (Deluxe)",
            artistId = 100L,
            releaseYear = 2019,
            artworkUrl = "https://example.com/deluxe.jpg",
            musicbrainzId = "mb-deluxe-123",
            releaseType = "Album"
        )
        val targetAlbum = Album(
            id = 2L,
            title = "Abbey Road",
            artistId = 100L,
            releaseYear = null,
            artworkUrl = null,
            musicbrainzId = null,
            releaseType = null
        )

        val artist = Artist(
            id = 100L,
            name = "The Beatles",
            imageUrl = null,
            genres = listOf("Rock"),
            musicbrainzId = null,
            spotifyId = null
        )

        val sourceTracks = listOf(
            Track(id = 10L, title = "Come Together", artist = "The Beatles", album = "Abbey Road (Deluxe)", duration = 260_000L, albumArtUrl = null, spotifyId = null, musicbrainzId = null, primaryArtistId = 100L),
            Track(id = 11L, title = "Her Majesty (Bonus)", artist = "The Beatles", album = "Abbey Road (Deluxe)", duration = 25_000L, albumArtUrl = null, spotifyId = null, musicbrainzId = null, primaryArtistId = 100L)
        )

        val targetTracks = listOf(
            Track(id = 20L, title = "Come Together", artist = "The Beatles", album = "Abbey Road", duration = 259_000L, albumArtUrl = null, spotifyId = null, musicbrainzId = null, primaryArtistId = 100L),
            Track(id = 21L, title = "Something", artist = "The Beatles", album = "Abbey Road", duration = 182_000L, albumArtUrl = null, spotifyId = null, musicbrainzId = null, primaryArtistId = 100L)
        )

        var deletedAlbumId: Long? = null
        var updatedAlbum: Album? = null
        val reassignedTrackAlbums = mutableMapOf<Long, String?>()
        var mergedSourceTrackId: Long? = null
        var mergedTargetTrackId: Long? = null
        var archiveUpdatedSource: String? = null
        var archiveUpdatedTarget: String? = null
        var cacheInvalidated = false

        val albumDao = createProxy<AlbumDao> { method, args ->
            when (method) {
                "getAlbumById" -> {
                    when (args?.get(0)) {
                        1L -> sourceAlbum
                        2L -> targetAlbum
                        else -> null
                    }
                }
                "update" -> {
                    updatedAlbum = args?.get(0) as? Album
                    Unit
                }
                "deleteById" -> {
                    deletedAlbumId = args?.get(0) as? Long
                    1
                }
                else -> null
            }
        }

        val artistDao = createProxy<ArtistDao> { method, args ->
            when (method) {
                "getArtistById" -> if (args?.get(0) == 100L) artist else null
                else -> null
            }
        }

        val trackDao = createProxy<TrackDao> { method, args ->
            when (method) {
                "getTracksForAlbumByArtist" -> {
                    val title = args?.get(0) as? String
                    if (title == "Abbey Road (Deluxe)") sourceTracks else targetTracks
                }
                "setTrackAlbum" -> {
                    val trackId = args?.get(0) as Long
                    val albumTitle = args.get(1) as? String
                    reassignedTrackAlbums[trackId] = albumTitle
                    Unit
                }
                "reassignAlbumTracks" -> 0
                else -> null
            }
        }

        val trackAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        // Set mergeTracks mock behavior via reflection/proxy if needed or test trackAliasRepo directly
        // Here we override mergeTracks invocation through a subclass or delegate
        val customTrackAliasRepo = object : TrackAliasRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null }, // EnrichedMetadataDao
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null },
            // ArtistLinkingService is a concrete class (not proxyable); an
            // unconstructed instance is safe because mergeTracks is overridden
            // and linking is never reached in this test.
            allocateInstance(ArtistLinkingService::class.java),
            NoOpAnalyticsTracker()
        ) {
            override suspend fun mergeTracks(sourceTrackId: Long, targetTrackId: Long): Boolean {
                mergedSourceTrackId = sourceTrackId
                mergedTargetTrackId = targetTrackId
                return true
            }
        }

        val scrobbleDao = createProxy<ScrobbleArchiveDao> { method, args ->
            when (method) {
                "updateAlbumName" -> {
                    archiveUpdatedSource = args?.get(0) as? String
                    archiveUpdatedTarget = args?.get(1) as? String
                    1
                }
                else -> null
            }
        }

        val statsRepo = createProxy<StatsRepository> { method, _ ->
            when (method) {
                "invalidateCache" -> {
                    cacheInvalidated = true
                    Unit
                }
                else -> null
            }
        }

        val database = createFakeDatabase()

        val repo = AlbumMergeRepository(
            albumDao, artistDao, trackDao, customTrackAliasRepo, scrobbleDao, database, statsRepo
        ).apply {
            transactionRunner = { it() }
        }

        val result = repo.mergeAlbums(1L, 2L)

        assertTrue("Merge must succeed", result)
        assertEquals("Duplicate track (Come Together: 10L) must be merged into target (20L)", 10L, mergedSourceTrackId)
        assertEquals("Duplicate track must target 20L", 20L, mergedTargetTrackId)

        assertEquals(
            "Unique track 11L (Her Majesty) must be reassigned to target album title",
            "Abbey Road",
            reassignedTrackAlbums[11L]
        )

        assertEquals("Scrobble archive must be updated from source album", "Abbey Road (Deluxe)", archiveUpdatedSource)
        assertEquals("Scrobble archive must be updated to target album", "Abbey Road", archiveUpdatedTarget)

        assertEquals("Source album must be deleted", 1L, deletedAlbumId)
        assertTrue("Stats cache must be invalidated", cacheInvalidated)

        // Metadata merged
        assertEquals("Target album must receive source release year", 2019, updatedAlbum?.releaseYear)
        assertEquals("Target album must receive source artwork URL", "https://example.com/deluxe.jpg", updatedAlbum?.artworkUrl)
        assertEquals("Target album must receive source MBID", "mb-deluxe-123", updatedAlbum?.musicbrainzId)
        assertEquals("Target album must receive source release type", "Album", updatedAlbum?.releaseType)
    }

    @Test
    fun `searchAlbums delegates to albumDao`() = runTest {
        val expectedResults = listOf(
            AlbumSearchResult(
                id = 5L,
                title = "Revolver",
                artistId = 100L,
                artistName = "The Beatles",
                releaseYear = 1966,
                artworkUrl = null,
                releaseType = "Album"
            )
        )

        val albumDao = createProxy<AlbumDao> { method, args ->
            when (method) {
                "searchAlbumsWithArtist" -> {
                    val query = args?.get(0) as String
                    val excludeId = args?.get(1) as? Long
                    if (query == "Revol" && excludeId == 1L) expectedResults else emptyList()
                }
                else -> null
            }
        }

        val repo = AlbumMergeRepository(
            albumDao,
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        )

        val results = repo.searchAlbums("Revol", excludeAlbumId = 1L)
        assertEquals(1, results.size)
        assertEquals("Revolver", results[0].title)

        // Empty query returns empty list immediately
        val emptyResults = repo.searchAlbums("   ")
        assertTrue(emptyResults.isEmpty())
    }

    @Test
    fun `getAlbumsForArtist delegates to albumDao`() = runTest {
        val expectedResults = listOf(
            AlbumSearchResult(
                id = 5L,
                title = "Help!",
                artistId = 100L,
                artistName = "The Beatles",
                releaseYear = 1965,
                artworkUrl = null,
                releaseType = "Album"
            )
        )

        val albumDao = createProxy<AlbumDao> { method, args ->
            when (method) {
                "getAlbumsByArtistWithArtist" -> {
                    val artistId = args?.get(0) as Long
                    val excludeId = args?.get(1) as? Long
                    if (artistId == 100L && excludeId == 1L) expectedResults else emptyList()
                }
                else -> null
            }
        }

        val repo = AlbumMergeRepository(
            albumDao,
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        )

        val results = repo.getAlbumsForArtist(100L, excludeAlbumId = 1L)
        assertEquals(1, results.size)
        assertEquals("Help!", results[0].title)
    }
}
