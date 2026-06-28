package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.errors.AgentErrorType
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import com.flexsentlabs.koncerto.core.errors.SubscriptionLimitException
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
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

    @Test
    fun `runner uses docker container when creation succeeds`() = runBlocking {
        val root = Files.createTempDirectory("agent-runner-docker-ok-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val dockerConfig = DockerConfig(enabled = true, network = false)
        AgentDockerFake.withFakeDocker(AgentDockerFake.agentDockerScript()) {
            val runner = DefaultAgentRunner(
                config, mgr, noopLogger(),
                dockerConfig = dockerConfig,
                maxConcurrentAgents = 2
            )
            runBlocking {
                val result = runner.run(
                    sampleIssue(), attempt = 1, prompt = "Hi",
                    agentKindOverride = "opencode", commandOverride = script,
                    turnTimeoutMs = 5000, stallTimeoutMs = 5000
                )
                assertThat(result.exceptionOrNull()).isNull()
            }
        }
    }

    @Test
    fun `runner completes codex exec command via writeRaw path`() = runBlocking {
        val root = Files.createTempDirectory("ar-codex-exec-")
        val script = root.resolve("fake-codex.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            if [[ "${'$'}*" == *exec* ]]; then
              read -r _ || true
              echo '{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}'
              exit 0
            fi
            exit 1
            """.trimIndent()
        )
        script.toFile().setExecutable(true)
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "codex exec --json", agentKind = "codex")
        val runner = DefaultAgentRunner(config, mgr, noopLogger(), maxRetries = 1)
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Do the task",
            agentKindOverride = "codex", commandOverride = "$script exec --json",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()).isNull()
    }

    @Test
    fun circuitBreakerBlocksRunWhenOpen() {
        runBlocking {
            val root = Files.createTempDirectory("ar-circuit-")
            val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
            val breaker = com.flexsentlabs.koncerto.core.agent.AgentCircuitBreaker(failureThreshold = 1, resetTimeoutMs = 60_000)
            val agentKey = "opencode:echo hi:default"
            breaker.recordFailure(agentKey)
            val config = sampleConfig(command = "echo hi", agentKind = "opencode")
            val runner = DefaultAgentRunner(
                config, mgr, noopLogger(),
                circuitBreaker = breaker,
                maxRetries = 1
            )
            val result = runner.run(
                sampleIssue(), attempt = 1, prompt = "Hi",
                agentKindOverride = "opencode", commandOverride = "echo hi",
                turnTimeoutMs = 5000, stallTimeoutMs = 5000
            )
            assertThat(result.exceptionOrNull()?.message.orEmpty()).contains("circuit_breaker_open")
        }
    }

    @Test
    fun classifyOutputLineDetectsSubscriptionLimit() {
        val root = Files.createTempDirectory("ar-classify-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val classifier = PatternErrorClassifier(
            listOf(
                PatternErrorClassifier.ClassificationPattern(
                    regex = Regex("usage limit", RegexOption.IGNORE_CASE),
                    build = { _, msg -> AgentErrorType.SubscriptionLimitError(details = msg) }
                )
            )
        )
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger(), errorClassifier = classifier)
        val method = DefaultAgentRunner::class.java.getDeclaredMethod(
            "classifyOutputLine",
            String::class.java,
            String::class.java,
            String::class.java,
            java.util.concurrent.atomic.AtomicReference::class.java
        )
        method.isAccessible = true
        val hit = java.util.concurrent.atomic.AtomicReference<String?>(null)
        method.invoke(runner, "You've hit your usage limit", "issue-1", "codex", hit)
        assertThat(hit.get()).isNotNull()
    }

    @Test
    fun `runner retries once before failing on persistent error`() = runBlocking {
        val root = Files.createTempDirectory("ar-retry-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger(), maxRetries = 2, retryDelayMs = 1)
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 2000, stallTimeoutMs = 2000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `runner skips pr when clarification file exists`() = runBlocking {
        val root = Files.createTempDirectory("ar-clarify-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val ws = mgr.ensureWorkspace("ABC-1")
        val clarification = ws.path.resolve(".koncerto/clarification.md")
        Files.createDirectories(clarification.parent)
        Files.writeString(clarification, "Need more detail")
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger(), maxRetries = 1)
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()).isNull()
        assertThat(Files.exists(clarification)).isTrue()
    }

    @Test
    fun `runner uses docker container with network enabled`() = runTest {
        val root = Files.createTempDirectory("agent-runner-docker-net-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val dockerConfig = DockerConfig(enabled = true, network = true)
        AgentDockerFake.withFakeDocker(AgentDockerFake.agentDockerScript()) {
            val runner = DefaultAgentRunner(
                config, mgr, noopLogger(),
                dockerConfig = dockerConfig,
                maxConcurrentAgents = 2
            )
            runBlocking {
                val result = runner.run(
                    sampleIssue(), attempt = 1, prompt = "Hi",
                    agentKindOverride = "opencode", commandOverride = script,
                    turnTimeoutMs = 5000, stallTimeoutMs = 5000
                )
                assertThat(result.exceptionOrNull()).isNull()
            }
        }
    }

    @Test
    fun `toTemplateMap includes issue fields for prompt rendering`() {
        val root = Files.createTempDirectory("ar-template-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger())
        val method = DefaultAgentRunner::class.java.getDeclaredMethod("toTemplateMap", Issue::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = method.invoke(runner, sampleIssue()) as Map<String, Any?>
        assertThat(map["identifier"]).isEqualTo("ABC-1")
        assertThat(map["title"]).isEqualTo("Test issue")
        assertThat(map["labels"]).isEqualTo(listOf("bug"))
    }

    @Test
    fun `run retries with backoff before failing on persistent errors`() = runBlocking {
        val root = Files.createTempDirectory("ar-backoff-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val runner = DefaultAgentRunner(config, mgr, noopLogger(), maxRetries = 2, retryDelayMs = 1)
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 2000, stallTimeoutMs = 2000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `run throws startup_failed when runtime start returns false`() = runBlocking {
        val mockFactory = mockk<AgentRuntimeFactory>()
        val mockRuntime = mockk<AgentRuntime>(relaxed = true)
        every {
            mockFactory.create(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockRuntime
        coEvery { mockRuntime.start(any()) } returns false

        val root = Files.createTempDirectory("ar-startup-fail-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger(), runtimeFactory = mockFactory)
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()?.message.orEmpty()).contains("startup_failed")
    }

    @Test
    fun `run delegates to modelRetryHandler when model override is free`() = runBlocking {
        val root = Files.createTempDirectory("ar-free-model-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val mockFactory = mockk<AgentRuntimeFactory>()
        val mockRuntime = mockk<AgentRuntime>(relaxed = true)
        val outputFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val eventsChannel = kotlinx.coroutines.channels.Channel<AgentEvent>(8)
        every {
            mockFactory.create(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockRuntime
        coEvery { mockRuntime.start(any()) } returns true
        every { mockRuntime.output } returns outputFlow.asSharedFlow()
        every { mockRuntime.events() } returns eventsChannel.receiveAsFlow()
        every { mockRuntime.send(any(), any()) } answers {
            runBlocking {
                eventsChannel.send(AgentEvent.TurnCompleted("t1", "u1", null, null))
            }
            "1"
        }
        val project = sampleConfig().projects["default"]!!
        val cycler = FreeModelCycler(listOf("opencode-free-1"), 3, noopLogger())
        val handler = ModelRetryHandler(
            cycler, project,
            mockk<TrackerClient>(relaxed = true),
            mockk<CompositeNotifier>(relaxed = true),
            noopLogger()
        )
        val runner = DefaultAgentRunner(
            sampleConfig(), mgr, noopLogger(),
            runtimeFactory = mockFactory,
            modelRetryHandler = handler,
            maxRetries = 1
        )
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "ignored",
            modelOverride = "free",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()).isNull()
    }

    @Test
    fun `run throws subscription limit when detected in agent output`() = runBlocking {
        val root = Files.createTempDirectory("ar-sub-limit-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val classifier = PatternErrorClassifier(
            listOf(
                PatternErrorClassifier.ClassificationPattern(
                    regex = Regex("usage limit", RegexOption.IGNORE_CASE),
                    build = { _, msg -> AgentErrorType.SubscriptionLimitError(details = msg) }
                )
            )
        )
        val mockFactory = mockk<AgentRuntimeFactory>()
        val mockRuntime = mockk<AgentRuntime>(relaxed = true)
        val outputFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val eventsChannel = kotlinx.coroutines.channels.Channel<AgentEvent>(8)
        every {
            mockFactory.create(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockRuntime
        coEvery { mockRuntime.start(any()) } returns true
        every { mockRuntime.output } returns outputFlow.asSharedFlow()
        every { mockRuntime.events() } returns eventsChannel.receiveAsFlow()
        every { mockRuntime.send(any(), any()) } answers {
            runBlocking {
                outputFlow.emit("[stderr] You've hit your usage limit")
                eventsChannel.send(AgentEvent.TurnCompleted("t1", "u1", null, null))
            }
            "1"
        }

        val runner = DefaultAgentRunner(
            sampleConfig(), mgr, noopLogger(),
            runtimeFactory = mockFactory,
            errorClassifier = classifier,
            maxRetries = 1
        )
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull() is SubscriptionLimitException).isTrue()
    }

    @Test
    fun `run invokes onAgentOutput callback for each line`() = runBlocking {
        val root = Files.createTempDirectory("ar-output-cb-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val captured = mutableListOf<String>()
        val runner = DefaultAgentRunner(
            sampleConfig(command = script, agentKind = "opencode"),
            mgr, noopLogger(),
            onAgentOutput = { _, line -> captured.add(line) },
            maxRetries = 1
        )
        runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(captured.isNotEmpty()).isTrue()
    }

    @Test
    fun `toTemplateMap includes blockedBy references`() {
        val root = Files.createTempDirectory("ar-template-block-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger())
        val method = DefaultAgentRunner::class.java.getDeclaredMethod("toTemplateMap", Issue::class.java)
        method.isAccessible = true
        val issue = sampleIssue().copy(
            blockedBy = listOf(
                com.flexsentlabs.koncerto.core.model.BlockerRef("b1", "ABC-0", "Done")
            )
        )
        @Suppress("UNCHECKED_CAST")
        val map = method.invoke(runner, issue) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val blockers = map["blocked_by"] as List<Map<String, Any?>>
        assertThat(blockers.single()["identifier"]).isEqualTo("ABC-0")
    }

    @Test
    fun `run completes with git workflow enabled`() = runBlocking {
        val root = Files.createTempDirectory("ar-git-pr-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val wsPath = root.resolve("ABC-1")
        Files.createDirectories(wsPath)
        ProcessBuilder("git", "init", "--initial-branch=main")
            .directory(wsPath.toFile()).redirectErrorStream(true).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "test@test.com")
            .directory(wsPath.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Test")
            .directory(wsPath.toFile()).start().waitFor()
        ProcessBuilder("git", "commit", "--allow-empty", "-m", "initial")
            .directory(wsPath.toFile()).start().waitFor()
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val gitWorkflow = GitWorkflow(
            GitConfig(enabled = true, autoCommit = true, createPr = true),
            noopLogger()
        )
        val runner = DefaultAgentRunner(
            sampleConfig(command = script, agentKind = "opencode"),
            mgr, noopLogger(),
            gitWorkflow = gitWorkflow,
            maxRetries = 1
        )
        val result = runner.run(
            sampleIssue(), attempt = 1, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(result.exceptionOrNull()).isNull()
    }
}

private object AgentDockerFake {
    fun withFakeDocker(script: String, block: () -> Unit) {
        val binDir = Files.createTempDirectory("fake-agent-docker-bin")
        val docker = binDir.resolve("docker")
        Files.writeString(docker, script)
        docker.toFile().setExecutable(true)
        val originalPath = System.getenv("PATH") ?: ""
        try {
            prependPath("$binDir:$originalPath")
            block()
        } finally {
            prependPath(originalPath)
            binDir.toFile().deleteRecursively()
        }
    }

    private fun prependPath(path: String) {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        val env = pe.getDeclaredField("theEnvironment")
        env.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (env.get(null) as MutableMap<String, String>)["PATH"] = path
        try {
            val ciEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment")
            ciEnv.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (ciEnv.get(null) as MutableMap<String, String>)["PATH"] = path
        } catch (_: NoSuchFieldException) {
        }
    }

    fun agentDockerScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  info) exit 0 ;;
  run)
    echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    exit 0
    ;;
  exec)
    while [ "${'$'}#" -gt 0 ]; do
      if [ "${'$'}1" = "-lc" ]; then
        echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
        echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        exit 0
      fi
      shift
    done
    exit 0
    ;;
  inspect) echo "running"; exit 0 ;;
  rm) exit 0 ;;
esac
exit 0
"""
}
