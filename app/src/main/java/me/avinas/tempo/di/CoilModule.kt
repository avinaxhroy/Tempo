package me.avinas.tempo.di

import android.content.Context
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
import javax.inject.Singleton

/**
 * Provides an optimized Coil ImageLoader for the entire application.
 * 
 * Optimizations:
 * - Memory cache: 15% of available memory for fast in-memory access
 * - Disk cache: 50MB for album art persistence across sessions
 * - Crossfade: Short 150ms transition for perceived performance
 * - Caching policies: Aggressive caching for network images
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            // Memory cache: ~15% of available memory
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15)
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk cache: 50MB for album art
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB
                    .build()
            }
            // Use aggressive caching for network images
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // Short crossfade for perceived performance
            .crossfade(150)
            // Respect cache headers for proper invalidation
            .respectCacheHeaders(true)
            // Debug logging only in debug builds
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
}
