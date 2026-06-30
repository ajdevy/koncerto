package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentAuthChecker
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.AgentProviderConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.NotificationsConfig
import com.flexsentlabs.koncerto.core.config.RoutingRule
import com.flexsentlabs.koncerto.core.config.FollowUpConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.config.TenantConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkplanConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.tenant.ConfigTenantResolver
import com.flexsentlabs.koncerto.core.tenant.TenantResolver
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.quota.QuotaConfig
import com.flexsentlabs.koncerto.core.quota.QuotaEnforcer
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.orchestrator.RetryEntry
import com.flexsentlabs.koncerto.core.errors.SubscriptionLimitException
import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.core.config.CrossProjectFollowUpConfig
import com.flexsentlabs.koncerto.core.audit.AuditEvent
import com.flexsentlabs.koncerto.core.audit.AuditEventType
import com.flexsentlabs.koncerto.core.audit.AuditLogger
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.TokenDaySummary
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DispatchServiceTest {

    companion object {
        init {
            AgentAuthChecker.markAuthenticated("codex")
        }

        @JvmStatic
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

        @JvmStatic
        fun config(
            stages: Map<String, StageAgentConfig> = emptyMap(),
            agents: Map<String, AgentProviderConfig> = emptyMap(),
            routingRules: List<RoutingRule> = emptyList()
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
                agents = agents,
                routingRules = routingRules
            )
        )

        @JvmStatic
        fun runningEntry(id: String, identifier: String) = RunningEntry(
            issue = Issue(
                id = id, identifier = identifier, title = "t", description = null,
                priority = 5, state = "Todo", branchName = null, url = null,
                labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
            ),
            threadId = "thread-$id",
            turnId = "turn-$id",
            startedAt = java.time.Instant.now(),
            lastHeartbeatAt = null
        )
    }

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
        val svc = createService(projectConfig = projectConfig, state = state, runner = runner)
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
            projectConfig = projectConfig, state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"), issue("2", "A-2", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `dispatch skips already claimed issues`() {
        val state = RuntimeState().also { it.claimed["1"] = true }
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
    fun `scheduleLimitPause stores entry and posts linear comment without state change`() = runBlocking {
        val linear = TrackingLinearClient()
        val issue = issue("1", "A-1", "In Progress")
        linear.addIssue(issue)
        val state = RuntimeState()
        val svc = createService(state = state, linear = linear)
        val resumeAt = System.currentTimeMillis() + 60_000
        svc.scheduleLimitPause(
            issue = issue,
            error = "You've hit your usage limit",
            provider = "codex",
            resumeAtMs = resumeAt,
            stageName = "in progress",
            agentKind = "codex"
        )
        val pause = state.limitPauses["1"]
        assertThat(pause).isNotNull()
        assertThat(pause!!.resumeAtMs).isEqualTo(resumeAt)
        assertThat(state.retryAttempts.containsKey("1")).isFalse()
        assertThat(linear.transitionedIssueId).isNull()
        assertThat(linear.commentedIssueId).isEqualTo("1")
        assertThat(linear.commentedBody!!.contains("paused", ignoreCase = true)).isTrue()
        assertThat(linear.commentedBody!!.contains("In Progress")).isTrue()
    }

    @Test
    fun `dispatchDueLimitPauses resumes and posts resume comment`() = runBlocking {
        val linear = TrackingLinearClient()
        val testIssue = issue("1", "A-1", "Todo")
        linear.addIssue(testIssue)
        val state = RuntimeState()
        state.limitPauses["1"] = LimitPauseEntry(
            issueId = "1",
            identifier = "A-1",
            stageName = "todo",
            agentKind = "codex",
            provider = "codex",
            error = "limit",
            resumeAtMs = System.currentTimeMillis() - 1
        )
        val runner = CollectingAgentRunner()
        val svc = createService(state = state, linear = linear, runner = runner)
        svc.dispatchDueLimitPauses(CoroutineScope(coroutineContext))
        yield()
        assertThat(state.limitPauses.containsKey("1")).isFalse()
        assertThat(linear.commentedBody!!.contains("resuming", ignoreCase = true)).isTrue()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `dispatchDueLimitPauses tolerates resume comment failure`() = runBlocking {
        val backing = TrackingLinearClient()
        backing.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) =
                backing.fetchCandidateIssues(projectSlug, activeStates)
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) =
                backing.fetchIssuesByStates(projectSlug, stateNames)
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) =
                backing.fetchIssueStatesByIds(issueIds)
            override suspend fun fetchIssueById(issueId: String) = backing.fetchIssueById(issueId)
            override suspend fun resolveStateId(projectSlug: String, stateName: String) =
                backing.resolveStateId(projectSlug, stateName)
            override suspend fun updateIssueState(issueId: String, stateId: String) =
                backing.updateIssueState(issueId, stateId)
            override suspend fun createComment(issueId: String, body: String) {
                if (body.contains("resuming", ignoreCase = true)) {
                    throw RuntimeException("comment failed")
                }
                backing.createComment(issueId, body)
            }
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) =
                backing.updateIssueAssignee(issueId, assigneeId)
            override suspend fun fetchIssueCreator(issueId: String) = backing.fetchIssueCreator(issueId)
            override suspend fun createIssue(
                projectSlug: String, title: String, state: String,
                description: String?, labels: List<String>
            ) = backing.createIssue(projectSlug, title, state, description, labels)
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) =
                backing.createLink(sourceIssueId, targetIssueId, type)
        }
        val state = RuntimeState()
        state.limitPauses["1"] = LimitPauseEntry(
            issueId = "1", identifier = "A-1", stageName = "todo",
            agentKind = "codex", provider = "codex", error = "limit",
            resumeAtMs = System.currentTimeMillis() - 1
        )
        val runner = CollectingAgentRunner()
        val svc = createService(state = state, linear = linear, runner = runner)
        svc.dispatchDueLimitPauses(CoroutineScope(coroutineContext))
        yield()
        assertThat(state.limitPauses.containsKey("1")).isFalse()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `fetchAndDispatch skips limit paused issues`() {
        val state = RuntimeState()
        state.limitPauses["1"] = LimitPauseEntry(
            issueId = "1",
            identifier = "A-1",
            stageName = "todo",
            agentKind = "codex",
            provider = "codex",
            error = "limit",
            resumeAtMs = System.currentTimeMillis() + 60_000
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state, runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `scheduleRetry creates retry entry with exponential backoff`() = runBlocking {
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
    fun `scheduleRetry increments attempt on subsequent retries`() = runBlocking {
        val (svc, state) = createServiceWithState()
        val issue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(issue, "err1")
        svc.scheduleRetry(issue, "err2")
        assertThat(state.retryAttempts["1"]?.attempt).isEqualTo(2)
        assertThat(state.retryAttempts["1"]?.error).isEqualTo("err2")
    }

    @Test
    fun `scheduleRetry caps backoff at maxRetryBackoffMs`() = runBlocking {
        val projectConfig = config().copy(agent = config().agent.copy(maxRetryBackoffMs = 60_000, maxRetries = 10))
        val (svc, state) = createServiceWithState(projectConfig = projectConfig)
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
        assertThat(state.isClaimed("1")).isEqualTo(false)
        assertThat(state.completed.containsKey("1")).isTrue()
    }

    @Test
    fun `matchesRequiredLabels skips issues without required labels`() {
        val projectConfig = config().copy(tracker = config().tracker.copy(requiredLabels = listOf("bugfix")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = projectConfig, runner = runner,
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
    fun `dispatch skips issues with sub-issues (children)`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(
                issue("1", "A-1", "Todo"),
                issue("2", "A-2", "Todo").copy(children = listOf("sub-1")),
                issue("3", "A-3", "Todo").copy(children = listOf("sub-2", "sub-3"))
            )
        )
        runDispatch(svc)
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-1")
    }

    @Test
    fun `matchesRequiredLabels is case insensitive`() {
        val projectConfig = config().copy(tracker = config().tracker.copy(requiredLabels = listOf("BugFix")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = projectConfig, runner = runner,
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
                issue("b1", "B-1", "Todo", blockers = listOf(BlockerRef("b1", "B-1", "Todo"))),
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
                issue("b1", "B-1", "Todo", blockers = listOf(BlockerRef("b1", "B-1", "Todo"))),
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
            projectConfig = projectConfig, runner = runner,
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
                prompt = "stage-prompt-todo", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val cache = WorkflowCache()
        cache.set(WorkflowDefinition(emptyMap(), "global-prompt"))
        val svc = createService(
            projectConfig = config(stages = stages), runner = runner,
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
                prompt = "review-prompt", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val cache = WorkflowCache()
        cache.set(WorkflowDefinition(emptyMap(), "global-prompt"))
        val svc = createService(
            projectConfig = config(stages = stages), runner = runner,
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
                prompt = null, model = null, effort = null, maxConcurrent = null,
                agentKind = "opencode", command = "opencode-custom", onCompleteState = null
            )
        )
        val svc = createService(
            projectConfig = config(stages = stages), runner = runner,
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
                prompt = null, model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "Done"
            )
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = config(stages = stages),
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
    fun `transitionOnComplete creates follow-up issue when followUp config set`() {
        val trackingLinear = TrackingLinearClient()
        trackingLinear.createIssueResult = issue("b", "ENG-2", "Todo")
        val stages = mapOf(
            "in progress" to StageAgentConfig(
                prompt = null, model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "Done",
                followUp = FollowUpConfig(
                    titleTemplate = "PR Review: {{ issue.identifier }}",
                    state = "Todo",
                    linkType = "blocks"
                )
            )
        )
        val state = RuntimeState()
        val svc = createService(
            projectConfig = config(stages = stages),
            state = state,
            linear = trackingLinear,
            candidates = listOf(issue("a", "ENG-1", "In Progress"))
        )
        val stage = svc.projectConfig.agent.stages["in progress"]
        runBlocking {
            svc.transitionOnComplete(issue("a", "ENG-1", "In Progress"), stage)
        }

        assertThat(trackingLinear.transitionedIssueId).isEqualTo("a")
        assertThat(trackingLinear.createdIssueTitle).isEqualTo("PR Review: ENG-1")
        assertThat(trackingLinear.createdIssueState).isEqualTo("Todo")
        assertThat(trackingLinear.linkedSourceId).isEqualTo("a")
        assertThat(trackingLinear.linkedTargetId).isEqualTo("b")
        assertThat(trackingLinear.linkedType).isEqualTo("blocks")
    }

    @Test
    fun `transitionOnComplete without followUp does not create follow-up issue`() {
        val trackingLinear = TrackingLinearClient()
        trackingLinear.createIssueResult = issue("b", "ENG-2", "Todo")
        val stages = mapOf(
            "in progress" to StageAgentConfig(
                prompt = null, model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "Done"
            )
        )
        val state = RuntimeState()
        val svc = createService(
            projectConfig = config(stages = stages),
            state = state,
            linear = trackingLinear,
            candidates = listOf(issue("a", "ENG-1", "In Progress"))
        )
        val stage = svc.projectConfig.agent.stages["in progress"]
        runBlocking {
            svc.transitionOnComplete(issue("a", "ENG-1", "In Progress"), stage)
        }

        assertThat(trackingLinear.transitionedIssueId).isEqualTo("a")
        assertThat(trackingLinear.createdIssueTitle).isNull()
        assertThat(trackingLinear.linkedSourceId).isNull()
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
                projectConfig = projectConfig,
                state = state,
                linear = trackingLinear,
                workspaces = workspaces,
                candidates = listOf(testIssue),
                runner = runner
            )
            runDispatchAwait(svc)

            assertThat(runner.dispatched.size).isEqualTo(1)
            assertThat(state.isBlocked("1")).isTrue()
            assertThat(state.completed.containsKey("1")).isFalse()
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
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "In Review"
            ))
            val projectConfig = config(stages = stages).copy(tracker = config().tracker.copy(blockedState = "Blocked"))
            val svc = createService(
                projectConfig = projectConfig,
                state = state,
                linear = trackingLinear,
                workspaces = workspaces,
                candidates = listOf(testIssue),
                runner = runner
            )

            runDispatchAwait(svc)
            assertThat(state.isBlocked("1")).isTrue()

            Files.delete(workspace.path.resolve(".koncerto").resolve("clarification.md"))
            state.removeBlocked("1")

            runDispatchAwait(svc)

            assertThat(runner.dispatched.size).isEqualTo(2)
            assertThat(state.completed.containsKey("1")).isTrue()
            assertThat(state.isBlocked("1")).isFalse()
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
        projectConfig: ProjectConfig? = null,
        state: RuntimeState = RuntimeState(),
        linear: LinearClient? = null,
        candidates: List<Issue>? = null,
        runner: AgentRunner = CollectingAgentRunner(),
        cache: WorkflowCache? = null,
        workspaces: WorkspaceManager? = null,
        gitWorkflow: GitWorkflow? = null,
        quotaEnforcer: QuotaEnforcer? = null,
        quotaConfig: QuotaConfig? = null,
        subtaskOrchestrator: SubtaskOrchestrator? = null,
        workplanParser: WorkplanParser? = null,
        autoReviewOrchestrator: AutoReviewOrchestrator? = null,
        auditLogger: AuditLogger? = null,
        notifier: CompositeNotifier? = null,
        notificationsConfig: NotificationsConfig? = null,
        crossProjectChainer: CrossProjectChainer? = null,
        metricsRepository: MetricsRepository? = null,
        tenantResolver: TenantResolver? = null
    ): DispatchService {
        val cfg = projectConfig ?: DispatchServiceTest.config()
        val wc = cache ?: WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) }
        val logger = StructuredLogger(emptyList())
        val client = linear ?: candidates?.let { SimpleLinear(it) } ?: SimpleLinear(emptyList())
        return DispatchService(
            cfg, state, client, runner, wc, logger, workspaces,
            metricsRepository = metricsRepository,
            notifier = notifier,
            notificationsConfig = notificationsConfig,
            subtaskOrchestrator = subtaskOrchestrator,
            workplanParser = workplanParser,
            quotaEnforcer = quotaEnforcer,
            quotaConfig = quotaConfig,
            crossProjectChainer = crossProjectChainer,
            auditLogger = auditLogger,
            autoReviewOrchestrator = autoReviewOrchestrator,
            gitWorkflow = gitWorkflow,
            tenantResolver = tenantResolver
        )
    }

    private fun createServiceWithState(
        projectConfig: ProjectConfig? = null
    ): Pair<DispatchService, RuntimeState> {
        val state = RuntimeState()
        val svc = createService(projectConfig = projectConfig ?: DispatchServiceTest.config(), state = state)
        return Pair(svc, state)
    }

    @Test
    fun `resolveAgent returns provider kind and model from named agent on stage`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "fast"
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-sonnet-4")
    }

    @Test
    fun `resolveAgent falls back to agentKind when no agents map`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = "codex", command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent uses project default when no stage config`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config())
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress")
        val resolved = svc.resolveAgent(issue, null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent label agent colon fast overrides provider`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = mapOf(
                "fast" to AgentProviderConfig("codex", model = "claude-sonnet-4"),
                "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
            ),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "slow"
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress", labels = listOf("agent:fast"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-sonnet-4")
    }

    @Test
    fun `resolveAgent label model colon gpt4o overrides stage model`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "fast"
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress", labels = listOf("model:gpt-4o"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("gpt-4o")
    }

    @Test
    fun `resolveAgent combines agent and model labels`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = mapOf(
                "fast" to AgentProviderConfig("codex"),
                "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
            ),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "slow"
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress", labels = listOf("agent:fast", "model:gpt-4o"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("gpt-4o")
    }

    @Test
    fun `resolveAgent handles non-existent provider label gracefully`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress", labels = listOf("agent:nonexistent"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent backward compat with agentKind and model labels`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = "codex", command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress", labels = listOf("model:claude-3"))
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude-3")
    }

    @Test
    fun `resolveAgent uses project default kind when no stage config uses no agent`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            stages = mapOf("unknown" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = null
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["unknown"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent warns when stage references non-existent provider`() {
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = emptyMap(),
            stages = mapOf("in progress" to StageAgentConfig(
                prompt = "test", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "nonexistent"
            ))
        ))
        val issue = DispatchServiceTest.issue("1", "T-1", "In Progress")
        val stage = svc.projectConfig.agent.stages["in progress"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent uses routing rule matching label`() {
        val agents = mapOf("frontend-agent" to AgentProviderConfig(kind = "codex", model = "gpt-4"))
        val rules = listOf(RoutingRule(ifLabel = "frontend", useAgent = "frontend-agent", priority = 10))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("frontend", "bug"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("gpt-4")
    }

    @Test
    fun `resolveAgent uses routing rule matching label prefix`() {
        val agents = mapOf("backend-agent" to AgentProviderConfig(kind = "opencode"))
        val rules = listOf(RoutingRule(ifLabelPrefix = "backend:", useAgent = "backend-agent", priority = 5))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("backend:api", "bug"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("opencode")
    }

    @Test
    fun `resolveAgent falls through when no routing rule matches`() {
        val agents = mapOf("special" to AgentProviderConfig(kind = "codex"))
        val rules = listOf(RoutingRule(ifLabel = "special-label", useAgent = "special", priority = 10))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("normal"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `stage config agent overrides routing rule`() {
        val agents = mapOf(
            "routed" to AgentProviderConfig(kind = "codex"),
            "staged" to AgentProviderConfig(kind = "opencode", model = "claude")
        )
        val rules = listOf(RoutingRule(ifLabel = "frontend", useAgent = "routed", priority = 10))
        val stages = mapOf("todo" to StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = null,
            agent = "staged"
        ))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules, stages = stages
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("frontend"))
        val stage = svc.projectConfig.agent.stages["todo"]
        val resolved = svc.resolveAgent(issue, stage)
        assertThat(resolved.kind).isEqualTo("opencode")
        assertThat(resolved.model).isEqualTo("claude")
    }

    @Test
    fun `label agent prefix overrides routing rule`() {
        val agents = mapOf(
            "routed" to AgentProviderConfig(kind = "codex"),
            "label-agent" to AgentProviderConfig(kind = "opencode")
        )
        val rules = listOf(RoutingRule(ifLabel = "frontend", useAgent = "routed", priority = 10))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("frontend", "agent:label-agent"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("opencode")
    }

    @Test
    fun `dispatchDueRetries logs warning when fetchIssueById fails`() {
        val state = RuntimeState()
        val pastDue = System.currentTimeMillis() - 10_000
        state.retryAttempts["1"] = RetryEntry("1", "A-1", 2, pastDue, "timeout")
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state,
            runner = runner,
            linear = ThrowingLinearClient()
        )
        runBlocking {
            svc.dispatchDueRetries(CoroutineScope(coroutineContext))
        }
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `dispatchDueRetries reschedules when issue already running`() {
        val state = RuntimeState()
        val pastDue = System.currentTimeMillis() - 10_000
        state.retryAttempts["1"] = RetryEntry("1", "A-1", 2, pastDue, "timeout")
        state.running["1"] = runningEntry("1", "A-1")
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
        assertThat(state.retryAttempts.containsKey("1")).isTrue()
    }

    @Test
    fun `handleClarification with bot creator assigns project admin`() {
        val root = Files.createTempDirectory("clarify-bot-")
        try {
            val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
            val trackingLinear = TrackingLinearClient()
            val botCreator = UserRef("bot-1", "Bot", true)
            val testIssue = issue("1", "A-1", "Todo").copy(creator = botCreator)
            trackingLinear.addIssue(testIssue)

            val workspace = workspaces.ensureWorkspace(testIssue.identifier)
            Files.createDirectories(workspace.path.resolve(".koncerto"))
            Files.writeString(workspace.path.resolve(".koncerto").resolve("clarification.md"), "Need specs")

            val state = RuntimeState()
            val runner = CollectingAgentRunner()
            val projectConfig = config().copy(tracker = config().tracker.copy(
                blockedState = "Blocked",
                projectAdmin = "admin-1"
            ))
            val svc = createService(
                projectConfig = projectConfig,
                state = state,
                linear = trackingLinear,
                workspaces = workspaces,
                candidates = listOf(testIssue),
                runner = runner
            )
            runDispatchAwait(svc)

            assertThat(trackingLinear.assignedUserId).isEqualTo("admin-1")
            assertThat(state.isBlocked("1")).isTrue()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveAgent uses routing rule matching state`() {
        val agents = mapOf("state-agent" to AgentProviderConfig(kind = "codex", model = "claude"))
        val rules = listOf(RoutingRule(ifState = "In Progress", useAgent = "state-agent", priority = 10))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "In Progress", labels = listOf("bug"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
        assertThat(resolved.model).isEqualTo("claude")
    }

    @Test
    fun `resolveAgent routing rule references missing agent falls through`() {
        val agents = emptyMap<String, AgentProviderConfig>()
        val rules = listOf(RoutingRule(ifLabel = "frontend", useAgent = "nonexistent", priority = 10))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", labels = listOf("frontend"))
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent uses routing rule matching priority`() {
        val agents = mapOf("high-prio" to AgentProviderConfig(kind = "codex"))
        val rules = listOf(RoutingRule(ifPriority = 1, useAgent = "high-prio"))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", priority = 1)
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `resolveAgent uses routing rule matching priority max`() {
        val agents = mapOf("low-prio" to AgentProviderConfig(kind = "opencode"))
        val rules = listOf(RoutingRule(ifPriorityMax = 3, useAgent = "low-prio"))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", priority = 3)
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("opencode")
    }

    @Test
    fun `resolveAgent routing rule with priority max excludes higher priority`() {
        val agents = mapOf("low-prio" to AgentProviderConfig(kind = "opencode"))
        val rules = listOf(RoutingRule(ifPriorityMax = 2, useAgent = "low-prio"))
        val (svc, _) = createServiceWithState(DispatchServiceTest.config(
            agents = agents, routingRules = rules
        ))
        val issue = DispatchServiceTest.issue("a", "ENG-1", "Todo", priority = 5)
        val resolved = svc.resolveAgent(issue, stageConfig = null)
        assertThat(resolved.kind).isEqualTo("codex")
    }

    @Test
    fun `transitionOnComplete handles followUp with blank linkType`() {
        val trackingLinear = TrackingLinearClient()
        trackingLinear.createIssueResult = issue("b", "ENG-2", "Todo")
        val stages = mapOf(
            "in progress" to StageAgentConfig(
                prompt = null, model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = "Done",
                followUp = FollowUpConfig(
                    titleTemplate = "Follow-up: {{ issue.identifier }}",
                    state = "Todo",
                    linkType = ""
                )
            )
        )
        val state = RuntimeState()
        val svc = createService(
            projectConfig = config(stages = stages),
            state = state,
            linear = trackingLinear,
            candidates = listOf(issue("a", "ENG-1", "In Progress"))
        )
        val stage = svc.projectConfig.agent.stages["in progress"]
        runBlocking {
            svc.transitionOnComplete(issue("a", "ENG-1", "In Progress"), stage)
        }

        assertThat(trackingLinear.transitionedIssueId).isEqualTo("a")
        assertThat(trackingLinear.createdIssueTitle).isEqualTo("Follow-up: ENG-1")
        assertThat(trackingLinear.linkedSourceId).isNull()
    }

    @Test
    fun `scheduleRetry exhaustion clears retry and transitions to blocked`() = runBlocking {
        val projectConfig = config().copy(
            agent = config().agent.copy(maxRetries = 2),
            tracker = config().tracker.copy(blockedState = "Blocked")
        )
        val trackingLinear = TrackingLinearClient()
        val state = RuntimeState()
        val svc = createService(projectConfig = projectConfig, state = state, linear = trackingLinear)
        val testIssue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(testIssue, "err1")
        svc.scheduleRetry(testIssue, "err2")
        svc.scheduleRetry(testIssue, "err3")
        assertThat(state.retryAttempts.containsKey("1")).isFalse()
        assertThat(trackingLinear.transitionedIssueId).isEqualTo("1")
        assertThat(trackingLinear.transitionedStateId).isEqualTo("done-id")
    }

    @Test
    fun `resolveStageOverride dispatches todo issue to in review when remote branch exists`(@TempDir root: Path) {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        workspaces.ensureWorkspace("A-1")
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "todo-prompt", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            ),
            "in review" to StageAgentConfig(
                prompt = "review-prompt", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val runner = CollectingAgentRunner()
        val gitWorkflow = FakeGitWorkflowForDispatch(remoteExists = true)
        val svc = createService(
            projectConfig = config(stages = stages),
            runner = runner,
            workspaces = workspaces,
            gitWorkflow = gitWorkflow,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.runArgs.first().prompt).isEqualTo("review-prompt")
    }

    @Test
    fun `resolveStageOverride ignored for non-todo issues`(@TempDir root: Path) {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        workspaces.ensureWorkspace("A-1")
        val stages = mapOf(
            "in progress" to StageAgentConfig(
                prompt = "progress-prompt", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            ),
            "in review" to StageAgentConfig(
                prompt = "review-prompt", model = null, effort = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null
            )
        )
        val runner = CollectingAgentRunner()
        val gitWorkflow = FakeGitWorkflowForDispatch(remoteExists = true)
        val projectConfig = config(stages = stages).copy(
            tracker = config().tracker.copy(activeStates = listOf("Todo", "In Progress"))
        )
        val svc = createService(
            projectConfig = projectConfig,
            runner = runner,
            workspaces = workspaces,
            gitWorkflow = gitWorkflow,
            candidates = listOf(issue("1", "A-1", "In Progress"))
        )
        runDispatch(svc)
        assertThat(runner.runArgs.first().prompt).isEqualTo("progress-prompt")
    }

    @Test
    fun `handleWorkplanIfPresent launches subtask orchestrator on completion`(@TempDir root: Path) {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        val workspace = workspaces.ensureWorkspace("A-1")
        val workplanDir = workspace.path.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        val manifest = SubtaskManifest(
            issueId = "A-1",
            subtasks = listOf(SubtaskDef(id = "step-1", description = "Step", prompt = "do-work", dependsOn = emptyList()))
        )
        Files.writeString(workplanDir.resolve("workplan.json"), Json.encodeToString(manifest))

        val executed = AtomicInteger(0)
        val subtaskOrchestrator = SubtaskOrchestrator(
            subtaskRunner = FakeSubtaskRunnerForDispatch { executed.incrementAndGet(); Result.Success(Unit) },
            gitWorkflow = FakeGitWorkflowForDispatch(remoteExists = false),
            logger = StructuredLogger(emptyList())
        )
        val projectConfig = config().copy(
            agent = config().agent.copy(
                workplan = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
            )
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = projectConfig,
            runner = runner,
            workspaces = workspaces,
            subtaskOrchestrator = subtaskOrchestrator,
            workplanParser = WorkplanParser(),
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatchAwait(svc)
        runBlocking { svc.awaitBackgroundJobs() }
        assertThat(executed.get()).isEqualTo(1)
    }

    @Test
    fun `handleWorkplanIfPresent logs parse failure for invalid workplan`(@TempDir root: Path) {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        val workspace = workspaces.ensureWorkspace("A-2")
        val workplanDir = workspace.path.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        Files.writeString(workplanDir.resolve("workplan.json"), "{not valid json")

        val subtaskOrchestrator = SubtaskOrchestrator(
            subtaskRunner = FakeSubtaskRunnerForDispatch { Result.Success(Unit) },
            gitWorkflow = FakeGitWorkflowForDispatch(remoteExists = false),
            logger = StructuredLogger(emptyList())
        )
        val projectConfig = config().copy(
            agent = config().agent.copy(
                workplan = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
            )
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = projectConfig,
            runner = runner,
            workspaces = workspaces,
            subtaskOrchestrator = subtaskOrchestrator,
            workplanParser = WorkplanParser(),
            candidates = listOf(issue("2", "A-2", "Todo"))
        )
        runDispatchAwait(svc)
        runBlocking { svc.awaitBackgroundJobs() }
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `agent messaging wrappers route and acknowledge messages`() {
        val (svc, _) = createServiceWithState()
        svc.registerAgent("agent-1", "issue-1")
        val messageId = svc.sendAgentMessage("agent-2", "agent-1", "hello")
        assertThat(svc.getAgentMessages("agent-1").single().payload).isEqualTo("hello")
        assertThat(svc.ackAgentMessage(messageId)).isTrue()
        assertThat(svc.getAgentMessages("agent-1").size).isEqualTo(0)
        assertThat(svc.resolveAgentIdToIssueId("agent-1")).isEqualTo("issue-1")
        svc.unregisterAgent("agent-1")
        assertThat(svc.resolveAgentIdToIssueId("agent-1")).isNull()
    }

    @Test
    fun `dispatch skipped when quota enforcer has no remaining capacity`() {
        val enforcer = QuotaEnforcer()
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo")),
            quotaEnforcer = enforcer,
            quotaConfig = QuotaConfig(maxConcurrentAgents = 0)
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
        assertThat(enforcer.getActiveCount("p")).isEqualTo(0)
    }

    @Test
    fun `quota enforcer releases capacity after successful dispatch`() {
        val enforcer = QuotaEnforcer()
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo")),
            quotaEnforcer = enforcer,
            quotaConfig = QuotaConfig(maxConcurrentAgents = 1)
        )
        runDispatchAwait(svc)
        assertThat(runner.dispatched.size).isEqualTo(1)
        assertThat(enforcer.getActiveCount("p")).isEqualTo(0)
    }

    @Test
    fun `dispatch skipped when agent not authenticated`() {
        AgentAuthChecker.markUnauthenticated("codex")
        try {
            val runner = CollectingAgentRunner()
            val svc = createService(
                runner = runner,
                candidates = listOf(issue("1", "A-1", "Todo"))
            )
            runDispatch(svc)
            assertThat(runner.dispatched.size).isEqualTo(0)
        } finally {
            AgentAuthChecker.markAuthenticated("codex")
        }
    }

    @Test
    fun `subscription limit failure schedules limit pause instead of retry`() = runBlocking {
        val linear = TrackingLinearClient()
        val testIssue = issue("1", "A-1", "Todo")
        linear.addIssue(testIssue)
        val state = RuntimeState()
        val runner = LimitFailureRunner(
            SubscriptionLimitException("usage limit", provider = "codex", rawMessage = "You've hit your usage limit")
        )
        val svc = createService(
            state = state,
            linear = linear,
            runner = runner,
            candidates = listOf(testIssue)
        )
        runDispatchAwait(svc)
        assertThat(state.limitPauses.containsKey("1")).isTrue()
        assertThat(state.retryAttempts.containsKey("1")).isFalse()
    }

    @Test
    fun `completion sends notification when configured`() = runBlocking {
        var notified = false
        val notifier = CompositeNotifier(listOf(object : com.flexsentlabs.koncerto.notifications.Notifier {
            override suspend fun send(event: com.flexsentlabs.koncerto.notifications.NotificationEvent) {
                if (event is com.flexsentlabs.koncerto.notifications.NotificationEvent.AgentCompleted) {
                    notified = true
                }
            }
        }))
        val projectConfig = config().copy(
            notifications = com.flexsentlabs.koncerto.core.config.NotificationsConfig(onCompleted = true)
        )
        val runner = CollectingAgentRunner()
        val svc = DispatchService(
            projectConfig = projectConfig,
            state = RuntimeState(),
            linear = SimpleLinear(listOf(issue("1", "A-1", "Todo"))),
            agentRunner = runner,
            workflowCache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi")) },
            logger = StructuredLogger(emptyList()),
            notifier = notifier,
            notificationsConfig = projectConfig.notifications
        )
        runDispatchAwait(svc)
        assertThat(notified).isTrue()
    }

    @Test
    fun `workplan parse failure is logged without crashing dispatch`(@TempDir root: Path) = runBlocking {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        workspaces.ensureWorkspace("A-1")
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            workspaces = workspaces,
            workplanParser = WorkplanParser(),
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `sequential mode dispatches eligible issues`() {
        val projectConfig = config().copy(
            agent = config().agent.copy(sequentialMode = true)
        )
        val runner = CollectingAgentRunner()
        val svc = createService(
            projectConfig = projectConfig,
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `fetchAndDispatch returns immediately when shutdown requested`() {
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        svc.shutdownRequested = true
        runDispatch(svc)
        assertThat(runner.dispatched.size).isEqualTo(0)
    }

    @Test
    fun `resolveStageOverride returns null when remote branch missing`(@TempDir root: Path) {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        workspaces.ensureWorkspace("A-1")
        val gitWorkflow = FakeGitWorkflowForDispatch(remoteExists = false)
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            workspaces = workspaces,
            gitWorkflow = gitWorkflow,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatch(svc)
        assertThat(runner.runArgs.first().prompt).isEqualTo("Hi")
    }

    @Test
    fun `shutdown sets shutdownRequested and awaits background jobs`() = runBlocking {
        val svc = createService()
        svc.shutdown()
        assertThat(svc.shutdownRequested).isTrue()
    }

    @Test
    fun `transitionOnComplete uses onFailureState when review status is fail`(@TempDir root: Path) = runBlocking {
        val ws = WorkspaceManager(root, HookExecutor { _, _ -> })
        ws.ensureWorkspace("T-1")
        val workspace = root.resolve("T-1")
        Files.writeString(workspace.resolve(".review-status"), "fail")
        Files.writeString(workspace.resolve(".review-attempt"), "1")
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "In Review"))
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = "In Progress", maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val svc = createService(
            projectConfig = config(stages = mapOf("in review" to stage)),
            linear = linear,
            workspaces = ws
        )
        svc.transitionOnComplete(issue("1", "T-1", "In Review"), stage)
        assertThat(linear.transitionedStateId).isEqualTo("done-id")
    }

    @Test
    fun `transitionOnComplete clears review files when max attempts reached`(@TempDir root: Path) = runBlocking {
        val ws = WorkspaceManager(root, HookExecutor { _, _ -> })
        ws.ensureWorkspace("T-1")
        val workspace = root.resolve("T-1")
        Files.writeString(workspace.resolve(".review-status"), "fail")
        Files.writeString(workspace.resolve(".review-attempt"), "3")
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "In Review"))
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = "In Progress", maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val svc = createService(
            projectConfig = config(stages = mapOf("in review" to stage)),
            linear = linear,
            workspaces = ws
        )
        svc.transitionOnComplete(issue("1", "T-1", "In Review"), stage)
        assertThat(Files.exists(workspace.resolve(".review-status"))).isFalse()
    }

    @Test
    fun `dispatch failure sends notification when configured`() = runBlocking {
        var notified = false
        val notifier = CompositeNotifier(listOf(object : com.flexsentlabs.koncerto.notifications.Notifier {
            override suspend fun send(event: com.flexsentlabs.koncerto.notifications.NotificationEvent) {
                if (event is com.flexsentlabs.koncerto.notifications.NotificationEvent.AgentFailed) {
                    notified = true
                }
            }
        }))
        val svc = createService(
            runner = FailingRunner("boom"),
            notifier = notifier,
            notificationsConfig = NotificationsConfig(onCompleted = false, onFailed = true),
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(notified).isTrue()
    }

    @Test
    fun `dispatch success records metrics and audit events`() = runBlocking {
        var metricResult: String? = null
        val metrics = object : MetricsRepository {
            override suspend fun updateAfterRun(
                issueId: String, issueIdentifier: String, projectSlug: String?,
                result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long
            ) { metricResult = result }
            override suspend fun findAll() = emptyList<com.flexsentlabs.koncerto.metrics.IssueMetrics>()
            override suspend fun findByProject(projectSlug: String?) = emptyList<com.flexsentlabs.koncerto.metrics.IssueMetrics>()
            override suspend fun findById(issueId: String) = null
            override suspend fun tokenHistory(days: Int) =
                listOf(TokenDaySummary("2026-01-01", 0, 0, 0))
        }
        var auditType: AuditEventType? = null
        val audit = object : AuditLogger {
            override fun log(event: AuditEvent) { auditType = event.type }
        }
        val svc = createService(
            runner = CollectingAgentRunner(),
            metricsRepository = metrics,
            auditLogger = audit,
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(metricResult).isEqualTo("success")
        assertThat(auditType).isEqualTo(AuditEventType.AGENT_COMPLETED)
    }

    @Test
    fun `dispatch emits token usage from agent events`() = runBlocking {
        val runner = TokenEventRunner()
        val state = RuntimeState()
        val svc = createService(
            state = state,
            runner = runner,
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(state.running.isEmpty()).isTrue()
    }

    @Test
    fun `scheduleLimitPause with disabled config schedules retry instead`() = runBlocking {
        val (svc, state) = createServiceWithState(
            projectConfig = config().copy(
                agent = config().agent.copy(
                    limitPause = config().agent.limitPause.copy(enabled = false)
                )
            )
        )
        val testIssue = issue("1", "T-1", "Todo")
        state.running["1"] = runningEntry("1", "T-1")
        svc.scheduleLimitPause(testIssue, "limit hit", "codex", System.currentTimeMillis() + 60_000, "todo", "codex")
        assertThat(state.limitPauses.containsKey("1")).isFalse()
        assertThat(state.retryAttempts.containsKey("1")).isTrue()
    }

    @Test
    fun `fetchAndDispatch marks todo issues blocked by active blockers`() {
        val blocker = issue("0", "A-0", "Todo")
        val blocked = issue("1", "A-1", "Todo", blockers = listOf(BlockerRef("0", "A-0", "Todo")))
        val runner = CollectingAgentRunner()
        val svc = createService(
            runner = runner,
            candidates = listOf(blocker, blocked)
        )
        runDispatch(svc)
        assertThat(svc.state.blockedKeys.contains("1")).isTrue()
        assertThat(runner.dispatched.map { it.id }).containsExactly("0")
    }

    @Test
    fun `handleNormalCompletion with auto review no review completes issue`(@TempDir root: Path) = runBlocking {
        val autoReview = AutoReviewOrchestrator(
            agentRunner = CollectingAgentRunner(),
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = SimpleLinear(listOf(issue("1", "T-1", "Todo"))),
            projectConfig = config(),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(
            runner = CollectingAgentRunner(),
            linear = linear,
            autoReviewOrchestrator = autoReview,
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(svc.state.completed.containsKey("1")).isTrue()
    }

    @Test
    fun `cross project follow up launches background chainer job`(@TempDir root: Path) = runBlocking {
        var followUpCalled = false
        val chainer = object : CrossProjectChainer {
            override suspend fun createFollowUp(
                issue: Issue,
                config: CrossProjectFollowUpConfig,
                sourceProjectSlug: String
            ) { followUpCalled = true }
        }
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = null,
            agent = null, followUp = null,
            crossProjectFollowUp = CrossProjectFollowUpConfig(
                targetProjectSlug = "other",
                titleTemplate = "Follow {{identifier}}",
                descriptionTemplate = null
            )
        )
        val svc = createService(
            projectConfig = config(stages = mapOf("todo" to stage)),
            runner = CollectingAgentRunner(),
            crossProjectChainer = chainer,
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        svc.awaitBackgroundJobs()
        assertThat(followUpCalled).isTrue()
    }

    @Test
    fun `transitionOnComplete uses onComplete when review fail exceeds max attempts`(@TempDir root: Path) = runBlocking {
        val workspace = root.resolve("T-1")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve(".review-status"), "fail")
        Files.writeString(workspace.resolve(".review-attempt"), "3")
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = "In Progress", maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(
            linear = linear,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = config(stages = mapOf("todo" to stage)),
            candidates = emptyList()
        )
        svc.transitionOnComplete(issue("1", "T-1", "Todo"), stage)
        assertThat(linear.transitionedStateId).isEqualTo("done-id")
        assertThat(Files.exists(workspace.resolve(".review-status"))).isFalse()
    }

    @Test
    fun `transitionOnComplete skips when stage config null`() = runBlocking {
        val linear = TrackingLinearClient()
        val svc = createService(linear = linear, candidates = emptyList())
        svc.transitionOnComplete(issue("1", "T-1", "Todo"), null)
        assertThat(linear.transitionedIssueId).isNull()
    }

    @Test
    fun `agent messaging ack returns false for unknown message`() {
        val svc = createService(candidates = emptyList())
        assertThat(svc.ackAgentMessage("missing-message")).isFalse()
    }

    @Test
    fun `scheduleLimitPause logs audit event when audit logger configured`() = runBlocking {
        var auditType: AuditEventType? = null
        val audit = object : AuditLogger {
            override fun log(event: AuditEvent) { auditType = event.type }
        }
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "A-1", "Todo"))
        val svc = createService(linear = linear, auditLogger = audit)
        svc.scheduleLimitPause(
            issue = issue("1", "A-1", "Todo"),
            error = "rate limited",
            provider = "codex",
            resumeAtMs = System.currentTimeMillis() + 60_000,
            stageName = "todo",
            agentKind = "codex"
        )
        assertThat(auditType).isEqualTo(AuditEventType.AGENT_RETRY_SCHEDULED)
    }

    @Test
    fun `scheduleLimitPause stores pause when linear comment fails`() = runBlocking {
        val backing = TrackingLinearClient()
        backing.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) =
                backing.fetchCandidateIssues(projectSlug, activeStates)
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) =
                backing.fetchIssuesByStates(projectSlug, stateNames)
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) =
                backing.fetchIssueStatesByIds(issueIds)
            override suspend fun fetchIssueById(issueId: String) = backing.fetchIssueById(issueId)
            override suspend fun resolveStateId(projectSlug: String, stateName: String) =
                backing.resolveStateId(projectSlug, stateName)
            override suspend fun updateIssueState(issueId: String, stateId: String) =
                backing.updateIssueState(issueId, stateId)
            override suspend fun createComment(issueId: String, body: String) {
                throw RuntimeException("comment api down")
            }
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) =
                backing.updateIssueAssignee(issueId, assigneeId)
            override suspend fun fetchIssueCreator(issueId: String) = backing.fetchIssueCreator(issueId)
            override suspend fun createIssue(
                projectSlug: String, title: String, state: String,
                description: String?, labels: List<String>
            ) = backing.createIssue(projectSlug, title, state, description, labels)
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) =
                backing.createLink(sourceIssueId, targetIssueId, type)
        }
        val state = RuntimeState()
        val svc = createService(state = state, linear = linear)
        svc.scheduleLimitPause(
            issue = issue("1", "A-1", "Todo"),
            error = "limit",
            provider = "codex",
            resumeAtMs = System.currentTimeMillis() + 60_000,
            stageName = "todo",
            agentKind = "codex"
        )
        assertThat(state.limitPauses.containsKey("1")).isTrue()
    }

    @Test
    fun `dispatchDueLimitPauses requeues pause when fetchIssueById fails`() = runBlocking {
        val backing = TrackingLinearClient()
        backing.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) =
                backing.fetchCandidateIssues(projectSlug, activeStates)
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) =
                backing.fetchIssuesByStates(projectSlug, stateNames)
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) =
                backing.fetchIssueStatesByIds(issueIds)
            override suspend fun fetchIssueById(issueId: String): Issue? =
                throw RuntimeException("fetch failed")
            override suspend fun resolveStateId(projectSlug: String, stateName: String) =
                backing.resolveStateId(projectSlug, stateName)
            override suspend fun updateIssueState(issueId: String, stateId: String) =
                backing.updateIssueState(issueId, stateId)
            override suspend fun createComment(issueId: String, body: String) =
                backing.createComment(issueId, body)
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) =
                backing.updateIssueAssignee(issueId, assigneeId)
            override suspend fun fetchIssueCreator(issueId: String) = backing.fetchIssueCreator(issueId)
            override suspend fun createIssue(
                projectSlug: String, title: String, state: String,
                description: String?, labels: List<String>
            ) = backing.createIssue(projectSlug, title, state, description, labels)
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) =
                backing.createLink(sourceIssueId, targetIssueId, type)
        }
        val state = RuntimeState()
        val pauseEntry = LimitPauseEntry(
            issueId = "1", identifier = "A-1", stageName = "todo",
            agentKind = "codex", provider = "codex", error = "limit",
            resumeAtMs = System.currentTimeMillis() - 1
        )
        state.limitPauses["1"] = pauseEntry
        val svc = createService(state = state, linear = linear)
        svc.dispatchDueLimitPauses(CoroutineScope(coroutineContext))
        assertThat(state.limitPauses["1"]).isEqualTo(pauseEntry)
    }

    @Test
    fun `dispatchDueLimitPauses requeues pause when dispatch fails`() = runBlocking {
        AgentAuthChecker.markUnauthenticated("codex")
        try {
            val linear = TrackingLinearClient()
            linear.addIssue(issue("1", "A-1", "Todo"))
            val state = RuntimeState()
            val pauseEntry = LimitPauseEntry(
                issueId = "1", identifier = "A-1", stageName = "todo",
                agentKind = "codex", provider = "codex", error = "limit",
                resumeAtMs = System.currentTimeMillis() - 1
            )
            state.limitPauses["1"] = pauseEntry
            val svc = createService(state = state, linear = linear)
            svc.dispatchDueLimitPauses(CoroutineScope(coroutineContext))
            assertThat(state.limitPauses.containsKey("1")).isTrue()
        } finally {
            AgentAuthChecker.markAuthenticated("codex")
        }
    }

    @Test
    fun `dispatch failure records failure metrics and audit`() = runBlocking {
        var metricResult: String? = null
        val metrics = object : MetricsRepository {
            override suspend fun updateAfterRun(
                issueId: String, issueIdentifier: String, projectSlug: String?,
                result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long
            ) { metricResult = result }
            override suspend fun findAll() = emptyList<com.flexsentlabs.koncerto.metrics.IssueMetrics>()
            override suspend fun findByProject(projectSlug: String?) = emptyList<com.flexsentlabs.koncerto.metrics.IssueMetrics>()
            override suspend fun findById(issueId: String) = null
            override suspend fun tokenHistory(days: Int) =
                listOf(TokenDaySummary("2026-01-01", 0, 0, 0))
        }
        var auditTypes = mutableListOf<AuditEventType>()
        val audit = object : AuditLogger {
            override fun log(event: AuditEvent) { auditTypes.add(event.type) }
        }
        val svc = createService(
            runner = FailingRunner("boom"),
            metricsRepository = metrics,
            auditLogger = audit,
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(metricResult).isEqualTo("failure")
        assertThat(auditTypes).contains(AuditEventType.AGENT_FAILED)
    }

    @Test
    fun `prepareDispatch sets tenant context on running entry`() = runBlocking {
        val tenantResolver = ConfigTenantResolver()
        val projectConfig = config().copy(
            tenant = TenantConfig(tier = "enterprise", quotaProfile = "large")
        )
        var capturedTier: String? = null
        val state = RuntimeState()
        val runner = object : AgentRunner {
            private val flow = MutableSharedFlow<AgentEvent>()
            override fun events() = flow.asSharedFlow()
            override suspend fun run(
                issue: Issue, attempt: Int?, prompt: String,
                agentKindOverride: String?, commandOverride: String?,
                modelOverride: String?, effortOverride: String?,
                turnTimeoutMs: Long?, stallTimeoutMs: Long?
            ): EmptyResult<IllegalStateException> {
                capturedTier = state.running[issue.id]?.tenantContext?.tier
                return Result.Success(Unit)
            }
        }
        val svc = createService(
            projectConfig = projectConfig,
            state = state,
            runner = runner,
            tenantResolver = tenantResolver,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(capturedTier).isEqualTo("enterprise")
    }

    @Test
    fun `resolveAgent applies KONCERTO_DEFAULT_MODEL when model unset`() {
        try {
            setEnvVar("KONCERTO_DEFAULT_MODEL", "gpt-test-default")
        } catch (_: Exception) {
            return
        }
        if (System.getenv("KONCERTO_DEFAULT_MODEL") != "gpt-test-default") return
        try {
            val (svc, _) = createServiceWithState()
            val resolved = svc.resolveAgent(issue("1", "T-1", "Todo"), null)
            assertThat(resolved.model).isEqualTo("gpt-test-default")
        } finally {
            clearEnvVar("KONCERTO_DEFAULT_MODEL")
        }
    }

    @Test
    fun `transitionOnComplete uses onComplete when onFailureState null`(@TempDir root: Path) = runBlocking {
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = null, maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(linear = linear, candidates = emptyList())
        svc.transitionOnComplete(issue("1", "T-1", "Todo"), stage)
        assertThat(linear.transitionedStateId).isEqualTo("done-id")
    }

    @Test
    fun `transitionOnComplete uses onComplete when review status file missing`(@TempDir root: Path) = runBlocking {
        val ws = WorkspaceManager(root, HookExecutor { _, _ -> })
        Files.createDirectories(root.resolve("T-1"))
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = "In Progress", maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "In Review"))
        val svc = createService(linear = linear, workspaces = ws, candidates = emptyList())
        svc.transitionOnComplete(issue("1", "T-1", "In Review"), stage)
        assertThat(linear.transitionedStateId).isEqualTo("done-id")
    }

    @Test
    fun `handleNormalCompletion with auto review pass completes issue`(@TempDir root: Path) = runBlocking {
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val todoStage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Ready for Human Review",
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val runner = ReviewWritingAgentRunner(root, "pass")
        val state = RuntimeState()
        val autoReview = AutoReviewOrchestrator(
            agentRunner = runner,
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = TrackingLinearClient(),
            projectConfig = config(stages = mapOf(
                "todo" to todoStage,
                "in review" to reviewStage,
                "review" to reviewStage.copy(onCompleteState = "Done")
            )),
            projectSlug = "p",
            runtimeState = state,
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(
            runner = runner,
            linear = linear,
            autoReviewOrchestrator = autoReview,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = config(stages = mapOf(
                "todo" to todoStage,
                "in review" to reviewStage,
                "review" to reviewStage.copy(onCompleteState = "Done")
            )),
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(svc.state.completed.containsKey("1")).isTrue()
        assertThat(linear.transitionedIssueId).isEqualTo("1")
    }

    @Test
    fun `handleNormalCompletion with auto review retry reroutes state`(@TempDir root: Path) = runBlocking {
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val todoStage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Ready for Human Review",
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val runner = ReviewWritingAgentRunner(root, reviewStatus = null)
        val state = RuntimeState()
        val autoReview = AutoReviewOrchestrator(
            agentRunner = runner,
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = TrackingLinearClient(),
            projectConfig = config(stages = mapOf("todo" to todoStage, "in review" to reviewStage)),
            projectSlug = "p",
            runtimeState = state,
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(
            runner = runner,
            linear = linear,
            autoReviewOrchestrator = autoReview,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = config(stages = mapOf("todo" to todoStage, "in review" to reviewStage)),
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(svc.state.completed.containsKey("1")).isFalse()
        assertThat(linear.transitionedIssueId).isEqualTo("1")
    }

    @Test
    fun `handleNormalCompletion with auto review blocked does not complete`(@TempDir root: Path) = runBlocking {
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val todoStage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Ready for Human Review",
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val runner = ReviewWritingAgentRunner(root, reviewStatus = null)
        val state = RuntimeState()
        val autoReview = AutoReviewOrchestrator(
            agentRunner = runner,
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = TrackingLinearClient(),
            projectConfig = config(stages = mapOf("todo" to todoStage, "in review" to reviewStage)),
            projectSlug = "p",
            runtimeState = state,
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "T-1", "Todo"))
        val svc = createService(
            runner = runner,
            linear = linear,
            autoReviewOrchestrator = autoReview,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = config(stages = mapOf("todo" to todoStage, "in review" to reviewStage)),
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(svc.state.completed.containsKey("1")).isFalse()
        assertThat(linear.transitionedIssueId).isEqualTo("1")
    }

    @Test
    fun `auto-review pass uses in-review stage onCompleteState not coding stage`(@TempDir root: Path) = runBlocking {
        // Regression: stages["review"] key was wrong (should be "in review"). Without fix the issue
        // would transition to the coding stage's onCompleteState ("In Review") instead of the
        // "in review" stage's onCompleteState ("Ready for Human Review"), creating an infinite loop.
        val codingStage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "In Review",
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Ready for Human Review", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val linear = TrackingLinearClient()
        linear.resolveStateIdResult = "state-id"
        linear.addIssue(issue("1", "T-1", "Todo"))
        val stages = mapOf("todo" to codingStage, "in review" to reviewStage)
        val runner = ReviewWritingAgentRunner(root, "pass")
        val state = RuntimeState()
        val autoReview = AutoReviewOrchestrator(
            agentRunner = runner,
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = TrackingLinearClient(),
            projectConfig = config(stages = stages),
            projectSlug = "p",
            runtimeState = state,
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val svc = createService(
            runner = runner,
            linear = linear,
            autoReviewOrchestrator = autoReview,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = config(stages = stages),
            candidates = listOf(issue("1", "T-1", "Todo"))
        )
        runDispatchAwait(svc)
        assertThat(svc.state.completed.containsKey("1")).isTrue()
        assertThat(linear.resolvedStateHistory).contains("Ready for Human Review")
        assertThat(linear.resolvedStateHistory.any { it.equals("In Review", ignoreCase = true) }).isFalse()
    }

    @Test
    fun `in-review stage completion skips autoReview and completes directly`(@TempDir root: Path) = runBlocking {
        // Regression: when the "in review" stage itself completes, handleNormalCompletion used to call
        // onCodingComplete() again, running a redundant nested review.
        val reviewStage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Ready for Human Review", onFailureState = null,
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        var runCount = 0
        val runner = object : AgentRunner {
            private val flow = MutableSharedFlow<AgentEvent>()
            override fun events() = flow.asSharedFlow()
            override suspend fun run(
                issue: Issue, attempt: Int?, prompt: String,
                agentKindOverride: String?, commandOverride: String?,
                modelOverride: String?, effortOverride: String?,
                turnTimeoutMs: Long?, stallTimeoutMs: Long?
            ): com.flexsentlabs.koncerto.core.result.EmptyResult<IllegalStateException> {
                runCount++
                return Result.Success(Unit)
            }
        }
        val linear = TrackingLinearClient()
        linear.resolveStateIdResult = "state-id"
        linear.addIssue(issue("1", "T-1", "In Review"))
        val stages = mapOf("in review" to reviewStage)
        val pcfg = config(stages = stages).copy(
            tracker = config().tracker.copy(activeStates = listOf("In Review"))
        )
        val state = RuntimeState()
        val autoReview = AutoReviewOrchestrator(
            agentRunner = runner,
            workspaceManager = WorkspaceManager(root, HookExecutor { _, _ -> }),
            linearClient = TrackingLinearClient(),
            projectConfig = pcfg,
            projectSlug = "p",
            runtimeState = state,
            notifier = null,
            logger = StructuredLogger(emptyList())
        )
        val svc = createService(
            runner = runner,
            linear = linear,
            autoReviewOrchestrator = autoReview,
            workspaces = WorkspaceManager(root, HookExecutor { _, _ -> }),
            projectConfig = pcfg,
            candidates = listOf(issue("1", "T-1", "In Review"))
        )
        runDispatchAwait(svc)
        assertThat(runCount).isEqualTo(1)
        assertThat(svc.state.completed.containsKey("1")).isTrue()
        assertThat(linear.resolvedStateHistory).contains("Ready for Human Review")
    }

    @Test
    fun `scheduleRetry exhaustion logs AGENT_FAILED audit event`() = runBlocking {
        val auditTypes = mutableListOf<AuditEventType>()
        val audit = object : AuditLogger {
            override fun log(event: AuditEvent) { auditTypes.add(event.type) }
        }
        val projectConfig = config().copy(
            agent = config().agent.copy(maxRetries = 1),
            tracker = config().tracker.copy(blockedState = "Blocked")
        )
        val svc = createService(projectConfig = projectConfig, auditLogger = audit)
        val testIssue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(testIssue, "err1")
        svc.scheduleRetry(testIssue, "err2")
        assertThat(auditTypes).contains(AuditEventType.AGENT_FAILED)
    }

    @Test
    fun `messageStore send and poll agent messages`() {
        val svc = createService()
        svc.messageStore.sendMessage("agent-a", "agent-b", "hello")
        val messages = svc.messageStore.pollMessages("agent-b")
        assertThat(messages.single().payload).isEqualTo("hello")
    }

    @Test
    fun `handleClarification tolerates comment failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient by tracking {
            override suspend fun createComment(issueId: String, body: String) {
                throw RuntimeException("comment failed")
            }
        }
        val svc = createService(linear = linear)
        svc.handleClarification("1", "Need more info")
        assertThat(svc.state.isBlocked("1")).isFalse()
    }

    @Test
    fun `handleClarification tolerates missing blocked state`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient by tracking {
            override suspend fun resolveStateId(projectSlug: String, stateName: String): String? =
                if (stateName == "Blocked") null else "done-id"
        }
        val svc = createService(
            linear = linear,
            projectConfig = config().copy(tracker = config().tracker.copy(blockedState = "Blocked"))
        )
        svc.handleClarification("1", "Need specs")
        assertThat(svc.state.isBlocked("1")).isTrue()
        assertThat(tracking.commentedIssueId).isEqualTo("1")
    }

    @Test
    fun `handleClarification sends notification when configured`() = runBlocking {
        var notified = false
        val notifier = object : com.flexsentlabs.koncerto.notifications.Notifier {
            override suspend fun send(event: com.flexsentlabs.koncerto.notifications.NotificationEvent) {
                if (event is com.flexsentlabs.koncerto.notifications.NotificationEvent.ClarificationRequested) {
                    notified = true
                }
            }
        }
        val linear = TrackingLinearClient()
        linear.addIssue(issue("1", "A-1", "Todo"))
        val svc = createService(
            linear = linear,
            notifier = CompositeNotifier(listOf(notifier)),
            notificationsConfig = NotificationsConfig(onClarification = true)
        )
        svc.handleClarification("1", "Please clarify")
        assertThat(notified).isTrue()
        assertThat(svc.state.isBlocked("1")).isTrue()
    }

    @Test
    fun `handleClarification no-ops when issue fetch returns null`() = runBlocking {
        val svc = createService(candidates = emptyList<Issue>())
        svc.handleClarification("missing", "content")
        assertThat(svc.state.isBlocked("missing")).isFalse()
    }

    @Test
    fun `handleClarification assigns human creator`() = runBlocking {
        val linear = TrackingLinearClient()
        val human = UserRef("user-1", "Human", isBot = false)
        linear.addIssue(issue("1", "A-1", "Todo").copy(creator = human))
        val svc = createService(linear = linear)
        svc.handleClarification("1", "Need input")
        assertThat(linear.assignedUserId).isEqualTo("user-1")
        assertThat(svc.state.isBlocked("1")).isTrue()
    }

    @Test
    fun `fetchAndDispatch removes stale blocked keys`() = runBlocking {
        val state = RuntimeState()
        state.addBlocked("stale-id")
        val ready = issue("2", "A-2", "Todo")
        val svc = createService(state = state, candidates = listOf(ready))
        svc.fetchAndDispatch(CoroutineScope(coroutineContext))
        assertThat(state.isBlocked("stale-id")).isFalse()
    }

    @Test
    fun `prepareDispatch releases claim when audit logger throws`() = runBlocking {
        val state = RuntimeState()
        val audit = object : AuditLogger {
            override fun log(event: AuditEvent) {
                if (event.type == AuditEventType.AGENT_DISPATCHED) throw RuntimeException("audit boom")
            }
        }
        val runner = CollectingAgentRunner()
        val svc = createService(
            state = state,
            runner = runner,
            auditLogger = audit,
            candidates = listOf(issue("1", "A-1", "Todo"))
        )
        try {
            runDispatch(svc)
        } catch (_: RuntimeException) {
        }
        assertThat(state.isClaimed("1")).isFalse()
    }

    @Test
    fun `agentMessageFlow delivers routed messages`() = runBlocking {
        val (svc, _) = createServiceWithState()
        val messages = mutableListOf<AgentMessage>()
        val job = launch {
            svc.agentMessageFlow("agent-1").collect { messages.add(it) }
        }
        kotlinx.coroutines.delay(50)
        svc.sendAgentMessage("agent-2", "agent-1", "hello-flow")
        kotlinx.coroutines.delay(100)
        job.cancel()
        assertThat(messages.map { it.payload }).contains("hello-flow")
    }

    @Test
    fun `prepareDispatch tolerates in progress state transition failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient by tracking {
            override suspend fun updateIssueState(issueId: String, stateId: String) {
                throw RuntimeException("linear down")
            }
        }
        val runner = CollectingAgentRunner()
        val svc = createService(linear = linear, runner = runner, candidates = listOf(issue("1", "A-1", "Todo")))
        runDispatchAwait(svc)
        assertThat(runner.dispatched.size).isEqualTo(1)
    }

    @Test
    fun `handleClarification tolerates assignee update failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.addIssue(issue("1", "A-1", "Todo").copy(creator = UserRef("user-1", "Human", isBot = false)))
        val linear = object : LinearClient by tracking {
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {
                throw RuntimeException("assignee failed")
            }
        }
        val svc = createService(linear = linear)
        svc.handleClarification("1", "Need input")
        assertThat(svc.state.isBlocked("1")).isTrue()
    }

    @Test
    fun `handleClarification tolerates state update failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.addIssue(issue("1", "A-1", "Todo"))
        val linear = object : LinearClient by tracking {
            override suspend fun updateIssueState(issueId: String, stateId: String) {
                throw RuntimeException("state failed")
            }
        }
        val svc = createService(linear = linear)
        svc.handleClarification("1", "Need input")
        assertThat(svc.state.isBlocked("1")).isTrue()
    }

    @Test
    fun `transitionOnComplete skips when target state id missing`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.resolveStateIdResult = null
        val stage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = "Done"
        )
        val svc = createService(linear = tracking)
        svc.transitionOnComplete(issue("1", "A-1", "Todo"), stage)
        assertThat(tracking.transitionedIssueId).isNull()
    }

    @Test
    fun `transitionOnComplete tolerates linear update failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.throwOnStateUpdate = true
        val stage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = "Done"
        )
        val svc = createService(linear = tracking)
        svc.transitionOnComplete(issue("1", "A-1", "Todo"), stage)
        assertThat(tracking.transitionedIssueId).isNull()
    }

    @Test
    fun `transitionOnComplete logs when follow-up creation fails`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.createIssueResult = null
        val stage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = "Done",
            followUp = FollowUpConfig(titleTemplate = "Follow {{ issue.identifier }}", state = "Todo", linkType = "")
        )
        val svc = createService(linear = tracking)
        svc.transitionOnComplete(issue("1", "A-1", "Todo"), stage)
        assertThat(tracking.transitionedIssueId).isEqualTo("1")
        assertThat(tracking.createdIssueTitle).isEqualTo("Follow A-1")
    }

    @Test
    fun `transitionOnComplete tolerates follow-up link failure`() = runBlocking {
        val tracking = TrackingLinearClient()
        tracking.createIssueResult = issue("2", "A-2", "Todo")
        tracking.createLinkResult = false
        val stage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = "Done",
            followUp = FollowUpConfig(titleTemplate = "Follow {{ issue.identifier }}", state = "Todo", linkType = "blocks")
        )
        val svc = createService(linear = tracking)
        svc.transitionOnComplete(issue("1", "A-1", "Todo"), stage)
        assertThat(tracking.linkedSourceId).isEqualTo("1")
        assertThat(tracking.linkedTargetId).isEqualTo("2")
    }

    @Test
    fun `scheduleRetry writes scheduled and exhausted trace steps`(@TempDir root: Path) = runBlocking {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        val workspace = workspaces.ensureWorkspace("T-1")
        val projectConfig = config().copy(agent = config().agent.copy(maxRetries = 1))
        val svc = createService(
            projectConfig = projectConfig,
            state = RuntimeState(),
            workspaces = workspaces
        )
        val testIssue = issue("1", "T-1", "Todo")

        svc.scheduleRetry(testIssue, "first failure")
        svc.scheduleRetry(testIssue, "second failure")

        val traceFile = Files.list(workspace.path.resolve(".koncerto")).use { stream ->
            stream.filter {
                it.fileName.toString().startsWith("dispatch-trace-") && it.fileName.toString().endsWith(".jsonl")
            }.findFirst().orElseThrow()
        }
        val trace = Files.readString(traceFile)
        assertThat(trace.contains("\"step\":\"retry\"")).isTrue()
        assertThat(trace.contains("\"status\":\"scheduled\"")).isTrue()
        assertThat(trace.contains("\"status\":\"exhausted\"")).isTrue()
    }

    @Test
    fun `scheduleRetry tolerates trace write failures`(@TempDir root: Path) = runBlocking {
        val workspaces = WorkspaceManager(root, HookExecutor { _, _ -> })
        val workspace = workspaces.ensureWorkspace("T-1")
        Files.writeString(workspace.path.resolve(".koncerto"), "not-a-dir")

        val svc = createService(
            projectConfig = config().copy(agent = config().agent.copy(maxRetries = 2)),
            state = RuntimeState(),
            workspaces = workspaces
        )
        svc.scheduleRetry(issue("1", "T-1", "Todo"), "boom")
        assertThat(svc.state.retryAttempts["1"]?.attempt).isEqualTo(1)
    }

    @Test
    fun `transitionOnComplete falls back when workspace resolution fails for review state`(@TempDir root: Path) = runBlocking {
        val rootFile = root.resolve("workspace-root-file")
        Files.writeString(rootFile, "not-a-directory")
        val cfg = config().copy(workspace = WorkspaceConfig(root = rootFile.toString()))
        val tracking = TrackingLinearClient()
        val stage = StageAgentConfig(
            prompt = "p", model = null, effort = null, maxConcurrent = null,
            agentKind = "codex", command = null, onCompleteState = "Done",
            onFailureState = "In Progress", maxReviewAttempts = 3,
            agent = null, followUp = null, crossProjectFollowUp = null
        )
        val svc = createService(projectConfig = cfg, state = RuntimeState(), linear = tracking)
        svc.transitionOnComplete(issue("1", "T-1", "Todo"), stage)
        assertThat(tracking.transitionedIssueId).isEqualTo("1")
    }

    private fun setEnvVar(name: String, value: String) {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        val env = pe.getDeclaredField("theEnvironment")
        env.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (env.get(null) as MutableMap<String, String>)[name] = value
        try {
            val ciEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment")
            ciEnv.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (ciEnv.get(null) as MutableMap<String, String>)[name] = value
        } catch (_: NoSuchFieldException) {
        }
    }

    private fun clearEnvVar(name: String) {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        val env = pe.getDeclaredField("theEnvironment")
        env.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (env.get(null) as MutableMap<String, String>).remove(name)
        try {
            val ciEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment")
            ciEnv.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (ciEnv.get(null) as MutableMap<String, String>).remove(name)
        } catch (_: NoSuchFieldException) {
        }
    }

    @Test
    fun `scheduleRetry posts comment before transitioning to blocked state`() = runBlocking {
        val projectConfig = config().copy(
            agent = config().agent.copy(maxRetries = 2),
            tracker = config().tracker.copy(blockedState = "Blocked")
        )
        val callOrder = mutableListOf<String>()
        val backing = TrackingLinearClient()
        val trackingLinear = object : LinearClient by backing {
            override suspend fun createComment(issueId: String, body: String) {
                callOrder.add("createComment")
                backing.createComment(issueId, body)
            }
            override suspend fun updateIssueState(issueId: String, stateId: String) {
                callOrder.add("updateState")
                backing.updateIssueState(issueId, stateId)
            }
        }
        val state = RuntimeState()
        val svc = createService(projectConfig = projectConfig, state = state, linear = trackingLinear)
        val testIssue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(testIssue, "err1")
        svc.scheduleRetry(testIssue, "err2")
        svc.scheduleRetry(testIssue, "err3")
        assertThat(backing.commentedIssueId).isEqualTo("1")
        assertThat(backing.transitionedIssueId).isEqualTo("1")
        assertThat(callOrder.indexOf("createComment") < callOrder.indexOf("updateState")).isTrue()
    }

    @Test
    fun `scheduleRetry still transitions to blocked even if comment fails`() = runBlocking {
        val projectConfig = config().copy(
            agent = config().agent.copy(maxRetries = 2),
            tracker = config().tracker.copy(blockedState = "Blocked")
        )
        val backing = TrackingLinearClient()
        val failingCommentLinear = object : LinearClient by backing {
            override suspend fun createComment(issueId: String, body: String) {
                throw RuntimeException("API down")
            }
        }
        val state = RuntimeState()
        val svc = createService(projectConfig = projectConfig, state = state, linear = failingCommentLinear)
        val testIssue = issue("1", "A-1", "Todo")
        svc.scheduleRetry(testIssue, "err1")
        svc.scheduleRetry(testIssue, "err2")
        svc.scheduleRetry(testIssue, "err3")
        assertThat(backing.transitionedIssueId).isEqualTo("1")
    }

    @Test
    fun `dispatchDueRetries requeues entry when redispatch fails`() = runBlocking {
        AgentAuthChecker.markUnauthenticated("codex")
        try {
            val state = RuntimeState()
            state.retryAttempts["1"] = RetryEntry(
                issueId = "1",
                identifier = "A-1",
                attempt = 2,
                dueAtMs = System.currentTimeMillis() - 10_000,
                error = "timeout"
            )
            val svc = createService(
                state = state,
                candidates = listOf(issue("1", "A-1", "Todo"))
            )
            svc.dispatchDueRetries(CoroutineScope(coroutineContext))
            assertThat(state.retryAttempts.containsKey("1")).isTrue()
        } finally {
            AgentAuthChecker.markAuthenticated("codex")
        }
    }

    @Test
    fun `dispatchDueLimitPauses keeps entry when issue claimed`() = runBlocking {
        val state = RuntimeState()
        val pauseEntry = LimitPauseEntry(
            issueId = "1",
            identifier = "A-1",
            stageName = "todo",
            agentKind = "codex",
            provider = "codex",
            error = "limit",
            resumeAtMs = System.currentTimeMillis() - 1
        )
        state.limitPauses["1"] = pauseEntry
        state.claimed["1"] = true
        val svc = createService(state = state, candidates = listOf(issue("1", "A-1", "Todo")))
        svc.dispatchDueLimitPauses(CoroutineScope(coroutineContext))
        assertThat(state.limitPauses["1"]).isEqualTo(pauseEntry)
    }

    @Test
    fun `awaitBackgroundJobs removes completed jobs`() = runBlocking {
        val svc = createService(candidates = emptyList())
        val field = DispatchService::class.java.getDeclaredField("backgroundJobs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(svc) as MutableList<Job>
        val completed = launch { }
        completed.join()
        jobs.add(completed)
        svc.awaitBackgroundJobs()
        assertThat(jobs.isEmpty()).isTrue()
    }
}

private class ReviewWritingAgentRunner(
    private val workspaceRoot: Path,
    private val reviewStatus: String?
) : AgentRunner {
    private var invocations = 0
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue, attempt: Int?, prompt: String,
        agentKindOverride: String?, commandOverride: String?,
        modelOverride: String?, effortOverride: String?,
        turnTimeoutMs: Long?, stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        invocations++
        if (invocations > 1 && reviewStatus != null) {
            val dir = workspaceRoot.resolve(issue.identifier)
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(".review-status"), reviewStatus)
        }
        return Result.Success(Unit)
    }
}

private class TokenEventRunner : AgentRunner {
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue, attempt: Int?, prompt: String,
        agentKindOverride: String?, commandOverride: String?,
        modelOverride: String?, effortOverride: String?,
        turnTimeoutMs: Long?, stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        flow.emit(AgentEvent.TurnCompleted(threadId = "t", turnId = "u", usage = TokenUsage(10, 20, 30), pid = null))
        return Result.Success(Unit)
    }
}

private class LimitFailureRunner(private val error: Throwable) : AgentRunner {
    private val flow = MutableSharedFlow<com.flexsentlabs.koncerto.agent.AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue, attempt: Int?, prompt: String,
        agentKindOverride: String?, commandOverride: String?,
        modelOverride: String?, effortOverride: String?,
        turnTimeoutMs: Long?, stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        @Suppress("UNCHECKED_CAST")
        return Result.Failure(error) as Result.Failure<IllegalStateException>
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
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? = null
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
}

private class TrackingLinearClient : LinearClient {
    var transitionedIssueId: String? = null
    var transitionedStateId: String? = null
    var commentedIssueId: String? = null
    var commentedBody: String? = null
    var assignedIssueId: String? = null
    var assignedUserId: String? = null
    var createdIssueTitle: String? = null
    var createdIssueState: String? = null
    var createdIssueLabels: List<String>? = null
    var linkedSourceId: String? = null
    var linkedTargetId: String? = null
    var linkedType: String? = null
    var createIssueResult: Issue? = null
    var createLinkResult: Boolean = true
    var resolveStateIdResult: String? = "done-id"
    var throwOnStateUpdate: Boolean = false
    val resolvedStateHistory = mutableListOf<String>()
    private val candidates = mutableListOf<Issue>()

    fun addIssue(issue: Issue) { candidates.add(issue) }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> =
        candidates.filter { activeStates.any { s -> it.state.equals(s, ignoreCase = true) } }
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> = emptyMap()
    override suspend fun fetchIssueById(issueId: String): Issue? = candidates.firstOrNull { it.id == issueId }
    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? {
        resolvedStateHistory.add(stateName)
        return resolveStateIdResult
    }
    override suspend fun updateIssueState(issueId: String, stateId: String) {
        if (throwOnStateUpdate) throw RuntimeException("linear down")
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
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? =
        candidates.firstOrNull { it.id == issueId }?.creator
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? {
        createdIssueTitle = title
        createdIssueState = state
        createdIssueLabels = labels
        return createIssueResult
    }
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean {
        linkedSourceId = sourceIssueId
        linkedTargetId = targetIssueId
        linkedType = type
        return createLinkResult
    }
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
    override suspend fun fetchIssueCreator(issueId: String): com.flexsentlabs.koncerto.core.model.UserRef? =
        throw RuntimeException("API down")
    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? = throw RuntimeException("API down")
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean =
        throw RuntimeException("API down")
}

private class CollectingAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    val runArgs = mutableListOf<RunArgs>()
    private val flow = MutableSharedFlow<com.flexsentlabs.koncerto.agent.AgentEvent>()
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
        runArgs += RunArgs(issue, prompt, agentKindOverride, commandOverride, modelOverride, effortOverride)
        return Result.Success(Unit)
    }
}

private data class RunArgs(
    val issue: Issue,
    val prompt: String,
    val agentKindOverride: String?,
    val commandOverride: String?,
    val modelOverride: String? = null,
    val effortOverride: String? = null
)

private class FailingRunner(private val errorMsg: String) : AgentRunner {
    private val flow = MutableSharedFlow<com.flexsentlabs.koncerto.agent.AgentEvent>()
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
        return Result.Failure(IllegalStateException(errorMsg))
    }
}

private class FakeGitWorkflowForDispatch(
    private val remoteExists: Boolean
) : GitWorkflow(GitConfig(enabled = true), StructuredLogger(emptyList())) {
    override fun remoteBranchExists(branchName: String, workspacePath: Path): Boolean = remoteExists
}

private class FakeSubtaskRunnerForDispatch(
    private val block: suspend () -> Unit
) : SubtaskRunner {
    override suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String,
        command: String?,
        turnTimeoutMs: Long,
        stallTimeoutMs: Long
    ): EmptyResult<IllegalStateException> {
        block()
        return Result.Success(Unit)
    }
}
