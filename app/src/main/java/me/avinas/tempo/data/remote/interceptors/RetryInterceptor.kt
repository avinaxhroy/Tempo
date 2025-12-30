package me.avinas.tempo.data.remote.interceptors

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * OkHttp Interceptor that implements retry logic with exponential backoff.
 * 
 * Handles transient failures like:
 * - 429 Too Many Requests (rate limited)
 * - 503 Service Unavailable
 * - 504 Gateway Timeout
 * - Network errors
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "RetryInterceptor"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val BACKOFF_MULTIPLIER = 2.0
        
        private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null
        var retryCount = 0

        while (retryCount <= MAX_RETRIES) {
            try {
                // Close previous response if retrying
                response?.close()
                
                response = chain.proceed(request)
                
                // Check if we should retry based on status code
                if (response.isSuccessful || !shouldRetry(response.code)) {
                    return response
                }
                
                Log.w(TAG, "Request failed with ${response.code}, retry $retryCount/$MAX_RETRIES")
                
                // Get retry-after header if present (for 429 responses)
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val backoffMs = if (retryAfter != null) {
                    retryAfter * 1000 // Convert seconds to ms
                } else {
                    calculateBackoff(retryCount)
                }
                
                response.close()
                
                if (retryCount < MAX_RETRIES) {
                    Log.d(TAG, "Backing off for ${backoffMs}ms before retry")
                    Thread.sleep(backoffMs)
                }
                
                retryCount++
                
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Network error on attempt $retryCount: ${e.message}")
                
                response?.close()
                
                if (retryCount < MAX_RETRIES) {
                    val backoffMs = calculateBackoff(retryCount)
                    Log.d(TAG, "Backing off for ${backoffMs}ms before retry")
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
                
                retryCount++
            }
        }

        // All retries exhausted
        return response ?: throw (lastException ?: IOException("Unknown error after $MAX_RETRIES retries"))
    }

    private fun shouldRetry(statusCode: Int): Boolean {
        return statusCode in RETRYABLE_STATUS_CODES
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = INITIAL_BACKOFF_MS * BACKOFF_MULTIPLIER.pow(retryCount.toDouble())
        return min(backoff.toLong(), MAX_BACKOFF_MS)
    }
}
