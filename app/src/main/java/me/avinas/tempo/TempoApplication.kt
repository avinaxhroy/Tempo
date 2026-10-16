package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsConsent
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.AppStartType
import me.avinas.tempo.data.analytics.AppStarted
import me.avinas.tempo.data.analytics.CrashSignatureRecorder
import me.avinas.tempo.data.analytics.DbMigration
import me.avinas.tempo.data.drive.BackupInterval
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.data.drive.LocalBackupStorage
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.UserKnownArtistDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.repository.AppPreferenceRepository
import me.avinas.tempo.service.TrackingServiceHeartbeat
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.utils.FrameworkRaceGuard
import me.avinas.tempo.worker.AnalyticsFlushWorker
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.DriveBackupWorker
import me.avinas.tempo.worker.DriveHistorySyncWorker
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.ListeningActivityWorker
import me.avinas.tempo.worker.LocalBackupWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import me.avinas.tempo.worker.SpotlightUnlockWorker
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Application class configuring Hilt DI, WorkManager HiltWorkerFactory,
 * and Coil SingletonImageLoader.
 */
@HiltAndroidApp
class TempoApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var userPreferencesDao: UserPreferencesDao

    @Inject
    lateinit var userKnownArtistDao: UserKnownArtistDao

    @Inject
    lateinit var appPreferenceRepository: AppPreferenceRepository

    @Inject
    lateinit var artistRepairService: me.avinas.tempo.data.repository.ArtistRepairService

    @Inject
    lateinit var listColumnRepairService: me.avinas.tempo.data.repository.ListColumnRepairService

    @Inject
    lateinit var backupSettingsManager: BackupSettingsManager

    @Inject
    lateinit var driveHistorySyncSettingsManager: DriveHistorySyncSettingsManager

    @Inject
    lateinit var crashSignatureRecorder: CrashSignatureRecorder

    @Inject
    lateinit var analyticsTracker: AnalyticsTracker

    @Inject
    lateinit var analyticsConsent: AnalyticsConsent

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lastSeenDbVersionKey = intPreferencesKey("last_seen_db_version")

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()

        val initStartedAt = System.currentTimeMillis()

        // Installed before anything else so a crash during start-up is still captured, then
        // any signature left by the previous run is reported.
        crashSignatureRecorder.install()
        crashSignatureRecorder.reportPending()

        // Installed after recorder so genuine crashes are still captured; filters QueuedWork framework race (b/257513022).
        FrameworkRaceGuard.install()

        loadUserKnownArtists()
        seedDefaultAppPreferences()
        reconcileAutomaticBackupSchedules()
        scheduleBackgroundWorkDeferred()
        observeAnalyticsConsent()

        reportAppStarted(initStartedAt)
        reportDatabaseVersionChange()
    }

    /**
     * Seeds `app_preferences` with the default app lists on every process start.
     *
     * `seedDefaultAppsIfNeeded` was previously only invoked from the Supported Apps
     * settings screen, so users who never opened that screen ran with an empty table —
     * every curated music app then read as "not enabled" once the tracking service's
     * preference cache initialised, and tracking silently stopped for them.
     *
     * INSERT OR IGNORE keeps this a no-op after the first run and never touches rows
     * the user has customised (enabled, disabled, blocked).
     */
    private fun seedDefaultAppPreferences() {
        applicationScope.launch {
            try {
                appPreferenceRepository.seedDefaultAppsIfNeeded()
            } catch (e: Exception) {
                // Bookkeeping must never affect start-up.
                android.util.Log.w("TempoApplication", "Failed to seed default app preferences", e)
            }
        }
    }

    /**
     * `Application.onCreate` runs exactly once per process, so a start observed here is always
     * a cold start.
     *
     * `startupMillis` measures Tempo's own synchronous start-up work — a regression guard for
     * anything blocking added to `onCreate` — rather than time-to-first-frame, which this
     * lifecycle callback genuinely cannot see. `listenerReady` is the more valuable signal:
     * it reports whether tracking was actually alive when the user opened the app, reusing the
     * heartbeat the health worker already relies on instead of duplicating permission checks.
     */
    private fun reportAppStarted(initStartedAt: Long) {
        // Measured on the main thread before anything async: this is exactly Tempo's own
        // synchronous start-up work, which is what the metric guards — the heartbeat read
        // below must not be counted into it.
        val startupMillis = System.currentTimeMillis() - initStartedAt
        applicationScope.launch {
            // The heartbeat read is disk I/O, so it stays off the main thread; the value
            // still reflects the state at open time.
            val heartbeat = TrackingServiceHeartbeat.snapshot(this@TempoApplication)
            analyticsTracker.track(
                AppStarted(
                    startType = AppStartType.COLD,
                    startupMillis = startupMillis,
                    listenerReady = heartbeat.hasEverStarted() && !heartbeat.shouldRequestRebind(),
                ),
            )
        }
    }

    /**
     * Reports when the on-disk schema version advances.
     *
     * This is the honest signal available without instrumenting every Room migration
     * individually, and it answers the question that actually matters when shipping one: how
     * many users have run it. Only the version pair is reported — a migration's duration and
     * success are not knowable from here, and guessing at them would put numbers in the
     * dashboard that nothing measured.
     */
    private fun reportDatabaseVersionChange() {
        applicationScope.launch {
            try {
                val lastSeen = dataStore.data.first()[lastSeenDbVersionKey]
                if (lastSeen != null && lastSeen != AppDatabase.VERSION) {
                    analyticsTracker.track(
                        DbMigration(fromVersion = lastSeen, toVersion = AppDatabase.VERSION),
                    )
                }
                dataStore.edit { it[lastSeenDbVersionKey] = AppDatabase.VERSION }
            } catch (e: Exception) {
                // Bookkeeping must never affect start-up.
                android.util.Log.w("TempoApplication", "Failed to record schema version", e)
            }
        }
    }

    private fun loadUserKnownArtists() {
        applicationScope.launch {
            try {
                val names = userKnownArtistDao.getAllNormalizedNames().toSet()
                ArtistParser.loadUserKnownBands(names)

                // One-time data repair; no-ops once applied (versioned flag).
                try {
                    artistRepairService.runRepairIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.w("TempoApplication", "Artist repair invocation failed", e)
                }

                // One-time repair of legacy JSON-array values in list columns
                try {
                    listColumnRepairService.runRepairIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.w("TempoApplication", "List column repair invocation failed", e)
                }
            } catch (e: Exception) {
                android.util.Log.w("TempoApplication", "Failed to load user known artists", e)
            }
        }
    }

    /**
     * Reconcile persisted automatic-backup settings on every process start.
     *
     * This is important when upgrading from the old combined Drive/local worker:
     * users who already selected Daily/Weekly/Monthly must receive the dedicated
     * LocalBackupWorker without having to revisit the Backup & Restore screen.
     * ExistingPeriodicWorkPolicy.UPDATE keeps healthy periodic cadence while also
     * repairing a missing WorkManager registration after an app/process restart.
     */
    private fun reconcileAutomaticBackupSchedules() {
        applicationScope.launch {
            try {
                val settings = backupSettingsManager.settings.first()

                if (settings.backupInterval == BackupInterval.MANUAL) {
                    LocalBackupWorker.cancel(this@TempoApplication)
                    DriveBackupWorker.cancel(this@TempoApplication)
                    return@launch
                }

                // Only the persisted SAF write grant is checked here. A valid
                // external/SD-card provider can be temporarily unavailable during
                // boot; do not erase the user's folder choice merely because the
                // provider cannot be queried at this exact moment.
                if (LocalBackupStorage.hasSelectedDirectory(this@TempoApplication)) {
                    LocalBackupWorker.schedule(
                        this@TempoApplication,
                        settings.backupInterval.hours,
                    )
                } else {
                    if (LocalBackupStorage.getSelectedDirectoryUri(this@TempoApplication) != null) {
                        LocalBackupStorage.clearSelectedDirectory(this@TempoApplication)
                    }
                    LocalBackupWorker.cancel(this@TempoApplication)
                }

                if (settings.isGoogleDriveEnabled) {
                    DriveBackupWorker.schedule(
                        this@TempoApplication,
                        settings.backupInterval.hours,
                        settings.wifiOnly,
                    )
                } else {
                    DriveBackupWorker.cancel(this@TempoApplication)
                }
            } catch (e: Exception) {
                // Backup schedule repair is best-effort and must never prevent the
                // application process from starting.
                android.util.Log.w(
                    "TempoApplication",
                    "Failed to reconcile automatic backup schedules",
                    e,
                )
            }
        }
    }

    private fun scheduleBackgroundWorkDeferred() {
        backgroundExecutor.execute {
            Thread.sleep(500)
            Handler(Looper.getMainLooper()).post {
                scheduleBackgroundWork()
            }
        }
    }

    private fun scheduleBackgroundWork() {
        // Self-heal: if a previous listener-restart attempt left the tracking
        // component DISABLED (process died mid-toggle), re-enable it now so
        // tracking recovers without a reinstall.
        me.avinas.tempo.service.MusicTrackingService
            .ensureComponentEnabled(this)

        ServiceHealthWorker.schedule(this)

        EnrichmentWorker.schedulePeriodic(this)

        EnrichmentWorker.enqueueImmediate(this)

        me.avinas.tempo.worker.GamificationWorker
            .enqueuePeriodicRefresh(this)
        me.avinas.tempo.worker.GamificationWorker
            .enqueueImmediateRefresh(this)

        // Local-only read; the resulting aggregate is queued and uploaded by the flush worker.
        ListeningActivityWorker.schedule(this)

        scheduleSpotifyPollingIfEnabled()

        // Restore the independent, opt-in history schedule after a restart.
        applicationScope.launch {
            try {
                val settings = driveHistorySyncSettingsManager.settings.first()
                if (settings.enabled) {
                    DriveHistorySyncWorker.schedule(this@TempoApplication)
                } else {
                    DriveHistorySyncWorker.cancel(this@TempoApplication)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TempoApplication", "Failed to restore Drive history sync schedule", e)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            SpotlightUnlockWorker.scheduleWeekly(this)
            ChallengeWorker.scheduleDaily(this)
        }, 30_000L)
    }

    /**
     * Owns the upload worker's lifecycle from the single source of truth: the consent flag.
     *
     * Every path that flips the gate — the Settings toggle, the Home disclosure card's
     * turn-off, a data wipe — gets the same treatment here: a closed gate drops anything
     * buffered and cancels the uploader immediately, and an open one re-registers it.
     *
     * This collects [AnalyticsConsent.collectionAllowed] rather than `isEnabled` so that the
     * disclosure being rendered is what arms the uploader. That matters for first-run
     * latency: the periodic worker's first execution is scheduled a full flex-adjusted
     * interval ahead (5h for the 6h/1h configuration), so without the immediate flush below
     * a new install would show "Waiting for the first event" for hours. See
     * [AnalyticsFlushWorker.enqueueImmediate].
     *
     * Scheduling here rather than in [scheduleBackgroundWork] is also what stops every
     * app start from resurrecting a worker that the user's opt-out had cancelled.
     */
    private fun observeAnalyticsConsent() {
        applicationScope.launch {
            analyticsConsent.collectionAllowed.collect { allowed ->
                if (allowed) {
                    AnalyticsFlushWorker.schedule(this@TempoApplication)
                    AnalyticsFlushWorker.enqueueImmediate(this@TempoApplication)
                } else {
                    analyticsTracker.purge()
                    AnalyticsFlushWorker.cancel(this@TempoApplication)
                }
            }
        }
    }

    /** Schedules Spotify polling worker if Spotify-API-Only mode is enabled. */
    private fun scheduleSpotifyPollingIfEnabled() {
        applicationScope.launch {
            try {
                val prefs = userPreferencesDao.getSync()
                if (prefs?.spotifyApiOnlyMode == true) {
                    Handler(Looper.getMainLooper()).post {
                        SpotifyPollingWorker.schedule(this@TempoApplication)
                    }
                }
            } catch (e: Exception) {
                // Ignore - worker will be scheduled when user enables mode
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        backgroundExecutor.shutdown()
    }
}
