package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.tenant.TenantContext
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement

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
    fun create(agentKind: String, command: String, workspacePath: Path): AgentRuntime {
        return when (agentKind.lowercase()) {
            "codex" -> CodexRuntime(command, workspacePath, logger)
            "opencode" -> OpencodeRuntime(command, workspacePath, logger)
            "claude" -> ClaudeReviewRuntime(command, workspacePath, logger)
            else -> throw IllegalArgumentException("Unknown agent.kind: $agentKind")
        }
    }
}
