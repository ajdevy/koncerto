package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class CliRunner(
    private val orchestrator: Orchestrator
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        orchestrator.start()
    }
}
