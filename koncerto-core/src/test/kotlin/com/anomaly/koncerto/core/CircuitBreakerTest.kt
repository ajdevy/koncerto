package com.anomaly.koncerto.core

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class CircuitBreakerTest {

    @Test
    fun `CLOSED state allows requests`() {
        val cb = CircuitBreaker(failureThreshold = 3, resetTimeoutMs = 10000)
        assertThat(cb.allowRequest()).isTrue()
    }

    @Test
    fun `transitions from CLOSED to OPEN after failureThreshold failures`() {
        val cb = CircuitBreaker(failureThreshold = 3, resetTimeoutMs = 10000)
        repeat(2) {
            cb.recordFailure()
            assertThat(cb.allowRequest()).isTrue()
        }
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `OPEN state rejects requests`() {
        val cb = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10000)
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `transition from OPEN to HALF_OPEN after resetTimeoutMs`() {
        val cb = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
        Thread.sleep(15)
        assertThat(cb.allowRequest()).isTrue()
    }

    @Test
    fun `HALF_OPEN state allows requests`() {
        val cb = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure()
        Thread.sleep(15)
        assertThat(cb.allowRequest()).isTrue()
        assertThat(cb.allowRequest()).isTrue()
    }

    @Test
    fun `recordSuccess resets to CLOSED`() {
        val cb = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure()
        Thread.sleep(15)
        cb.allowRequest()
        cb.recordSuccess()
        assertThat(cb.allowRequest()).isTrue()
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `multiple failure and success cycles`() {
        val cb = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 10)
        cb.recordFailure()
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
        Thread.sleep(15)
        assertThat(cb.allowRequest()).isTrue()
        cb.recordSuccess()
        assertThat(cb.allowRequest()).isTrue()

        cb.recordFailure()
        cb.recordFailure()
        assertThat(cb.allowRequest()).isFalse()
        Thread.sleep(15)
        assertThat(cb.allowRequest()).isTrue()
        cb.recordSuccess()
        assertThat(cb.allowRequest()).isTrue()
    }
}
