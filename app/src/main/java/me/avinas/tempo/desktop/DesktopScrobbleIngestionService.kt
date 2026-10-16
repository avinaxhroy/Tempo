package me.avinas.tempo.desktop

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.RefreshCoordinator
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
    private val listeningEventDao: ListeningEventDao,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val refreshCoordinator: RefreshCoordinator
) {
    companion object {
        private const val TAG = "DesktopIngestion"

        /** Deduplication window: skip if same track exists within ±5 min. */
        private const val DEDUP_WINDOW_MS = 10_000L
        private val DRIVE_DEVICE_ID = Regex("^[A-Za-z0-9._-]{1,200}$")
        private val DRIVE_EVENT_ID = Regex("^[0-9a-f]{64}$")


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

        // 1.6 Storage check: reject if available storage is critically low (< 100MB)
        val availableBytes = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.w(TAG, "Could not check available storage", e)
            Long.MAX_VALUE // Assume enough space if we can't check
        }
        if (availableBytes < 100 * 1024 * 1024) { // < 100MB
            Log.w(TAG, "Rejecting play ingestion: low storage (${availableBytes / (1024 * 1024)}MB available)")
            return IngestionResult.Error("low_storage")
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
        val newTrackIds = mutableSetOf<Long>()

        // Reject malformed batches instead of acknowledging plays we silently
        // discarded. LAN senders remove whole batches on a successful response,
        // so a missing title/artist/timestamp would otherwise be lost forever.
        for (i in 0 until playsArray.length()) {
            val entry = playsArray.optJSONObject(i)
                ?: return IngestionResult.Error("invalid_play_at_index_$i")
            if (entry.optString("title").isBlank() ||
                entry.optString("artist").isBlank() ||
                entry.optLong("timestamp_utc", 0L) <= 0L
            ) {
                return IngestionResult.Error("invalid_play_at_index_$i")
            }
        }

        for (i in 0 until playsArray.length()) {
            val entry = playsArray.getJSONObject(i)

            val title = entry.getString("title").trim()
            val artist = entry.getString("artist").trim()
            val album = entry.optString("album").takeIf { it.isNotBlank() }
            val timestampUtc = entry.getLong("timestamp_utc")
            val durationMs = entry.optLong("duration_ms", 0L)
            // listened_ms is sent by the browser extension and represents actual listened time.
            // Fall back to duration_ms (full track duration) for desktop app plays that
            // don't send this field.
            val listenedMs = entry.optLong("listened_ms", 0L).takeIf { it > 0L } ?: durationMs
            val sourceApp = entry.optString("source_app", "Desktop").trim()
            val declaredDeviceId = entry.optString("origin_device_id", "")
            val declaredEventId = entry.optString("origin_event_id", "")
            // An authenticated pairing proves the sender device, not ownership
            // of a Google account. Only explicitly supplied provenance can
            // authorise a future cloud relay. Missing/invalid stays LAN-only.
            val senderAccount = entry.optString("origin_account_subject", "").trim()
                .takeIf { it.isNotBlank() && it.length <= 255 &&
                    !it.contains('@') && it != "legacy-unverified" }
            // Older LAN senders omit these fields, so keep their existing
            // heuristic path. New senders reuse their exact Google Drive event
            // identity, eliminating LAN -> Android -> Drive publication loops.
            val stableOrigin = if (
                DRIVE_DEVICE_ID.matches(declaredDeviceId) &&
                DRIVE_EVENT_ID.matches(declaredEventId)
            ) declaredDeviceId to declaredEventId else null

            // The LAN transport preserves the sender's history as-is.
            // Duration filtering is a separate user preference: silently
            // skipping short plays would make a successful batch incomplete.

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
                        newTrackIds.add(trackId)
                    }
                }

                // 4. Deduplication: skip if an event for this track exists within ±60 s
                val isDuplicate = stableOrigin == null && checkDuplicate(trackId, timestampUtc)
                if (isDuplicate) {
                    Log.d(TAG, "Duplicate skipped: $title @ $timestampUtc")
                    duplicates++
                    continue
                }

                // 5. Insert the ListeningEvent
                // Use listenedMs as playDuration — this is the actual listened time sent by the
                // browser extension. For desktop app plays that don't send listened_ms, this
                // falls back to the full track duration_ms.
                val completionPct = if (durationMs > 0L) {
                    ((listenedMs.toDouble() / durationMs) * 100).toInt().coerceIn(0, 100)
                } else {
                    DESKTOP_COMPLETION_PERCENT
                }
                val originSource = entry.optString("origin_source", "desktop:$sourceApp")
                val event = ListeningEvent(
                    track_id = trackId,
                    timestamp = timestampUtc,
                    playDuration = listenedMs.coerceAtLeast(0L),
                    completionPercentage = completionPct,
                    // A LAN-delivered play is not necessarily present on Drive:
                    // the sender may have disabled Google sync. Keep its exact
                    // producer/event ID, but distinguish LAN from cloud imports
                    // so Android can relay this play without generating a new ID.
                    source = stableOrigin?.let { "lan:${it.first}:$originSource" } ?: "desktop:$sourceApp",
                    wasSkipped = completionPct < 30,
                    isReplay = false,
                    estimatedDurationMs = durationMs.takeIf { it > 0L },
                    contentFingerprint = stableOrigin?.let { "drive:v1:${it.second}" },
                    driveAccountSubject = if (stableOrigin == null) "lan-unverified"
                        else senderAccount ?: "lan-unverified"
                )
                if (stableOrigin == null) {
                    listeningRepository.insert(event)
                    accepted++
                } else {
                    val result = listeningEventDao.insertAllBatchedWithDedup(
                        listOf(event), event.driveAccountSubject
                    )
                    accepted += result.inserted
                    duplicates += result.skipped
                }
                Log.d(TAG, "Accepted: $title by $artist @ $timestampUtc")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not durably ingest LAN play [$title / $artist] at index $i", e)
                // The sender acknowledges and clears the entire LAN batch on
                // success. Return a non-2xx error instead; already committed
                // events are safe to retry because of their stable origin IDs.
                // Never rotate the pairing token on a failed batch.
                return IngestionResult.Error("ingestion_failed_retry_batch")
            }
        }

        // Never rotate the pairing token or return ok:true when the whole
        // request was not accounted for. Desktop now checks these counts.
        // Already committed events are safe to resend by stable event ID.
        if (accepted.toLong() + duplicates.toLong() != playsArray.length().toLong()) {
            return IngestionResult.Error("ingestion_failed_retry_batch")
        }

        // 6. Update pairing session last-seen
        pairingManager.recordSuccessfulSync(
            token = token,
            deviceName = deviceName.ifBlank { session.deviceName }
        )

        // 7. Rotate auth token for forward secrecy (client will use the new token next time)
        val rotatedToken = pairingManager.rotateToken(token)
        val rotatedTokenStr = rotatedToken ?: token

        // Enqueue per-track immediate enrichment for each new track, identical to how
        // MusicTrackingService handles real-time plays. Using per-track IDs avoids
        // displacing or deprioritizing tracks in the pending enrichment queue.
        for (trackId in newTrackIds) {
            EnrichmentWorker.enqueueImmediate(context, trackId)
        }

        // 8. Notify the History screen (and other observers) that new plays were added.
        // Without this, the History ViewModel only reacts through the Room-backed
        // observeListeningOverview() Flow, which may not fire while the screen is
        // in the background or on a non-zero page. This mirrors what MusicTrackingService
        // does after recording a live play.
        if (accepted > 0) {
            refreshCoordinator.notifyNewTrackRecorded()
        }

        Log.i(TAG, "Ingestion complete: $accepted accepted, $duplicates duplicates (device: $deviceName)")
        return IngestionResult.Success(accepted, duplicates, nextToken = rotatedTokenStr)
    }

    // Helpers

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
        candidates.firstOrNull { ArtistParser.hasAnyMatchingArtist(it.artist, artist) }?.let {
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
