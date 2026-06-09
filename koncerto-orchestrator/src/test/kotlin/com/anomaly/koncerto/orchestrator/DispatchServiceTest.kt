package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.AgentProjectConfig
import com.anomaly.koncerto.core.config.AgentProviderConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.config.WorkflowDefinition
import com.anomaly.koncerto.orchestrator.RetryEntry
import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.model.UserRef
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DispatchServiceTest {

    @Test
    fun `dispatch eligible issues and skip ineligible ones`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo"),
                issue("2", "A-2", "Done"),
                issue("3", "A-3", "Todo", priority = 1)
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-3", "A-1")
    }

    @Test
    fun `dispatch respects maxConcurrentAgentsByState`() {
        val state = RuntimeState().also {
            it.running["existing"] = runningEntry("existing", "X-1").let { e ->
                e.copy(issue = e.issue.copy(state = "Todo"))
            }
        }
        val projectConfig = config().copy(agent = config().agent.copy(maxConcurrentAgentsByState = mapOf("todo" to 1)))
        val runner = CollectingAgentRunner()
        val svc = createService(config = projectConfig, state = state, runner = runner)
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch respects per-state cap when under limit`() {
        val state = RuntimeState().also {
            it.running["existing"] = runningEntry("existing", "X-1").let { e ->
                e.copy(issue = e.issue.copy(state = "Todo"))
            }
        }
        val projectConfig = config().copy(agent = config().agent.copy(maxConcurrentAgentsByState = mapOf("todo" to 2)))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = projectConfig, state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"), issue("2", "A-2", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(2)
    }

    @Test
    fun `dispatch skips already claimed issues`() {
        val state = RuntimeState().also { it.claimed.add("1") }
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch skips already running issues`() {
        val state = RuntimeState().also { it.running["1"] = runningEntry("1", "A-1") }
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `scheduleRetry creates retry entry with exponential backoff`() {
        val (svc, state) = createServiceWithState()
        val beforeMs = System.currentTimeMillis()
        svc.scheduleRetry(issue("1", "A-1", "Todo"), "timeout error")
        val entry = state.retryAttempts["1"]
        assertThat(entry).isNotNull()
        assertThat(entry!!.attempt).isEqualTo(1)
        assertThat(entry.error).isEqualTo("timeout error")
        assertThat(entry.identifier).isEqualTo("A-1")
        assertThat(entry.dueAtMs >= beforeMs + 10_000).isTrue()
    }

    @Test
    fun `scheduleRetry increments attempt on subsequent retries`() {
        val (svc, state) = createServiceWithState()
        val issue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(issue, "err1")
        svc.scheduleRetry(issue, "err2")
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(2)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("err2")
    }

    @Test
    fun `scheduleRetry caps backoff at maxRetryBackoffMs`() {
        val projectConfig = config().copy(agent = config().agent.copy(maxRetryBackoffMs = 60_000))
        val (svc, state) = createServiceWithState(config = projectConfig)
        repeat(10) { svc.scheduleRetry(issue("1", "A-1", "Todo"), "err") }
        val entry = state.retryAttempts["1"]
        assertThat(entry).isNotNull()
        assertThat(entry!!.attempt).isEqualTo(10)
        val maxDue = System.currentTimeMillis() + 60_000 + 1000
        assertThat(entry.dueAtMs <= maxDue).isTrue()
    }

    @Test
    fun `dispatch completion removes from claimed and adds to completed`() {
        val state = RuntimeState()
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(state.claimed.contains("1")).isEqualTo(false)
        assertThat(state.completed.contains("1")).isTrue()
    }

    @Test
    fun `matchesRequiredLabels skips issues without required labels`() {
        val projectConfig = config().copy(tracker = config().tracker.copy(requiredLabels = listOf("bugfix")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = projectConfig, runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo", labels = listOf("feature")),
                issue("2", "A-2", "Todo", labels = listOf("bugfix")),
                issue("3", "A-3", "Todo", labels = emptyList())
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-2")
    }

    @Test
    fun `matchesRequiredLabels is case insensitive`() {
        val projectConfig = config().copy(tracker = config().tracker.copy(requiredLabels = listOf("BugFix")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = projectConfig, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo", labels = listOf("bugfix")))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `isBlockedForTodo skips todo issues with non-terminal blockers`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo", blockers = listOf(BlockerRef("b1", "B-1", "In Progress"))),
                issue("2", "A-2", "Todo", blockers = emptyList())
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-2")
    }

    @Test
    fun `isBlockedForTodo allows todo issues with terminal blockers`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo", blockers = listOf(BlockerRef("b1", "B-1", "Done")))
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `isBlockedForTodo treats null blocker state as blocked`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo", blockers = listOf(BlockerRef("b1", "B-1", null)))
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `isBlockedForTodo only applies to todo state`() {
        val projectConfig = config().copy(tracker = config().tracker.copy(activeStates = listOf("Todo", "In Progress")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = projectConfig, runner = runner,
            candidates = listOf(
                issue("1", "A-1", "In Progress", blockers = listOf(BlockerRef("b1", "B-1", "Todo")))
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `dispatch failure triggers scheduleRetry`() {
        val state = RuntimeState()
        val svc = createService(
            state = state,
            candidates = listOf(issue("1", "A-1", "Todo")),
            runner = FailingRunner("agent crashed")
        )
        runDispatch(svc)
        assertThat(state.retryAttempts.containsKey("1")).isTrue()
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(1)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("agent crashed")
    }

    @Test
    fun `fetchCandidateIssues failure does not crash`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            linear = ThrowingLinearClient()
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `available slots exhausted stops dispatch`() {
        val state = RuntimeState().also {
            it.maxConcurrentAgents = 2
            it.running["x"] = runningEntry("x", "X-1")
            it.running["y"] = runningEntry("y", "X-2")
        }
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"), issue("2", "A-2", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatch uses stage-specific prompt when state matches stage`() {
        val runner = CollectingAgentRunner()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "stage-prompt-todo", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val cache = WorkflowCache()
        cache.set(WorkflowDefinition(emptyMap(), "global-prompt"))
        val svc = createService(
            config = config(stages = stages), runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo")),
            cache = cache
        )
        runDispatch(svc)
        assertThat(runner.runArgs.first().prompt).isEqualTo("stage-prompt-todo")
    }

    @Test
    fun `dispatch uses global prompt when state does not match any stage`() {
        val runner = CollectingAgentRunner()
        val stages = mapOf(
            "in review" to StageAgentConfig(
                prompt = "review-prompt", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val cache = WorkflowCache()
        cache.set(WorkflowDefinition(emptyMap(), "global-prompt"))
        val svc = createService(
            config = config(stages = stages), runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo")),
            cache = cache
        )
        runDispatch(svc)
        assertThat(runner.runArgs.first().prompt).isEqualTo("global-prompt")
    }

    @Test
    fun `dispatch passes stage agentKind and command to runner`() {
        val runner = CollectingAgentRunner()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = null, model = null, maxConcurrent = null,
                agentKind = "opencode", command = "opencode-custom", onCompleteState = null
            )
        )
        val svc = createService(
            config = config(stages = stages), runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        val args = runner.runArgs.first()
        assertThat(args.agentKindOverride).isEqualTo("opencode")
        assertThat(args.commandOverride).isEqualTo("opencode-custom")
    }

    @Test
    fun `onCompleteState resolves and updates issue state after completion`() {
        val state = RuntimeState()
        val trackingLinear = TrackingLinearClient()
        trackingLinear.addIssue(issue("1", "A-1", "Todo"))
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = null, model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "Done"
            )
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = config(stages = stages),
            state = state,
            linear = trackingLinear,
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(trackingLinear.transitionedIssueId).isEqualTo("1")
        assertThat(trackingLinear.transitionedStateId).isEqualTo("done-id")
    }

    @Test
    fun `onCompleteState without stage does not transition`() {
        val state = RuntimeState()
        val trackingLinear = TrackingLinearClient()
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state,
            linear = trackingLinear,
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(trackingLinear.transitionedIssueId).isEqualTo(null as String?)
    }

    @Test
    fun `dispatchDueRetries re-dispatches due retry entries`() {
        val state = RuntimeState()
        val pastDue = System.currentTimeMillis() - 10_000
        state.retryAttempts["1"] = RetryEntry("1", "A-1", 2, pastDue, "timeout")
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state,
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runBlocking {
            svc.dispatchDueRetries(CoroutineScope(coroutineContext))
        }
        assertThat(runner.dispatched.size).isEqualTo(1)
        assertThat(runner.dispatched[0].id).isEqualTo("1")
        assertThat(state.retryAttempts.containsKey("1")).isFalse()
    }

    @Test
    fun `dispatchDueRetries skips not-yet-due entries`() {
        val state = RuntimeState()
        val futureDue = System.currentTimeMillis() + 60_000
        state.retryAttempts["1"] = RetryEntry("1", "A-1", 1, futureDue, "timeout")
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state,
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runBlocking {
            svc.dispatchDueRetries(CoroutineScope(coroutineContext))
        }
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `clarification requested blocks issue assigns and transitions state`() {
        val root = Files.createTempDirectory("clarify-test-")
        try {
            val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
            val trackingLinear = TrackingLinearClient()
            val creator = UserRef("user-1", "Alice", false)
            val testIssue = issue("1", "A-1", "Todo").copy(creator = creator)
            trackingLinear.addIssue(testIssue)

            val workspace = workspaces.ensureWorkspace(testIssue.identifier)
            Files.createDirectories(workspace.path.resolve(".koncerto"))
            Files.writeString(workspace.path.resolve(".koncerto").resolve("clarification.md"), "Need specs")

            val state = RuntimeState()
            val runner = CollectingAgentRunner()
            val projectConfig = config().copy(tracker = config().tracker.copy(blockedState = "Blocked"))
            val svc = createService(
                config = projectConfig,
                state = state,
                linear = trackingLinear,
                workspaces = workspaces,
                candidates = listOf(testIssue),
                runner = runner
            )
            runDispatchAwait(svc)

            assertThat(runner.dispatched.size).isEqualTo(1)
            assertThat(state.blocked.contains("1")).isTrue()
            assertThat(state.completed.contains("1")).isFalse()
            assertThat(trackingLinear.commentedIssueId).isEqualTo("1")
            assertThat(trackingLinear.commentedBody).isEqualTo("Need specs")
            assertThat(trackingLinear.transitionedIssueId).isEqualTo("1")
            assertThat(trackingLinear.assignedUserId).isEqualTo("user-1")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `issue re-dispatched when unblocked and back in active state`() {
        val root = Files.createTempDirectory("clarify-redispatch-")
        try {
            val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
            val trackingLinear = TrackingLinearClient()
            val creator = UserRef("user-1", "Alice", false)
            val testIssue = issue("1", "A-1", "Todo").copy(creator = creator)
            trackingLinear.addIssue(testIssue)

            val workspace = workspaces.ensureWorkspace(testIssue.identifier)
            Files.createDirectories(workspace.path.resolve(".koncerto"))
            Files.writeString(workspace.path.resolve(".koncerto").resolve("clarification.md"), "Need specs")

            val state = RuntimeState()
            val runner = CollectingAgentRunner()
            val stages = mapOf("todo" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "In Review"
            ))
            val projectConfig = config(stages = stages).copy(tracker = config().tracker.copy(blockedState = "Blocked"))
            val svc = createService(
                config = projectConfig,
                state = state,
                linear = trackingLinear,
                workspaces = workspaces,
                candidates = listOf(testIssue),
                runner = runner
            )

            runDispatchAwait(svc)
            assertThat(state.blocked.contains("1")).isTrue()

            Files.delete(workspace.path.resolve(".koncerto").resolve("clarification.md"))
            state.blocked.remove("1")

            runDispatchAwait(svc)

            assertThat(runner.dispatched.size).isEqualTo(2)
            assertThat(state.completed.contains("1")).isTrue()
            assertThat(state.blocked.contains("1")).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun runDispatch(svc: DispatchService) {
        runBlocking {
            svc.fetchAndDispatch(CoroutineScope(coroutineContext))
        }
    }

    private fun <T> runBlockingAndJoin(block: suspend CoroutineScope.() -> T): T =
        runBlocking {
            coroutineScope {
                block()
            }
        }

    private fun runDispatchAwait(svc: DispatchService) {
        runBlockingAndJoin {
            svc.fetchAndDispatch(this)
        }
    }

    private fun createService(
        config: ProjectConfig = config(),
        state: RuntimeState = RuntimeState(),
        linear: LinearClient? = null,
        candidates: List<Issue>? = null,
        runner: AgentRunner = CollectingAgentRunner(),
        cache: WorkflowCache? = null,
        workspaces: WorkspaceManager? = null
    ): DispatchService {
        val wc = cache ?: WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val logger = StructuredLogger(emptyList())
        val client = linear ?: candidates?.let { SimpleLinear(it) } ?: SimpleLinear(emptyList())
        return DispatchService(config, state, client, runner, wc, logger, "proj", workspaces)
    }

    private fun createServiceWithState(
        config: ProjectConfig = config()
    ): Pair<DispatchService, RuntimeState> {
        val state = RuntimeState()
        val svc = createService(config = config, state = state)
        return Pair(svc, state)
    }

    @Test
    fun `resolveAgent returns provider kind and model from named agent on stage`() {
        val (svc, _) = createServiceWithState(config(
            agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "fast"
            ))
        ))
        val issue = issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-sonnet-4")
    }

    @Test
    fun `resolveAgent falls back to agentKind when no agents map`() {
        val (svc, _) = createServiceWithState(config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = "codex", command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent uses project default when no stage config`() {
        val (svc, _) = createServiceWithState(config())
        val issue = issue("1", "T-1", "In Progress")
        val resolved = svc.resolveAgent(issue, null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent label agent colon fast overrides provider`() {
        val (svc, _) = createServiceWithState(config(
            agents = mapOf(
                "fast" to AgentProviderConfig("codex", model = "claude-sonnet-4"),
                "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
            ),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "slow"
            ))
        ))
        val issue = issue("1", "T-1", "In Progress", labels = listOf("agent:fast"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-sonnet-4")
    }

    @Test
    fun `resolveAgent label model colon gpt4o overrides stage model`() {
        val (svc, _) = createServiceWithState(config(
            agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "fast"
            ))
        ))
        val issue = issue("1", "T-1", "In Progress", labels = listOf("model:gpt-4o"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("gpt-4o")
    }

    @Test
    fun `resolveAgent combines agent and model labels`() {
        val (svc, _) = createServiceWithState(config(
            agents = mapOf(
                "fast" to AgentProviderConfig("codex"),
                "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
            ),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "slow"
            ))
        ))
        val issue = issue("1", "T-1", "In Progress", labels = listOf("agent:fast", "model:gpt-4o"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("gpt-4o")
    }

    @Test
    fun `resolveAgent handles non-existent provider label gracefully`() {
        val (svc, _) = createServiceWithState(config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = issue("1", "T-1", "In Progress", labels = listOf("agent:nonexistent"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent backward compat with agentKind and model labels`() {
        val (svc, _) = createServiceWithState(config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = "codex", command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = issue("1", "T-1", "In Progress", labels = listOf("model:claude-3"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-3")
    }

    @Test
    fun `resolveAgent uses project default kind when no stage config uses no agent`() {
        val (svc, _) = createServiceWithState(config(
            stages = mapOf("unknown" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["unknown"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent warns when stage references non-existent provider`() {
        val (svc, _) = createServiceWithState(config(
            agents = emptyMap(),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "nonexistent"
            ))
        ))
        val issue = issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    companion object {
        fun issue(
            id: String, identifier: String, state: String,
            priority: Int = 5, labels: List<String> = emptyList(),
            blockers: List<BlockerRef> = emptyList()
        ) = Issue(
            id = id, identifier = identifier, title = "t", description = null,
            priority = priority, state = state, branchName = null, url = null,
            labels = labels, blockedBy = blockers,
            createdAt = null, updatedAt = null
        )

        fun config(
            stages: Map<String, StageAgentConfig> = emptyMap(),
            agents: Map<String, AgentProviderConfig> = emptyMap()
        ) = ProjectConfig(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
                requiredLabels = emptyList(),
                activeStates = listOf("Todo"), terminalStates = listOf("Done"),
                blockedState = "Blocked", projectAdmin = null
            ),
            workspace = WorkspaceConfig(root = "/tmp"),
            agent = AgentProjectConfig(
                kind = "codex", command = "codex app-server",
                maxConcurrentAgents = 10, maxTurns = 1, maxRetryBackoffMs = 300000,
                maxConcurrentAgentsByState = emptyMap(),
                turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                stages = stages,
                agents = agents
            )
        )

        fun runningEntry(id: String, identifier: String) = RunningEntry(
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
}

private class SimpleLinear(private val candidates: List<Issue>) : LinearClient {
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
    override suspend fun fetchIssueCreator(issueId: String): com.anomaly.koncerto.core.model.UserRef? = null
}

private class TrackingLinearClient : LinearClient {
    var transitionedIssueId: String? = null
    var transitionedStateId: String? = null
    var commentedIssueId: String? = null
    var commentedBody: String? = null
    var assignedIssueId: String? = null
    var assignedUserId: String? = null
    private val candidates = mutableListOf<Issue>()

    fun addIssue(issue: Issue) { candidates.add(issue) }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        candidates.filter { activeStates.any { s -> it.state.equals(s, ignoreCase = true) } }
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> = emptyMap()
    override suspend fun fetchIssueById(issueId: String): Issue? = candidates.firstOrNull { it.id == issueId }
    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = "done-id"
    override suspend fun updateIssueState(issueId: String, stateId: String) {
        transitionedIssueId = issueId
        transitionedStateId = stateId
    }
    override suspend fun createComment(issueId: String, body: String) {
        commentedIssueId = issueId
        commentedBody = body
    }
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {
        assignedIssueId = issueId
        assignedUserId = assigneeId
    }
    override suspend fun fetchIssueCreator(issueId: String): com.anomaly.koncerto.core.model.UserRef? =
        candidates.firstOrNull { it.id == issueId }?.creator
}

private class ThrowingLinearClient : LinearClient {
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
    override suspend fun fetchIssueCreator(issueId: String): com.anomaly.koncerto.core.model.UserRef? =
        throw RuntimeException("API down")
}

private class CollectingAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    val runArgs = mutableListOf<RunArgs>()
    private val flow = MutableSharedFlow<com.anomaly.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        runArgs += RunArgs(issue, prompt, agentKindOverride, commandOverride)
        return Result.Success(Unit)
    }
}

private data class RunArgs(
    val issue: Issue,
    val prompt: String,
    val agentKindOverride: String?,
    val commandOverride: String?
)

private class FailingRunner(private val errorMsg: String) : AgentRunner {
    private val flow = MutableSharedFlow<com.anomaly.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?
    ): EmptyResult<IllegalStateException> {
        return Result.Failure(IllegalStateException(errorMsg))
    }
}
