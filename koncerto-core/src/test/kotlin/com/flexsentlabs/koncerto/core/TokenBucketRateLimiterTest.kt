package com.flexsentlabs.koncerto.core

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class TokenBucketRateLimiterTest {

    @Test
    fun `tryAcquire returns true when tokens are available`() {
        val limiter = TokenBucketRateLimiter(maxTokens = 5, refillIntervalMs = 1000, refillCount = 1)
        assertThat(limiter.tryAcquire()).isTrue()
    }

    @Test
    fun `tryAcquire returns false when tokens are exhausted`() {
        val limiter = TokenBucketRateLimiter(maxTokens = 1, refillIntervalMs = 10000, refillCount = 1)
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isFalse()
    }

    @Test
    fun `acquire suspends and eventually succeeds after refill`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxTokens = 1, refillIntervalMs = 1000, refillCount = 1)
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isFalse()
        withTimeout(5000) {
            limiter.acquire()
        }
    }

    @Test
    fun `tokens refill over time`() {
        val limiter = TokenBucketRateLimiter(maxTokens = 3, refillIntervalMs = 300, refillCount = 1)
        repeat(3) { assertThat(limiter.tryAcquire()).isTrue() }
        assertThat(limiter.tryAcquire()).isFalse()
        Thread.sleep(500)
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isFalse()
    }

    @Test
    fun `tokens do not exceed maxTokens`() {
        val limiter = TokenBucketRateLimiter(maxTokens = 3, refillIntervalMs = 100, refillCount = 10)
        repeat(3) { limiter.tryAcquire() }
        Thread.sleep(300)
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isTrue()
        assertThat(limiter.tryAcquire()).isFalse()
    }

    @Test
    fun `concurrent access does not throw`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxTokens = 100, refillIntervalMs = 50, refillCount = 10)
        coroutineScope {
            val jobs = List(10) {
                async(Dispatchers.Default) {
                    repeat(10) { limiter.tryAcquire() }
                }
            }
            jobs.forEach { it.await() }
        }
    }
}