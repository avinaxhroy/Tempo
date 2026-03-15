package me.avinas.tempo.desktop

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.utils.ArtistParser
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
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val artistLinkingService: ArtistLinkingService
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
        val newTrackIds = mutableListOf<Long>()

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
                    // Link artists (creates Artist records and TrackArtist relationships)
                    if (!ArtistParser.isUnknownArtist(artist)) {
                        try {
                            artistLinkingService.linkArtistsForTrack(
                                trackResolution.track ?: Track(
                                    id = trackId,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    duration = durationMs.takeIf { it > 0L },
                                    albumArtUrl = null,
                                    spotifyId = null,
                                    musicbrainzId = null,
                                    contentType = "MUSIC"
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to link artists for track $trackId", e)
                        }
                    }
                    enrichedMetadataRepository.createPendingIfNotExists(trackId)
                    newTrackIds.add(trackId)
                } else {
                    // Existing track: if album art is still missing (enrichment never succeeded
                    // or failed previously) re-queue it so it gets another attempt.
                    // This is the common case when a desktop sync resends a track that was
                    // ingested before but whose EnrichmentWorker run produced no art.
                    val existingMeta = enrichedMetadataRepository.forTrackSync(trackId)
                    if (existingMeta == null || existingMeta.albumArtUrl.isNullOrBlank()) {
                        if (existingMeta == null) {
                            enrichedMetadataRepository.createPendingIfNotExists(trackId)
                        } else {
                            // Reset status to PENDING so the worker picks it up again
                            enrichedMetadataRepository.markForReEnrichment(trackId)
                        }
                        if (!newTrackIds.contains(trackId)) {
                            newTrackIds.add(trackId)
                        }
                    }
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

        // Enqueue per-track immediate enrichment for each new track, identical to how
        // MusicTrackingService handles real-time plays.  Using per-track IDs (rather than
        // a single batch call) ensures the tracks are not lost at the bottom of the
        // play-count-ordered PENDING queue and cannot be displaced by concurrent calls.
        for (trackId in newTrackIds) {
            EnrichmentWorker.enqueueImmediate(context, trackId)
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
        // 1. Exact case-insensitive match
        trackRepository.findByTitleAndArtist(title, artist)?.let {
            return TrackResolution(id = it.id, isNew = false)
        }
        // 2. Fuzzy substring match (handles minor whitespace / case differences)
        trackRepository.findByTitleAndArtistFuzzy(title, artist)?.let {
            return TrackResolution(id = it.id, isNew = false)
        }
        // 3. Any-artist intersection match:
        //    Fetch every track with the same title, then check whether at least one
        //    individual artist name from the incoming play exists in the stored artist
        //    string (or vice-versa).  This handles:
        //      - Desktop sends only the primary artist ("Farhan Khan") but phone stores
        //        the full list ("Farhan Khan, Mujtaba Aziz Naza, Mr. Doss").
        //      - Different separators: "A & B" vs "A, B", "A feat. B" vs "A ft. B", etc.
        val candidates = trackRepository.findCandidatesByTitle(title)
        candidates.firstOrNull { artistsOverlap(it.artist, artist) }?.let {
            return TrackResolution(id = it.id, isNew = false)
        }
        // 4. No match found — create a new track record
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
        val id = trackRepository.insert(newTrack)
        return TrackResolution(id = id, isNew = true, track = newTrack.copy(id = id))
    }

    private data class TrackResolution(
        val id: Long,
        val isNew: Boolean,
        val track: Track? = null
    )

    /**
     * Returns true if the two artist strings share at least one individual artist name.
     *
     * Both strings are split on common separators (comma, ampersand, slash, featuring
     * keywords, "x", "and", "vs").  Each token is trimmed and lowercased before the
     * intersection check, so formatting differences ("feat." vs "ft.", " & " vs ", ")
     * and partial-artist sends ("Farhan Khan" vs "Farhan Khan, Mujtaba Aziz Naza, …")
     * are all handled transparently.
     */
    private fun artistsOverlap(storedArtist: String, incomingArtist: String): Boolean {
        val separator = Regex(
            """\s*[,/]\s*|\s+&\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+with\s+|\s+x\s+|\s+and\s+|\s+vs\.?\s+""",
            RegexOption.IGNORE_CASE
        )
        fun tokens(s: String) = separator.split(s)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val stored   = tokens(storedArtist)
        val incoming = tokens(incomingArtist)
        return stored.any { it in incoming }
    }

    private suspend fun checkDuplicate(trackId: Long, timestampUtc: Long): Boolean {
        val windowStart = timestampUtc - DEDUP_WINDOW_MS
        val windowEnd = timestampUtc + DEDUP_WINDOW_MS
        val existing = listeningRepository.getEventsInRange(windowStart, windowEnd)
        return existing.any { it.track_id == trackId }
    }
}
