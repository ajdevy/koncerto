package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.config.AgentProjectConfig
import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.agent.AgentCircuitBreaker
import com.anomaly.koncerto.core.errors.AgentErrorType
import com.anomaly.koncerto.core.errors.PatternErrorClassifier
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentRunnerLimitDetectionTest {

    @Test
    fun `runner emits limit detected on stderr rate limit`() = runBlocking {
        val root = Files.createTempDirectory("ar-limit-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo "rate limit exceeded" >&2
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = PatternErrorClassifier()
        )
        val latch = CountDownLatch(1)
        var captured: AgentEvent.LimitDetected? = null
        val collectorJob = launch {
            runner.events().collect { event ->
                if (event is AgentEvent.LimitDetected) {
                    captured = event
                    latch.countDown()
                }
            }
        }
        val issue = sampleIssue()
        runner.run(
            issue, attempt = 1, prompt = "Fix it",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 50000
        )
        collectorJob.cancel()
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(captured).isNotNull()
        assertThat(captured!!.agentError.type is AgentErrorType.RateLimitError).isTrue()
    }

    @Test
    fun `runner emits limit detected on stderr auth error`() = runBlocking {
        val root = Files.createTempDirectory("ar-auth-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo "Unauthorized: invalid API key" >&2
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = PatternErrorClassifier()
        )
        val latch = CountDownLatch(1)
        var captured: AgentEvent.LimitDetected? = null
        val collectorJob = launch {
            runner.events().collect { event ->
                if (event is AgentEvent.LimitDetected) {
                    captured = event
                    latch.countDown()
                }
            }
        }
        val issue = sampleIssue()
        runner.run(
            issue, attempt = 1, prompt = "Fix it",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 50000
        )
        collectorJob.cancel()
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(captured).isNotNull()
        assertThat(captured!!.agentError.type is AgentErrorType.AuthError).isTrue()
    }

    @Test
    fun `runner does not emit limit detected on normal stderr`() = runBlocking {
        val root = Files.createTempDirectory("ar-normal-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo "normal diagnostic info" >&2
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script, agentKind = "opencode")
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = PatternErrorClassifier()
        )
        val latch = CountDownLatch(1)
        val limitEvents = mutableListOf<AgentEvent.LimitDetected>()
        val collectorJob = launch {
            runner.events().collect { event ->
                if (event is AgentEvent.LimitDetected) {
                    limitEvents.add(event)
                    latch.countDown()
                }
            }
        }
        val issue = sampleIssue()
        runner.run(
            issue, attempt = 1, prompt = "Fix it",
            agentKindOverride = "opencode", commandOverride = script,
            turnTimeoutMs = 50000, stallTimeoutMs = 50000
        )
        collectorJob.cancel()
        val triggered = latch.await(3, TimeUnit.SECONDS)
        assertThat(triggered).isFalse()
        assertThat(limitEvents).isEmpty()
    }

    @Test
    fun `runner emits limit detected on exception classify`() = runBlocking {
        val root = Files.createTempDirectory("ar-exc-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = PatternErrorClassifier(),
            maxRetries = 1,
            retryDelayMs = 100
        )
        val latch = CountDownLatch(1)
        var captured: AgentEvent.LimitDetected? = null
        val collectorJob = launch {
            runner.events().collect { event ->
                if (event is AgentEvent.LimitDetected) {
                    captured = event
                    latch.countDown()
                }
            }
        }
        val issue = sampleIssue()
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        collectorJob.cancel()
        latch.await(3, TimeUnit.SECONDS)
    }

    @Test
    fun `rate limit failure does not trip circuit breaker`() = runBlocking {
        val root = Files.createTempDirectory("ar-cb-rate-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val cb = AgentCircuitBreaker(failureThreshold = 2, resetTimeoutMs = 60000)
        val alwaysRateLimit = PatternErrorClassifier(
            listOf(PatternErrorClassifier.ClassificationPattern(
                regex = Regex(".*"),
                build = { _, msg -> AgentErrorType.RateLimitError(details = msg, retryAfterMs = 100) }
            ))
        )
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = alwaysRateLimit,
            circuitBreaker = cb,
            maxRetries = 2
        )
        val issue = sampleIssue()
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(cb.allowRequest("opencode:false")).isTrue()
    }

    @Test
    fun `unclassified failure trips circuit breaker`() = runBlocking {
        val root = Files.createTempDirectory("ar-cb-unclass-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val cb = AgentCircuitBreaker(failureThreshold = 2, resetTimeoutMs = 60000)
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            circuitBreaker = cb,
            maxRetries = 1
        )
        val issue = sampleIssue()
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5000, stallTimeoutMs = 5000
        )
        assertThat(cb.allowRequest("opencode:false")).isFalse()
    }

    companion object {
        private fun noopLogger() = StructuredLogger(
            listOf(object : LogSink {
                override fun write(line: String) {}
            })
        )

        private fun sampleConfig(
            command: String = "codex app-server",
            agentKind: String = "codex"
        ): ServiceConfig {
            val projectConfig = ProjectConfig(
                tracker = TrackerConfig(
                    kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
                    requiredLabels = emptyList(),
                    activeStates = listOf("Todo"), terminalStates = listOf("Done")
                ),
                workspace = WorkspaceConfig(root = "/tmp"),
                agent = AgentProjectConfig(
                    kind = agentKind, command = command,
                    maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300000,
                    maxConcurrentAgentsByState = emptyMap(),
                    turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                    stages = emptyMap()
                )
            )
            return ServiceConfig(
                pollIntervalMs = 30000,
                projects = mapOf("default" to projectConfig),
                hooks = HooksConfig(null, null, null, null, 60000),
                gitConfig = GitConfig()
            )
        }

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
    }
}
