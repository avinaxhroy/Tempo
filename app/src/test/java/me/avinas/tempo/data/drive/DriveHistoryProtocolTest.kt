package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class DriveHistoryProtocolTest {

    @Test
    fun `event id is stable for the same originating row`() {
        val first = DriveHistoryProtocol.createEventId(
            deviceId = "device-a",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = "  Track Name ",
            artist = "Artist"
        )
        val second = DriveHistoryProtocol.createEventId(
            deviceId = "device-a",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = "track name",
            artist = "artist"
        )

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun `event id separates devices and local rows`() {
        val base = DriveHistoryProtocol.createEventId("device-a", 42L, 1000L, "Song", "Artist")
        val otherDevice = DriveHistoryProtocol.createEventId("device-b", 42L, 1000L, "Song", "Artist")
        val otherRow = DriveHistoryProtocol.createEventId("device-a", 43L, 1000L, "Song", "Artist")

        assertNotEquals(base, otherDevice)
        assertNotEquals(base, otherRow)
    }

    @Test
    fun `batch id is deterministic for the ordered event set`() {
        val first = event("event-1", 1000L)
        val second = event("event-2", 2000L)

        val idA = DriveHistoryProtocol.createBatchId(listOf(first, second))
        val idB = DriveHistoryProtocol.createBatchId(listOf(first, second))
        val reversed = DriveHistoryProtocol.createBatchId(listOf(second, first))

        assertEquals(idA, idB)
        assertNotEquals(idA, reversed)
        assertEquals(64, idA.length)
    }

    @Test
    fun `protocol ids match the cross-client golden vectors`() {
        val eventId = DriveHistoryProtocol.createEventId(
            deviceId = "device-1",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = " Song ",
            artist = " Artist "
        )
        assertEquals(
            "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4",
            eventId
        )

        val batchId = DriveHistoryProtocol.createBatchId(
            listOf(event(eventId, 1_700_000_000_000L))
        )
        assertEquals(
            "785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43",
            batchId
        )
    }

    @Test
    fun `batch filename stays inside Tempo history namespace`() {
        val name = DriveHistoryProtocol.fileName("device-a", "a".repeat(64))
        assertTrue(name.startsWith(DriveHistoryProtocol.FILE_PREFIX))
        assertTrue(name.endsWith(".json.gz"))
    }

    @Test
    fun `batch filename includes accepted deletion generation`() {
        assertEquals(
            "tempo_history_v1_g1234_device-a_${"a".repeat(64)}.json.gz",
            DriveHistoryProtocol.fileName("device-a", "a".repeat(64), 1234L)
        )
        // Pre-generation clients/files map to generation zero during migration.
        assertEquals(
            "tempo_history_v1_g0_device-a_${"a".repeat(64)}.json.gz",
            DriveHistoryProtocol.fileName("device-a", "a".repeat(64))
        )
    }

    @Test
    fun `late auth failures match only the token that actually failed`() {
        assertTrue(GoogleAuthManager.failedTokenIsStillCurrent("token-a", "token-a"))
        assertFalse(GoogleAuthManager.failedTokenIsStillCurrent("token-a", "token-b"))
        assertFalse(GoogleAuthManager.failedTokenIsStillCurrent("token-a", null))
        assertFalse(GoogleAuthManager.failedTokenIsStillCurrent("", ""))
    }

    @Test
    fun `Android stream volume never masquerades as a protocol percentage`() {
        assertEquals(0, DriveHistorySyncManager.protocolVolumeLevel(0))
        assertNull(DriveHistorySyncManager.protocolVolumeLevel(8))
        assertNull(DriveHistorySyncManager.protocolVolumeLevel(null))
    }

    @Test
    fun `decoder rejects a batch with too many events`() {
        val events = listOf(event("event-0", 1_700_000_000_000L))
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(events),
            sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device",
            sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L,
            events = events
        )
        val validCompressed = DriveHistoryProtocol.encodeCompressed(batch)
        val root = JSONObject(
            GZIPInputStream(ByteArrayInputStream(validCompressed))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        )
        val eventsJson = root.getJSONArray("events")
        val template = eventsJson.getJSONObject(0)
        while (eventsJson.length() <= DriveHistoryProtocol.MAX_EVENTS_PER_BATCH) {
            eventsJson.put(template)
        }
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }

        val failure = runCatching { DriveHistoryProtocol.decodeCompressed(compressed) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("too many events"))
    }

    @Test
    fun `decoder rejects an oversized compressed payload before inflating it`() {
        val oversized = ByteArray(DriveHistoryProtocol.MAX_COMPRESSED_BYTES + 1)

        val failure = runCatching { DriveHistoryProtocol.decodeCompressed(oversized) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("compressed size limit"))
    }

    private fun event(id: String, timestamp: Long) = DriveHistoryEvent(
        eventId = if (id.matches(Regex("^[0-9a-f]{64}$"))) id else {
            DriveHistoryProtocol.createEventId(
                "fixture-device",
                id.hashCode().toLong() and 0x7fff_ffffL,
                timestamp,
                "Song $id",
                "Artist"
            )
        },
        title = "Song $id",
        artist = "Artist",
        album = null,
        timestampUtc = timestamp,
        durationMs = 180_000L,
        listenedMs = 170_000L,
        sourceApp = "test",
        source = "test",
        skipped = false,
        replayCount = 0,
        completionPercentage = 94,
        pauseCount = 0,
        seekCount = 0,
        sessionId = null,
        site = null,
        contentType = "MUSIC",
        volumeLevel = 50,
        totalPauseDurationMs = 0L,
        positionUpdatesCount = 10
    )
}
