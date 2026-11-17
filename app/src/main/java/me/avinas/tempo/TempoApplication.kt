package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsConsent
import me.avinas.tempo.data.analytics.AnalyticsGate
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.CrashSignatureRecorder
import me.avinas.tempo.data.analytics.InstallOnce
import me.avinas.tempo.data.analytics.StartupSlow
import me.avinas.tempo.data.drive.BackupInterval
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.LocalBackupStorage
import me.avinas.tempo.data.local.dao.UserKnownArtistDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.repository.AppPreferenceRepository
import me.avinas.tempo.service.TrackingServiceHeartbeat
import me.avinas.tempo.ui.onboarding.dataStore
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.utils.FrameworkRaceGuard
import me.avinas.tempo.utils.ShareUtils
import me.avinas.tempo.worker.AnalyticsFlushWorker
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.DriveBackupWorker
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.ListeningActivityWorker
import me.avinas.tempo.worker.LocalBackupWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import me.avinas.tempo.worker.SpotlightUnlockWorker
import me.avinas.tempo.worker.StatsPrecomputeWorker
import java.util.concurrent.Executors
import javax.inject.Inject

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
    lateinit var crashSignatureRecorder: CrashSignatureRecorder

    @Inject
    lateinit var analyticsTracker: AnalyticsTracker

    @Inject
    lateinit var analyticsConsent: AnalyticsConsent

    @Inject
    lateinit var analyticsDailyLedger: me.avinas.tempo.data.analytics.AnalyticsDailyLedger

    @Inject
    lateinit var analyticsInstallId: me.avinas.tempo.data.analytics.AnalyticsInstallId

    @Inject
    lateinit var analyticsFeatureLedger: me.avinas.tempo.data.analytics.AnalyticsFeatureLedger

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val installOnceReportedKey = booleanPreferencesKey("analytics_install_once")

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
        observeShareCompletion()

        reportAppStarted(initStartedAt)
        reportInstallOnce()
    }

    /**
     * Records a share once the chooser reports a picked target.
     *
     * Opening the system share sheet is not a share: the user can back out, and a
     * third-party target never reports a result. The reliable signal is the chooser's
     * `EXTRA_CHOSEN_COMPONENT_INTENT_SENDER` callback. Mark `share_last_success` and
     * clear the nudge's dismiss counter there, not when the sheet opens.
     *
     * Registered for the process, not per screen: shares start from stats, the home nudge and
     * the spotlight canvases, and all of them should count. The receiver is not exported and
     * the sender pending intent is package-scoped, so nothing else on the device can fake one.
     */
    private fun observeShareCompletion() {
        runCatching {
            ShareUtils.registerChosenTargetListener(
                this,
                ShareUtils.ChosenTargetReceiver { target ->
                    // A null target means the chooser could not resolve one; the user still
                    // picked something, but there is no evidence the share was delivered.
                    if (target != null) {
                        applicationScope.launch {
                            runCatching {
                                dataStore.edit { prefs ->
                                    prefs[longPreferencesKey("share_last_success")] =
                                        System.currentTimeMillis()
                                    // A share is positive feedback: the "dismissed three times"
                                    // rule should not outlive it, or someone who ignored the
                                    // nudge twice could never see it again after sharing.
                                    prefs[intPreferencesKey("share_nudge_dismiss_count")] = 0
                                }
                            }
                        }
                    }
                },
            )
        }.onFailure { error ->
            // Registration failing must never take start-up down; the worst case is that a
            // share goes unrecorded.
            android.util.Log.w("TempoApplication", "Share completion listener unavailable", error)
        }
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
     * lifecycle callback genuinely cannot see.
     */
    private fun reportAppStarted(initStartedAt: Long) {
        // Measured on the main thread before anything async: this is exactly Tempo's own
        // synchronous start-up work, which is what the metric guards.
        val startupMillis = System.currentTimeMillis() - initStartedAt

        // Only the exception is worth sending. A healthy start carries no information, and
        // reporting it on every process start — including the ones Android makes for background
        // work — was roughly 28% of all traffic, for a value that never varied.
        //
        // The heartbeat read that used to live here is gone with it: `listener_ready` is now
        // part of the daily activity report, so app start no longer touches disk at all.
        if (startupMillis < StartupSlow.THRESHOLD_MILLIS) return

        applicationScope.launch {
            analyticsTracker.track(StartupSlow(startupMillis))
        }
    }

    /**
     * Reports the install itself, once ever.
     *
     * Every other event is per day or per outcome, which left two questions unanswerable: whether
     * "active users fell" meant people left or merely that nobody new arrived, and which build a
     * device was running when it first appeared. One event per install, ever, closes both — about
     * as many events in total as the project has installs.
     *
     * The marker is written only after [AnalyticsTracker.canReport] agrees the event would be
     * queued. Writing it without a send would consume the once-ever slot while nothing left the
     * device, and a user who later turned reporting on would never be counted at all.
     */
    private fun reportInstallOnce() {
        applicationScope.launch {
            try {
                if (dataStore.data.first()[installOnceReportedKey] == true) return@launch
                if (!analyticsTracker.canReport()) return@launch

                analyticsTracker.track(
                    InstallOnce(
                        appVersion = BuildConfig.VERSION_NAME,
                        androidApi = android.os.Build.VERSION.SDK_INT,
                    ),
                )
                dataStore.edit { it[installOnceReportedKey] = true }
            } catch (e: Exception) {
                // Bookkeeping must never affect start-up.
                android.util.Log.w("TempoApplication", "Failed to record install", e)
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
     * Upgrading from the old combined Drive/local worker must hand users who already selected
     * Daily/Weekly/Monthly the dedicated LocalBackupWorker without a trip to the Backup & Restore
     * screen. ExistingPeriodicWorkPolicy.UPDATE keeps healthy periodic cadence while also
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
        // This used to hop back onto the main thread after the 500 ms deferral, which put a
        // PackageManager binder call, WorkManager's lazy initialisation and eight enqueues inside
        // the first-frame window. None of it needs the main thread.
        applicationScope.launch {
            delay(500)
            scheduleBackgroundWork()
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

        // Warm the stats cache. Every recorded listening event invalidates the whole cache
        // (see RoomStatsRepository.onNewListeningEvent) and that cache is in-memory only, so the
        // first visit to Stats or Home after any listening would otherwise pay the full cold
        // aggregates. This worker exists to precompute them but was never scheduled anywhere —
        // `grep StatsPrecomputeWorker.` had no call sites, so it never ran.
        StatsPrecomputeWorker.schedule(this)

        scheduleSpotifyPollingIfEnabled()

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
        // A build that cannot report has no uploader to register and no buffer worth dropping —
        // and because its notice never renders, the gate can never open, so subscribing would
        // mean running the opt-out branch forever against a decision that will not change.
        if (!AnalyticsGate.isBuildConfigured(
                endpoint = BuildConfig.ANALYTICS_ENDPOINT,
                isDebug = BuildConfig.DEBUG,
                debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
            )
        ) {
            return
        }

        applicationScope.launch {
            analyticsConsent.collectionAllowed.collect { allowed ->
                if (allowed) {
                    AnalyticsFlushWorker.schedule(this@TempoApplication)
                    AnalyticsFlushWorker.enqueueImmediate(this@TempoApplication)
                } else {
                    analyticsTracker.purge()
                    AnalyticsFlushWorker.cancel(this@TempoApplication)

                    // Opting out deletes the install id as well as the unsent buffer, so
                    // everything already recorded becomes unlinkable to anything sent after
                    // turning reporting back on. Without this the same id would resume
                    // reporting into the old history, and "stop reporting" would have meant
                    // "pause for a while" rather than what it says.
                    //
                    // This is the write that made the loop: it changes the store the flow above
                    // reads. `distinctUntilChanged` on that flow is what makes it safe.
                    analyticsInstallId.reset()
                    analyticsDailyLedger.clear()
                    analyticsFeatureLedger.clear()
                }
            }
        }
    }

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
