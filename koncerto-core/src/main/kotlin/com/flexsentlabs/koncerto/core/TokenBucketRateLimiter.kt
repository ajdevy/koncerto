package com.flexsentlabs.koncerto.core

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

class TokenBucketRateLimiter(
    private val maxTokens: Int,
    private val refillIntervalMs: Long,
    private val refillCount: Int
) {
    private val tokens = AtomicLong(maxTokens.toLong())
    private val lastRefillMs = AtomicLong(System.currentTimeMillis())

    private val ACQUIRE_TIMEOUT_MS = 30_000L

    suspend fun acquire() {
        val deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire()) return
            delay(refillIntervalMs.coerceAtMost(1000L))
        }
        throw RuntimeException("TokenBucketRateLimiter.acquire timed out after ${ACQUIRE_TIMEOUT_MS}ms")
    }

    fun tryAcquire(): Boolean {
        refill()
        val current = tokens.get()
        if (current <= 0) return false
        return tokens.compareAndSet(current, current - 1)
    }

    private fun refill(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastRefillMs.get()
        val elapsed = now - last
        if (elapsed < refillIntervalMs) return false
        val intervals = elapsed / refillIntervalMs
        val newTokens = (intervals * refillCount).toLong()
        val targetTime = last + intervals * refillIntervalMs
        if (!lastRefillMs.compareAndSet(last, targetTime)) return false
        tokens.updateAndGet { current -> (current + newTokens).coerceAtMost(maxTokens.toLong()) }
        return true
    }
}
