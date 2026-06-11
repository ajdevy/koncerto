package com.anomaly.koncerto.agent

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class AgentHealthEndpoint(private val healthChecker: AgentHealthChecker) : HealthIndicator {
    override fun health(): Health {
        val statuses = healthChecker.getAllStatuses()
        val healthy = statuses.count { it.status == AgentHealth.HEALTHY }
        val unhealthy = statuses.count { it.status == AgentHealth.UNHEALTHY }
        return Health.up()
            .withDetail("totalAgents", statuses.size)
            .withDetail("healthy", healthy)
            .withDetail("unhealthy", unhealthy)
            .withDetail("agents", statuses.associate { it.agentKey to mapOf(
                "status" to it.status.name,
                "alive" to it.isAlive,
                "uptimeMs" to it.uptimeMs
            )})
            .build()
    }
}
