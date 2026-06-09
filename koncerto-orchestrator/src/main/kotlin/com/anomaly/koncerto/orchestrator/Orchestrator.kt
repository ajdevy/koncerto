package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workflow.WorkflowCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Orchestrator(
    private val config: ServiceConfig,
    private val state: RuntimeState,
    private val linear: LinearClient,
    private val workspaces: WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String,
    internal val dispatchService: DispatchService = DispatchService(
        config, state, linear, agentRunner, workflowCache, logger, projectSlug, workspaces
    )
) {
    private var loopJob: Job? = null
    internal var scope: CoroutineScope? = null

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
                delay(state.pollIntervalMs)
                tick()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
    }

    private suspend fun tick() {
        try {
            reconcile()
            runPreflight()
            dispatchService.dispatchDueRetries(scope!!)
            dispatchService.fetchAndDispatch(scope!!)
        } catch (e: Exception) {
            logger.failure("tick_failed", emptyMap(), e)
        }
    }

    internal suspend fun reconcile() {
        if (state.running.isEmpty()) return
        val ids = state.running.keys.toList()
        try {
            val states = linear.fetchIssueStatesByIds(ids)
            for ((id, trackerState) in states) {
                val entry = state.running[id] ?: continue
                if (config.terminalStates.any { it.equals(trackerState, ignoreCase = true) }) {
                    logger.info(
                        "stop_terminal",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.claimed.remove(id)
                    try {
                        workspaces.removeWorkspace(entry.issue.identifier)
                    } catch (_: Exception) {
                    }
                } else if (config.activeStates.any { it.equals(trackerState, ignoreCase = true) }) {
                } else {
                    logger.info(
                        "stop_non_active",
                        mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState
                    )
                    state.running.remove(id)
                    state.claimed.remove(id)
                }
            }
        } catch (e: Exception) {
            logger.warn("reconcile_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }

    private fun runPreflight() {
        val cmd = if (config.agentKind == "opencode") config.opencodeCommand else config.codexCommand
        if (config.trackerKind.isNullOrBlank() || config.trackerApiKey.isNullOrBlank()
            || config.trackerProjectSlug.isNullOrBlank() || cmd.isBlank()
        ) {
            logger.warn("preflight_invalid", emptyMap())
        }
    }

    internal fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnCompleted -> {
                event.usage?.let { u ->
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
