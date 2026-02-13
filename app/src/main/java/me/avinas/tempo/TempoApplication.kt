package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import me.avinas.tempo.worker.SpotlightUnlockWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Tempo Application with Hilt DI and custom WorkManager configuration.
 * 
 * Implements Configuration.Provider to use HiltWorkerFactory for injecting
 * dependencies into Workers.
 * 
 * Implements SingletonImageLoader.Factory to provide our optimized ImageLoader singleton
 * to all Compose and Views in the app.
 */
@HiltAndroidApp
class TempoApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var imageLoader: ImageLoader
    
    @Inject
    lateinit var userPreferencesDao: UserPreferencesDao
    
    // Background executor for non-critical initialization
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    // Coroutine scope for app-level async work
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    /**
     * Provide our Hilt-injected ImageLoader singleton to the entire app.
     * This ensures all image loading uses our optimized cache configuration.
     */
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader
    }

    override fun onCreate() {
        super.onCreate()
        
        // Schedule periodic background workers after a short delay
        // This prevents blocking the main thread during app startup
        // and reduces frame drops during initial composition
        scheduleBackgroundWorkDeferred()
    }
    
    /**
     * Schedule background work with a small delay to avoid blocking startup.
     * WorkManager operations involve database access and can cause frame drops
     * if executed synchronously during onCreate.
     */
    private fun scheduleBackgroundWorkDeferred() {
        // Use a background thread with a small delay to let the UI render first
        backgroundExecutor.execute {
            // Small delay to let initial frames render
            Thread.sleep(500)
            
            // Schedule work on main thread (WorkManager requires it)
            Handler(Looper.getMainLooper()).post {
                scheduleBackgroundWork()
            }
        }
    }

    private fun scheduleBackgroundWork() {
        // Schedule service health monitoring
        ServiceHealthWorker.schedule(this)
        
        // Schedule periodic MusicBrainz enrichment
        EnrichmentWorker.schedulePeriodic(this)
        
        // Trigger immediate enrichment to backfill any missing album art
        EnrichmentWorker.enqueueImmediate(this)
        
        // Schedule weekly Spotlight story unlock checks (lightweight, battery-optimized)
        SpotlightUnlockWorker.scheduleWeekly(this)
        
        // Schedule gamification refresh (XP, levels, badges)
        me.avinas.tempo.worker.GamificationWorker.enqueuePeriodicRefresh(this)
        me.avinas.tempo.worker.GamificationWorker.enqueueImmediateRefresh(this)
        
        // Schedule Spotify polling if API-Only mode is enabled
        scheduleSpotifyPollingIfEnabled()
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

