package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.WorkflowDefinition
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
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
        orch.fetchAndDispatchPublic()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-3", "A-1")
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
        codexCommand = "codex app-server", codexApprovalPolicy = null,
        codexThreadSandbox = null, codexTurnSandboxPolicy = null,
        turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000
    )
}

class FakeLinearClient(private val candidates: List<Issue>) : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        candidates.filter { issue -> activeStates.any { it.equals(issue.state, ignoreCase = true) } }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        candidates.filter { stateNames.contains(it.state) }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        candidates.filter { issueIds.contains(it.id) }.associate { it.id to it.state }
}

class FakeAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(issue: Issue, attempt: Int?, prompt: String): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Success(Unit)
    }
}

fun Orchestrator.fetchAndDispatchPublic() {
    runBlocking { this@fetchAndDispatchPublic.fetchAndDispatch() }
}
