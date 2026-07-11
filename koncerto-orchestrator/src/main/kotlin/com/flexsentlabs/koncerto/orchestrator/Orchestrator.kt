package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.errors.AgentErrorType
import com.flexsentlabs.koncerto.core.audit.AuditLogger
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.LimitCooldownTracker
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.orchestrator.SubtaskOrchestrator
import com.flexsentlabs.koncerto.orchestrator.WorkplanParser
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Orchestrator(
    private val config: ServiceConfig,
    private val linearClientFactory: (ProjectConfig) -> LinearClient,
    private val workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val scope: CoroutineScope,
    private val runtimeStates: Map<String, RuntimeState> = emptyMap(),
    private val metricsRepository: MetricsRepository? = null,
    private val notifier: CompositeNotifier? = null,
    private val subtaskOrchestrator: SubtaskOrchestrator? = null,
    private val workplanParser: WorkplanParser? = null,
    private val auditLogger: AuditLogger? = null,
    private val autoReviewOrchestratorFactory: ((ProjectConfig, RuntimeState) -> AutoReviewOrchestrator)? = null,
    // Pre-implementation gate detector. Nullable + injected (like autoReviewOrchestratorFactory) so
    // core Orchestrator tests never trigger a real LLM call; production wires the real one in Beans.
    private val testResourceDetector: TestResourceRequirementDetector? = null
) {
    internal val issueProjectMap = ConcurrentHashMap<String, String>()

    @Volatile
    var shutdownRequested = false

    data class ProjectRuntime(
        val config: ProjectConfig,
        val linear: LinearClient,
        val workspaces: WorkspaceManager,
        val state: RuntimeState,
        val dispatch: DispatchService,
        val limitCooldown: LimitCooldownTracker = LimitCooldownTracker()
    )

    val projects: Map<String, ProjectRuntime>

    init {
        projects = config.projects.mapValues { (slug, pc) ->
            val state = runtimeStates[slug] ?: RuntimeState().also {
                it.pollIntervalMs = config.pollIntervalMs
                it.maxConcurrentAgents = pc.agent.maxConcurrentAgents
            }
            val linear = linearClientFactory(pc)
            val ws = workspaceManagerFactory(pc)
            val projectGitWorkflow = GitWorkflow(config.gitConfig.forProject(pc), logger)
            val autoReview = autoReviewOrchestratorFactory?.invoke(pc, state)
            val dispatch = DispatchService(
                projectConfig = pc,
                state = state,
                linear = linear,
                agentRunner = agentRunner,
                workflowCache = workflowCache,
                logger = logger,
                workspaces = ws,
                issueProjectMap = issueProjectMap,
                metricsRepository = metricsRepository,
                notifier = notifier,
                notificationsConfig = pc.notifications,
                subtaskOrchestrator = subtaskOrchestrator,
                workplanParser = workplanParser,
                auditLogger = auditLogger,
                autoReviewOrchestrator = autoReview,
                gitWorkflow = projectGitWorkflow,
                testResourceDetector = testResourceDetector
            )
            ProjectRuntime(pc, linear, ws, state, dispatch)
        }
    }

    private var loopJob: Job? = null

    fun start() {
        loopJob = scope.launch {
            launch {
                agentRunner.events().collect { ev ->
                    handleAgentEvent(ev)
                }
            }
            tickLoop()
        }
    }

    fun stop() {
        loopJob?.cancel()
    }

    fun restart() {
        loopJob?.cancel()
        shutdownRequested = false
        scope.launch {
            for ((_, pr) in projects) {
                pr.state.clearAll()
            }
        }
        loopJob = scope.launch { tickLoop() }
    }

    fun requestShutdown(): Boolean {
        shutdownRequested = true
        return runningAgentsCount() > 0
    }

    fun runningAgentsCount(): Int = projects.values.sumOf { it.state.running.size }

    internal suspend fun tickLoop() {
        while (true) {
            if (shutdownRequested) {
                delay(config.pollIntervalMs)
                return
            }
            tick()
            delay(config.pollIntervalMs)
        }
    }

    private suspend fun tick() {
        for ((slug, pr) in projects) {
            try {
                reconcile(slug, pr)
                runPreflight(pr)
                pr.dispatch.dispatchDueRetries(scope)
                pr.dispatch.dispatchDueLimitPauses(scope)
                pr.dispatch.fetchAndDispatch(scope)
            } catch (e: Exception) {
                logger.failure("tick_failed", mapOf("project" to slug), e)
            }
        }
    }

    internal suspend fun reconcile(slug: String, pr: ProjectRuntime) {
        val state = pr.state
        if (state.running.isEmpty()) return
        val runningIds = state.running.keys.toList()
        val blockerIds = state.running.values
            .flatMap { it.issue.blockedBy.mapNotNull { it.id } }
        val ids = (runningIds + blockerIds).distinct()
        try {
            val states = pr.linear.fetchIssueStatesByIds(ids)
            for ((id, trackerState) in states) {
                val entry = state.running[id] ?: continue
                if (pr.config.tracker.terminalStates.any { it.equals(trackerState, ignoreCase = true) }) {
                    logger.info(
                        "stop_terminal",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.releaseClaim(id)
                    state.removeOutput(id)
                    try { pr.workspaces.removeWorkspace(entry.issue.identifier) } catch (_: Exception) {}
                } else if (pr.config.tracker.activeStates.any { it.equals(trackerState, ignoreCase = true) }) {
                } else {
                    logger.info(
                        "stop_non_active",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.releaseClaim(id)
                    state.removeOutput(id)
                    val nc = pr.config.notifications
                    if (nc.onStalled && pr.dispatch.notifier != null) {
                        pr.dispatch.notifier.send(NotificationEvent.AgentStalled(
                            projectSlug = pr.config.tracker.projectSlug,
                            issueId = id,
                            issueIdentifier = entry.issue.identifier,
                            title = entry.issue.title,
                            stallDurationMs = 0
                        ))
                    }
                }
            }
            for ((id, entry) in state.running) {
                if (entry.issue.blockedBy.isEmpty()) continue
                val allBlockersResolved = entry.issue.blockedBy.all { blocker ->
                    val blockerId = blocker.id
                    if (blockerId == null) return@all true
                    // Only treat this as a fresh unblock transition if Koncerto was itself
                    // tracking the blocker as running — otherwise a blocker resolved long
                    // before this issue was ever dispatched (the normal case for any real
                    // dependency chain) would match "resolved" on every single tick forever,
                    // repeatedly wiping this issue's in-flight dispatch and workspace.
                    if (blockerId !in runningIds) return@all false
                    val blockerState = states[blockerId]
                    if (blockerState == null) return@all false
                    pr.config.tracker.terminalStates.any { it.equals(blockerState, ignoreCase = true) }
                }
                if (allBlockersResolved) {
                    logger.info(
                        "unblocked",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier)
                    )
                    state.running.remove(id)
                    state.releaseClaim(id)
                    state.removeBlocked(id)
                    state.removeOutput(id)
                    try { pr.workspaces.removeWorkspace(entry.issue.identifier) } catch (_: Exception) {}
                }
            }
            detectZombies(state, pr)
        } catch (e: Exception) {
            logger.warn("reconcile_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }

    private suspend fun detectZombies(state: RuntimeState, pr: ProjectRuntime) {
        val now = Instant.now()
        val heartbeatTimeout = pr.config.agent.heartbeatTimeoutMs
        for ((id, entry) in state.running) {
            if (!entry.issue.normalizedState.equals("in progress", ignoreCase = true)) continue
            val lastSignal: Instant = entry.lastHeartbeatAt ?: entry.startedAt
            if (Duration.between(lastSignal, now).toMillis() < heartbeatTimeout) continue
            logger.info(
                "zombie_detected",
                mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier)
            )
            try {
                val todoStateId = pr.linear.resolveStateId(pr.config.tracker.projectSlug, "Todo")
                if (todoStateId != null) {
                    pr.linear.updateIssueState(id, todoStateId)
                }
            } catch (e: Exception) {
                logger.warn("zombie_state_reset_failed", mapOf("issue_id" to id), "error" to (e.message ?: "unknown"))
            }
            state.running.remove(id)
            state.releaseClaim(id)
            state.removeOutput(id)
            try { pr.workspaces.removeWorkspace(entry.issue.identifier) } catch (_: Exception) {}
        }
    }

    private fun runPreflight(pr: ProjectRuntime) {
        val cmd = pr.config.agent.command ?: pr.config.agent.kind
        if (pr.config.tracker.kind.isNullOrBlank() || pr.config.tracker.apiKey.isNullOrBlank()
            || pr.config.tracker.projectSlug.isNullOrBlank() || cmd.isBlank()
        ) {
            logger.warn("preflight_invalid", emptyMap())
        }
    }

    internal suspend fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnCompleted -> {
                event.usage?.let { u ->
                    val state = projects.values.firstOrNull { pr ->
                        pr.state.running.values.any { it.threadId == event.threadId }
                    }?.state
                    if (state == null) {
                        logger.warn(
                            "turn_completed_no_running_entry",
                            mapOf("thread_id" to event.threadId)
                        )
                        return@let
                    }
                    state.addTokenTotals(u.inputTokens, u.outputTokens, u.totalTokens)
                }
            }
            is AgentEvent.LimitDetected -> {
                for ((slug, pr) in projects) {
                    val entry = pr.state.running[event.issueId] ?: continue
                    val nc = pr.config.notifications
                    if (nc.onLimit.isEmpty() || pr.dispatch.notifier == null) continue
                    val errorTypeName = event.agentError.type::class.simpleName ?: "Unknown"
                    if (!pr.limitCooldown.shouldSend(errorTypeName, event.issueId)) continue
                    pr.dispatch.notifier!!.send(NotificationEvent.LimitDetected(
                        projectSlug = pr.config.tracker.projectSlug,
                        issueId = event.issueId,
                        issueIdentifier = entry.issue.identifier,
                        title = entry.issue.title,
                        errorType = errorTypeName,
                        summary = "${errorTypeName}: ${event.agentError.message}",
                        retryAfterMs = (event.agentError.type as? AgentErrorType.RateLimitError)?.retryAfterMs
                    ))
                }
            }
            else -> {}
        }
    }
}
