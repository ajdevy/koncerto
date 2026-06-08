package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.WorkflowDefinition
import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workflow.WorkflowCache
import kotlinx.coroutines.CoroutineScope
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
        val config = config().copy(maxConcurrentAgentsByState = mapOf("todo" to 1))
        val runner = CollectingAgentRunner()
        val svc = createService(config = config, state = state, runner = runner)
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
        val config = config().copy(maxConcurrentAgentsByState = mapOf("todo" to 2))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = config, state = state, runner = runner,
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
        val config = config().copy(maxRetryBackoffMs = 60_000)
        val (svc, state) = createServiceWithState(config = config)
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
        val config = config().copy(requiredLabels = listOf("bugfix"))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = config, runner = runner,
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
        val config = config().copy(requiredLabels = listOf("BugFix"))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = config, runner = runner,
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
        val config = config().copy(activeStates = listOf("Todo", "In Progress"))
        val runner = CollectingAgentRunner()
        val svc = createService(
            config = config, runner = runner,
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

    private fun runDispatch(svc: DispatchService) {
        runBlocking {
            svc.fetchAndDispatch(CoroutineScope(coroutineContext))
        }
    }

    private fun createService(
        config: ServiceConfig = config(),
        state: RuntimeState = RuntimeState(),
        linear: LinearClient? = null,
        candidates: List<Issue>? = null,
        runner: AgentRunner = CollectingAgentRunner()
    ): DispatchService {
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val logger = StructuredLogger(emptyList())
        val client = linear ?: candidates?.let { SimpleLinear(it) } ?: SimpleLinear(emptyList())
        return DispatchService(config, state, client, runner, cache, logger, "proj")
    }

    private fun createServiceWithState(
        config: ServiceConfig = config()
    ): Pair<DispatchService, RuntimeState> {
        val state = RuntimeState()
        val svc = createService(config = config, state = state)
        return Pair(svc, state)
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

        fun config() = ServiceConfig(
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
            turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000
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
}

private class ThrowingLinearClient : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        throw RuntimeException("API down")
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        throw RuntimeException("API down")
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        throw RuntimeException("API down")
}

private class CollectingAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<com.anomaly.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(issue: Issue, attempt: Int?, prompt: String): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Success(Unit)
    }
}

private class FailingRunner(private val errorMsg: String) : AgentRunner {
    private val flow = MutableSharedFlow<com.anomaly.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(issue: Issue, attempt: Int?, prompt: String): EmptyResult<IllegalStateException> {
        return Result.Failure(IllegalStateException(errorMsg))
    }
}
