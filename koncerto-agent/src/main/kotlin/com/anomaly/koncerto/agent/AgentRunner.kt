package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.agent.AgentCircuitBreaker
import com.anomaly.koncerto.core.config.DockerConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.errors.AgentError
import com.anomaly.koncerto.core.errors.AgentErrorType
import com.anomaly.koncerto.core.errors.ErrorClassifier
import com.anomaly.koncerto.core.errors.ErrorTracker
import com.anomaly.koncerto.core.errors.RetryDecision
import com.anomaly.koncerto.core.errors.RetryDecisionMaker
import com.anomaly.koncerto.core.retry.RetryStrategy
import com.anomaly.koncerto.core.events.AgentLifecycleEvent
import com.anomaly.koncerto.core.events.EventBus
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.runCatchingResult
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.GitWorkflow
import com.anomaly.koncerto.workspace.Workspace
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.PromptRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

data class AttemptResult(
    val issue: Issue,
    val workspace: Workspace,
    val outcome: Outcome,
    val tokenUsage: TokenUsage
) {
    enum class Outcome { SUCCEEDED, FAILED, TIMED_OUT, STALLED, CANCELLED, STARTUP_FAILED }
}

interface AgentRunner {
    fun events(): Flow<AgentEvent>
    suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String? = null,
        commandOverride: String? = null,
        turnTimeoutMs: Long? = null,
        stallTimeoutMs: Long? = null
    ): EmptyResult<IllegalStateException>
}

