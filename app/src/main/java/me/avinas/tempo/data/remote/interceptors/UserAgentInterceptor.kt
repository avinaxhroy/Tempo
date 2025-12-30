package me.avinas.tempo.data.remote.interceptors

import me.avinas.tempo.data.remote.musicbrainz.MusicBrainzApi
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Interceptor that adds User-Agent header to all requests.
 * 
 * MusicBrainz API requires a descriptive User-Agent header identifying
 * the application making requests.
 */
@Singleton
class UserAgentInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", MusicBrainzApi.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        
        return chain.proceed(request)
    }
}
