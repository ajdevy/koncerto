package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.TokenUsage
import com.anomaly.koncerto.core.audit.AuditEvent
import com.anomaly.koncerto.core.audit.AuditEventType
import com.anomaly.koncerto.core.audit.AuditLogger
import com.anomaly.koncerto.orchestrator.RunningEntry
import com.anomaly.koncerto.core.config.NotificationsConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.RoutingRule
import com.anomaly.koncerto.core.config.FollowUpConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.config.WorkplanConfig
import java.nio.file.Files
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
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile

private const val AGENT_LABEL_PREFIX = "agent:"
private const val MODEL_LABEL_PREFIX = "model:"
private const val DEFAULT_PRIORITY = Int.MAX_VALUE
private const val RETRY_RESCHEDULE_DELAY_MS = 1000L
private val DEFAULT_CREATED_AT = Instant.MAX // Sort null createdAt LAST (newest first)

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
    private val crossProjectChainer: CrossProjectChainer? = null,
    private val auditLogger: AuditLogger? = null
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
                && !state.isClaimed(candidate.id)
                && candidate.normalizedState.equals("todo", ignoreCase = true)) {
                state.addBlocked(candidate.id)
            }
        }
        for (frontierId in graph.frontier.map { it.id }) {
            scope.coroutineContext.ensureActive()
            state.removeBlocked(frontierId)
        }
        val candidateIds = candidates.map { it.id }.toSet()
        for (id in state.blockedKeys.toTypedArray()) {
            scope.coroutineContext.ensureActive()
            if (id !in candidateIds) state.removeBlocked(id)
        }

        for (issue in sorted) {
            scope.coroutineContext.ensureActive()
            // Race condition acceptable - availableSlots is a hint; dispatch() uses tryClaim which is atomic.
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
        auditLogger?.log(AuditEvent(
            timestamp = System.currentTimeMillis(),
            type = AuditEventType.AGENT_RETRY_SCHEDULED,
            projectSlug = projectSlug,
            issueId = issue.id,
            issueIdentifier = issue.identifier,
            attempt = entry.attempt,
            error = error
        ))
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

    private fun dispatch(issue: Issue, scope: CoroutineScope, attempt: Int? = null, retryEntry: RetryEntry? = null): Boolean {
        val execData = prepareDispatch(issue, attempt) ?: run {
            retryEntry?.let { state.retryAttempts[issue.id] = it.copy(dueAtMs = System.currentTimeMillis() + RETRY_RESCHEDULE_DELAY_MS) }
            return false
        }
        scope.launch {
            runIssueExecution(scope, execData, issue)
        }
        return true
    }

    private fun dispatchSequential(issue: Issue, scope: CoroutineScope, attempt: Int? = null, retryEntry: RetryEntry? = null): Boolean {
        val execData = prepareDispatch(issue, attempt) ?: run {
            retryEntry?.let { state.retryAttempts[issue.id] = it.copy(dueAtMs = System.currentTimeMillis() + RETRY_RESCHEDULE_DELAY_MS) }
            return false
        }
        scope.launch {
            runIssueExecution(scope, execData, issue)
        }
        return true
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

        if (!state.tryClaim(issue.id)) return null

        // Check quota atomically with claim — null quotaConfig means no limit
        val quotaAcquired = quotaConfig == null || quotaEnforcer?.tryAcquire(projectSlug, quotaConfig) == true
        if (!quotaAcquired) {
            state.releaseClaim(issue.id)
            return null
        }

        val entry = RunningEntry(
            issue = issue,
            threadId = threadId,
            turnId = turnId,
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            tenantContext = tenantContext
        )
        state.running[issue.id] = entry
        auditLogger?.log(AuditEvent(
            timestamp = System.currentTimeMillis(),
            type = AuditEventType.AGENT_DISPATCHED,
            projectSlug = projectSlug,
            issueId = issue.id,
            issueIdentifier = issue.identifier,
            agentKind = resolved.kind
        ))

        return DispatchExecutionData(stageConfig, prompt, resolved, threadId, turnId, attempt)
    }

    private suspend fun runIssueExecution(scope: CoroutineScope, data: DispatchExecutionData, issue: Issue) {
        scope.coroutineContext.ensureActive()
        issueProjectMap[issue.id] = projectSlug
        agentIdToIssueId[data.threadId] = issue.id

        val result = agentRunner.run(
            issue, data.attempt, data.prompt, data.resolved.kind, data.resolved.command,
            turnTimeoutMs = projectConfig.agent.turnTimeoutMs,
            stallTimeoutMs = projectConfig.agent.stallTimeoutMs
        )

        val eventCollectorJob = scope.launch {
            agentRunner.events()
                .takeWhile { _ -> state.running.containsKey(issue.id) }
                .collect { event ->
                    if (event is AgentEvent.TurnCompleted) {
                        event.usage?.let { usage ->
                            state.updateIssueTokens(issue.id, usage.inputTokens, usage.outputTokens, usage.totalTokens)
                        }
                    }
                }
        }
        try {
            result.onSuccess {
                scope.coroutineContext.ensureActive()
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
                auditLogger?.log(AuditEvent(
                    timestamp = System.currentTimeMillis(),
                    type = AuditEventType.AGENT_COMPLETED,
                    projectSlug = projectSlug,
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    inputTokens = finalEntry?.inputTokens ?: 0,
                    outputTokens = finalEntry?.outputTokens ?: 0,
                    totalTokens = finalEntry?.totalTokens ?: 0
                ))
                scope.coroutineContext.ensureActive()
                handleWorkplanIfPresent(scope, issue, data.stageConfig, finalEntry)
                handleCrossProjectFollowUp(scope, issue, data.stageConfig)
                handleNormalCompletion(issue, data.stageConfig, finalEntry)
                quotaEnforcer?.release(projectSlug)
            }.onFailure { err ->
                scope.coroutineContext.ensureActive()
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
                auditLogger?.log(AuditEvent(
                    timestamp = System.currentTimeMillis(),
                    type = AuditEventType.AGENT_FAILED,
                    projectSlug = projectSlug,
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    error = err.message
                ))
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
                quotaEnforcer?.release(projectSlug)
            }
        } finally {
            eventCollectorJob.cancel()
        }
    }

    private val backgroundJobs = CopyOnWriteArrayList<Job>()

    suspend fun awaitBackgroundJobs() {
        backgroundJobs.forEach { it.join() }
        backgroundJobs.removeIf { it.isCompleted }
    }

    suspend fun shutdown() {
        shutdownRequested = true
        awaitBackgroundJobs()
    }

    private suspend fun handleWorkplanIfPresent(
        scope: CoroutineScope,
        issue: Issue,
        stageConfig: StageAgentConfig?,
        entry: RunningEntry?
    ) {
        scope.coroutineContext.ensureActive()
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
                val job = scope.launch {
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
                backgroundJobs.add(job)
                job.invokeOnCompletion { cause -> backgroundJobs.remove(job) }
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
        val job = scope.launch {
            try {
                chainer.createFollowUp(issue, followUpConfig, projectSlug)
            } catch (e: Exception) {
                logger.failure("followup_failed", mapOf("issue_id" to issue.id), e)
            }
        }
        backgroundJobs.add(job)
        job.invokeOnCompletion { cause -> backgroundJobs.remove(job) }
    }

    suspend fun dispatchDueRetries(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val dueKeys = state.retryAttempts.entries
            .filter { it.value.dueAtMs <= now }
            .map { it.key }
            .toList()

        for (issueId in dueKeys) {
            scope.coroutineContext.ensureActive()
            if (state.availableSlots() <= 0) break

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

            if (state.running.containsKey(issueId) || state.isClaimed(issueId)) {
                val retryEntry = state.retryAttempts[issueId]
                retryEntry?.let {
                    state.retryAttempts[issueId] = it.copy(dueAtMs = System.currentTimeMillis() + RETRY_RESCHEDULE_DELAY_MS)
                }
                continue
            }

            val retryEntry = state.retryAttempts.remove(issueId)
            if (retryEntry == null) continue

            state.running.remove(issueId)
            val success = if (projectConfig.agent.sequentialMode) {
                dispatchSequential(issue, scope, retryEntry.attempt, retryEntry)
            } else {
                dispatch(issue, scope, retryEntry.attempt, retryEntry)
            }
            if (!success) {
                // Atomic re-insert: only add back if no other thread inserted a newer entry
                state.retryAttempts.computeIfAbsent(issueId) {
                    retryEntry.copy(dueAtMs = System.currentTimeMillis() + RETRY_RESCHEDULE_DELAY_MS)
                }
            }
        }
    }

    private suspend fun resolveReviewTargetState(issue: Issue, stageConfig: StageAgentConfig): String? {
        val onComplete = stageConfig.onCompleteState
        val onFailure = stageConfig.onFailureState
        val maxAttempts = stageConfig.maxReviewAttempts ?: 3

        if (onFailure == null) return onComplete

        val ws = workspaces ?: return onComplete
        val workspace = runCatching { ws.ensureWorkspace(issue.identifier) }.getOrNull() ?: return onComplete
        val statusFile = workspace.path.resolve(".review-status")
        val attemptFile = workspace.path.resolve(".review-attempt")

        if (!Files.exists(statusFile)) return onComplete
        val status = runCatching { Files.readString(statusFile) }.getOrNull()?.trim()?.lowercase() ?: return onComplete

        if (status != "fail") return onComplete

        val attempt = runCatching { Files.readString(attemptFile).trim().toInt() }.getOrNull() ?: 0

        return if (attempt >= maxAttempts) {
            logger.warn("review_max_attempts_reached", mapOf(
                "issue_id" to issue.id,
                "attempts" to attempt.toString(),
                "max" to maxAttempts.toString()
            ))
            runCatching { Files.deleteIfExists(statusFile) }
            runCatching { Files.deleteIfExists(attemptFile) }
            onComplete
        } else {
            onFailure
        }
    }

    internal suspend fun transitionOnComplete(issue: Issue, stageConfig: StageAgentConfig?) {
        val config = stageConfig ?: return
        val targetState = resolveReviewTargetState(issue, config) ?: return
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
        val issue = withContext(Dispatchers.IO) {
            val fetched = linear.fetchIssueById(issueId) ?: return@withContext null
            linear.createComment(issueId, clarificationContent)

            val blockedStateId = linear.resolveStateId(projectSlug, projectConfig.tracker.blockedState)
            if (blockedStateId != null) {
                linear.updateIssueState(issueId, blockedStateId)
                logger.info("state_transitioned", mapOf(
                    "issue_id" to issueId,
                    "from_state" to fetched.state,
                    "to_state" to projectConfig.tracker.blockedState
                ))
            } else {
                logger.warn("blocked_state_not_found", mapOf("blocked_state" to projectConfig.tracker.blockedState))
            }

            val creator = fetched.creator
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
            fetched
        } ?: return

        logger.info("clarification_received", mapOf(
            "issue_id" to issueId,
            "issue_identifier" to issue.identifier
        ))

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
