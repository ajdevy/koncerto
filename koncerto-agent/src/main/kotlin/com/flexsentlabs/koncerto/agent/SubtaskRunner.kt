package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

interface SubtaskRunner {
    suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String = "opencode",
        command: String? = null,
        turnTimeoutMs: Long = 3_600_000L,
        stallTimeoutMs: Long = 300_000L
    ): EmptyResult<IllegalStateException>
}

class DefaultSubtaskRunner(
    private val logger: StructuredLogger,
    private val runtimeFactory: AgentRuntimeFactory? = null,
    private val heartbeatIntervalMs: Long = 30_000L
) : SubtaskRunner {
    override suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String,
        command: String?,
        turnTimeoutMs: Long,
        stallTimeoutMs: Long
    ): EmptyResult<IllegalStateException> = runCatching {
        val factory = runtimeFactory ?: AgentRuntimeFactory(logger)
        val effectiveCommand = command ?: kind
        val runtime = factory.create(kind, effectiveCommand, workspacePath)

        if (!runtime.start()) {
            throw IllegalStateException("subtask_agent_startup_failed")
        }

        try {
            withTimeout(turnTimeoutMs) {
                coroutineScope {
                    val lastOutputMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

                    val outputJob = launch {
                        runtime.output.collect { line ->
                            lastOutputMs.set(System.currentTimeMillis())
                        }
                    }

                    val stallJob = launch {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            val elapsed = System.currentTimeMillis() - lastOutputMs.get()
                            if (elapsed > stallTimeoutMs) {
                                throw IllegalStateException(
                                    "Subtask agent stalled (no output for ${elapsed}ms)"
                                )
                            }
                        }
                    }

                    val aliveJob = launch {
                        while (true) {
                            kotlinx.coroutines.delay(heartbeatIntervalMs)
                            if (!runtime.isAlive()) {
                                throw IllegalStateException("subtask_process_died")
                            }
                        }
                    }

                    runtime.send("initialize", null)
                    runtime.send("thread/start", buildJsonObject {
                        put("working_directory", workspacePath.toString())
                    })
                    runtime.send("turn/start", buildJsonObject {
                        put("input", prompt)
                    })

                    val turnDone = CompletableDeferred<Unit>()
                    val eventWatcher = launch {
                        runtime.events().collect { event ->
                            when (event) {
                                is AgentEvent.TurnCompleted,
                                is AgentEvent.TurnFailed -> turnDone.complete(Unit)
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
            throw IllegalStateException(e.message ?: "subtask_failed")
        }
    }

    private inline fun runCatching(block: () -> Unit): EmptyResult<IllegalStateException> {
        return try {
            block()
            com.flexsentlabs.koncerto.core.result.Result.Success(Unit)
        } catch (e: Exception) {
            com.flexsentlabs.koncerto.core.result.Result.Failure(
                IllegalStateException(e.message ?: "unknown_error")
            )
        }
    }
}