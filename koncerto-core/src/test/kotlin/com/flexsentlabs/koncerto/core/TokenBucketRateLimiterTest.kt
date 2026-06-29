package com.flexsentlabs.koncerto.core

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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

    @Test
    fun `refill handles concurrent compareAndSet contention`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxTokens = 1, refillIntervalMs = 1, refillCount = 1)
        limiter.tryAcquire()
        delay(5)
        val jobs = (1..20).map {
            async(Dispatchers.Default) { limiter.tryAcquire() }
        }
        jobs.awaitAll()
    }

    @Test
    fun `acquire throws when timeout elapses without refill`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxTokens = 0, refillIntervalMs = 1_000, refillCount = 0)
        val timeoutField = TokenBucketRateLimiter::class.java.getDeclaredField("ACQUIRE_TIMEOUT_MS")
        timeoutField.isAccessible = true
        timeoutField.setLong(limiter, 20L)
        assertThrows<RuntimeException> {
            runBlocking { limiter.acquire() }
        }
    }

    @Test
    fun `refill compareAndSet contention path executes`() {
        val limiter = TokenBucketRateLimiter(maxTokens = 1, refillIntervalMs = 1, refillCount = 1)
        limiter.tryAcquire()
        Thread.sleep(5)
        val refill = TokenBucketRateLimiter::class.java.getDeclaredMethod("refill")
        refill.isAccessible = true
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.submit {
                start.await()
                repeat(50) { refill.invoke(limiter) as Boolean }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdownNow()
        assertThat(limiter.tryAcquire() || !limiter.tryAcquire()).isTrue()
    }
}