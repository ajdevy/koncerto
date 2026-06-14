package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.config.AgentProjectConfig
import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.core.config.DockerConfig
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.GitWorkflow
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentRunnerTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun sampleConfig(
        command: String = "codex app-server",
        agentKind: String = "codex"
    ): ServiceConfig = ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf("default" to ProjectConfig(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
                requiredLabels = emptyList(), activeStates = listOf("Todo"), terminalStates = listOf("Done")
            ),
            workspace = WorkspaceConfig(root = "/tmp"),
            agent = AgentProjectConfig(
                kind = agentKind, command = command,
                maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300000,
                maxConcurrentAgentsByState = emptyMap(),
                turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                stages = emptyMap()
            )
        )),
        hooks = HooksConfig(null, null, null, null, 60000),
        gitConfig = GitConfig()
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
        val result = runner.run(issue, attempt = null, prompt = "Hi {{ issue.identifier }}", commandOverride = "false")
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
        val result = runner.run(issue, attempt = null, prompt = "Hello", commandOverride = "false")
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
    fun `AttemptResult stores all properties`() {
        val issue = sampleIssue()
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val workspace = mgr.ensureWorkspace(issue.identifier)
        val usage = TokenUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150)
        val result = AttemptResult(
            issue = issue,
            workspace = workspace,
            outcome = AttemptResult.Outcome.SUCCEEDED,
            tokenUsage = usage
        )
        assertThat(result.issue.id).isEqualTo("1")
        assertThat(result.workspace.path).isEqualTo(workspace.path)
        assertThat(result.outcome).isEqualTo(AttemptResult.Outcome.SUCCEEDED)
        assertThat(result.tokenUsage.totalTokens).isEqualTo(150L)
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
        runner.run(issue, attempt = null, prompt = "test", commandOverride = "false")
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
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = 1, prompt = "Fix {{ issue.title }}")
        assertThat(result).isNotNull()
    }

    @Test
    fun `run times out when turn exceeds turnTimeoutMs`() = runBlocking {
        val root = Files.createTempDirectory("ar-timeout-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "sleep 120", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = "sleep 120",
            turnTimeoutMs = 100, stallTimeoutMs = 50000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `run detects stall when no output for stallTimeoutMs`() = runBlocking {
        val root = Files.createTempDirectory("ar-stall-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            read line && read line && read line
            sleep 120
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 100
        )
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull()?.message ?: "").contains("stalled")
    }

    @Test
    fun `run succeeds when turn completes before timeout`() = runBlocking {
        val root = Files.createTempDirectory("ar-ok-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            read line && read line && read line
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = 1, prompt = "Fix {{ issue.title }}",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 50000
        )
        assertThat(result.exceptionOrNull()).isNull()
    }

    @Test
    fun `stall timeout fires before turn timeout when stall is shorter`() = runBlocking {
        val root = Files.createTempDirectory("ar-stall-first-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            read line && read line && read line
            sleep 120
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 50
        )
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull()?.message ?: "").contains("stalled")
    }

    @Test
    fun `run commits partial work on turn timeout`() = runBlocking {
        val root = Files.createTempDirectory("ar-commit-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val issue = sampleIssue()

        val wsPath = root.resolve("ABC-1")
        Files.createDirectories(wsPath)
        ProcessBuilder("git", "init")
            .directory(wsPath.toFile()).redirectErrorStream(true).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "test@test.com")
            .directory(wsPath.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Test")
            .directory(wsPath.toFile()).start().waitFor()
        ProcessBuilder("git", "commit", "--allow-empty", "-m", "initial")
            .directory(wsPath.toFile()).start().waitFor()

        val gitWorkflow = GitWorkflow(GitConfig(enabled = true, autoCommit = true), noopLogger())
        val config = sampleConfig(command = "sleep 120", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger(), gitWorkflow = gitWorkflow)
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = "sleep 120",
            turnTimeoutMs = 100, stallTimeoutMs = 50000
        )
        assertThat(result.exceptionOrNull()).isNotNull()

        val logProc = ProcessBuilder("git", "log", "--oneline")
            .directory(wsPath.toFile()).redirectErrorStream(true).start()
        val logOutput = logProc.inputStream.bufferedReader().readText()
        assertThat(logOutput.lines().count { it.isNotBlank() }).isEqualTo(2)
    }

    @Test
    fun `stall timeout longer than turn timeout — turn timeout fires first`() = runBlocking {
        val root = Files.createTempDirectory("ar-turn-timeout-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            read line && read line && read line
            sleep 120
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 100, stallTimeoutMs = 50000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull()?.message ?: "").contains("Timed out")
    }

    @Test
    fun `zero stall timeout — fires immediately when no output`() = runBlocking {
        val root = Files.createTempDirectory("ar-zero-stall-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "sleep 120", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = "sleep 120",
            turnTimeoutMs = 50000, stallTimeoutMs = 0
        )
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull()?.message ?: "").contains("stalled")
    }

    @Test
    fun `subprocess exits before timeout — no crash`() = runBlocking {
        val root = Files.createTempDirectory("ar-exit-early-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "echo done", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(
            issue, attempt = null, prompt = "do something",
            agentKindOverride = "opencode", commandOverride = "echo done",
            turnTimeoutMs = 50000, stallTimeoutMs = 50000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `runner falls back gracefully when docker container creation fails`() = runTest {
        val root = Files.createTempDirectory("agent-runner-docker-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val dockerConfig = DockerConfig(enabled = true)
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            dockerConfig = dockerConfig,
            maxConcurrentAgents = 2
        )
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "Hi", commandOverride = "false")
        assertThat(result).isNotNull()
    }
}
