package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.itunes.iTunesApi
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
 * Hilt module providing iTunes Search API client.
 * 
 * iTunes Search API is completely free with no authentication required.
 * Rate limit: ~20 requests per minute.
 */
@Module
@InstallIn(SingletonComponent::class)
object iTunesModule {

    /**
     * OkHttpClient configured for iTunes Search API.
     * No special interceptors needed - API is very simple.
     */
    @Provides
    @Singleton
    @Named("itunes")
    fun provideiTunesOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrofit client for iTunes Search API.
     */
    @Provides
    @Singleton
    @Named("itunes")
    fun provideiTunesRetrofit(
        @Named("itunes") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(iTunesApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * iTunes Search API interface.
     */
    @Provides
    @Singleton
    fun provideiTunesApi(
        @Named("itunes") retrofit: Retrofit
    ): iTunesApi {
        return retrofit.create(iTunesApi::class.java)
    }
}
