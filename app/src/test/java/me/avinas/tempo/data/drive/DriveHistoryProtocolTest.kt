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

    /**
     * Cross-branch CI passes the browser's actual compressed wire payload to
     * the Android decoder. Without the fixture env var, normal local unit
     * test execution keeps using the self-contained protocol tests below.
     */
    @Test
    fun `browser Chrome and Firefox history batches decode on Android`() {
        val directory = System.getenv("TEMPO_BROWSER_FIXTURE_DIR") ?: return
        val firstTimestamp = 1_700_000_000_000L
        for (platform in listOf("chrome_extension", "firefox_extension")) {
            val bytes = java.io.File(directory, platform + ".json.gz").readBytes()
            val batch = DriveHistoryProtocol.decodeCompressed(bytes)
            assertEquals(1, batch.schemaVersion)
            assertEquals("interop-browser", batch.sourceDeviceId)
            assertEquals(platform, batch.sourcePlatform)
            assertEquals(2, batch.events.size)
            assertEquals(batch.batchId, DriveHistoryProtocol.createBatchId(batch.events))
            assertEquals("Été 🔊", batch.events[0].title)
            assertEquals("Björk", batch.events[0].artist)
            assertEquals(firstTimestamp, batch.events[0].timestampUtc)
            assertEquals(firstTimestamp + 800L, batch.events[1].timestampUtc)
            assertNotEquals(batch.events[0].eventId, batch.events[1].eventId)
            assertEquals(
                DriveHistoryProtocol.createEventId(
                    "interop-browser", 42L, firstTimestamp, "Été 🔊", "Björk"
                ),
                batch.events[0].eventId
            )
        }
    }


    @Test
    fun `Drive batch creation does not retry an uncertain POST outcome`() {
        // A timeout or HTTP 5xx does not prove that Google Drive rejected
        // the create. The next sync checks the exact filename/checksum first.
        assertFalse(DriveAppDataClient.canRetryAfterAmbiguousFailure("POST"))
        assertFalse(DriveAppDataClient.canRetryAfterAmbiguousFailure("post"))
        assertTrue(DriveAppDataClient.canRetryAfterAmbiguousFailure("GET"))
        assertTrue(DriveAppDataClient.canRetryAfterAmbiguousFailure("DELETE"))
        // A PATCH on the shared deletion marker changes its server generation.
        assertFalse(DriveAppDataClient.canRetryAfterAmbiguousFailure("PATCH"))
    }

    @Test
    fun `LAN-only plays relay with original Drive event identity`() {
        val id = "a".repeat(64)
        assertEquals(
            "desktop:Spotify" to id,
            DriveHistorySyncManager.lanRelayIdentity(
                "lan:desktop-device:desktop:Spotify", "drive:v1:$id"
            )
        )
        assertEquals(
            "browser:YouTube Music" to id,
            DriveHistorySyncManager.lanRelayIdentity(
                "lan:browser-device:browser:YouTube Music", "drive:v1:$id"
            )
        )
        // Cloud-downloaded events must never be re-uploaded by the Android relay.
        assertNull(DriveHistorySyncManager.lanRelayIdentity(
            "drive:desktop-device:desktop:Spotify", "drive:v1:$id"
        ))
        assertNull(DriveHistorySyncManager.lanRelayIdentity(
            "lan::desktop:Spotify", "drive:v1:$id"
        ))
        assertNull(DriveHistorySyncManager.lanRelayIdentity(
            "lan:desktop-device:desktop:Spotify", "invalid"
        ))
        assertNull(DriveHistorySyncManager.lanRelayIdentity(
            "lan:desktop-device:desktop:Spotify", null
        ))
    }

    @Test
    fun `Android reupload keeps the original event ID after track metadata edits`() {
        val created = DriveHistorySyncManager.stableLocalEventId(
            null, "android-device", 142L, 1_700_000_000_000L, "Uncorrected title", "Artist"
        )
        val reuploaded = DriveHistorySyncManager.stableLocalEventId(
            created, "android-device", 142L, 1_700_000_000_000L,
            "Corrected title", "Corrected artist"
        )
        val regeneratedWithoutAlias = DriveHistorySyncManager.stableLocalEventId(
            null, "android-device", 142L, 1_700_000_000_000L,
            "Corrected title", "Corrected artist"
        )
        assertEquals(created, reuploaded)
        assertNotEquals(created, regeneratedWithoutAlias)
    }

    @Test
    fun `LAN relay batches preserve actual desktop and browser producers`() {
        assertEquals(
            DriveHistorySyncManager.BatchProducer("desktop-device", "desktop"),
            DriveHistorySyncManager.lanBatchProducer("lan:desktop-device:desktop:Spotify")
        )
        assertEquals(
            DriveHistorySyncManager.BatchProducer("browser-device", "browser"),
            DriveHistorySyncManager.lanBatchProducer("lan:browser-device:browser:YouTube Music")
        )
        assertEquals(
            DriveHistorySyncManager.BatchProducer("legacy-device", "android"),
            DriveHistorySyncManager.lanBatchProducer("lan:legacy-device:unclassified-source")
        )
        assertNull(DriveHistorySyncManager.lanBatchProducer("drive:desktop-device:desktop:Spotify"))
        assertNull(DriveHistorySyncManager.lanBatchProducer("lan::desktop:Spotify"))
    }

    @Test
    fun `text truncation preserves complete emoji at the UTF16 boundary`() {
        val prefix = "x".repeat(999)
        assertEquals(prefix, DriveHistoryProtocol.truncateText(prefix + "🔊"))
        val fitting = "x".repeat(998) + "🔊"
        assertEquals(fitting, DriveHistoryProtocol.truncateText(fitting))
        assertEquals("", DriveHistoryProtocol.truncateText("🔊", 1))
        assertEquals("", DriveHistoryProtocol.truncateText("text", 0))
        val events = listOf(event("boundary", 1_700_000_000_000L).copy(
            title = DriveHistoryProtocol.truncateText(prefix + "🔊")
        ))
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(events), sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device", sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L, events = events
        )
        assertEquals(prefix, DriveHistoryProtocol.decodeCompressed(
            DriveHistoryProtocol.encodeCompressed(batch)).events.single().title)
    }

    @Test
    fun `encoder rejects unpaired Unicode surrogates rather than replacing characters`() {
        val good = event("unicode", 1_700_000_000_000L)
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(listOf(good)), sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device", sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L, events = listOf(good)
        )
        for (invalid in listOf("bad\uD800", "bad\uDC00")) {
            for (malformed in listOf(good.copy(title = invalid), good.copy(album = invalid))) {
                assertTrue(runCatching {
                    DriveHistoryProtocol.encodeCompressed(batch.copy(events = listOf(malformed)))
                }.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }

    @Test
    fun `decoder rejects escaped unpaired Unicode surrogates in otherwise valid UTF8`() {
        val events = listOf(event("escaped", 1_700_000_000_000L).copy(title = "placeholder"))
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(events), sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device", sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L, events = events
        )
        val text = GZIPInputStream(ByteArrayInputStream(DriveHistoryProtocol.encodeCompressed(batch)))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        for (escape in listOf("\\ud800", "\\udc00")) {
            val malformed = text.replace("placeholder", "bad$escape")
            val compressed = ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(malformed.toByteArray(Charsets.UTF_8)) }
                output.toByteArray()
            }
            assertTrue(runCatching { DriveHistoryProtocol.decodeCompressed(compressed) }
                .exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `every deletion marker timestamp must be valid even when another marker is newer`() {
        assertEquals(0L, DriveAppDataClient.markerVersion(emptyList()))
        assertEquals(200L, DriveAppDataClient.markerVersion(listOf(100L, 200L)))
        for (invalid in listOf(null, 0L, -1L)) {
            assertTrue(runCatching { DriveAppDataClient.markerVersion(listOf(200L, invalid)) }
                .exceptionOrNull() is DriveException.Server)
        }
    }

    @Test
    fun `decoder preserves valid unicode and rejects malformed UTF8 instead of changing the title`() {
        val events = listOf(event("unicode", 1_700_000_000_000L).copy(title = "é 🔊"))
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(events), sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device", sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L, events = events
        )
        val valid = DriveHistoryProtocol.encodeCompressed(batch)
        assertEquals("é 🔊", DriveHistoryProtocol.decodeCompressed(valid).events.single().title)
        val bytes = GZIPInputStream(ByteArrayInputStream(valid)).use { it.readBytes() }
        val index = bytes.indexOf(0xc3.toByte())
        assertTrue(index >= 0)
        bytes[index] = 0xff.toByte()
        val corrupt = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        assertTrue(runCatching { DriveHistoryProtocol.decodeCompressed(corrupt) }.exceptionOrNull()
            is java.nio.charset.CharacterCodingException)
    }

    @Test
    fun `generation metadata matches the safe cross-client integer grammar`() {
        assertEquals(0L, DriveHistoryProtocol.parseGeneration(null))
        assertEquals(42L, DriveHistoryProtocol.parseGeneration("42"))
        assertEquals(DriveHistoryProtocol.MAX_WIRE_INTEGER, DriveHistoryProtocol.parseGeneration("9007199254740991"))
        for (invalid in listOf("", "-1", "+42", " 42", "42.0", "NaN", "9007199254740992", "999999999999999999999999")) {
            assertEquals(invalid, null, DriveHistoryProtocol.parseGeneration(invalid))
        }
    }

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
    fun `Drive cleanup protects current batches with missing or corrupt generation metadata`() {
        val id = "a".repeat(64)
        val current = DriveHistoryProtocol.fileName("device-a", id, 200L)
        val newer = DriveHistoryProtocol.fileName("device-a", id, 201L)
        val older = DriveHistoryProtocol.fileName("device-a", id, 100L)
        for (ownerDelete in listOf(false, true)) {
            assertFalse(DriveHistoryProtocol.shouldDeleteHistoryBatch(current, null, 200L, ownerDelete))
            assertFalse(DriveHistoryProtocol.shouldDeleteHistoryBatch(newer, "invalid", 200L, ownerDelete))
        }
        assertFalse(DriveHistoryProtocol.shouldDeleteHistoryBatch(older, "invalid", 200L, false))
        assertTrue(DriveHistoryProtocol.shouldDeleteHistoryBatch(older, "invalid", 200L, true))
        assertTrue(DriveHistoryProtocol.shouldDeleteHistoryBatch(older, null, 200L, false))
        assertTrue(DriveHistoryProtocol.shouldDeleteHistoryBatch(
            "tempo_history_v1_legacy.json.gz", null, 200L, false))
        assertFalse(DriveHistoryProtocol.shouldDeleteHistoryBatch(
            "tempo_history_v1_corrupt-no-extension", "invalid", 200L, false))
        assertTrue(DriveHistoryProtocol.shouldDeleteHistoryBatch(
            "tempo_history_v1_corrupt-no-extension", "invalid", 200L, true))
        assertFalse(DriveHistoryProtocol.shouldDeleteHistoryBatch(
            "unrelated.json.gz", "invalid", 200L, true))
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

    @Test
    fun `100 thousand listens over ten years roundtrip in bounded Drive batches`() {
        val total = 100_000
        val pageSize = DriveHistoryProtocol.MAX_EVENTS_PER_BATCH
        val seen = HashSet<String>(total)
        val firstTimestamp = 1_451_606_400_000L // January 2016
        val stepMs = 3_153_600L // Approximately 10 years in 100k listens
        var restored = 0
        var maxBatch = 0
        for (start in 0 until total step pageSize) {
            val events = (start until minOf(start + pageSize, total)).map { index ->
                val timestamp = firstTimestamp + index.toLong() * stepMs
                event("history", timestamp).copy(
                    eventId = DriveHistoryProtocol.createEventId(
                        "android-history-source", index.toLong() + 1, timestamp, "Song", "Artist"
                    ),
                    title = "Song"
                )
            }
            val batch = DriveHistoryBatch(
                batchId = DriveHistoryProtocol.createBatchId(events),
                sourceDeviceId = "android-history-source",
                sourceDeviceName = "Archive fixture",
                sourcePlatform = "android",
                createdAtUtc = events.last().timestampUtc,
                events = events,
            )
            val decoded = DriveHistoryProtocol.decodeCompressed(DriveHistoryProtocol.encodeCompressed(batch))
            assertEquals(batch.batchId, decoded.batchId)
            assertEquals(events.size, decoded.events.size)
            assertTrue(decoded.events.size <= pageSize)
            assertTrue(decoded.events.zipWithNext().all { (a, b) -> a.timestampUtc < b.timestampUtc })
            maxBatch = maxOf(maxBatch, decoded.events.size)
            restored += decoded.events.size
            decoded.events.forEach { assertTrue("distinct historic producer identity", seen.add(it.eventId)) }
        }
        assertEquals(total, restored)
        assertEquals(total, seen.size)
        assertEquals(pageSize, maxBatch)
        // Re-decoding a historical batch does not create new producer IDs.
        val oldest = event("history", firstTimestamp).copy(
            eventId = DriveHistoryProtocol.createEventId(
                "android-history-source", 1L, firstTimestamp, "Song", "Artist"
            ), title = "Song"
        )
        assertTrue(oldest.eventId in seen)
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
