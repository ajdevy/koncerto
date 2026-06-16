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

    suspend fun acquire() {
        while (true) {
            if (tryAcquire()) return
            delay(refillIntervalMs)
        }
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
