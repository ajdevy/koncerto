package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.TokenUsage
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.WorkflowDefinition
import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class OrchestratorTest {

    @Test
    fun `dispatch eligible issues and skip ineligible ones`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi {{ issue.identifier }}")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo"),
                sampleIssue("2", "A-2", "Done"),
                sampleIssue("3", "A-3", "Todo").copy(priority = 1)
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-3", "A-1")
    }

    @Test
    fun `dispatch respects maxConcurrentAgentsByState`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(maxConcurrentAgentsByState = mapOf("todo" to 1))
        val state = RuntimeState()
        state.running["existing"] = runningEntry("existing", "X-1").let {
            it.copy(issue = it.issue.copy(state = "Todo"))
        }
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo"),
                sampleIssue("2", "A-2", "Todo")
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch respects per-state cap when under limit`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(maxConcurrentAgentsByState = mapOf("todo" to 2))
        val state = RuntimeState()
        state.running["existing"] = runningEntry("existing", "X-1").let {
            it.copy(issue = it.issue.copy(state = "Todo"))
        }
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo"),
                sampleIssue("2", "A-2", "Todo")
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(2)
    }

    @Test
    fun `dispatch skips already claimed issues`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.claimed.add("1")
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(listOf(sampleIssue("1", "A-1", "Todo")))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch skips already running issues`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(listOf(sampleIssue("1", "A-1", "Todo")))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `reconcile removes terminal state from running`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        state.claimed.add("1")
        val linear = FakeLinearClientWithStates(mapOf("1" to "Done"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.scope = CoroutineScope(coroutineContext)
        orch.reconcile()
        assertThat(state.running.containsKey("1")).isEqualTo(false)
        assertThat(state.claimed.contains("1")).isEqualTo(false)
    }

    @Test
    fun `reconcile removes non-active state from running`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        state.claimed.add("1")
        val linear = FakeLinearClientWithStates(mapOf("1" to "InvalidState"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.scope = CoroutineScope(coroutineContext)
        orch.reconcile()
        assertThat(state.running.containsKey("1")).isEqualTo(false)
        assertThat(state.claimed.contains("1")).isEqualTo(false)
    }

    @Test
    fun `reconcile keeps active state in running`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        val linear = FakeLinearClientWithStates(mapOf("1" to "Todo"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.scope = CoroutineScope(coroutineContext)
        orch.reconcile()
        assertThat(state.running.containsKey("1")).isTrue()
    }

    @Test
    fun `reconcile does nothing when running is empty`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClientWithStates(emptyMap())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.scope = CoroutineScope(coroutineContext)
        orch.reconcile()
        assertThat(state.running.size).isEqualTo(0)
    }

    @Test
    fun `reconcile handles fetch error gracefully`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        val linear = FakeLinearClientThrowing()
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.scope = CoroutineScope(coroutineContext)
        orch.reconcile()
        assertThat(state.running.containsKey("1")).isTrue()
    }

    @Test
    fun `handleAgentEvent accumulates token usage from TurnCompleted`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        val event = AgentEvent.TurnCompleted(
            threadId = "t1", turnId = "r1",
            usage = TokenUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
            pid = 1234L
        )
        orch.handleAgentEvent(event)
        assertThat(state.tokenTotals.inputTokens).isEqualTo(100)
        assertThat(state.tokenTotals.outputTokens).isEqualTo(50)
        assertThat(state.tokenTotals.totalTokens).isEqualTo(150)
    }

    @Test
    fun `handleAgentEvent accumulates across multiple TurnCompleted events`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.handleAgentEvent(AgentEvent.TurnCompleted(
            threadId = "t1", turnId = "r1",
            usage = TokenUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
            pid = 1234L
        ))
        orch.handleAgentEvent(AgentEvent.TurnCompleted(
            threadId = "t1", turnId = "r2",
            usage = TokenUsage(inputTokens = 200, outputTokens = 80, totalTokens = 280),
            pid = 1234L
        ))
        assertThat(state.tokenTotals.inputTokens).isEqualTo(300)
        assertThat(state.tokenTotals.outputTokens).isEqualTo(130)
        assertThat(state.tokenTotals.totalTokens).isEqualTo(430)
    }

    @Test
    fun `handleAgentEvent ignores null usage`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.handleAgentEvent(AgentEvent.TurnCompleted(
            threadId = "t1", turnId = "r1", usage = null, pid = 1234L
        ))
        assertThat(state.tokenTotals.inputTokens).isEqualTo(0)
        assertThat(state.tokenTotals.outputTokens).isEqualTo(0)
        assertThat(state.tokenTotals.totalTokens).isEqualTo(0)
    }

    @Test
    fun `handleAgentEvent ignores non-TurnCompleted events`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.handleAgentEvent(AgentEvent.TurnFailed(
            threadId = "t1", turnId = "r1", error = "boom", pid = 1234L
        ))
        assertThat(state.tokenTotals.inputTokens).isEqualTo(0)
    }

    @Test
    fun `dispatch completion removes from claimed and adds to completed`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(listOf(sampleIssue("1", "A-1", "Todo")))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(state.claimed.contains("1")).isEqualTo(false)
        assertThat(state.completed.contains("1")).isTrue()
    }

    @Test
    fun `scheduleRetry creates retry entry with exponential backoff`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        val issue = sampleIssue("1", "A-1", "Todo")
        val beforeMs = System.currentTimeMillis()
        orch.dispatchService.scheduleRetry(issue, "timeout error")
        val entry = state.retryAttempts["1"]
        assertThat(entry).isNotNull()
        assertThat(entry!!.attempt).isEqualTo(1)
        assertThat(entry.error).isEqualTo("timeout error")
        assertThat(entry.identifier).isEqualTo("A-1")
        assertThat(entry.dueAtMs >= beforeMs + 10_000).isTrue()
    }

    @Test
    fun `scheduleRetry increments attempt on subsequent retries`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        val issue = sampleIssue("1", "A-1", "Todo")
        orch.dispatchService.scheduleRetry(issue, "err1")
        orch.dispatchService.scheduleRetry(issue, "err2")
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(2)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("err2")
    }

    @Test
    fun `scheduleRetry caps backoff at maxRetryBackoffMs`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(maxRetryBackoffMs = 60_000)
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        val issue = sampleIssue("1", "A-1", "Todo")
        repeat(10) { orch.dispatchService.scheduleRetry(issue, "err") }
        val entry = state.retryAttempts["1"]
        assertThat(entry).isNotNull()
        assertThat(entry!!.attempt).isEqualTo(10)
        val maxDue = System.currentTimeMillis() + 60_000 + 1000
        assertThat(entry.dueAtMs <= maxDue).isTrue()
    }

    @Test
    fun `matchesRequiredLabels skips issues without required labels`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(requiredLabels = listOf("bugfix"))
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo").copy(labels = listOf("feature")),
                sampleIssue("2", "A-2", "Todo").copy(labels = listOf("bugfix")),
                sampleIssue("3", "A-3", "Todo").copy(labels = emptyList())
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-2")
    }

    @Test
    fun `matchesRequiredLabels is case insensitive`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(requiredLabels = listOf("BugFix"))
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(sampleIssue("1", "A-1", "Todo").copy(labels = listOf("bugfix")))
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `isBlockedForTodo skips todo issues with non-terminal blockers`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "In Progress"))
                ),
                sampleIssue("2", "A-2", "Todo").copy(blockedBy = emptyList())
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-2")
    }

    @Test
    fun `isBlockedForTodo allows todo issues with terminal blockers`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "Done"))
                )
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `isBlockedForTodo treats null blocker state as blocked`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = null))
                )
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `isBlockedForTodo only applies to todo state`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(activeStates = listOf("Todo", "In Progress"))
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "In Progress").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "Todo"))
                )
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        yield()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `dispatch failure triggers scheduleRetry`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(listOf(sampleIssue("1", "A-1", "Todo")))
        val runner = FailingAgentRunner("agent crashed")
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(state.retryAttempts.containsKey("1")).isTrue()
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(1)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("agent crashed")
    }

    @Test
    fun `fetchCandidateIssues failure does not crash`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache()
        val linear = FakeLinearClientThrowing()
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `available slots exhausted stops dispatch`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.maxConcurrentAgents = 2
        state.running["x"] = runningEntry("x", "X-1")
        state.running["y"] = runningEntry("y", "X-2")
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(
                sampleIssue("1", "A-1", "Todo"),
                sampleIssue("2", "A-2", "Todo")
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    private fun sampleIssue(id: String, identifier: String, state: String) = Issue(
        id = id, identifier = identifier, title = "t", description = null,
        priority = 5, state = state, branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private fun sampleConfig() = ServiceConfig(
        trackerKind = "linear", trackerEndpoint = "x", trackerApiKey = "k", trackerProjectSlug = "p",
        requiredLabels = emptyList(),
        activeStates = listOf("Todo"), terminalStates = listOf("Done"),
        pollIntervalMs = 30000,
        workspaceRoot = java.nio.file.Path.of("/tmp"),
        hooks = HooksConfig(null, null, null, null, 60000),
        maxConcurrentAgents = 10, maxTurns = 1, maxRetryBackoffMs = 300000,
        maxConcurrentAgentsByState = emptyMap(),
        agentKind = "codex",
        codexCommand = "codex app-server", codexApprovalPolicy = null,
        codexThreadSandbox = null, codexTurnSandboxPolicy = null,
            opencodeCommand = "opencode",
            turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
            stages = emptyMap()
        )

    private fun runningEntry(id: String, identifier: String) = RunningEntry(
        issue = Issue(
            id = id, identifier = identifier, title = "t", description = null,
            priority = 5, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        ),
        threadId = "thread-$id",
        turnId = "turn-$id",
        startedAt = java.time.Instant.now(),
        lastCodexTimestamp = null
    )
}

class FakeLinearClient(private val candidates: List<Issue>) : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        candidates.filter { issue -> activeStates.any { it.equals(issue.state, ignoreCase = true) } }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        candidates.filter { stateNames.contains(it.state) }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        candidates.filter { issueIds.contains(it.id) }.associate { it.id to it.state }

    override suspend fun fetchIssueById(issueId: String): Issue? =
        candidates.firstOrNull { it.id == issueId }

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null

    override suspend fun updateIssueState(issueId: String, stateId: String) {}
}

class FakeLinearClientWithStates(private val stateMap: Map<String, String>) : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        issueIds.filter { it in stateMap }.associateWith { stateMap[it]!! }

    override suspend fun fetchIssueById(issueId: String): Issue? = null

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null

    override suspend fun updateIssueState(issueId: String, stateId: String) {}
}

class FakeLinearClientThrowing : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        throw RuntimeException("API down")

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        throw RuntimeException("API down")

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        throw RuntimeException("API down")

    override suspend fun fetchIssueById(issueId: String): Issue? =
        throw RuntimeException("API down")

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? =
        throw RuntimeException("API down")

    override suspend fun updateIssueState(issueId: String, stateId: String) {
        throw RuntimeException("API down")
    }
}

class FakeAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Success(Unit)
    }
}

class FailingAgentRunner(private val errorMsg: String) : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Failure(IllegalStateException(errorMsg))
    }
}

fun Orchestrator.runDispatchSync() {
    runBlocking {
        scope = CoroutineScope(coroutineContext)
        dispatchService.fetchAndDispatch(scope!!)
    }
}
