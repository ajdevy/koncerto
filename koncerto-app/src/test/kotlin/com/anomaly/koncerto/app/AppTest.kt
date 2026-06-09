package com.anomaly.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.orchestrator.RuntimeState
import org.junit.jupiter.api.Test

class AppTest {

    @Test
    fun `RuntimeState initializes with defaults`() {
        val state = RuntimeState()
        assertThat(state.availableSlots()).isEqualTo(10)
        assertThat(state.running.isEmpty()).isTrue()
        assertThat(state.claimed.isEmpty()).isTrue()
        assertThat(state.retryAttempts.isEmpty()).isTrue()
    }

    @Test
    fun `RuntimeState tracks running entries`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 2

        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = com.anomaly.koncerto.orchestrator.RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = java.time.Instant.now(),
            lastCodexTimestamp = null
        )

        assertThat(state.availableSlots()).isEqualTo(1)
        assertThat(state.running.size).isEqualTo(1)
    }

    @Test
    fun `RuntimeState tracks retry entries`() {
        val state = RuntimeState()
        state.retryAttempts["1"] = com.anomaly.koncerto.orchestrator.RetryEntry(
            issueId = "1",
            identifier = "ABC-1",
            attempt = 2,
            dueAtMs = System.currentTimeMillis() + 60000,
            error = "timeout"
        )

        assertThat(state.retryAttempts.size).isEqualTo(1)
        assertThat(state.retryAttempts["1"]!!.attempt).isEqualTo(2)
    }

    @Test
    fun `Application class exists`() {
        assertThat(KoncertoApplication::class.java.simpleName).isEqualTo("KoncertoApplication")
    }
}
