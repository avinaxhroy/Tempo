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
import me.avinas.tempo.data.local.entities.ListeningEventOrigin
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
        private const val KEY_GOOGLE_ACCOUNT_SUBJECT = "google_account_subject"
        private const val KEY_INVALID_EXPORT_IDS = "invalid_drive_export_ids"
        private const val KEY_REJECTED_FILES = "drive_rejected_files"
        private const val KEY_SUPPRESSED_THROUGH = "drive_cloud_suppressed_through"
        private const val KEY_UPLOADED_BEFORE_DELETION = "drive_uploaded_before_deletion"
        private const val KEY_AUTHORIZED_OLD_LAN_THROUGH = "drive_authorized_old_lan_through"
        private const val PAGE_SIZE = 200
        private const val BATCH_SIZE = 50
        private const val DOWNLOAD_OVERLAP_MS = 24L * 60L * 60L * 1000L

        internal data class RejectedBatchRecord(
            val fileId: String,
            val fileName: String,
            val reason: String,
            val recordedAt: Long,
        ) {
            fun serialize(): String = "$fileId\t$fileName\t${reason.replace('\t', ' ').replace('\n', ' ')}\t$recordedAt"
            companion object {
                fun parse(raw: String): RejectedBatchRecord? {
                    val parts = raw.split('\t')
                    if (parts.size < 4) return null
                    return RejectedBatchRecord(
                        fileId = parts[0],
                        fileName = parts[1],
                        reason = parts[2],
                        recordedAt = parts[3].toLongOrNull() ?: 0L,
                    )
                }
            }
        }

        /**
         * Original identity of a LAN-delivered event. Only authenticated LAN
         * payloads with an intact producer ID and event fingerprint qualify.
         * Keep the original ID on Drive so relaying cannot double-count a play.
         */
        /**
         * Once an Android-native play has an origin ID, its identity must not
         * change if the user later corrects the track's title or artist.
         */
        internal fun stableLocalEventId(
            existingOriginId: String?,
            deviceId: String,
            localEventId: Long,
            timestampUtc: Long,
            title: String,
            artist: String
        ): String {
            if (existingOriginId != null) {
                require(existingOriginId.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Invalid persisted Android playback origin"
                }
                return existingOriginId
            }
            return DriveHistoryProtocol.createEventId(
                deviceId = deviceId,
                localEventId = localEventId,
                timestampUtc = timestampUtc,
                title = title,
                artist = artist
            )
        }

        /**
         * A local database restore can remap listening-row IDs for every Google
         * account. Reset upload/download cursors and row-specific diagnostics,
         * but NEVER forget a previously accepted cloud deletion generation:
         * those server checkpoints are independent of Room row IDs. Otherwise
         * switching A -> B -> A could re-accept an old deletion marker and
         * suppress legitimate post-deletion history.
         */
        internal fun isPerAccountRestoreStateKey(key: String): Boolean =
            key.startsWith("${KEY_UPLOAD_CURSOR}:") ||
                key.startsWith("${KEY_DOWNLOAD_CREATED_CURSOR}:") ||
                key.startsWith("${KEY_REJECTED_FILES}:") ||
                key.startsWith("${KEY_INVALID_EXPORT_IDS}:")

        /** Only a complete Drive listing can prove an older rejected file was deleted.
         * Incremental queries omit files outside the 24-hour overlap.
         */
        internal fun retainedRejectedRecords(
            previous: Set<String>,
            listedFileIds: Set<String>,
            completeListing: Boolean
        ): Set<String> = if (!completeListing) previous else previous.filterTo(mutableSetOf()) {
            listedFileIds.contains(it.substringBefore('\t'))
        }

        /**
         * A failed cleanup of already-deleted cloud generations must not block
         * importing current-generation batches. Never swallow cancellation.
         */
        internal suspend fun cleanupStaleBatchBestEffort(
            delete: suspend () -> Unit,
            onError: (Exception) -> Unit,
        ): Boolean = try {
            delete()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            false
        }


        /** Local row IDs fence the deletion snapshot; a capture made AFTER
         * the server-timestamped deletion is new history, even when the phone
         * only learns about the deletion after recording it offline.
         */
        internal fun isCloudSuppressedRow(
            eventId: Long,
            suppressedThrough: Long,
            recordedAtUtc: Long,
            deletionMarkerUtc: Long,
            uploadedBeforeDeletion: Long,
        ): Boolean = eventId <= suppressedThrough &&
            (recordedAtUtc <= deletionMarkerUtc || eventId <= uploadedBeforeDeletion)

        /** A LAN pairing does not prove Google identity. Reject unknown owners
         * and old deliveries arriving after cloud deletion unless the user
         * explicitly authorized the bounded local snapshot.
         */
        internal fun canRelayLanToCloud(
            owner: String?,
            currentSubject: String,
            timestampUtc: Long,
            eventId: Long,
            deletionMarkerUtc: Long,
            authorizedOldLanThrough: Long,
        ): Boolean = owner == currentSubject &&
            (deletionMarkerUtc <= 0L || timestampUtc > deletionMarkerUtc ||
                eventId <= authorizedOldLanThrough)

        /** Pure resume rule: existing per-subject progress always wins over
         * global progress from the account that happened to be active last.
         */

        internal fun chooseAccountUploadCursor(
            savedForSubject: Long?,
            legacyEmailCursor: Long?,
            isNewAccount: Boolean,
            previouslyActiveCursor: Long,
            newestLocalRow: Long,
        ): Long = savedForSubject ?: legacyEmailCursor ?:
            if (isNewAccount) newestLocalRow else previouslyActiveCursor

        internal fun lanBatchProducer(source: String): BatchProducer? {
            val parts = source.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "lan" ||
                !DriveHistoryProtocol.isValidDeviceId(parts[1]) || parts[2].isBlank()
            ) return null
            val platform = when {
                parts[2].startsWith("desktop:") -> "desktop"
                parts[2].startsWith("browser:") -> "browser"
                else -> "android"
            }
            return BatchProducer(parts[1], platform)
        }

        internal fun lanRelayIdentity(source: String, fingerprint: String?): Pair<String, String>? {
            val parts = source.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "lan" ||
                !DriveHistoryProtocol.isValidDeviceId(parts[1]) || parts[2].isBlank()
            ) return null
            val rawFingerprint = fingerprint ?: return null
            if (!rawFingerprint.startsWith(DRIVE_IMPORT_FINGERPRINT_PREFIX)) return null
            val eventId = rawFingerprint.removePrefix(DRIVE_IMPORT_FINGERPRINT_PREFIX)
            if (!eventId.matches(Regex("^[0-9a-f]{64}$"))) return null
            return parts[2] to eventId
        }

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
    private val eventMapper by lazy { DriveHistoryEventMapper(database, trackResolver, deviceId) }
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
     * never silently by a background worker. Re-enabling does not authorize
     * republishing history previously removed from Drive.
     */
    suspend fun enableSync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) return@withContext false
            appDataClient.withAccountBoundSession { accountEmail ->
                reconcileGoogleAccountBoundary(accountEmail)
                val currentMarker = appDataClient.getHistoryDisableMarkerVersion()
                val acceptedMarker = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L)
                if (currentMarker > acceptedMarker) {
                    // An offline phone must not resurrect a deleted cloud
                    // archive just because the user reconnects.
                    val subject = statePrefs.getString(KEY_GOOGLE_ACCOUNT_SUBJECT, null)
                        ?: error("Missing verified Google subject")
                    val ceiling = database.listeningEventDao().getMaxEventId()
                    val key = suppressedThroughKey(subject)
                    val existing = statePrefs.getLong(key, 0L)
                    val uploadedKey = uploadedBeforeDeletionKey(subject)
                    val uploaded = statePrefs.getLong(uploadCursorKey(subject),
                        statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L))
                    val scopedDisableKey = acceptedDisableVersionKey(subject)
                    val scopedDownloadKey = downloadCursorKey(subject)
                    val currentDisable = statePrefs.getLong(scopedDisableKey, acceptedMarker)
                    val newDisable = maxOf(currentDisable, currentMarker)
                    check(statePrefs.edit()
                        .remove(authorizedOldLanKey(subject))
                        .putLong(key, maxOf(existing, ceiling))
                        .putLong(uploadedKey,
                            maxOf(statePrefs.getLong(uploadedKey, 0L), uploaded))
                        .remove(KEY_DOWNLOAD_CREATED_CURSOR)
                        .remove(scopedDownloadKey)
                        .putLong(KEY_ACCEPTED_DISABLE_VERSION, newDisable)
                        .putLong(scopedDisableKey, newDisable)
                        .remove(rejectedFilesKey(subject))
                        .commit()) { "Could not persist Google Drive deletion boundary" }
                }
                settingsManager.setEnabled(true)
                true
            }
        }
    }

    suspend fun disableSync() = mutex.withLock {
        settingsManager.setEnabled(false)
    }

    /** Explicit consent, separate from merely enabling Drive, to republish old
     * local recordings previously suppressed by a cloud deletion.
     */
    suspend fun shareOlderLocalHistory(): DriveHistorySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!settingsManager.settings.first().enabled || !ensureAuthorized()) {
                return@withContext DriveHistorySyncResult.Error("Connect Google Drive first")
            }
            appDataClient.withAccountBoundSession { accountSubject ->
                if (reconcileGoogleAccountBoundary(accountSubject)) {
                    settingsManager.setEnabled(false)
                    return@withAccountBoundSession DriveHistorySyncResult.Error(
                        "Google account changed; reconnect before sharing old history"
                    )
                }
                val remoteDisabled = handleRemoteDisableIfNeeded()
                if (remoteDisabled != null) return@withAccountBoundSession remoteDisabled
                val key = suppressedThroughKey(accountSubject)
                val dao = database.listeningEventDao()
                val throughEventId = dao.getMaxEventId()
                // Persist snapshot-scoped consent and rewind BEFORE claiming SQL
                // ownership. A crash cannot silently authorize future arrivals
                // or strand approved rows behind the upload cursor.
                check(statePrefs.edit().remove(key)
                    .remove(uploadedBeforeDeletionKey(accountSubject))
                    .putLong(authorizedOldLanKey(accountSubject), throughEventId)
                    .remove(KEY_UPLOAD_CURSOR)
                    .remove(uploadCursorKey(accountSubject)).commit()) {
                    "Could not authorize the local Drive history upload"
                }
                dao.authorizeLanRelaysForAccount(accountSubject, throughEventId)
                // Reuse the normal sync path after releasing the mutex.
                DriveHistorySyncResult.Success(0, 0, 0, 0)
            }
        }
    }

    /** Re-read all cloud batches for explicit historical recovery; never delete local plays. */
    suspend fun restoreFullHistory(): DriveHistorySyncResult = syncNow(forceFullRestore = true)

    suspend fun syncNow(forceFullRestore: Boolean = false): DriveHistorySyncResult = mutex.withLock {
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

                appDataClient.withAccountBoundSession { accountEmail ->
                    if (reconcileGoogleAccountBoundary(accountEmail)) {
                        val message =
                            "Google account changed. Cross-device sync was turned off; enable it again to use the new Drive account."
                        settingsManager.setEnabled(false)
                        settingsManager.markFailure(message)
                        return@withAccountBoundSession DriveHistorySyncResult.RemoteDisabled(message)
                    }

                    val remoteDisable = handleRemoteDisableIfNeeded()
                    if (remoteDisable != null) return@withAccountBoundSession remoteDisable

                    var uploadFailure: Exception? = null
                    val uploaded = try {
                        uploadLocalHistory(accountEmail)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // Failed outgoing transport must not stop independent
                        // inbound Drive history from updating this device.
                        uploadFailure = failure
                        0
                    }
                    if (forceFullRestore) {
                        // Clear only the receive cursor. If the download fails, retry
                        // again from the beginning rather than losing an old batch.
                        statePrefs.edit()
                            .remove(KEY_DOWNLOAD_CREATED_CURSOR)
                            .remove(downloadCursorKey(accountEmail))
                            .commit()
                            .also { check(it) { "Could not persist Drive full-restore request" } }
                    }
                    val download = downloadRemoteHistory(includeOwnDeviceBatches = forceFullRestore, accountSubject = accountEmail)
                    uploadFailure?.let { throw it }
                    val invalidCount = statePrefs.getStringSet("${KEY_INVALID_EXPORT_IDS}:${accountEmail}", emptySet())
                        .orEmpty().size
                    val rejectedList = getRejectedBatches(accountEmail)
                    val warningMessage = when {
                        rejectedList.isNotEmpty() -> {
                            val sample = rejectedList.first()
                            "${rejectedList.size} Google Drive history file(s) need attention (e.g. ${sample.fileName}: ${sample.reason}). Check the affected history and run Restore full history to retry."
                        }
                        invalidCount > 0 -> "${invalidCount} local event(s) need metadata repair; preserved on device"
                        download.skipped > 0 -> "${download.skipped} duplicate event(s) ignored"
                        else -> null
                    }
                    settingsManager.markSuccess(
                        uploaded = uploaded,
                        imported = download.inserted,
                        message = warningMessage
                    )
                    DriveHistorySyncResult.Success(
                        uploaded = uploaded,
                        imported = download.inserted,
                        duplicates = download.skipped,
                        replaced = download.replaced
                    )
                }
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
    private fun uploadCursorKey(accountSubject: String): String = "${KEY_UPLOAD_CURSOR}:${accountSubject}"
    private fun downloadCursorKey(accountSubject: String): String = "${KEY_DOWNLOAD_CREATED_CURSOR}:${accountSubject}"
    private fun acceptedDisableVersionKey(accountSubject: String): String = "${KEY_ACCEPTED_DISABLE_VERSION}:${accountSubject}"
    private fun rejectedFilesKey(accountSubject: String): String = "${KEY_REJECTED_FILES}:${accountSubject}"
    private fun suppressedThroughKey(accountSubject: String): String =
        "${KEY_SUPPRESSED_THROUGH}:${accountSubject}"
    private fun uploadedBeforeDeletionKey(accountSubject: String): String =
        "${KEY_UPLOADED_BEFORE_DELETION}:${accountSubject}"
    private fun authorizedOldLanKey(accountSubject: String): String =
        "${KEY_AUTHORIZED_OLD_LAN_THROUGH}:${accountSubject}"

    private fun getRejectedBatches(accountSubject: String): List<RejectedBatchRecord> {
        val raw = statePrefs.getStringSet(rejectedFilesKey(accountSubject), emptySet()) ?: emptySet()
        return raw.mapNotNull { RejectedBatchRecord.parse(it) }
            .sortedBy { it.recordedAt }
    }

    private fun markRejectedBatch(
        accountSubject: String,
        fileId: String,
        fileName: String,
        reason: String
    ) {
        val key = rejectedFilesKey(accountSubject)
        val current = statePrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.removeAll { it.startsWith("$fileId\t") }
        val record = RejectedBatchRecord(
            fileId = fileId,
            fileName = fileName,
            reason = reason,
            recordedAt = System.currentTimeMillis()
        )
        current.add(record.serialize())
        statePrefs.edit().putStringSet(key, current).apply()
    }

    private fun clearRejectedBatch(accountSubject: String, fileId: String) {
        val key = rejectedFilesKey(accountSubject)
        val current = statePrefs.getStringSet(key, emptySet())?.toMutableSet() ?: return
        if (current.removeAll { it.startsWith("$fileId\t") }) {
            statePrefs.edit().putStringSet(key, current).apply()
        }
    }

    private fun clearMissingRejectedBatches(
        accountSubject: String,
        liveFileIds: Set<String>,
        completeListing: Boolean
    ) {
        val key = rejectedFilesKey(accountSubject)
        val current = statePrefs.getStringSet(key, emptySet())?.toMutableSet() ?: return
        val filtered = retainedRejectedRecords(current, liveFileIds, completeListing)
        if (filtered.size != current.size) {
            statePrefs.edit().putStringSet(key, filtered).apply()
        }
    }

    private fun clearAllRejectedBatches(accountSubject: String) {
        statePrefs.edit().remove(rejectedFilesKey(accountSubject)).apply()
    }

    private suspend fun reconcileGoogleAccountBoundary(current: String): Boolean {
        require(current.isNotBlank()) { "Google account subject cannot be empty" }
        val dao = database.listeningEventDao()
        val previous = statePrefs.getString(KEY_GOOGLE_ACCOUNT_SUBJECT, null)
            ?.takeIf { it.isNotBlank() }
        val previousEmail = statePrefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val currentEmail = authManager.currentAccount.value?.email?.trim()?.lowercase()
        if (previous == current) return false

        // Pre-fix GoogleIdTokenCredential.id returned email, NOT a stable
        // subject. Migrate only rows matching the email verified by the
        // newly selected Google identity; never reassign quarantined imports.
        val legacySameAccount = previous != null && previous == currentEmail
        if (currentEmail != null && currentEmail != current) {
            dao.upgradeLegacyEmailOwner(currentEmail, current)
        }

        val changed = (previous != null && !legacySameAccount) ||
            (previous == null && previousEmail != null && previousEmail != currentEmail)
        val legacyOwner = when {
            previous != null -> if (legacySameAccount) current else previous
            previousEmail == null || previousEmail == currentEmail -> current
            else -> "legacy-unverified"
        }
        // Claim rows before moving the active account pointer. If a failure
        // occurs here, the old credentials and preferences remain unchanged.
        dao.claimUnownedDriveHistory(legacyOwner)

        val globalCursor = statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L)
        val maxId = dao.getMaxEventId()
        val scopedCursor = uploadCursorKey(current)
        val oldEmailCursor = currentEmail?.let { uploadCursorKey(it) }
        val resumedCursor = chooseAccountUploadCursor(
            savedForSubject = statePrefs.getLong(scopedCursor, 0L)
                .takeIf { statePrefs.contains(scopedCursor) },
            legacyEmailCursor = oldEmailCursor?.takeIf(statePrefs::contains)
                ?.let { statePrefs.getLong(it, 0L) },
            isNewAccount = changed,
            previouslyActiveCursor = globalCursor,
            newestLocalRow = maxId,
        )

        val scopedDownloadKey = downloadCursorKey(current)
        val oldEmailDownloadKey = currentEmail?.let { downloadCursorKey(it) }
        val resumedDownloadCursor = when {
            statePrefs.contains(scopedDownloadKey) -> statePrefs.getLong(scopedDownloadKey, 0L)
            oldEmailDownloadKey != null && statePrefs.contains(oldEmailDownloadKey) ->
                statePrefs.getLong(oldEmailDownloadKey, 0L)
            else -> 0L
        }

        val scopedDisableKey = acceptedDisableVersionKey(current)
        val oldEmailDisableKey = currentEmail?.let { acceptedDisableVersionKey(it) }
        val resumedDisableVersion = when {
            statePrefs.contains(scopedDisableKey) -> statePrefs.getLong(scopedDisableKey, 0L)
            oldEmailDisableKey != null && statePrefs.contains(oldEmailDisableKey) ->
                statePrefs.getLong(oldEmailDisableKey, 0L)
            else -> 0L
        }

        val editor = statePrefs.edit()
        if (previous != null) {
            // Snapshot departing account progress so returning to this account
            // resumes where it left off, rather than re-evaluating old deletion markers.
            editor.putLong(uploadCursorKey(previous), globalCursor)
            editor.putLong(downloadCursorKey(previous), statePrefs.getLong(KEY_DOWNLOAD_CREATED_CURSOR, 0L))
            editor.putLong(acceptedDisableVersionKey(previous), statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L))
        }
        editor.putString(KEY_GOOGLE_ACCOUNT_SUBJECT, current)
            .putString(KEY_GOOGLE_ACCOUNT_EMAIL, currentEmail)
            .putLong(KEY_UPLOAD_CURSOR, resumedCursor)
            .putLong(scopedCursor, resumedCursor)
            .putLong(KEY_DOWNLOAD_CREATED_CURSOR, resumedDownloadCursor)
            .putLong(scopedDownloadKey, resumedDownloadCursor)
            .putLong(KEY_ACCEPTED_DISABLE_VERSION, resumedDisableVersion)
            .putLong(scopedDisableKey, resumedDisableVersion)

        check(editor.commit()) { "Could not persist Google Drive account boundary" }
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

        val message = "Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history."
        acceptDeletionMarker(currentMarker, message)
        appDataClient.deleteHistoryBatchesBeforeGeneration(currentMarker)
        return DriveHistorySyncResult.RemoteDisabled(message)
    }

    private suspend fun acceptDeletionMarker(marker: Long, message: String? = null) {
        withContext(NonCancellable) {
            // Stop before cleanup; keep local plays and their cloud suppression
            // durable even if the network or process fails afterwards.
            settingsManager.markStopped(message ?: "Cloud history sync was turned off after deletion")
            val subject = statePrefs.getString(KEY_GOOGLE_ACCOUNT_SUBJECT, null)
                ?: error("Missing verified Google subject")
            val ceiling = database.listeningEventDao().getMaxEventId()
            val suppressionKey = suppressedThroughKey(subject)
            val previous = statePrefs.getLong(suppressionKey, 0L)
            val uploadedKey = uploadedBeforeDeletionKey(subject)
            val alreadyUploaded = statePrefs.getLong(uploadCursorKey(subject),
                statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L))
            val scopedDisableKey = acceptedDisableVersionKey(subject)
            val scopedDownloadKey = downloadCursorKey(subject)
            val currentDisable = statePrefs.getLong(scopedDisableKey, statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L))
            val newDisable = maxOf(currentDisable, marker)
            check(statePrefs.edit()
                .remove(authorizedOldLanKey(subject))
                .putLong(KEY_ACCEPTED_DISABLE_VERSION, newDisable)
                .putLong(scopedDisableKey, newDisable)
                .putLong(suppressionKey, maxOf(previous, ceiling))
                .putLong(uploadedKey, maxOf(statePrefs.getLong(uploadedKey, 0L), alreadyUploaded))
                .remove(KEY_DOWNLOAD_CREATED_CURSOR)
                .remove(scopedDownloadKey)
                .remove(rejectedFilesKey(subject))
                .commit()) { "Could not persist Drive history deletion marker" }
        }
    }

    /** The authenticated LAN producer is the owner of relayed events.
     * Writing all relays as Android-origin batches would wrongly classify two
     * distinct producers' captures as two events from one producer, breaking
     * one-to-one deduplication on the receiving clients.
     */
    internal data class BatchProducer(val deviceId: String, val platform: String) {
        val displayName: String get() = "Tempo ${platform.replaceFirstChar { it.uppercase() }}"
    }

    private fun uploadProducer(event: ListeningEvent): BatchProducer {
        if (!event.source.startsWith("lan:")) {
            return BatchProducer(deviceId, "android")
        }
        return lanBatchProducer(event.source)
            ?: error("Cannot recover producer identity for relayed LAN listening event ${event.id}")
    }

    /**
     * Uploads locally-owned Room events in id order. Imported Drive events are
     * skipped so another device's event can never bounce back into Drive as a new
     * event. The cursor advances only after every eligible event in a page has
     * been safely uploaded.
     */
    private suspend fun uploadLocalHistory(accountSubject: String): Int {
        val dao = database.listeningEventDao()
        val maxId = dao.getMaxEventId()
        val storedCursor = statePrefs.getLong(uploadCursorKey(accountSubject),
            statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L))
        val generation = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        val suppressedThrough = statePrefs.getLong(suppressedThroughKey(accountSubject), 0L)
        val uploadedBeforeDeletion =
            statePrefs.getLong(uploadedBeforeDeletionKey(accountSubject), 0L)
        val authorizedOldLanThrough =
            statePrefs.getLong(authorizedOldLanKey(accountSubject), 0L)
        // Background sync never claims LAN records with unverified Google
        // ownership, even if the original producer event ID looks valid.
        // A database restore can move Room row ids backwards while SharedPreferences
        // survive. If the persisted cursor is now beyond the database snapshot,
        // keeping it would make every newly-created row look already scanned until
        // ids eventually caught up. Restart from zero instead; deterministic Drive
        // event/batch ids make replay safe and preferable to silently losing plays.
        var afterId = if (storedCursor > maxId) {
            check(statePrefs.edit()
                .remove(KEY_UPLOAD_CURSOR).remove(uploadCursorKey(accountSubject))
                .commit()) { "Could not restart Drive upload after database restore" }
            0L
        } else {
            storedCursor
        }
        var uploaded = 0
        val retryIds = statePrefs.getStringSet("${KEY_INVALID_EXPORT_IDS}:${accountSubject}", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()
        val stillInvalid = retryIds.toMutableSet()
        // Every SQL query AND every serialization page must remain bounded.
        // The old implementation fetched in chunks, then concatenated the
        // entire repair queue before getOriginClaimsForEvents(IN (...)), which
        // could overflow SQLite's 999-variable limit on older Android.
        val repairPages = retryIds.toList().sorted().chunked(PAGE_SIZE)
        var repairIndex = 0

        while (afterId < maxId || repairIndex < repairPages.size) {
            val retrying = repairIndex < repairPages.size
            val requestedRepairIds = if (retrying) repairPages[repairIndex++] else emptyList()
            val page = if (retrying) dao.getDriveRetryRows(requestedRepairIds)
                else dao.getEventsPage(afterId, maxId, PAGE_SIZE)
            if (retrying) {
                // Removed rows cannot be repaired and should not accumulate
                // as ghost IDs in the persistent retry queue.
                val present = page.mapTo(HashSet()) { it.id }
                stillInvalid.removeAll(requestedRepairIds.filterNot(present::contains).toSet())
            }
            if (page.isEmpty() && !retrying) break
            if (page.isEmpty()) continue

            // Resolve per-play identities before serialization. Persist new
            // Android-native IDs BEFORE network I/O: a crash after the Drive
            // upload must never allow a later metadata correction to change
            // the event ID on the retry or a full-history restore.
            val ownOrigins = dao.getOriginClaimsForEvents(page.map { it.id })
                .filter { it.sourceDeviceId == deviceId }
                .associate { it.listeningEventId to it.originEventId }
            val newOwnOrigins = mutableListOf<ListeningEventOrigin>()
            val eventsByProducer = linkedMapOf<BatchProducer, MutableList<DriveHistoryEvent>>()
            for (event in page) {
                if (isCloudSuppressedRow(
                        event.id, suppressedThrough, event.timestamp, generation,
                        uploadedBeforeDeletion,
                    )) continue
                if (event.driveAccountSubject != null &&
                    event.driveAccountSubject != accountSubject) continue
                if (event.source.startsWith("lan:") && !canRelayLanToCloud(
                        event.driveAccountSubject, accountSubject, event.timestamp,
                        event.id, generation, authorizedOldLanThrough,
                    )) continue
                // Downloaded Drive history never bounces back into the cloud.
                // Account-matched LAN events keep the original producer ID.
                // Late arrivals recorded before deletion require consent.
                if (event.contentFingerprint?.startsWith(DRIVE_IMPORT_FINGERPRINT_PREFIX) == true &&
                    !event.source.startsWith("lan:")
                ) continue
                // Never move the persistent upload cursor past a local play that
                // cannot be exported (for example, a temporarily missing Track).
                // Surface the row ID and retry after its metadata is repaired.
                val exported = eventMapper.localEventToProtocol(event, ownOrigins[event.id])
                if (exported == null) {
                    stillInvalid.add(event.id)
                    Log.w(TAG, "Retaining invalid Drive export row ${event.id} for later repair")
                    continue
                }
                stillInvalid.remove(event.id)
                if (!event.source.startsWith("lan:") && !event.source.startsWith("drive:") &&
                    ownOrigins[event.id] == null
                ) {
                    newOwnOrigins.add(ListeningEventOrigin(exported.eventId, event.id, deviceId, accountSubject))
                }
                eventsByProducer.getOrPut(uploadProducer(event)) { mutableListOf() }.add(exported)
            }
            if (newOwnOrigins.isNotEmpty()) dao.insertOriginAliases(newOwnOrigins)

            // Keep each producer's events in its own immutable Drive batch.
            // Event IDs and batch IDs are still deterministic across retries.
            for ((producer, producerEvents) in eventsByProducer) {
                for (events in producerEvents.chunked(BATCH_SIZE)) {
                    val batchId = DriveHistoryProtocol.createBatchId(events)
                    val batch = DriveHistoryBatch(
                        batchId = batchId,
                        sourceDeviceId = producer.deviceId,
                        sourceDeviceName = producer.displayName,
                        sourcePlatform = producer.platform,
                        createdAtUtc = events.maxOf { it.timestampUtc },
                        events = events
                    )
                    val bytes = DriveHistoryProtocol.encodeCompressed(batch)
                    appDataClient.uploadHistoryBatch(
                        fileName = DriveHistoryProtocol.fileName(producer.deviceId, batchId, generation),
                        compressedBytes = bytes,
                        appProperties = mapOf(
                            DriveHistoryProtocol.APP_PROPERTY_KIND to DriveHistoryProtocol.KIND_HISTORY_BATCH,
                            DriveHistoryProtocol.APP_PROPERTY_SCHEMA to DriveHistoryProtocol.SCHEMA_VERSION.toString(),
                            DriveHistoryProtocol.APP_PROPERTY_DEVICE_ID to producer.deviceId,
                            DriveHistoryProtocol.APP_PROPERTY_PLATFORM to producer.platform,
                            DriveHistoryProtocol.APP_PROPERTY_GENERATION to generation.toString()
                        )
                    )
                    uploaded += events.size
                }
            }

            if (!retrying) afterId = page.last().id
            check(statePrefs.edit()
                .putLong(KEY_UPLOAD_CURSOR, afterId)
                .putLong(uploadCursorKey(accountSubject), afterId)
                .putStringSet("${KEY_INVALID_EXPORT_IDS}:${accountSubject}", stillInvalid.map(Long::toString).toSet())
                .commit()) { "Could not checkpoint Drive upload history and invalid rows" }
        }

        return uploaded
    }

    /**
     * Uses a 24-hour overlap around the Drive created-time cursor. Overlap makes
     * the cursor resilient to delayed/out-of-order uploads; exact event ids plus
     * Tempo's temporal reconciliation make re-reading those files harmless.
     */
    private suspend fun downloadRemoteHistory(
        includeOwnDeviceBatches: Boolean = false,
        accountSubject: String,
    ): ImportSummary {
        val cursor = statePrefs.getLong(KEY_DOWNLOAD_CREATED_CURSOR, 0L)
        val acceptedGeneration = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        val createdAfter = if (cursor > 0L) {
            (cursor - DOWNLOAD_OVERLAP_MS).coerceAtLeast(0L)
        } else null

        val files = appDataClient.listHistoryBatches(createdAfter)
        // An incremental result omits older files: absence alone does not mean deletion.
        clearMissingRejectedBatches(
            accountSubject, files.map { it.fileId }.toSet(),
            completeListing = createdAfter == null || createdAfter <= 0L
        )
        var maxCreated = cursor
        var inserted = 0
        var skipped = 0
        var replaced = 0

        for ((index, file) in files.sortedBy { it.createdAt }.withIndex()) {
            // Full restoration may process thousands of batches. Persist the
            // last completed prefix periodically so process death or a work
            // timeout does not force replaying the entire historic archive.
            if (index > 0 && index % 50 == 0 && maxCreated > cursor) {
                check(statePrefs.edit()
                    .putLong(KEY_DOWNLOAD_CREATED_CURSOR, maxCreated)
                    .putLong(downloadCursorKey(accountSubject), maxCreated)
                    .commit()) {
                    "Could not checkpoint Drive history restoration"
                }
            }
            val fileGeneration = DriveHistoryProtocol.parseGeneration(
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION]
            )
            if (fileGeneration == null) {
                Log.w(TAG, "Skipping history file with an invalid generation: ${file.fileName}")
                markRejectedBatch(accountSubject, file.fileId, file.fileName, "Invalid generation property")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            if (fileGeneration < acceptedGeneration) {
                if (!DriveHistoryProtocol.shouldDeleteHistoryBatch(
                        file.fileName,
                        file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION],
                        acceptedGeneration,
                        ownerDelete = false
                    )) {
                    markRejectedBatch(accountSubject, file.fileId, file.fileName,
                        "Generation metadata conflicts with newer batch filename")
                    maxCreated = maxOf(maxCreated, file.createdAt)
                    continue
                }
                // Pre-delete data (including a late upload) must not resurrect.
                // Cleanup is best effort: transient Drive DELETE failures must
                // not stop later, current-generation history from downloading.
                // Cancellation is different: always propagate it immediately.
                val deleted = cleanupStaleBatchBestEffort(
                    delete = { appDataClient.delete(file.fileId) },
                    onError = { error ->
                        Log.w(TAG, "Could not remove obsolete Drive batch ${file.fileName}; ignoring it for import", error)
                    }
                )
                if (deleted) {
                    clearRejectedBatch(accountSubject, file.fileId)
                } else {
                    // Cloud privacy cleanup may still be incomplete. Keep a
                    // durable, account-scoped warning until a full Drive scan
                    // confirms the file is gone or deletion succeeds.
                    markRejectedBatch(accountSubject, file.fileId, file.fileName,
                        "Obsolete pre-deletion Drive file could not be removed")
                }
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
                markRejectedBatch(accountSubject, file.fileId, file.fileName, "Invalid Tempo metadata")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val remoteDeviceId = requireNotNull(sourceDeviceId)
            if (!includeOwnDeviceBatches && remoteDeviceId == deviceId) {
                clearRejectedBatch(accountSubject, file.fileId)
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
                markRejectedBatch(accountSubject, file.fileId, file.fileName, e.message ?: "Malformed payload")
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
                markRejectedBatch(accountSubject, file.fileId, file.fileName, "Payload does not match Drive metadata")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            if (!includeOwnDeviceBatches && batch.sourceDeviceId == deviceId) {
                clearRejectedBatch(accountSubject, file.fileId)
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            val incoming = mutableListOf<ListeningEvent>()
            for (event in batch.events) {
                eventMapper.protocolEventToLocal(event, batch.sourceDeviceId)?.let {
                    incoming.add(it.copy(driveAccountSubject = accountSubject))
                }
            }

            // Resolver/database failures are not safe to skip. Let them abort this
            // sync so the cursor remains behind the uncommitted batch and the next
            // run can retry it idempotently.
            val result = database.listeningEventDao().insertAllBatchedWithDedup(incoming, accountSubject)
            clearRejectedBatch(accountSubject, file.fileId)
            inserted += result.inserted
            skipped += result.skipped
            replaced += result.replaced
            maxCreated = maxOf(maxCreated, file.createdAt)
        }

        if (maxCreated > cursor) {
            statePrefs.edit()
                .putLong(KEY_DOWNLOAD_CREATED_CURSOR, maxCreated)
                .putLong(downloadCursorKey(accountSubject), maxCreated)
                .apply()
        }
        return ImportSummary(inserted, skipped, replaced)
    }

    suspend fun deleteCloudHistoryAndReset(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) throw DriveException.Auth("Google Drive authorization is required")
            appDataClient.withAccountBoundSession { accountEmail ->
                if (reconcileGoogleAccountBoundary(accountEmail)) {
                    settingsManager.setEnabled(false)
                    throw DriveException.Auth(
                        "Google account changed. Enable cross-device sync for the new account before deleting its Drive history."
                    )
                }

                // Bump the shared server-timestamped generation BEFORE deleting old
                // batches. A client that explicitly re-enables after this point writes
                // generation N and is therefore protected from stale-device cleanup.
                val markerVersion = appDataClient.bumpHistoryDisableMarker()
                acceptDeletionMarker(markerVersion)
                val deleted = appDataClient.deleteHistoryBatchesBeforeGeneration(markerVersion, ownerDelete = true)
                clearAllRejectedBatches(accountEmail)
                deleted
            }
        }
    }

    /**
     * Reset cloud-sync cursors without deleting local history. A separate
     * per-account suppression fence prevents cloud-deleted plays from being
     * republished on an ordinary reconnect.
     * A database restore may change primary keys for every Google owner.
     * Invalid-row retry IDs also refer to old primary keys and must be cleared.
     * A cloud deletion marker, by contrast, resets only the active account.
     */
    private fun resetCursorsLocked(clearEveryAccount: Boolean = false) {
        val editor = statePrefs.edit()
            .remove(KEY_UPLOAD_CURSOR)
            .remove(KEY_DOWNLOAD_CREATED_CURSOR)
        if (clearEveryAccount) {
            statePrefs.all.keys.filter(::isPerAccountRestoreStateKey)
                .forEach(editor::remove)
            // A restore can remap Room IDs. Do not reuse old consent for rows
            // that acquire those IDs from a new database snapshot.
            statePrefs.all.keys.filter {
                it.startsWith("${KEY_AUTHORIZED_OLD_LAN_THROUGH}:")
            }.forEach(editor::remove)
            editor.remove(KEY_INVALID_EXPORT_IDS)
        } else {
            val subject = statePrefs.getString(KEY_GOOGLE_ACCOUNT_SUBJECT, null)
            if (subject != null) {
                editor.remove(uploadCursorKey(subject))
                    .remove(downloadCursorKey(subject))
                    .remove(rejectedFilesKey(subject))
            }
        }
        check(editor.commit()) { "Could not invalidate Google Drive cursors after history change" }
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
            try {
                // Room restore can remap primary keys and move previously
                // deleted history above its prior ID ceiling. Conservatively
                // fence all restored rows for suppressed Google accounts.
                val ceiling = database.listeningEventDao().getMaxEventId()
                val editor = statePrefs.edit()
                statePrefs.all.keys.filter { it.startsWith("${KEY_SUPPRESSED_THROUGH}:") }
                    .forEach { key ->
                        editor.putLong(key, maxOf(statePrefs.getLong(key, 0L), ceiling))
                    }
                check(editor.commit()) { "Could not preserve deletion suppression after restore" }
            } finally {
                // Invalidate stale Room row-ID upload cursors even if a restore
                // or a suppression-fence update fails.
                resetCursorsLocked(clearEveryAccount = true)
            }
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
