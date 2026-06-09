package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workflow.WorkflowCache
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DispatchService(
    private val config: ServiceConfig,
    private val state: RuntimeState,
    private val linear: LinearClient,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String,
    private val retryExecutor: RetryExecutor = RetryExecutor(config.maxRetryBackoffMs)
) {
    suspend fun fetchAndDispatch(scope: CoroutineScope) {
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
            dispatch(issue, scope)
        }
    }

    fun scheduleRetry(issue: Issue, error: String) {
        state.running.remove(issue.id)
        val previousAttempt = state.retryAttempts[issue.id]?.attempt ?: 0
        val entry = retryExecutor.createEntry(issue.id, issue.identifier, previousAttempt, error)
        state.retryAttempts[issue.id] = entry
        logger.info(
            "retry_scheduled",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier),
            "attempt" to entry.attempt, "delay_ms" to (entry.dueAtMs - System.currentTimeMillis())
        )
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

    private fun dispatch(issue: Issue, scope: CoroutineScope, attempt: Int? = null) {
        if (issue.id in state.claimed) return
        state.claimed.add(issue.id)
        logger.info(
            "dispatch_start",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        )

        val stageConfig = config.stages[issue.normalizedState]
        val prompt = stageConfig?.prompt ?: workflowCache.current().promptTemplate
        val agentKind = stageConfig?.agentKind ?: config.agentKind
        val command = stageConfig?.command
        val model = resolveModel(issue, stageConfig)

        val extra = mutableMapOf("prompt_source" to if (stageConfig?.prompt != null) "stage" else "global")
        if (model != null) extra["model"] = model
        if (attempt != null) extra["attempt"] = attempt.toString()
        logger.info("dispatch_config", extra)

        scope.launch {
            val result = agentRunner.run(issue, attempt, prompt, agentKind, command)
            result.onSuccess {
                state.completed.add(issue.id)
                state.claimed.remove(issue.id)
                logger.info(
                    "dispatch_completed",
                    mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
                )
                transitionOnComplete(issue, stageConfig)
            }.onFailure { err ->
                scheduleRetry(issue, err.message ?: "unknown")
            }
        }
    }

    suspend fun dispatchDueRetries(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val due = state.retryAttempts.filter { it.value.dueAtMs <= now }
        for ((issueId, entry) in due) {
            if (state.availableSlots() <= 0) break
            if (state.running.containsKey(issueId) || issueId in state.claimed) continue
            val issue = try {
                linear.fetchIssueById(issueId)
            } catch (_: Exception) {
                null
            }
            if (issue == null) {
                logger.warn("retry_fetch_failed", mapOf("issue_id" to issueId))
                state.retryAttempts.remove(issueId)
                continue
            }
            state.retryAttempts.remove(issueId)
            state.running.remove(issueId)
            dispatch(issue, scope, entry.attempt)
        }
    }

    private suspend fun transitionOnComplete(issue: Issue, stageConfig: StageAgentConfig?) {
        val targetState = stageConfig?.onCompleteState ?: return
        try {
            val stateId = linear.resolveStateId(projectSlug, targetState)
            if (stateId == null) {
                logger.warn("state_not_found", mapOf(
                    "issue_id" to issue.id,
                    "target_state" to targetState
                ))
                return
            }
            linear.updateIssueState(issue.id, stateId)
            logger.info("state_transitioned", mapOf(
                "issue_id" to issue.id,
                "from_state" to issue.state,
                "to_state" to targetState
            ))
        } catch (e: Exception) {
            logger.failure("state_transition_failed", mapOf(
                "issue_id" to issue.id,
                "target_state" to targetState
            ), e)
        }
    }

    private fun resolveModel(issue: Issue, stageConfig: StageAgentConfig?): String? {
        val labelModel = issue.labels.firstNotNullOfOrNull { label ->
            val prefix = "model:"
            if (label.startsWith(prefix)) label.removePrefix(prefix) else null
        }
        return labelModel ?: stageConfig?.model
    }
}
