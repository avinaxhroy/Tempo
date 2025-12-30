package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.spotify.SpotifyApi
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
 * Hilt module providing Spotify API client and related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {

    /**
     * OkHttpClient configured for Spotify API.
     * 
     * Note: Authentication header is added per-request, not via interceptor,
     * because the token can change (refresh) during the app lifecycle.
     */
    @Provides
    @Singleton
    @Named("spotify")
    fun provideSpotifyOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Add retry on connection failure
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Retrofit client for Spotify Web API.
     */
    @Provides
    @Singleton
    @Named("spotify")
    fun provideSpotifyRetrofit(
        moshi: Moshi,
        @Named("spotify") okHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(SpotifyApi.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
    }

    /**
     * Spotify API service.
     */
    @Provides
    @Singleton
    fun provideSpotifyApi(@Named("spotify") retrofit: Retrofit): SpotifyApi {
        return retrofit.create(SpotifyApi::class.java)
    }
}
