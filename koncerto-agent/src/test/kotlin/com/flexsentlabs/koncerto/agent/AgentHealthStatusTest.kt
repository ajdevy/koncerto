package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class AgentHealthStatusTest {

    @Test
    fun `AgentHealthStatus stores health metadata`() {
        val status = AgentHealthStatus(
            agentKey = "agent-1",
            isAlive = true,
            lastHeartbeat = 1_700_000_000_000L,
            processId = 4242L,
            uptimeMs = 120_000L,
            activeTurns = 1,
            totalTurnsCompleted = 5,
            lastError = null,
            status = AgentHealth.HEALTHY
        )
        assertThat(status.agentKey).isEqualTo("agent-1")
        assertThat(status.isAlive).isEqualTo(true)
        assertThat(status.processId).isEqualTo(4242L)
        assertThat(status.status).isEqualTo(AgentHealth.HEALTHY)
    }
}
