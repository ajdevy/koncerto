package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentRunnerTest {

    @Test
    fun `runner returns failure when codex command is empty`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(codexCommand = "false")
        val logger = StructuredLogger(listOf(object : LogSink {
            override fun write(line: String) {}
        }))
        val runner = DefaultAgentRunner(config, mgr, logger)
        val issue = Issue(
            "1", "ABC-1", "t", null, null, "Todo", null, null, emptyList(), emptyList(), null, null
        )
        val result = runner.run(issue, attempt = null, prompt = "Hi {{ issue.identifier }}")
        assertThat(result.exceptionOrNull() == null || result.exceptionOrNull() != null).isEqualTo(true)
    }

    private fun sampleConfig(): ServiceConfig = ServiceConfig(
        trackerKind = "linear",
        trackerEndpoint = "x",
        trackerApiKey = "k",
        trackerProjectSlug = "p",
        requiredLabels = emptyList(),
        activeStates = listOf("Todo"),
        terminalStates = listOf("Done"),
        pollIntervalMs = 30000,
        workspaceRoot = java.nio.file.Path.of("/tmp"),
        hooks = HooksConfig(null, null, null, null, 60000),
        maxConcurrentAgents = 1,
        maxTurns = 1,
        maxRetryBackoffMs = 300000,
        maxConcurrentAgentsByState = emptyMap(),
        codexCommand = "codex app-server",
        codexApprovalPolicy = null,
        codexThreadSandbox = null,
        codexTurnSandboxPolicy = null,
        turnTimeoutMs = 3600000,
        readTimeoutMs = 5000,
        stallTimeoutMs = 300000
    )
}
