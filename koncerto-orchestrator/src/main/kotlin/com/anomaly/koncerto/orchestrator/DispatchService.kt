package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.TokenUsage
import com.anomaly.koncerto.core.config.NotificationsConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.RoutingRule
import com.anomaly.koncerto.core.config.FollowUpConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.notifications.CompositeNotifier
import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

data class ResolvedAgent(
    val kind: String,
    val command: String?,
    val model: String?
)

class DispatchService(
    val projectConfig: ProjectConfig,
    val state: RuntimeState,
    val linear: LinearClient,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String,
    private val workspaces: WorkspaceManager? = null,
    private val retryExecutor: RetryExecutor = RetryExecutor(projectConfig.agent.maxRetryBackoffMs),
    private val issueProjectMap: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    private val metricsRepository: MetricsRepository? = null,
    val notifier: CompositeNotifier? = null,
    private val notificationsConfig: NotificationsConfig? = null
) {
    val messageStore = AgentMessageStore(logger)

    private val agentIdToIssueId = ConcurrentHashMap<String, String>()
    @Volatile
    var shutdownRequested = false

    suspend fun fetchAndDispatch(scope: CoroutineScope) {
        if (shutdownRequested) return
        val candidates = try {
            linear.fetchCandidateIssues(projectSlug, projectConfig.tracker.activeStates)
        } catch (e: Exception) {
            logger.failure("fetch_candidates_failed", emptyMap(), e)
            return
        }
        val graph = DependencyGraph.build(candidates, projectConfig.tracker.terminalStates)
        val sorted = graph.frontier
            .filter { !state.running.containsKey(it.id) && it.id !in state.claimed }
            .filter { matchesRequiredLabels(it) }
            .filter { !isBlockedForTodo(it) }
            .sortedWith(
                compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
                    .thenBy { it.createdAt ?: Instant.MAX }
                    .thenBy { it.identifier }
            )

        for (candidate in candidates) {
            if (candidate.id !in graph.frontier.map { it.id }
                && !state.running.containsKey(candidate.id)
                && candidate.id !in state.claimed) {
                state.blocked.add(candidate.id)
            }
        }
        for (frontierId in graph.frontier.map { it.id }) {
            state.blocked.remove(frontierId)
        }
        val candidateIds = candidates.map { it.id }.toSet()
        for (id in state.blocked.toTypedArray()) {
            if (id !in candidateIds) state.blocked.remove(id)
        }

        for (issue in sorted) {
            if (state.availableSlots() <= 0) break
            val perStateLimit = projectConfig.agent.maxConcurrentAgentsByState[issue.normalizedState]
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
        if (projectConfig.tracker.requiredLabels.isEmpty()) return true
        val issueLabels = issue.labels.toSet()
        return projectConfig.tracker.requiredLabels.all { it.trim().lowercase() in issueLabels }
    }

    private fun isBlockedForTodo(issue: Issue): Boolean {
        if (!issue.normalizedState.equals("todo", ignoreCase = true)) return false
        return issue.id in state.blocked
    }

    private fun evaluateRoutingRules(issue: Issue): ResolvedAgent? {
        for (rule in projectConfig.agent.routingRules) {
            val matchLabel = rule.ifLabel
            val matchLabelPrefix = rule.ifLabelPrefix
            val matchState = rule.ifState
            val matchPriority = rule.ifPriority
            val matchPriorityMax = rule.ifPriorityMax
            val issuePriority = issue.priority
            val matches = (matchLabel == null || issue.labels.any { it.equals(matchLabel, ignoreCase = true) })
                && (matchLabelPrefix == null || issue.labels.any { it.startsWith(matchLabelPrefix, ignoreCase = true) })
                && (matchState == null || issue.normalizedState.equals(matchState, ignoreCase = true))
                && (matchPriority == null || issuePriority == matchPriority)
                && (matchPriorityMax == null || (issuePriority != null && issuePriority <= matchPriorityMax))
            if (!matches) continue

            val provider = projectConfig.agent.agents[rule.useAgent]
            if (provider == null) {
                logger.warn("routing_rule_agent_not_found", mapOf(
                    "use_agent" to rule.useAgent,
                    "issue_id" to issue.id
                ))
                continue
            }

            logger.info("routing_rule_matched", mapOf(
                "issue_id" to issue.id,
                "rule" to rule.useAgent,
                "agent_kind" to provider.kind
            ))
            return ResolvedAgent(
                kind = provider.kind,
                command = provider.command,
                model = provider.model
            )
        }
        return null
    }

    internal fun resolveAgent(issue: Issue, stageConfig: StageAgentConfig?): ResolvedAgent {
        val stageProvider = stageConfig?.agent?.let { projectConfig.agent.agents[it] }

        val labelProvider = issue.labels.firstNotNullOfOrNull { label ->
            val prefix = "agent:"
            if (label.startsWith(prefix)) projectConfig.agent.agents[label.removePrefix(prefix)] else null
        }

        val result = if (stageProvider != null || labelProvider != null) {
            val baseKind = stageProvider?.kind
                ?: stageConfig?.agentKind
                ?: projectConfig.agent.kind
            val baseCommand = stageProvider?.command
                ?: stageConfig?.command
                ?: projectConfig.agent.command
            val baseModel = stageProvider?.model
                ?: stageConfig?.model

            val finalKind = labelProvider?.kind ?: baseKind
            val finalCommand = labelProvider?.command ?: baseCommand
            val finalModel = labelProvider?.model ?: baseModel

            if (labelProvider == null && stageConfig?.agent != null && projectConfig.agent.agents[stageConfig.agent] == null) {
                logger.warn("agent_provider_not_found", mapOf(
                    "agent_name" to stageConfig.agent,
                    "project_slug" to projectSlug
                ))
            }

            ResolvedAgent(finalKind, finalCommand, finalModel)
        } else {
            val routed = evaluateRoutingRules(issue)
            if (routed != null) routed
            else ResolvedAgent(
                kind = stageConfig?.agentKind ?: projectConfig.agent.kind,
                command = stageConfig?.command ?: projectConfig.agent.command,
                model = stageConfig?.model
            )
        }

        val labelModel = issue.labels.firstNotNullOfOrNull { label ->
            val prefix = "model:"
            if (label.startsWith(prefix)) label.removePrefix(prefix) else null
        }
        return if (labelModel != null) result.copy(model = labelModel) else result
    }

    private fun dispatch(issue: Issue, scope: CoroutineScope, attempt: Int? = null) {
        if (issue.id in state.claimed) return
        state.claimed.add(issue.id)
        issueProjectMap[issue.id] = projectSlug
        logger.info(
            "dispatch_start",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        )

        val stageConfig = projectConfig.agent.stages[issue.normalizedState]
        val prompt = stageConfig?.prompt ?: workflowCache.current().promptTemplate
        val resolved = resolveAgent(issue, stageConfig)

        val extra = mutableMapOf("prompt_source" to if (stageConfig?.prompt != null) "stage" else "global")
        if (resolved.model != null) extra["model"] = resolved.model
        if (attempt != null) extra["attempt"] = attempt.toString()
        logger.info("dispatch_config", extra)

        scope.launch {
            val result = agentRunner.run(
                issue, attempt, prompt, resolved.kind, resolved.command,
                turnTimeoutMs = projectConfig.agent.turnTimeoutMs,
                stallTimeoutMs = projectConfig.agent.stallTimeoutMs
            )
            result.onSuccess {
                state.claimed.remove(issue.id)
                val entry = state.running[issue.id]
                metricsRepository?.updateAfterRun(
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    projectSlug = projectSlug,
                    result = "success",
                    inputTokens = entry?.inputTokens ?: 0,
                    outputTokens = entry?.outputTokens ?: 0,
                    totalTokens = entry?.totalTokens ?: 0
                )
                val clarificationContent = readClarification(issue.identifier)
                if (clarificationContent != null) {
                    handleClarification(issue.id, clarificationContent)
                } else {
                    state.completed.add(issue.id)
                    logger.info(
                        "dispatch_completed",
                        mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
                    )
                    transitionOnComplete(issue, stageConfig)
                    if (notificationsConfig?.onCompleted == true && notifier != null) {
                        notifier.send(NotificationEvent.AgentCompleted(
                            projectSlug = projectSlug,
                            issueId = issue.id,
                            issueIdentifier = issue.identifier,
                            title = issue.title,
                            tokenUsage = state.running[issue.id]?.let {
                                TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens)
                            }
                        ))
                    }
                }
            }.onFailure { err ->
                metricsRepository?.updateAfterRun(
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    projectSlug = projectSlug,
                    result = "failure",
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0
                )
                scheduleRetry(issue, err.message ?: "unknown")
                if (notificationsConfig?.onFailed == true && notifier != null) {
                    notifier.send(NotificationEvent.AgentFailed(
                        projectSlug = projectSlug,
                        issueId = issue.id,
                        issueIdentifier = issue.identifier,
                        title = issue.title,
                        error = err.message ?: "unknown"
                    ))
                }
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

    internal suspend fun transitionOnComplete(issue: Issue, stageConfig: StageAgentConfig?) {
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
            return
        }

        val followUp = stageConfig?.followUp ?: return
        val renderedTitle = FollowUpRenderer.render(followUp.titleTemplate, issue)
        val renderedDescription = followUp.descriptionTemplate?.let { FollowUpRenderer.render(it, issue) }

        val created = linear.createIssue(
            projectSlug, renderedTitle, followUp.state,
            renderedDescription, followUp.labels
        )
        if (created == null) {
            logger.warn("follow_up_creation_failed", mapOf("source_issue_id" to issue.id))
            return
        }

        logger.info("follow_up_created", mapOf(
            "source_issue_id" to issue.id,
            "source_identifier" to issue.identifier,
            "follow_up_id" to created.id,
            "follow_up_identifier" to created.identifier
        ))

        if (followUp.linkType.isNotBlank()) {
            val linked = linear.createLink(issue.id, created.id, followUp.linkType)
            if (!linked) {
                logger.warn("follow_up_link_failed", mapOf(
                    "source" to issue.id,
                    "target" to created.id,
                    "link_type" to followUp.linkType
                ))
            }
        }
    }

    private suspend fun readClarification(identifier: String): String? {
        val ws = workspaces ?: return null
        val workspace = runCatching { ws.ensureWorkspace(identifier) }.getOrNull() ?: return null
        val path = workspace.path.resolve(".koncerto").resolve("clarification.md")
        return if (Files.exists(path)) runCatching { Files.readString(path) }.getOrNull() else null
    }

    suspend fun handleClarification(issueId: String, clarificationContent: String) {
        val issue = linear.fetchIssueById(issueId) ?: return
        logger.info("clarification_received", mapOf(
            "issue_id" to issueId,
            "issue_identifier" to issue.identifier
        ))

        linear.createComment(issueId, clarificationContent)

        val blockedStateId = linear.resolveStateId(projectSlug, projectConfig.tracker.blockedState)
        if (blockedStateId != null) {
            linear.updateIssueState(issueId, blockedStateId)
            logger.info("state_transitioned", mapOf(
                "issue_id" to issueId,
                "from_state" to issue.state,
                "to_state" to projectConfig.tracker.blockedState
            ))
        } else {
            logger.warn("blocked_state_not_found", mapOf("blocked_state" to projectConfig.tracker.blockedState))
        }

        val creator = issue.creator
        val assigneeId = when {
            creator != null && !creator.isBot -> creator.id
            projectConfig.tracker.projectAdmin != null -> projectConfig.tracker.projectAdmin
            else -> null
        }
        if (assigneeId != null) {
            linear.updateIssueAssignee(issueId, assigneeId)
            logger.info("assignee_updated", mapOf(
                "issue_id" to issueId,
                "assignee_id" to assigneeId
            ))
        }

        state.blocked.add(issueId)

        if (notificationsConfig?.onClarification == true && notifier != null) {
            notifier.send(NotificationEvent.ClarificationRequested(
                projectSlug = projectSlug,
                issueId = issueId,
                issueIdentifier = issue.identifier,
                title = issue.title
            ))
        }
    }

    fun registerAgent(agentId: String, issueId: String) {
        agentIdToIssueId[agentId] = issueId
        logger.debug("agent_registered", mapOf("agent_id" to agentId, "issue_id" to issueId))
    }

    fun unregisterAgent(agentId: String) {
        agentIdToIssueId.remove(agentId)
        messageStore.clearAgentMessages(agentId)
        logger.debug("agent_unregistered", mapOf("agent_id" to agentId))
    }

    fun sendAgentMessage(fromAgentId: String, toAgentId: String, payload: String): String {
        val messageId = messageStore.sendMessage(fromAgentId, toAgentId, payload)
        logger.info("agent_message_routed", mapOf(
            "message_id" to messageId,
            "from" to fromAgentId,
            "to" to toAgentId
        ))
        return messageId
    }

    fun getAgentMessages(agentId: String): List<AgentMessage> {
        return messageStore.getUnacknowledgedMessages(agentId)
    }

    fun ackAgentMessage(messageId: String): Boolean {
        return messageStore.ackMessage(messageId)
    }

    fun agentMessageFlow(agentId: String): Flow<AgentMessage> {
        return messageStore.waitForMessages(agentId)
    }

    fun resolveAgentIdToIssueId(agentId: String): String? {
        return agentIdToIssueId[agentId]
    }
}
