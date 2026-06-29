package com.flexsentlabs.koncerto.core.retry

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.assertThrows

class RetryStrategyTest {

    @Test
    fun `nextDelay calculates exponential backoff with jitter`() {
        val config = RetryConfig(initialDelayMs = 1000, multiplier = 2.0, jitterFactor = 0.2, maxDelayMs = 60_000)
        val delay0 = RetryStrategy.nextDelay(0, config)
        val delay1 = RetryStrategy.nextDelay(1, config)
        val delay2 = RetryStrategy.nextDelay(2, config)

        Assertions.assertTrue(delay0 >= 1000)
        Assertions.assertTrue(delay0 <= 1200)
        Assertions.assertTrue(delay1 >= 2000)
        Assertions.assertTrue(delay1 <= 2400)
        Assertions.assertTrue(delay2 >= 4000)
        Assertions.assertTrue(delay2 <= 4800)
    }

    @Test
    fun `nextDelay caps at maxDelayMs`() {
        val config = RetryConfig(initialDelayMs = 1000, multiplier = 2.0, jitterFactor = 0.2, maxDelayMs = 5000)
        val delay = RetryStrategy.nextDelay(10, config)
        Assertions.assertTrue(delay <= 6000)
    }

    @Test
    fun `retryWithBackoff succeeds on first attempt`() = runBlocking {
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        var attempts = 0
        val result = RetryStrategy.retryWithBackoff(block = { attempt: Int ->
            attempts++
            "success"
        }, config = config)
        assertThat(result).isEqualTo("success")
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `retryWithBackoff retries on failure then succeeds`() = runBlocking {
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        var attempts = 0
        val result = RetryStrategy.retryWithBackoff(block = { attempt: Int ->
            attempts++
            if (attempts < 2) throw RuntimeException("fail")
            "success"
        }, config = config)
        assertThat(result).isEqualTo("success")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `retryWithBackoff exhausts retries then throws`() = runBlocking {
        val config = RetryConfig(maxRetries = 2, initialDelayMs = 10)
        var attempts = 0
        val ex = assertThrows<RuntimeException> {
            RetryStrategy.retryWithBackoff(block = { attempt: Int ->
                attempts++
                throw RuntimeException("always fails")
            }, config = config)
        }
        assertThat(attempts).isEqualTo(3)
        assertThat(ex.message).isEqualTo("always fails")
    }

    @Test
    fun `retryWithBackoff respects shouldRetry predicate`() = runBlocking {
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        var attempts = 0
        val ex = assertThrows<RuntimeException> {
            RetryStrategy.retryWithBackoff(
                block = { attempt: Int ->
                    attempts++
                    throw RuntimeException("always fails")
                },
                config = config,
                shouldRetry = { it is IllegalArgumentException }
            )
        }
        assertThat(attempts).isEqualTo(1)
        assertThat(ex.message).isEqualTo("always fails")
    }

    @Test
    fun `retryWithBackoff retries when shouldRetry returns true`() = runBlocking {
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        var attempts = 0
        val result = RetryStrategy.retryWithBackoff(
            block = { attempt: Int ->
                attempts++
                if (attempts < 2) throw RuntimeException("retry me")
                "success"
            },
            config = config,
            shouldRetry = { it is RuntimeException }
        )
        assertThat(result).isEqualTo("success")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `nextDelay with zero jitter`() {
        val config = RetryConfig(initialDelayMs = 1000, multiplier = 2.0, jitterFactor = 0.0)
        val delay = RetryStrategy.nextDelay(2, config)
        assertThat(delay).isEqualTo(4000)
    }

    @Test
    fun `retryWithBackoff attempt parameter increments`() = runBlocking {
        val config = RetryConfig(maxRetries = 3, initialDelayMs = 10)
        val attempts = mutableListOf<Int>()
        val result = RetryStrategy.retryWithBackoff(block = { attempt: Int ->
            attempts.add(attempt)
            if (attempt < 2) throw RuntimeException("fail")
            "success"
        }, config = config)
        assertThat(result).isEqualTo("success")
        assertThat(attempts).isEqualTo(listOf(0, 1, 2))
    }

    @Test
    fun `retryWithBackoff with zero max retries throws immediately`() = runBlocking {
        val config = RetryConfig(maxRetries = 0, initialDelayMs = 10)
        var attempts = 0
        val ex = assertThrows<IllegalStateException> {
            RetryStrategy.retryWithBackoff(
                block = {
                    attempts++
                    throw IllegalStateException("no retries")
                },
                config = config
            )
        }
        assertThat(attempts).isEqualTo(1)
        assertThat(ex.message).isEqualTo("no retries")
    }

    @Test
    fun `nextDelay uses default config when omitted`() {
        val delay = RetryStrategy.nextDelay(0)
        Assertions.assertTrue(delay >= 1000)
    }

    @Test
    fun `retryWithBackoff with negative retries throws fallback exception`() = runBlocking {
        val ex = assertThrows<RuntimeException> {
            RetryStrategy.retryWithBackoff(
                block = { "never called" },
                config = RetryConfig(maxRetries = -1)
            )
        }
        assertThat(ex.message).isEqualTo("retryWithBackoff failed unexpectedly")
    }

    @Test
    fun `retryWithBackoff uses default config when omitted`() = runBlocking {
        val result = RetryStrategy.retryWithBackoff(block = { _: Int -> "ok" })
        assertThat(result).isEqualTo("ok")
    }
}