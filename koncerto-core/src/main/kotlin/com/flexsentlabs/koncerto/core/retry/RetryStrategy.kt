package com.flexsentlabs.koncerto.core.retry

import kotlinx.coroutines.delay

data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 60_000,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.2
)

object RetryStrategy {
    fun nextDelay(attempt: Int, config: RetryConfig = RetryConfig()): Long {
        val exponentialDelay = config.initialDelayMs * Math.pow(config.multiplier, attempt.toDouble())
        val cappedDelay = minOf(exponentialDelay.toLong(), config.maxDelayMs)
        val jitter = (cappedDelay * config.jitterFactor * Math.random()).toLong()
        return cappedDelay + jitter
    }

    suspend fun <T> retryWithBackoff(
        block: suspend (Int) -> T,
        config: RetryConfig = RetryConfig(),
        shouldRetry: (Throwable) -> Boolean = { true }
    ): T {
        var lastException: Throwable? = null
        for (attempt in 0..config.maxRetries) {
            try {
                return block(attempt)
            } catch (e: Throwable) {
                lastException = e
                if (!shouldRetry(e) || attempt >= config.maxRetries) throw e
                val delay = nextDelay(attempt, config)
                delay(delay)
            }
        }
        throw lastException ?: RuntimeException("retryWithBackoff failed unexpectedly")
    }
}
