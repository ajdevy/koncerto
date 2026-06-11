package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.TokenUsage
import com.anomaly.koncerto.orchestrator.RunningEntry
import com.anomaly.koncerto.core.config.NotificationsConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.RoutingRule
import com.anomaly.koncerto.core.config.FollowUpConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.config.WorkplanConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.quota.QuotaConfig
import com.anomaly.koncerto.core.quota.QuotaEnforcer
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.tenant.TenantContext
import com.anomaly.koncerto.core.tenant.TenantResolver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

private const val AGENT_LABEL_PREFIX = "agent:"
private const val MODEL_LABEL_PREFIX = "model:"
private const val DEFAULT_PRIORITY = Int.MAX_VALUE
private val DEFAULT_CREATED_AT = Instant.MAX

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
    private val notificationsConfig: NotificationsConfig? = null,
    private val subtaskOrchestrator: SubtaskOrchestrator? = null,
    private val workplanParser: WorkplanParser? = null,
    private val tenantResolver: TenantResolver? = null,
    private val quotaEnforcer: QuotaEnforcer? = null,
    private val quotaConfig: QuotaConfig? = null,
    private val crossProjectChainer: CrossProjectChainer? = null
) {
    val messageStore = AgentMessageStore(logger)

    private val agentIdToIssueId = ConcurrentHashMap<String, String>()
    @Volatile
    var shutdownRequested = false

    suspend fun fetchAndDispatch(scope: CoroutineScope) {
        if (shutdownRequested) return
        val candidates = try {
            scope.coroutineContext.ensureActive()
            linear.fetchCandidateIssues(projectSlug, projectConfig.tracker.activeStates)
        } catch (e: Exception) {
            logger.failure("fetch_candidates_failed", emptyMap(), e)
            return
        }
        val graph = DependencyGraph.build(candidates, projectConfig.tracker.terminalStates)
        val sorted = graph.frontier
            .filter { !state.running.containsKey(it.id) && !state.isClaimed(it.id) }
            .filter { matchesRequiredLabels(it) }
            .filter { !isBlockedForTodo(it) }
            .sortedWith(
                compareBy<Issue>({ it.priority ?: DEFAULT_PRIORITY })
                    .thenBy { it.createdAt ?: DEFAULT_CREATED_AT }
                    .thenBy { it.identifier }
            )

        for (candidate in candidates) {
            scope.coroutineContext.ensureActive()
            if (candidate.id !in graph.frontier.map { it.id }
                && !state.running.containsKey(candidate.id)
                && !state.isClaimed(candidate.id)) {
                state.addBlocked(candidate.id)
            }
        }
        for (frontierId in graph.frontier.map { it.id }) {
            scope.coroutineContext.ensureActive()
            state.removeBlocked(frontierId)
        }
        val candidateIds = candidates.map { it.id }.toSet()
        for (id in state.blocked.keys.toTypedArray()) {
            scope.coroutineContext.ensureActive()
            if (id !in candidateIds) state.removeBlocked(id)
        }

        for (issue in sorted) {
            scope.coroutineContext.ensureActive()
            if (state.availableSlots() <= 0) break
            val perStateLimit = projectConfig.agent.maxConcurrentAgentsByState[issue.normalizedState]
            val currentForState = state.running.values.count { it.issue.normalizedState == issue.normalizedState }
            val perStateCap = perStateLimit ?: state.maxConcurrentAgents
            if (currentForState >= perStateCap) continue
            if (projectConfig.agent.sequentialMode) {
                dispatchSequential(issue, scope)
            } else {
                dispatch(issue, scope)
            }
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
        return state.isBlocked(issue.id)
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
            if (label.startsWith(AGENT_LABEL_PREFIX)) projectConfig.agent.agents[label.removePrefix(AGENT_LABEL_PREFIX)] else null
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
            if (label.startsWith(MODEL_LABEL_PREFIX)) label.removePrefix(MODEL_LABEL_PREFIX) else null
        }
        return if (labelModel != null) result.copy(model = labelModel) else result
    }

    private suspend fun handleNormalCompletion(
        issue: Issue,
        stageConfig: StageAgentConfig?,
        entry: RunningEntry?
    ) {
        val clarificationContent = readClarification(issue.identifier)
        if (clarificationContent != null) {
            handleClarification(issue.id, clarificationContent)
        } else {
            state.completed[issue.id] = true
            state.removeOutput(issue.id)
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
                    tokenUsage = entry?.let {
                        TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens)
                    }
                ))
            }
        }
    }

    private fun dispatch(issue: Issue, scope: CoroutineScope, attempt: Int? = null) {
        val execData = prepareDispatch(issue, attempt) ?: return
        scope.launch {
            runIssueExecution(scope, execData, issue)
        }
    }

    private suspend fun dispatchSequential(issue: Issue, scope: CoroutineScope, attempt: Int? = null) {
        val execData = prepareDispatch(issue, attempt) ?: return
        runIssueExecution(scope, execData, issue)
    }

    private data class DispatchExecutionData(
        val stageConfig: StageAgentConfig?,
        val prompt: String,
        val resolved: ResolvedAgent,
        val threadId: String,
        val turnId: String,
        val attempt: Int?
    )

    private fun prepareDispatch(issue: Issue, attempt: Int?): DispatchExecutionData? {
        logger.info(
            "dispatch_start",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        )
        val stageConfig = projectConfig.agent.stages[issue.normalizedState]
        val prompt = stageConfig?.prompt ?: workflowCache.current().promptTemplate
        val resolved = resolveAgent(issue, stageConfig)

        val extra = mutableMapOf("prompt_source" to if (stageConfig?.prompt != null) "stage" else "global")
        if (resolved.model != null) extra["model"] = resolved.model
        logger.info("dispatch_config", extra)

        val threadId = java.util.UUID.randomUUID().toString()
        val turnId = java.util.UUID.randomUUID().toString()
        val tenantContext = tenantResolver?.resolveTenant(projectSlug, projectConfig)
        val contextMap = mutableMapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        if (tenantContext != null) {
            contextMap["tenant_id"] = tenantContext.tenantId.value
            contextMap["tenant_tier"] = tenantContext.tier
        }
        logger.info("dispatch_context", contextMap)

        val entry = RunningEntry(
            issue = issue,
            threadId = threadId,
            turnId = turnId,
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            tenantContext = tenantContext
        )
        state.running[issue.id] = entry

        return DispatchExecutionData(stageConfig, prompt, resolved, threadId, turnId, attempt)
    }

    private suspend fun runIssueExecution(scope: CoroutineScope, data: DispatchExecutionData, issue: Issue) {
        if (!state.tryClaim(issue.id)) {
            state.running.remove(issue.id)
            return
        }
        issueProjectMap[issue.id] = projectSlug
        agentIdToIssueId[data.threadId] = issue.id

        val quotaAcquired = projectConfig.quota?.let { quotaEnforcer?.tryAcquire(projectSlug, it) } == true

        val result = agentRunner.run(
            issue, data.attempt, data.prompt, data.resolved.kind, data.resolved.command,
            turnTimeoutMs = projectConfig.agent.turnTimeoutMs,
            stallTimeoutMs = projectConfig.agent.stallTimeoutMs
        )

        val eventCollector = scope.launch {
            agentRunner.events().collect { event ->
                if (event is AgentEvent.TurnCompleted) {
                    event.usage?.let { usage ->
                        val current = state.running[issue.id]
                        current?.let { updated ->
                            state.running[issue.id] = updated.copy(
                                inputTokens = updated.inputTokens + usage.inputTokens,
                                outputTokens = updated.outputTokens + usage.outputTokens,
                                totalTokens = updated.totalTokens + usage.totalTokens,
                                turnCount = updated.turnCount + 1
                            )
                        }
                    }
                }
            }
        }

        try {
            result.onSuccess {
                scope.coroutineContext.ensureActive()
                eventCollector.cancel()
                state.releaseClaim(issue.id)
                agentIdToIssueId.remove(data.threadId)
                issueProjectMap.remove(issue.id)
                val finalEntry = state.running.remove(issue.id)
                state.removeOutput(issue.id)
                metricsRepository?.updateAfterRun(
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    projectSlug = projectSlug,
                    result = "success",
                    inputTokens = finalEntry?.inputTokens ?: 0,
                    outputTokens = finalEntry?.outputTokens ?: 0,
                    totalTokens = finalEntry?.totalTokens ?: 0
                )
                handleWorkplanIfPresent(issue, data.stageConfig, finalEntry)
                if (quotaAcquired) quotaEnforcer?.release(projectSlug)
                handleCrossProjectFollowUp(scope, issue, data.stageConfig)
                handleNormalCompletion(issue, data.stageConfig, finalEntry)
            }.onFailure { err ->
                scope.coroutineContext.ensureActive()
                eventCollector.cancel()
                state.releaseClaim(issue.id)
                agentIdToIssueId.remove(data.threadId)
                issueProjectMap.remove(issue.id)
                state.running.remove(issue.id)
                state.removeOutput(issue.id)
                metricsRepository?.updateAfterRun(
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    projectSlug = projectSlug,
                    result = "failure",
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0
                )
                if (quotaAcquired) quotaEnforcer?.release(projectSlug)
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
        } finally {
            agentIdToIssueId.remove(data.threadId)
            issueProjectMap.remove(issue.id)
            state.running.remove(issue.id)
            state.releaseClaim(issue.id)
            if (quotaAcquired) quotaEnforcer?.release(projectSlug)
        }
    }

    private suspend fun handleWorkplanIfPresent(
        issue: Issue,
        stageConfig: StageAgentConfig?,
        entry: RunningEntry?
    ) {
        val wpConfig = projectConfig.agent.workplan
        if (wpConfig == null || subtaskOrchestrator == null || workplanParser == null) return

        val workspace = workspaces?.ensureWorkspace(issue.identifier) ?: return
        when (val wpResult = workplanParser.parse(workspace.path)) {
            is Result.Success -> {
                logger.info(
                    "workplan_detected",
                    mapOf(
                        "issue_id" to issue.id,
                        "issue_identifier" to issue.identifier,
                        "subtask_count" to wpResult.value.subtasks.size.toString()
                    )
                )
                val orchestrator = subtaskOrchestrator ?: return
                supervisorScope {
                    orchestrator.execute(
                        workspacePath = workspace.path,
                        manifest = wpResult.value,
                        config = wpConfig
                    ).collect { event ->
                        logger.info("subtask_event", mapOf(
                            "issue_id" to issue.id,
                            "event" to event::class.simpleName
                        ))
                    }
                }
            }
            is Result.Failure -> {
                logger.debug("workplan_parse_failed", mapOf(
                    "issue_id" to issue.id as Any?,
                    "error" to (wpResult.exceptionOrNull()?.message ?: "unknown") as Any?
                ))
            }
        }
    }

    private fun handleCrossProjectFollowUp(scope: CoroutineScope, issue: Issue, stageConfig: StageAgentConfig?) {
        val followUpConfig = stageConfig?.crossProjectFollowUp ?: return
        val chainer = crossProjectChainer ?: return
        scope.launch {
            chainer.createFollowUp(issue, followUpConfig, projectSlug)
        }
    }

    suspend fun dispatchDueRetries(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val dueKeys = state.retryAttempts.entries
            .filter { it.value.dueAtMs <= now }
            .map { it.key }
            .toList()

        for (issueId in dueKeys) {
            scope.coroutineContext.ensureActive()
            val retryEntry = state.retryAttempts.remove(issueId)
            if (retryEntry == null) continue
            if (state.availableSlots() <= 0) break
            if (state.running.containsKey(issueId) || state.isClaimed(issueId)) continue
            val issue = try {
                scope.coroutineContext.ensureActive()
                linear.fetchIssueById(issueId)
            } catch (_: Exception) {
                null
            }
            if (issue == null) {
                logger.warn("retry_fetch_failed", mapOf("issue_id" to issueId))
                continue
            }
            state.running.remove(issueId)
            if (projectConfig.agent.sequentialMode) {
                dispatchSequential(issue, scope, retryEntry.attempt)
            } else {
                dispatch(issue, scope, retryEntry.attempt)
            }
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

    private suspend fun readClarification(identifier: String): String? = withContext(Dispatchers.IO) {
        val ws = workspaces ?: return@withContext null
        val workspace = runCatching { ws.ensureWorkspace(identifier) }.getOrNull() ?: return@withContext null
        val path = workspace.path.resolve(".koncerto").resolve("clarification.md")
        if (Files.exists(path)) runCatching { Files.readString(path) }.getOrNull() else null
    }

    suspend fun handleClarification(issueId: String, clarificationContent: String) {
        val issue = withContext(Dispatchers.IO) { linear.fetchIssueById(issueId) } ?: return
        logger.info("clarification_received", mapOf(
            "issue_id" to issueId,
            "issue_identifier" to issue.identifier
        ))

        withContext(Dispatchers.IO) { linear.createComment(issueId, clarificationContent) }

        val blockedStateId = withContext(Dispatchers.IO) { linear.resolveStateId(projectSlug, projectConfig.tracker.blockedState) }
        if (blockedStateId != null) {
            withContext(Dispatchers.IO) { linear.updateIssueState(issueId, blockedStateId) }
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
            withContext(Dispatchers.IO) { linear.updateIssueAssignee(issueId, assigneeId) }
            logger.info("assignee_updated", mapOf(
                "issue_id" to issueId,
                "assignee_id" to assigneeId
            ))
        }

        state.addBlocked(issueId)

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
