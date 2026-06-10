package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class CliRunner(
    private val orchestrator: Orchestrator
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        val command = args.firstOrNull()
        when (command?.lowercase()) {
            "status" -> printStatus()
            "agents" -> printAgents()
            "restart" -> orchestrator.restart()
            "help" -> printHelp()
            else -> orchestrator.start()
        }
    }

    private fun printStatus() {
        println("[koncerto] Projects: ${orchestrator.projects.size}")
        val totalRunning = orchestrator.projects.values.sumOf { it.state.running.size }
        val totalBlocked = orchestrator.projects.values.sumOf { it.state.blocked.size }
        val totalRetrying = orchestrator.projects.values.sumOf { it.state.retryAttempts.size }
        println("[koncerto] Running: $totalRunning")
        println("[koncerto] Blocked: $totalBlocked")
        println("[koncerto] Retrying: $totalRetrying")
        val tokens = orchestrator.projects.values.firstOrNull()?.state?.tokenTotals
        if (tokens != null) {
            println("[koncerto] Tokens: in=${tokens.inputTokens} out=${tokens.outputTokens} total=${tokens.totalTokens}")
        }
    }

    private fun printAgents() {
        for ((slug, pr) in orchestrator.projects) {
            println("[koncerto] Project: $slug")
            for ((id, entry) in pr.state.running) {
                val seconds = Duration.between(entry.startedAt, Instant.now()).seconds
                println("  ${entry.issue.identifier} | turns=${entry.turnCount} | ${seconds}s | ${entry.issue.title}")
            }
        }
    }

    private fun printHelp() {
        println("Koncerto CLI")
        println("  (no args)   Start orchestrator")
        println("  status      Show orchestrator state")
        println("  agents      List running agents")
        println("  restart     Restart orchestrator")
        println("  help        Show this message")
    }
}
