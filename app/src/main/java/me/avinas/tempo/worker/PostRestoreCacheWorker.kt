package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import me.avinas.tempo.R

/**
 * Worker to pre-cache hotlinked images after backup restore.
 * 
 * Strategy: Cache top 200 most relevant images immediately.
 * - Top songs, artists, albums from stats
 * - Recent listening history
 * 
 * Additional images are cached on-demand when user navigates deeper.
 */
@HiltWorker
class PostRestoreCacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val imageLoader: ImageLoader
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "PostRestoreCacheWorker"
        private const val KEY_URLS = "urls" // Kept for backward compatibility
        private const val KEY_FILE_PATH = "urls_file_path"
        private const val PARALLELISM = 4
        private const val MAX_INITIAL_CACHE = 200
        private const val NOTIFICATION_CHANNEL_ID = "restore_cache_worker"
        private const val NOTIFICATION_ID = 3003
        
        /**
         * Schedule caching for top 200 most relevant images.
         * Additional images can be cached on-demand.
         */
        fun schedule(context: Context, urls: List<String>) {
            // Limit to top 200 for initial cache
            val prioritizedUrls = urls.take(MAX_INITIAL_CACHE)
            
            if (prioritizedUrls.isEmpty()) {
                Log.i(TAG, "No URLs to cache")
                return
            }
            
            // Write URLs to a file to avoid WorkManager Data size limit (10KB)
            val cacheFile = java.io.File(context.cacheDir, "restore_image_cache_urls.txt")
            try {
                cacheFile.writeText(prioritizedUrls.joinToString("\n"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write cache urls file", e)
                return
            }
            
            val data = Data.Builder()
                .putString(KEY_FILE_PATH, cacheFile.absolutePath)
                .build()
            
            val request = OneTimeWorkRequestBuilder<PostRestoreCacheWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("post_restore_cache")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "post_restore_cache",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            
            Log.i(TAG, "Scheduled caching of ${prioritizedUrls.size} images")
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Try to read from file first (new method)
        val filePath = inputData.getString(KEY_FILE_PATH)
        var urls = if (filePath != null) {
            val file = java.io.File(filePath)
            if (file.exists()) {
                try {
                    file.readLines().filter { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read cache urls file", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            // Fallback to Data array (old method)
            inputData.getStringArray(KEY_URLS)?.toList() ?: emptyList()
        }
        
        if (urls.isEmpty()) {
            Log.i(TAG, "No URLs to cache")
            return@withContext Result.success()
        }
        
        Log.i(TAG, "Starting pre-cache of ${urls.size} images")
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        // Process in batches to avoid overwhelming the network
        urls.chunked(PARALLELISM).forEach { batch ->
            batch.map { url ->
                async {
                    try {
                        val request = ImageRequest.Builder(applicationContext)
                            .data(url)
                            .memoryCachePolicy(CachePolicy.DISABLED) // Only disk cache
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        
                        // Execute the request - if it succeeds, increment success count
                        // In Coil 3, successful execution doesn't throw an exception
                        imageLoader.execute(request)
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cache: $url", e)
                        failCount.incrementAndGet()
                    }
                }
            }.awaitAll()
            
            // Update progress
            setProgress(
                Data.Builder()
                    .putInt("cached", successCount.get() + failCount.get())
                    .putInt("total", urls.size)
                    .build()
            )
        }
        
        Log.i(TAG, "Pre-cache complete: ${successCount.get()} success, ${failCount.get()} failed")
        
        // We don't delete the file to allow for retries if needed, 
        // and it gets overwritten on next schedule anyway.
        
        Result.success(
            Data.Builder()
                .putInt("success", successCount.get())
                .putInt("failed", failCount.get())
                .build()
        )
    }
    
    /**
     * Required for expedited work on Android 10 (SDK 29).
     * Returns ForegroundInfo with notification when work runs as foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Image Caching",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Caching images")
            .setContentText("Downloading album artwork...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
