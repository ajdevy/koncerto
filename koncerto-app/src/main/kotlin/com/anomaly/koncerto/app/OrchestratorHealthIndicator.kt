package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class OrchestratorHealthIndicator(
    private val orchestrator: Orchestrator
) : HealthIndicator {
    override fun health(): Health {
        val runningCount = orchestrator.projects.values.sumOf { it.state.running.size }
        val blockedCount = orchestrator.projects.values.sumOf { it.state.blocked.size }
        val retryCount = orchestrator.projects.values.sumOf { it.state.retryAttempts.size }
        return Health.up()
            .withDetail("runningAgents", runningCount)
            .withDetail("blockedIssues", blockedCount)
            .withDetail("retryingIssues", retryCount)
            .withDetail("uptimeMs", System.currentTimeMillis() - START_TIME_MS)
            .build()
    }

    companion object {
        private val START_TIME_MS = System.currentTimeMillis()
    }
}
