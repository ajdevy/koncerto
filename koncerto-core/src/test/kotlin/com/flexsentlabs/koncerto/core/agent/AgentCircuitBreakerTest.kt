package com.flexsentlabs.koncerto.core.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class AgentCircuitBreakerTest {

    @Test
    fun `CLOSED state allows requests`() {
        val cb = AgentCircuitBreaker(failureThreshold = 3, resetTimeoutMs = 10000)
        assertThat(cb.allowRequest("key")).isTrue()
    }

    @Test
    fun `transitions to OPEN after failureThreshold failures`() {
        val cb = AgentCircuitBreaker(failureThreshold = 3, resetTimeoutMs = 10000)
        repeat(2) { cb.recordFailure("key"); assertThat(cb.allowRequest("key")).isTrue() }
        cb.recordFailure("key")
        assertThat(cb.allowRequest("key")).isFalse()
    }

    @Test
    fun `OPEN state rejects requests`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10000)
        cb.recordFailure("key")
        assertThat(cb.allowRequest("key")).isFalse()
    }

    @Test
    fun `transitions from OPEN to HALF_OPEN after resetTimeoutMs`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure("key")
        assertThat(cb.allowRequest("key")).isFalse()
        Thread.sleep(15)
        assertThat(cb.allowRequest("key")).isTrue()
    }

    @Test
    fun `HALF_OPEN state allows requests`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure("key")
        Thread.sleep(15)
        assertThat(cb.allowRequest("key")).isTrue()
        assertThat(cb.allowRequest("key")).isTrue()
    }

    @Test
    fun `recordSuccess resets to CLOSED`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10)
        cb.recordFailure("key")
        Thread.sleep(15)
        cb.allowRequest("key")
        cb.recordSuccess("key")
        assertThat(cb.allowRequest("key")).isTrue()
        cb.recordFailure("key")
        assertThat(cb.allowRequest("key")).isFalse()
    }

    @Test
    fun `multiple keys are isolated`() {
        val cb = AgentCircuitBreaker(failureThreshold = 2, resetTimeoutMs = 10000)
        cb.recordFailure("a")
        assertThat(cb.allowRequest("a")).isTrue()
        assertThat(cb.allowRequest("b")).isTrue()
        cb.recordFailure("a")
        assertThat(cb.allowRequest("a")).isFalse()
        assertThat(cb.allowRequest("b")).isTrue()
    }

    @Test
    fun `reset clears state for a key`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10000)
        cb.recordFailure("key")
        assertThat(cb.allowRequest("key")).isFalse()
        cb.reset("key")
        assertThat(cb.allowRequest("key")).isTrue()
    }

    @Test
    fun `getState returns correct ordinal`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10000)
        assertThat(cb.getState("unknown")).isEqualTo(0)
        assertThat(cb.getState("key")).isEqualTo(0)
        cb.recordFailure("key")
        assertThat(cb.getState("key")).isEqualTo(1)
    }

    @Test
    fun `getAllStates returns all breaker states`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 10000)
        cb.recordFailure("a")
        cb.recordFailure("b")
        val states = cb.getAllStates()
        assertThat(states.size).isEqualTo(2)
        assertThat(states["a"]).isEqualTo(1)
        assertThat(states["b"]).isEqualTo(1)
    }

    @Test
    fun `recordSuccess on unknown key does nothing`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1)
        cb.recordSuccess("nonexistent")
        assertThat(cb.getState("nonexistent")).isEqualTo(0)
    }

    @Test
    fun `getState returns 0 for unknown key`() {
        val cb = AgentCircuitBreaker(failureThreshold = 1)
        assertThat(cb.getState("unknown")).isEqualTo(0)
    }
}
