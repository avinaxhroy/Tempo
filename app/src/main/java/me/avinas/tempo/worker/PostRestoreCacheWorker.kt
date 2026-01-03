package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

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
        private const val KEY_URLS = "urls"
        private const val PARALLELISM = 4
        private const val MAX_INITIAL_CACHE = 200
        
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
            
            val data = Data.Builder()
                .putStringArray(KEY_URLS, prioritizedUrls.toTypedArray())
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
        val urls = inputData.getStringArray(KEY_URLS)?.toList() 
            ?: return@withContext Result.success()
        
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
                        
                        val result = imageLoader.execute(request)
                        if (result.drawable != null) {
                            successCount.incrementAndGet()
                        } else {
                            failCount.incrementAndGet()
                        }
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
        Result.success(
            Data.Builder()
                .putInt("success", successCount.get())
                .putInt("failed", failCount.get())
                .build()
        )
    }
}
