package com.anomaly.koncerto.core.agent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class AgentCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 60_000
) {
    private val breakers = ConcurrentHashMap<String, BreakerState>()

    fun allowRequest(agentKey: String): Boolean {
        val state = breakers.computeIfAbsent(agentKey) { BreakerState(this) }
        return state.allowRequest()
    }

    fun recordSuccess(agentKey: String) {
        val state = breakers[agentKey] ?: return
        state.recordSuccess()
    }

    fun recordFailure(agentKey: String) {
        val state = breakers.computeIfAbsent(agentKey) { BreakerState(this) }
        state.recordFailure()
    }

    fun getState(agentKey: String): Int {
        val state = breakers[agentKey] ?: return 0
        return state.currentState.ordinal
    }

    fun reset(agentKey: String) {
        breakers.remove(agentKey)
    }

    fun getAllStates(): Map<String, Int> {
        return breakers.mapValues { it.value.currentState.ordinal }
    }

    class BreakerState(private val parent: AgentCircuitBreaker) {
        enum class State { CLOSED, OPEN, HALF_OPEN }

        private val state = AtomicInteger(State.CLOSED.ordinal)
        private val failureCount = AtomicInteger(0)
        private val lastFailureMs = AtomicLong(0)

        val currentState: State
            get() = State.entries[state.get()]

        fun allowRequest(): Boolean {
            when (State.entries[state.get()]) {
                State.CLOSED -> return true
                State.OPEN -> {
                    if (System.currentTimeMillis() - lastFailureMs.get() >= parent.resetTimeoutMs) {
                        state.set(State.HALF_OPEN.ordinal)
                        return true
                    }
                    return false
                }
                State.HALF_OPEN -> return true
            }
        }

        fun recordSuccess() {
            state.set(State.CLOSED.ordinal)
            failureCount.set(0)
        }

        fun recordFailure() {
            lastFailureMs.set(System.currentTimeMillis())
            val count = failureCount.incrementAndGet()
            if (count >= parent.failureThreshold && state.get() != State.OPEN.ordinal) {
                state.set(State.OPEN.ordinal)
            }
        }
    }
}