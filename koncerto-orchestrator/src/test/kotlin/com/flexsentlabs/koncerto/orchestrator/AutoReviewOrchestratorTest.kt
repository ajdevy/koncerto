package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.hasSize
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
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

    private fun failingRunner(): AgentRunner = object : AgentRunner {
        private val flow = MutableSharedFlow<AgentEvent>()
        override fun events() = flow.asSharedFlow()
        override suspend fun run(
            issue: Issue, attempt: Int?, prompt: String,
            agentKindOverride: String?, commandOverride: String?,
            modelOverride: String?, effortOverride: String?,
            turnTimeoutMs: Long?, stallTimeoutMs: Long?
        ): EmptyResult<IllegalStateException> = Result.Failure(IllegalStateException("should not run"))
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
    fun `onReviewStageComplete runs post review pipeline without rerunning agent`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output"), "✅ PASS\nreview ok")

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
                else -> GhProcessResult(1, "unknown command")
            }
        }

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )

        var callbackCount = 0
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = failingRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            ghProcessRunner = ghRunner,
            onReviewPassed = { _, _ ->
                callbackCount++
                null
            }
        )

        val decision = orchestrator.onReviewStageComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat((decision as AutoReviewOrchestrator.ReviewDecision.Pass).transitionToState).isEqualTo("Done")
        assertThat(callbackCount).isEqualTo(1)
        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!).contains("review ok")
        assertThat(Files.exists(issueDir.resolve(".review-output-detailed"))).isFalse()
    }

    @Test
    fun `returns RetryWithCoding(null) when subscription limit reached and posts one Linear comment`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-output"), "You've hit your org's monthly usage limit")
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        var commentCount = 0
        var lastComment = ""
        val tracker = object : TrackerClient by fakeTracker() {
            override suspend fun createComment(issueId: String, body: String) {
                commentCount++
                lastComment = body
            }
        }

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = null,
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = tracker,
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )

        val firstDecision = orchestrator.onReviewStageComplete(issue())
        assertThat(firstDecision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
        assertThat((firstDecision as AutoReviewOrchestrator.ReviewDecision.RetryWithCoding).rerouteToState).isNull()
        assertThat(commentCount).isEqualTo(1)
        assertThat(lastComment).contains("subscription limit")

        val secondDecision = orchestrator.onReviewStageComplete(issue())
        assertThat(secondDecision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
        assertThat((secondDecision as AutoReviewOrchestrator.ReviewDecision.RetryWithCoding).rerouteToState).isNull()
        assertThat(commentCount).isEqualTo(1)
    }

    @Test
    fun `normal review pass still works when subscription limit output absent`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output"), "✅ PASS\nreview ok")

        var commentCount = 0
        val tracker = object : TrackerClient by fakeTracker() {
            override suspend fun createComment(issueId: String, body: String) {
                commentCount++
            }
        }

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = null,
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = failingRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = tracker,
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )

        val decision = orchestrator.onReviewStageComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(commentCount).isEqualTo(0)
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
        Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/feature/T-1\n")
        Files.writeString(
            workspace.resolve(".git/config"),
            """
            [remote "origin"]
                url = git@github.com:$repo.git
            """.trimIndent()
        )
    }

    private fun initGitWorktreeOrigin(workspace: Path, repo: String = "owner/repo") {
        val gitDir = workspace.resolve(".gitdir")
        Files.createDirectories(gitDir)
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature/T-1\n")
        Files.writeString(
            gitDir.resolve("config"),
            """
            [remote "origin"]
                url = git@github.com:$repo.git
            """.trimIndent()
        )
        Files.writeString(
            workspace.resolve(".git"),
            "gitdir: ${gitDir.toAbsolutePath()}"
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
    fun `resolveRepoFullName reads owner repo from worktree gitdir file`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitWorktreeOrigin(issueDir, "acme/widget")
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
    fun `postDetailedReviewAsPrComment falls back to pr list when pr view fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nLooks good.")

        var commentPosted = false
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(1, "no pull requests found")
                command.contains("list") -> GhProcessResult(0, """[{"number":7}]""")
                command.contains("comment") -> {
                    commentPosted = true
                    GhProcessResult(0, "https://github.com/acme/widget/pull/7#issuecomment-1")
                }
                else -> GhProcessResult(1, "unknown command")
            }
        }

        passingReviewOrchestrator(
            workspaceDir,
            ghProcessRunner = ghRunner
        ).onCodingComplete(issue())

        assertThat(commentPosted).isTrue()
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
    fun `review trace captures successful pass pipeline`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "---\n✅ PASS\nLooks good\n")

        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":7}""")
                command.contains("comment") -> GhProcessResult(0, "https://github.com/acme/widget/pull/7#issuecomment-1")
                else -> GhProcessResult(0, "")
            }
        }

        passingReviewOrchestrator(
            workspaceDir,
            ghProcessRunner = ghRunner,
            onReviewPassed = { _, _ -> "https://demo.example.com/vid" }
        ).onCodingComplete(issue())

        val traceDir = issueDir.resolve(".koncerto")
        val traceFile = Files.list(traceDir).use { stream ->
            stream.filter {
                it.fileName.toString().startsWith("review-trace-") && it.fileName.toString().endsWith(".jsonl")
            }.findFirst().orElseThrow()
        }
        val trace = Files.readString(traceFile)
        assertThat(trace.contains("review_pass")).isTrue()
        assertThat(trace.contains("pr_comment")).isTrue()
        assertThat(trace.contains("demo_recording")).isTrue()
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

    @Test
    fun `uses default maxReviewAttempts when stage value is null`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        Files.createDirectories(workspaceDir.resolve("T-1"))
        val stage = reviewStage().copy(maxReviewAttempts = null, onFailureState = "In Progress")
        val state = RuntimeState()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to stage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = state,
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
        assertThat(state.reviewAttempts["issue-1"]).isEqualTo(1)
    }

    @Test
    fun `review backup copy failure does not stop pass flow`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.createDirectories(issueDir.resolve(".review-output"))

        val decision = passingReviewOrchestrator(workspaceDir).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `pass pipeline tolerates scenario deploy callback and cleanup throws`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nok")

        val throwingGenerator = DemoScenarioGenerator(
            opencodeCommand = "opencode",
            logger = noopLogger(),
            processRunner = { _, _, _ -> throw RuntimeException("scenario boom") }
        )
        val throwingDeployer = object : ProjectDeployer {
            override suspend fun deploy(config: DeployConfig): DeployResult {
                throw RuntimeException("deploy blew up")
            }

            override suspend fun cleanup(config: DeployConfig) {
                throw RuntimeException("cleanup blew up")
            }
        }
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = throwingGenerator,
            targetProjectDeployer = throwingDeployer,
            onReviewPassed = { _, _ -> throw RuntimeException("demo callback boom") }
        ).onCodingComplete(issue())

        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
    }

    @Test
    fun `pipeline wrapper catches postDetailedReview exceptions`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nok")
        Files.writeString(issueDir.resolve(".review-output"), "ok")

        val explodingLogger = StructuredLogger(listOf(object : LogSink {
            override fun write(line: String) {
                throw RuntimeException("log sink failed")
            }
        }))
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = explodingLogger
        ).onCodingComplete(issue())

        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `postDetailedReviewAsPrComment still comments when pr number cannot be resolved`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".review-output-detailed"), "✅ PASS\nok")

        var commentCalled = false
        val ghRunner: GhProcessRunner = { command, _ ->
            if (command.contains("comment")) commentCalled = true
            if (command.contains("view")) GhProcessResult(0, """{"unexpected":true}""") else GhProcessResult(0, "")
        }
        val decision = passingReviewOrchestrator(workspaceDir, ghProcessRunner = ghRunner).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(commentCalled).isTrue()
    }

    @Test
    fun `deploy failure reporter path executes on deploy failure`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val deployer = RecordingProjectDeployer(deployResult = DeployResult.failure("boom", logs = "stack"))
        val reporter = com.flexsentlabs.koncerto.deploy.DemoFailureReporter(noopLogger())
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            targetProjectDeployer = deployer,
            demoFailureReporter = reporter,
            ghProcessRunner = { command, _ ->
                if (command.contains("view")) GhProcessResult(1, "not found") else GhProcessResult(0, "")
            }
        ).onCodingComplete(issue())

        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(deployer.deployCalls.size).isEqualTo(1)
    }

    @Test
    fun `readReviewStatus catch path returns retry decision`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.createDirectories(issueDir.resolve(".review-status"))

        val stage = reviewStage().copy(onFailureState = "In Progress")
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to stage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        ).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
    }

    @Test
    fun `writeReviewExhaustion catches io failures`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.createDirectories(issueDir.resolve(".review-exhausted.tmp"))

        val stage = reviewStage().copy(maxReviewAttempts = 1)
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to stage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        ).onCodingComplete(issue())

        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
    }

    @Test
    fun `traceReviewStep catch path is tolerated`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        Files.writeString(issueDir.resolve(".koncerto"), "not-a-dir")

        val decision = passingReviewOrchestrator(workspaceDir).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `buildDefaultReviewPrompt includes identifier and title`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("buildDefaultReviewPrompt", Issue::class.java)
        method.isAccessible = true
        val prompt = method.invoke(orchestrator, issue(id = "1", identifier = "ABC-7")) as String
        assertThat(prompt.contains("ABC-7")).isTrue()
        assertThat(prompt.contains("Test issue")).isTrue()
    }

    @Test
    fun `pass pipeline traces scenario saved when generator succeeds`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        val scenarioYaml = """
            ```yaml demo_scenario
            steps:
              - action: navigate
                url: http://localhost:8080
            ```
        """.trimIndent()
        val generator = DemoScenarioGenerator(
            opencodeCommand = "opencode",
            logger = noopLogger(),
            processRunner = { _, _, _ -> scenarioYaml }
        )
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = generator
        ).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `pass pipeline traces deploy success url`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        val deployer = RecordingProjectDeployer(
            deployResult = DeployResult.success("http://localhost:32768")
        )
        val decision = passingReviewOrchestrator(workspaceDir, deployer = deployer).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
        assertThat(deployer.deployCalls).hasSize(1)
    }

    @Test
    fun `pass pipeline traces demo recording skipped when callback returns null`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")
        val decision = passingReviewOrchestrator(
            workspaceDir,
            onReviewPassed = { _, _ -> null }
        ).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Pass::class)
    }

    @Test
    fun `pass pipeline blocks when demo callback fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val decision = passingReviewOrchestrator(
            workspaceDir,
            onReviewPassed = { _, _ -> throw IllegalStateException("site cannot be reached") }
        ).onCodingComplete(issue())

        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.Blocked::class)
    }

    @Test
    fun `retry path traces review retry step`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "fail")
        val decision = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        ).onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
    }

    @Test
    fun `resolveGitConfigPath returns null for malformed gitdir file`(@TempDir tmpDir: Path) {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".git"), "not-a-gitdir-line")
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("resolveGitConfigPath", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(orchestrator, issueDir) as Path?
        assertThat(result).isNull()
    }

    @Test
    fun `readReviewStatus re-reads empty status file after delay`(@TempDir tmpDir: Path) = runTest {
        val issueDir = tmpDir.resolve("T-1").also { Files.createDirectories(it) }
        val statusFile = issueDir.resolve(".review-status")
        Files.writeString(statusFile, "")
        val writeJob = launch {
            delay(50)
            Files.writeString(statusFile, "pass")
        }
        val orchestrator = passingReviewOrchestrator(tmpDir)
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod(
            "readReviewStatus",
            Path::class.java,
            Continuation::class.java
        )
        method.isAccessible = true
        val passed = suspendCancellableCoroutine<Boolean> { cont ->
            val invokeResult = runCatching { method.invoke(orchestrator, issueDir, cont) }
            invokeResult.onFailure { cont.resumeWith(kotlin.Result.failure(it)) }
            if (invokeResult.isSuccess && invokeResult.getOrNull() !== COROUTINE_SUSPENDED) {
                cont.resume(invokeResult.getOrNull() as Boolean)
            }
        }
        writeJob.join()
        assertThat(passed).isTrue()
    }

    @Test
    fun `cleanupReviewFiles tolerates delete failures on review status directory`(@TempDir tmpDir: Path) {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.createDirectories(issueDir.resolve(".review-status"))
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("cleanupReviewFiles", Path::class.java)
        method.isAccessible = true
        method.invoke(orchestrator, issueDir)
    }

    @Test
    fun `onCodingComplete handles workspace resolution failure gracefully`(@TempDir tmpDir: Path) = runTest {
        val workspaceRootFile = tmpDir.resolve("workspace-root-file")
        Files.writeString(workspaceRootFile, "not-a-directory")
        val state = RuntimeState()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceRootFile, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage()), workspaceRoot = workspaceRootFile.toString()),
            projectSlug = "p",
            runtimeState = state,
            notifier = noopNotifier(),
            logger = noopLogger()
        )
        val decision = orchestrator.onCodingComplete(issue())
        assertThat(decision).isInstanceOf(AutoReviewOrchestrator.ReviewDecision.RetryWithCoding::class)
        assertThat(state.reviewAttempts["issue-1"]).isEqualTo(1)
    }

    @Test
    fun `resolveGitConfigPath returns null when gitdir path is invalid`(@TempDir tmpDir: Path) {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".git"), "gitdir: \u0000")
        val orchestrator = passingReviewOrchestrator(workspaceDir)
        val method = AutoReviewOrchestrator::class.java.getDeclaredMethod("resolveGitConfigPath", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(orchestrator, issueDir) as Path?
        assertThat(result).isNull()
    }

    @Test
    fun `postDetailedReviewAsPrComment posts demo URL to PR when review file missing but demoUrl provided`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")

        var capturedBody: String? = null
        val ghRunner: GhProcessRunner = { command, _ ->
            when {
                command.contains("view") -> GhProcessResult(0, """{"number":16}""")
                command.contains("comment") -> {
                    val bodyFileIndex = command.indexOf("--body-file")
                    if (bodyFileIndex >= 0) {
                        capturedBody = Files.readString(Path.of(command[bodyFileIndex + 1]))
                    }
                    GhProcessResult(0, "https://github.com/acme/widget/pull/16#issuecomment-1")
                }
                else -> GhProcessResult(1, "unknown command")
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
        method.invoke(orchestrator, issue(), ws, 1, "https://demo.example.com/vid")

        assertThat(capturedBody).isNotNull()
        assertThat(capturedBody!!.contains("Watch Demo Recording")).isTrue()
        assertThat(capturedBody!!.contains("https://demo.example.com/vid")).isTrue()
        assertThat(capturedBody!!.contains("Claude Review #1")).isTrue()
    }

    @Test
    fun `postDetailedReviewAsPrComment skips when review file missing and demoUrl is null`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        initGitOrigin(issueDir, "acme/widget")

        var commentCalled = false
        val ghRunner: GhProcessRunner = { command, _ ->
            if (command.contains("comment")) commentCalled = true
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

        assertThat(commentCalled).isFalse()
    }
}
