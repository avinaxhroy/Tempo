package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.interceptors.RetryInterceptor
import me.avinas.tempo.data.remote.reccobeats.ReccoBeatsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module providing ReccoBeats API client.
 * 
 * ReccoBeats is a FREE API that provides audio features similar to Spotify's deprecated endpoint.
 * No authentication required - just make API calls!
 * 
 * Used as a fallback when:
 * 1. Spotify is not connected
 * 2. Spotify's audio features API returns 403 (deprecated for third-party apps)
 * 
 * Key benefits:
 * - FREE, no API key required
 * - Same audio features format as Spotify
 * - Supports Spotify IDs directly
 * - Can analyze uploaded audio files
 * 
 * Rate limiting: HTTP 429 if exceeded, handled with retry + backoff
 */
@Module
@InstallIn(SingletonComponent::class)
object ReccoBeatsModule {

    /**
     * OkHttpClient configured for ReccoBeats API:
     * - No authentication interceptor needed
     * - Retry with backoff for rate limits
     * - Reasonable timeouts
     */
    @Provides
    @Singleton
    @Named("reccobeats")
    fun provideReccoBeatsOkHttpClient(
        retryInterceptor: RetryInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // Longer timeout for audio file uploads
            .build()
    }

    /**
     * Retrofit client for ReccoBeats API.
     */
    @Provides
    @Singleton
    @Named("reccobeats")
    fun provideReccoBeatsRetrofit(
        @Named("reccobeats") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ReccoBeatsApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * ReccoBeats API interface.
     */
    @Provides
    @Singleton
    fun provideReccoBeatsApi(
        @Named("reccobeats") retrofit: Retrofit
    ): ReccoBeatsApi {
        return retrofit.create(ReccoBeatsApi::class.java)
    }
}
