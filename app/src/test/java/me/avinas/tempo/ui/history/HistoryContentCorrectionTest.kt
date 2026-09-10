package me.avinas.tempo.ui.history

import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.DatabaseTransactionRunner
import me.avinas.tempo.data.local.entities.*
import me.avinas.tempo.data.repository.*
import me.avinas.tempo.data.stats.PaginatedResult
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryContentCorrectionTest {
    private val tracks = mutableMapOf<Long, Track>()
    private val marks = mutableListOf<ManualContentMark>()
    private val deletedPlays = mutableListOf<Long>()
    private val deletedTracks = mutableListOf<Long>()
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
    private fun track(id: Long, title: String = "Song") = Track(
        id = id, title = title, artist = "Artist", album = null, duration = null,
        albumArtUrl = null, spotifyId = null, musicbrainzId = null, contentType = "PODCAST"
    )
    private fun viewModel() = HistoryViewModel(
        fake<StatsRepository> { name, _ -> when (name) {
            "observeListeningOverview" -> emptyFlow<Any>()
            "getHistory" -> PaginatedResult<HistoryItem>(emptyList(), 0, 0, 20, false)
            "invalidateCache" -> Unit
            else -> error(name)
        } },
        fake<ListeningRepository> { name, args -> when (name) {
            "deleteByTrackId" -> { deletedPlays.add(args[0] as Long); Unit }
            else -> error(name)
        } },
        fake<TrackRepository> { name, args -> when (name) {
            "getById" -> flowOf(tracks[args[0]])
            "all" -> flowOf(tracks.values.toList())
            "update" -> { val track = args[0] as Track; tracks[track.id] = track; Unit }
            "deleteById" -> { deletedTracks.add(args[0] as Long); 1 }
            else -> error(name)
        } },
        fake<ManualContentMarkDao> { name, args -> when (name) {
            "getAllSync" -> marks.toList()
            "insertMark" -> { marks.add(args[0] as ManualContentMark); marks.size.toLong() }
            else -> error(name)
        } },
        fake<UserPreferencesDao> { name, _ -> when (name) {
            "getSync" -> UserPreferences()
            "upsert" -> Unit
            else -> error(name)
        } },
        fake<LastFmImportMetadataDao> { name, _ -> if (name == "getLatestCompleted") null else error(name) },
        fake<ScrobbleArchiveDao> { name, _ -> error(name) },
        RefreshCoordinator(),
        object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    )

    private fun checkCorrection(block: suspend TestScope.(HistoryViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = viewModel()
        val store = ViewModelStore().apply { put("history", model) }
        try {
            runCurrent()
            block(model)
            assertNull(model.uiState.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `always music from history retains plays and fixes content type`() = checkCorrection { model ->
        tracks[1] = track(1)
        model.markContent(1, "ALWAYS_MUSIC", false)
        runCurrent()
        assertEquals("MUSIC", tracks[1]?.contentType)
        assertEquals("ALWAYS_MUSIC", marks.single().contentType)
        assertTrue(deletedPlays.isEmpty())
        assertTrue(deletedTracks.isEmpty())
    }

    @Test fun `video correction removes all matching plays but keeps rule and track`() = checkCorrection { model ->
        tracks[1] = track(1)
        tracks[2] = track(2)
        tracks[3] = track(3, "Other")
        model.markContent(1, "NON_MUSIC", true)
        runCurrent()
        assertEquals(setOf(1L, 2L), deletedPlays.toSet())
        assertEquals("NON_MUSIC", marks.single().contentType)
        assertTrue(deletedTracks.isEmpty())
    }

    @Test fun `artist block preserves specific always music exception`() = checkCorrection { model ->
        tracks[1] = track(1)
        tracks[2] = track(2, "Other")
        marks.add(ManualContentMark(targetTrackId = 1, patternType = "TITLE_ARTIST",
            originalTitle = "Song", originalArtist = "Artist", patternValue = "Song", contentType = "ALWAYS_MUSIC"))
        model.markArtistContent(2, "NON_MUSIC", true)
        runCurrent()
        assertEquals(listOf(2L), deletedPlays)
        assertEquals("MUSIC", tracks[1]?.contentType)
        assertTrue(deletedTracks.isEmpty())
    }

    @Test fun `artist always music retains listening history`() = checkCorrection { model ->
        tracks[1] = track(1)
        tracks[2] = track(2, "Other")
        model.markArtistContent(1, "ALWAYS_MUSIC", false)
        runCurrent()
        assertTrue(tracks.values.all { it.contentType == "MUSIC" })
        assertTrue(deletedPlays.isEmpty())
    }
}
