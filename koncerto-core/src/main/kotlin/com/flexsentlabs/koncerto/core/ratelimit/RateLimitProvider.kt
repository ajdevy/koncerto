package com.flexsentlabs.koncerto.core.ratelimit

import com.flexsentlabs.koncerto.core.config.RateLimitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RateLimitProvider(
    private val config: RateLimitConfig,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val _availableTokens = MutableStateFlow(config.burstCapacity.toDouble())
    val availableTokens: StateFlow<Double> = _availableTokens

    private val _lastRefill = MutableStateFlow(System.currentTimeMillis())
    val lastRefill: StateFlow<Long> = _lastRefill

    private val requestTimes = mutableListOf<Long>()
    private val hourlyRequestTimes = mutableListOf<Long>()
    private val mutex = Mutex()

    suspend fun acquire(): Boolean = mutex.withLock {
        refillIfNeeded()
        val now = System.currentTimeMillis()

        val minuteAgo = now - 60_000
        val hourAgo = now - 3_600_000

        requestTimes.retainAll { it > minuteAgo }
        hourlyRequestTimes.retainAll { it > hourAgo }

        val currentMinuteRate = requestTimes.size
        val currentHourRate = hourlyRequestTimes.size

        if (currentMinuteRate >= config.requestsPerMinute ||
            currentHourRate >= config.requestsPerHour) {
            return@withLock false
        }

        requestTimes.add(now)
        hourlyRequestTimes.add(now)

        val currentTokens = _availableTokens.value - 1
        _availableTokens.value = maxOf(0.0, currentTokens)
        true
    }

    private fun refillIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - _lastRefill.value
        if (elapsed >= 1000) {
            val refillRate = config.requestsPerMinute.toDouble() / 60.0
            val tokensToAdd = refillRate * (elapsed / 1000.0)
            val newTokens = minOf(config.burstCapacity.toDouble(), _availableTokens.value + tokensToAdd)
            _availableTokens.value = newTokens
            _lastRefill.value = now
        }
    }

    suspend fun waitForAvailability(): Boolean {
        while (true) {
            if (acquire()) return true
            kotlinx.coroutines.delay(config.backoffMs.toLong())
        }
    }

    suspend fun getStats(): RateLimitStats = mutex.withLock {
        val now = System.currentTimeMillis()
        val minuteAgo = now - 60_000
        val hourAgo = now - 3_600_000

        RateLimitStats(
            availableTokens = _availableTokens.value.toInt(),
            requestsLastMinute = requestTimes.count { it > minuteAgo },
            requestsLastHour = hourlyRequestTimes.count { it > hourAgo },
            limitPerMinute = config.requestsPerMinute,
            limitPerHour = config.requestsPerHour
        )
    }
}

data class RateLimitStats(
    val availableTokens: Int,
    val requestsLastMinute: Int,
    val requestsLastHour: Int,
    val limitPerMinute: Int,
    val limitPerHour: Int
)

object RateLimitRegistry {
    private val providers = mutableMapOf<String, RateLimitProvider>()

    fun getOrCreate(key: String, config: RateLimitConfig, scope: kotlinx.coroutines.CoroutineScope): RateLimitProvider {
        return providers.getOrPut(key) {
            RateLimitProvider(config, scope)
        }
    }

    fun get(key: String): RateLimitProvider? = providers[key]

    fun getAll(): Map<String, RateLimitProvider> = providers.toMap()
}