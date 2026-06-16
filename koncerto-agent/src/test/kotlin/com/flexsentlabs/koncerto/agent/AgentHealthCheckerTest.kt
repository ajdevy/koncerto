package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class AgentHealthCheckerTest {

    @Test
    fun `reportHeartbeat creates status`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 12345L)
        val status = checker.getStatus("agent-1")
        assertThat(status).isNotNull()
        assertThat(status!!.agentKey).isEqualTo("agent-1")
        assertThat(status.processId).isEqualTo(12345L)
        assertThat(status.isAlive).isTrue()
        assertThat(status.status).isEqualTo(AgentHealth.HEALTHY)
    }

    @Test
    fun `heartbeat updates lastHeartbeat`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 100L)
        val first = checker.getStatus("agent-1")!!
        val firstBeat = first.lastHeartbeat
        Thread.sleep(10)
        checker.reportHeartbeat("agent-1", 100L)
        val second = checker.getStatus("agent-1")!!
        assertThat(second.lastHeartbeat > firstBeat).isTrue()
    }

    @Test
    fun `stale detection returns unhealthy`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 1)
        checker.reportHeartbeat("agent-1", 100L)
        Thread.sleep(5)
        val status = checker.getStatus("agent-1")
        assertThat(status).isNotNull()
        assertThat(status!!.isAlive).isFalse()
        assertThat(status.status).isEqualTo(AgentHealth.UNHEALTHY)
    }

    @Test
    fun `markUnhealthy sets status to UNHEALTHY`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 100L)
        checker.markUnhealthy("agent-1", "process crashed")
        val status = checker.getStatus("agent-1")
        assertThat(status).isNotNull()
        assertThat(status!!.status).isEqualTo(AgentHealth.UNHEALTHY)
        assertThat(status.isAlive).isFalse()
        assertThat(status.lastError).isEqualTo("process crashed")
    }

    @Test
    fun `removeAgent removes entry`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 100L)
        assertThat(checker.getStatus("agent-1")).isNotNull()
        checker.removeAgent("agent-1")
        assertThat(checker.getStatus("agent-1")).isNull()
    }

    @Test
    fun `getHealthyCount and getUnhealthyCount`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("healthy-1", 100L)
        checker.reportHeartbeat("healthy-2", 200L)
        checker.reportHeartbeat("unhealthy-1", 300L)
        checker.markUnhealthy("unhealthy-1", "error")
        assertThat(checker.getHealthyCount()).isEqualTo(2)
        assertThat(checker.getUnhealthyCount()).isEqualTo(1)
    }

    @Test
    fun `getAllStatuses returns all agents`() {
        val checker = DefaultAgentHealthChecker(staleThresholdMs = 60_000)
        checker.reportHeartbeat("agent-1", 100L)
        checker.reportHeartbeat("agent-2", 200L)
        val all = checker.getAllStatuses()
        assertThat(all.size).isEqualTo(2)
    }

    @Test
    fun `getStatus returns null for unknown agent`() {
        val checker = DefaultAgentHealthChecker()
        assertThat(checker.getStatus("nonexistent")).isNull()
    }

    @Test
    fun `getAllStatuses returns empty when no agents`() {
        val checker = DefaultAgentHealthChecker()
        assertThat(checker.getAllStatuses()).isEmpty()
    }
}
