package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.orchestrator.RetryEntry
import com.flexsentlabs.koncerto.orchestrator.RunningEntry
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.jupiter.api.Test

class OrchestratorHealthIndicatorTest {

    @Test
    fun `health returns UP status`() {
        val state = RuntimeState()
        val orch = createOrchestrator(state)

        val health = OrchestratorHealthIndicator(orch).health()

        assertThat(health.status.code).isEqualTo("UP")
    }

    @Test
    fun `health reports zero running agents for empty state`() {
        val state = RuntimeState()
        val health = OrchestratorHealthIndicator(createOrchestrator(state)).health()

        assertThat(health.details["runningAgents"]).isEqualTo(0)
        assertThat(health.details["blockedIssues"]).isEqualTo(0)
        assertThat(health.details["retryingIssues"]).isEqualTo(0)
    }

    @Test
    fun `health reports running agents`() {
        val state = RuntimeState()
        state.running["1"] = runningEntry("1", "ABC-1")
        state.running["2"] = runningEntry("2", "ABC-2")
        val health = OrchestratorHealthIndicator(createOrchestrator(state)).health()

        assertThat(health.details["runningAgents"]).isEqualTo(2)
    }

    @Test
    fun `health reports blocked and retrying issues`() {
        val state = RuntimeState()
        state.addBlocked("issue-1")
        state.addBlocked("issue-2")
        state.retryAttempts["r1"] = RetryEntry(
            issueId = "r1", identifier = "ABC-3",
            attempt = 1, dueAtMs = System.currentTimeMillis() + 60000, error = "timeout"
        )
        val health = OrchestratorHealthIndicator(createOrchestrator(state)).health()

        assertThat(health.details["blockedIssues"]).isEqualTo(2)
        assertThat(health.details["retryingIssues"]).isEqualTo(1)
    }

    @Test
    fun `health reports uptime`() {
        val health = OrchestratorHealthIndicator(createOrchestrator()).health()

        assertThat(health.details.containsKey("uptimeMs")).isTrue()
        assertThat((health.details["uptimeMs"] as Number).toLong() > 0L).isTrue()
    }

    @Test
    fun `health aggregates counts across multiple projects`() {
        val stateA = RuntimeState().apply {
            running["1"] = runningEntry("1", "ABC-1")
            addBlocked("blocked-a")
        }
        val stateB = RuntimeState().apply {
            running["2"] = runningEntry("2", "ABC-2")
            running["3"] = runningEntry("3", "ABC-3")
            retryAttempts["r1"] = RetryEntry(
                issueId = "r1", identifier = "ABC-4",
                attempt = 1, dueAtMs = System.currentTimeMillis() + 60_000, error = "timeout"
            )
        }
        val orch = createOrchestrator(
            runtimeStates = mapOf("proj-a" to stateA, "proj-b" to stateB),
            projectSlugs = listOf("proj-a", "proj-b")
        )
        val health = OrchestratorHealthIndicator(orch).health()

        assertThat(health.details["runningAgents"]).isEqualTo(3)
        assertThat(health.details["blockedIssues"]).isEqualTo(1)
        assertThat(health.details["retryingIssues"]).isEqualTo(1)
    }

    // ── helpers ────────────────────────────────────────────────

    private fun createOrchestrator(state: RuntimeState = RuntimeState()): Orchestrator =
        createOrchestrator(mapOf("proj" to state), listOf("proj"))

    private fun createOrchestrator(
        runtimeStates: Map<String, RuntimeState>,
        projectSlugs: List<String> = runtimeStates.keys.toList()
    ): Orchestrator {
        val root = Files.createTempDirectory("health-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val projects = projectSlugs.associateWith { slug ->
            ProjectConfig(
                tracker = TrackerConfig(
                    kind = "linear", endpoint = "x", apiKey = "k", projectSlug = slug
                ),
                workspace = WorkspaceConfig(root = "/tmp"),
                agent = AgentProjectConfig(
                    kind = "codex", command = "codex app-server",
                    maxConcurrentAgents = 10, maxTurns = 1, maxRetryBackoffMs = 300000,
                    turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                    stages = emptyMap()
                )
            )
        }
        return Orchestrator(
            config = ServiceConfig(projects = projects),
            linearClientFactory = { FakeLinearClient() },
            workspaceManagerFactory = { mgr },
            agentRunner = FakeAgentRunner(),
            workflowCache = WorkflowCache(),
            logger = StructuredLogger(emptyList()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            runtimeStates = runtimeStates
        )
    }

    private fun runningEntry(id: String, identifier: String, state: String = "Todo") = RunningEntry(
        issue = Issue(
            id = id, identifier = identifier, title = "t", description = null,
            priority = 5, state = state, branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        ),
        threadId = "thread-$id",
        turnId = "turn-$id",
        startedAt = Instant.now(),
        lastHeartbeatAt = null
    )
}

class FakeLinearClient : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> = emptyMap()
    override suspend fun fetchIssueById(issueId: String): Issue? = null
    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null
    override suspend fun updateIssueState(issueId: String, stateId: String) = Unit
    override suspend fun createComment(issueId: String, body: String) = Unit
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) = Unit
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? = null
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
}

class FakeAgentRunner : AgentRunner {
    private val flow = MutableSharedFlow<com.flexsentlabs.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue, attempt: Int?, prompt: String,
        agentKindOverride: String?, commandOverride: String?,
        modelOverride: String?,
        effortOverride: String?,
        turnTimeoutMs: Long?, stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> = Result.Success(Unit)
}
