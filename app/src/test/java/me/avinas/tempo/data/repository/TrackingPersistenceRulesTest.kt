package me.avinas.tempo.data.repository

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.entities.*
import me.avinas.tempo.data.preferences.RulesTestContext
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import org.junit.Assert.*
import org.junit.Test

class TrackingPersistenceRulesTest {
    private val context = RulesTestContext()
    private val rules = TrackingRulesPreferences(context)
    private var track = Track(id = 1, title = "Song", artist = "Artist", duration = null,
        album = null, albumArtUrl = null, spotifyId = null, musicbrainzId = null)
    private var marks = emptyList<ManualContentMark>()
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
    private val repository = RoomListeningRepository(
        fake<ListeningEventDao> { name, _ -> error("Unexpected event write: $name") },
        fake<TrackDao> { name, args ->
            when (name) {
                "getTrackById" -> track
                "update" -> { track = args[0] as Track; Unit }
                else -> error(name)
            }
        },
        fake<ManualContentMarkDao> { name, _ -> if (name == "getAllSync") marks else error(name) },
        fake<EnrichedMetadataDao> { name, _ -> if (name == "forTrackSync") null else error(name) },
        context
    )
    private fun event(duration: Long = 25_000) = ListeningEvent(
        track_id = 1, timestamp = 1, playDuration = duration, source = "player", completionPercentage = 50, wasSkipped = false
    )
    private fun mark(content: String) = ManualContentMark(
        targetTrackId = 1, patternType = "TITLE_ARTIST", originalTitle = "Song",
        originalArtist = "Artist", patternValue = "Song", contentType = content
    )

    @Test fun `minimum boundary is inclusive and rechecked after settings change`() = runTest {
        assertFalse(repository.shouldPersist(event(24_999)))
        assertTrue(repository.shouldPersist(event()))
        rules.minimumPlayDurationMs = 30_000
        assertFalse(repository.shouldPersist(event()))
    }
    @Test fun `newly discovered duration rechecks maximum`() = runTest {
        assertTrue(repository.shouldPersist(event()))
        track = track.copy(duration = 1_200_001)
        assertFalse(repository.shouldPersist(event()))
        track = track.copy(duration = 1_200_000)
        assertTrue(repository.shouldPersist(event()))
    }
    @Test fun `unknown duration is not rejected by a heuristic estimate`() = runTest {
        assertTrue(repository.shouldPersist(event().copy(estimatedDurationMs = 9_000_000)))
    }
    @Test fun `no limit and app override are honored at persistence`() = runTest {
        track = track.copy(duration = 9_000_000)
        rules.defaultMaxMusicDurationMs = null
        assertTrue(repository.shouldPersist(event()))
        rules.setAppCustomMaxMusicDurationMs("player", 1_200_000)
        assertFalse(repository.shouldPersist(event()))
        rules.setAppNoLimit("player")
        assertTrue(repository.shouldPersist(event()))
    }
    @Test fun `always music bypasses maximum and normalizes classification but respects minimum`() = runTest {
        marks = listOf(mark("ALWAYS_MUSIC"))
        track = track.copy(duration = 9_000_000, contentType = "PODCAST")
        assertTrue(repository.shouldPersist(event()))
        assertEquals("MUSIC", track.contentType)
        assertFalse(repository.shouldPersist(event(1_000)))
    }
    @Test fun `video and non music override no limit`() = runTest {
        rules.defaultMaxMusicDurationMs = null
        for (content in listOf("VIDEO", "NON_MUSIC")) {
            marks = listOf(mark(content))
            assertFalse(repository.shouldPersist(event()))
        }
    }
}
