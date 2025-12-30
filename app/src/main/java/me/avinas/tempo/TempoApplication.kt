package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Tempo Application with Hilt DI and custom WorkManager configuration.
 * 
 * Implements Configuration.Provider to use HiltWorkerFactory for injecting
 * dependencies into Workers.
 */
@HiltAndroidApp
class TempoApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    // Background executor for non-critical initialization
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
    }
    
    override fun onTerminate() {
        super.onTerminate()
        backgroundExecutor.shutdown()
    }
}

