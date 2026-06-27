package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.Notifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AutoReviewOrchestratorTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun noopNotifier() = CompositeNotifier(listOf(object : Notifier {
        override suspend fun send(event: NotificationEvent) {}
    }))

    private fun fakeTracker(): TrackerClient = object : TrackerClient {
        override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
        override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
        override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
        override suspend fun fetchIssueById(issueId: String): Issue? = null
        override suspend fun resolveStateId(projectSlug: String, stateName: String) = "state-blocked"
        override suspend fun updateIssueState(issueId: String, stateId: String) {}
        override suspend fun createComment(issueId: String, body: String) {}
        override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
        override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
        override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
        override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
    }

    private fun projectConfig(
        stages: Map<String, StageAgentConfig> = emptyMap(),
        workspaceRoot: String = "/tmp"
    ) = ProjectConfig(
        tracker = TrackerConfig(
            kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p",
            requiredLabels = emptyList(), activeStates = listOf("Todo"),
            terminalStates = listOf("Done"), blockedState = "Blocked", projectAdmin = null
        ),
        workspace = WorkspaceConfig(root = workspaceRoot),
        agent = AgentProjectConfig(
            kind = "opencode", command = "opencode",
            maxConcurrentAgents = 2, maxTurns = 1, maxRetryBackoffMs = 0,
            maxConcurrentAgentsByState = emptyMap(),
            turnTimeoutMs = 60000, readTimeoutMs = 5000, stallTimeoutMs = 30000,
            stages = stages
        )
    )

    private fun issue(id: String = "issue-1", identifier: String = "T-1") = Issue(
        id = id, identifier = identifier, title = "Test issue", description = null,
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private val validScenarioYaml = """
        demo_scenario:
          description: "Test"
          steps:
            - action: wait
              ms: 1000
    """.trimIndent()

    private fun trackingScenarioGenerator(): Pair<DemoScenarioGenerator, () -> Int> {
        var callCount = 0
        val gen = DemoScenarioGenerator(
            opencodeCommand = "opencode",
            logger = noopLogger(),
            processRunner = { _, _, _ ->
                callCount++
                validScenarioYaml
            }
        )
        return gen to { callCount }
    }

    private fun fakeRunner(succeed: Boolean = true): AgentRunner = object : AgentRunner {
        private val flow = MutableSharedFlow<AgentEvent>()
        override fun events() = flow.asSharedFlow()
        override suspend fun run(
            issue: Issue, attempt: Int?, prompt: String,
            agentKindOverride: String?, commandOverride: String?,
            modelOverride: String?, effortOverride: String?,
            turnTimeoutMs: Long?, stallTimeoutMs: Long?
        ): EmptyResult<IllegalStateException> =
            if (succeed) Result.Success(Unit)
            else Result.Failure(IllegalStateException("runner failed"))
    }

    @Test
    fun `returns NoReview when no review stage configured`(@TempDir tmpDir: Path) = runTest {
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(tmpDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = emptyMap(), workspaceRoot = tmpDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.NoReview::class)
    }

    @Test
    fun `returns Pass when review status file contains pass`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat((decision as AutoReviewOrchestrator.ReviewDecision.Pass).transitionToState).isEqualTo("Done")
    }

    @Test
    fun `returns RetryWithCoding when review fails and attempts remain`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
        assertThat((decision as AutoReviewOrchestrator.ReviewDecision.RetryWithCoding).rerouteToState)
            .isEqualTo("In Progress")
    }

    @Test
    fun `returns Blocked when max review attempts exhausted`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val state = RuntimeState()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = state,
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
        assertThat(state.reviewAttempts["issue-1"]).isNull()
    }

    @Test
    fun `tracks review attempts across calls`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = null, onFailureState = null,
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val state = RuntimeState()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = state,
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
        assertThat(state.reviewAttempts["issue-1"]).isEqualTo(1)
        orchestrator.onCodingComplete(issue())
        assertThat(state.reviewAttempts["issue-1"]).isEqualTo(2)
    }

    @Test
    fun `invokes onReviewPassed callback when review passes`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        var capturedIssue: Issue? = null
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            onReviewPassed = { issue, _ -> capturedIssue = issue; null }
        )
        val decision = orchestrator.onCodingComplete(issue(id = "issue-1", identifier = "T-1"))
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(capturedIssue).isNotNull()
        assertThat(capturedIssue!!.id).isEqualTo("issue-1")
        assertThat(capturedIssue!!.identifier).isEqualTo("T-1")
    }

    @Test
    fun `does not invoke onReviewPassed callback when review fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        var invoked = false
        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            onReviewPassed = { _, _ -> invoked = true; null }
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision !is AutoReviewOrchestrator.ReviewDecision.Pass).isTrue()
        assertThat(invoked).isFalse()
    }

    @Test
    fun `demoScenarioGenerator generate is called when review passes`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val (gen, callCount) = trackingScenarioGenerator()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = gen
        )
        orchestrator.onCodingComplete(issue())
        assertThat(callCount()).isEqualTo(1)
    }

    @Test
    fun `demoScenarioGenerator generate is NOT called when review fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "fail")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val (gen, callCount) = trackingScenarioGenerator()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = gen
        )
        orchestrator.onCodingComplete(issue())
        assertThat(callCount()).isEqualTo(0)
    }
}
