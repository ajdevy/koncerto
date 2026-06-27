package com.flexsentlabs.koncerto.workspace

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HookExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface HookExecutor {
    suspend fun run(workspacePath: Path, script: String)
}

class ShellHookExecutor(
    private val timeoutMs: Long,
    private val logger: StructuredLogger
) : HookExecutor {

    override suspend fun run(workspacePath: Path, script: String) {
        val pb = ProcessBuilder("bash", "-lc", script)
            .directory(workspacePath.toFile())
            .redirectErrorStream(true)
        val proc = pb.start()
        // Drain stdout on a separate thread before waitFor to prevent OS pipe-buffer deadlock
        // when hook output exceeds ~64 KB.
        val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            proc.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            outputFuture.cancel(true)
            logger.warn("hook_timeout", mapOf("workspace" to workspacePath.toString()))
            throw HookExecutionException("hook_timeout")
        }
        val output = outputFuture.get()
        if (proc.exitValue() != 0) {
            throw HookExecutionException("hook_exit_${proc.exitValue()}: ${output.take(2000)}")
        }
    }
}