package com.anomaly.koncerto.agent

import java.util.concurrent.ConcurrentHashMap

class DefaultAgentHealthChecker(
    private val staleThresholdMs: Long = 30_000
) : AgentHealthChecker {
    private val agents = ConcurrentHashMap<String, AgentHealthStatus>()

    override fun getStatus(agentKey: String): AgentHealthStatus? {
        val status = agents[agentKey] ?: return null
        val now = System.currentTimeMillis()
        val isStale = now - status.lastHeartbeat > staleThresholdMs
        return if (isStale) status.copy(isAlive = false, status = AgentHealth.UNHEALTHY) else status
    }

    override fun getAllStatuses(): List<AgentHealthStatus> {
        return agents.keys.mapNotNull { getStatus(it) }
    }

    override fun reportHeartbeat(agentKey: String, processId: Long) {
        val existing = agents[agentKey]
        val now = System.currentTimeMillis()
        agents[agentKey] = AgentHealthStatus(
            agentKey = agentKey,
            isAlive = true,
            lastHeartbeat = now,
            processId = processId,
            uptimeMs = existing?.let { now - it.lastHeartbeat + it.uptimeMs } ?: 0,
            activeTurns = existing?.activeTurns ?: 0,
            totalTurnsCompleted = existing?.totalTurnsCompleted ?: 0,
            status = AgentHealth.HEALTHY
        )
    }

    override fun markUnhealthy(agentKey: String, error: String) {
        agents.computeIfPresent(agentKey) { _, existing ->
            existing.copy(
                isAlive = false,
                status = AgentHealth.UNHEALTHY,
                lastError = error
            )
        }
    }

    override fun removeAgent(agentKey: String) { agents.remove(agentKey) }

    override fun getHealthyCount(): Int = getAllStatuses().count { it.status == AgentHealth.HEALTHY }

    override fun getUnhealthyCount(): Int = getAllStatuses().count { it.status == AgentHealth.UNHEALTHY }
}
