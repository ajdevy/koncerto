package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.contains
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
import com.flexsentlabs.koncerto.deploy.DeployConfig
import com.flexsentlabs.koncerto.deploy.DeployResult
import com.flexsentlabs.koncerto.deploy.ProjectDeployer
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.Notifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
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

    @Test
    fun `review exhaustion sends notification to notifier`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        var notified = false
        var capturedEvent: NotificationEvent? = null
        val trackingNotifier = CompositeNotifier(listOf(object : Notifier {
            override suspend fun send(event: NotificationEvent) {
                notified = true
                capturedEvent = event
            }
        }))

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = trackingNotifier,
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
        assertThat(notified).isTrue()
        assertThat(capturedEvent).isNotNull()
    }

    @Test
    fun `readReviewStatus returns false for empty status file after delay`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "")

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
        assertThat(decision is AutoReviewOrchestrator.ReviewDecision.RetryWithCoding).isTrue()
    }

    @Test
    fun `review files are deleted after pass`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output"), "review output")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "detailed output")
        Files.writeString(issueDir.resolve(".review-attempt"), "1")

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
        orchestrator.onCodingComplete(issue())
        assertThat(Files.exists(issueDir.resolve(".review-status"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-output"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-output-detailed"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-attempt"))).isFalse()
    }

    @Test
    fun `review files are deleted after retry`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "fail")
        Files.writeString(issueDir.resolve(".review-output"), "review output")
        Files.writeString(issueDir.resolve(".review-attempt"), "1")

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
        assertThat(Files.exists(issueDir.resolve(".review-status"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-output"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-attempt"))).isFalse()
    }

    @Test
    fun `review files are deleted after exhaustion`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "fail")
        Files.writeString(issueDir.resolve(".review-output"), "review output")
        Files.writeString(issueDir.resolve(".review-attempt"), "1")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
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
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
        assertThat(Files.exists(issueDir.resolve(".review-status"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-output"))).isFalse()
        assertThat(Files.exists(issueDir.resolve(".review-attempt"))).isFalse()
    }

    @Test
    fun `review exhaustion when blocked state not found`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val nullStateTracker = object : TrackerClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String): Issue? = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null
            override suspend fun updateIssueState(issueId: String, stateId: String) {}
            override suspend fun createComment(issueId: String, body: String) {}
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
        }

        var notified = false
        val trackingNotifier = CompositeNotifier(listOf(object : Notifier {
            override suspend fun send(event: NotificationEvent) { notified = true }
        }))

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = nullStateTracker,
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = trackingNotifier,
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
        assertThat(notified).isTrue()
    }

    private fun initGitOrigin(workspace: Path, repo: String = "owner/repo") {
        Files.createDirectories(workspace.resolve(".git"))
        Files.writeString(
            workspace.resolve(".git/config"),
            """
            [remote "origin"]
                url = git@github.com:$repo.git
            """.trimIndent()
        )
    }

    private class RecordingProjectDeployer(
        var deployResult: DeployResult = DeployResult.success("http://localhost:8080")
    ) : ProjectDeployer {
        val deployCalls = mutableListOf<DeployConfig>()
        val cleanupCalls = mutableListOf<DeployConfig>()

        override suspend fun deploy(config: DeployConfig): DeployResult {
            deployCalls.add(config)
            return deployResult
        }

        override suspend fun cleanup(config: DeployConfig) {
            cleanupCalls.add(config)
        }
    }

    private fun reviewStage() = StageAgentConfig(
        prompt = null, model = "claude-sonnet", effort = null, maxConcurrent = null,
        agentKind = "claude", command = "claude",
        onCompleteState = "Done", onFailureState = "In Progress",
        maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
    )

    private fun passingReviewOrchestrator(
        workspaceDir: Path,
        deployer: RecordingProjectDeployer? = null,
        deployRepoFullName: String? = null,
        ghProcessRunner: GhProcessRunner? = null,
        onReviewPassed: (suspend (Issue, String?) -> String?)? = null
    ): AutoReviewOrchestrator {
        val issueDir = workspaceDir.resolve("T-1")
        Files.createDirectories(issueDir)
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        return AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            targetProjectDeployer = deployer,
            deployRepoFullName = deployRepoFullName,
            ghProcessRunner = ghProcessRunner ?: { _, _ -> GhProcessResult(0, "") },
            onReviewPassed = onReviewPassed
        )
    }

    @Test
    fun `writeReviewExhaustionFile writes json marker on max attempts`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(
                stages = mapOf("in review" to reviewStage().copy(maxReviewAttempts = 1)),
                workspaceRoot = workspaceDir.toString()
            ),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
        val exhaustionFile = workspaceDir.resolve("T-1").resolve(".review-exhausted")
        assertThat(Files.exists(exhaustionFile)).isTrue()
        val content = Files.readString(exhaustionFile)
        assertThat(content.contains("issue-1")).isTrue()
        assertThat(content.contains("total_attempts")).isTrue()
        assertThat(content.contains("Review gate failed")).isTrue()
    }

    @Test
    fun `resolveRepoFullName reads owner repo from workspace git config`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val deployer = RecordingProjectDeployer()
        passingReviewOrchestrator(workspaceDir, deployer = deployer).onCodingComplete(issue())
        assertThat(deployer.deployCalls.single().repoFullName).isEqualTo("acme/widget")
    }

    @Test
    fun `resolveRepoFullName falls back to deployRepoFullName when git config missing`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val deployer = RecordingProjectDeployer()
        passingReviewOrchestrator(
            workspaceDir,
            deployer = deployer,
            deployRepoFullName = "fallback/repo"
        ).onCodingComplete(issue())
        assertThat(deployer.deployCalls.single().repoFullName).isEqualTo("fallback/repo")
    }

    @Test
    fun `postDetailedReviewAsPrComment posts stripped review body via gh runner`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(
            issueDir.resolve(".review-output-detailed"),
            """
            Claude configuration file not found at: /missing
            ---
            ✅ PASS
            Looks good to merge.
            """.trimIndent()
        )

        var capturedBody: String? = null
        val ghRunner: GhProcessRunner = { command, workDir ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":7}""")
                command.contains("comment") -> {
                    val bodyFileIndex = command.indexOf("--body-file")
                    if (bodyFileIndex >= 0) {
                        capturedBody = Files.readString(Path.of(command[bodyFileIndex + 1]))
                    }
                    GhProcessResult(0, "https://github.com/acme/widget/pull/7#issuecomment-1")
                }
                else -> GhProcessResult(1, "unknown command")
            }
        }

        passingReviewOrchestrator(
            workspaceDir,
            ghProcessRunner = ghRunner,
            onReviewPassed = { _, _ -> "https://demo.example/rec" }
        ).onCodingComplete(issue())

        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!.contains("Claude Review #1")).isTrue()
        assertThat(capturedBody!!.contains("Looks good to merge.")).isTrue()
        assertThat(capturedBody!!.contains("Watch Demo Recording")).isTrue()
    }

    @Test
    fun `deployTargetProject and cleanupDemoDeploy run on successful review pass`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")

        val deployer = RecordingProjectDeployer(
            deployResult = DeployResult.success("http://localhost:32768", tag = "koncerto-demo-t-1")
        )
        passingReviewOrchestrator(workspaceDir, deployer = deployer).onCodingComplete(issue(id = "issue-1", identifier = "T-1"))

        assertThat(deployer.deployCalls.size).isEqualTo(1)
        assertThat(deployer.deployCalls.single().prBranch).isEqualTo("T-1")
        assertThat(deployer.cleanupCalls.size).isEqualTo(1)
        assertThat(deployer.cleanupCalls.single().repoFullName).isEqualTo("acme/widget")
    }

    @Test
    fun `deployTargetProject skips cleanup when deploy fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")

        val deployer = RecordingProjectDeployer(
            deployResult = DeployResult.failure("build failed", logs = "error log")
        )
        val decision = passingReviewOrchestrator(workspaceDir, deployer = deployer).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(deployer.deployCalls.size).isEqualTo(1)
        assertThat(deployer.cleanupCalls.size).isEqualTo(0)
    }

    @Test
    fun `postDetailedReviewAsPrComment skips when gh comment fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nAll good.")

        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":9}""")
                command.contains("comment") -> GhProcessResult(1, "comment failed")
                else -> GhProcessResult(1, "unknown")
            }
        }

        passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner).onCodingComplete(issue())
    }

    @Test
    fun `postDetailedReviewAsPrComment skips when repo cannot be resolved`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nNo repo.")

        var ghCalled = false
        val ghRunner: GhProcessRunner = { _, _ ->
            ghCalled = true
            GhProcessResult(0, "ok")
        }

        passingReviewOrchestrator(
            workspaceDir,
            ghProcessRunner = ghRunner,
            deployRepoFullName = null
        ).onCodingComplete(issue())
        assertThat(ghCalled).isFalse()
    }

    @Test
    fun `onCodingComplete resolves review prompt via workflow cache`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        val cache = WorkflowCache().also {
            it.setWorkflowDir(tmpDir.resolve("workflow"))
            Files.createDirectories(tmpDir.resolve("workflow"))
            Files.writeString(tmpDir.resolve("workflow/review.md"), "Resolved review prompt")
        }
        val reviewStage = reviewStage().copy(prompt = "review.md")
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            workflowCache = cache
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `onCodingComplete invokes onReviewPassed callback`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        var callbackInvoked = false
        val orchestrator = passingReviewOrchestrator(
            workspaceDir,
            onReviewPassed = { _, _ -> callbackInvoked = true; "https://demo.example.com/vid" }
        )
        orchestrator.onCodingComplete(issue())
        assertThat(callbackInvoked).isTrue()
    }

    @Test
    fun `postDetailedReviewAsPrComment appends demo url when provided`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "---\n✅ PASS\nAll checks passed\n")
        var capturedBody: String? = null
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":7}""")
                command.contains("comment") -> {
                    val bodyFileIndex = command.indexOf("--body-file")
                    if (bodyFileIndex >= 0) {
                        capturedBody = Files.readString(Path.of(command[bodyFileIndex + 1]))
                    }
                    GhProcessResult(0, "https://github.com/acme/widget/pull/7#issuecomment-1")
                }
                else -> GhProcessResult(0, "")
            }
        }
        val orchestrator = passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner)
        val ws = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }).ensureWorkspace("T-1")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "postDetailedReviewAsPrComment",
            Issue::class.java,
            com.flexsentlabs.koncerto.workspace.Workspace::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, issue(), ws, 2, "https://demo.example.com/vid")
        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!).contains("Watch Demo Recording")
        assertThat(capturedBody!!).contains("https://demo.example.com/vid")
    }

    @Test
    fun `resolvePrNumber returns number from gh output`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        val ghRunner: GhProcessRunner = { command, _ ->
            if (command.contains("view")) GhProcessResult(0, """{"number":42}""")
            else GhProcessResult(0, "")
        }
        val orchestrator = passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner)
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("resolvePrNumber", Path::class.java)
        method.isAccessible = true
        val prNumber = method.invoke(orchestrator, issueDir) as Int?
        assertThat(prNumber).isEqualTo(42)
    }

    @Test
    fun `cleanupReviewFiles tolerates delete failures`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val reviewDir = issueDir.resolve(".review-output")
        Files.createDirectories(reviewDir)
        Files.writeString(reviewDir.resolve("nested.txt"), "keep")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("cleanupReviewFiles", Path::class.java)
        method.isAccessible = true
        method.invoke(orchestrator, issueDir)
        assertThat(Files.exists(reviewDir)).isTrue()
    }

    @Test
    fun `handleReviewExhaustion posts comment before transitioning to blocked state`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        val commentedIds = mutableListOf<String>()
        val commentedBodies = mutableListOf<String>()
        val transitionedIds = mutableListOf<String>()
        val callOrder = mutableListOf<String>()

        val trackingTracker = object : TrackerClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String): Issue? = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String) = "state-blocked"
            override suspend fun updateIssueState(issueId: String, stateId: String) {
                transitionedIds.add(issueId)
                callOrder.add("updateState")
            }
            override suspend fun createComment(issueId: String, body: String) {
                commentedIds.add(issueId)
                commentedBodies.add(body)
                callOrder.add("createComment")
            }
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
        }

        val exhaustionStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = trackingTracker,
            projectConfig = projectConfig(stages = mapOf("in review" to exhaustionStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())

        assertThat(commentedIds.contains("issue-1")).isTrue()
        assertThat(commentedBodies.any { it.contains("review") || it.contains("attempt") }).isTrue()
        assertThat(callOrder.indexOf("createComment") < callOrder.indexOf("updateState")).isTrue()
    }

    @Test
    fun `handleReviewExhaustion still transitions to blocked even if comment fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))

        var transitioned = false
        val failingCommentTracker = object : TrackerClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String): Issue? = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String) = "state-blocked"
            override suspend fun updateIssueState(issueId: String, stateId: String) { transitioned = true }
            override suspend fun createComment(issueId: String, body: String) { throw RuntimeException("API down") }
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
        }

        val exhaustionStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = failingCommentTracker,
            projectConfig = projectConfig(stages = mapOf("in review" to exhaustionStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())

        assertThat(transitioned).isTrue()
    }

    @Test
    fun `postDetailedReviewAsPrComment logs failure when gh exits non-zero`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "---\n✅ PASS\nLooks good\n")
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":9}""")
                command.contains("comment") -> GhProcessResult(1, "comment failed")
                else -> GhProcessResult(0, "")
            }
        }
        val orchestrator = passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner)
        val ws = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }).ensureWorkspace("T-1")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "postDetailedReviewAsPrComment",
            Issue::class.java,
            com.flexsentlabs.koncerto.workspace.Workspace::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, issue(), ws, 1, null)
    }

    @Test
    fun `postDetailedReviewAsPrComment skips when detailed file missing`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val ws = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }).ensureWorkspace("T-1")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "postDetailedReviewAsPrComment",
            Issue::class.java,
            com.flexsentlabs.koncerto.workspace.Workspace::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, issue(), ws, 1, null)
    }

    @Test
    fun `postDetailedReviewAsPrComment skips blank content after stripping`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "Claude configuration file not found\n")
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val ws = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }).ensureWorkspace("T-1")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "postDetailedReviewAsPrComment",
            Issue::class.java,
            com.flexsentlabs.koncerto.workspace.Workspace::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, issue(), ws, 1, null)
    }

    @Test
    fun `deployTargetProject returns failure when deployer fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        val failingDeployer = RecordingProjectDeployer(
            deployResult = DeployResult.failure("build failed", logs = "docker error")
        )
        val orchestrator = passingReviewOrchestrator(
            workspaceDir,
            deployer = failingDeployer,
            deployRepoFullName = "acme/widget",
            ghProcessRunner = { command, _ ->
                if (command.contains("view")) GhProcessResult(0, """{"number":1}""")
                else GhProcessResult(0, "")
            }
        )
        orchestrator.onCodingComplete(issue())
    }

    @Test
    fun `handleReviewExhaustion sends notification when notifier configured`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))
        var notified = false
        val notifier = CompositeNotifier(listOf(object : com.flexsentlabs.koncerto.notifications.Notifier {
            override suspend fun send(event: com.flexsentlabs.koncerto.notifications.NotificationEvent) {
                notified = true
            }
        }))
        val exhaustionStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to exhaustionStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = notifier,
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
        assertThat(notified).isTrue()
    }

    @Test
    fun `postDetailedReviewAsPrComment uses verdict line when no yaml separator`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(
            issueDir.resolve(".review-output-detailed"),
            "Preamble line\n✅ PASS\nVerdict without separator block.\n"
        )
        var capturedBody: String? = null
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":3}""")
                command.contains("comment") -> {
                    val idx = command.indexOf("--body-file")
                    if (idx >= 0) capturedBody = Files.readString(Path.of(command[idx + 1]))
                    GhProcessResult(0, "https://github.com/acme/widget/pull/3#issuecomment-1")
                }
                else -> GhProcessResult(0, "")
            }
        }
        passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner).onCodingComplete(issue())
        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!.contains("Verdict without separator")).isTrue()
    }

    @Test
    fun `postDetailedReviewAsPrComment logs error when gh runner throws`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "---\n✅ PASS\nok\n")
        val ghRunner: GhProcessRunner = { command, _ ->
            if (command.contains("comment")) throw RuntimeException("gh exploded")
            GhProcessResult(0, """{"number":1}""")
        }
        val orchestrator = passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner)
        val ws = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }).ensureWorkspace("T-1")
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "postDetailedReviewAsPrComment",
            Issue::class.java,
            com.flexsentlabs.koncerto.workspace.Workspace::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, issue(), ws, 1, null)
    }

    @Test
    fun `handleReviewExhaustion warns when blocked state missing`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))
        val tracker = object : TrackerClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String): Issue? = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null
            override suspend fun updateIssueState(issueId: String, stateId: String) {}
            override suspend fun createComment(issueId: String, body: String) {}
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
        }
        val exhaustionStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = tracker,
            projectConfig = projectConfig(stages = mapOf("in review" to exhaustionStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
    }

    @Test
    fun `postDetailedReviewAsPrComment uses plain content when no verdict marker`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "Plain review notes without pass fail markers\n")
        var capturedBody: String? = null
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":5}""")
                command.contains("comment") -> {
                    val idx = command.indexOf("--body-file")
                    if (idx >= 0) capturedBody = Files.readString(Path.of(command[idx + 1]))
                    GhProcessResult(0, "https://github.com/acme/widget/pull/5#issuecomment-1")
                }
                else -> GhProcessResult(0, "")
            }
        }
        passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner).onCodingComplete(issue())
        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!.contains("Plain review notes")).isTrue()
    }

    @Test
    fun `handleReviewExhaustion tolerates comment state and notification failures`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))
        val tracker = object : TrackerClient {
            override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
            override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
            override suspend fun fetchIssueById(issueId: String): Issue? = null
            override suspend fun resolveStateId(projectSlug: String, stateName: String) = "blocked-id"
            override suspend fun updateIssueState(issueId: String, stateId: String) {
                throw RuntimeException("state update failed")
            }
            override suspend fun createComment(issueId: String, body: String) {
                throw RuntimeException("comment failed")
            }
            override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
            override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
            override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) = null
            override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) = false
        }
        val notifier = object : Notifier {
            override suspend fun send(event: NotificationEvent) {
                throw RuntimeException("notify failed")
            }
        }
        val exhaustionStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = tracker,
            projectConfig = projectConfig(stages = mapOf("in review" to exhaustionStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = CompositeNotifier(listOf(notifier)),
            logger = noopLogger()
        )
        orchestrator.onCodingComplete(issue())
        assertThat(Files.exists(workspaceDir.resolve("T-1").resolve(".review-exhausted"))).isTrue()
    }
}
