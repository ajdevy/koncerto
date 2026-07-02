package com.flexsentlabs.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.hasSize
import com.flexsentlabs.koncerto.agent.AgentAuthChecker
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.RateLimitConfig
import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.ratelimit.RateLimitRegistry
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.metrics.IssueMetrics
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.TokenDaySummary
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.orchestrator.RetryEntry
import com.flexsentlabs.koncerto.orchestrator.RunningEntry
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.orchestrator.TokenTotals
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Paths
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private fun createOrchestrator(
    config: ServiceConfig,
    state: RuntimeState,
    slug: String = "default",
    candidateIssues: List<Issue> = emptyList()
): Orchestrator {
    val fakeClient = object : LinearClient {
        override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = candidateIssues
        override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
        override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
        override suspend fun fetchIssueById(issueId: String) = null
        override suspend fun resolveStateId(projectSlug: String, stateName: String) = null
        override suspend fun updateIssueState(issueId: String, stateId: String) {}
        override suspend fun createComment(issueId: String, body: String) {}
        override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
        override suspend fun fetchIssueCreator(issueId: String) = null
        override suspend fun createIssue(
            projectSlug: String, title: String, state: String,
            description: String?, labels: List<String>
        ): Issue? = null
        override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
    }
    return Orchestrator(
        config = config,
        linearClientFactory = { fakeClient },
        workspaceManagerFactory = { WorkspaceManager(Paths.get("/tmp"), HookExecutor { _, _ -> }) },
        agentRunner = object : AgentRunner {
            override fun events(): Flow<AgentEvent> = MutableSharedFlow<AgentEvent>().asSharedFlow()
            override suspend fun run(
                issue: Issue, attempt: Int?, prompt: String,
                agentKindOverride: String?, commandOverride: String?,
                modelOverride: String?,
                effortOverride: String?,
                turnTimeoutMs: Long?, stallTimeoutMs: Long?,
            gitWorkflowOverride: GitWorkflow?
            ): EmptyResult<IllegalStateException> = Result.Success(Unit)
        },
        workflowCache = WorkflowCache(),
        logger = StructuredLogger(emptyList()),
        scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        runtimeStates = mapOf(slug to state),
        metricsRepository = null
    )
}

class ApiV1ControllerTest {

    @AfterEach
    fun resetLoginState() {
        AgentAuthChecker.markUnauthenticated("claude")
        AgentAuthChecker.markUnauthenticated("codex")
        com.flexsentlabs.koncerto.agent.ClaudeAuthSupport.clearToken()
        val controllerClass = ApiV1Controller::class.java
        listOf("codexProcess", "claudeProcess").forEach { name ->
            val field = controllerClass.getDeclaredField(name)
            field.isAccessible = true
            (field.get(null) as? Process)?.destroyForcibly()
            field.set(null, null)
        }
        listOf("codexUrl", "codexCode", "claudeUrl", "claudeCode", "claudeLoginOutput").forEach { name ->
            val field = controllerClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(null, null)
        }
        AgentAuthChecker.markAuthenticated("codex")
    }

    private fun minimalConfig() = ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf("default" to ProjectConfig(
            tracker = TrackerConfig(
                kind = "", endpoint = "x", apiKey = "", projectSlug = "",
                requiredLabels = emptyList(), activeStates = emptyList(), terminalStates = emptyList()
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
    fun `state returns snapshot with running and retrying`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5

        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastHeartbeatAt = null,
            inputTokens = 100,
            outputTokens = 50,
            totalTokens = 150,
            turnCount = 2
        )
        state.retryAttempts["2"] = RetryEntry("2", "ABC-2", 1, System.currentTimeMillis() + 60000, "timeout")

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()

        assertThat(snapshot!!.running.size).isEqualTo(1)
        assertThat(snapshot.running[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(snapshot.running[0].threadId).isEqualTo("t-1")
        assertThat(snapshot.running[0].turnCount).isEqualTo(2)
        assertThat(snapshot.running[0].inputTokens).isEqualTo(100)
        assertThat(snapshot.retrying.size).isEqualTo(1)
        assertThat(snapshot.retrying[0].identifier).isEqualTo("ABC-2")
        assertThat(snapshot.retrying[0].attempt).isEqualTo(1)
    }

    @Test
    fun `byIdentifier returns issue details when found`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastHeartbeatAt = null,
            turnCount = 3
        )

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = controller.byIdentifier("ABC-1").block()

        assertThat(result!!.issueId).isEqualTo("1")
        assertThat(result.issueIdentifier).isEqualTo("ABC-1")
        assertThat(result.threadId).isEqualTo("t-1")
        assertThat(result.turnCount).isEqualTo(3)
    }

    @Test
    fun `byIdentifier returns not_found when missing`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = controller.byIdentifier("MISSING").block()

        assertThat(result!!.error).isEqualTo("not_found")
    }

