package com.flexsentlabs.koncerto.core.circuitbreaker

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class ProviderCircuitBreakerTest {

    @Test
    fun `CLOSED state allows requests`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
        assertThat(cb.allowRequest()).isTrue()
        assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED)
    }

    @Test
    fun `transitions to OPEN after failureThreshold failures`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
        repeat(3) { cb.recordFailure() }
        assertThat(cb.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `OPEN state rejects requests`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `OPEN transitions to HALF_OPEN after resetTimeoutMs`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeoutMs = 10))
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
        Thread.sleep(15)
        assertThat(cb.allowRequest()).isTrue()
        assertThat(cb.getState()).isEqualTo(CircuitState.HALF_OPEN)
    }

    @Test
    fun `HALF_OPEN limits requests to halfOpenMaxAttempts`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 1, resetTimeoutMs = 10, halfOpenMaxAttempts = 2
        ))
        cb.recordFailure()
        Thread.sleep(15)
        // 1st: OPEN→HALF_OPEN transition (resets counter, returns true)
        assertThat(cb.allowRequest()).isTrue()
        // 2nd: attempt 1 of 2
        assertThat(cb.allowRequest()).isTrue()
        // 3rd: attempt 2 of 2
        assertThat(cb.allowRequest()).isTrue()
        // 4th: blocked (exhausted halfOpenMaxAttempts)
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `recordSuccess in HALF_OPEN resets to CLOSED`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeoutMs = 10))
        cb.recordFailure()
        Thread.sleep(15)
        cb.allowRequest()
        cb.recordSuccess()
        assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.allowRequest()).isTrue()
    }

    @Test
    fun `recordFailure in HALF_OPEN transitions back to OPEN`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeoutMs = 10))
        cb.recordFailure()
        Thread.sleep(15)
        cb.allowRequest()
        cb.recordFailure()
        assertThat(cb.getState()).isEqualTo(CircuitState.OPEN)
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `recordSuccess in CLOSED is no-op`() {
        val cb = ProviderCircuitBreaker(CircuitBreakerConfig(failureThreshold = 2))
        cb.recordSuccess()
        assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED)
        cb.recordFailure()
        assertThat(cb.allowRequest()).isTrue()
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `default config uses sane defaults`() {
        val cb = ProviderCircuitBreaker()
        assertThat(cb.allowRequest()).isTrue()
        repeat(5) { cb.recordFailure() }
        assertThat(cb.getState()).isEqualTo(CircuitState.OPEN)
    }
}
