package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status

class AgentHealthEndpointTest {

    @Test
    fun `health returns UP with agent stats`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 100L)
        checker.reportHeartbeat("agent-2", 200L)

        val endpoint = AgentHealthEndpoint(checker)
        val health = endpoint.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["totalAgents"]).isEqualTo(2)
        assertThat(health.details["healthy"]).isEqualTo(2)
        assertThat(health.details["unhealthy"]).isEqualTo(0)
    }

    @Test
    fun `health reports mixed healthy and unhealthy agents`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("healthy-1", 100L)
        checker.markUnhealthy("healthy-1", "error")
        checker.reportHeartbeat("healthy-2", 200L)

        val endpoint = AgentHealthEndpoint(checker)
        val health = endpoint.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["totalAgents"]).isEqualTo(2)
        assertThat(health.details["healthy"]).isEqualTo(1)
        assertThat(health.details["unhealthy"]).isEqualTo(1)
    }

    @Test
    fun `health returns zero counts for no agents`() {
        val checker = DefaultAgentHealthChecker()
        val endpoint = AgentHealthEndpoint(checker)
        val health = endpoint.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["totalAgents"]).isEqualTo(0)
        assertThat(health.details["healthy"]).isEqualTo(0)
        assertThat(health.details["unhealthy"]).isEqualTo(0)
    }

    @Test
    fun `health includes agent details map`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("my-agent", 42L)

        val endpoint = AgentHealthEndpoint(checker)
        val health = endpoint.health()

        assertThat(health.details.containsKey("agents")).isTrue()
    }
}