    @Test
    fun `refresh returns ok`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = controller.refresh().block()

        assertThat(result!!.status).isEqualTo("ok")
    }

    @Test
    fun `models returns configured stages`() {
        val state = RuntimeState()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "impl.md", model = "claude-sonnet", effort = null, maxConcurrent = null,
                agentKind = "opencode", command = null, onCompleteState = null
            )
        )
        val pc = minimalConfig().projects["default"]!!
        val config = minimalConfig().copy(
            projects = mapOf("default" to pc.copy(
                agent = pc.agent.copy(kind = "opencode", stages = stages)
            ))
        )
        val controller = ApiV1Controller(config, createOrchestrator(config, state))
        val result = controller.models("").block()

        assertThat(result!!.agentKind).isEqualTo("opencode")
        assertThat(result.totalStages).isEqualTo(1)
        assertThat(result.configuredStages[0].stage).isEqualTo("todo")
        assertThat(result.configuredStages[0].model).isEqualTo("claude-sonnet")
        assertThat(result.configuredStages[0].agentKind).isEqualTo("opencode")
    }

    @Test
    fun `models returns empty list when no stages configured`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = controller.models("").block()

        assertThat(result!!.totalStages).isEqualTo(0)
        assertThat(result.configuredStages).isEqualTo(emptyList())
    }

    @Test
    fun `history returns all metrics`() {
        val repo = FakeMetricsRepository()
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state), repo)
        val result = runBlocking { controller.history(null, 50) }
        assertThat(result.size).isEqualTo(2)
        assertThat(result[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(result[1].issueIdentifier).isEqualTo("DEF-2")
    }

    @Test
    fun `history filters by project`() {
        val repo = FakeMetricsRepository()
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state), repo)
        val result = runBlocking { controller.history("project-x", 50) }
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0].projectSlug).isEqualTo("project-x")
    }

    @Test
    fun `history returns empty when no metrics`() {
        val repo = FakeMetricsRepository(emptyList())
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state), repo)
        val result = runBlocking { controller.history(null, 50) }
        assertThat(result).isEmpty()
    }

    @Test
    fun `stages returns per-project stage config`() {
        val state = RuntimeState()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "impl.md", model = "claude-sonnet", effort = null, maxConcurrent = 3,
                agentKind = "opencode", command = null, onCompleteState = "In Progress"
            )
        )
        val pc = minimalConfig().projects["default"]!!
        val config = minimalConfig().copy(
            projects = mapOf("default" to pc.copy(
                agent = pc.agent.copy(kind = "opencode", maxConcurrentAgents = 5, stages = stages)
            ))
        )
        val controller = ApiV1Controller(config, createOrchestrator(config, state))
        val result = controller.stages()
        assertThat(result.size).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val defaultEntry = result["default"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val agent = defaultEntry["agent"] as Map<String, Any>
        assertThat(agent["kind"]).isEqualTo("opencode")
        assertThat(agent["maxConcurrent"]).isEqualTo(5)
        @Suppress("UNCHECKED_CAST")
        val stagesMap = defaultEntry["stages"] as Map<String, Map<String, Any?>>
        assertThat(stagesMap["todo"]?.get("prompt")).isEqualTo("impl.md")
        assertThat(stagesMap["todo"]?.get("onCompleteState")).isEqualTo("In Progress")
    }

    @Test
    fun `pauseAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = controller.pauseAgent("ABC-1")
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running["1"]?.paused).isEqualTo(true)
    }

    @Test
    fun `resumeAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null, paused = true
        )
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = controller.resumeAgent("ABC-1")
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running["1"]?.paused).isEqualTo(false)
    }

    @Test
    fun `cancelAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        state.claimed["1"] = true
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = runBlocking { controller.cancelAgent("ABC-1") }
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running.containsKey("1")).isFalse()
        assertThat(state.claimed.contains("1")).isFalse()
    }

    @Test
    fun `pauseAgent returns 404 for unknown identifier`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = controller.pauseAgent("UNKNOWN")
        assertThat(response.statusCodeValue).isEqualTo(404)
    }

    @Test
    fun `resumeAgent returns 404 for unknown identifier`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = controller.resumeAgent("UNKNOWN")
        assertThat(response.statusCodeValue).isEqualTo(404)
    }

    @Test
    fun `cancelAgent returns 404 for unknown identifier`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val response = runBlocking { controller.cancelAgent("UNKNOWN") }
        assertThat(response.statusCodeValue).isEqualTo(404)
    }

    @Test
    fun `streamOutput returns flux with output lines`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        runBlocking { state.appendOutput("1", "hello world") }
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val flux = controller.streamOutput("ABC-1", "")
        val result = flux.next().block()
        assertThat(result).isNotNull()
        assertThat(result!!.data()).isEqualTo("hello world")
        assertThat(result.event()).isEqualTo("output")
    }

    @Test
    fun `streamOutput returns empty when identifier not found`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val flux = controller.streamOutput("MISSING", "")
        assertThat(flux.next().block()).isNull()
    }

    @Test
    fun `streamOutput returns empty when no project exists`() {
        val config = minimalConfig().copy(projects = emptyMap())
        val state = RuntimeState()
        val orchestrator = createOrchestrator(config, state)
        val controller = ApiV1Controller(config, orchestrator)
        val flux = controller.streamOutput("ABC-1", "")
        assertThat(flux.next().block()).isNull()
    }

    @Test
    fun `streamOutput uses specific project when provided`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        runBlocking { state.appendOutput("1", "output") }
        val config = minimalConfig()
        val controller = ApiV1Controller(config, createOrchestrator(config, state))
        val flux = controller.streamOutput("ABC-1", "default")
        val result = flux.next().block()
        assertThat(result).isNotNull()
    }

    @Test
    fun `streamOutput returns empty when project does not exist`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val flux = controller.streamOutput("ABC-1", "missing-project")
        assertThat(flux.next().block()).isNull()
    }

    @Test
    fun `streamOutput returns empty when entry has no output flow`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val flux = controller.streamOutput("ABC-1", "")
        assertThat(flux.next().block()).isNull()
    }

    @Test
    fun `streamOutput finds running agent across projects when project omitted`() {
        val emptyState = RuntimeState()
        val activeState = RuntimeState()
        activeState.maxConcurrentAgents = 5
        val issue = Issue("2", "XYZ-9", "Other project", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        activeState.running["2"] = RunningEntry(
            issue = issue, threadId = "t-2", turnId = "u-2",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        runBlocking { activeState.appendOutput("2", "cross-project line") }
        val config = minimalConfig().copy(
            projects = mapOf(
                "default" to minimalConfig().projects.getValue("default"),
                "other" to minimalConfig().projects.getValue("default")
            )
        )
        val orchestrator = createOrchestrator(
            config,
            emptyState,
            slug = "default",
        ).let { first ->
            Orchestrator(
                config = config,
                linearClientFactory = { first.projects.getValue("default").linear },
                workspaceManagerFactory = { first.projects.getValue("default").workspaces },
                agentRunner = object : AgentRunner {
                    override fun events(): Flow<AgentEvent> = MutableSharedFlow<AgentEvent>().asSharedFlow()
                    override suspend fun run(
                        issue: Issue, attempt: Int?, prompt: String,
                        agentKindOverride: String?, commandOverride: String?,
                        modelOverride: String?,
                        effortOverride: String?,
                        turnTimeoutMs: Long?, stallTimeoutMs: Long?,
                    gitWorkflowOverride: GitWorkflow?
                    ): EmptyResult<IllegalStateException> = Result.Success(Unit)
                },
                workflowCache = WorkflowCache(),
                logger = StructuredLogger(emptyList()),
                scope = CoroutineScope(Job() + Dispatchers.Unconfined),
                runtimeStates = mapOf("default" to emptyState, "other" to activeState),
                metricsRepository = null
            )
        }
        val controller = ApiV1Controller(config, orchestrator)
        val result = controller.streamOutput("XYZ-9", "").next().block()
        assertThat(result).isNotNull()
        assertThat(result!!.data()).isEqualTo("cross-project line")
    }

    @Test
    fun `state includes project slug and paused flag for running rows`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null, paused = true
        )
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()
        assertThat(snapshot!!.running[0].projectSlug).isEqualTo("default")
        assertThat(snapshot.running[0].paused).isTrue()
    }

    @Test
    fun `state returns blocked entries`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test Issue", null, 1, "Todo", null, "http://url", emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        state.addBlocked("1")

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()
        assertThat(snapshot!!.blocked.size).isEqualTo(1)
        assertThat(snapshot.blocked[0].issueId).isEqualTo("1")
        assertThat(snapshot.blocked[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(snapshot.blocked[0].title).isEqualTo("Test Issue")
        assertThat(snapshot.blocked[0].url).isEqualTo("http://url")
    }

    @Test
    fun `state returns blocked entries with identifier from retry when not in running`() {
        val state = RuntimeState()
        state.retryAttempts["2"] = RetryEntry("2", "BLOCKED-2", 1, System.currentTimeMillis() + 60000, "error")
        state.addBlocked("2")

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()
        assertThat(snapshot!!.blocked.size).isEqualTo(1)
        assertThat(snapshot.blocked[0].issueIdentifier).isEqualTo("BLOCKED-2")
    }

    @Test
    fun `state returns blocked entries with running blockers`() {
        val state = RuntimeState()
        val blockerRef = BlockerRef("2", "XYZ-2", "Todo")
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), listOf(blockerRef), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null
        )
        state.addBlocked("1")

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()
        assertThat(snapshot!!.blocked.size).isEqualTo(1)
        assertThat(snapshot.blocked[0].blockedBy).isEqualTo(listOf("XYZ-2"))
    }

    @Test
    fun `dependencies returns empty when no project configured`() {
        val config = minimalConfig().copy(projects = emptyMap())
        val state = RuntimeState()
        val orchestrator = createOrchestrator(config, state)
        val controller = ApiV1Controller(config, orchestrator)
        val result = runBlocking { controller.dependencies("") }
        assertThat(result.nodes).isEmpty()
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `dependencies returns graph for configured project`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Issue 1", null, 1, "Todo", null, "http://url", emptyList(), emptyList(), null, null, null)
        val config = minimalConfig()
        val controller = ApiV1Controller(config, createOrchestrator(config, state, candidateIssues = listOf(issue)))
        val result = runBlocking { controller.dependencies("") }
        assertThat(result.nodes).isNotEmpty()
        assertThat(result.nodes[0].id).isEqualTo("1")
        assertThat(result.nodes[0].label).isEqualTo("ABC-1")
        assertThat(result.nodes[0].state).isEqualTo("Todo")
        assertThat(result.nodes[0].url).isEqualTo("http://url")
    }

    @Test
    fun `dependencies returns graph with edges and blockedBy`() {
        val state = RuntimeState()
        val blockerRef = BlockerRef("2", "XYZ-2", "Todo")
        val issue1 = Issue("1", "ABC-1", "Issue 1", null, 2, "Todo", null, null, emptyList(), listOf(blockerRef), null, null, null)
        val issue2 = Issue("2", "XYZ-2", "Issue 2", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        val config = minimalConfig()
        val controller = ApiV1Controller(config, createOrchestrator(config, state, candidateIssues = listOf(issue1, issue2)))
        val result = runBlocking { controller.dependencies("") }
        assertThat(result.nodes).hasSize(2)
        assertThat(result.edges).isNotEmpty()
        assertThat(result.edges[0].from).isEqualTo("ABC-1")
        assertThat(result.edges[0].to).isEqualTo("XYZ-2")
        val node = result.nodes.find { it.id == "1" }
        assertThat(node).isNotNull()
        assertThat(node!!.blockedBy).isEqualTo(listOf("XYZ-2"))
    }

    @Test
    fun `dependencies handles fetch error gracefully`() {
        val state = RuntimeState()
        val failingClient = object : LinearClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
                throw RuntimeException("API error")
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String) = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String) = null
            override suspend fun updateIssueState(issueId: String, stateId: String) {}
            override suspend fun createComment(issueId: String, body: String) {}
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String) = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>): Issue? = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
        }
        val config = minimalConfig()
        val orchestrator = Orchestrator(
            config = config,
            linearClientFactory = { failingClient },
            workspaceManagerFactory = { WorkspaceManager(Paths.get("/tmp"), HookExecutor { _, _ -> }) },
            agentRunner = object : AgentRunner {
                override fun events(): Flow<AgentEvent> = MutableSharedFlow<AgentEvent>().asSharedFlow()
                override suspend fun run(issue: Issue, attempt: Int?, prompt: String, agentKindOverride: String?, commandOverride: String?, modelOverride: String?, effortOverride: String?, turnTimeoutMs: Long?, stallTimeoutMs: Long?, gitWorkflowOverride: GitWorkflow?): EmptyResult<IllegalStateException> = Result.Success(Unit)
            },
            workflowCache = WorkflowCache(),
            logger = StructuredLogger(emptyList()),
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            runtimeStates = mapOf("default" to state),
            metricsRepository = null
        )
        val controller = ApiV1Controller(config, orchestrator)
        val result = runBlocking { controller.dependencies("") }
        assertThat(result.nodes).isEmpty()
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `dependencies returns empty when project slug not found`() {
        val state = RuntimeState()
        val config = minimalConfig()
        val controller = ApiV1Controller(config, createOrchestrator(config, state))
        val result = runBlocking { controller.dependencies("nonexistent") }
        assertThat(result.nodes).isEmpty()
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `history returns empty when repository is null`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = runBlocking { controller.history(null, 50) }
        assertThat(result).isEmpty()
    }

    @Test
    fun `history returns empty when repository is null with project filter`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = runBlocking { controller.history("some-project", 50) }
        assertThat(result).isEmpty()
    }

    @Test
    fun `rateLimitStats returns empty inner maps when no providers`() {
        val state = RuntimeState()
        val registry = RateLimitRegistry
        val field = RateLimitRegistry::class.java.getDeclaredField("providers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val providers = field.get(registry) as MutableMap<String, Any>
        providers.clear()
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val result = runBlocking { controller.rateLimitStats() }
        assertThat(result).isNotEmpty()
        assertThat(result["default"]).isNotNull()
        assertThat(result["default"]!!).isEmpty()
    }

    @Test
    fun `models returns empty list when project has no agent config`() {
        val state = RuntimeState()
        val config = minimalConfig()
        val controller = ApiV1Controller(config, createOrchestrator(config, state))
        val result = controller.models("nonexistent").block()
        assertThat(result!!.totalStages).isEqualTo(0)
        assertThat(result.configuredStages).isEmpty()
    }

    @Test
    fun `rateLimitStats returns stats for matched providers`() {
        val state = RuntimeState()
        val config = minimalConfig()
        val registry = RateLimitRegistry
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val rateLimitConfig = RateLimitConfig(
            requestsPerMinute = 60, requestsPerHour = 1000, burstCapacity = 20, backoffMs = 1000
        )
        registry.getOrCreate("test-default", rateLimitConfig, scope)
        try {
            val controller = ApiV1Controller(config, createOrchestrator(config, state))
            val result = runBlocking { controller.rateLimitStats() }
            assertThat(result).isNotEmpty()
            assertThat(result["default"]).isNotNull()
        } finally {
            val field = RateLimitRegistry::class.java.getDeclaredField("providers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val providers = field.get(registry) as MutableMap<String, Any>
            providers.clear()
        }
    }

    @Test
    fun `rateLimitStats returns stats for prefix matched providers`() {
        val state = RuntimeState()
        val config = minimalConfig()
        val registry = RateLimitRegistry
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val rateLimitConfig = RateLimitConfig(
            requestsPerMinute = 60, requestsPerHour = 1000, burstCapacity = 20, backoffMs = 1000
        )
        registry.getOrCreate("default:test", rateLimitConfig, scope)
        try {
            val controller = ApiV1Controller(config, createOrchestrator(config, state))
            val result = runBlocking { controller.rateLimitStats() }
            assertThat(result).isNotEmpty()
            assertThat(result["default"]).isNotNull()
        } finally {
            val field = RateLimitRegistry::class.java.getDeclaredField("providers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val providers = field.get(registry) as MutableMap<String, Any>
            providers.clear()
        }
    }

    @Test
    fun `state includes codex rate limits`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5
        state.updateCodexRateLimits(mapOf("gpt-4" to "active"))
        state.addTokenTotals(100, 50, 150)
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastHeartbeatAt = null,
            inputTokens = 100, outputTokens = 50, totalTokens = 150
        )

        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), state))
        val snapshot = controller.state().block()
        assertThat(snapshot!!.rateLimits["gpt-4"]).isEqualTo("active")
        assertThat(snapshot.tokenTotals.inputTokens).isEqualTo(100)
        assertThat(snapshot.tokenTotals.outputTokens).isEqualTo(50)
        assertThat(snapshot.tokenTotals.totalTokens).isEqualTo(150)
    }

    @Test
    fun `state aggregates running entries across multiple projects`() {
        val stateA = RuntimeState()
        val stateB = RuntimeState()
        stateA.running["1"] = RunningEntry(
            issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null),
            threadId = "t-1", turnId = "u-1", startedAt = Instant.now(), lastHeartbeatAt = null
        )
        stateB.running["2"] = RunningEntry(
            issue = Issue("2", "XYZ-2", "Other", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null),
            threadId = "t-2", turnId = "u-2", startedAt = Instant.now(), lastHeartbeatAt = null
        )
        val config = minimalConfig().copy(
            projects = mapOf(
                "proj-a" to minimalConfig().projects.getValue("default"),
                "proj-b" to minimalConfig().projects.getValue("default")
            )
        )
        val orchestrator = Orchestrator(
            config = config,
            linearClientFactory = { createOrchestrator(config, stateA).projects.getValue("proj-a").linear },
            workspaceManagerFactory = { WorkspaceManager(Paths.get("/tmp"), HookExecutor { _, _ -> }) },
            agentRunner = object : AgentRunner {
                override fun events(): Flow<AgentEvent> = MutableSharedFlow<AgentEvent>().asSharedFlow()
                override suspend fun run(
                    issue: Issue, attempt: Int?, prompt: String,
                    agentKindOverride: String?, commandOverride: String?,
                    modelOverride: String?, effortOverride: String?,
                    turnTimeoutMs: Long?, stallTimeoutMs: Long?,
                gitWorkflowOverride: GitWorkflow?
                ): EmptyResult<IllegalStateException> = Result.Success(Unit)
            },
            workflowCache = WorkflowCache(),
            logger = StructuredLogger(emptyList()),
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            runtimeStates = mapOf("proj-a" to stateA, "proj-b" to stateB),
            metricsRepository = null
        )
        val controller = ApiV1Controller(config, orchestrator)
        val snapshot = controller.state().block()
        assertThat(snapshot!!.running.size).isEqualTo(2)
        assertThat(snapshot.running.map { it.projectSlug }.toSet()).isEqualTo(setOf("proj-a", "proj-b"))
    }

    @Test
    fun `models uses requested project slug`() {
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "impl.md", model = "gpt-4", effort = null, maxConcurrent = null,
                agentKind = "codex", command = null, onCompleteState = null
            )
        )
        val pc = minimalConfig().projects["default"]!!
        val config = minimalConfig().copy(
            projects = mapOf(
                "alpha" to pc.copy(agent = pc.agent.copy(kind = "codex", stages = stages)),
                "beta" to pc.copy(agent = pc.agent.copy(kind = "opencode", stages = emptyMap()))
            )
        )
        val controller = ApiV1Controller(config, createOrchestrator(config, RuntimeState(), slug = "alpha"))
        val result = controller.models("alpha").block()
        assertThat(result!!.agentKind).isEqualTo("codex")
        assertThat(result.totalStages).isEqualTo(1)
        assertThat(result.configuredStages[0].model).isEqualTo("gpt-4")
    }

    @Test
    fun `withTimeout reads stream until deadline`() {
        val method = ApiV1Controller::class.java.getDeclaredMethod(
            "withTimeout",
            java.io.InputStream::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val input = "hello https://example.com world".byteInputStream()
        val text = method.invoke(controller, input, 1000L) as String
        assertThat(text.contains("hello")).isTrue()
    }

    @Test
    fun `getAgentAuthStatus returns configured agent kinds`() {
        val cfg = minimalConfig().copy(
            projects = mapOf(
                "default" to minimalConfig().projects.values.first().copy(
                    agent = minimalConfig().projects.values.first().agent.copy(
                        stages = mapOf(
                            "todo" to StageAgentConfig(
                                prompt = null, model = null, effort = null, maxConcurrent = null,
                                agentKind = "claude", command = null, onCompleteState = null,
                                agent = null, followUp = null, crossProjectFollowUp = null
                            )
                        )
                    )
                )
            )
        )
        val controller = ApiV1Controller(cfg, createOrchestrator(cfg, RuntimeState()))
        val response = controller.getAgentAuthStatus()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.any { it.agent == "claude" }).isTrue()
    }

    @Test
    fun `getCodexLoginStatus returns idle when no login process`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getCodexLoginStatus()
        assertThat(response.body!!.state).isEqualTo("idle")
    }

    @Test
    fun `getCodexLoginStatus returns completed when process exited`() {
        AgentAuthChecker.markUnauthenticated("codex")
        val process = ProcessBuilder("true").start()
        process.waitFor()
        setControllerField("codexProcess", process)
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getCodexLoginStatus()
        assertThat(response.body!!.state).isEqualTo("completed")
        assertThat(AgentAuthChecker.isAuthenticated("codex")).isTrue()
    }

    @Test
    fun `getClaudeLoginStatus extracts token when process exited`(@TempDir tokenDir: java.nio.file.Path) {
        AgentAuthChecker.markUnauthenticated("claude")
        val tokenPath = tokenDir.resolve("claude-oauth-token")
        System.setProperty("koncerto.claude.auth.token.path", tokenPath.toString())
        try {
            setControllerField(
                "claudeLoginOutput",
                """
                Your OAuth token
                sk-ant-oat01-testtoken123456789
                Store this token securely
                """.trimIndent()
            )
            val process = ProcessBuilder("true").start()
            process.waitFor()
            setControllerField("claudeProcess", process)
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.getClaudeLoginStatus()
            assertThat(response.body!!.state).isEqualTo("completed")
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
        } finally {
            System.clearProperty("koncerto.claude.auth.token.path")
        }
    }

    @Test
    fun `getCodexLoginStatus returns pending when process alive`() {
        AgentAuthChecker.markUnauthenticated("codex")
        val process = ProcessBuilder("sleep", "30").start()
        setControllerField("codexProcess", process)
        setControllerField("codexUrl", "https://auth.example.com")
        setControllerField("codexCode", "ABCD-1234")
        try {
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.getCodexLoginStatus()
            assertThat(response.body!!.state).isEqualTo("pending")
            assertThat(response.body!!.url).isEqualTo("https://auth.example.com")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `startCodexLogin returns pending when login already in progress`() {
        AgentAuthChecker.markUnauthenticated("codex")
        val process = ProcessBuilder("sleep", "30").start()
        setControllerField("codexProcess", process)
        setControllerField("codexUrl", "https://auth.example.com")
        setControllerField("codexCode", "ABCD-1234")
        try {
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.startCodexLogin().block()
            assertThat(response!!.statusCode.value()).isEqualTo(200)
            assertThat(response.body!!.state).isEqualTo("pending")
            assertThat(response.body!!.url).isEqualTo("https://auth.example.com")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `startCodexLogin returns conflict when alive process has no url`() {
        AgentAuthChecker.markUnauthenticated("codex")
        val process = ProcessBuilder("sleep", "30").start()
        setControllerField("codexProcess", process)
        try {
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.startCodexLogin().block()
            assertThat(response!!.statusCode.value()).isEqualTo(409)
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `cancelCodexLogin destroys active process`() {
        val process = ProcessBuilder("sleep", "30").start()
        setControllerField("codexProcess", process)
        setControllerField("codexUrl", "https://auth.example.com")
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.cancelCodexLogin()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(process.isAlive).isFalse()
    }

    @Test
    fun `getClaudeLoginStatus returns pending when process alive`() {
        AgentAuthChecker.markUnauthenticated("claude")
        val process = ProcessBuilder("sleep", "30").start()
        setControllerField("claudeProcess", process)
        setControllerField("claudeUrl", "https://claude.example.com")
        setControllerField("claudeCode", "CLDE-0000")
        try {
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.getClaudeLoginStatus()
            assertThat(response.body!!.state).isEqualTo("pending")
            assertThat(response.body!!.url).isEqualTo("https://claude.example.com")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `submitClaudeCode returns process_exited when login finished`() {
        val process = ProcessBuilder("true").start()
        process.waitFor()
        setControllerField("claudeProcess", process)
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.submitClaudeCode(mapOf("code" to "1234-ABCD"))
        assertThat(response.body!!.state).isEqualTo("process_exited")
    }

    @Test
    fun `submitClaudeCode writes code to active process`() {
        val process = ProcessBuilder("cat").start()
        setControllerField("claudeProcess", process)
        setControllerField("claudeUrl", "https://claude.example.com")
        setControllerField("claudeCode", "CLDE-0000")
        try {
            val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
            val response = controller.submitClaudeCode(mapOf("code" to "1234-ABCD"))
            assertThat(response.statusCode.value()).isEqualTo(200)
            assertThat(response.body!!.state).isEqualTo("pending")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `cancelCodexLogin returns ok`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.cancelCodexLogin()
        assertThat(response.statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `cancelClaudeLogin returns ok`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.cancelClaudeLogin()
        assertThat(response.statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `getClaudeLoginStatus returns completed when authenticated`() {
        AgentAuthChecker.markAuthenticated("claude")
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getClaudeLoginStatus()
        assertThat(response.body!!.state).isEqualTo("completed")
    }

    @Test
    fun `startClaudeLogin returns completed when already authenticated`() {
        AgentAuthChecker.markAuthenticated("claude")
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.startClaudeLogin().block()
        assertThat(response!!.body!!.state).isEqualTo("completed")
    }

    @Test
    fun `submitClaudeCode returns bad request without code`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.submitClaudeCode(emptyMap())
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.state).isEqualTo("error")
    }

    @Test
    fun `submitClaudeCode returns no_process when login not started`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.submitClaudeCode(mapOf("code" to "1234-ABCD"))
        assertThat(response.body!!.state).isEqualTo("no_process")
    }

    @Test
    fun `StateSnapshot and RunningRow DTOs hold values`() {
        val row = ApiV1Controller.RunningRow(
            issueId = "1",
            issueIdentifier = "ABC-1",
            threadId = "t-1",
            turnId = "u-1",
            turnCount = 4,
            inputTokens = 200,
            outputTokens = 100,
            totalTokens = 300,
            url = "https://linear.app/issue/ABC-1",
            projectSlug = "default",
            paused = true
        )
        val snapshot = ApiV1Controller.StateSnapshot(
            running = listOf(row),
            retrying = emptyList(),
            blocked = emptyList(),
            tokenTotals = ApiV1Controller.Totals(200, 100, 300, 42),
            rateLimits = mapOf("gpt-4" to "active")
        )
        assertThat(snapshot.running.single().issueIdentifier).isEqualTo("ABC-1")
        assertThat(snapshot.running.single().paused).isTrue()
        assertThat(snapshot.tokenTotals.totalTokens).isEqualTo(300)
        assertThat(snapshot.tokenTotals.secondsRunning).isEqualTo(42)
        assertThat(snapshot.rateLimits["gpt-4"]).isEqualTo("active")
    }

    @Test
    fun `RetryingRow BlockedRow and auth DTOs hold values`() {
        val retrying = ApiV1Controller.RetryingRow("1", "ABC-1", 2, 1_700_000_000_000L, "timeout")
        val blocked = ApiV1Controller.BlockedRow(
            issueId = "2",
            issueIdentifier = "DEF-2",
            title = "Blocked task",
            url = "https://linear.app/issue/DEF-2",
            blockedBy = listOf("ABC-1")
        )
        val auth = ApiV1Controller.AgentAuthStatus("codex", authenticated = true, needsAuth = true)
        val device = ApiV1Controller.DeviceAuthResponse("https://auth.example.com", "ABCD-1234")
        val login = ApiV1Controller.CodexLoginStatus("pending", device.url, device.code)
        val rateLimit = ApiV1Controller.RateLimitStatsEntry(
            availableTokens = 10,
            requestsLastMinute = 2,
            requestsLastHour = 15,
            limitPerMinute = 60,
            limitPerHour = 1000
        )
        assertThat(retrying.attempt).isEqualTo(2)
        assertThat(blocked.blockedBy).containsExactly("ABC-1")
        assertThat(auth.agent).isEqualTo("codex")
        assertThat(device.code).isEqualTo("ABCD-1234")
        assertThat(login.state).isEqualTo("pending")
        assertThat(rateLimit.availableTokens).isEqualTo(10)
    }

    @Test
    fun `getAgentAuthStatus returns default agent kinds when stages empty`() {
        val cfg = minimalConfig()
        val controller = ApiV1Controller(cfg, createOrchestrator(cfg, RuntimeState()))
        val response = controller.getAgentAuthStatus()
        assertThat(response.body!!.map { it.agent }).containsExactly("codex", "opencode", "claude")
    }

    @Test
    fun `getClaudeLoginStatus returns idle when no login process`() {
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getClaudeLoginStatus()
        assertThat(response.body!!.state).isEqualTo("idle")
    }

    @Test
    fun `submitClaudeCode returns write_failed when stdin write throws`() {
        val fakeProcess = object : Process() {
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(b: Int) { throw java.io.IOException("write failed") }
                override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("write failed") }
            }
            override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
            override fun exitValue() = 0
            override fun destroy() {}
            override fun isAlive() = true
        }
        setControllerField("claudeProcess", fakeProcess)
        setControllerField("claudeUrl", "https://claude.example.com")
        setControllerField("claudeCode", "CLDE-0000")
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.submitClaudeCode(mapOf("code" to "1234-ABCD"))
        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body!!.state).isEqualTo("write_failed")
    }

    @Test
    fun `getCodexLoginStatus returns completed when process finished`() {
        val process = ProcessBuilder("true").start()
        process.waitFor()
        setControllerField("codexProcess", process)
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getCodexLoginStatus()
        assertThat(response.body!!.state).isEqualTo("completed")
    }

    @Test
    fun `getClaudeLoginStatus extracts token from process output on completion`() {
        AgentAuthChecker.markUnauthenticated("claude")
        val process = ProcessBuilder("bash", "-lc", "echo 'oauth_token=sk-ant-oat01-test'").start()
        process.waitFor()
        setControllerField("claudeProcess", process)
        setControllerField("claudeLoginOutput", "oauth_token=sk-ant-oat01-test")
        val controller = ApiV1Controller(minimalConfig(), createOrchestrator(minimalConfig(), RuntimeState()))
        val response = controller.getClaudeLoginStatus()
        assertThat(response.body!!.state).isEqualTo("completed")
    }

    private fun setControllerField(name: String, value: Any?) {
        val field = ApiV1Controller::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(null, value)
    }
}

private class FakeMetricsRepository(private val allMetrics: List<IssueMetrics> = listOf(
    IssueMetrics("1", "ABC-1", null, 5, 100, 50, 150, "SUCCESS", "2024-01-01", "2024-01-01", "2024-01-10"),
    IssueMetrics("2", "DEF-2", "project-x", 3, 200, 100, 300, "FAILED", "2024-02-01", "2024-02-01", "2024-02-10")
)) : MetricsRepository {
    override suspend fun updateAfterRun(issueId: String, issueIdentifier: String, projectSlug: String?, result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long) = Unit
    override suspend fun findAll(): List<IssueMetrics> = allMetrics
    override suspend fun findByProject(projectSlug: String?): List<IssueMetrics> = allMetrics.filter { it.projectSlug == projectSlug }
    override suspend fun findById(issueId: String): IssueMetrics? = allMetrics.find { it.issueId == issueId }
    override suspend fun tokenHistory(days: Int): List<TokenDaySummary> = emptyList()
}
