package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.remote.deezer.DeezerApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeezerModule {

    @Provides
    @Singleton
    fun provideDeezerApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): DeezerApi {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeezerApi::class.java)
    }
}
