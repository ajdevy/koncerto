package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import com.flexsentlabs.koncerto.core.tenant.TenantContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class DockerRuntime(
    private val command: String,
    private val workspacePath: Path,
    private val logger: StructuredLogger,
    private val containerId: String
) : AgentRuntime {
    private val requestId = AtomicLong(1)
    private var execProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val events = Channel<AgentEvent>(Channel.BUFFERED)
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val output: SharedFlow<String> = _output.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var pid: Long? = null
    @Volatile
    private var jsonlMode: Boolean = false
    @Volatile
    private var turnCompletedEmitted: Boolean = false

    override suspend fun start(tenantContext: TenantContext?): Boolean = withContext(Dispatchers.IO) {
        if (!isDockerDaemonAvailable()) {
            events.trySend(AgentEvent.StartupFailed(error = "docker daemon is not available", pid = null))
            return@withContext false
        }
        try {
            val socatCmd = command.replace("'", "'\\''")
            val execCmd = "docker exec -i $containerId bash -lc 'socat EXEC:\"$socatCmd\",pty,raw,echo=0 STDIO'"
            val pb = ProcessBuilder("bash", "-lc", execCmd)
                .directory(workspacePath.toFile())
                .redirectErrorStream(false)
            val p = pb.start()
            execProcess = p
            pid = p.pid()
            writer = p.outputStream.bufferedWriter()
            val stdout = p.inputStream.bufferedReader()
            val stderr = p.errorStream.bufferedReader()
            readerJob = scope.launch {
                launch { readStdout(stdout) }
                launch { readStderr(stderr) }
            }
            true
        } catch (e: Exception) {
            events.trySend(AgentEvent.StartupFailed(error = e.message ?: "docker exec failed", pid = null))
            false
        }
    }

    private fun isDockerDaemonAvailable(): Boolean {
        return try {
            val pb = ProcessBuilder("bash", "-lc", "docker info")
            val p = pb.start()
            val exited = p.waitFor(5, TimeUnit.SECONDS)
            exited && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readStdout(reader: BufferedReader) {
        try {
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                _output.tryEmit("[stdout] $line")
                    try {
                        val msgs = JsonRpcFraming.decodeAll(line)
                        msgs.forEach { dispatchMessage(it) }
                    } catch (e: Exception) {
                        if (!tryHandleAsJsonl(line)) {
                            events.trySend(AgentEvent.Malformed(raw = line.take(2000), pid = pid))
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn("docker_stdout_read_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
        if (jsonlMode && !turnCompletedEmitted) {
            _output.tryEmit("[jsonl] stream ended")
            events.trySend(AgentEvent.TurnCompleted("?", "?", null, pid))
        }
    }

    private suspend fun readStderr(reader: BufferedReader) {
        try {
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    _output.tryEmit("[stderr] $line")
                    logger.debug("docker_stderr", emptyMap(), "line" to line.take(500))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun dispatchMessage(msg: JsonRpcMessage) {
        when (msg) {
            is JsonRpcResponseMsg -> {
                val r = msg.response
                val result = r.result as? JsonObject
                val method = result?.get("method")?.toString()
                when {
                    method?.contains("session/started") == true -> emitSessionStarted(result)
                    method?.contains("turn/completed") == true -> emitTurnCompleted(result, success = true)
                    method?.contains("turn/failed") == true -> emitTurnCompleted(result, success = false)
                    method?.contains("turn/cancelled") == true -> emitTurnCancelled(result)
                    else -> events.trySend(AgentEvent.OtherMessage(method ?: "response", r.result, pid))
                }
            }
            is JsonRpcNotificationMsg -> {
                val n = msg.notification
                when (n.method) {
                    "session/started" -> emitSessionStartedFromNotification(n)
                    "turn/completed" -> emitTurnCompletedFromNotification(n, success = true)
                    "turn/failed" -> emitTurnCompletedFromNotification(n, success = false)
                    "turn/cancelled" -> emitTurnCancelledFromNotification(n)
                    "turn/input_required" -> events.trySend(AgentEvent.TurnInputRequired("?", "?", pid))
                    "approval/auto_approved" -> events.trySend(AgentEvent.ApprovalAutoApproved(pid))
                    "unsupported_tool_call" -> events.trySend(AgentEvent.UnsupportedToolCall("?", pid))
                    "agent/message" -> {
                        val p = n.params as? JsonObject
                        val fromAgentId = p?.get("from_agent_id")?.toString()?.trim('"') ?: "unknown"
                        val payload = p?.get("payload")?.toString()?.trim('"') ?: ""
                        val messageId = p?.get("message_id")?.toString()?.trim('"') ?: UUID.randomUUID().toString()
                        events.trySend(AgentEvent.AgentMessage(messageId, fromAgentId, "this-agent", payload, pid))
                    }
                    else -> events.trySend(AgentEvent.Notification(n.method, n.params, pid))
                }
            }
        }
    }

    private fun emitSessionStarted(result: JsonObject?) {
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: UUID.randomUUID().toString()
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "0"
        events.trySend(AgentEvent.SessionStarted(threadId, turnId, pid))
    }

    private fun emitSessionStartedFromNotification(n: JsonRpcNotification) {
        val p = n.params as? JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: UUID.randomUUID().toString()
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "0"
        events.trySend(AgentEvent.SessionStarted(threadId, turnId, pid))
    }

    private fun emitTurnCompleted(result: JsonObject?, success: Boolean) {
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "?"
        val usage = extractUsage(result)
        if (success) events.trySend(AgentEvent.TurnCompleted(threadId, turnId, usage, pid))
        else events.trySend(AgentEvent.TurnFailed(threadId, turnId, "agent_reported_failure", pid))
    }

    private fun emitTurnCompletedFromNotification(n: JsonRpcNotification, success: Boolean) {
        val p = n.params as? JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "?"
        val usage = extractUsage(p)
        if (success) events.trySend(AgentEvent.TurnCompleted(threadId, turnId, usage, pid))
        else events.trySend(AgentEvent.TurnFailed(threadId, turnId, "agent_reported_failure", pid))
    }

    private fun emitTurnCancelled(result: JsonObject?) {
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "?"
        events.trySend(AgentEvent.TurnCancelled(threadId, turnId, pid))
    }

    private fun emitTurnCancelledFromNotification(n: JsonRpcNotification) {
        val p = n.params as? JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "?"
        events.trySend(AgentEvent.TurnCancelled(threadId, turnId, pid))
    }

    private fun extractUsage(obj: JsonObject?): TokenUsage? {
        if (obj == null) return null
        val usage = obj["usage"] as? JsonObject ?: return null
        val input = (usage["input_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val output = (usage["output_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val total = (usage["total_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (input + output)
        return TokenUsage(input, output, total)
    }

    override fun isAlive(): Boolean {
        if (execProcess?.isAlive == true) return true
        return checkContainerRunning()
    }

    private fun checkContainerRunning(): Boolean {
        return try {
            val pb = ProcessBuilder("bash", "-lc", "docker inspect $containerId --format='{{.State.Status}}'")
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            output == "running"
        } catch (_: Exception) {
            false
        }
    }

    override fun events(): Flow<AgentEvent> = events.receiveAsFlow()

    private fun tryHandleAsJsonl(line: String): Boolean {
        if (!line.trimStart().startsWith("{")) return false
        return try {
            val element = Json.parseToJsonElement(line)
            val obj = element.jsonObject
            val type = (obj["type"] as? JsonPrimitive)?.content
            jsonlMode = true
            when (type) {
                "thread.started" -> {
                    val threadId = obj["thread_id"]?.toString()?.trim('"') ?: UUID.randomUUID().toString()
                    events.trySend(AgentEvent.SessionStarted(threadId, "0", pid))
                    true
                }
                "turn.started", "item.started" -> {
                    _output.tryEmit("[jsonl] $type")
                    true
                }
                "item.completed", "item.created" -> {
                    _output.tryEmit("[jsonl] $type")
                    true
                }
                "turn.completed" -> {
                    turnCompletedEmitted = true
                    val usage = extractUsageFromJsonl(obj)
                    events.trySend(AgentEvent.TurnCompleted("?", "?", usage, pid))
                    true
                }
                "error" -> {
                    val message = obj["message"]?.toString()?.trim('"') ?: "unknown error"
                    events.trySend(AgentEvent.TurnFailed("?", "?", message, pid))
                    true
                }
                else -> {
                    _output.tryEmit("[jsonl] $type")
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractUsageFromJsonl(obj: JsonObject): TokenUsage? {
        val usage = obj["usage"] as? JsonObject ?: return null
        val input = (usage["input_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val output = (usage["output_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val total = (usage["total_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (input + output)
        return TokenUsage(input, output, total)
    }

    override fun writeRaw(data: String) {
        synchronized(this) {
            writer?.let {
                it.write(data)
                it.newLine()
                it.flush()
            }
        }
    }

    override fun closeStdin() {
        synchronized(this) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    override fun send(method: String, params: JsonElement?): String {
        val id = requestId.getAndIncrement().toString()
        val req = JsonRpcRequest(id = id, method = method, params = params)
        synchronized(this) {
            writer?.let {
                it.write(JsonRpcFraming.encodeRequest(req))
                it.flush()
            }
        }
        return id
    }

    override fun sendMessage(toAgentId: String, payload: String): String {
        val id = requestId.getAndIncrement().toString()
        val params = kotlinx.serialization.json.buildJsonObject {
            put("to_agent_id", kotlinx.serialization.json.JsonPrimitive(toAgentId))
            put("payload", kotlinx.serialization.json.JsonPrimitive(payload))
        }
        val req = JsonRpcRequest(id = id, method = "agent/message", params = params)
        synchronized(this) {
            writer?.let {
                it.write(JsonRpcFraming.encodeRequest(req))
                it.flush()
            }
        }
        return id
    }

    override fun stop() {
        captureContainerLogs()
        captureContainerStats()
        try { writer?.close() } catch (_: Exception) {}
        try { execProcess?.destroy() } catch (_: Exception) {}
        try { execProcess?.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        readerJob?.cancel()
        removeContainer()
        events.close()
    }

    private fun captureContainerStats() {
        try {
            val pb = ProcessBuilder("bash", "-lc", "docker stats --no-stream $containerId 2>&1")
            val p = pb.start()
            val stats = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            if (stats.isNotBlank()) {
                stats.lines().filter { it.isNotBlank() }.forEach { line ->
                    _output.tryEmit("[container-stats] $line")
                }
                logger.debug("container_stats_captured", mapOf(
                    "container_id" to containerId,
                    "stats_bytes" to stats.length.toString()
                ))
            }
        } catch (e: Exception) {
            logger.warn("container_stats_failed", mapOf("container_id" to containerId),
                "error" to (e.message ?: "unknown"))
        }
    }

    private fun captureContainerLogs() {
        try {
            val pb = ProcessBuilder("bash", "-lc", "docker logs $containerId 2>&1")
            val p = pb.start()
            val logs = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            if (logs.isNotBlank()) {
                logs.lines().filter { it.isNotBlank() }.forEach { line ->
                    _output.tryEmit("[container-log] $line")
                }
                logger.info("container_logs_captured", mapOf(
                    "container_id" to containerId,
                    "log_bytes" to logs.length.toString()
                ))
            }
        } catch (e: Exception) {
            logger.warn("container_logs_failed", mapOf("container_id" to containerId),
                "error" to (e.message ?: "unknown"))
        }
    }

    private fun removeContainer() {
        try {
            val pb = ProcessBuilder("bash", "-lc", "docker rm -f $containerId 2>/dev/null")
            val p = pb.start()
            p.waitFor(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("container_remove_failed", mapOf("container_id" to containerId),
                "error" to (e.message ?: "unknown"))
        }
    }
}
