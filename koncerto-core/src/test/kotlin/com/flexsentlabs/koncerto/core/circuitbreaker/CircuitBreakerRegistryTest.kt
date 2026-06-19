package com.flexsentlabs.koncerto.core.circuitbreaker

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CircuitBreakerRegistryTest {

    @BeforeEach
    fun setUp() {
        CircuitBreakerRegistry.resetAll()
    }

    @Test
    fun `getOrCreate creates new breaker for unknown key`() {
        val cb = CircuitBreakerRegistry.getOrCreate("test")
        assertThat(cb).isNotNull()
        assertThat(cb.getState()).isEqualTo(CircuitState.CLOSED)
    }

    @Test
    fun `getOrCreate returns same breaker for known key`() {
        val cb1 = CircuitBreakerRegistry.getOrCreate("test")
        val cb2 = CircuitBreakerRegistry.getOrCreate("test")
        assertThat(cb1 === cb2).isTrue()
    }

    @Test
    fun `get returns null for unknown key`() {
        val cb = CircuitBreakerRegistry.get("nonexistent")
        assertThat(cb).isNull()
    }

    @Test
    fun `get returns breaker after creation`() {
        CircuitBreakerRegistry.getOrCreate("test")
        val cb = CircuitBreakerRegistry.get("test")
        assertThat(cb).isNotNull()
    }

    @Test
    fun `getAll returns all breakers`() {
        CircuitBreakerRegistry.getOrCreate("a")
        CircuitBreakerRegistry.getOrCreate("b")
        val all = CircuitBreakerRegistry.getAll()
        assertThat(all.size).isEqualTo(2)
        assertThat(all.containsKey("a")).isTrue()
        assertThat(all.containsKey("b")).isTrue()
    }

    @Test
    fun `reset removes breaker for key`() {
        CircuitBreakerRegistry.getOrCreate("test")
        CircuitBreakerRegistry.reset("test")
        assertThat(CircuitBreakerRegistry.get("test")).isNull()
    }

    @Test
    fun `resetAll removes all breakers`() {
        CircuitBreakerRegistry.getOrCreate("a")
        CircuitBreakerRegistry.getOrCreate("b")
        CircuitBreakerRegistry.resetAll()
        assertThat(CircuitBreakerRegistry.getAll()).isEmpty()
    }

    @Test
    fun `getOrCreate with custom config`() {
        val config = CircuitBreakerConfig(failureThreshold = 10, resetTimeoutMs = 60_000, halfOpenMaxAttempts = 5)
        val cb = CircuitBreakerRegistry.getOrCreate("custom", config)
        assertThat(cb).isNotNull()
        cb.recordFailure()
        repeat(9) { cb.recordFailure() }
        assertThat(cb.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `getAll snapshot is immutable`() {
        CircuitBreakerRegistry.getOrCreate("a")
        val snapshot = CircuitBreakerRegistry.getAll()
        CircuitBreakerRegistry.getOrCreate("b")
        assertThat(snapshot.size).isEqualTo(1)
    }
}
