package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.metrics.MetricsRepository
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
    private val linear: LinearClient,
    private val workspaces: WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val runtimeStates: Map<String, RuntimeState> = emptyMap(),
    private val metricsRepository: MetricsRepository? = null
) {
    internal val issueProjectMap = ConcurrentHashMap<String, String>()

    internal val dispatchServices: Map<String, DispatchService> =
        config.projects.mapValues { (slug, projectConfig) ->
            val state = runtimeStates[slug] ?: RuntimeState().also {
                it.pollIntervalMs = config.pollIntervalMs
                it.maxConcurrentAgents = projectConfig.agent.maxConcurrentAgents
            }
            createDispatchService(projectConfig, state, slug)
        }

    private var loopJob: Job? = null
    internal var scope: CoroutineScope? = null

    internal fun createDispatchService(
        projectConfig: ProjectConfig,
        state: RuntimeState,
        slug: String
    ): DispatchService = DispatchService(
        projectConfig, state, linear, agentRunner, workflowCache, logger, slug, workspaces,
        issueProjectMap = issueProjectMap,
        metricsRepository = metricsRepository
    )

    fun start(scope: CoroutineScope) {
        this.scope = scope
        loopJob = scope.launch {
            launch {
                agentRunner.events().collect { ev ->
                    handleAgentEvent(ev)
                }
            }
            tick()
            while (isActive) {
                delay(config.pollIntervalMs)
                tick()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
    }

    private suspend fun tick() {
        for ((slug, projectConfig) in config.projects) {
            val ds = dispatchServices[slug]
            if (ds == null) {
                logger.warn("tick_no_dispatch_service", mapOf("project_slug" to slug))
                continue
            }
            val state = ds.state
            try {
                reconcile(state, projectConfig)
                runPreflight(projectConfig)
                ds.dispatchDueRetries(scope!!)
                ds.fetchAndDispatch(scope!!)
            } catch (e: Exception) {
                logger.failure("tick_failed", mapOf("project" to slug), e)
            }
        }
    }

    internal suspend fun reconcile(state: RuntimeState, projectConfig: ProjectConfig) {
        if (state.running.isEmpty()) return
        val ids = state.running.keys.toList()
        try {
            val states = linear.fetchIssueStatesByIds(ids)
            for ((id, trackerState) in states) {
                val entry = state.running[id] ?: continue
                if (projectConfig.tracker.terminalStates.any { it.equals(trackerState, ignoreCase = true) }) {
                    logger.info(
                        "stop_terminal",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.claimed.remove(id)
                    state.removeOutput(id)
                    try {
                        workspaces.removeWorkspace(entry.issue.identifier)
                    } catch (_: Exception) {
                    }
                } else if (projectConfig.tracker.activeStates.any { it.equals(trackerState, ignoreCase = true) }) {
                } else {
                    logger.info(
                        "stop_non_active",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.claimed.remove(id)
                    state.removeOutput(id)
                }
            }
        } catch (e: Exception) {
            logger.warn("reconcile_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }

    private fun runPreflight(projectConfig: ProjectConfig) {
        val cmd = projectConfig.agent.command ?: projectConfig.agent.kind
        if (projectConfig.tracker.kind.isNullOrBlank() || projectConfig.tracker.apiKey.isNullOrBlank()
            || projectConfig.tracker.projectSlug.isNullOrBlank() || cmd.isBlank()
        ) {
            logger.warn("preflight_invalid", emptyMap())
        }
    }

    internal fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnCompleted -> {
                event.usage?.let { u ->
                    val state = dispatchServices.values.firstOrNull { ds ->
                        ds.state.running.values.any { it.threadId == event.threadId }
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
