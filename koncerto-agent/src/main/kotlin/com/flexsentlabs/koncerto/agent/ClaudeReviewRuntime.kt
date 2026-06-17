package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.flexsentlabs.koncerto.core.tenant.TenantContext

class ClaudeReviewRuntime(
    private val command: String,
    private val workspacePath: Path,
    private val logger: StructuredLogger,
    model: String? = null
) : AgentRuntime {

    private val effectiveCommand: String = if (model != null) "$command --model $model" else command
    private var process: Process? = null
    private var workerJob: Job? = null
    private val events = Channel<AgentEvent>(Channel.BUFFERED)
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val output: SharedFlow<String> = _output.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pid: Long? = null

    override suspend fun start(tenantContext: TenantContext?): Boolean = true

    override fun send(method: String, params: JsonElement?): String {
        if (method == "turn/start") {
            val input = (params as? JsonObject)?.get("input")
                ?.let { (it as? JsonPrimitive)?.content }
            if (input != null) {
                workerJob = scope.launch { runReview(input) }
            } else {
                logger.warn("claude_review_missing_input", emptyMap())
            }
        }
        return "ok"
    }

    override fun sendMessage(toAgentId: String, payload: String): String = "ok"

    private suspend fun runReview(prompt: String) = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("bash", "-lc", effectiveCommand)
                .directory(workspacePath.toFile())
                .redirectErrorStream(true)
            val p = pb.start()
            process = p
            pid = p.pid()
            logger.info("claude_review_started", mapOf("pid" to pid.toString()))

            p.outputStream.bufferedWriter().use { writer ->
                writer.write(prompt)
                writer.flush()
            }
            p.outputStream.close()

            val output = p.inputStream.bufferedReader().readText()
            if (output.isNotBlank()) {
                output.lines().forEach { _output.tryEmit(it) }
            }

            p.waitFor(5, TimeUnit.MINUTES)
            val exitCode = p.exitValue()
            logger.info("claude_review_completed", mapOf(
                "pid" to pid.toString(),
                "exit_code" to exitCode.toString(),
                "output_bytes" to output.length.toString()
            ))

            val hasFailures = output.contains("❌ FAIL") ||
                output.contains("**Critical:**") && hasNonZeroCritical(output)

            val statusFile = workspacePath.resolve(".review-status")
            Files.writeString(statusFile, if (hasFailures) "fail" else "pass")

            val attemptFile = workspacePath.resolve(".review-attempt")
            val attempt = if (Files.exists(attemptFile)) {
                (runCatching { Files.readString(attemptFile).trim().toInt() }.getOrNull() ?: 0) + 1
            } else 1
            Files.writeString(attemptFile, attempt.toString())

            events.trySend(AgentEvent.SessionStarted(
                threadId = UUID.randomUUID().toString(),
                turnId = "1",
                pid = pid
            ))
            events.trySend(AgentEvent.TurnCompleted(
                threadId = UUID.randomUUID().toString(),
                turnId = "1",
                usage = null,
                pid = pid
            ))
        } catch (e: Exception) {
            logger.warn("claude_review_failed", emptyMap(), "error" to (e.message ?: "unknown"))
            events.trySend(AgentEvent.TurnFailed(
                threadId = "?", turnId = "?",
                error = e.message ?: "claude_review_failed", pid = pid
            ))
        }
    }

    private fun hasNonZeroCritical(output: String): Boolean {
        val lines = output.lines()
        var inCritical = false
        for (line in lines) {
            if (line.contains("**Critical:**")) {
                val count = line.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (count != null && count > 0) return true
            }
        }
        return false
    }

    override fun isAlive(): Boolean = process?.isAlive == true

    override fun events(): Flow<AgentEvent> = events.receiveAsFlow()

    override fun stop() {
        try { process?.destroy() } catch (_: Exception) {}
        try { process?.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        workerJob?.cancel()
        events.close()
    }
}
