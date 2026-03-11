package me.avinas.tempo.desktop

import android.util.Log
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.TrackRepository
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes incoming desktop scrobble payloads.
 *
 * Responsibilities:
 *  1. Validate the auth token via [DesktopPairingManager]
 *  2. Parse the JSON `scrobbles` array
 *  3. Upsert [Track] records (find-or-create by title + artist)
 *  4. Deduplicate against existing [ListeningEvent] rows (±60 s window)
 *  5. Insert accepted events with `source = "desktop:<source_app>"`
 *  6. Update the pairing session's `last_seen_ms`
 *
 * Expected payload schema (mirrors [desktopplan.md]):
 * ```json
 * {
 *   "auth_token": "...",
 *   "device_name": "Avinash-MacBook-Pro",
 *   "scrobbles": [
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
class DesktopScrobbleIngestionService @Inject constructor(
    private val pairingManager: DesktopPairingManager,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository
) {
    companion object {
        private const val TAG = "DesktopIngestion"

        /** Deduplication window: skip if same track exists within ±60 s. */
        private const val DEDUP_WINDOW_MS = 60_000L

        /** Minimum sensible play duration to accept (5 seconds). */
        private const val MIN_PLAY_DURATION_MS = 5_000L

        /** Assumed completion percentage for desktop scrobbles (90 %, analogous to Last.fm's
         *  50 % threshold but stricter, since desktop clients only scrobble finished tracks). */
        private const val DESKTOP_COMPLETION_PERCENT = 90
    }

    /**
     * Validate + parse + store one scrobble batch.
     * This function is safe to call from any coroutine (IO dispatcher recommended).
     */
    suspend fun ingest(token: String, payload: JSONObject): IngestionResult {
        // 1. Authenticate
        val session = pairingManager.validateToken(token)
            ?: return IngestionResult.InvalidToken

        // 2. Parse metadata
        val deviceName = payload.optString("device_name", session.deviceName)
        val scrobblesArray = try {
            payload.getJSONArray("scrobbles")
        } catch (e: Exception) {
            Log.w(TAG, "Payload missing 'scrobbles' array")
            return IngestionResult.Error("missing_scrobbles_array")
        }

        var accepted = 0
        var duplicates = 0

        for (i in 0 until scrobblesArray.length()) {
            val entry = scrobblesArray.optJSONObject(i) ?: continue

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
                val trackId = findOrCreateTrack(title, artist, album, durationMs)

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
                Log.e(TAG, "Error processing scrobble [$title / $artist]", e)
                // Continue with remaining entries rather than aborting the whole batch
            }
        }

        // 6. Update pairing session last-seen
        pairingManager.recordSuccessfulSync(
            token = token,
            deviceName = deviceName.ifBlank { session.deviceName }
        )

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
    ): Long {
        // Exact match first
        trackRepository.findByTitleAndArtist(title, artist)?.let { return it.id }
        // Fuzzy match (handles minor whitespace/case differences)
        trackRepository.findByTitleAndArtistFuzzy(title, artist)?.let { return it.id }
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
        return trackRepository.insert(newTrack)
    }

    private suspend fun checkDuplicate(trackId: Long, timestampUtc: Long): Boolean {
        val windowStart = timestampUtc - DEDUP_WINDOW_MS
        val windowEnd = timestampUtc + DEDUP_WINDOW_MS
        val existing = listeningRepository.getEventsInRange(windowStart, windowEnd)
        return existing.any { it.track_id == trackId }
    }
}
