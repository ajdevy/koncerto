package com.flexsentlabs.koncerto.core.circuitbreaker

import java.util.concurrent.atomic.AtomicInteger

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val resetTimeoutMs: Long = 30_000,
    val halfOpenMaxAttempts: Int = 3
)

class ProviderCircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig()
) {
    @Volatile private var state: CircuitState = CircuitState.CLOSED
    private val failureCount = AtomicInteger(0)
    @Volatile private var lastFailureTime: Long = 0L
    private val halfOpenAttempts = AtomicInteger(0)

    fun allowRequest(): Boolean {
        when (state) {
            CircuitState.CLOSED -> return true
            CircuitState.OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime > config.resetTimeoutMs) {
                    state = CircuitState.HALF_OPEN
                    halfOpenAttempts.set(0)
                    return true
                }
                return false
            }
            CircuitState.HALF_OPEN -> {
                if (halfOpenAttempts.getAndIncrement() < config.halfOpenMaxAttempts) {
                    return true
                }
                return false
            }
        }
    }

    fun recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED
            failureCount.set(0)
            halfOpenAttempts.set(0)
        }
    }

    fun recordFailure() {
        lastFailureTime = System.currentTimeMillis()
        when (state) {
            CircuitState.CLOSED -> {
                if (failureCount.incrementAndGet() >= config.failureThreshold) {
                    state = CircuitState.OPEN
                }
            }
            CircuitState.HALF_OPEN -> state = CircuitState.OPEN
            else -> {}
        }
    }

    fun getState(): CircuitState = state
}
