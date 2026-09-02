package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.BackupStatus
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.data.local.dao.UserKnownArtistDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.DriveHistorySyncWorker
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import me.avinas.tempo.worker.SpotlightUnlockWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Application class configuring Hilt DI, WorkManager HiltWorkerFactory,
 * and Coil SingletonImageLoader.
 */
@HiltAndroidApp
class TempoApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var userPreferencesDao: UserPreferencesDao

    @Inject
    lateinit var userKnownArtistDao: UserKnownArtistDao

    @Inject
    lateinit var artistRepairService: me.avinas.tempo.data.repository.ArtistRepairService

    @Inject
    lateinit var listColumnRepairService: me.avinas.tempo.data.repository.ListColumnRepairService

    @Inject
    lateinit var backupSettingsManager: BackupSettingsManager

    @Inject
    lateinit var driveHistorySyncSettingsManager: DriveHistorySyncSettingsManager

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        loadUserKnownArtists()
        scheduleBackgroundWorkDeferred()
    }

    private fun loadUserKnownArtists() {
        applicationScope.launch {
            try {
                val names = userKnownArtistDao.getAllNormalizedNames().toSet()
                ArtistParser.loadUserKnownBands(names)

                try {
                    artistRepairService.runRepairIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.w("TempoApplication", "Artist repair invocation failed", e)
                }

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

    private fun scheduleBackgroundWorkDeferred() {
        backgroundExecutor.execute {
            Thread.sleep(500)
            Handler(Looper.getMainLooper()).post { scheduleBackgroundWork() }
        }
    }

    private fun scheduleBackgroundWork() {
        me.avinas.tempo.service.MusicTrackingService.ensureComponentEnabled(this)

        ServiceHealthWorker.schedule(this)
        EnrichmentWorker.schedulePeriodic(this)
        EnrichmentWorker.enqueueImmediate(this)
        me.avinas.tempo.worker.GamificationWorker.enqueuePeriodicRefresh(this)
        me.avinas.tempo.worker.GamificationWorker.enqueueImmediateRefresh(this)
        scheduleSpotifyPollingIfEnabled()

        applicationScope.launch {
            try {
                val settings = backupSettingsManager.settings.first()
                if (settings.lastBackupStatus == BackupStatus.IN_PROGRESS) {
                    backupSettingsManager.updateLastBackup(BackupStatus.FAILED)
                }
            } catch (_: Exception) {
                // Non-critical.
            }
        }

        // Restore the user's cross-device sync schedule after process/device
        // restart. The setting is opt-in and defaults to disabled.
        applicationScope.launch {
            try {
                val settings = driveHistorySyncSettingsManager.settings.first()
                Handler(Looper.getMainLooper()).post {
                    if (settings.enabled) {
                        DriveHistorySyncWorker.schedule(this@TempoApplication)
                    } else {
                        DriveHistorySyncWorker.cancel(this@TempoApplication)
                    }
                }
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
     * Check if Spotify-API-Only mode is enabled and schedule polling if so.
     * This ensures the worker resumes after app restart.
     */
    private fun scheduleSpotifyPollingIfEnabled() {
        applicationScope.launch {
            try {
                val prefs = userPreferencesDao.getSync()
                if (prefs?.spotifyApiOnlyMode == true) {
                    Handler(Looper.getMainLooper()).post {
                        SpotifyPollingWorker.schedule(this@TempoApplication)
                    }
                }
            } catch (_: Exception) {
                // Worker will be scheduled when the user enables the mode.
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        backgroundExecutor.shutdown()
    }
}
