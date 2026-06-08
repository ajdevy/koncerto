package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.runCatchingResult
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.Workspace
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.PromptRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        prompt: String
    ): EmptyResult<IllegalStateException>
}

class DefaultAgentRunner(
    private val config: ServiceConfig,
    private val workspaces: WorkspaceManager,
    private val logger: StructuredLogger,
    private val runtimeFactory: AgentRuntimeFactory? = null
) : AgentRunner {

    private val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<AgentEvent> = eventFlow.asSharedFlow()

    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String
    ): EmptyResult<IllegalStateException> = runCatchingResult {
        val workspace = workspaces.ensureWorkspace(issue.identifier)
        workspaces.assertInsideRoot(workspace.path)
        config.hooks.afterCreate?.let { workspaces.runAfterCreate(workspace, it) }
        config.hooks.beforeRun?.let { workspaces.runBeforeRun(workspace, it) }

        val factory = runtimeFactory ?: AgentRuntimeFactory(logger)
        val command = if (config.agentKind == "opencode") config.opencodeCommand else config.codexCommand
        val runtime = factory.create(config.agentKind, command, workspace.path)
        if (!runtime.start()) throw IllegalStateException("startup_failed")

        val rendered = PromptRenderer.render(
            prompt, mapOf(
                "issue" to issue.toTemplateMap(),
                "attempt" to attempt
            )
        )

        runtime.send("initialize", null)
        runtime.send(
            "thread/start", buildJsonObject {
                put("working_directory", workspace.path.toString())
            }
        )
        runtime.send(
            "turn/start", buildJsonObject {
                put("input", rendered)
            }
        )

        runtime.stop()
        config.hooks.afterRun?.let { workspaces.runAfterRun(workspace, it, logger) }
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
            mapOf(
                "id" to it.id,
                "identifier" to it.identifier,
                "state" to it.state
            )
        },
        "created_at" to createdAt?.toString(),
        "updated_at" to updatedAt?.toString()
    )
}
