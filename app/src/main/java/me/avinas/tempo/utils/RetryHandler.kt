package me.avinas.tempo.utils

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Robust retry handler with exponential backoff and jitter.
 * 
 * Features:
 * - Exponential backoff to prevent thundering herd
 * - Jitter to distribute retries
 * - Configurable retry conditions
 * - Circuit breaker pattern support
 */
object RetryHandler {
    
    private const val TAG = "RetryHandler"
    
    /**
     * Execute an operation with retry logic.
     * 
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay before first retry
     * @param maxDelayMs Maximum delay between retries
     * @param factor Exponential backoff factor
     * @param jitterFactor Random jitter factor (0.0 to 1.0)
     * @param retryOn Predicate to determine if exception should trigger retry
     * @param onRetry Callback called before each retry
     * @param operation The operation to execute
     * @return Result of the operation or failure
     */
    suspend fun <T> withRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 30000,
        factor: Double = 2.0,
        jitterFactor: Double = 0.1,
        retryOn: (Exception) -> Boolean = { true },
        onRetry: suspend (attempt: Int, exception: Exception, nextDelayMs: Long) -> Unit = { _, _, _ -> },
        operation: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null
        
        repeat(maxRetries + 1) { attempt ->
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastException = e
                
                if (attempt >= maxRetries || !retryOn(e)) {
                    Log.e(TAG, "Operation failed after ${attempt + 1} attempts", e)
                    return Result.failure(e)
                }
                
                // Calculate delay with exponential backoff
                val nextDelay = calculateDelay(currentDelay, maxDelayMs, jitterFactor)
                
                Log.w(TAG, "Attempt ${attempt + 1}/$maxRetries failed, retrying in ${nextDelay}ms: ${e.message}")
                onRetry(attempt + 1, e, nextDelay)
                
                delay(nextDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }
        
        return Result.failure(lastException ?: RuntimeException("Unknown error"))
    }
    
    /**
     * Execute an operation with retry and timeout.
     */
    suspend fun <T> withRetryAndTimeout(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 30000,
        timeoutMs: Long = 60000,
        operation: suspend () -> T
    ): Result<T> {
        val startTime = System.currentTimeMillis()
        
        return withRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
            retryOn = { e ->
                // Don't retry if we've exceeded timeout
                val elapsed = System.currentTimeMillis() - startTime
                elapsed < timeoutMs
            },
            operation = operation
        )
    }
    
    /**
     * Calculate delay with jitter.
     */
    private fun calculateDelay(baseDelay: Long, maxDelay: Long, jitterFactor: Double): Long {
        val jitter = (baseDelay * jitterFactor * Random.nextDouble()).toLong()
        return min(baseDelay + jitter, maxDelay)
    }
}

/**
 * Circuit breaker for protecting against cascading failures.
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Circuit is tripped, requests fail immediately
 * - HALF_OPEN: Testing if service has recovered
 */
class CircuitBreaker(
    private val name: String,
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30000,
    private val halfOpenSuccessThreshold: Int = 2
) {
    companion object {
        private const val TAG = "CircuitBreaker"
    }
    
    private var state: State = State.CLOSED
    private var failureCount: Int = 0
    private var successCount: Int = 0
    private var lastFailureTime: Long = 0
    
    enum class State {
        CLOSED,    // Normal operation
        OPEN,      // Failing, reject requests
        HALF_OPEN  // Testing recovery
    }
    
    /**
     * Execute an operation through the circuit breaker.
     */
    suspend fun <T> execute(operation: suspend () -> T): Result<T> {
        if (!canExecute()) {
            Log.d(TAG, "[$name] Circuit is OPEN, rejecting request")
            return Result.failure(CircuitOpenException("Circuit breaker $name is open"))
        }
        
        return try {
            val result = operation()
            recordSuccess()
            Result.success(result)
        } catch (e: Exception) {
            recordFailure()
            Result.failure(e)
        }
    }
    
    /**
     * Check if the circuit allows execution.
     */
    @Synchronized
    fun canExecute(): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                // Check if reset timeout has passed
                if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                    state = State.HALF_OPEN
                    successCount = 0
                    Log.i(TAG, "[$name] Circuit transitioning to HALF_OPEN")
                    true
                } else {
                    false
                }
            }
            State.HALF_OPEN -> true
        }
    }
    
    /**
     * Record a successful operation.
     */
    @Synchronized
    private fun recordSuccess() {
        when (state) {
            State.HALF_OPEN -> {
                successCount++
                if (successCount >= halfOpenSuccessThreshold) {
                    state = State.CLOSED
                    failureCount = 0
                    Log.i(TAG, "[$name] Circuit CLOSED after recovery")
                }
            }
            State.CLOSED -> {
                failureCount = 0
            }
            else -> {}
        }
    }
    
    /**
     * Record a failed operation.
     */
    @Synchronized
    private fun recordFailure() {
        lastFailureTime = System.currentTimeMillis()
        
        when (state) {
            State.HALF_OPEN -> {
                state = State.OPEN
                Log.w(TAG, "[$name] Circuit OPEN after half-open failure")
            }
            State.CLOSED -> {
                failureCount++
                if (failureCount >= failureThreshold) {
                    state = State.OPEN
                    Log.w(TAG, "[$name] Circuit OPEN after $failureCount failures")
                }
            }
            else -> {}
        }
    }
    
    /**
     * Get current state.
     */
    fun getState(): State = state
    
    /**
     * Force reset the circuit breaker.
     */
    @Synchronized
    fun reset() {
        state = State.CLOSED
        failureCount = 0
        successCount = 0
        Log.i(TAG, "[$name] Circuit manually reset to CLOSED")
    }
}

/**
 * Exception thrown when circuit breaker is open.
 */
class CircuitOpenException(message: String) : Exception(message)

/**
 * Rate limiter using token bucket algorithm.
 */
class RateLimiter(
    private val name: String,
    private val maxTokens: Int,
    private val refillRatePerSecond: Double
) {
    companion object {
        private const val TAG = "RateLimiter"
    }
    
    private var tokens: Double = maxTokens.toDouble()
    private var lastRefillTime: Long = System.currentTimeMillis()
    
    /**
     * Try to acquire a permit.
     * @return true if permit was acquired, false if rate limited
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            Log.d(TAG, "[$name] Rate limited, tokens: ${tokens.toInt()}")
            false
        }
    }
    
    /**
     * Acquire a permit, waiting if necessary.
     * @return time waited in milliseconds
     */
    /**
     * Acquire a permit, waiting if necessary.
     * @return time waited in milliseconds
     */
    suspend fun acquire(): Long {
        var totalWait = 0L
        while (true) {
            val wait = synchronized(this) {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    val tokensNeeded = 1.0 - tokens
                    ((tokensNeeded / refillRatePerSecond) * 1000).toLong().coerceAtLeast(10L)
                }
            }
            
            if (wait == 0L) return totalWait
            
            delay(wait)
            totalWait += wait
        }
    }
    
    /**
     * Get available tokens.
     */
    fun getAvailableTokens(): Int {
        refill()
        return tokens.toInt()
    }
    
    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillTime
        val tokensToAdd = (elapsed / 1000.0) * refillRatePerSecond
        
        tokens = min(tokens + tokensToAdd, maxTokens.toDouble())
        lastRefillTime = now
    }
}
