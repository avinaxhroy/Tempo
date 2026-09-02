package me.avinas.tempo.data.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.TrackResolver
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional cross-device history sync through Google Drive appDataFolder.
 *
 * Local -> Drive uses an id cursor over Room rows and immutable compressed batch
 * files. Drive -> Local resolves tracks through Tempo's normal TrackResolver and
 * inserts listening events through the source-aware dedup pipeline.
 */
@Singleton
class DriveHistorySyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val trackResolver: TrackResolver,
    private val appDataClient: DriveAppDataClient,
    private val authManager: GoogleAuthManager,
    private val settingsManager: DriveHistorySyncSettingsManager
) {
    companion object {
        private const val TAG = "DriveHistorySync"
        private const val STATE_PREFS = "drive_history_sync_state"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_UPLOAD_CURSOR = "upload_cursor"
        private const val KEY_DOWNLOAD_CREATED_CURSOR = "download_created_cursor"
        private const val KEY_ACCEPTED_DISABLE_VERSION = "accepted_disable_marker_version"
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        private const val PAGE_SIZE = 200
        private const val BATCH_SIZE = 50
        private const val DOWNLOAD_OVERLAP_MS = 24L * 60L * 60L * 1000L
        private const val IMPORT_FINGERPRINT_PREFIX = "drive:v1:"

        /**
         * Android stores the device-specific audio stream index, not a portable
         * percentage. Preserve a known mute as 0 and report non-zero indices as
         * unknown instead of misrepresenting (for example) level 8/15 as 8%.
         */
        internal fun protocolVolumeLevel(androidStreamIndex: Int?): Int? = when (androidStreamIndex) {
            0 -> 0
            else -> null
        }
    }

    private val mutex = Mutex()
    private val statePrefs by lazy {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    }

    val deviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val existing = statePrefs.getString(KEY_DEVICE_ID, null)
        if (existing != null && DriveHistoryProtocol.isValidDeviceId(existing)) return@lazy existing
        val generated = UUID.randomUUID().toString()
        check(statePrefs.edit().putString(KEY_DEVICE_ID, generated).commit()) {
            "Could not persist Tempo's Drive device identity"
        }
        generated
    }

    val deviceName: String
        get() = "Tempo Android"

    /**
     * Explicit user opt-in. A shared deletion marker is acknowledged only here,
     * never silently by a background worker. If cloud history was deleted while
     * this device was disabled/offline, reset cursors so the user's explicit
     * re-enable can intentionally seed Drive again from locally-owned history.
     */
    suspend fun enableSync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) return@withContext false
            reconcileGoogleAccountBoundary()
            val currentMarker = appDataClient.getHistoryDisableMarkerVersion()
            val acceptedMarker = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L)
            if (currentMarker > acceptedMarker) {
                resetCursorsLocked()
            }
            statePrefs.edit().putLong(KEY_ACCEPTED_DISABLE_VERSION, currentMarker).apply()
            settingsManager.setEnabled(true)
            true
        }
    }

    suspend fun disableSync() = mutex.withLock {
        settingsManager.setEnabled(false)
    }

    suspend fun syncNow(): DriveHistorySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsManager.settings.first()
            if (!settings.enabled) {
                return@withContext DriveHistorySyncResult.Disabled
            }

            settingsManager.markRunning()
            try {
                if (!ensureAuthorized()) {
                    val message = "Google Drive authorization is required"
                    settingsManager.markFailure(message)
                    return@withContext DriveHistorySyncResult.Error(message)
                }

                if (reconcileGoogleAccountBoundary()) {
                    val message =
                        "Google account changed. Cross-device sync was turned off; enable it again to use the new Drive account."
                    settingsManager.setEnabled(false)
                    settingsManager.markFailure(message)
                    return@withContext DriveHistorySyncResult.RemoteDisabled(message)
                }

                val remoteDisable = handleRemoteDisableIfNeeded()
                if (remoteDisable != null) return@withContext remoteDisable

                val uploaded = uploadLocalHistory()
                val download = downloadRemoteHistory()
                settingsManager.markSuccess(
                    uploaded = uploaded,
                    imported = download.inserted,
                    message = if (download.skipped > 0) {
                        "${download.skipped} duplicate event(s) ignored"
                    } else null
                )
                DriveHistorySyncResult.Success(
                    uploaded = uploaded,
                    imported = download.inserted,
                    duplicates = download.skipped,
                    replaced = download.replaced
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    settingsManager.markFailure("Cross-device history sync was cancelled")
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "History sync failed", e)
                val message = e.message ?: "Cross-device history sync failed"
                settingsManager.markFailure(message)
                DriveHistorySyncResult.Error(message, e)
            }
        }
    }

    private suspend fun ensureAuthorized(): Boolean {
        if (!authManager.isSignedIn.value && !authManager.restoreSessionSilently()) {
            return false
        }
        return authManager.getAccessToken() != null
    }

    /**
     * Keep Drive cursors scoped to one Google account. The account identity is
     * intentionally stored in the sync state (not in auth token storage), so it
     * survives Google sign-out long enough to detect a later account switch.
     *
     * Returns true only when a previously-known account changed. Cursors and the
     * accepted deletion marker are reset before any operation against the new
     * account. The caller decides whether that account change is an explicit
     * opt-in (enableSync) or must stop a background/manual sync (syncNow).
     */
    private fun reconcileGoogleAccountBoundary(): Boolean {
        val current = authManager.currentAccount.value?.email
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val previous = statePrefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (previous == current) return false

        val changed = previous != null
        val editor = statePrefs.edit()
            .putString(KEY_GOOGLE_ACCOUNT_EMAIL, current)
        if (changed) {
            editor
                .remove(KEY_UPLOAD_CURSOR)
                .remove(KEY_DOWNLOAD_CREATED_CURSOR)
                .remove(KEY_ACCEPTED_DISABLE_VERSION)
        }
        editor.apply()
        return changed
    }

    /**
     * If another linked device bumped the shared deletion marker after this
     * device last explicitly enabled Drive sync, honor that deletion before any
     * upload. Only generations older than the marker are removed: a different
     * device may already have explicitly re-enabled sync and started generation N,
     * and this stale device must never erase that newly-seeded generation.
     */
    private suspend fun handleRemoteDisableIfNeeded(): DriveHistorySyncResult.RemoteDisabled? {
        val currentMarker = appDataClient.getHistoryDisableMarkerVersion()
        val acceptedMarker = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L)
        if (currentMarker <= acceptedMarker) return null

        appDataClient.deleteHistoryBatchesBeforeGeneration(currentMarker)
        statePrefs.edit().putLong(KEY_ACCEPTED_DISABLE_VERSION, currentMarker).apply()
        settingsManager.setEnabled(false)
        resetCursorsLocked()
        return DriveHistorySyncResult.RemoteDisabled(
            "Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history."
        )
    }

    /**
     * Uploads locally-owned Room events in id order. Imported Drive events are
     * skipped so another device's event can never bounce back into Drive as a new
     * event. The cursor advances only after every eligible event in a page has
     * been safely uploaded.
     */
    private suspend fun uploadLocalHistory(): Int {
        val dao = database.listeningEventDao()
        val maxId = dao.getMaxEventId()
        val storedCursor = statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L)
        val generation = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        // A database restore can move Room row ids backwards while SharedPreferences
        // survive. If the persisted cursor is now beyond the database snapshot,
        // keeping it would make every newly-created row look already scanned until
        // ids eventually caught up. Restart from zero instead; deterministic Drive
        // event/batch ids make replay safe and preferable to silently losing plays.
        var afterId = if (storedCursor > maxId) {
            statePrefs.edit().remove(KEY_UPLOAD_CURSOR).apply()
            0L
        } else {
            storedCursor
        }
        var uploaded = 0

        while (afterId < maxId) {
            val page = dao.getEventsPage(afterId, maxId, PAGE_SIZE)
            if (page.isEmpty()) break

            val protocolEvents = mutableListOf<DriveHistoryEvent>()
            for (event in page) {
                if (event.contentFingerprint?.startsWith(IMPORT_FINGERPRINT_PREFIX) == true) continue
                localEventToProtocol(event)?.let(protocolEvents::add)
            }

            for (events in protocolEvents.chunked(BATCH_SIZE)) {
                if (events.isEmpty()) continue
                val batchId = DriveHistoryProtocol.createBatchId(events)
                val batch = DriveHistoryBatch(
                    batchId = batchId,
                    sourceDeviceId = deviceId,
                    sourceDeviceName = deviceName,
                    sourcePlatform = "android",
                    createdAtUtc = System.currentTimeMillis(),
                    events = events
                )
                val bytes = DriveHistoryProtocol.encodeCompressed(batch)
                appDataClient.uploadHistoryBatch(
                    fileName = DriveHistoryProtocol.fileName(deviceId, batchId, generation),
                    compressedBytes = bytes,
                    appProperties = mapOf(
                        DriveHistoryProtocol.APP_PROPERTY_KIND to DriveHistoryProtocol.KIND_HISTORY_BATCH,
                        DriveHistoryProtocol.APP_PROPERTY_SCHEMA to DriveHistoryProtocol.SCHEMA_VERSION.toString(),
                        DriveHistoryProtocol.APP_PROPERTY_DEVICE_ID to deviceId,
                        DriveHistoryProtocol.APP_PROPERTY_PLATFORM to "android",
                        DriveHistoryProtocol.APP_PROPERTY_GENERATION to generation.toString()
                    )
                )
                uploaded += events.size
            }

            afterId = page.last().id
            statePrefs.edit().putLong(KEY_UPLOAD_CURSOR, afterId).apply()
        }

        return uploaded
    }

    private suspend fun localEventToProtocol(event: ListeningEvent): DriveHistoryEvent? {
        val track = database.trackDao().getTrackById(event.track_id) ?: return null
        val title = track.title.trim().take(1_000).takeIf { it.isNotBlank() } ?: return null
        val artist = track.artist.trim().take(1_000).takeIf { it.isNotBlank() } ?: return null
        if (event.timestamp !in 1..DriveHistoryProtocol.MAX_WIRE_INTEGER) return null
        val durationMs = (event.estimatedDurationMs ?: track.duration ?: event.playDuration)
            .coerceAtLeast(event.playDuration)
            .coerceAtLeast(0L)
            .coerceAtMost(DriveHistoryProtocol.MAX_WIRE_INTEGER)
        val sourceApp = event.source
            .removePrefix("desktop:")
            .removePrefix("browser:")
            .ifBlank { "android" }
            .take(1_000)
        val source = event.source.ifBlank { "android" }.take(1_000)

        return DriveHistoryEvent(
            eventId = DriveHistoryProtocol.createEventId(
                deviceId = deviceId,
                localEventId = event.id,
                timestampUtc = event.timestamp,
                title = title,
                artist = artist
            ),
            title = title,
            artist = artist,
            album = track.album?.trim()?.take(1_000)?.takeIf { it.isNotBlank() },
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
            sessionId = event.sessionId?.take(1_000),
            site = null,
            contentType = track.contentType.ifBlank { "MUSIC" }.take(1_000),
            volumeLevel = protocolVolumeLevel(event.volumeLevel),
            totalPauseDurationMs = event.totalPauseDurationMs
                .coerceIn(0L, DriveHistoryProtocol.MAX_WIRE_INTEGER),
            positionUpdatesCount = event.positionUpdatesCount.coerceAtLeast(0)
        )
    }

    /**
     * Uses a 24-hour overlap around the Drive created-time cursor. Overlap makes
     * the cursor resilient to delayed/out-of-order uploads; exact event ids plus
     * Tempo's temporal reconciliation make re-reading those files harmless.
     */
    private suspend fun downloadRemoteHistory(): ImportSummary {
        val cursor = statePrefs.getLong(KEY_DOWNLOAD_CREATED_CURSOR, 0L)
        val acceptedGeneration = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        val createdAfter = if (cursor > 0L) {
            (cursor - DOWNLOAD_OVERLAP_MS).coerceAtLeast(0L)
        } else null

        val files = appDataClient.listHistoryBatches(createdAfter)
        var maxCreated = cursor
        var inserted = 0
        var skipped = 0
        var replaced = 0

        for (file in files.sortedBy { it.createdAt }) {
            val rawGeneration = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION]
            val parsedGeneration = rawGeneration?.toLongOrNull()
            if (rawGeneration != null && (parsedGeneration == null || parsedGeneration < 0L)) {
                Log.w(TAG, "Skipping history file with an invalid generation: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val fileGeneration = parsedGeneration ?: 0L
            if (fileGeneration < acceptedGeneration) {
                // Pre-delete data (including an upload that finished after the
                // delete request) is never allowed to resurrect. Cleanup is best
                // effort here because failing to delete stale data must not block
                // current-generation sync.
                appDataClient.delete(file.fileId)
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            val sourceDeviceId = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_DEVICE_ID]
                ?.takeIf(DriveHistoryProtocol::isValidDeviceId)
            val metadataValid = file.fileName.startsWith(DriveHistoryProtocol.FILE_PREFIX) &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_KIND] == DriveHistoryProtocol.KIND_HISTORY_BATCH &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_SCHEMA] == DriveHistoryProtocol.SCHEMA_VERSION.toString() &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_SHA256]
                    ?.matches(Regex("^[0-9a-f]{64}$")) == true &&
                sourceDeviceId != null
            if (!metadataValid) {
                Log.w(TAG, "Skipping history file with invalid Tempo metadata: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val remoteDeviceId = requireNotNull(sourceDeviceId)
            if (remoteDeviceId == deviceId) {
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            // Network/API failures are retryable. Do not move the created-time
            // cursor past a file we did not actually obtain, otherwise after the
            // 24-hour overlap expires that batch could be lost forever.
            // A permanently malformed/oversized payload must not block all later
            // history forever. Treat that specific file as consumed, but never do
            // the same for a transient download failure above.
            val batch = try {
                val bytes = appDataClient.download(file)
                DriveHistoryProtocol.decodeCompressed(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed history batch ${file.fileName}", e)
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val sourcePlatform = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_PLATFORM]
            val expectedName = DriveHistoryProtocol.fileName(remoteDeviceId, batch.batchId, fileGeneration)
            if (batch.sourceDeviceId != remoteDeviceId ||
                sourcePlatform != batch.sourcePlatform ||
                file.fileName != expectedName
            ) {
                Log.w(TAG, "Skipping history batch whose payload does not match Drive metadata: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            if (batch.sourceDeviceId == deviceId) {
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            val incoming = mutableListOf<ListeningEvent>()
            for (event in batch.events) {
                protocolEventToLocal(event, batch.sourceDeviceId)?.let(incoming::add)
            }

            // Resolver/database failures are not safe to skip. Let them abort this
            // sync so the cursor remains behind the uncommitted batch and the next
            // run can retry it idempotently.
            val result = database.listeningEventDao().insertAllBatchedWithDedup(incoming)
            inserted += result.inserted
            skipped += result.skipped
            replaced += result.replaced
            maxCreated = maxOf(maxCreated, file.createdAt)
        }

        if (maxCreated > cursor) {
            statePrefs.edit().putLong(KEY_DOWNLOAD_CREATED_CURSOR, maxCreated).apply()
        }
        return ImportSummary(inserted, skipped, replaced)
    }

    private suspend fun protocolEventToLocal(
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
            volumeLevel = event.volumeLevel,
            contentFingerprint = "$IMPORT_FINGERPRINT_PREFIX${event.eventId}"
        )
    }

    suspend fun deleteCloudHistoryAndReset(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) throw DriveException.Auth("Google Drive authorization is required")
            if (reconcileGoogleAccountBoundary()) {
                settingsManager.setEnabled(false)
                throw DriveException.Auth(
                    "Google account changed. Enable cross-device sync for the new account before deleting its Drive history."
                )
            }

            // Bump the shared server-timestamped generation BEFORE deleting old
            // batches. A client that explicitly re-enables after this point writes
            // generation N and is therefore protected from stale-device cleanup.
            val markerVersion = appDataClient.bumpHistoryDisableMarker()
            val deleted = appDataClient.deleteHistoryBatchesBeforeGeneration(markerVersion)
            statePrefs.edit().putLong(KEY_ACCEPTED_DISABLE_VERSION, markerVersion).apply()
            settingsManager.setEnabled(false)
            resetCursorsLocked()
            deleted
        }
    }

    /**
     * Reset only cloud-sync cursors. Local listening history is never deleted.
     * Re-enabling Drive history sync later intentionally republishes locally-owned
     * history if a newer shared deletion marker was acknowledged.
     */
    private fun resetCursorsLocked() {
        statePrefs.edit()
            .remove(KEY_UPLOAD_CURSOR)
            .remove(KEY_DOWNLOAD_CREATED_CURSOR)
            .apply()
    }

    /**
     * Serialize a local import/restore with Drive synchronization and invalidate
     * both cursors afterwards. Without holding the same mutex as [syncNow], a
     * worker could advance its upload cursor while Room is being replaced and
     * permanently skip restored rows whose ids fall behind that cursor.
     *
     * Cursors are reset even when the import fails because importers may already
     * have committed part of the database before reporting an error.
     */
    suspend fun <T> withLocalHistoryRestore(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } finally {
            resetCursorsLocked()
        }
    }

    private data class ImportSummary(
        val inserted: Int,
        val skipped: Int,
        val replaced: Int
    )
}

sealed class DriveHistorySyncResult {
    data object Disabled : DriveHistorySyncResult()
    data class RemoteDisabled(val message: String) : DriveHistorySyncResult()
    data class Success(
        val uploaded: Int,
        val imported: Int,
        val duplicates: Int,
        val replaced: Int
    ) : DriveHistorySyncResult()
    data class Error(val message: String, val exception: Exception? = null) : DriveHistorySyncResult()
}
