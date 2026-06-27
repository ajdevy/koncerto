package com.flexsentlabs.koncerto.core.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class AgentLifecycleEventTest {

    @Test
    fun `Started event carries agentKey and processId`() {
        val event = AgentLifecycleEvent.Started("agent-1", 999L, timestamp = 100L)
        assertThat(event.agentKey).isEqualTo("agent-1")
        assertThat(event.processId).isEqualTo(999L)
        assertThat(event.timestamp).isEqualTo(100L)
        assertThat(event).isInstanceOf(AgentLifecycleEvent.Started::class)
    }

    @Test
    fun `Completed event carries success flag`() {
        val event = AgentLifecycleEvent.Completed("agent-2", success = true, timestamp = 200L)
        assertThat(event.agentKey).isEqualTo("agent-2")
        assertThat(event.success).isEqualTo(true)
        assertThat(event.timestamp).isEqualTo(200L)
    }

    @Test
    fun `Failed event carries error and attempt`() {
        val event = AgentLifecycleEvent.Failed("agent-3", error = "timeout", attempt = 2, timestamp = 300L)
        assertThat(event.agentKey).isEqualTo("agent-3")
        assertThat(event.error).isEqualTo("timeout")
        assertThat(event.attempt).isEqualTo(2)
        assertThat(event.timestamp).isEqualTo(300L)
    }

    @Test
    fun `Recovered event carries restart metadata`() {
        val event = AgentLifecycleEvent.Recovered(
            agentKey = "agent-4",
            afterError = "stall",
            restartCount = 3,
            timestamp = 400L
        )
        assertThat(event.agentKey).isEqualTo("agent-4")
        assertThat(event.afterError).isEqualTo("stall")
        assertThat(event.restartCount).isEqualTo(3)
        assertThat(event.timestamp).isEqualTo(400L)
    }

    @Test
    fun `Stalled event carries stalled duration`() {
        val event = AgentLifecycleEvent.Stalled("agent-5", stalledDurationMs = 60_000L, timestamp = 500L)
        assertThat(event.agentKey).isEqualTo("agent-5")
        assertThat(event.stalledDurationMs).isEqualTo(60_000L)
        assertThat(event.timestamp).isEqualTo(500L)
    }
}
