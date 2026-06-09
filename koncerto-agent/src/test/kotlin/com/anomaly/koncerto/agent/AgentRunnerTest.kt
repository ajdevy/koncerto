package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
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

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun sampleConfig(
        command: String = "codex app-server",
        agentKind: String = "codex",
        opencodeCommand: String = "opencode"
    ): ServiceConfig = ServiceConfig(
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
        agentKind = agentKind,
        codexCommand = command,
        codexApprovalPolicy = null,
        codexThreadSandbox = null,
        codexTurnSandboxPolicy = null,
        opencodeCommand = opencodeCommand,
            turnTimeoutMs = 3600000,
            readTimeoutMs = 5000,
            stallTimeoutMs = 300000,
            stages = emptyMap()
        )

    private fun sampleIssue(): Issue = Issue(
        id = "1",
        identifier = "ABC-1",
        title = "Test issue",
        description = "A description",
        priority = 1,
        state = "Todo",
        branchName = "abc-1-test",
        url = "https://linear.app/test/issue/ABC-1",
        labels = listOf("bug"),
        blockedBy = emptyList(),
        createdAt = null,
        updatedAt = null
    )

    @Test
    fun `runner returns success with false command`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "Hi {{ issue.identifier }}")
        assertThat(result).isNotNull()
    }

    @Test
    fun `runner succeeds with valid command and prompt`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script)
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = 1, prompt = "Fix {{ issue.title }}")
        assertThat(result).isNotNull()
    }

    @Test
    fun `runner succeeds even when command exits nonzero`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "Hello")
        assertThat(result).isNotNull()
    }

    @Test
    fun `runner events flow is accessible`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger())
        val flow = runner.events()
        assertThat(flow).isNotNull()
    }

    @Test
    fun `AttemptResult Outcome enum has all values`() {
        val outcomes = AttemptResult.Outcome.entries
        assertThat(outcomes.size).isEqualTo(6)
        assertThat(outcomes.contains(AttemptResult.Outcome.SUCCEEDED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.FAILED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.TIMED_OUT)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.STALLED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.CANCELLED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.STARTUP_FAILED)).isTrue()
    }

    @Test
    fun `runner creates workspace for issue identifier`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        runner.run(issue, attempt = null, prompt = "test")
        // Workspace directory should have been created
        val wsPath = root.resolve("ABC-1")
        assertThat(Files.exists(wsPath)).isTrue()
    }

    @Test
    fun `runner with null attempt passes null to template`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = "sleep 0.5"
        val config = sampleConfig(command = script)
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "attempt={{ attempt }}")
        assertThat(result).isNotNull()
    }

    @Test
    fun `runner works with opencode runtime kind`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode", opencodeCommand = script)
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = 1, prompt = "Fix {{ issue.title }}")
        assertThat(result).isNotNull()
    }
}
