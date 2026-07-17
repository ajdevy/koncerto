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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.core.review.ReviewOutputParser
import com.flexsentlabs.koncerto.core.review.ReviewParseResult
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
    private val findingsJson = Json { prettyPrint = false; encodeDefaults = true }

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

            // Parse structured findings + usage from the (possibly JSON-enveloped) output.
            // Degrades to legacy verdict-string parsing on any failure (Epic 18, NFR-02).
            val parsed = ReviewOutputParser.parse(output, promptVersion = extractPromptVersion(prompt))

            // `.review-output` feeds the PR comment, so it must hold the human-readable review —
            // never the raw JSON envelope (--output-format json) or the machine findings block.
            val humanText = parsed.humanText.ifBlank { output }
            if (humanText.isNotBlank()) {
                humanText.lines().forEach { _output.tryEmit(it) }
            }

            val statusFile = workspacePath.resolve(".review-status")
            Files.writeString(statusFile, if (parsed.verdictPass) "pass" else "fail")
            Files.writeString(workspacePath.resolve(".review-output"), humanText)
            runCatching {
                Files.writeString(
                    workspacePath.resolve(".review-findings.json"),
                    findingsJson.encodeToString(ReviewParseResult.serializer(), parsed)
                )
            }.onFailure { logger.warn("claude_review_findings_write_failed", emptyMap(), "error" to (it.message ?: "?")) }

            logger.info("claude_review_parsed", mapOf(
                "verdict" to if (parsed.verdictPass) "pass" else "fail",
                "findings" to parsed.findings.size.toString(),
                "parse_status" to parsed.parseStatus.name,
                "input_tokens" to parsed.usage.inputTokens.toString(),
                "output_tokens" to parsed.usage.outputTokens.toString()
            ))

            val attemptFile = workspacePath.resolve(".review-attempt")
            val attempt = if (Files.exists(attemptFile)) {
                (runCatching { Files.readString(attemptFile).trim().toInt() }.getOrNull() ?: 0) + 1
            } else 1
            Files.writeString(attemptFile, attempt.toString())

            val usage = parsed.usage.takeIf { it.totalTokens > 0 }?.let {
                TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens)
            }

            events.trySend(AgentEvent.SessionStarted(
                threadId = UUID.randomUUID().toString(),
                turnId = "1",
                pid = pid
            ))
            events.trySend(AgentEvent.TurnCompleted(
                threadId = UUID.randomUUID().toString(),
                turnId = "1",
                usage = usage,
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

    /** Reads `version:` from the leading YAML frontmatter of the rendered prompt, if present. */
    internal fun extractPromptVersion(prompt: String): String? {
        val lines = prompt.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != "---") return null
        val versionRegex = Regex("""^version:\s*['"]?([^'"\s]+)['"]?\s*$""")
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trim() == "---") return null
            versionRegex.find(line.trim())?.let { return it.groupValues[1] }
        }
        return null
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
