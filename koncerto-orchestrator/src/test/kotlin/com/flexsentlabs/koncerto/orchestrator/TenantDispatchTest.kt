package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.TenantConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tenant.ConfigTenantResolver
import com.flexsentlabs.koncerto.core.tenant.TenantContext
import com.flexsentlabs.koncerto.core.tenant.TenantId
import com.flexsentlabs.koncerto.core.tenant.TenantResolver
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.CompletableDeferred
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TenantDispatchTest {

    @Test
    fun `tenantContext is set on RunningEntry after dispatch`() {
        val state = RuntimeState()
        val runner = TenantTestAgentRunner()
        val tenantResolver: TenantResolver = ConfigTenantResolver()
        val config = configWithTenant()
        val svc = DispatchService(
            projectConfig = config,
            state = state,
            linear = TenantTestLinear(listOf(issue("1", "A-1", "Todo"))),
            agentRunner = runner,
            workflowCache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) },
            logger = StructuredLogger(emptyList()),
            projectSlug = "my-project",
            tenantResolver = tenantResolver
        )
        runBlocking {
            svc.fetchAndDispatch(this)
            runner.started.await()
            val entry = state.running["1"]
            assertThat(entry).isNotNull()
            assertThat(entry!!.tenantContext).isNotNull()
            assertThat(entry.tenantContext!!.tenantId.value).isEqualTo("my-project")
            assertThat(entry.tenantContext!!.tier).isEqualTo("enterprise")
            assertThat(entry.tenantContext!!.quotaProfile).isEqualTo("large")
            runner.complete.complete(Unit)
        }
    }

    @Test
    fun `tenantContext is null when no resolver provided`() {
        val state = RuntimeState()
        val runner = TenantTestAgentRunner()
        val svc = DispatchService(
            projectConfig = configWithTenant(),
            state = state,
            linear = TenantTestLinear(listOf(issue("1", "A-1", "Todo"))),
            agentRunner = runner,
            workflowCache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) },
            logger = StructuredLogger(emptyList()),
            projectSlug = "my-project",
            tenantResolver = null
        )
        runBlocking {
            svc.fetchAndDispatch(this)
            runner.started.await()
            val entry = state.running["1"]
            assertThat(entry).isNotNull()
            assertThat(entry!!.tenantContext).isNull()
            runner.complete.complete(Unit)
        }
    }

    @Test
    fun `workspace returns tenant-scoped path`() {
        val root = java.nio.file.Files.createTempDirectory("tenant-ws-")
        try {
            val ws = WorkspaceManager(root, HookExecutor { _, _ -> })
            val tenantContext = TenantContext(
                tenantId = TenantId("tenant-1"),
                projectSlug = "my-app"
            )
            val workspace = ws.ensureWorkspace("ISSUE-42", tenantContext)
            val expectedPath = root.resolve("tenant-1").resolve("my-app").resolve("ISSUE-42")
            assertThat(workspace.path).isEqualTo(expectedPath.toAbsolutePath().normalize())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace returns non-scoped path without tenant context`() {
        val root = java.nio.file.Files.createTempDirectory("plain-ws-")
        try {
            val ws = WorkspaceManager(root, HookExecutor { _, _ -> })
            val workspace = ws.ensureWorkspace("ISSUE-42")
            val expectedPath = root.resolve("ISSUE-42")
            assertThat(workspace.path).isEqualTo(expectedPath.toAbsolutePath().normalize())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configWithTenant() = ProjectConfig(
        tracker = TrackerConfig(
            kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p"
        ),
        workspace = WorkspaceConfig(root = "/tmp"),
        agent = AgentProjectConfig(maxConcurrentAgents = 10),
        tenant = TenantConfig(tier = "enterprise", quotaProfile = "large")
    )

    companion object {
        fun issue(
            id: String, identifier: String, state: String,
            priority: Int = 5, labels: List<String> = emptyList()
        ) = Issue(
            id = id, identifier = identifier, title = "t", description = null,
            priority = priority, state = state, branchName = null, url = null,
            labels = labels, blockedBy = emptyList(),
            createdAt = null, updatedAt = null
        )
    }
}

private class TenantTestLinear(private val candidates: List<Issue>) : LinearClient {
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
    override suspend fun fetchIssueCreator(issueId: String) = null
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
}

private class TenantTestAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    val started = CompletableDeferred<Unit>()
    val complete = CompletableDeferred<Unit>()
    private val flow = MutableSharedFlow<com.flexsentlabs.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?,
        modelOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
    ): com.flexsentlabs.koncerto.core.result.EmptyResult<IllegalStateException> {
        dispatched += issue
        started.complete(Unit)
        complete.await()
        return com.flexsentlabs.koncerto.core.result.Result.Success(Unit)
    }
}
