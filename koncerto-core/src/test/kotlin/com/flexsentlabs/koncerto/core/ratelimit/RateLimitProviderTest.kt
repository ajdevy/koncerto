package com.flexsentlabs.koncerto.core.ratelimit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.RateLimitConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class RateLimitProviderTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    @Test
    fun `acquire returns true when under limits`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 1000, burstCapacity = 10)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isTrue()
    }

    @Test
    fun `acquire decrements available tokens`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 1000, burstCapacity = 5)
        val provider = RateLimitProvider(config, scope)
        repeat(5) { provider.acquire() }
        assertThat(provider.availableTokens.value).isEqualTo(0.0)
    }

    @Test
    fun `acquire does not go below zero tokens`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 1000, burstCapacity = 2)
        val provider = RateLimitProvider(config, scope)
        repeat(10) { provider.acquire() }
        assertThat(provider.availableTokens.value).isEqualTo(0.0)
    }

    @Test
    fun `getStats returns current state`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 10, requestsPerHour = 100, burstCapacity = 5)
        val provider = RateLimitProvider(config, scope)
        provider.acquire()
        val stats = provider.getStats()
        assertThat(stats.availableTokens).isEqualTo(4)
        assertThat(stats.requestsLastMinute).isEqualTo(1)
        assertThat(stats.requestsLastHour).isEqualTo(1)
        assertThat(stats.limitPerMinute).isEqualTo(10)
        assertThat(stats.limitPerHour).isEqualTo(100)
    }

    @Test
    fun `minute limit blocks requests`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 2, requestsPerHour = 1000, burstCapacity = 10)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isTrue()
        assertThat(provider.acquire()).isTrue()
        assertThat(provider.acquire()).isFalse()
    }

    @Test
    fun `hour limit blocks when minute under but hour at limit`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 2, burstCapacity = 10)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isTrue()
        assertThat(provider.acquire()).isTrue()
        assertThat(provider.acquire()).isFalse()
    }

    @Test
    fun `availableTokens starts at burstCapacity`() {
        val config = RateLimitConfig(requestsPerMinute = 10, requestsPerHour = 100, burstCapacity = 20)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.availableTokens.value).isEqualTo(20.0)
    }

    @Test
    fun `lastRefill starts with current time`() {
        val config = RateLimitConfig(requestsPerMinute = 10, requestsPerHour = 100)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.lastRefill.value > 0).isTrue()
    }

    @Test
    fun `acquire respects zero minute limit`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 0, requestsPerHour = 1000, burstCapacity = 5)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isFalse()
    }

    @Test
    fun `acquire respects zero hour limit`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 0, burstCapacity = 5)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isFalse()
    }

    @Test
    fun `tokens refill after elapsed time`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 60, requestsPerHour = 1000, burstCapacity = 10)
        val provider = RateLimitProvider(config, scope)
        repeat(10) { provider.acquire() }
        assertThat(provider.availableTokens.value).isEqualTo(0.0)
        delay(1100)
        provider.acquire()
        assertThat(provider.availableTokens.value).isGreaterThan(0.0)
    }

    @Test
    fun `waitForAvailability returns true when acquire succeeds immediately`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 100, requestsPerHour = 1000, burstCapacity = 10, backoffMs = 10)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.waitForAvailability()).isTrue()
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `waitForAvailability waits until minute window slides`() = runBlocking {
        val config = RateLimitConfig(requestsPerMinute = 1, requestsPerHour = 1000, burstCapacity = 10, backoffMs = 50)
        val provider = RateLimitProvider(config, scope)
        assertThat(provider.acquire()).isTrue()
        assertThat(provider.acquire()).isFalse()

        val result = async { provider.waitForAvailability() }
        delay(61_000)
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `registry getOrCreate returns same instance for same key`() {
        val config = RateLimitConfig(requestsPerMinute = 10, requestsPerHour = 100)
        val provider1 = RateLimitRegistry.getOrCreate("registry-same-key", config, scope)
        val provider2 = RateLimitRegistry.getOrCreate("registry-same-key", config, scope)
        assertThat(provider1).isEqualTo(provider2)
    }

    @Test
    fun `registry get returns null for unknown key`() {
        assertThat(RateLimitRegistry.get("registry-unknown-key-${System.nanoTime()}")).isNull()
    }

    @Test
    fun `registry getAll includes registered providers`() {
        val key = "registry-getall-key-${System.nanoTime()}"
        val config = RateLimitConfig(requestsPerMinute = 5, requestsPerHour = 50)
        val provider = RateLimitRegistry.getOrCreate(key, config, scope)
        assertThat(RateLimitRegistry.getAll()[key]).isEqualTo(provider)
    }
}
