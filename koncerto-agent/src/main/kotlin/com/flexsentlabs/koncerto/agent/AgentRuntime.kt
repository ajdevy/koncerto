package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.tenant.TenantContext
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement

import com.flexsentlabs.koncerto.core.config.DockerConfig

interface AgentRuntime {
    suspend fun start(tenantContext: TenantContext? = null): Boolean
    fun send(method: String, params: JsonElement? = null): String
    fun sendMessage(toAgentId: String, payload: String): String
    fun writeRaw(data: String)
    fun closeStdin()
    fun events(): Flow<AgentEvent>
    val output: SharedFlow<String>
    fun isAlive(): Boolean = true
    fun stop()
}

class AgentRuntimeFactory(private val logger: StructuredLogger) {
    fun create(
        agentKind: String,
        command: String,
        workspacePath: Path,
        dockerConfig: DockerConfig? = null,
        dockerContainerId: String? = null,
        model: String? = null,
        effort: String? = null,
        freeModelCycler: FreeModelCycler? = null
    ): AgentRuntime {
        val fullCommand = buildFullCommand(agentKind, command, model, effort)
        val useDocker = dockerConfig?.enabled == true && dockerContainerId != null
        if (useDocker) {
            return DockerRuntime(fullCommand, workspacePath, logger, dockerContainerId)
        }
        return when (agentKind.lowercase()) {
            "codex" -> CodexRuntime(fullCommand, workspacePath, logger)
            "opencode" -> OpencodeRuntime(fullCommand, workspacePath, logger, model, freeModelCycler)
            "claude" -> ClaudeReviewRuntime(fullCommand, workspacePath, logger)
            else -> throw IllegalArgumentException("Unknown agent.kind: $agentKind")
        }
    }

    private fun buildFullCommand(agentKind: String, command: String, model: String?, effort: String?): String {
        var cmd = command
        if (model != null) {
            cmd += " --model $model"
        }
        if (effort != null) {
            cmd += when (agentKind.lowercase()) {
                "codex" -> " -c model_reasoning_effort=$effort"
                "claude" -> " --effort $effort"
                "opencode" -> " --variant $effort"
                else -> ""
            }
        }
        return cmd
    }
}
