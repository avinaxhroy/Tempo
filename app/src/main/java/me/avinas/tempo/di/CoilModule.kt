package me.avinas.tempo.di

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.BuildConfig
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

private const val TAG = "CoilModule"

/**
 * Qualifier for the image-specific OkHttpClient.
 * This client has aggressive caching configured specifically for album art.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageClient

/**
 * Qualifier for the image-specific OkHttp Cache.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageCache

/**
 * Provides an optimized Coil ImageLoader for the entire application.
 * 
 * This implements a multi-layer caching strategy:
 * 
 * 1. **Memory Cache (15% of RAM)**: Fastest access for recently viewed images
 * 2. **Coil Disk Cache (100MB)**: Decoded images ready for display
 * 3. **OkHttp Cache (50MB)**: Raw HTTP responses to avoid network requests entirely
 * 
 * Key optimizations:
 * - Ignores server cache headers to force aggressive caching
 * - Uses INEXACT precision to reuse cached images across different sizes
 * - Custom OkHttp interceptor to add long cache lifetime to responses
 * - Strong references in memory cache to prevent premature eviction
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    /**
     * OkHttp cache for raw HTTP responses.
     * This caches the network response BEFORE Coil processes it.
     */
    @Provides
    @Singleton
    @ImageCache
    fun provideImageOkHttpCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, "okhttp_image_cache")
        return Cache(cacheDir, 50L * 1024 * 1024) // 50MB OkHttp cache
    }

    /**
     * Custom interceptor that forces aggressive caching.
     * This intercepts responses and rewrites cache headers to ensure
     * images are cached for a long time regardless of server headers.
     */
    private fun createForceCacheInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            
            // Log cache state in debug builds
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Image request: ${request.url}")
            }
            
            val response = chain.proceed(request)
            
            // Force cache the response for 30 days
            // This overrides any cache headers from the server
            val cacheControl = CacheControl.Builder()
                .maxAge(30, TimeUnit.DAYS)
                .maxStale(365, TimeUnit.DAYS)
                .build()
            
            response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Cache-Control")
                .removeHeader("Expires")
                .header("Cache-Control", cacheControl.toString())
                .build()
        }
    }

    /**
     * OkHttpClient configured specifically for image loading with caching.
     * Uses @ImageClient qualifier to avoid conflict with API OkHttpClient.
     */
    @Provides
    @Singleton
    @ImageClient
    fun provideImageOkHttpClient(
        @ImageCache cache: Cache
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(cache)
            // Add our force-cache interceptor as a network interceptor
            // This ensures it runs AFTER the cache check, modifying responses
            .addNetworkInterceptor(createForceCacheInterceptor())
            // Timeouts for slow CDNs
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // Follow redirects (common for CDNs)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @ImageClient okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // Use our custom OkHttpClient with caching
            .okHttpClient(okHttpClient)
            // Memory cache: ~15% of available memory
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            // Disk cache: 100MB for decoded album art
            // This is separate from the OkHttp cache and stores decoded images
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB
                    .build()
            }
            // Enable all caching policies
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // Short crossfade for perceived performance
            .crossfade(150)
            // CRITICAL: Ignore HTTP cache headers from the original request
            // Our interceptor adds proper cache headers, but this ensures
            // Coil doesn't override our caching decisions
            .respectCacheHeaders(false)
            // Allow RGB_565 for lower memory usage on older devices
            .allowRgb565(true)
            // Debug logging only in debug builds
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
}
