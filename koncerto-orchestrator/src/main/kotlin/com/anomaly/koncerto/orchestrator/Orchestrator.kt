package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.notifications.CompositeNotifier
import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workflow.WorkflowCache
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
    private val notifier: CompositeNotifier? = null
) {
    internal val issueProjectMap = ConcurrentHashMap<String, String>()

    @Volatile
    var shutdownRequested = false

    data class ProjectRuntime(
        val config: ProjectConfig,
        val linear: LinearClient,
        val workspaces: WorkspaceManager,
        val state: RuntimeState,
        val dispatch: DispatchService
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
            val dispatch = DispatchService(
                projectConfig = pc,
                state = state,
                linear = linear,
                agentRunner = agentRunner,
                workflowCache = workflowCache,
                logger = logger,
                projectSlug = slug,
                workspaces = ws,
                issueProjectMap = issueProjectMap,
                metricsRepository = metricsRepository,
                notifier = notifier,
                notificationsConfig = pc.notifications
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
        shutdownRequested = false
        for ((_, pr) in projects) {
            pr.state.clearAll()
        }
        scope.launch { tickLoop() }
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
                continue
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
                pr.dispatch.fetchAndDispatch(scope)
            } catch (e: Exception) {
                logger.failure("tick_failed", mapOf("project" to slug), e)
            }
        }
    }

    internal suspend fun reconcile(slug: String, pr: ProjectRuntime) {
        val state = pr.state
        if (state.running.isEmpty()) return
        val ids = state.running.keys.toList()
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
                    state.claimed.remove(id)
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
                    state.claimed.remove(id)
                    state.removeOutput(id)
                    val nc = pr.config.notifications
                    if (nc.onStalled && pr.dispatch.notifier != null) {
                        pr.dispatch.notifier.send(NotificationEvent.AgentStalled(
                            projectSlug = slug,
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
                    val blockerState = states[blockerId]
                    if (blockerState == null) return@all true
                    pr.config.tracker.terminalStates.any { it.equals(blockerState, ignoreCase = true) }
                }
                if (allBlockersResolved) {
                    logger.info(
                        "unblocked",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier)
                    )
                    state.running.remove(id)
                    state.claimed.remove(id)
                    state.blocked.remove(id)
                    state.removeOutput(id)
                    try { pr.workspaces.removeWorkspace(entry.issue.identifier) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            logger.warn("reconcile_failed", emptyMap(), "error" to (e.message ?: "unknown"))
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

    internal fun handleAgentEvent(event: AgentEvent) {
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
                    state.tokenTotals = state.tokenTotals.copy(
                        inputTokens = state.tokenTotals.inputTokens + u.inputTokens,
                        outputTokens = state.tokenTotals.outputTokens + u.outputTokens,
                        totalTokens = state.tokenTotals.totalTokens + u.totalTokens
                    )
                }
            }
            else -> {}
        }
    }
}
