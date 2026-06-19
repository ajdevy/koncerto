package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.core.errors.AgentError
import com.flexsentlabs.koncerto.core.errors.AgentErrorType
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class OrchestratorTest {

    private val defaultProjectSlug = "proj"

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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-3", "A-1")
    }

    @Test
    fun `dispatch respects maxConcurrentAgentsByState`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(agent = sampleProjectConfig().agent.copy(maxConcurrentAgentsByState = mapOf("todo" to 1)))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch respects per-state cap when under limit`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(agent = sampleProjectConfig().agent.copy(maxConcurrentAgentsByState = mapOf("todo" to 2)))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `dispatch skips already claimed issues`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.claimed["1"] = true
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(listOf(sampleIssue("1", "A-1", "Todo")))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        state.claimed["1"] = true
        val linear = FakeLinearClientWithStates(mapOf("1" to "Done"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
        assertThat(state.running.containsKey("1")).isEqualTo(false)
        assertThat(state.isClaimed("1")).isEqualTo(false)
    }

    @Test
    fun `reconcile removes non-active state from running`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1")
        state.claimed["1"] = true
        val linear = FakeLinearClientWithStates(mapOf("1" to "InvalidState"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
        assertThat(state.running.containsKey("1")).isEqualTo(false)
        assertThat(state.isClaimed("1")).isEqualTo(false)
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
        assertThat(state.running.size).isEqualTo(0)
    }

    @Test
    fun `reconcile unblocks issue when blocker reaches terminal state`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val blockerId = "blocker-1"
        val issueId = "issue-1"

        state.running[blockerId] = runningEntry(blockerId, "ENG-1")
        state.claimed[blockerId] = true
        state.running[issueId] = runningEntry(issueId, "ENG-2").copy(
            issue = runningEntry(issueId, "ENG-2").issue.copy(
                blockedBy = listOf(BlockerRef(id = blockerId, identifier = "ENG-1", state = "In Progress"))
            )
        )
        state.claimed[issueId] = true

        val linear = FakeLinearClientWithStates(mapOf(blockerId to "Done"))
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
        assertThat(state.running.containsKey(blockerId)).isEqualTo(false)
        assertThat(state.isClaimed(blockerId)).isEqualTo(false)
        assertThat(state.running.containsKey(issueId)).isEqualTo(false)
        assertThat(state.isClaimed(issueId)).isEqualTo(false)
        assertThat(state.isBlocked(issueId)).isEqualTo(false)
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.reconcile(defaultProjectSlug, orch.projects.values.first())
        assertThat(state.running.containsKey("1")).isTrue()
    }

    @Test
    fun `handleAgentEvent accumulates token usage from TurnCompleted`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "A-1").copy(threadId = "t1")
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        state.running["1"] = runningEntry("1", "A-1").copy(threadId = "t1")
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.handleAgentEvent(AgentEvent.TurnFailed(
            threadId = "t1", turnId = "r1", error = "boom", pid = 1234L
        ))
        assertThat(state.tokenTotals.inputTokens).isEqualTo(0)
    }

    @Test
    fun `handleAgentEvent LimitDetected dispatches notification when notifier and config present`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-lim-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(
            notifications = sampleProjectConfig().notifications.copy(
                onLimit = listOf("logging")
            )
        )
        val config = sampleConfig(pc)
        val state = RuntimeState()
        state.tryClaim("1")
        state.running["1"] = RunningEntry(
            issue = Issue(id = "1", identifier = "ABC-1", title = "Test", description = null,
                priority = 1, state = "Todo", branchName = null, url = null,
                labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null),
            threadId = "t1", turnId = "r1", startedAt = java.time.Instant.now(), lastCodexTimestamp = null
        )
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val captured = mutableListOf<NotificationEvent>()
        val notifier = CompositeNotifier(listOf(object : Notifier {
            override suspend fun send(event: NotificationEvent) {
                captured += event
            }
        }))
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state),
            notifier = notifier
        )
        orch.handleAgentEvent(AgentEvent.LimitDetected(
            agentError = AgentError(AgentErrorType.RateLimitError(details = "429", retryAfterMs = 5000), "Too many requests", "exception"),
            issueId = "1",
            line = "HTTP 429"
        ))
        assertThat(captured.size).isEqualTo(1)
        val ev = captured[0]
        assertThat(ev is NotificationEvent.LimitDetected).isTrue()
        ev as NotificationEvent.LimitDetected
        assertThat(ev.errorType).isEqualTo("RateLimitError")
    }

    @Test
    fun `handleAgentEvent LimitDetected skips notification when onLimit empty`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-lim2-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(
            notifications = sampleProjectConfig().notifications.copy(
                onLimit = emptyList()
            )
        )
        val config = sampleConfig(pc)
        val state = RuntimeState()
        state.tryClaim("1")
        state.running["1"] = RunningEntry(
            issue = Issue(id = "1", identifier = "ABC-1", title = "Test", description = null,
                priority = 1, state = "Todo", branchName = null, url = null,
                labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null),
            threadId = "t1", turnId = "r1", startedAt = java.time.Instant.now(), lastCodexTimestamp = null
        )
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val captured = mutableListOf<NotificationEvent>()
        val notifier = CompositeNotifier(listOf(object : Notifier {
            override suspend fun send(event: NotificationEvent) {
                captured += event
            }
        }))
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state),
            notifier = notifier
        )
        orch.handleAgentEvent(AgentEvent.LimitDetected(
            agentError = AgentError(AgentErrorType.RateLimitError(), "Too many requests", "exception"),
            issueId = "1",
            line = "HTTP 429"
        ))
        assertThat(captured).isEmpty()
    }

    @Test
    fun `handleAgentEvent LimitDetected respects cooldown`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-lim3-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(
            notifications = sampleProjectConfig().notifications.copy(
                onLimit = listOf("logging"), limitCooldownMs = 60000
            )
        )
        val config = sampleConfig(pc)
        val state = RuntimeState()
        state.tryClaim("1")
        state.running["1"] = RunningEntry(
            issue = Issue(id = "1", identifier = "ABC-1", title = "Test", description = null,
                priority = 1, state = "Todo", branchName = null, url = null,
                labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null),
            threadId = "t1", turnId = "r1", startedAt = java.time.Instant.now(), lastCodexTimestamp = null
        )
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val captured = mutableListOf<NotificationEvent>()
        val notifier = CompositeNotifier(listOf(object : Notifier {
            override suspend fun send(event: NotificationEvent) {
                captured += event
            }
        }))
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state),
            notifier = notifier
        )
        orch.handleAgentEvent(AgentEvent.LimitDetected(
            agentError = AgentError(AgentErrorType.RateLimitError(), "Too many requests", "exception"),
            issueId = "1",
            line = "HTTP 429"
        ))
        orch.handleAgentEvent(AgentEvent.LimitDetected(
            agentError = AgentError(AgentErrorType.RateLimitError(), "Too many requests again", "exception"),
            issueId = "1",
            line = "HTTP 429"
        ))
        assertThat(captured.size).isEqualTo(1)
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(state.isClaimed("1")).isEqualTo(false)
        assertThat(state.completed.containsKey("1")).isTrue()
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        val issue = sampleIssue("1", "A-1", "Todo")
        val beforeMs = System.currentTimeMillis()
        orch.projects[defaultProjectSlug]!!.dispatch.scheduleRetry(issue, "timeout error")
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        val issue = sampleIssue("1", "A-1", "Todo")
        orch.projects[defaultProjectSlug]!!.dispatch.scheduleRetry(issue, "err1")
        orch.projects[defaultProjectSlug]!!.dispatch.scheduleRetry(issue, "err2")
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(2)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("err2")
    }

    @Test
    fun `scheduleRetry caps backoff at maxRetryBackoffMs`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(agent = sampleProjectConfig().agent.copy(maxRetryBackoffMs = 60_000, maxRetries = 10))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
        val state = RuntimeState()
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val cache = WorkflowCache()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        val issue = sampleIssue("1", "A-1", "Todo")
        repeat(10) { orch.projects[defaultProjectSlug]!!.dispatch.scheduleRetry(issue, "err") }
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
        val pc = sampleProjectConfig().copy(tracker = sampleProjectConfig().tracker.copy(requiredLabels = listOf("bugfix")))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-2")
    }

    @Test
    fun `matchesRequiredLabels is case insensitive`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(tracker = sampleProjectConfig().tracker.copy(requiredLabels = listOf("BugFix")))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val linear = FakeLinearClient(
            listOf(sampleIssue("1", "A-1", "Todo").copy(labels = listOf("bugfix")))
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
                sampleIssue("b1", "B-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "Todo"))
                ),
                sampleIssue("1", "A-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "In Progress"))
                ),
                sampleIssue("2", "A-2", "Todo").copy(blockedBy = emptyList())
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
                sampleIssue("b1", "B-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = "Todo"))
                ),
                sampleIssue("1", "A-1", "Todo").copy(
                    blockedBy = listOf(BlockerRef(id = "b1", identifier = "B-1", state = null))
                )
            )
        )
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `isBlockedForTodo only applies to todo state`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc = sampleProjectConfig().copy(tracker = sampleProjectConfig().tracker.copy(activeStates = listOf("Todo", "In Progress")))
        val config = ServiceConfig(projects = mapOf(defaultProjectSlug to pc))
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
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
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf(defaultProjectSlug to state)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `two projects each get their own runtime`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc1 = sampleProjectConfig().copy(
            tracker = sampleProjectConfig().tracker.copy(projectSlug = "p1")
        )
        val pc2 = sampleProjectConfig().copy(
            tracker = sampleProjectConfig().tracker.copy(projectSlug = "p2")
        )
        val config = ServiceConfig(projects = mapOf("alpha" to pc1, "beta" to pc2))
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = WorkflowCache(),
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        assertThat(orch.projects.size).isEqualTo(2)
        assertThat(orch.projects["alpha"]?.config?.tracker?.projectSlug).isEqualTo("p1")
        assertThat(orch.projects["beta"]?.config?.tracker?.projectSlug).isEqualTo("p2")
        assertThat(orch.projects["alpha"]?.state === orch.projects["beta"]?.state).isEqualTo(false)
    }

    @Test
    fun `multi-project tick handles failure in one project gracefully`() = runBlocking {
        val root1 = Files.createTempDirectory("orch-good-")
        val root2 = Files.createTempDirectory("orch-bad-")
        val mgr1 = WorkspaceManager(root1, HookExecutor { _, _ -> })
        val mgr2 = WorkspaceManager(root2, HookExecutor { _, _ -> })
        val goodPc = sampleProjectConfig().copy(
            agent = sampleProjectConfig().agent.copy(command = null) // will trigger preflight warn but not crash
        )
        val badPc = sampleProjectConfig().copy(
            agent = sampleProjectConfig().agent.copy(maxTurns = 0) // will throw on parse
        )
        val config = ServiceConfig(
            pollIntervalMs = 30000,
            projects = mapOf("good" to goodPc, "bad" to badPc)
        )
        val linear = FakeLinearClient(emptyList())
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val s1 = RuntimeState()
        val s2 = RuntimeState()
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { if (it == goodPc) mgr1 else mgr2 },
            agentRunner = runner,
            workflowCache = WorkflowCache(),
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = mapOf("good" to s1, "bad" to s2)
        )
        assertThat(orch.projects.size).isEqualTo(2)
    }

    @Test
    fun `multi-project issueProjectMap tracks across projects`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val pc1 = sampleProjectConfig().copy(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p1"
            )
        )
        val pc2 = sampleProjectConfig().copy(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p2"
            )
        )
        val config = ServiceConfig(projects = mapOf("p1" to pc1, "p2" to pc2))
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi {{ issue.identifier }}")) }
        val linear = FakeLinearClient(listOf(
            sampleIssue("1", "A-1", "Todo"),
            sampleIssue("2", "B-1", "Todo")
        ))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(
            config = config,
            linearClientFactory = { linear },
            workspaceManagerFactory = { mgr },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        orch.runDispatchSync()
        assertThat(runner.dispatched.size == 2 || runner.dispatched.size == 4).isTrue()
        assertThat(runner.dispatched.map { it.id }.toSet()).isEqualTo(setOf("1", "2"))
    }

    private fun sampleIssue(id: String, identifier: String, state: String) = Issue(
        id = id, identifier = identifier, title = "t", description = null,
        priority = 5, state = state, branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private fun sampleProjectConfig() = ProjectConfig(
        tracker = TrackerConfig(
            kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
            requiredLabels = emptyList(),
            activeStates = listOf("Todo"), terminalStates = listOf("Done")
        ),
        workspace = WorkspaceConfig(root = "/tmp"),
        agent = AgentProjectConfig(
            kind = "codex", command = "codex app-server",
            maxConcurrentAgents = 10, maxTurns = 1, maxRetryBackoffMs = 300000,
            maxConcurrentAgentsByState = emptyMap(),
            turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
            stages = emptyMap()
        )
    )

    private fun sampleConfig(projectConfig: ProjectConfig = sampleProjectConfig()) = ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf(defaultProjectSlug to projectConfig),
        hooks = com.flexsentlabs.koncerto.core.config.HooksConfig(null, null, null, null, 60000),
        gitConfig = com.flexsentlabs.koncerto.core.config.GitConfig()
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
    override suspend fun createComment(issueId: String, body: String) {}
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? = null
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
}

class FakeLinearClientWithStates(private val stateMap: Map<String, String>) : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        issueIds.filter { it in stateMap }.associateWith { stateMap[it]!! }

    override suspend fun fetchIssueById(issueId: String): Issue? = null

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null

    override suspend fun updateIssueState(issueId: String, stateId: String) {}
    override suspend fun createComment(issueId: String, body: String) {}
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? = null
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
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
    override suspend fun createComment(issueId: String, body: String) { throw RuntimeException("API down") }
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) { throw RuntimeException("API down") }
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? =
        throw RuntimeException("API down")
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = throw RuntimeException("API down")
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean =
        throw RuntimeException("API down")
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
        commandOverride: String?,
        modelOverride: String?,
        effortOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
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
        commandOverride: String?,
        modelOverride: String?,
        effortOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Failure(IllegalStateException(errorMsg))
    }
}

fun Orchestrator.runDispatchSync() {
    runBlocking {
        projects.values.forEach { it.dispatch.fetchAndDispatch(this) }
    }
}
