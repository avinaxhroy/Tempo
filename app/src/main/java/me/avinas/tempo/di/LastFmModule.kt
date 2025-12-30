package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.interceptors.RetryInterceptor
import me.avinas.tempo.data.remote.lastfm.LastFmApi
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
 * Hilt module providing Last.fm API client.
 * 
 * Last.fm provides excellent tag/genre data for tracks and artists.
 * Used as a fallback when Spotify audio features are unavailable.
 */
@Module
@InstallIn(SingletonComponent::class)
object LastFmModule {

    /**
     * OkHttpClient configured for Last.fm API:
     * - Light rate limiting (~5 req/sec is fine)
     * - Retry with backoff
     */
    @Provides
    @Singleton
    @Named("lastfm")
    fun provideLastFmOkHttpClient(
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
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrofit client for Last.fm API.
     */
    @Provides
    @Singleton
    @Named("lastfm")
    fun provideLastFmRetrofit(
        @Named("lastfm") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(LastFmApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * Last.fm API interface.
     */
    @Provides
    @Singleton
    fun provideLastFmApi(
        @Named("lastfm") retrofit: Retrofit
    ): LastFmApi {
        return retrofit.create(LastFmApi::class.java)
    }
}
