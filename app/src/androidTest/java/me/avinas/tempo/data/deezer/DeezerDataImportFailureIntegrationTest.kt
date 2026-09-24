package me.avinas.tempo.data.deezer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.RoomTrackRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeezerDataImportFailureIntegrationTest {

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
    fun outOfMemoryAfterTrackCreationRemovesPartiallyCreatedTrack() = runBlocking {
        val realTrackRepository =
            RoomTrackRepository(
                database.trackDao(),
                database.trackArtistDao(),
                database.manualContentMarkDao(),
                database.enrichedMetadataDao(),
            )
        val expectedFailure = OutOfMemoryError("synthetic import OOM")
        var failNextTrackRead = false

        val faultingTrackRepository =
            object : TrackRepository by realTrackRepository {
                override suspend fun insert(track: Track): Long {
                    val id = realTrackRepository.insert(track)
                    if (id > 0L) {
                        failNextTrackRead = true
                    }
                    return id
                }

                override fun getById(id: Long): Flow<Track?> {
                    if (failNextTrackRead) {
                        failNextTrackRead = false
                        throw expectedFailure
                    }
                    return realTrackRepository.getById(id)
                }
            }

        val service =
            DeezerDataImportService(
                trackResolver = TrackResolver(faultingTrackRepository),
                listeningEventDao = database.listeningEventDao(),
                trackArtistDao = database.trackArtistDao(),
                artistLinkingService =
                    ArtistLinkingService(
                        database.trackDao(),
                        database.artistDao(),
                        database.trackArtistDao(),
                        database.artistAliasDao(),
                    ),
                enrichedMetadataDao = database.enrichedMetadataDao(),
                trackRepository = faultingTrackRepository,
                statsRepository = unusedStatsRepository(),
            )

        val parsed =
            DeezerXlsxParser.ParseResult(
                entries =
                    listOf(
                        DeezerXlsxParser.Entry(
                            trackName = "Synthetic OOM Track",
                            artistName = "Synthetic OOM Artist",
                            albumName = "Synthetic OOM Album",
                            isrc = "USABC2600001",
                            listenedAtMillis = 1_790_000_000_000L,
                            msPlayed = 120_000L,
                        ),
                    ),
                malformedRows = 0,
            )

        val thrown =
            try {
                service.importEntries(
                    parsed = parsed,
                    errors = mutableListOf(),
                    minimumPlayDurationMs = 25_000L,
                )
                null
            } catch (failure: Throwable) {
                failure
            }

        assertSame(expectedFailure, thrown)
        assertEquals(0, database.trackDao().getCount())
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedStatsRepository(): StatsRepository =
        Proxy.newProxyInstance(
            StatsRepository::class.java.classLoader,
            arrayOf(StatsRepository::class.java),
        ) { _, method, _ ->
            throw AssertionError("Unexpected StatsRepository call: ${method.name}")
        } as StatsRepository
}
