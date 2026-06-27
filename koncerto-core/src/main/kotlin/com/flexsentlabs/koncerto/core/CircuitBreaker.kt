package com.flexsentlabs.koncerto.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CircuitBreaker(
    private val failureThreshold: Int,
    private val resetTimeoutMs: Long
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicInteger(State.CLOSED.ordinal)
    private val failureCount = AtomicInteger(0)
    private val lastFailureMs = AtomicLong(0)

    fun allowRequest(): Boolean {
        when (State.entries[state.get()]) {
            State.CLOSED -> return true
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureMs.get() >= resetTimeoutMs) {
                    state.set(State.HALF_OPEN.ordinal)
                    return true
                }
                return false
            }
            State.HALF_OPEN -> return true
        }
    }

    fun recordSuccess() {
        if (state.get() != State.HALF_OPEN.ordinal) return
        state.set(State.CLOSED.ordinal)
        failureCount.set(0)
    }

    fun recordFailure() {
        lastFailureMs.set(System.currentTimeMillis())
        val count = failureCount.incrementAndGet()
        if (count >= failureThreshold && state.get() != State.OPEN.ordinal) {
            state.set(State.OPEN.ordinal)
        }
    }
}