class DefaultAgentRunner(
    private val config: ServiceConfig,
    private val workspaces: WorkspaceManager,
    private val logger: StructuredLogger,
    private val runtimeFactory: AgentRuntimeFactory? = null,
    private val gitWorkflow: GitWorkflow? = null,
    private val onAgentOutput: ((issueId: String, line: String) -> Unit)? = null,
    private val onAgentOutputSuspend: suspend (issueId: String, line: String) -> Unit = { _, _ -> },
    private val heartbeatIntervalMs: Long = 30_000L,
    private val circuitBreaker: AgentCircuitBreaker? = null,
    private val errorTracker: ErrorTracker? = null,
    private val healthChecker: AgentHealthChecker? = null,
    private val maxRetries: Int = 1,
    private val retryDelayMs: Long = 5_000L,
    private val errorClassifier: ErrorClassifier? = null,
    private val dockerConfig: DockerConfig? = null,
    private val maxConcurrentAgents: Int = 2
) : AgentRunner {
    private val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<AgentEvent> = eventFlow.asSharedFlow()

    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> = runCatchingResult {
        val effectiveAttempt = attempt ?: 1
        val agentKey = "${agentKindOverride ?: "opencode"}:${commandOverride ?: agentKindOverride ?: "opencode"}"

        if (!(circuitBreaker?.allowRequest(agentKey) ?: true)) {
            throw IllegalStateException("circuit_breaker_open: $agentKey")
        }

        var lastException: Exception? = null
        for (retryAttempt in 1..maxRetries) {
            try {
                val result = runWithRetry(
                    issue = issue,
                    attempt = effectiveAttempt,
                    prompt = prompt,
                    agentKindOverride = agentKindOverride,
                    commandOverride = commandOverride,
                    turnTimeoutMs = turnTimeoutMs,
                    stallTimeoutMs = stallTimeoutMs,
                    retryAttempt = retryAttempt,
                    agentKey = agentKey
                )
                circuitBreaker?.recordSuccess(agentKey)
                EventBus.publish(AgentLifecycleEvent.Completed(agentKey, true))
                return@runCatchingResult result
            } catch (e: Exception) {
                lastException = e
                val errorMsg = e.message ?: e.javaClass.simpleName
                errorTracker?.recordError(agentKey, errorMsg, "agent")

                val classified = errorClassifier?.classify("exception", errorMsg)
                val isClassified = classified != null && classified !is AgentErrorType.UnknownError
                if (isClassified) {
                    eventFlow.tryEmit(AgentEvent.LimitDetected(
                        agentError = AgentError(type = classified!!, message = errorMsg, source = "exception"),
                        issueId = issue.id,
                        line = errorMsg
                    ))
                }

                val decision = if (isClassified) RetryDecisionMaker.decide(classified!!, retryAttempt) else null

                if (decision is RetryDecision.NoRetry) {
                    circuitBreaker?.recordFailure(agentKey)
                    throw e
                }

                val delayMs = when (decision) {
                    is RetryDecision.RetryWithDelay -> decision.delayMs
                    is RetryDecision.RetryWithBackoff -> RetryStrategy.nextDelay(retryAttempt - 1, decision.config)
                    else -> retryDelayMs * retryAttempt
                }

                logger.warn("agent_run_failed_retry", mapOf(
                    "issue_id" to issue.id,
                    "issue_identifier" to issue.identifier,
                    "agent_key" to agentKey,
                    "retry_attempt" to retryAttempt,
                    "max_retries" to maxRetries,
                    "error" to errorMsg,
                    "delay_ms" to delayMs
                ))

                if (errorMsg.contains("stalled", ignoreCase = true)) {
                    EventBus.publish(AgentLifecycleEvent.Stalled(agentKey, 0L))
                } else {
                    EventBus.publish(AgentLifecycleEvent.Failed(agentKey, errorMsg, retryAttempt))
                }

                if (decision !is RetryDecision.RetryWithDelay) {
                    circuitBreaker?.recordFailure(agentKey)
                }

                if (retryAttempt < maxRetries) {
                    EventBus.publish(AgentLifecycleEvent.Recovered(agentKey, errorMsg, retryAttempt))
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("agent_run_failed_after_retries")
    }

    private suspend fun runWithRetry(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?,
        retryAttempt: Int,
        agentKey: String
    ) {
        val workspace = workspaces.ensureWorkspace(issue.identifier)
        workspaces.assertInsideRoot(workspace.path)
        config.hooks.afterCreate?.let { workspaces.runAfterCreate(workspace, it) }
        config.hooks.beforeRun?.let { workspaces.runBeforeRun(workspace, it) }
        gitWorkflow?.createBranch(workspace.path, issue.identifier)

        val useDocker = dockerConfig?.enabled == true
        val containerManager = if (useDocker) {
            DockerContainerManager(dockerConfig!!, workspace.path, maxConcurrentAgents, logger)
        } else null
        val containerId = containerManager?.createContainer()
        if (useDocker && containerId == null) {
            logger.warn("docker_container_creation_failed_fallback", mapOf("issue" to issue.identifier))
        }

        val factory = runtimeFactory ?: AgentRuntimeFactory(logger)
        val effectiveKind = agentKindOverride ?: "opencode"
        val command = commandOverride ?: effectiveKind
        val runtime = factory.create(effectiveKind, command, workspace.path, dockerConfig, containerId)
        if (!runtime.start()) {
            containerManager?.removeContainer(containerId ?: "")
            throw IllegalStateException("startup_failed")
        }
        EventBus.publish(AgentLifecycleEvent.Started(agentKey, 0L))
        healthChecker?.reportHeartbeat(agentKey, 0L)
        val processId = AtomicLong(0L)

        val effectiveTurnTimeout = turnTimeoutMs ?: 3_600_000L
        val effectiveStallTimeout = stallTimeoutMs ?: 300_000L

        try {
            withTimeout(effectiveTurnTimeout) {
                coroutineScope {
                    val lastOutputMs = AtomicLong(System.currentTimeMillis())

                    val outputJob = launch {
                        runtime.output.collect { line ->
                            lastOutputMs.set(System.currentTimeMillis())
                            if (onAgentOutput != null) {
                                onAgentOutput(issue.id, line)
                            }
                            onAgentOutputSuspend(issue.id, line)
                            if (errorClassifier != null && line.startsWith("[stderr]")) {
                                val msg = line.removePrefix("[stderr] ").removePrefix("[stderr]")
                                val classified = errorClassifier.classify("stderr", msg)
                                if (classified !is AgentErrorType.UnknownError) {
                                    eventFlow.tryEmit(AgentEvent.LimitDetected(
                                        agentError = AgentError(
                                            type = classified,
                                            message = msg.trim(),
                                            source = "stderr"
                                        ),
                                        issueId = issue.id,
                                        line = line
                                    ))
                                }
                            }
                        }
                    }

                    val stallJob = launch {
                        while (true) {
                            delay(1000)
                            val elapsed = System.currentTimeMillis() - lastOutputMs.get()
                            if (elapsed > effectiveStallTimeout) {
                                throw IllegalStateException("Agent stalled (no output for ${elapsed}ms)")
                            }
                        }
                    }

                    val aliveJob = launch {
                        while (true) {
                            delay(heartbeatIntervalMs)
                            if (!runtime.isAlive()) {
                                throw IllegalStateException("agent_process_died")
                            }
                        }
                    }

                    val rendered = PromptRenderer.render(prompt, mapOf(
                        "issue" to issue.toTemplateMap(),
                        "attempt" to attempt
                    ))

                    runtime.send("initialize", null)
                    runtime.send("thread/start", buildJsonObject {
                        put("working_directory", workspace.path.toString())
                    })
                    runtime.send("turn/start", buildJsonObject {
                        put("input", rendered)
                    })

                    val turnDone = CompletableDeferred<Unit>()
                    val eventWatcher = launch {
                        runtime.events().collect { event ->
                            when (event) {
                                is AgentEvent.SessionStarted -> {
                                    event.pid?.let { processId.set(it) }
                                }
                                is AgentEvent.TurnCompleted, is AgentEvent.TurnFailed -> {
                                    healthChecker?.reportHeartbeat(agentKey, processId.get())
                                    turnDone.complete(Unit)
                                }
                                else -> {}
                            }
                        }
                    }

                    turnDone.await()
                    eventWatcher.cancel()
                    runtime.stop()
                    outputJob.cancel()
                    stallJob.cancel()
                    aliveJob.cancel()
                }
            }
        } catch (e: Exception) {
            runtime.stop()
            healthChecker?.markUnhealthy(agentKey, e.message ?: e.javaClass.simpleName)
            gitWorkflow?.let { gw ->
                try {
                    gw.commitAndPush(workspace.path, issue.identifier, issue.title, issue.labels)
                    logger.info("partial_work_committed", mapOf(
                        "issue" to issue.identifier,
                        "reason" to (e.message ?: e.javaClass.simpleName)
                    ))
                } catch (_: Exception) {
                    logger.warn("partial_commit_failed", mapOf("issue" to issue.identifier))
                }
            }
            healthChecker?.removeAgent(agentKey)
            containerManager?.removeContainer(containerId ?: "")
            throw e
        }

        containerManager?.removeContainer(containerId ?: "")

        config.hooks.afterRun?.let { workspaces.runAfterRun(workspace, it, logger) }

        val clarificationPath = workspace.path.resolve(".koncerto").resolve("clarification.md")
        val clarificationRequested = Files.exists(clarificationPath)
        if (clarificationRequested) {
            logger.info("clarification_requested", mapOf("issue" to issue.identifier, "path" to clarificationPath.toString()))
        }

        if (!clarificationRequested) {
            gitWorkflow?.let { gw ->
                gw.commitAndPush(workspace.path, issue.identifier, issue.title, issue.labels)
                val prUrl = gw.createPullRequest(workspace.path, issue.identifier, issue.title, issue.description)
                if (prUrl != null) {
                    logger.info("pr_created", mapOf("url" to prUrl, "issue" to issue.identifier))
                }
            }
        }
        healthChecker?.removeAgent(agentKey)
    }

    private fun Issue.toTemplateMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "identifier" to identifier,
        "title" to title,
        "description" to description,
        "priority" to priority,
        "state" to state,
        "branch_name" to branchName,
        "url" to url,
        "labels" to labels,
        "blocked_by" to blockedBy.map {
            mapOf("id" to it.id, "identifier" to it.identifier, "state" to it.state)
        },
        "created_at" to createdAt?.toString(),
        "updated_at" to updatedAt?.toString()
    )
}
