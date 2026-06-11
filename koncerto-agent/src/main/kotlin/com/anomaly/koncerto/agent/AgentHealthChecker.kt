package com.anomaly.koncerto.agent

data class AgentHealthStatus(
    val agentKey: String,
    val isAlive: Boolean,
    val lastHeartbeat: Long,
    val processId: Long? = null,
    val uptimeMs: Long = 0,
    val activeTurns: Int = 0,
    val totalTurnsCompleted: Int = 0,
    val lastError: String? = null,
    val status: AgentHealth = AgentHealth.UNKNOWN
)

enum class AgentHealth { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }

interface AgentHealthChecker {
    fun getStatus(agentKey: String): AgentHealthStatus?
    fun getAllStatuses(): List<AgentHealthStatus>
    fun reportHeartbeat(agentKey: String, processId: Long)
    fun markUnhealthy(agentKey: String, error: String)
    fun removeAgent(agentKey: String)
    fun getHealthyCount(): Int
    fun getUnhealthyCount(): Int
}
