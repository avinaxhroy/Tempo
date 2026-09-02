package me.avinas.tempo.data.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Versioned, platform-neutral protocol used to exchange listening history through
 * Google Drive's appDataFolder. Android, the browser extension and Tempo Desktop
 * must keep this wire format compatible.
 *
 * Drive is only the transport. The local databases remain the source of truth on
 * each device and every imported event goes through Tempo's normal dedup pipeline.
 */
object DriveHistoryProtocol {
    const val SCHEMA_VERSION = 1
    const val FILE_PREFIX = "tempo_history_v1_"
    const val APP_PROPERTY_KIND = "tempo_kind"
    const val APP_PROPERTY_SCHEMA = "tempo_schema"
    const val APP_PROPERTY_DEVICE_ID = "source_device_id"
    const val APP_PROPERTY_PLATFORM = "source_platform"
    const val APP_PROPERTY_GENERATION = "tempo_generation"
    const val APP_PROPERTY_SHA256 = "tempo_sha256"
    const val KIND_HISTORY_BATCH = "history_batch"

    // Keep hostile/corrupt Drive files from forcing unbounded allocations. These
    // limits mirror the Desktop reader and are deliberately far above normal
    // 50-event Tempo batches.
    const val MAX_COMPRESSED_BYTES = 10 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 10 * 1024 * 1024
    const val MAX_EVENTS_PER_BATCH = 1_000
    private const val MAX_PRIMARY_TEXT_LENGTH = 1_000
    const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,200}$")
    private val PLATFORM_PATTERN = Regex("^[A-Za-z0-9._-]{1,100}$")

    /** Kept for callers that need a one-off identifier outside retryable uploads. */
    fun newBatchId(): String = sha256(UUID.randomUUID().toString())

    /**
     * Generation is the Google-server timestamp of the deletion marker this
     * client explicitly accepted. It is part of the filename as well as Drive
     * metadata so a post-delete re-seed can never collide with an old immutable
     * batch that happens to contain the same deterministic event IDs.
     */
    fun fileName(deviceId: String, batchId: String, generation: Long = 0L): String {
        require(isValidDeviceId(deviceId)) { "Invalid Tempo Drive device id" }
        require(SHA256_PATTERN.matches(batchId)) { "Invalid Tempo Drive batch id" }
        require(generation >= 0L) { "Tempo Drive generation cannot be negative" }
        return "${FILE_PREFIX}g${generation}_${deviceId}_${batchId}.json.gz"
    }

    /**
     * Deterministic batch id derived only from the ordered event ids.
     *
     * A network upload may reach Drive even if the client is killed before it can
     * persist its local cursor/flag. Retrying the same chunk must therefore target
     * the same Drive filename instead of creating a second immutable copy.
     */
    fun createBatchId(events: List<DriveHistoryEvent>): String {
        require(events.isNotEmpty()) { "A history batch cannot be empty" }
        require(events.all { SHA256_PATTERN.matches(it.eventId) }) {
            "A history batch contains an invalid event id"
        }
        return sha256(
            buildString {
                append("tempo-batch-v1")
                events.forEach { event ->
                    append('|')
                    append(event.eventId)
                }
            }
        )
    }

    /**
     * Stable event id for a locally-owned database row. It deliberately includes
     * the random Tempo device id so integer row ids from two devices can never
     * collide. Imported Drive events retain their original event_id instead of
     * generating a new one, preventing sync loops.
     */
    fun createEventId(
        deviceId: String,
        localEventId: Long,
        timestampUtc: Long,
        title: String,
        artist: String
    ): String {
        require(isValidDeviceId(deviceId)) { "Invalid Tempo Drive device id" }
        require(localEventId >= 0L) { "Tempo local event id cannot be negative" }
        require(timestampUtc in 1..MAX_WIRE_INTEGER) { "Invalid Tempo event timestamp" }
        require(title.isNotBlank() && artist.isNotBlank()) { "Tempo event identity is incomplete" }
        val canonical = buildString {
            append("tempo-history-v1|")
            append(deviceId)
            append('|')
            append(localEventId)
            append('|')
            append(timestampUtc)
            append('|')
            append(title.trim().lowercase())
            append('|')
            append(artist.trim().lowercase())
        }
        return sha256(canonical)
    }

    fun encodeCompressed(batch: DriveHistoryBatch): ByteArray {
        validateBatch(batch)
        val json = JSONObject().apply {
            put("schema_version", batch.schemaVersion)
            put("batch_id", batch.batchId)
            put("source_device_id", batch.sourceDeviceId)
            put("source_device_name", batch.sourceDeviceName)
            put("source_platform", batch.sourcePlatform)
            put("created_at_utc", batch.createdAtUtc)
            put("events", JSONArray().apply {
                batch.events.forEach { put(eventToJson(it)) }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_DECOMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the decompressed size limit"
        }

        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(json) }
            output.toByteArray().also { compressed ->
                require(compressed.size <= MAX_COMPRESSED_BYTES) {
                    "Tempo Drive history batch exceeds the compressed size limit"
                }
            }
        }
    }

    fun decodeCompressed(bytes: ByteArray): DriveHistoryBatch {
        require(bytes.size <= MAX_COMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the compressed size limit"
        }

        val jsonBytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_DECOMPRESSED_BYTES) {
                        "Tempo Drive history batch expands beyond the safe size limit"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val schema = root.requireInt("schema_version", 0, Int.MAX_VALUE)
        require(schema == SCHEMA_VERSION) {
            "Unsupported Tempo history schema: $schema"
        }

        val batchId = root.requireString("batch_id", 64)
        val sourceDeviceId = root.requireString("source_device_id", 200)
        require(SHA256_PATTERN.matches(batchId) && isValidDeviceId(sourceDeviceId)) {
            "Malformed Tempo Drive history batch identity"
        }

        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        require(eventsJson.length() <= MAX_EVENTS_PER_BATCH) {
            "Tempo Drive history batch contains too many events"
        }
        val events = ArrayList<DriveHistoryEvent>(eventsJson.length())
        for (index in 0 until eventsJson.length()) {
            events.add(eventFromJson(eventsJson.getJSONObject(index)))
        }
        val batch = DriveHistoryBatch(
            schemaVersion = schema,
            batchId = batchId,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = root.requireString("source_device_name", MAX_PRIMARY_TEXT_LENGTH),
            sourcePlatform = root.requireString("source_platform", 100),
            createdAtUtc = root.requireLong("created_at_utc", 1L, MAX_WIRE_INTEGER),
            events = events
        )
        validateBatch(batch)
        return batch
    }

    private fun eventToJson(event: DriveHistoryEvent): JSONObject = JSONObject().apply {
        put("event_id", event.eventId)
        put("title", event.title)
        put("artist", event.artist)
        putNullable("album", event.album)
        put("timestamp_utc", event.timestampUtc)
        put("duration_ms", event.durationMs)
        put("listened_ms", event.listenedMs)
        put("source_app", event.sourceApp)
        put("source", event.source)
        put("skipped", event.skipped)
        put("replay_count", event.replayCount)
        put("completion_percentage", event.completionPercentage)
        put("pause_count", event.pauseCount)
        put("seek_count", event.seekCount)
        putNullable("session_id", event.sessionId)
        putNullable("site", event.site)
        put("content_type", event.contentType)
        putNullable("volume_level", event.volumeLevel)
        put("total_pause_duration_ms", event.totalPauseDurationMs)
        put("position_updates_count", event.positionUpdatesCount)
    }

    private fun eventFromJson(json: JSONObject): DriveHistoryEvent = DriveHistoryEvent(
        eventId = json.requireString("event_id", 64).also {
            require(SHA256_PATTERN.matches(it)) { "Malformed Tempo Drive event id" }
        },
        title = json.requireString("title", MAX_PRIMARY_TEXT_LENGTH),
        artist = json.requireString("artist", MAX_PRIMARY_TEXT_LENGTH),
        album = json.optNullableString("album"),
        timestampUtc = json.requireLong("timestamp_utc", 1L, MAX_WIRE_INTEGER),
        durationMs = json.requireLong("duration_ms", 0L, MAX_WIRE_INTEGER),
        listenedMs = json.requireLong("listened_ms", 0L, MAX_WIRE_INTEGER),
        sourceApp = json.requireString("source_app", MAX_PRIMARY_TEXT_LENGTH),
        source = json.requireString("source", MAX_PRIMARY_TEXT_LENGTH),
        skipped = json.requireBoolean("skipped"),
        replayCount = json.requireInt("replay_count", 0, Int.MAX_VALUE),
        completionPercentage = json.requireInt("completion_percentage", 0, 100),
        pauseCount = json.requireInt("pause_count", 0, Int.MAX_VALUE),
        seekCount = json.requireInt("seek_count", 0, Int.MAX_VALUE),
        sessionId = json.optNullableString("session_id"),
        site = json.optNullableString("site"),
        contentType = json.requireString("content_type", MAX_PRIMARY_TEXT_LENGTH),
        volumeLevel = if (json.has("volume_level") && json.isNull("volume_level")) {
            null
        } else {
            json.requireInt("volume_level", 0, 100)
        },
        totalPauseDurationMs = json.requireLong("total_pause_duration_ms", 0L, MAX_WIRE_INTEGER),
        positionUpdatesCount = json.requireInt("position_updates_count", 0, Int.MAX_VALUE)
    )

    private fun validateBatch(batch: DriveHistoryBatch) {
        require(batch.schemaVersion == SCHEMA_VERSION) { "Unsupported Tempo history schema" }
        require(SHA256_PATTERN.matches(batch.batchId)) { "Malformed Tempo Drive batch id" }
        require(isValidDeviceId(batch.sourceDeviceId)) { "Malformed Tempo Drive device id" }
        require(batch.sourceDeviceName.isNotBlank() && batch.sourceDeviceName.length <= MAX_PRIMARY_TEXT_LENGTH) {
            "Malformed Tempo Drive device name"
        }
        require(PLATFORM_PATTERN.matches(batch.sourcePlatform)) { "Malformed Tempo Drive source platform" }
        require(batch.createdAtUtc in 1..MAX_WIRE_INTEGER) { "Malformed Tempo Drive batch timestamp" }
        require(batch.events.isNotEmpty()) { "Tempo Drive history batch is empty" }
        require(batch.events.size <= MAX_EVENTS_PER_BATCH) { "Tempo Drive history batch contains too many events" }
        batch.events.forEach { event ->
            require(SHA256_PATTERN.matches(event.eventId)) { "Malformed Tempo Drive event id" }
            require(event.title.isNotBlank() && event.title.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo event title" }
            require(event.artist.isNotBlank() && event.artist.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo event artist" }
            require(event.timestampUtc in 1..MAX_WIRE_INTEGER) { "Malformed Tempo event timestamp" }
            require(event.durationMs in 0..MAX_WIRE_INTEGER && event.listenedMs in 0..MAX_WIRE_INTEGER) {
                "Malformed Tempo event duration"
            }
            require(event.sourceApp.isNotBlank() && event.sourceApp.length <= MAX_PRIMARY_TEXT_LENGTH &&
                event.source.isNotBlank() && event.source.length <= MAX_PRIMARY_TEXT_LENGTH
            ) { "Malformed Tempo event source" }
            require(event.album == null || event.album.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo event album" }
            require(event.replayCount >= 0 && event.completionPercentage in 0..100 &&
                event.pauseCount >= 0 && event.seekCount >= 0 && event.positionUpdatesCount >= 0
            ) { "Malformed Tempo event counters" }
            require(event.sessionId == null || event.sessionId.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo event session" }
            require(event.site == null || event.site.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo event site" }
            require(event.contentType.isNotBlank() && event.contentType.length <= MAX_PRIMARY_TEXT_LENGTH) {
                "Malformed Tempo event content type"
            }
            require(event.totalPauseDurationMs in 0..MAX_WIRE_INTEGER) { "Malformed Tempo event pause duration" }
            require(event.volumeLevel == null || event.volumeLevel in 0..100) { "Malformed Tempo event volume" }
        }
        require(createBatchId(batch.events) == batch.batchId) {
            "Tempo Drive history batch id does not match its events"
        }
    }

    fun isValidDeviceId(value: String): Boolean = DEVICE_ID_PATTERN.matches(value)

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        require(has(key)) { "Malformed Tempo field: $key" }
        if (isNull(key)) return null
        val value = get(key)
        require(value is String && value.length <= MAX_PRIMARY_TEXT_LENGTH) { "Malformed Tempo field: $key" }
        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.requireString(key: String, maxLength: Int): String {
        val value = get(key)
        require(value is String && value.isNotBlank() && value.length <= maxLength) {
            "Malformed Tempo field: $key"
        }
        return value
    }

    private fun JSONObject.requireLong(key: String, min: Long, max: Long): Long {
        val number = get(key)
        require(number is Number) { "Malformed Tempo field: $key" }
        val double = number.toDouble()
        val value = number.toLong()
        require(double.isFinite() && value.toDouble() == double && value in min..max) {
            "Malformed Tempo field: $key"
        }
        return value
    }

    private fun JSONObject.requireInt(key: String, min: Int, max: Int): Int =
        requireLong(key, min.toLong(), max.toLong()).toInt()

    private fun JSONObject.requireBoolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { "Malformed Tempo field: $key" }
        return value
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class DriveHistoryBatch(
    val schemaVersion: Int = DriveHistoryProtocol.SCHEMA_VERSION,
    val batchId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourcePlatform: String,
    val createdAtUtc: Long,
    val events: List<DriveHistoryEvent>
)

data class DriveHistoryEvent(
    val eventId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val timestampUtc: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val sourceApp: String,
    val source: String,
    val skipped: Boolean,
    val replayCount: Int,
    val completionPercentage: Int,
    val pauseCount: Int,
    val seekCount: Int,
    val sessionId: String?,
    val site: String?,
    val contentType: String,
    /** 0 means muted; any positive value means audible; null means unknown. */
    val volumeLevel: Int?,
    val totalPauseDurationMs: Long,
    val positionUpdatesCount: Int
)
