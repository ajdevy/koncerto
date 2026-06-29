package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.confirmVerified
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    @Test
    fun `handleExhaustion blocks issue in linear and sends notification`() = runTest {
        val root = Files.createTempDirectory("model-retry-linear-")
        val config = sampleConfig().projects["default"]!!.copy(
            workspace = WorkspaceConfig(root = root.toString()),
            tracker = sampleConfig().projects["default"]!!.tracker.copy(blockedState = "Blocked"),
        )
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>(relaxed = true)
        coEvery { linearClient.createComment(any(), any()) } just runs
        coEvery { linearClient.resolveStateId("p", "Blocked") } returns "blocked-state-id"
        coEvery { linearClient.updateIssueState("issue-1", "blocked-state-id") } returns Unit
        coEvery { linearClient.fetchIssueById("issue-1") } returns Issue(
            id = "issue-1",
            identifier = "FLE-1",
            title = "Test issue",
            description = null,
            priority = null,
            state = "Todo",
            branchName = null,
            url = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        coEvery { notifier.send(any()) } returns Unit

        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())

        val result = handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(Exception("model failed"))
        }

        assertThat(result is Result.Failure).isTrue()
        coVerify { linearClient.updateIssueState("issue-1", "blocked-state-id") }
        coVerify { notifier.send(match { it is NotificationEvent.AgentFailed }) }
    }

    @Test
    fun `handleExhaustion continues when blocked state is missing`() = runTest {
        val root = Files.createTempDirectory("model-retry-no-state-")
        val config = sampleConfig().projects["default"]!!.copy(
            workspace = WorkspaceConfig(root = root.toString()),
        )
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>(relaxed = true)
        coEvery { linearClient.resolveStateId(any(), any()) } returns null
        coEvery { linearClient.fetchIssueById(any()) } returns null

        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())

        val result = handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(Exception("model failed"))
        }

        assertThat(result is Result.Failure).isTrue()
        coVerify(exactly = 0) { linearClient.updateIssueState(any(), any()) }
    }

    @Test
    fun `executeWithRetry applies backoff before retrying same model`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a"), 3, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, noopLogger())

        var attempts = 0
        val result = handler.executeWithRetry("issue-1") { _ ->
            attempts++
            Result.Failure(Exception("transient"))
        }

        assertThat(result is Result.Failure).isTrue()
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `handleExhaustion posts comment before transitioning to blocked state`() = runTest {
        val root = Files.createTempDirectory("model-retry-comment-")
        val config = sampleConfig().projects["default"]!!.copy(workspace = WorkspaceConfig(root = root.toString()))
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()

        coEvery { linearClient.resolveStateId(any(), any()) } returns "blocked-state-id"
        coEvery { linearClient.createComment(any(), any()) } just runs
        coEvery { linearClient.updateIssueState(any(), any()) } just runs
        coEvery { linearClient.fetchIssueById(any()) } returns null

        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())

        handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(ModelExhaustedException(listOf("model-a"), 3, "rate limit"))
        }

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            linearClient.createComment("issue-1", match { it.contains("exhausted") || it.contains("model") })
            linearClient.updateIssueState("issue-1", "blocked-state-id")
        }

        root.toFile().deleteRecursively()
    }

    @Test
    fun `handleExhaustion still transitions to blocked even if comment fails`() = runTest {
        val root = Files.createTempDirectory("model-retry-comment-fail-")
        val config = sampleConfig().projects["default"]!!.copy(workspace = WorkspaceConfig(root = root.toString()))
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()

        coEvery { linearClient.resolveStateId(any(), any()) } returns "blocked-state-id"
        coEvery { linearClient.createComment(any(), any()) } throws RuntimeException("API down")
        coEvery { linearClient.updateIssueState(any(), any()) } just runs
        coEvery { linearClient.fetchIssueById(any()) } returns null

        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())

        handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(ModelExhaustedException(listOf("model-a"), 1, "err"))
        }

        coVerify { linearClient.updateIssueState("issue-1", "blocked-state-id") }

        root.toFile().deleteRecursively()
    }

    @Test
    fun `handleExhaustion continues when notification send fails`() = runTest {
        val root = Files.createTempDirectory("model-retry-notify-fail-")
        val config = sampleConfig().projects["default"]!!.copy(workspace = WorkspaceConfig(root = root.toString()))
        val cycler = FreeModelCycler(listOf("model-a"), 1, noopLogger())
        val linearClient = mockk<TrackerClient>()
        val notifier = mockk<CompositeNotifier>()

        coEvery { linearClient.createComment(any(), any()) } just runs
        coEvery { linearClient.resolveStateId(any(), any()) } returns "blocked-state-id"
        coEvery { linearClient.updateIssueState(any(), any()) } just runs
        coEvery { linearClient.fetchIssueById(any()) } returns Issue(
            id = "issue-1",
            identifier = "FLE-1",
            title = "Test issue",
            description = null,
            priority = null,
            state = "Todo",
            branchName = null,
            url = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        coEvery { notifier.send(any()) } throws RuntimeException("notify down")

        val handler = ModelRetryHandler(cycler, config, linearClient, notifier, noopLogger())
        val result = handler.executeWithRetry("issue-1") { _ ->
            Result.Failure(ModelExhaustedException(listOf("model-a"), 1, "err"))
        }

        assertThat(result is Result.Failure).isTrue()
        coVerify { linearClient.updateIssueState("issue-1", "blocked-state-id") }
        root.toFile().deleteRecursively()
    }

    @Test
    fun `executeWithRetry throws illegal state when model selection fails unexpectedly`() = runTest {
        val cycler = mockk<FreeModelCycler>()
        val linearClient = mockk<TrackerClient>(relaxed = true)
        val notifier = mockk<CompositeNotifier>(relaxed = true)
        val logger = noopLogger()
        @Suppress("UNCHECKED_CAST")
        val unexpectedFailure = Result.Failure(Exception("selection failed")) as Result<String, ModelExhaustedException>
        coEvery { cycler.nextModel() } returns unexpectedFailure
        coEvery { cycler.getStatus() } returns mapOf("retry_counts" to emptyMap<String, Int>())
        coEvery { cycler.reportFailure(any(), any()) } just runs
        val handler = ModelRetryHandler(cycler, sampleConfig().projects["default"]!!, linearClient, notifier, logger)

        val ex = assertThrows<IllegalStateException> {
            runBlocking {
                handler.executeWithRetry("issue-1") { Result.Success(Unit) }
            }
        }
        assertThat(ex.message).isEqualTo("Unexpected failure in model selection")
    }
}
