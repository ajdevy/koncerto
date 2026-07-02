package com.flexsentlabs.koncerto.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.DefaultLinearClient
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.linear.LinearGraphQLClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e")
class BlockedIssuesE2eTest {

    @Test
    fun `blocked issue is dispatched after blocker completes via full orchestration loop`() = runBlocking {
        val root = Files.createTempDirectory("koncerto-e2e-blocked-")
        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val issueA = Issue(
                id = "a", identifier = "A-1", title = "Blocker",
                description = null, priority = 5, state = "In Progress",
                branchName = null, url = null, labels = emptyList(),
                blockedBy = emptyList(), createdAt = null, updatedAt = null
            )
            val issueB = Issue(
                id = "b", identifier = "B-1", title = "Blocked",
                description = null, priority = 5, state = "Todo",
                branchName = null, url = null, labels = emptyList(),
                blockedBy = listOf(BlockerRef(id = "a", identifier = "A-1", state = "In Progress")),
                createdAt = null, updatedAt = null
            )

            val linear = ControlledLinearClient(listOf(issueA, issueB))
            val runner = CollectingE2eRunner()
            val state = RuntimeState()
            val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
            val logger = StructuredLogger(emptyList())

            val config = ServiceConfig(
                pollIntervalMs = 200,
                projects = mapOf("proj" to ProjectConfig(
                    tracker = TrackerConfig(
                        kind = "linear", endpoint = "x", apiKey = "k",
                        projectSlug = "proj", requiredLabels = emptyList(),
                        activeStates = listOf("Todo", "In Progress"),
                        terminalStates = listOf("Done", "Closed"),
                        blockedState = "Blocked", projectAdmin = null
                    ),
                    workspace = WorkspaceConfig(root = root.toString()),
                    agent = AgentProjectConfig(maxConcurrentAgents = 10)
                )),
                hooks = HooksConfig(null, null, null, null, 60000),
                gitConfig = GitConfig()
            )

            val orch = Orchestrator(
                config = config,
                linearClientFactory = { linear },
                workspaceManagerFactory = { WorkspaceManager(root, HookExecutor { _, _ -> }) },
                agentRunner = runner,
                workflowCache = cache,
                logger = logger,
                scope = scope,
                runtimeStates = mapOf("proj" to state)
            )

            orch.start()

            waitFor({ runner.dispatched.any { it.id == "a" } }, 60000)
            assertThat(runner.dispatched.map { it.id }).isEqualTo(listOf("a"))
            assertThat(state.isBlocked("b")).isTrue()

            linear.setState("a", "Done")

            waitFor({ runner.dispatched.any { it.id == "b" } }, 60000)
            assertThat(runner.dispatched.map { it.id }).isEqualTo(listOf("a", "b"))
            assertThat(state.isBlocked("b")).isFalse()

            orch.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blocked issue is not dispatched when Linear GraphQL reports active blocker via inverseRelations`() = runBlocking {
        val root = Files.createTempDirectory("koncerto-e2e-blocked-graphql-")
        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val blockerNode = makeIssueNode("a", "FLE-52", "In Progress")
            val blockedNode = makeIssueNodeWithBlocker("b", "FLE-53", "Todo", "a", "FLE-52", "In Progress")
            val fakeGraphql = FakeLinearGraphqlClient(mutableListOf(blockerNode, blockedNode))
            val linear = DefaultLinearClient(fakeGraphql, "proj")

            val runner = CollectingE2eRunner()
            val state = RuntimeState()
            val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
            val logger = StructuredLogger(emptyList())

            val config = ServiceConfig(
                pollIntervalMs = 200,
                projects = mapOf("proj" to ProjectConfig(
                    tracker = TrackerConfig(
                        kind = "linear", endpoint = "x", apiKey = "k",
                        projectSlug = "proj", requiredLabels = emptyList(),
                        activeStates = listOf("Todo", "In Progress"),
                        terminalStates = listOf("Done", "Closed"),
                        blockedState = "Blocked", projectAdmin = null
                    ),
                    workspace = WorkspaceConfig(root = root.toString()),
                    agent = AgentProjectConfig(maxConcurrentAgents = 10)
                )),
                hooks = HooksConfig(null, null, null, null, 60000),
                gitConfig = GitConfig()
            )

            val orch = Orchestrator(
                config = config,
                linearClientFactory = { linear },
                workspaceManagerFactory = { WorkspaceManager(root, HookExecutor { _, _ -> }) },
                agentRunner = runner,
                workflowCache = cache,
                logger = logger,
                scope = scope,
                runtimeStates = mapOf("proj" to state)
            )

            orch.start()

            waitFor({ runner.dispatched.any { it.identifier == "FLE-52" } }, 10000)

            delay(600)
            assertThat(runner.dispatched.any { it.identifier == "FLE-53" }).isFalse()
            assertThat(state.isBlocked("b")).isTrue()

            orch.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitFor(condition: () -> Boolean, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(100)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}

private class ControlledLinearClient(issues: List<Issue>) : LinearClient {
    private val issueStates = ConcurrentHashMap<String, String>()
    private val candidates = issues.toMutableList()

    init {
        issues.forEach { issueStates[it.id] = it.state }
    }

    fun setState(issueId: String, newState: String) {
        issueStates[issueId] = newState
        val idx = candidates.indexOfFirst { it.id == issueId }
        if (idx >= 0) {
            candidates[idx] = candidates[idx].copy(state = newState)
        }
    }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        candidates.filter { issue -> activeStates.any { it.equals(issue.state, ignoreCase = true) } }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        candidates.filter { stateNames.contains(it.state) }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        issueIds.mapNotNull { id -> issueStates[id]?.let { id to it } }.toMap()

    override suspend fun fetchIssueById(issueId: String): Issue? = candidates.firstOrNull { it.id == issueId }
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

private class CollectingE2eRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue, attempt: Int?, prompt: String,
        agentKindOverride: String?, commandOverride: String?,
        modelOverride: String?,
        effortOverride: String?,
        turnTimeoutMs: Long?, stallTimeoutMs: Long?,
    gitWorkflowOverride: GitWorkflow?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Success(Unit)
    }
}

private fun makeIssueNode(id: String, identifier: String, state: String): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("identifier", JsonPrimitive(identifier))
    put("title", JsonPrimitive("T-$identifier"))
    put("description", JsonNull)
    put("priority", JsonNull)
    put("url", JsonNull)
    put("branchName", JsonNull)
    put("createdAt", JsonNull)
    put("updatedAt", JsonNull)
    put("state", buildJsonObject { put("name", JsonPrimitive(state)) })
    put("labels", buildJsonObject { put("nodes", buildJsonArray {}) })
    put("children", buildJsonObject { put("nodes", buildJsonArray {}) })
    put("blockedBy", buildJsonObject { put("nodes", buildJsonArray {}) })
}

private fun makeIssueNodeWithBlocker(
    id: String, identifier: String, state: String,
    blockerId: String, blockerIdentifier: String, blockerState: String
): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("identifier", JsonPrimitive(identifier))
    put("title", JsonPrimitive("T-$identifier"))
    put("description", JsonNull)
    put("priority", JsonNull)
    put("url", JsonNull)
    put("branchName", JsonNull)
    put("createdAt", JsonNull)
    put("updatedAt", JsonNull)
    put("state", buildJsonObject { put("name", JsonPrimitive(state)) })
    put("labels", buildJsonObject { put("nodes", buildJsonArray {}) })
    put("children", buildJsonObject { put("nodes", buildJsonArray {}) })
    put("blockedBy", buildJsonObject {
        put("nodes", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("blocks"))
                put("issue", buildJsonObject {
                    put("id", JsonPrimitive(blockerId))
                    put("identifier", JsonPrimitive(blockerIdentifier))
                    put("state", buildJsonObject { put("name", JsonPrimitive(blockerState)) })
                })
            })
        })
    })
}

private class FakeLinearGraphqlClient(
    private val issueNodes: MutableList<JsonObject>
) : LinearGraphQLClient("http://localhost:1", "fake-key") {

    override suspend fun execute(query: String, variables: JsonObject): JsonObject {
        if (query.contains("ProjectSlugId")) {
            val projectId = (variables["projectId"] as? JsonPrimitive)?.content ?: "proj"
            return buildJsonObject {
                put("data", buildJsonObject {
                    put("project", buildJsonObject { put("slugId", JsonPrimitive(projectId)) })
                })
            }
        }
        return buildJsonObject {
            put("data", buildJsonObject {
                put("issues", buildJsonObject {
                    put("pageInfo", buildJsonObject {
                        put("hasNextPage", JsonPrimitive(false))
                        put("endCursor", JsonNull)
                    })
                    put("nodes", buildJsonArray { issueNodes.forEach { add(it) } })
                })
            })
        }
    }
}
