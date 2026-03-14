package me.avinas.tempo.desktop

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.utils.BatteryUtils
import me.avinas.tempo.worker.EnrichmentWorker
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes incoming desktop play payloads.
 *
 * Responsibilities:
 *  1. Validate the auth token via [DesktopPairingManager]
 *  2. Check battery level (reject if critical)
 *  3. Parse the JSON `plays` array
 *  4. Upsert [Track] records (find-or-create by title + artist)
 *  5. Deduplicate against existing [ListeningEvent] rows (±60 s window)
 *  6. Insert accepted events with `source = "desktop:<source_app>"`
 *  7. Update the pairing session's `last_seen_ms`
 *
 * Battery Optimization:
 * - Plays are rejected if battery level is ≤ 20% (critical)
 * - Desktop app is notified via special error code to avoid syncing at low battery
 *
 * Expected payload schema (mirrors [desktopplan.md]):
 * ```json
 * {
 *   "auth_token": "...",
 *   "device_name": "Avinash-MacBook-Pro",
 *   "plays": [
 *     {
 *       "title": "Starboy",
 *       "artist": "The Weeknd",
 *       "album": "Starboy",
 *       "timestamp_utc": 1698246000000,
 *       "duration_ms": 230000,
 *       "source_app": "Spotify Desktop"
 *     }
 *   ]
 * }
 * ```
 */
@Singleton
class DesktopPlayIngestionService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pairingManager: DesktopPairingManager,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository
) {
    companion object {
        private const val TAG = "DesktopIngestion"

        /** Deduplication window: skip if same track exists within ±60 s. */
        private const val DEDUP_WINDOW_MS = 60_000L

        /** Minimum sensible play duration to accept (5 seconds). */
        private const val MIN_PLAY_DURATION_MS = 5_000L

        /** Assumed completion percentage for desktop plays (90 %, stricter than Last.fm's
         *  50 % threshold, since desktop clients only log finished tracks). */
        private const val DESKTOP_COMPLETION_PERCENT = 90
    }

    /**
     * Validate + parse + store one play batch.
     * This function is safe to call from any coroutine (IO dispatcher recommended).
     * 
     * Returns special error codes:
     * - "battery_critical" if battery is ≤ 20% (desktop should retry later)
     */
    suspend fun ingest(token: String, payload: JSONObject): IngestionResult {
        // 1. Authenticate
        val session = pairingManager.validateToken(token)
            ?: return IngestionResult.InvalidToken

        // 1.5 Battery check: reject if battery is critically low (≤ 20%)
        if (BatteryUtils.isCriticalBattery(context, forceRefresh = true)) {
            Log.w(TAG, "Rejecting play ingestion: battery level is critical (≤ 20%)")
            return IngestionResult.Error("battery_critical")
        }

        // 2. Parse metadata
        val deviceName = payload.optString("device_name", session.deviceName)
        val playsArray = try {
            payload.getJSONArray("plays")
        } catch (e: Exception) {
            Log.w(TAG, "Payload missing 'plays' array")
            return IngestionResult.Error("missing_plays_array")
        }

        var accepted = 0
        var duplicates = 0
        var hasNewTracks = false

        for (i in 0 until playsArray.length()) {
            val entry = playsArray.optJSONObject(i) ?: continue

            val title = entry.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
            val artist = entry.optString("artist").trim().takeIf { it.isNotBlank() } ?: continue
            val album = entry.optString("album").takeIf { it.isNotBlank() }
            val timestampUtc = entry.optLong("timestamp_utc", 0L).takeIf { it > 0L } ?: continue
            val durationMs = entry.optLong("duration_ms", 0L)
            val sourceApp = entry.optString("source_app", "Desktop").trim()

            // Guard: ignore implausibly short plays
            if (durationMs in 1 until MIN_PLAY_DURATION_MS) {
                Log.d(TAG, "Skipping short play ($durationMs ms): $title")
                continue
            }

            try {
                // 3. Find or create the Track record
                val trackResolution = findOrCreateTrack(title, artist, album, durationMs)
                val trackId = trackResolution.id
                if (trackResolution.isNew) {
                    enrichedMetadataRepository.createPendingIfNotExists(trackId)
                    hasNewTracks = true
                }

                // 4. Deduplication: skip if an event for this track exists within ±60 s
                val isDuplicate = checkDuplicate(trackId, timestampUtc)
                if (isDuplicate) {
                    Log.d(TAG, "Duplicate skipped: $title @ $timestampUtc")
                    duplicates++
                    continue
                }

                // 5. Insert the ListeningEvent
                val event = ListeningEvent(
                    track_id = trackId,
                    timestamp = timestampUtc,
                    playDuration = durationMs.coerceAtLeast(0L),
                    completionPercentage = DESKTOP_COMPLETION_PERCENT,
                    source = "desktop:$sourceApp",
                    wasSkipped = false,
                    isReplay = false,
                    estimatedDurationMs = durationMs.takeIf { it > 0L }
                )
                listeningRepository.insert(event)
                accepted++
                Log.d(TAG, "Accepted: $title by $artist @ $timestampUtc")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing play [$title / $artist]", e)
                // Continue with remaining entries rather than aborting the whole batch
            }
        }

        // 6. Update pairing session last-seen
        pairingManager.recordSuccessfulSync(
            token = token,
            deviceName = deviceName.ifBlank { session.deviceName }
        )

        if (hasNewTracks) {
            // Queue one immediate run to process all newly created desktop tracks.
            EnrichmentWorker.enqueueImmediate(context)
        }

        Log.i(TAG, "Ingestion complete: $accepted accepted, $duplicates duplicates (device: $deviceName)")
        return IngestionResult.Success(accepted, duplicates)
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private suspend fun findOrCreateTrack(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): TrackResolution {
        // Exact match first
        trackRepository.findByTitleAndArtist(title, artist)?.let {
            return TrackResolution(id = it.id, isNew = false)
        }
        // Fuzzy match (handles minor whitespace/case differences)
        trackRepository.findByTitleAndArtistFuzzy(title, artist)?.let {
            return TrackResolution(id = it.id, isNew = false)
        }
        // Create new track record
        val newTrack = Track(
            title = title,
            artist = artist,
            album = album,
            duration = durationMs.takeIf { it > 0L },
            albumArtUrl = null,
            spotifyId = null,
            musicbrainzId = null,
            contentType = "MUSIC"
        )
        return TrackResolution(id = trackRepository.insert(newTrack), isNew = true)
    }

    private data class TrackResolution(
        val id: Long,
        val isNew: Boolean
    )

    private suspend fun checkDuplicate(trackId: Long, timestampUtc: Long): Boolean {
        val windowStart = timestampUtc - DEDUP_WINDOW_MS
        val windowEnd = timestampUtc + DEDUP_WINDOW_MS
        val existing = listeningRepository.getEventsInRange(windowStart, windowEnd)
        return existing.any { it.track_id == trackId }
    }
}
