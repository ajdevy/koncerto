package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(kotlin.time.ExperimentalTime::class)
class Orchestrator(
    private val config: ServiceConfig,
    private val state: RuntimeState,
    private val linear: LinearClient,
    private val workspaces: WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String
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
            fetchAndDispatch()
        } catch (e: Exception) {
            logger.failure("tick_failed", emptyMap(), e)
        }
    }

    private suspend fun reconcile() {
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
                    // still active; keep going
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

    internal suspend fun fetchAndDispatch() {
        val candidates = try {
            linear.fetchCandidateIssues(projectSlug, config.activeStates)
        } catch (e: Exception) {
            logger.failure("fetch_candidates_failed", emptyMap(), e)
            return
        }
        val sorted = candidates
            .filter { !state.running.containsKey(it.id) && it.id !in state.claimed }
            .filter { matchesRequiredLabels(it) }
            .filter { !isBlockedForTodo(it) }
            .sortedWith(
                compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
                    .thenBy { it.createdAt ?: Instant.MAX }
                    .thenBy { it.identifier }
            )

        for (issue in sorted) {
            if (state.availableSlots() <= 0) break
            val perStateLimit = config.maxConcurrentAgentsByState[issue.normalizedState]
            val currentForState = state.running.values.count { it.issue.normalizedState == issue.normalizedState }
            val perStateCap = perStateLimit ?: state.maxConcurrentAgents
            if (currentForState >= perStateCap) continue
            dispatch(issue)
        }
    }

    private fun matchesRequiredLabels(issue: Issue): Boolean {
        if (config.requiredLabels.isEmpty()) return true
        val issueLabels = issue.labels.toSet()
        return config.requiredLabels.all { it.trim().lowercase() in issueLabels }
    }

    private fun isBlockedForTodo(issue: Issue): Boolean {
        if (!issue.normalizedState.equals("todo", ignoreCase = true)) return false
        return issue.blockedBy.any { blocker ->
            val s = blocker.state?.lowercase() ?: return@any true
            config.terminalStates.none { it.equals(s, ignoreCase = true) }
        }
    }

    private fun dispatch(issue: Issue) {
        if (issue.id in state.claimed) return
        state.claimed.add(issue.id)
        logger.info(
            "dispatch_start",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        )
        val prompt = workflowCache.current().promptTemplate
        val attempt: Int? = null
        scope?.launch {
            val result = agentRunner.run(issue, attempt, prompt)
            result.onSuccess {
                state.completed.add(issue.id)
                state.claimed.remove(issue.id)
                logger.info(
                    "dispatch_completed",
                    mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
                )
            }.onFailure { err ->
                scheduleRetry(issue, err.message ?: "unknown")
            }
        }
    }

    private fun scheduleRetry(issue: Issue, error: String) {
        state.running.remove(issue.id)
        val existing = state.retryAttempts[issue.id]
        val nextAttempt = (existing?.attempt ?: 0) + 1
        val delayMs =
            (10_000L * (1L shl (nextAttempt - 1).coerceAtMost(20))).coerceAtMost(config.maxRetryBackoffMs)
        val entry = RetryEntry(
            issue.id, issue.identifier, nextAttempt,
            System.currentTimeMillis() + delayMs, error
        )
        state.retryAttempts[issue.id] = entry
        logger.info(
            "retry_scheduled",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier),
            "attempt" to nextAttempt, "delay_ms" to delayMs
        )
    }

    private fun handleAgentEvent(event: AgentEvent) {
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

    internal suspend fun reconcileForTest() = reconcile()

    internal fun handleAgentEventForTest(event: AgentEvent) = handleAgentEvent(event)

    internal suspend fun scheduleRetryForTest(issue: Issue, error: String) = scheduleRetry(issue, error)
}
