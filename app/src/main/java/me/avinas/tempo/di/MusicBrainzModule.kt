package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.interceptors.RateLimitInterceptor
import me.avinas.tempo.data.remote.interceptors.RetryInterceptor
import me.avinas.tempo.data.remote.interceptors.UserAgentInterceptor
import me.avinas.tempo.data.remote.musicbrainz.CoverArtArchiveApi
import me.avinas.tempo.data.remote.musicbrainz.MusicBrainzApi
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
 * Hilt module providing MusicBrainz and Cover Art Archive API clients.
 */
@Module
@InstallIn(SingletonComponent::class)
object MusicBrainzModule {

    /**
     * OkHttpClient configured for MusicBrainz API:
     * - Rate limiting (1 req/sec)
     * - Retry with exponential backoff
     * - Custom User-Agent header
     */
    @Provides
    @Singleton
    @Named("musicbrainz")
    fun provideMusicBrainzOkHttpClient(
        rateLimitInterceptor: RateLimitInterceptor,
        retryInterceptor: RetryInterceptor,
        userAgentInterceptor: UserAgentInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(rateLimitInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrofit client for MusicBrainz API.
     */
    @Provides
    @Singleton
    @Named("musicbrainz")
    fun provideMusicBrainzRetrofit(
        @Named("musicbrainz") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(MusicBrainzApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * MusicBrainz API interface.
     */
    @Provides
    @Singleton
    fun provideMusicBrainzApi(
        @Named("musicbrainz") retrofit: Retrofit
    ): MusicBrainzApi {
        return retrofit.create(MusicBrainzApi::class.java)
    }

    /**
     * OkHttpClient for Cover Art Archive (no rate limiting needed).
     */
    @Provides
    @Singleton
    @Named("coverart")
    fun provideCoverArtOkHttpClient(
        userAgentInterceptor: UserAgentInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Retrofit client for Cover Art Archive.
     */
    @Provides
    @Singleton
    @Named("coverart")
    fun provideCoverArtRetrofit(
        @Named("coverart") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(CoverArtArchiveApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * Cover Art Archive API interface.
     */
    @Provides
    @Singleton
    fun provideCoverArtArchiveApi(
        @Named("coverart") retrofit: Retrofit
    ): CoverArtArchiveApi {
        return retrofit.create(CoverArtArchiveApi::class.java)
    }
}
