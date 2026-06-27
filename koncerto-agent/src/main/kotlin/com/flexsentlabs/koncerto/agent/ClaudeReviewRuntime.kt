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
import kotlinx.coroutines.cancel
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
    private val logger: StructuredLogger
) : AgentRuntime {

    private var process: Process? = null
    private var workerJob: Job? = null
    private val events = Channel<AgentEvent>(Channel.BUFFERED)
    private val _output = MutableSharedFlow<String>(replay = 32, extraBufferCapacity = 64)
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

    override fun writeRaw(data: String) {}

    override fun closeStdin() {}

    override fun sendMessage(toAgentId: String, payload: String): String = "ok"

    private suspend fun runReview(prompt: String) = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("bash", "-lc", command)
                .directory(workspacePath.toFile())
                .redirectErrorStream(true)
            ClaudeAuthSupport.applyToken(pb)
            val p = pb.start()
            process = p
            pid = p.pid()
            logger.info("claude_review_started", mapOf("pid" to pid.toString()))

            p.outputStream.bufferedWriter().use { writer ->
                writer.write(prompt)
                writer.flush()
            }
            p.outputStream.close()

            val raw = p.inputStream.bufferedReader().use { it.readText() }
            val output = raw.lines()
                .filter { line ->
                    line !in listOf("", " ") &&
                        !line.startsWith("Claude configuration file not found") &&
                        !line.startsWith("A backup file exists at:") &&
                        !line.startsWith("You can manually restore")
                }
                .joinToString("\n")
                .trim()
            if (output.isNotBlank()) {
                output.lines().forEach { _output.tryEmit(it) }
            }

            val completed = p.waitFor(5, TimeUnit.MINUTES)
            if (!completed) {
                p.destroyForcibly()
                logger.warn("claude_review_timed_out", mapOf("pid" to pid.toString()))
                events.trySend(AgentEvent.TurnFailed("?", "?", "review_timed_out", pid))
                return@withContext
            }
            val exitCode = p.exitValue()
            logger.info("claude_review_completed", mapOf(
                "pid" to pid.toString(),
                "exit_code" to exitCode.toString(),
                "output_bytes" to output.length.toString()
            ))

            val hasFailures = output.contains("❌ FAIL")

            val statusFile = workspacePath.resolve(".review-status")
            Files.writeString(statusFile, if (hasFailures) "fail" else "pass")
            Files.writeString(workspacePath.resolve(".review-output"), output)

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

    internal fun hasNonZeroCritical(output: String): Boolean {
        val countRegex = Regex("""\*\*Critical:\*\*\s*(\d+)""")
        return output.lines().any { line ->
            countRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true
        }
    }

    override fun isAlive(): Boolean = process?.isAlive == true

    override fun events(): Flow<AgentEvent> = events.receiveAsFlow()

    override fun stop() {
        try { process?.destroy() } catch (_: Exception) {}
        try { process?.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        workerJob?.cancel()
        scope.cancel()
        events.close()
    }
}
