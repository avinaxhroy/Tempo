package me.avinas.tempo.data.drive

import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.TrackResolver

/** Maps between Tempo listening rows and the immutable Drive history wire format. */
internal const val DRIVE_IMPORT_FINGERPRINT_PREFIX = "drive:v1:"

internal class DriveHistoryEventMapper(
    private val database: AppDatabase,
    private val trackResolver: TrackResolver,
    private val deviceId: String,
) {
    suspend fun localEventToProtocol(
        event: ListeningEvent,
        persistedOriginId: String?
    ): DriveHistoryEvent? {
        val track = database.trackDao().getTrackById(event.track_id) ?: return null
        val title = DriveHistoryProtocol.truncateText(track.title.trim()).takeIf { it.isNotBlank() } ?: return null
        val artist = DriveHistoryProtocol.truncateText(track.artist.trim()).takeIf { it.isNotBlank() } ?: return null
        if (event.timestamp !in 1..DriveHistoryProtocol.MAX_WIRE_INTEGER) return null
        val durationMs = (event.estimatedDurationMs ?: track.duration ?: event.playDuration)
            .coerceAtLeast(event.playDuration)
            .coerceAtLeast(0L)
            .coerceAtMost(DriveHistoryProtocol.MAX_WIRE_INTEGER)
        // A LAN import carries the precise original event ID and music source.
        // Publish it through this Android account only if the sender has not
        // used Drive itself; the remote clients will deduplicate using event_id.
        // A *cloud* import never enters this export path.
        val lanRelay = event.source.startsWith("lan:")
        val origin = if (lanRelay) {
            DriveHistorySyncManager.lanRelayIdentity(event.source, event.contentFingerprint) ?: return null
        } else null
        val originalSource = origin?.first ?: event.source
        val sourceApp = originalSource
            .removePrefix("desktop:")
            .removePrefix("browser:")
            .ifBlank { "android" }
            .let { DriveHistoryProtocol.truncateText(it) }
        val source = DriveHistoryProtocol.truncateText(originalSource.ifBlank { "android" })
        val originalEventId = origin?.second

        return DriveHistoryEvent(
            eventId = originalEventId ?: DriveHistorySyncManager.stableLocalEventId(
                existingOriginId = persistedOriginId,
                deviceId = deviceId,
                localEventId = event.id,
                timestampUtc = event.timestamp,
                title = title,
                artist = artist
            ),
            title = title,
            artist = artist,
            album = track.album?.trim()?.let { DriveHistoryProtocol.truncateText(it) }?.takeIf { it.isNotBlank() },
            timestampUtc = event.timestamp,
            durationMs = durationMs,
            listenedMs = event.playDuration.coerceIn(0L, DriveHistoryProtocol.MAX_WIRE_INTEGER),
            sourceApp = sourceApp,
            source = source,
            skipped = event.wasSkipped,
            replayCount = if (event.isReplay) 1 else 0,
            completionPercentage = event.completionPercentage.coerceIn(0, 100),
            pauseCount = event.pauseCount.coerceAtLeast(0),
            seekCount = event.seekCount.coerceAtLeast(0),
            sessionId = event.sessionId?.let { DriveHistoryProtocol.truncateText(it) },
            site = null,
            contentType = DriveHistoryProtocol.truncateText(track.contentType.ifBlank { "MUSIC" }),
            volumeLevel = DriveHistorySyncManager.protocolVolumeLevel(event.volumeLevel),
            totalPauseDurationMs = event.totalPauseDurationMs
                .coerceIn(0L, DriveHistoryProtocol.MAX_WIRE_INTEGER),
            positionUpdatesCount = event.positionUpdatesCount.coerceAtLeast(0)
        )
    }


    suspend fun protocolEventToLocal(
        event: DriveHistoryEvent,
        sourceDeviceId: String
    ): ListeningEvent? {
        if (event.timestampUtc <= 0L || event.title.isBlank() || event.artist.isBlank()) return null

        val resolution = trackResolver.resolve(
            TrackResolver.Query(
                title = event.title.trim(),
                artist = event.artist.trim(),
                album = event.album?.trim()?.takeIf { it.isNotBlank() },
                duration = event.durationMs.takeIf { it > 0L }
            ),
            contentType = event.contentType.ifBlank { "MUSIC" }
        )

        val listenedMs = event.listenedMs.coerceAtLeast(0L)
        return ListeningEvent(
            track_id = resolution.trackId,
            timestamp = event.timestampUtc,
            playDuration = listenedMs,
            completionPercentage = event.completionPercentage.coerceIn(0, 100),
            source = "drive:$sourceDeviceId:${event.source.ifBlank { event.sourceApp }}",
            wasSkipped = event.skipped,
            isReplay = event.replayCount > 0,
            estimatedDurationMs = event.durationMs.takeIf { it > 0L },
            pauseCount = event.pauseCount.coerceAtLeast(0),
            sessionId = event.sessionId,
            endTimestamp = if (listenedMs > 0L) event.timestampUtc + listenedMs else null,
            totalPauseDurationMs = event.totalPauseDurationMs.coerceAtLeast(0L),
            seekCount = event.seekCount.coerceAtLeast(0),
            positionUpdatesCount = event.positionUpdatesCount.coerceAtLeast(0),
            wasInterrupted = event.skipped,
            volumeLevel = event.volumeLevel?.takeIf { it == 0 },
            contentFingerprint = "$DRIVE_IMPORT_FINGERPRINT_PREFIX${event.eventId}"
        )
    }


}
