package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.agent.AgentCircuitBreaker
import com.flexsentlabs.koncerto.core.errors.AgentErrorType
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
            errorClassifier = PatternErrorClassifier(),
            heartbeatIntervalMs = 100
        )
        val events = AgentRuntimeTestSupport.collectRunnerEventsDuring(
            runner,
            until = { collected -> collected.any { it is AgentEvent.LimitDetected } },
        ) {
            runner.run(
                sampleIssue(), attempt = 1, prompt = "Fix it",
                agentKindOverride = "opencode", commandOverride = script,
                turnTimeoutMs = 50_000, stallTimeoutMs = 50_000
            )
        }
        val captured = events.filterIsInstance<AgentEvent.LimitDetected>().firstOrNull()
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
            errorClassifier = PatternErrorClassifier(),
            heartbeatIntervalMs = 100
        )
        val events = AgentRuntimeTestSupport.collectRunnerEventsDuring(
            runner,
            until = { collected -> collected.any { it is AgentEvent.LimitDetected } },
        ) {
            runner.run(
                sampleIssue(), attempt = 1, prompt = "Fix it",
                agentKindOverride = "opencode", commandOverride = script,
                turnTimeoutMs = 50_000, stallTimeoutMs = 50_000
            )
        }
        val captured = events.filterIsInstance<AgentEvent.LimitDetected>().firstOrNull()
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
        val events = AgentRuntimeTestSupport.collectRunnerEventsDuring(
            runner,
            timeoutMs = 3_000,
            until = { true },
        ) {
            runner.run(
                sampleIssue(), attempt = 1, prompt = "Fix it",
                agentKindOverride = "opencode", commandOverride = script,
                turnTimeoutMs = 50_000, stallTimeoutMs = 50_000
            )
        }
        assertThat(events.filterIsInstance<AgentEvent.LimitDetected>()).isEmpty()
    }

    @Test
    fun `runner emits limit detected on exception classify`() = runBlocking {
        val root = Files.createTempDirectory("ar-exc-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val classifier = PatternErrorClassifier(
            PatternErrorClassifier.DEFAULT_PATTERNS + PatternErrorClassifier.ClassificationPattern(
                regex = Regex("agent_process_died"),
                build = { _, msg -> AgentErrorType.TransientError(details = msg) },
            )
        )
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            errorClassifier = classifier,
            maxRetries = 1,
            retryDelayMs = 100,
            heartbeatIntervalMs = 100
        )
        val events = AgentRuntimeTestSupport.collectRunnerEventsDuring(
            runner,
            timeoutMs = 5_000,
            until = { collected -> collected.any { it is AgentEvent.LimitDetected } },
        ) {
            try {
                runner.run(
                    sampleIssue(), attempt = null, prompt = "Hi",
                    agentKindOverride = "opencode", commandOverride = "false",
                    turnTimeoutMs = 5_000, stallTimeoutMs = 5_000
                )
            } catch (_: Exception) {
                // Expected when the stub agent exits immediately.
            }
        }
        val captured = events.filterIsInstance<AgentEvent.LimitDetected>().firstOrNull()
        assertThat(captured).isNotNull()
        assertThat(captured!!.agentError.type is AgentErrorType.TransientError).isTrue()
    }

    @Test
    fun `rate limit failure does not trip circuit breaker`() = runBlocking {
        val root = Files.createTempDirectory("ar-cb-rate-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val cb = AgentCircuitBreaker(failureThreshold = 2, resetTimeoutMs = 60_000)
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
            maxRetries = 2,
            heartbeatIntervalMs = 100
        )
        val issue = sampleIssue()
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5_000, stallTimeoutMs = 5_000
        )
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5_000, stallTimeoutMs = 5_000
        )
        assertThat(cb.allowRequest("opencode:false:default")).isTrue()
    }

    @Test
    fun `unclassified failure trips circuit breaker`() = runBlocking {
        val root = Files.createTempDirectory("ar-cb-unclass-")
        Files.createDirectories(root.resolve("ABC-1"))
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false", agentKind = "opencode")
        val cb = AgentCircuitBreaker(failureThreshold = 2, resetTimeoutMs = 60_000)
        val runner = DefaultAgentRunner(
            config, mgr, noopLogger(),
            circuitBreaker = cb,
            maxRetries = 1,
            heartbeatIntervalMs = 100
        )
        val issue = sampleIssue()
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5_000, stallTimeoutMs = 5_000
        )
        runner.run(
            issue, attempt = null, prompt = "Hi",
            agentKindOverride = "opencode", commandOverride = "false",
            turnTimeoutMs = 5_000, stallTimeoutMs = 5_000
        )
        assertThat(cb.allowRequest("opencode:false:default")).isFalse()
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
                    maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300_000,
                    maxConcurrentAgentsByState = emptyMap(),
                    turnTimeoutMs = 3_600_000, readTimeoutMs = 5_000, stallTimeoutMs = 300_000,
                    stages = emptyMap()
                )
            )
            return ServiceConfig(
                pollIntervalMs = 30_000,
                projects = mapOf("default" to projectConfig),
                hooks = HooksConfig(null, null, null, null, 60_000),
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
