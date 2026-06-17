package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import io.mockk.confirmVerified
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ModelRetryHandlerTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun sampleConfig(): ServiceConfig = ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf("default" to ProjectConfig(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
                requiredLabels = emptyList(), activeStates = listOf("Todo"), terminalStates = listOf("Done")
            ),
            workspace = WorkspaceConfig(root = "/tmp"),
            agent = AgentProjectConfig(
                kind = "opencode", command = "opencode",
                maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300000,
                maxConcurrentAgentsByState = emptyMap(),
                turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                stages = emptyMap()
            )
        )),
        hooks = HooksConfig(null, null, null, null, 60000),
        gitConfig = GitConfig()
    )

    @Test
    fun `executeWithRetry returns success on first attempt`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a"), 3, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, noopLogger())

        val result = handler.executeWithRetry("issue-1") { model ->
            Result.Success(Unit)
        }

        assertThat(result is Result.Success).isTrue()
    }

    @Test
    fun `executeWithRetry retries on failure and succeeds`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 3, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, noopLogger())

        var attempt = 0
        val result = handler.executeWithRetry("issue-1") { model ->
            attempt++
            if (attempt == 1) {
                Result.Failure(Exception("first failure"))
            } else {
                Result.Success(Unit)
            }
        }

        assertThat(result is Result.Success).isTrue()
        assertThat(attempt).isEqualTo(2)
    }

    @Test
    fun `executeWithRetry cycles through models on repeated failures`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, noopLogger())

        var attempts = 0
        val result = handler.executeWithRetry("issue-1") { model ->
            attempts++
            Result.Failure(Exception("failure $attempts"))
        }

        assertThat(result is Result.Failure).isTrue()
        val failure = result as Result.Failure<Exception>
        assertThat(failure.error is ModelExhaustedException).isTrue()
    }

    @Test
    fun `handleExhaustion writes status file`() = runTest {
        val root = Files.createTempDirectory("model-retry-")
        val config = sampleConfig().projects["default"]!!.copy(workspace = WorkspaceConfig(root = root.toString()))
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())

        val exhausted = ModelExhaustedException(listOf("model-a"), 1, "test error")
        val result = handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(exhausted)
        }

        assertThat(result is Result.Failure).isTrue()
        val statusFile = root.resolve(".model-exhausted-issue-1")
        assertThat(Files.exists(statusFile)).isTrue()
    }

    @Test
    fun `executeWithRetry handles exhaustion gracefully`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, noopLogger())

        val exhausted = ModelExhaustedException(listOf("model-a"), 1, "test error")
        val result = handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(exhausted)
        }

        assertThat(result is Result.Failure).isTrue()
    }
}
