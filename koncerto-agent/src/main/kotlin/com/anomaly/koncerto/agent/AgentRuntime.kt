package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.tenant.TenantContext
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement

import com.anomaly.koncerto.core.config.DockerConfig

interface AgentRuntime {
    suspend fun start(tenantContext: TenantContext? = null): Boolean
    fun send(method: String, params: JsonElement? = null): String
    fun sendMessage(toAgentId: String, payload: String): String
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
        freeModelCycler: FreeModelCycler? = null
    ): AgentRuntime {
        val useDocker = dockerConfig?.enabled == true && dockerContainerId != null
        if (useDocker) {
            return DockerRuntime(command, workspacePath, logger, dockerContainerId)
        }
        return when (agentKind.lowercase()) {
            "codex" -> CodexRuntime(command, workspacePath, logger)
            "opencode" -> OpencodeRuntime(command, workspacePath, logger, model, freeModelCycler)
            "claude" -> ClaudeReviewRuntime(command, workspacePath, logger)
            else -> throw IllegalArgumentException("Unknown agent.kind: $agentKind")
        }
    }
}
